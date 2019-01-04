package eu.slipo.athenarc.triplegeo;

import com.linuxense.javadbf.DBFField;
import com.linuxense.javadbf.DBFReader;
import com.vividsolutions.jts.geom.Geometry;
import eu.slipo.athenarc.triplegeo.tools.MapToRdf;
import eu.slipo.athenarc.triplegeo.utils.*;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.TaskContext;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.serializer.KryoSerializer;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.datasyslab.geospark.formatMapper.shapefileParser.ShapefileReader;
import org.datasyslab.geospark.serde.GeoSparkKryoRegistrator;
import org.datasyslab.geospark.spatialRDD.SpatialRDD;
import scala.collection.mutable.WrappedArray;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.*;

public class SparkTask {

    //they must be static in order to be serializable
    private static Configuration currentConfig;
    private static Classification classification;

    public  SparkTask(Configuration config, Classification classific, String inFile, String outFile, int sourceSRID, int targetSRID) {
        currentConfig = config;
        classification = classific;

        String currentFormat = currentConfig.inputFormat.toUpperCase();           //Possible values: SHAPEFILE, DBMS, CSV, GPX, GEOJSON, JSON, OSM_XML, OSM_PBF, XML


        int num_partitions = currentConfig.partitions != 0 ? config.partitions : 3;

        // spark's part
        SparkConf conf = new SparkConf().setAppName("SparkTripleGeo")
                .setMaster("local[*]")
                .set("spark.hadoop.validateOutputSpecs", "false")
                .set("spark.serializer", KryoSerializer.class.getName())
                .set("spark.kryo.registrator", GeoSparkKryoRegistrator.class.getName());

        SparkSession session = SparkSession
                .builder()
                .appName("SparkTripleGeo")
                .config(conf)
                .getOrCreate();
        SparkContext sc = session.sparkContext();
        JavaSparkContext jsc = JavaSparkContext.fromSparkContext(sc);

        try {
            //Apply data transformation according to the given input format
            if (currentFormat.trim().contains("SHAPEFILE")) {
                InputChecker ic = new InputChecker(inFile);
                if (!ic.check()) {
                    throw new IllegalArgumentException(ic.getError());
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
                spatialRDD.rawSpatialRDD = ShapefileReader.readToGeometryRDD(jsc, inFile);

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
                            int partition_index = TaskContext.getPartitionId();
                            String partitions_outputFile = new StringBuilder(outFile).insert(outFile.lastIndexOf("."), "_" + partition_index).toString();

                            MapToRdf conv = new MapToRdf(currentConfig, classification, partitions_outputFile, sourceSRID, targetSRID, map_iter);
                            conv.apply();
                        });

            }
            else if (currentFormat.trim().contains("CSV")) {
                Dataset df = session.read()
                        .format("csv")
                        .option("header", "true")
                        .option("delimiter", String.valueOf(currentConfig.delimiter))
                        .csv(inFile.split(";"));

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
                            int partition_index = TaskContext.getPartitionId();
                            String partitions_outputFile = new StringBuilder(outFile).insert(outFile.lastIndexOf("."), "_" + partition_index).toString();

                            MapToRdf conv = new MapToRdf(currentConfig, classification, partitions_outputFile, sourceSRID, targetSRID, map_iter);
                            conv.apply();
                        });
            }
            else if (currentFormat.trim().contains("GEOJSON")) {
                Dataset df = session.read()
                        .option("multiLine", true)
                        .json(inFile.split(";"));

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
                            int partition_index = TaskContext.getPartitionId();
                            String partitions_outputFile = new StringBuilder(outFile).insert(outFile.lastIndexOf("."), "_" + partition_index).toString();

                            MapToRdf conv = new MapToRdf(currentConfig, classification, partitions_outputFile, sourceSRID, targetSRID, map_iter);
                            conv.apply();
                        });
            }
            else {
                throw new IllegalArgumentException(Constants.INCORRECT_SETTING);
            }
        } catch (Exception e) {
            ExceptionHandler.abort(e, Constants.INCORRECT_SETTING);      //Execution terminated abnormally
        }
    }
}
