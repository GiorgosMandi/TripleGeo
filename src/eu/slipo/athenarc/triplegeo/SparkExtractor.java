package eu.slipo.athenarc.triplegeo;


//Execute
// spark-submit --class eu.slipo.athenarc.triplegeo.SparkExtractor --master local[*] ~/Documents/DIT/Thesis/TripleGEO_Extensions/SparkTripleGeo/target/triplegeo-1.6-SNAPSHOT.jar  test/conf/spark_shp_options.conf
// java -cp ~/Documents/DIT/Thesis/TripleGEO_Extensions/SparkTripleGeo/target/triplegeo-1.6-SNAPSHOT.jar eu.slipo.athenarc.triplegeo.Extractor test/conf/shp_options.conf


/**
 * Entry point to TripleGeo for converting from various input formats (Spark EXECUTION)
 * @author Georgios Mandilaras
 */
import eu.slipo.athenarc.triplegeo.utils.*;
import org.apache.commons.io.FilenameUtils;
import java.util.*;



public class SparkExtractor {
    static Assistant myAssistant;
    private static Configuration currentConfig;         //Configuration settings for the transformation
    static Classification classification = null;        //Classification hierarchy for assigning categories to features
    static String inputFile;
    static List<String> outputFiles;
    static int sourceSRID;                              //Source CRS according to EPSG
    static int targetSRID;                              //Target CRS according to EPSG

    /**
     * Main entry point to execute the transformation process.
     * @param args  Arguments for the execution, including the path to a configuration file.
     * @throws InterruptedException
     */
    public static void main(String[] args)  {
        System.out.println(Constants.COPYRIGHT);
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
                if (currentConfig.sourceCRS != null) {
                    //Needed for parsing original geometry in WTK representation
                    sourceSRID = Integer.parseInt(currentConfig.sourceCRS.substring(currentConfig.sourceCRS.indexOf(':') + 1, currentConfig.sourceCRS.length()));
                } else
                    sourceSRID = 0;                //Non-specified

                if (currentConfig.targetCRS != null) {
                    //Needed for parsing original geometry in WTK representation
                    targetSRID = Integer.parseInt(currentConfig.targetCRS.substring(currentConfig.targetCRS.indexOf(':') + 1, currentConfig.targetCRS.length()));
                    System.out.println("Transformation will take place from " + currentConfig.sourceCRS + " to " + currentConfig.targetCRS + " reference system.");
                } else {   //No transformation will take place
                    //CAUTION! Not always safe to assume that features are georeferenced in WGS84 lon/lat coordinates
                    targetSRID = 0;                //Non-specified
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
            } finally {
                if (classification != null)
                    System.out.println(myAssistant.getGMTime() + " Classification hierarchy read successfully!");
                else if (!currentConfig.inputFormat.contains("OSM"))
                    System.out.println("No classification hierarchy specified for features to be extracted.");
            }

            //----------------------------------------------------------------------------------------------------------
            //----------------------------------------------------------------------------------------------------------

            outputFiles.add(currentConfig.outputDir + FilenameUtils.getBaseName(inputFile) + myAssistant.getOutputExtension(currentConfig.serialization));
            for (int i=0; i< outputFiles.size(); i++)
                outputFiles.set(i, outputFiles.get(i).replaceAll("[0-9]", ""));
            String outFile = outputFiles.get(outputFiles.size() - 1);

            long start = System.currentTimeMillis();

            new SparkTask(currentConfig, classification, inputFile, outFile, sourceSRID, targetSRID);

            long elapsed = System.currentTimeMillis() - start;

            System.out.println(myAssistant.getGMTime() + String.format(" Transformation process concluded successfully in %d ms.", elapsed));
            //Assistant.mergeFiles(outputFiles, "C:/Development/Java/workspace/TripleGeo/test/output/merged_output.rdf");
            System.exit(0);          //Execution completed successfully
        }
        else {
            System.err.println(Constants.INCORRECT_CONFIG);
            System.exit(1);          //Execution terminated abnormally
        }
    }
}
