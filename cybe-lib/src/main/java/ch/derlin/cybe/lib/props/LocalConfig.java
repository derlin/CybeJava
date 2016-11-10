package ch.derlin.cybe.lib.props;

import com.google.gson.annotations.SerializedName;
import ch.derlin.cybe.lib.gson.DoNotSerialize;
import ch.derlin.cybe.lib.gson.GsonUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.validator.routines.UrlValidator;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author: Lucy Linder
 * @date: 19.06.2014
 */
public class LocalConfig implements Closeable{
    public static final String LOCAL_CONF_FILENAME = ".cybe";

    private String course;

    @SerializedName( "course_url" )
    private String courseUrl;

    @SerializedName( "inodes_to_names_mapping" )
    private Map<String, String> inodesToNamesMapping = new ConcurrentHashMap<>();

    @SerializedName( "dir" )
    private Set<String> dir = new TreeSet<>();

    @SerializedName( "ctype" )
    private Set<String> ctypes = new TreeSet<>();

    @SerializedName( "origin" )
    private Set<String> origin = new TreeSet<>();

    // -- not serialized
    @DoNotSerialize
    private transient String filepath;

    @DoNotSerialize
    private transient boolean modified = true;

    //-------------------------------------------------------------


    /** Create a new localconfig, with the filepath set to "./{@link #LOCAL_CONF_FILENAME}" */
    public LocalConfig(){
        this.filepath = LOCAL_CONF_FILENAME;
    }


    /**
     * Create a new localconfig with the given filepath.
     *
     * @param path the path (dir + filename) were the file is located/should be saved.
     */
    public LocalConfig( String path ){
        this.filepath = path;
    }


    /**
     * Create a localconfig.
     *
     * @param fullpath  the path (dir + filename) were the file is located/should be saved.
     * @param course    the course name it refers to,
     * @param courseUrl the course url
     */
    public LocalConfig( String fullpath, String course, String courseUrl ){
        this.filepath = fullpath;
        this.course = course;
        this.courseUrl = courseUrl;
    }


    //----------------------------------------------------


    /** @return true if a modification was made since the last save, false otherwise */
    public boolean isModified(){
        return modified;
    }


    /** @return the filepath of this config, i.e. were it will be saved. */
    public String getFilepath(){
        return filepath;
    }


    /** @param filepath the filepath of this config, i.e. were it should be saved. */
    public void setFilepath( String filepath ){
        this.filepath = filepath;
    }


    /** @return the course name */
    public String getCourse(){
        return course;
    }


    /** @return the course url */
    public String getCourseUrl(){
        return courseUrl;
    }


    /**
     * The localConfig keeps a list mapping file ids (inodes in linux, concatenation of drive id, low and high index in
     * Windows) with names. It allows the user to move/rename files while avoiding to download them again...
     *
     * @param uniqueId a unique file id (inode in Linux)
     * @param filename the original filename, as found on moodle/cyberlearn
     */
    public void putFileRef( String uniqueId, String filename ){
        this.inodesToNamesMapping.put( uniqueId, filename );
        modified = true;
    }


    /**
     * @param id the unique file id (inode in Linux)
     * @return the original filename, as found on moodle/cyberlearn
     */
    public String getFileFromId( String id ){
        return inodesToNamesMapping.containsKey( id ) ? inodesToNamesMapping.get( id ) : null;
    }//end getFileFromId


    /**
     * Remove an entry from the inodes-to-name mapping (see {@link #putFileRef(String, String)}.
     *
     * @param uniqueId the unique file id (inode in Linux)
     */
    public void removeFileRef( String uniqueId ){
        modified |= this.inodesToNamesMapping.remove( uniqueId ) != null;
    }


    /** Clear the list of inodes-to-name mappings entirely. */
    public void removeAllFileRefs(){
        if( this.inodesToNamesMapping.size() > 0 ){
            this.inodesToNamesMapping.clear();
            modified = true;
        }
    }


    /**
     * Remove from the inodes-to-name mapping all entry whose id are not in the given list.
     *
     * @param fileIds a list of file ids to keep
     */
    public void cleanFileRefs( List<String> fileIds ){
        fileIds.forEach( this::removeFileRef );
    }//end cleanFileRefs


    /**
     * Mark a [list of] directory as containing resources from this course.
     * this directory will then be checked for already downloaded files.
     *
     * @param dir the directory/ies (relative to the root directory, i.e. were this file is located. See {@link
     *            #filepath}.)
     * @return true if the configuration changed as a result of the call, false otherwise.
     */
    public boolean addDir( String... dir ){
        boolean m = this.dir.addAll( Arrays.asList( dir ) );
        modified |= m;
        return m;
    }


    /**
     * Remove a [list of] directory from the configuration. See {@link #addDir(String...)}
     *
     * @param dir the dir(s)
     */
    public boolean removeDir( String... dir ){
        boolean m = this.dir.removeAll( Arrays.asList( dir ) );
        modified |= m;
        return m;
    }


    /**
     * Add a [list of] acceptable content-types, i.e. content-type that should be downloaded.
     *
     * @param ctype the content-type, either as an official content type like "text/plain" or "application/pdf" or as
     *              an
     *              extension like "pdf", "txt" or "zip"
     * @return true if the configuration changed as a result of the call, false otherwise.
     */
    public boolean addCtype( String... ctype ){
        boolean ret = false;
        for( String c : ctype ){
            ret |= this.ctypes.add( c );
        }//end for
        modified |= ret;
        return ret;
    }


    /**
     * Remove a [list of] content-type.
     *
     * @param ctype the content-type to remove. Note that it must match the one given to the {@link
     *              #addCtype(String...)} to work.
     * @return true if the configuration changed as a result of the call, false otherwise.
     */
    public boolean removeCtype( String... ctype ){
        boolean ret = false;
        for( String c : ctype ){
            ret |= this.ctypes.remove( c );
        }//end for
        modified |= ret;
        return ret;
    }


    /**
     * Add a [list of] moodle link, which will also be parsed when looking for resources.
     *
     * @param origins the origin. Note that they must be valid moodle/cyberlearn urls ("http://cyberlearn.hes-so.ch/.
     *                ..")
     * @return true if the configuration changed as a result of the call, false otherwise.
     */
    public boolean addOrigin( String... origins ){
        boolean ret = false;
        for( String o : origins ){
            if( UrlValidator.getInstance().isValid( o ) ) ret |= this.origin.add( o );
        }//end for
        modified |= ret;
        return ret;
    }


    /**
     * Remove a [list of] moodle link. See {@link #addOrigin(String...)};
     *
     * @param origins the origins to remove.
     * @return true if the configuration changed as a result of the call, false otherwise.
     */
    public boolean removeOrigin( String... origins ){
        boolean ret = false;
        for( String o : origins ){
            ret |= this.origin.remove( o );
        }//end for
        modified |= ret;
        return ret;
    }


    /** @param course the course name */
    public void setCourse( String course ){
        this.course = course;
    }


    /** @param courseUrl the url of the course's homepage */
    public void setCourseUrl( String courseUrl ){
        this.courseUrl = courseUrl;
    }


    //-------------------------------------------------------------


    /**
     * Save the configuration to {@link #getFilepath()}.
     *
     * @return true upon success
     */
    public boolean save(){
        return this.save( this.filepath );
    }//end save


    /**
     * Save the configuration to the given file.
     *
     * @param filepath the file path in which to save the configuration
     * @return true upon success
     */
    public boolean save( String filepath ){
        boolean ret = false;
        if(modified){
            System.out.println("Saving LocalConfig...");
            ret = GsonUtils.writeJsonFile( filepath, this, true );
            modified = !ret;
        }
        return ret;
    }//end save


    /**
     * Load the configuration from the given file.
     *
     * @param path the path to the file
     * @return the configuration object
     */
    public static LocalConfig loadInstance( String path ){
        LocalConfig config = ( LocalConfig ) GsonUtils.getJsonFromFile( path, new LocalConfig() );
        if( config != null ){
            config.filepath = path;
            config.modified = false;
        }
        return config;
    }//end loadInstance


    /**
     * Check if the given file should be downloaded, This will first look if the content-type matches one in the ctypes
     * list. Upon failure, a second look-up will be performed with the filename extension.
     *
     * @param ctype the content-type
     * @param name  the filename
     * @return true if the file should be downloaded/is of interest, false otherwise.
     */
    public boolean isFileAccepted( String ctype, String name ){
        String extension = FilenameUtils.getExtension( name );
        for( String ct : ctypes ){
            if( ct.contains( ctype ) || extension.equals( ct ) ) return true;
        }//end for
        return false;
    }

    //----------------------------------------------------


    /**
     * Close this configuration file. If the configuration was modified, the changes will be written on disk (see {@link
     * #getFilepath()}).
     */
    @Override
    public void close(){
        if( modified ) save();
    }
}//end class
