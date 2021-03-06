package water;

import hex.ModelBuilder;
import hex.api.DeepLearningBuilderHandler;
import hex.api.ExampleBuilderHandler;
import hex.api.GLMBuilderHandler;
import hex.api.KMeansBuilderHandler;
import hex.deeplearning.DeepLearning;
import hex.example.Example;
import hex.glm.GLM;
import hex.kmeans.KMeans;

import java.io.File;

public class H2OApp {
  public static void main2( String relpath ) { driver(new String[0],relpath); }

  public static void main( String[] args  ) { driver(args,System.getProperty("user.dir")); }

  private static void driver( String[] args, String relpath ) {

    // Fire up the H2O Cluster
    H2O.main(args);

    // Register REST API
    register(relpath);
  }

  static void register(String relpath) {

    H2O.registerResourceRoot(new File(relpath + File.separator + "h2o-web/src/main/resources/www"));
    H2O.registerResourceRoot(new File(relpath + File.separator + "h2o-core/src/main/resources/www"));

    // Register menu items and service handlers for algos
    H2O.registerGET("/DeepLearning",hex.schemas.DeepLearningHandler.class,"train","/DeepLearning","Deep Learning","Model","Train a Deep Learning model on the specified Frame.");
    H2O.registerGET("/GLM",hex.schemas.GLMHandler.class,"train","/GLM","GLM","Model","Train a GLM model on the specified Frame.");
    H2O.registerGET("/KMeans",hex.schemas.KMeansHandler.class,"train","/KMeans","KMeans","Model","Train a KMeans model on the specified Frame.");
    H2O.registerGET("/Example",hex.schemas.ExampleHandler.class,"train","/Example","Example","Model","Train an Example model on the specified Frame.");

    // An empty Job for testing job polling
    // TODO: put back:
    // H2O.registerGET("/SlowJob", SlowJobHandler.class, "work", "/SlowJob", "Slow Job", "Model");

    /////////////////////////////////////////////////////////////////////////////////////////////
    // Register the algorithms and their builder handlers:
    ModelBuilder.registerModelBuilder("kmeans", KMeans.class);
    H2O.registerPOST("/2/ModelBuilders/kmeans", KMeansBuilderHandler.class, "train","Train a KMeans model on the specified Frame.");

    ModelBuilder.registerModelBuilder("deeplearning", DeepLearning.class);
    H2O.registerPOST("/2/ModelBuilders/deeplearning", DeepLearningBuilderHandler.class, "train","Train a Deep Learning model on the specified Frame.");
    H2O.registerPOST("/2/ModelBuilders/deeplearning/parameters", DeepLearningBuilderHandler.class, "validate_parameters","Validate a set of Deep Learning model builder parameters.");

    ModelBuilder.registerModelBuilder("glm", GLM.class);
    H2O.registerPOST("/2/ModelBuilders/glm", GLMBuilderHandler.class, "train","Train a GLM model on the specified Frame.");

    ModelBuilder.registerModelBuilder("example", Example.class);
    H2O.registerPOST("/2/ModelBuilders/example", ExampleBuilderHandler.class, "train","Train an Example model on the specified Frame.");

    // Done adding menu items; fire up web server
    H2O.finalizeRequest();
  }
}
