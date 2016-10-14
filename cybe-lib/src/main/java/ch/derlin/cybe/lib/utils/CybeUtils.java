package ch.derlin.cybe.lib.utils;

import org.apache.commons.io.IOUtils;
import ch.derlin.cybe.lib.win.WinUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A bunch of useful  methods to deal with urls, files and such.
 * User: lucy
 * Date: 20/06/14
 * Version: 0.1
 */
public class CybeUtils{

    public enum OS{LINUX, MAC, WINDOWS, SUN, OTHER}


    /**
     * Write the content of an {@link InputStream} into a file.
     * Note that the inputstream won't be closed.
     *
     * @param path the filepath
     * @param in   the inputstream
     */
    public static void saveResource( String path, InputStream in ){
        try( FileOutputStream out = new FileOutputStream( new File( path ) ) ){
            IOUtils.copy( in, out );
        }catch( Exception e ){
            e.printStackTrace();
        }
    }//end saveResource


    /**
     * Return the last part of an url, i.e. everything after the last "/" and before the first # (anchor) or ?.
     * The potential parameters and anchors will be stripped.
     * <p/>
     *
     * @param url the url
     * @return the last part or null
     */
    public static String lastPartOfUrl( String url ){
        return url.replaceAll( "(#|\\?).*", "" ).replaceAll( ".*/", "" );
    }//end lastPartOf


    /**
     * Join the
     *
     * @param subpaths
     * @return
     */
    public static String concatPath( String... subpaths ){
        StringBuilder builder = new StringBuilder();
        for( String s : subpaths ){
            if( builder.length() > 0 ) builder.append( File.separator );
            builder.append( s );
        }//end for

        return builder.toString();
    }//end pathJoin


    /**
     * Remove all the characters except [accented] letters, numbers, ".", "_" and " " from the given string.
     *
     * @param filename the string
     * @return the normalised string
     */
    public static String normaliseFilname( String filename ){
        return filename.replaceAll( "[^\\p{L}0-9\\. _-]", "" );
    }


    /**
     * @param s the string
     * @return true if s is null or empty, false otherwise
     */
    public static boolean isNullOrEmpty( String s ){
        return s == null || s.isEmpty();
    }//end isNullOrEmpty


    public static OS getOs(){
        String os = System.getProperty( "os.name" ).toLowerCase();
        if( os.contains( "linux" ) ) return OS.LINUX;
        if( os.contains( "ch/derlin/cybe/lib/win" ) ) return OS.WINDOWS;
        if( os.contains( "mac" ) ) return OS.MAC;
        if( os.contains( "sum" ) ) return OS.SUN;
        return OS.OTHER;
    }//end getOs


    /**
     * return a string uniquely identifying the given file.
     * <ul>
     *     <li>On linux: the inode number</li>
     *     <li>On Windows/ntfs: {@code "[volumes serial nbr]:[file index high]:[file index low]"}</li>
     * </ul>
     * The other systems are not supported. TODO
     *
     * @param filepath the path to the file
     * @return the unique id  or an empty string if the system is not supported
     */
    public static String getUniqueFileId( String filepath ){
        String id = "";
        try{
            OS os = getOs();
            if( os == OS.LINUX ){
                id = Files.readAttributes( Paths.get( filepath ), BasicFileAttributes.class ).fileKey()
                        .toString();
                id = id.replaceAll( ".*,ino=", "" ).replace( ")", "" );
                return id.isEmpty() ? null : id;

            }else if( os == OS.WINDOWS ){
                id = WinUtils.getUniqueFileId( filepath );
            }
        }catch( IOException ignored ){
        }
        return id;
    }//end getUniqueFileId


}//end class
