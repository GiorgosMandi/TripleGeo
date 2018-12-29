package eu.slipo.athenarc.triplegeo.tools;

import eu.slipo.athenarc.triplegeo.utils.*;
import org.geotools.factory.Hints;
import org.geotools.referencing.CRS;
import org.geotools.referencing.ReferencingFactoryFinder;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import scala.Tuple2;

import java.util.Iterator;
import java.util.List;
import java.util.Map;


public class MapToRdf {

    Converter myConverter;
    Assistant myAssistant;
    private MathTransform reproject = null;
    int sourceSRID;                            //Source CRS according to EPSG
    int targetSRID;                            //Target CRS according to EPSG
    private Configuration currentConfig;       //User-specified configuration settings
    private Classification classification;     //Classification hierarchy for assigning categories to features
    String outputFile;                         //Output RDF file

    private Iterator<Tuple2<String, Map>> data;


    //Initialize a CRS factory for possible reprojections
    private static final CRSAuthorityFactory crsFactory = ReferencingFactoryFinder
            .getCRSAuthorityFactory("EPSG", new Hints(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE));

    public MapToRdf(Configuration config, Classification classific, String outFile, int sourceSRID, int targetSRID, Iterator<Tuple2<String, Map>> input) throws ClassNotFoundException {

        this.currentConfig = config;
        this.classification = classific;
        this.outputFile = outFile;
        this.sourceSRID = sourceSRID;
        this.targetSRID = targetSRID;
        myAssistant = new Assistant();
        this.data = input;
        //Check if a coordinate transform is required for geometries
        if (currentConfig.targetCRS != null)
        {
            try {
                boolean lenient = true; // allow for some error due to different datums
                CoordinateReferenceSystem sourceCRS = crsFactory.createCoordinateReferenceSystem(currentConfig.sourceCRS);
                CoordinateReferenceSystem targetCRS = crsFactory.createCoordinateReferenceSystem(currentConfig.targetCRS);
                reproject = CRS.findMathTransform(sourceCRS, targetCRS, lenient);
            } catch (Exception e) {
                ExceptionHandler.abort(e, "Error in CRS transformation (reprojection) of geometries.");      //Execution terminated abnormally
            }
        }
        else  //No transformation specified; determine the CRS of geometries
        {
            if (sourceSRID == 0)
                this.targetSRID = 4326;          //All features assumed in WGS84 lon/lat coordinates
            else
                this.targetSRID = sourceSRID;    //Retain original CRS
        }
        // Other parameters
        if (myAssistant.isNullOrEmpty(currentConfig.defaultLang)) {
            currentConfig.defaultLang = "en";
        }
    }


    /*
     * Applies transformation according to the configuration settings.
     */
    public void apply()
    {
        try {
             if (currentConfig.mode.contains("STREAM"))
            {
                //Mode STREAM: consume records and streamline them into a serialization file
                myConverter =  new StreamConverter(currentConfig, outputFile);
               while(data.hasNext()) {
                   Tuple2 tuple = data.next();
                   String wkt = (String) tuple._1;
                   Map <String, String> userData = (Map<String, String>) tuple._2;
                   String geometryMode = wkt.split(" ")[0];
                   //Export data in a streaming fashion
                   myConverter.parse(myAssistant, wkt, userData, classification, targetSRID,geometryMode);
                }
            }
        } catch (Exception e) {
            ExceptionHandler.abort(e, "");
        }
    }
}
