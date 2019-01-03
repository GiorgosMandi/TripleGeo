package eu.slipo.athenarc.triplegeo;


//Execute
// spark-submit --class eu.slipo.athenarc.triplegeo.SparkExtractor --master local[3] ~/Documents/DIT/Thesis/TripleGEO_Extensions/SparkTripleGeo/target/triplegeo-1.6-SNAPSHOT.jar  test/conf/spark_shp_options.conf
// java -cp ~/Documents/DIT/Thesis/TripleGEO_Extensions/SparkTripleGeo/target/triplegeo-1.6-SNAPSHOT.jar eu.slipo.athenarc.triplegeo.Extractor test/conf/shp_options.conf


import com.linuxense.javadbf.DBFField;
import com.linuxense.javadbf.DBFReader;
import com.vividsolutions.jts.geom.Geometry;
import eu.slipo.athenarc.triplegeo.tools.MapToRdf;
import eu.slipo.athenarc.triplegeo.utils.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.TaskContext;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.*;
import org.apache.spark.serializer.KryoSerializer;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.datasyslab.geospark.formatMapper.shapefileParser.ShapefileReader;
import org.datasyslab.geospark.serde.GeoSparkKryoRegistrator;
import org.datasyslab.geospark.spatialRDD.SpatialRDD;
import org.apache.spark.sql.functions;
import scala.collection.mutable.WrappedArray;


import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.*;



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

            //----------------------------------------------------------------------------------------------------------
            //----------------------------------------------------------------------------------------------------------

            String currentFormat = currentConfig.inputFormat.toUpperCase();
            int num_partitions = currentConfig.partitions != 0 ? currentConfig.partitions : 3;

            // spark's part
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

            if (currentFormat.equals("SHAPEFILE")) {
                InputChecker ic = new InputChecker(inputFile);
                if (!ic.check()) {
                    System.out.println(ic.getError());
                    System.exit(0);
                }

                String dbf_file = ic.getFile("dbf");
                DBFReader dbf_reader;
                List<String> dbf_fields = new ArrayList<String>();
                try {
                    dbf_reader = new DBFReader(new FileInputStream(dbf_file));
                    int numberOfFields = dbf_reader.getFieldCount();
                    for (int i = 0; i < numberOfFields; i++) {
                        DBFField field = dbf_reader.getField(i);
                        dbf_fields.add(field.getName());
                    }
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }

                SpatialRDD spatialRDD = new SpatialRDD<Geometry>();
                spatialRDD.rawSpatialRDD = ShapefileReader.readToGeometryRDD(jsc, inputFile);

                spatialRDD.rawSpatialRDD
                        .repartition(num_partitions)
                        .map((Function<Geometry, Map>) geometry -> {
                            String[] userData = geometry.getUserData().toString().split("\t");
                            String wkt = geometry.toText();
                            Map<String, String> map = new HashMap<>();
                            map.put("wkt_geometry", wkt);
                            for (int i = 0; i < dbf_fields.size(); i++)
                                map.put(dbf_fields.get(i), userData[i]);
                            return map;
                        })
                        .foreachPartition((VoidFunction<Iterator<Map<String, String>>>) map_iter -> {
                            int partion_index = TaskContext.getPartitionId();
                            outputFiles.add(currentConfig.outputDir + FilenameUtils.getBaseName(inputFile) + myAssistant.getOutputExtension(currentConfig.serialization));
                            String outFile = outputFiles.get(outputFiles.size() - 1);
                            outFile = new StringBuilder(outFile).insert(outFile.lastIndexOf("."), "_" + partion_index).toString();

                            MapToRdf conv = new MapToRdf(currentConfig, classification, outFile, sourceSRID, targetSRID, map_iter);
                            conv.apply();
                        });
            }
            else if (currentFormat.equals("CSV")){

                Dataset df = session.read()
                        .format("csv")
                        .option("header", "true")
                        .option("delimiter", String.valueOf(currentConfig.delimiter))
                        .csv(inputFile.split(";"));

                String[] columns = df.columns();
                df.javaRDD()
                        .repartition(num_partitions)
                        .map((Function<Row, Map>) row -> {
                            Map<String,String> map = new HashMap<>();
                            for(int i=0; i<columns.length; i++)
                                map.put(columns[i], row.get(i).toString());
                            return map;
                        })
                        .foreachPartition((VoidFunction<Iterator<Map<String, String>>>) map_iter -> {
                            int partion_index = TaskContext.getPartitionId();
                            outputFiles.add(currentConfig.outputDir + FilenameUtils.getBaseName(inputFile) + myAssistant.getOutputExtension(currentConfig.serialization));
                            String outFile = outputFiles.get(outputFiles.size() - 1);
                            outFile = new StringBuilder(outFile).insert(outFile.lastIndexOf("."), "_" + partion_index).toString();

                            MapToRdf conv = new MapToRdf(currentConfig, classification, outFile, sourceSRID, targetSRID, map_iter);
                            conv.apply();
                        });
            }
            else if (currentFormat.equals("GEOJSON")){
                Dataset df = session.read()
                        .option("multiLine", true)
                        .json(inputFile.split(";"));

                Dataset featuresDF = df.withColumn("features", functions.explode(df.col("features")));
                Dataset DataDF = featuresDF.select(
                        featuresDF.col("features.properties").getItem(currentConfig.attrKey).as("key"),
                        featuresDF.col("features.properties").getItem(currentConfig.attrName).as("name"),
                        featuresDF.col("features.properties").getItem(currentConfig.attrCategory).as("category"),
                        featuresDF.col("features.geometry").getItem("type").as("geom_type"),
                        featuresDF.col("features.geometry").getItem("coordinates").as("coordinates")
                        );
                DataDF.show();
                String[] columns = DataDF.columns();

                DataDF.javaRDD()
                        .repartition(num_partitions)
                        .map((Function<Row, Map>) row -> {
                            Map<String,String> map = new HashMap<>();
                            String geomType = null;
                            for(int i=0; i<columns.length; i++) {
                                if (columns[i].equals("geom_type"))
                                    geomType = row.get(i).toString();
                                else if(columns[i].equals("coordinates")){
                                    String coord = ((WrappedArray)row.get(i)).mkString(" ");
                                    String wkt = geomType.toUpperCase() + " (" + coord + ")";
                                    map.put("wkt_geometry", wkt);
                                }
                                else {
                                    try {
                                        map.put(columns[i], row.get(i).toString());
                                    }catch (NullPointerException e){
                                        map.put(columns[i], null);
                                    }
                                }
                            }
                            return map;
                        })
                        .foreachPartition((VoidFunction<Iterator<Map<String, String>>>) map_iter -> {
                            int partion_index = TaskContext.getPartitionId();
                            outputFiles.add(currentConfig.outputDir + FilenameUtils.getBaseName(inputFile) + myAssistant.getOutputExtension(currentConfig.serialization));
                            String outFile = outputFiles.get(outputFiles.size() - 1);
                            outFile = new StringBuilder(outFile).insert(outFile.lastIndexOf("."), "_" + partion_index).toString();

                            MapToRdf conv = new MapToRdf(currentConfig, classification, outFile, sourceSRID, targetSRID, map_iter);
                            conv.apply();
                        });
            }
        }
    }
}
