package eu.slipo.athenarc.triplegeo.utils;

import org.apache.commons.io.FilenameUtils;
import java.io.*;


/**
 *  Checks if the input of the user is correct.
 *  The input must be a folder that contains the shapefile.
 *  @author Georgios Mandilaras
 */
public class InputChecker {

    private String inputFile;
    private String error_message;

    /**
     * Constructor
     * @param filename: The path of the input file
     */
    public InputChecker(String filename) {
        inputFile = filename;
        if( inputFile.charAt(inputFile.length()-1) =='/') {
            inputFile = inputFile.substring(0, inputFile.length()-1);
        }
        error_message = null;
    }

    /**
     *
     * @return the error message. If it's null, then no error occurred.
     */
    public String getError() {
        return error_message;
    }

    /**
     * Reset the inputFile variable.
     * @param filename: The path of the input file
     */
    public void setInputFile(String filename) {
        inputFile = filename;
        if( inputFile.charAt(inputFile.length()-1) =='/') {
            inputFile = inputFile.substring(0, inputFile.length()-1);
        }
    }


    /**
     * Checks if folder contains the necessary files of Shapefile.
     * The files must have the same name with the folder.
     * @return true or false if the folder contains the necessary files.
     */
    private boolean check_files() {
        File dir = new File(inputFile);
        File[] directoryListing = dir.listFiles();
        String filename = inputFile.substring(inputFile.lastIndexOf("/")+1, inputFile.length());
        if (directoryListing != null) {
            boolean shp_flag = false;
            boolean dbf_flag = false;
            boolean shx_flag = false;
            for (File child : directoryListing) {
                String childName = child.getName().substring(0, child.getName().lastIndexOf("."));
                if (!childName.equals(filename))
                    continue;
                String extension = FilenameUtils.getExtension(child.getAbsolutePath());
                switch (extension) {
                    case "shp":
                        shp_flag = true;
                        break;
                    case "dbf":
                        dbf_flag = true;
                        break;
                    case "shx":
                        shx_flag = true;
                        break;
                }
            }
            if (shp_flag && dbf_flag && shx_flag)
                return true;
            else {
                error_message = "Error: Necessary files are missing.";
                return false;
            }
        } else {
            error_message = "Error: Necessary files are missing.";
            return false;
        }
    }

    /**
     * Checks if the the input is correct.
     * The  input must be a directory that contains all the necessary shapefiles.
     * @return true or false if the input is correct.
     */
    public boolean check() {
        File dir = new File(inputFile);

        if (!dir.exists()) {
            error_message = "Error: Folder does not exist.";
            return false;
        }
        boolean isDirectory = dir.isDirectory();
        if (isDirectory) {
            return check_files();
        } else {
            error_message = "Error: The input must be a folder that will contain the shapefile.";
            return false;
        }
    }

    /**
     * Returns the absolute path of the requested file.
     * @param extension: the extension of the file that will search for.
     * @return the absolute path of the requested file
     */
    public String getFile(String extension) {
        File dir = new File(inputFile);
        File[] directoryListing = dir.listFiles();
        if (directoryListing != null) {
            for (File child : directoryListing) {
                String child_extension = FilenameUtils.getExtension(child.getAbsolutePath());
                if (extension.equals("dbf") && child_extension.equals("dbf"))
                    return child.getAbsolutePath();
                if (extension.equals("shp") && child_extension.equals("shp"))
                    return child.getAbsolutePath();
                if (extension.equals("shx") && child_extension.equals("shx"))
                    return child.getAbsolutePath();
            }
        }
        return null;
    }

}
