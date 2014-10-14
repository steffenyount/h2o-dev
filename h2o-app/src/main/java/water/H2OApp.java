package water;

import hex.ModelBuilder;
import hex.api.DeepLearningBuilderHandler;
import hex.api.ExampleBuilderHandler;
import hex.api.GBMBuilderHandler;
import hex.api.GLMBuilderHandler;
import hex.api.KMeansBuilderHandler;
import hex.deeplearning.DeepLearning;
import hex.example.Example;
import hex.gbm.GBM;
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
    H2O.registerGET("/Example",hex.schemas.ExampleHandler.class,"train","/Example","Example","Model");
    H2O.registerGET("/DeepLearning",hex.schemas.DeepLearningHandler.class,"train","/DeepLearning","Deep Learning","Model");
    H2O.registerGET("/GLM",hex.schemas.GLMHandler.class,"train","/GLM","GLM","Model");
    H2O.registerGET("/KMeans",hex.schemas.KMeansHandler.class,"train","/KMeans","KMeans","Model");
    H2O.registerGET("/GBM",hex.schemas.GBMHandler.class,"train","/GBM","GBM","Model");

    /////////////////////////////////////////////////////////////////////////////////////////////
    // Register the algorithms and their builder handlers:
    ModelBuilder.registerModelBuilder("gbm", GBM.class);
    H2O.registerPOST("/2/ModelBuilders/gbm", GBMBuilderHandler.class, "train");
    H2O.registerPOST("/2/ModelBuilders/gbm/parameters", GBMBuilderHandler.class, "validate_parameters");

    ModelBuilder.registerModelBuilder("kmeans", KMeans.class);
    H2O.registerPOST("/2/ModelBuilders/kmeans", KMeansBuilderHandler.class, "train");
    H2O.registerPOST("/2/ModelBuilders/kmeans/parameters", KMeansBuilderHandler.class, "validate_parameters");

    ModelBuilder.registerModelBuilder("deeplearning", DeepLearning.class);
    H2O.registerPOST("/2/ModelBuilders/deeplearning", DeepLearningBuilderHandler.class, "train");
    H2O.registerPOST("/2/ModelBuilders/deeplearning/parameters", DeepLearningBuilderHandler.class, "validate_parameters");

    ModelBuilder.registerModelBuilder("glm", GLM.class);
    H2O.registerPOST("/2/ModelBuilders/glm", GLMBuilderHandler.class, "train");

    ModelBuilder.registerModelBuilder("example", Example.class);
    H2O.registerPOST("/2/ModelBuilders/example", ExampleBuilderHandler.class, "train");

    // Done adding menu items; fire up web server
    H2O.finalizeRequest();
  }
}
