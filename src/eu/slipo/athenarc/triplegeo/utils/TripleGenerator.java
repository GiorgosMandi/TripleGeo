/*
 * @(#) TripleGenerator.java 	 version 1.6   26/10/2018
 *
 * Copyright (C) 2013-2018 Information Management Systems Institute, Athena R.C., Greece.
 *
 * This library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package eu.slipo.athenarc.triplegeo.utils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.vocabulary.RDF;

import com.vividsolutions.jts.geom.Geometry;

import eu.slipo.athenarc.triplegeo.utils.Mapping.mapProperties;

/**
 * Generates a collection of RDF triples from the (spatial & thematic) attributes of a given feature.
 * @author Kostas Patroumpas
 * @version 1.6
 */

/* DEVELOPMENT HISTORY
 * Created by: Kostas Patroumpas, 19/12/2017
 * Modified: 21/12/2017, added support for object and data properties according to the SLIPO ontology
 * Modified: 23/12/2017, added support for reading attribute mappings from YML file
 * Modified: 1/2/2018, added export of classification scheme for SLIPO
 * Modified: 7/2/2018, added export of all thematic attributes as triples with their original attribute name as RDF property
 * Modified: 14/2/2018; collecting attribute statistics on-the-fly while transforming each feature
 * Modified: 15/2/2018; added support for transforming thematic attributes according to a custom ontology specified in .YML
 * Modified: 21/2/2018; added support for calculated area and perimeter for geometries
 * Modified: 23/4/2018; added support for mapping of multi-lingual attribute values 
 * Modified: 11/5/2018; extra attributes on area, perimeter or length of geometries calculated in standard units (e.g., SI meters and square meters)
 * Modified: 27/7/2018; values in thematic (non-spatial) attributes get cleaned from special characters (e.g., newline, quotes, etc.) that may be problematic in the resulting triples
 * Modified: 27/7/2018; improved handling of URLs and language tags
 * Modified: 9/10/2018; allowing generation of URIs either using built-in functions or by retaining original IDs
 * Last modified: 26/10/2018
 */

public class TripleGenerator {

	Assistant myAssistant;
	ValueChecker myChecker;
	private static Configuration currentConfig;

	private List<Triple> results;          //Container of resulting triples

	Mapping attrMappings = null;           //Mapping of thematic attributes (input) to RDF predicates (output)
	Map<String, String> prefixes;          //Prefixes for namespaces employed during transformation and serialization of RDF triples
	String attrURI = null;                 //Attribute used for the URI of features, as specified in the mapping of thematic attributes
	String attrCategoryURI = null;         //Attribute used for the URI of categories, as specified in the mapping of thematic attributes
	String attrDataSource = null;          //Attribute used for the name of data source, as specified in the mapping of thematic attributes
	
	Map<String, Integer> attrStatistics;   //Statistics for each attribute
	
    /**
     * Constructs a TripleGenerator for transforming a feature (as a record of attributes) into RDF triples
     * @param config  User-specified configuration for the transformation process.
     */
	public TripleGenerator(Configuration config) {
		    
		myAssistant = new Assistant(config);
		myChecker = new ValueChecker();
		
	    currentConfig = config;       //Configuration parameters as set up by the various conversion utilities (CSV, SHP, DBMS, etc.) 
	    
	    results = new ArrayList<>();          //Holds a collection of RDF triples resulting from transformation
  
	    attrStatistics = new HashMap<String, Integer>();
	    
	    //Keep prefixes as specified in the configuration
	    prefixes = new HashMap<String, String>();
	    for (int i=0; i<currentConfig.prefixes.length; i++)
	    	prefixes.put(currentConfig.prefixes[i].trim(), currentConfig.namespaces[i].trim());
	    
	    //Attribute mappings should have been properly configured in a .YML file
	    if (currentConfig.mappingSpec != null)
	    {
		    attrMappings = new Mapping();

		    //Read mapping file from the path specified in configuration settings
		    attrMappings.createFromFile(currentConfig.mappingSpec); 
			
		    //Identify the extra attributes for category URIs and name of data source as specified in the mapping file
		    for (String key: attrMappings.getKeys())
		    {
		    	if ((attrMappings.find(key).entityType != null) && (attrMappings.find(key).entityType.equalsIgnoreCase("uri")))
		    		attrURI = key;
		    	if ((attrMappings.find(key).entityType != null) && (attrMappings.find(key).entityType.contains("category")))
		    		attrCategoryURI = key;
		    	if ((attrMappings.find(key).predicate != null) && (attrMappings.find(key).predicate.contains("sourceRef")))
		    		attrDataSource = key;
		    }
	    }
	    //Otherwise, give default names to these extra attributes
	    if (attrURI == null)
	    	attrURI = "URI";
	    if (attrCategoryURI == null)
	    	attrCategoryURI = "CATEGORY_URI";
	    if (attrDataSource == null)
	    	attrDataSource = "DATA_SOURCE";
	 }


	 /**
	  * Provides a collection of triples resulting from conversion (usually from a single input feature)
	  * @return  A list of RDF triples
	  */
	  public List<Triple> getTriples() {
		  
		  return results;	  
	  }

	  
	  /**
	   * Cleans up all triples resulted from conversion so far
	   */
	  public void clearTriples() {
		  
		  results.clear();	  
	  }
	    

	  /**
	   * Update statistics (currently only a counter) of values transformed for a particular attribute
	   * @param attrKey  The name of the attribute
	   */
	  private void updateStatistics(String attrKey) {
		  
			if ((attrStatistics.get(attrKey)) == null)
  				attrStatistics.put(attrKey, 1);                                  //First occurrence of this attribute
  			else
  				attrStatistics.replace(attrKey, attrStatistics.get(attrKey)+1);  //Update count of NOT NULL values for this attribute
	  }
	  

	  /**
	   * Provides the statistics collected during transformation (i.e., count of transformed values per attribute)
	   * @return  A Map containing attribute names (keys) and their respective counts (values)
	   */
	  public Map<String, Integer> getStatistics() {

		  return attrStatistics;
	  }
	  

	  /**
	   * Converts the given feature (a tuple of thematic attributes and its geometry WKT) into RDF triples
	   * @param row  Attribute values for each thematic (non-spatial) attribute
	   * @param wkt  Well-Known Text representation of the geometry  
	   * @param targetSRID  The EPSG identifier of the Coordinate Reference System of the geometry
	   * @param classific  The classification scheme used in the category assigned to the feature
	   * @return  The URI assigned to this feature and used in its resulting RDF triples
	   */
	  public String transform(Map<String,String> row, String wkt, int targetSRID, Classification classific) {

		String uri = null;	
		try {
			String uuid = null;
  	        //First, assign a URI to this feature
	        if (attrMappings != null) 
	        {
	        	if (attrMappings.find(attrURI) != null)
	        	{	//Generate URI according to the specified mapping using a built-in function
	        		List<String> argv = getArgValues(attrMappings.find(attrURI).getFunctionArguments(), row);
	        		uuid = (String) myAssistant.applyRuntimeMethod(attrMappings.find(attrURI).getGeneratorFunction(), argv.toArray(new Object[argv.size()]));
	        	}
	        	else   //No mapping specified for URIs, so generate a random UUID
	        		uuid = myAssistant.getRandomUUID();   

	        	//FIXME: Characters like "/" in URIs should not be encoded!
	        	String encodingResource = myChecker.replaceWhiteSpace(uuid);	  	    //URLEncoder.encode(uuid, Constants.UTF_8)  
	  	        uri = currentConfig.featureNS + encodingResource;
	        }
	        else
	        {
				//CAUTION! On-the-fly generation of a UUID for this feature, giving as seed the data source and the identifier of that feature
				uuid = myAssistant.getUUID(currentConfig.featureSource, row.get(currentConfig.attrKey)).toString();
				String encodingResource = myChecker.replaceWhiteSpace(URLEncoder.encode(uuid, Constants.UTF_8));	  	      
	  	        uri = currentConfig.featureNS + encodingResource;
	        }
	        
	        //Then, parse geometric representation (including encoding to the target CRS)
	        if (wkt != null)
	        {
		        //Detect geometry type from the WKT representation (i.e., getting the text before parentheses)
		  	  	String geomType = " ";
		  	  	int a = wkt.indexOf("(");
		  	  	if (a > 0)
		  	  		geomType = wkt.substring(0, a).trim();

		  	  	//Insert extra attributes derived from geometries
		  	  	if (attrMappings != null)
		  	  	{
			  	  	List<String> g;
			  	  	//Insert extra attributes concerning the CALCULATED area OR perimeter for polygons
			  	  	if (geomType.toUpperCase().contains("POLYGON"))
			  	  	{
			  	  		g = attrMappings.findExtraGeometricAttr("getArea");
			  	  		if (!g.isEmpty())
			  	  			row.put(g.get(0), (myAssistant.applyRuntimeMethod("getArea", new Object[]{wkt, targetSRID})).toString());
	
			  	  		g = attrMappings.findExtraGeometricAttr("getLength");
			  	  		if (!g.isEmpty())
			  	  			row.put(g.get(0), (myAssistant.applyRuntimeMethod("getLength", new Object[]{wkt, targetSRID})).toString());
			  	  	}
			  	  	//Insert an extra property concerning the CALCULATED length of linestrings
			  	  	else if (geomType.toUpperCase().contains("LINE"))
			  	  	{
			  	  		g = attrMappings.findExtraGeometricAttr("getLength");
			  	  		if (!g.isEmpty())
			  	  			row.put(g.get(0), (myAssistant.applyRuntimeMethod("getLength", new Object[]{wkt, targetSRID})).toString());
			  	  	}
			  	  	
			  	  	//Insert extra attributes concerning lon/lat coordinates for the centroid 
			  	  	Geometry geomProjected = myAssistant.geomTransformWGS84(wkt, targetSRID);
			  	  	g = attrMappings.findExtraGeometricAttr("getLongitude");
			  	  	if (!g.isEmpty())  		
			  	  	    row.put(g.get(0), myAssistant.applyRuntimeMethod("getLongitude", new Object[]{geomProjected}).toString());	  
			  	  	g = attrMappings.findExtraGeometricAttr("getLatitude");
			  	  	if (!g.isEmpty())
			  	  	    row.put(g.get(0), myAssistant.applyRuntimeMethod("getLatitude", new Object[]{geomProjected}).toString());		  	  
/*		  	  	
			  	    //ALTERNATIVE (NOT USED): Insert extra attributes concerning lon/lat coordinates for the centroid 
			  	  	g = attrMappings.findExtraGeometricAttr("getLonLatCoords");
			  	  	if (g != null)
			  	  	{
			  	  	    //Only used for issuing extra lon/lat triples according to WGS84 GeoPosition RDF Vocabulary			        	
			        	double[] coords = (double[]) myAssistant.applyRuntimeMethod("getLonLatCoords", new Object[]{wkt, targetSRID});
			        	if (coords != null)
			        	{
			        		row.put(g.get(0), "" + coords[0]);   //Implicit assumption that the first attribute is always referring to longitude...
			        		row.put(g.get(1), "" + coords[1]);   //...whereas the second one to latitude.
			        	}			  	  		
			  	  	}
*/		  	  
		  	  	}
		  	  	//Apply transformation for the geometry
	        	transformGeometry2RDF(uri, wkt, targetSRID, geomType);		        	
	        }
	        
  	        //Finally, transform thematic (non-spatial) attributes
	        if (attrMappings != null) 
	        {  	//Handling based on user-specified mappings to a custom ontology
	        	transformCustomThematic2RDF(uri, row, classific);
	        }
	        else
	        {   //Otherwise, each attribute name is used as the property in the resulting triple with values as literals
		        transformPlainThematic2RDF(uri, row); 	
	        }              
		}
		catch(Exception e) { 
			ExceptionHandler.warn(e, "An error occurred during transformation of an input record.");
		}
			
		return uri;                 //Return the URI assigned to this feature
	  }
	  

   /**
    * Handles all thematic (i.e., non-spatial) attributes of a feature, by simply issuing a triple with the original attribute name as property
    * @param uri  The URI assigned to this feature
    * @param attrValues  Attribute values for each thematic (non-spatial) attribute of the feature
    * @throws UnsupportedEncodingException
    */
	public void transformPlainThematic2RDF(String uri, Map<String, String> attrValues) throws UnsupportedEncodingException {   
		
  	    try 
  	    {
	      	//Also include information about the data source provider as specified in the configuration
	      	attrValues.put(attrDataSource, currentConfig.featureSource);
	      	
  	        //Insert literals for each attribute
  	        for (String key: attrValues.keySet())
  	        {
  	        	if (!key.equals(currentConfig.attrGeometry))    	  //With the exception of geometry, create one triple for each attribute value
  	        	{
  	        		String val = attrValues.get(key);
  	        		if ((val != null) && (!val.equals("")) && (!val.contains("Null")))       //Issue triples for NOT NULL/non-empty values only
  	        		{
  	        			createTriple4PlainLiteral(uri, myChecker.replaceWhiteSpace(currentConfig.ontologyNS + URLEncoder.encode(key, Constants.UTF_8)), val);
  	        			updateStatistics(key);                        //Update count of NOT NULL values transformed for this attribute
  	        		}
  	        	}
  	        }
  	    }
  	    catch(Exception e) { 
  	    	ExceptionHandler.warn(e, " An error occurred when attempting transformation of a thematic attribute value.");
  	    } 	    
    }
	
	
	/**
	 * Converts representation of a geometry WKT into suitable RDF triple(s) depending on the specified spatial ontology	
	 * @param uri  The URI assigned to this feature
	 * @param wkt  Well-Known Text representation of the geometry 
	 * @param srid  The EPSG identifier of the Coordinate Reference System of the geometry
	 * @param geomType  The type of the geometry (e.g., POINT, POLYGON, etc.)
	 */
	public void transformGeometry2RDF(String uri, String wkt, int srid, String geomType) {	 
		
      try {

        //Distinguish geometric representation according to the target store (e.g., Virtuoso, GeoSPARQL compliant etc.)
        if (currentConfig.targetGeoOntology.equalsIgnoreCase("wgs84_pos"))        //WGS84 Geoposition RDF vocabulary
        	insertWGS84Point(uri, wkt);
        else if (currentConfig.targetGeoOntology.equalsIgnoreCase("Virtuoso"))    //Legacy Virtuoso RDF point geometries
        	insertVirtuosoPoint(uri, wkt);
        else
        	insertWKTGeometry(uri, wkt, srid, geomType);            //Encoding geometry with a specific CRS is allowed in GeoSPARQL only
        
        //Type according to GeoSPARQL feature
        createTriple4Resource(uri, RDF.type.getURI(), currentConfig.geometryNS + Constants.FEATURE);
          
      } catch (Exception e) {
    	  ExceptionHandler.warn(e, " An error occurred during transformation of a geometry.");
      }    
	}


	/**
	 * Inserts a typical WKT geometry of a spatial feature into the Jena model (suitable for GeoSPARQL compliant stores)
	 * @param uri  The URI assigned to this feature
	 * @param wkt  Well-Known Text representation of the geometry 
	 * @param srid  The EPSG identifier of the Coordinate Reference System of the geometry
	 */
	private void insertWKTGeometry(String uri, String wkt, int srid, String geomType) {	
		
	  	  //Create a link between a spatial feature and its respective geometry
	  	  createTriple4Resource(uri, Constants.NS_GEO + "hasGeometry", uri + Constants.GEO_URI_SUFFIX);
  	
	  	  //Insert a triple for the geometry type (e.g., point, polygon, etc.) of a feature
	  	  createTriple4Resource(uri + Constants.GEO_URI_SUFFIX, RDF.type.getURI(), Constants.NS_SF + geomType);

	  	  //Encode SRID information before the WKT literal
	  	  wkt = "<http://www.opengis.net/def/crs/EPSG/0/" + srid + "> " + wkt;

	  	  //Triple with the WKT literal
	  	  createTriple4TypedLiteral(uri + Constants.GEO_URI_SUFFIX, Constants.NS_GEO + Constants.WKT, wkt, TypeMapper.getInstance().getSafeTypeByName(Constants.NS_GEO + Constants.WKTLiteral));
	}


	/**
	 * Insert a Point geometry of a spatial feature into the Jena model according to legacy Virtuoso RDF geometry specifications (concerning point geometries only)
	 * @param uri  The URI assigned to this feature
	 * @param pointWKT  Well-Known Text representation of the (point) geometry 
	 */
	private void insertVirtuosoPoint(String uri, String pointWKT) {  

		createTriple4TypedLiteral(uri, Constants.NS_POS + Constants.GEOMETRY, pointWKT, TypeMapper.getInstance().getSafeTypeByName(Constants.NS_VIRT + Constants.GEOMETRY));	    
	}
		  

	/**
	 * Insert a Point geometry of a spatial feature into the Jena model according to legacy WGS84 Geoposition RDF vocabulary
	 * @param uri  The URI assigned to this feature
	 * @param pointWKT  Well-Known Text representation of the (point) geometry
	 */
	private void insertWGS84Point(String uri, String pointWKT) {
	    
		//Get coordinates from the WKT representation
		double coords[] = myAssistant.getLonLatCoords(pointWKT, 4326);     //Geoposition RDF vocabulary supports WGS84 coordinates only
		
	  	//X-ordinate as a property
	  	createTriple4TypedLiteral(uri, Constants.NS_POS + Constants.LONGITUDE, ""+coords[0], TypeMapper.getInstance().getSafeTypeByName(Constants.NS_XSD + "float"));
	  	 
	  	//Y-ordinate as a property
	  	createTriple4TypedLiteral(uri, Constants.NS_POS + Constants.LATITUDE, ""+coords[1], TypeMapper.getInstance().getSafeTypeByName(Constants.NS_XSD + "float"));
	}

	/**
	 * Provides a list of argument values to be used in calling a built-in function.
	 * @param args  List of arguments (parameters) of the built-in function.
	 * @param attrValues  List of pairs of attributes and their respective values for a given feature
	 * @return  Argument values to be used in the function call.
	 */
	private List<String> getArgValues(List<String> args, Map<String, String> attrValues) {
		
      		//Also include information about the data source provider as specified in the configuration
			attrValues.put(attrDataSource, currentConfig.featureSource);
		
			List<String> argv = new ArrayList<String>();
			for (String arg: args)
			{
			    //For each argument, get its actual value to be used by the built-in function
				String val = attrValues.get(arg);
				if (val == null)
					val = "";	      				
				argv.add(val);             			
			}
			
			return argv;
	}
	
	/**
	 * Transforms all thematic (i.e., non-spatial) attributes according to a custom ontology specified in a YML format
	 * @param uri  The URI assigned to this feature
	 * @param attrValues  Attribute values for each thematic (non-spatial) attribute of the feature
	 * @param classific  The classification scheme used in the category assigned to the feature
	 * @throws UnsupportedEncodingException
	 */
	public void transformCustomThematic2RDF(String uri, Map<String, String> attrValues, Classification classific) throws UnsupportedEncodingException  {    
		
  	    try 
  	    {
  	    	mapProperties mapping;
  	    	Set<String> indexCompAttrs = new HashSet<String>();      //Retains an index for all composite entities consisting of multiple attributes (e.g., address)
  	        	    	
  	        //Include a category identifier, as found in the classification scheme and suffixed with the user-specified namespace
	      	if ((classific != null) && (classific.getUUID(attrValues.get(currentConfig.attrCategory))) != null)
	      		attrValues.put(attrCategoryURI, currentConfig.featureClassNS + classific.getUUID(attrValues.get(currentConfig.attrCategory)));
//	      	else
//	      		System.out.println("CATEGORY NOT FOUND:" + attrValues.get(currentConfig.attrCategory));
	      	
	      	//Also include information about the data source provider as specified in the configuration
	      	//attrValues.put(attrDataSource, currentConfig.featureSource);
	      	
	      	//Dynamically generate values for extra attributes using built-in functions
	      	for (String extraAttr: attrMappings.getExtraThematicAttributes())
	      	{
//	      		if (extraAttr.equalsIgnoreCase(attrURI))                //This is a special case, already handled in the generation of URIs
//	      			continue;
	      		
	      		mapping = attrMappings.find(extraAttr);                 //Mapping associated with this attribute
	      		
	      		List<String> args = mapping.getFunctionArguments();     //Identify any arguments that should be used by the generator function
	      		 
	      		//Call built-in function in order to assign a value to this extra attribute
	      		if ((args != null) && (!args.isEmpty()))
	      		{
	      			List<String> argv = getArgValues(args, attrValues);
	      			attrValues.put(extraAttr, (String) myAssistant.applyRuntimeMethod(mapping.getGeneratorFunction(), argv.toArray(new Object[argv.size()]))); 
	      		}
	      		else
	      			attrValues.put(extraAttr, (String) myAssistant.applyRuntimeMethod(mapping.getGeneratorFunction(), new Object[]{}));
	      	}
	      	
  	        //Iterate over each attribute specified in the mapping and insert triple(s) according to its specifications
  	        for (String key: attrValues.keySet())
  	        {
  	        	if (!key.equals(currentConfig.attrGeometry))    // (!key.equals(currentConfig.attrKey))
  	        	{
  	        		String val = attrValues.get(key);  	        		
  	        		if ((val != null) && (!val.equals("")) && (!val.contains("Null")))       //Issue triples for NOT NULL/non-empty values only
  	        		{
  	        			val = myChecker.removeIllegalChars(val);          //Replace special characters not allowed in literals 	        			
  	        			mapping = attrMappings.find(key);                 //Mapping associated with this attribute
  	        			String lang;                                      //Language used in string literals 
  	        			String entityType = null;                         //Entity type used as a suffix to the URI
  	        			
  	        			if (mapping == null)                              //Cannot find a mapping that exactly matches this attribute
  	        			{
  	        				//Check whether this is a multi-faceted attribute (e.g., a name in various languages)
  	        				//FIXME: Wild char '%' is used in YML mappings in order to specify such attributes
  	        				String attrBase = attrMappings.findMultiFaceted(key);
  	        				if (attrBase != null)                        //Multi-faceted attribute is specified in the mappings
  	        					mapping = attrMappings.find(attrBase);   //Mapping associated with this multi-faceted attribute

  	        				if (mapping == null)   //If still no mapping is found, then ...
  	        				{
  	        					//Trivial handling of any attribute not specifically mapped to the ontology by emitting triples for (key, value) pairs
  	        					if (attrMappings.find("_") != null)      //FIXME: Wild-card character used to denote any other attribute not specifically defined in the YML mapping 
  	        					{
  	        						mapping = attrMappings.find("_");
		  	        				createTriple4Resource(uri, mapping.getPredicate(), uri + "/" + key);
		  	        				createTriple4PlainLiteral(uri + "/" + key, currentConfig.ontologyNS + "key", key);
		  	        				createTriple4PlainLiteral(uri + "/" + key, currentConfig.ontologyNS + "value", val);
		  	        				updateStatistics(key);               //Update count of NOT NULL values transformed for this attribute
  	        					}
  	        					
	  	        				continue;
  	        				}
  	        				else
  	        				{
  	        					//Apply a built-in function 
  	        					lang = (String) myAssistant.applyRuntimeMethod(mapping.getLanguage(), new Object[]{key, attrBase.length()}); //Language tag is dynamically inferred from the last part of the attribute name
  	        					if (lang != null)
  	        						entityType = mapping.getEntityType() + "_" + lang;                 //URIs will also include a language suffix in order to be distinguishable
  	        					else
  	        						continue;
  	        				}
  	        			}
  	        			else
  	        			{
  	        				lang = mapping.getLanguage();
  	        				entityType = mapping.getEntityType();
  	        			}
    			
  	        			updateStatistics(key);                          //Update count of NOT NULL values transformed for this attribute
  	        			
  	        			//User specifications for transforming this attribute
  	        			String resPart = mapping.getPart();             //This resource is part of another entity (e.g., streetname is part of address)
  	        			String resClass = mapping.getInstance();        //This resource instantiates a class (e.g., email instantiates a contact)	
        				String predicate = mapping.getPredicate();      //Predicate according to the ontology
        				String resType = mapping.getResourceType();     //Type of the resource                 
        				RDFDatatype dataType = mapping.getDataType();   //Data type for literals
        				
        				//Handle value for this attribute according to its designated mapping profile
        				switch (mapping.getMappingProfile()) {
        					case IS_INSTANCE_TAG_LANGUAGE :       //Property is an instance of class in the ontology and also specifies language tag in literals 
        						createTriple4Resource(uri, predicate, uri + "/" + entityType);
        						if (myAssistant.isValidISOLanguage(lang)) {      //Check for valid ISO 693-1 language codes
        							createTriple4LanguageLiteral(uri + "/" + entityType, currentConfig.ontologyNS + resClass + "Value", val, lang);
        							createTriple4PlainLiteral(uri + "/" + entityType, currentConfig.ontologyNS + "language", lang);
        						}
        						else                                              //This is not actually a language code, so treat it like a literal
        							createTriple4PlainLiteral(uri + "/" + entityType, currentConfig.ontologyNS + resClass + "Value", val);
        							
        						if (!resType.trim().toUpperCase().equals("NONE"))     //FIXME: Issue triple for resource type unless it is explicitly suppressed
        							createTriple4PlainLiteral(uri + "/" + entityType, currentConfig.ontologyNS + resClass + "Type", resType); 
        						//Also insert a triple for the RDF class of this entity
        						createTriple4Resource(uri + "/" + entityType, RDF.type.getURI(), currentConfig.ontologyNS + resClass);
        						break;
        					case IS_INSTANCE :                    //Property is an instance of class in the ontology without language tags
        						createTriple4Resource(uri, predicate, uri + "/" + entityType);
            					createTriple4PlainLiteral(uri + "/" + entityType, currentConfig.ontologyNS + resClass + "Value", val);
            					createTriple4PlainLiteral(uri + "/" + entityType, currentConfig.ontologyNS + resClass + "Type", resType); 
            					//Also insert a triple for the RDF class of this entity
            					createTriple4Resource(uri + "/" + entityType, RDF.type.getURI(), currentConfig.ontologyNS + resClass);
            					break;
        					case IS_PART_TAG_LANGUAGE :          //Property is part of a composite class in the ontology and also specifies language tag in literals 
        						if (!indexCompAttrs.contains(resPart))
            					{
            						createTriple4Resource(uri, currentConfig.ontologyNS + entityType, uri + "/" + resPart);
            						indexCompAttrs.add(resPart);
            						//Also insert a triple for the RDF class of this entity
            						createTriple4Resource(uri + "/" + resPart, RDF.type.getURI(), currentConfig.ontologyNS + resPart);
            					}
            					createTriple4LanguageLiteral(uri + "/" + resPart, predicate, val, lang);
            					break;
        					case IS_PART :                        //Property is part of a composite class in the ontology without language tags
        						if (!indexCompAttrs.contains(resPart))
            					{
            						createTriple4Resource(uri, currentConfig.ontologyNS + entityType, uri + "/" + resPart);
            						indexCompAttrs.add(resPart);
            						//Also insert a triple for the RDF class of this entity
            						createTriple4Resource(uri + "/" + resPart, RDF.type.getURI(), currentConfig.ontologyNS + resPart);
            					}
            					createTriple4PlainLiteral(uri + "/" + resPart, predicate, val);
            					break;
        					case HAS_DATA_TYPE_URL :             //Property with a URL object; URLs must be valid, otherwise they may be corrected by the checker
        						createTriple4Resource(uri, predicate, myChecker.cleanupURL(val));
        						break;
        					case HAS_DATA_TYPE :                  //Property with a literal having data type specification
        						createTriple4TypedLiteral(uri, expandNamespace(predicate), val, dataType);
        						break;
        					case IS_LITERAL_TAG_LANGUAGE :        //Property with a plain literal having a language tag
        						createTriple4LanguageLiteral(uri, predicate, val, lang);
        						break;
        					case IS_LITERAL :                     //Property with a plain literal without further specifications
        						createTriple4PlainLiteral(uri, predicate, val);
        						break;
        					default:                              //No action
        							
        				};			
  	        		}
        		}
        	}    
  	    }
  	    catch(Exception e) { 
  	    	ExceptionHandler.warn(e, " An error occurred when attempting transformation of a thematic attribute value.");
  	    }
	}

	
	/**
	 * Transforms a given category in a (possibly hierarchical) classification scheme into RDF triples
	 * FIXME: Current handling fits the classification scheme suggested by the SLIPO ontology for Points of Interest (POI) 
	 * @param uuid  A universally unique identifier (UUID) assigned to the category
	 * @param name  The name of this category according to the classification
	 * @param parent_uuid  The universally unique identifier (UUID) assigned to the parent of this category in the classification scheme
	 */
	public void transformCategory2RDF(String uuid, String name, String parent_uuid) {    	
	
  	    try 
  	    {	
	      	//Classification scheme is named according to the data source provider as specified in the configuration
  	    	String classificSource = currentConfig.featureSource;
  	    	
  	    	//Create an identifier for the RDF resource
  	        String encodingResource = myChecker.replaceWhiteSpace(URLEncoder.encode(uuid, Constants.UTF_8));	  	      
  	        String uri = currentConfig.featureClassNS + encodingResource;
	      	  	
  	        //Create triples
  	        createTriple4Resource(uri, currentConfig.ontologyNS + "termClassification", currentConfig.featureClassificationNS + classificSource);
  	        createTriple4Resource(uri, RDF.type.getURI(), currentConfig.ontologyNS + "Term");
  	        createTriple4PlainLiteral(uri, currentConfig.ontologyNS + "value", name); 
  	    	if (parent_uuid != null)
  	    		createTriple4Resource(uri, currentConfig.ontologyNS + "parent", currentConfig.featureClassNS + myChecker.replaceWhiteSpace(URLEncoder.encode(parent_uuid, Constants.UTF_8)));	  
  	    }
  	    catch(Exception e) { 
  	    	ExceptionHandler.warn(e, " An error occurred when attempting transformation of a thematic attribute value.");
  	    }	    
	}

	
	/**
	 * Expands the prefix into the full namespace of a given RDF node (usually, a predicate)
	 * @param s  A prefixed name with a prefix label and a local part, separated by a colon ":"
	 * @return  A URI by concatenating the expanded namespace associated with the prefix and the local part
	 */
	private String expandNamespace(String s) {
		
		String prefix = s.substring(0, s.indexOf(':'));  //Get the prefix
		String namespace = prefixes.get(prefix);         //Identify its respective full namespace
		if (namespace != null)
			return s.replace(prefix + ":", namespace);   //... and replace it
		
		return s;	 //No replacement took place
	}


	/**
	 * Creates an RDF triple with specific handling of literals having a language tag
	 * @param s  Triple subject
	 * @param p  Triple predicate
	 * @param o  Triple object literal
	 * @param lang -- Language specification of the literal value
	 */
	private void createTriple4LanguageLiteral(String s, String p, String o, String lang) { 
		
	    results.add(new Triple(NodeFactory.createURI(s), NodeFactory.createURI(expandNamespace(p)), NodeFactory.createLiteral(o, lang)));
	}

	
	/**
	 * Creates an RDF triple for a plain literal (without language tag or data type specification)
	 * @param s -- Triple subject
	 * @param p -- Triple predicate
	 * @param o -- Triple object literal
	 */
	private void createTriple4PlainLiteral(String s, String p, String o) { 
		
	    results.add(new Triple(NodeFactory.createURI(s), NodeFactory.createURI(expandNamespace(p)), NodeFactory.createLiteral(o)));
	}

	/**
	 * Creates an RDF triple with a resource as its object (i.e., non literal values)
	 * @param s -- Triple subject
	 * @param p -- Triple predicate
	 * @param o -- Triple object resource
	 */
	private void createTriple4Resource(String s, String p, String o) { 
		
	    results.add(new Triple(NodeFactory.createURI(s), NodeFactory.createURI(expandNamespace(p)), NodeFactory.createURI(o)));
	}
	

	/**
	 * Creates an RDF triple with specific handling of literals having a data type specification
	 * @param s -- Triple subject
	 * @param p -- Triple predicate
	 * @param o -- Triple object literal
	 * @param d -- Data type specification of the literal value
	 */
	private void createTriple4TypedLiteral(String s, String p, String o, RDFDatatype d) { 

	    results.add(new Triple(NodeFactory.createURI(s), NodeFactory.createURI(p), NodeFactory.createLiteral(o, d)));
	}
	
}
