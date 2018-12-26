package eu.slipo.athenarc.triplegeo.tests;

import junit.framework.TestCase;
import eu.slipo.athenarc.triplegeo.utils.InputChecker;


public class InputCheckerTest extends TestCase {

    public void testCheck() {

        InputChecker ic = new InputChecker("/home/giorgosmandi/Documents/DIT/Thesis/TripleGEO_Extensions/SparkTripleGeo/test/data/points");
        assertEquals(true, ic.check());

        ic.setInputFile("/home/giorgosmandi/Documents/DIT/Thesis/TripleGEO_Extensions/SparkTripleGeo/test/data/points/");
        assertEquals(true, ic.check());

        ic.setInputFile("/home/giorgosmandi/Documents/DIT/Thesis/TripleGEO_Extensions/SparkTripleGeo/test/data/points.shp");
        assertEquals(false, ic.check());
        assertEquals("Error: The input must be a folder that will contain the shapefile.", ic.getError());

        ic.setInputFile("/home/giorgosmandi/Documents/DIT/Thesis/TripleGEO_Extensions/SparkTripleGeo/test/data/doesntexist");
        assertEquals(false, ic.check());
        assertEquals("Error: Folder does not exist.", ic.getError());
    }

    public void testGetfile() {
        InputChecker ic = new InputChecker("/home/giorgosmandi/Documents/DIT/Thesis/TripleGEO_Extensions/SparkTripleGeo/test/data/points");
        assertEquals("/home/giorgosmandi/Documents/DIT/Thesis/TripleGEO_Extensions/SparkTripleGeo/test/data/points/points.dbf", ic.getFile("dbf"));

    }
}