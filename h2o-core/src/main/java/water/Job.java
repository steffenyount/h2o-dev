package water;

import jsr166y.CountedCompleter;
import water.H2O.H2OCountedCompleter;
import water.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

/** Jobs are used to do minimal tracking of long-lifetime user actions,
 *  including progress-bar updates and the ability to review in progress or
 *  completed Jobs, and cancel currently running Jobs.
 *  <p>
 *  Jobs are {@link Keyed}, because they need to Key to control e.g. atomic updates.
 *  Jobs produce a {@link Keyed} result, such as a Frame (from Parsing), or a Model.
 */
public class Job<T extends Keyed> extends Keyed {
  /** A system key for global list of Job keys. */
  static final Key LIST = Key.make(" JobList", (byte) 0, Key.BUILT_IN_KEY, false);
  private static class JobList extends Keyed { 
    Key[] _jobs;
    JobList() { super(LIST); _jobs = new Key[0]; }
    private JobList(Key[]jobs) { super(LIST); _jobs = jobs; }
    public long checksum() { /* TODO: something better? */ return (long) Arrays.hashCode(_jobs);}
  }

  /** The list of all Jobs, past and present. 
   *  @return The list of all Jobs, past and present */
  public static Job[] jobs() {
    Value val = DKV.get(LIST);
    if( val==null ) return new Job[0];
    JobList jl = val.get();
    Job[] jobs = new Job[jl._jobs.length];
    int j=0;
    for( int i=0; i<jl._jobs.length; i++ ) {
      val = DKV.get(jl._jobs[i]);
      if( val != null ) jobs[j++] = val.get();
    }
    if( j==jobs.length ) return jobs; // All jobs still exist
    jobs = Arrays.copyOf(jobs,j);     // Shrink out removed 
    Key keys[] = new Key[j];
    for( int i=0; i<j; i++ ) keys[i] = jobs[i]._key;
    // One-shot throw-away attempt at remove dead jobs from the jobs list
    DKV.DputIfMatch(LIST,val,new Value(LIST,new JobList(keys)),new Futures());
    return jobs;
  }

  transient H2OCountedCompleter _fjtask; // Top-level task to do
  transient H2OCountedCompleter _barrier;// Top-level task you can block on

  /** Jobs produce a single DKV result into Key _dest */
  public final Key _dest;   // Key for result
  /** Since _dest is public final, not sure why we have a getter but some
   *  people like 'em. */
  public final Key dest() { return _dest; }

  /** User description */
  public final String _description;
  /** Job start_time using Sys.CTM */
  public long _start_time;     // Job started
  /** Job end_time using Sys.CTM, or 0 if not ended */
  public long   _end_time;     // Job end time, or 0 if not ended
  /** Any exception thrown by this Job, or null if none */
  public String _exception;    // Unpacked exception & stack trace

  /** Possible job states. */
  public static enum JobState {
    CREATED,   // Job was created
    RUNNING,   // Job is running
    CANCELLED, // Job was cancelled by user
    FAILED,    // Job crashed, error message/exception is available
    DONE       // Job was successfully finished
  }

  public JobState _state;

  /** Returns true if the job was cancelled by the user or crashed.
   *  @return true if the job is in state {@link JobState#CANCELLED} or {@link JobState#FAILED} */
  public boolean isCancelledOrCrashed() {
    return _state == JobState.CANCELLED || _state == JobState.FAILED;
  }

  /** Returns true if this job is running
   *  @return returns true only if this job is in running state. */
  public boolean isRunning() { return _state == JobState.RUNNING; }
  /** Returns true if this job is done
   *  @return  true if the job is in state {@link JobState#DONE} */
  public boolean isDone   () { return _state == JobState.DONE   ; }

  /** Returns true if this job was started and is now stopped */
  public boolean isStopped() { return _state == JobState.DONE || isCancelledOrCrashed(); }

  /** Check if given job is running.
   *  @param job_key job key
   *  @return true if job is still running else returns false.  */
  public static boolean isRunning(Key job_key) { return job_key.<Job>get().isRunning(); }

  /** Current runtime; zero if not started */
  public final long msec() {
    switch( _state ) {
    case CREATED: return 0;
    case RUNNING: return System.currentTimeMillis() - _start_time;
    default:      return _end_time                  - _start_time;
    }
  }

  protected Job(Key jobKey, Key dest, String desc) {
    super(jobKey);
    _description = desc;
    _dest = dest;
    _state = JobState.CREATED;  // Created, but not yet running
  }
  /** Create a Job
   *  @param dest Final result Key to be produced by this Job
   *  @param desc String description
   */
  public Job(Key dest, String desc) {
    this(defaultJobKey(),dest,desc);
  }
  // Job Keys are pinned to this node (i.e., the node that invoked the
  // computation), because it should be almost always updated locally
  private static Key defaultJobKey() { return Key.make((byte) 0, Key.JOB, false, H2O.SELF); }


  /** Start this task based on given top-level fork-join task representing job computation.
   *  @param fjtask top-level job computation task.
   *  @param work Units of work to be completed
   *  @return this job in {@link JobState#RUNNING} state
   *  
   *  @see JobState
   *  @see H2OCountedCompleter
   */
  public Job start(final H2OCountedCompleter fjtask, long work) {
    DKV.put(_progressKey = Key.make(), new Progress(work));
    assert _state == JobState.CREATED : "Trying to run job which was already run?";
    assert fjtask != null : "Starting a job with null working task is not permitted!";
    assert fjtask.getCompleter() == null : "Cannot have a completer; this must be a top-level task";
    _fjtask = fjtask;

    // Make a wrapper class that only *starts* when the fjtask completes -
    // especially it only starts even when fjt completes exceptionally... thus
    // the fjtask onExceptionalCompletion code runs completely before this
    // empty task starts - providing a simple barrier.  Threads blocking on the
    // job will block on the "barrier" task, which will block until the fjtask
    // runs the onCompletion or onExceptionCompletion code.
    _barrier = new H2OCountedCompleter() {
        @Override public void compute2() { }
        @Override public boolean onExceptionalCompletion(Throwable ex, CountedCompleter caller) { 
          if( getCompleter() == null ) { // nobody else to handle this exception, so print it out
            System.err.println("barrier onExCompletion for "+fjtask);
            ex.printStackTrace();
          }
          return true;
        }
      };
    fjtask.setCompleter(_barrier);
    _start_time = System.currentTimeMillis();
    _state      = JobState.RUNNING;
    // Save the full state of the job
    DKV.put(_key, this);
    // Update job list
    final Key jobkey = _key;
    new TAtomic<JobList>() {
      @Override public JobList atomic(JobList old) {
        if( old == null ) old = new JobList();
        Key[] jobs = old._jobs;
        old._jobs = Arrays.copyOf(jobs, jobs.length + 1);
        old._jobs[jobs.length] = jobkey;
        return old;
      }
    }.invoke(LIST);
    H2O.submitTask(fjtask);
    return this;
  }

  /** Blocks and get result of this job.
   * <p>
   * This call blocks on working task which was passed via {@link #start}
   * method and returns the result which is fetched from UKV based on job
   * destination key.
   * </p>
   * @return result of this job fetched from UKV by destination key.
   * @see #start
   * @see DKV
   */
  public T get() {
    assert _fjtask != null : "Cannot block on missing F/J task";
    _barrier.join(); // Block on the *barrier* task, which blocks until the fjtask on*Completion code runs completely
    assert !isRunning();
    return _dest.get();
  }

  /** Marks job as finished and records job end time. */
  public void done() {
    cancel(null,JobState.DONE);
  }

  /** Signal cancellation of this job.
   * <p>The job will be switched to state {@link JobState#CANCELLED} which signals that
   * the job was cancelled by a user. */
  public void cancel() {
    cancel(null, JobState.CANCELLED);
  }

  /** Signal exceptional cancellation of this job.
   *  @param ex exception causing the termination of job. */
  public void cancel2(Throwable ex) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    ex.printStackTrace(pw);
    String stackTrace = sw.toString();
    cancel("Got exception '" + ex.getClass() + "', with msg '" + ex.getMessage() + "'\n" + stackTrace, JobState.FAILED);
    //if(_fjtask != null && !_fjtask.isDone()) _fjtask.completeExceptionally(ex);
  }

  /** Signal exceptional cancellation of this job.
   *  @param msg cancellation message explaining reason for cancelation */
  public void cancel(final String msg) {
    cancel(msg, msg == null ? JobState.CANCELLED : JobState.FAILED);
  }

  private void cancel(final String msg, final JobState resultingState ) {
    assert resultingState != JobState.RUNNING;
    if( _state == JobState.CANCELLED ) Log.info("Canceled job " + _key + "("  + _description + ") was cancelled again.");
    if( _state == resultingState ) return; // No change if already done
    _finalProgress = resultingState==JobState.DONE ? 1.0f : progress_impl(); // One-shot set from NaN to progress, no longer need Progress Key

    final long done = System.currentTimeMillis();
    _exception = msg;
    _state = resultingState;
    _end_time = done;
    // Atomically flag the job as canceled
    new TAtomic<Job>() {
      @Override public Job atomic(Job old) {
        if( old == null ) return null; // Job already removed
        if( old._state == resultingState ) return null; // Job already canceled/crashed
        if( !isCancelledOrCrashed() && old.isCancelledOrCrashed() ) return null;
        // Atomically capture cancel/crash state, plus end time
        old._exception = msg;
        old._state = resultingState;
        old._end_time = done;
        return old;
      }
      @Override void onSuccess( Job old ) {
        // Run the onCancelled code synchronously, right now
        if( isCancelledOrCrashed() )
          onCancelled();
      }
    }.invoke(_key);
    // Cleanup on a cancel (or remove)
    DKV.remove(_progressKey);
  }

  /**
   * Callback which is called after job cancellation (by user, by exception).
   */
  protected void onCancelled() {
  }

  /** Returns a float from 0 to 1 representing progress.  Polled periodically.  
   *  Can default to returning e.g. 0 always.  */
  public float progress() { return isStopped() ? _finalProgress : progress_impl(); }
  // Checks the DKV for the progress Key & object
  private float progress_impl() {
    return _progressKey == null || DKV.get(_progressKey) == null ? 0f : DKV.get(_progressKey).<Progress>get().progress(); 
  }
  protected Key _progressKey; //Key to store the Progress object under
  private float _finalProgress = Float.NaN; // Final progress after Job stops running

  /** Report new work done for this job */
  public final void update(final long newworked) { new ProgressUpdate(newworked).fork(_progressKey); }

  /** Report new work done for a given job key */
  public static void update(final long newworked, Key jobkey) {
    jobkey.<Job>get().update(newworked);
  }

  /**
   * Helper class to store the job progress in the DKV
   */
  public static class Progress extends Iced{
    private final long _work;
    private long _worked;
    public Progress(long total) { _work = total; }
    /** Report Job progress from 0 to 1.  Completed jobs are always 1.0 */
    public float progress() {
      return _work == 0 /*not yet initialized*/ ? 0f : Math.max(0.0f, Math.min(1.0f, (float)_worked / (float)_work));
    }
  }

  /**
   * Helper class to atomically update the job progress in the DKV
   */
  protected static class ProgressUpdate extends TAtomic<Progress> {
    final long _newwork;
    public ProgressUpdate(long newwork) { _newwork = newwork; }
    /** Update progress with new work */
    @Override public Progress atomic(Progress old) {
      if(old == null) return old;
      old._worked += _newwork;
      return old;
    }
  }

  /** Simple named exception class */
  public static class JobCancelledException extends RuntimeException{}

  @Override protected Futures remove_impl(Futures fs) {
    DKV.remove(_progressKey, fs);
    return fs;
  }

  /** Default checksum; not really used by Jobs.  */
  @Override public long checksum() {
    // Not really sure what should go here. . .
    // This isn't really being used for Job right now, so it's non-critical.
    return _description.hashCode() * (_dest == null ? 1 : _dest.hashCode()) * _start_time;
  }
}
