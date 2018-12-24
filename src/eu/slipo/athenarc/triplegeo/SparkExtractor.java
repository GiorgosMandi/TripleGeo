package eu.slipo.athenarc.triplegeo;


//Execute
// spark-submit --class eu.slipo.athenarc.triplegeo.SparkExtractor --master local[3] ~/Documents/DIT/Thesis/TripleGEO_Extensions/SparkTripleGeo/target/triplegeo-1.6-SNAPSHOT.jar  test/conf/shp_options.conf

import com.vividsolutions.jts.geom.Geometry;
import eu.slipo.athenarc.triplegeo.utils.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.TaskContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.serializer.KryoSerializer;
import org.apache.spark.sql.SparkSession;
import org.datasyslab.geospark.formatMapper.shapefileParser.ShapefileReader;
import org.datasyslab.geospark.serde.GeoSparkKryoRegistrator;
import org.datasyslab.geospark.spatialRDD.SpatialRDD;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;



public class SparkExtractor {
    static Assistant myAssistant;
    private static Configuration currentConfig;         //Configuration settings for the transformation
    static Classification classification = null;        //Classification hierarchy for assigning categories to features
    static String inputFile;
    static List<String> outputFiles;
    static int sourceSRID;                              //Source CRS according to EPSG
    static int targetSRID;                              //Target CRS according to EPSG

    public static void main(String[] args)  {
        System.out.println(Constants.COPYRIGHT);

        boolean failure = false;                       //Indicates whether at least one task has failed to conclude

        if (args.length >= 0) {

            myAssistant = new Assistant();

            //Specify a configuration file with properties used in the conversion
            currentConfig = new Configuration(args[0]);          //Argument like "./bin/shp_options.conf"

            //Conversion mode: (in-memory) STREAM or (disk-based) GRAPH or through RML mappings or via XSLT transformation
            System.out.println("Conversion mode: " + currentConfig.mode);

            //Issue copyright statement for RML processing modules
            if (currentConfig.mode.contains("RML"))
                System.out.println(Constants.RML_COPYRIGHT);

            //Force N-TRIPLES serialization in case that the STREAM mode is chosen
            if (currentConfig.mode.contains("STREAM"))
                currentConfig.serialization = "N-TRIPLES";

            //Force RDF/XML serialization in case that the XSLT mode is chosen (for XML/GML input)
            if (currentConfig.mode.contains("XSLT"))
                currentConfig.serialization = "RDF/XML";

            //Clean up temporary directory to be used in transformation when disk-based GRAPH mode is chosen
            if (currentConfig.mode.contains("GRAPH"))
                myAssistant.removeDirectory(currentConfig.tmpDir);

            System.out.println("Output serialization: " + currentConfig.serialization);

            System.setProperty("org.geotools.referencing.forceXY", "true");


            inputFile = currentConfig.inputFiles;     //MULTIPLE input file names separated by ;


            //Initialize collection of output files that will carry the resulting RDF triples
            outputFiles = new ArrayList<String>();

            //Check if a coordinate transform is required for geometries
            try {
                if (currentConfig.sourceCRS != null)
                {
                    //Needed for parsing original geometry in WTK representation
                    sourceSRID = Integer.parseInt(currentConfig.sourceCRS.substring( currentConfig.sourceCRS.indexOf(':')+1, currentConfig.sourceCRS.length()));
                }
                else
                    sourceSRID = 0;                //Non-specified

                if (currentConfig.targetCRS != null)
                {
                    //Needed for parsing original geometry in WTK representation
                    targetSRID = Integer.parseInt(currentConfig.targetCRS.substring( currentConfig.targetCRS.indexOf(':')+1, currentConfig.targetCRS.length()));
                    System.out.println("Transformation will take place from " + currentConfig.sourceCRS + " to " + currentConfig.targetCRS + " reference system.");
                }
                else
                {   //No transformation will take place
                    //CAUTION! Not always safe to assume that features are georeferenced in WGS84 lon/lat coordinates
                    targetSRID = 0; 				//Non-specified
                    System.out.println(Constants.NO_REPROJECTION);
                }
            } catch (Exception e) {
                ExceptionHandler.abort(e, "Please check SRID specifications in the configuration.");      //Execution terminated abnormally
            }

            //Check whether a classification hierarchy is specified in a separate file and apply transformation accordingly
            try {
                if ((currentConfig.classificationSpec != null) && (!currentConfig.inputFormat.contains("OSM")))    //Classification for OSM XML data is handled by the OSM converter
                {
                    String outClassificationFile = currentConfig.outputDir + FilenameUtils.getBaseName(currentConfig.classificationSpec) + myAssistant.getOutputExtension(currentConfig.serialization);
                    classification = new Classification(currentConfig, currentConfig.classificationSpec, outClassificationFile);
                    outputFiles.add(outClassificationFile);
                }
            }
            //Handle any errors that may have occurred during reading of classification hierarchy
            catch (Exception e) {
                ExceptionHandler.abort(e, "Failed to read classification hierarchy.");
            }
            finally {
                if (classification != null)
                    System.out.println(myAssistant.getGMTime() + " Classification hierarchy read successfully!");
                else if (!currentConfig.inputFormat.contains("OSM"))
                    System.out.println("No classification hierarchy specified for features to be extracted.");
            }

            System.out.println("\n\n\n\n\n\n\n\n");


            SparkConf conf = new SparkConf().setAppName("SparkTripleGeo")
                    .setMaster("local[*]")
                    .set("spark.hadoop.validateOutputSpecs", "false")
                    .set("spark.serializer", KryoSerializer.class.getName())
                    .set("spark.kryo.registrator", GeoSparkKryoRegistrator.class.getName());

            SparkSession session = SparkSession
                    .builder()
                    .appName("Java Spark SQL basic example")
                    .config("spark.some.config.option", "some-value")
                    .config(conf)
                    .getOrCreate();
            SparkContext sc = session.sparkContext();
            JavaSparkContext jsc = JavaSparkContext.fromSparkContext(sc);

            SpatialRDD spatialRDD = new SpatialRDD<Geometry>();
            System.out.println (inputFile);
            spatialRDD.rawSpatialRDD = ShapefileReader.readToGeometryRDD(jsc, inputFile);

            /*spatialRDD.rawSpatialRDD
                    .repartition(3)
                    .mapPartitionsWithIndex(new Function2<Integer, Iterator<String>, Iterator<String>>(){
                        public Iterator<String> call(Integer index, Iterator<String> iter){
                            String outFile = outputFiles.get(outputFiles.size()-1) + "_" + index;
                            new Task(currentConfig, classification, inputFile, outFile, sourceSRID, targetSRID);

                            return iter;
                        }
                }, true)
                .collect();
             */
            spatialRDD.rawSpatialRDD
                    .repartition(3)
                    .foreachPartition(new VoidFunction<Iterator>() {
                        @Override
                        public void call(Iterator iterator) throws Exception {
                            String outFile = outputFiles.get(outputFiles.size()-1);
                            outFile = new StringBuilder(outFile).insert(outFile.lastIndexOf(".") , "_" + TaskContext.getPartitionId()).toString();
                            new Task(currentConfig, classification, inputFile, outFile, sourceSRID, targetSRID);
                        }
                    });



        }
    }
}
