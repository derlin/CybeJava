package ch.derlin.cybe.cmdline;

import ch.derlin.cybe.cmdline.parsing.CliFlag;
import ch.derlin.cybe.cmdline.parsing.CliParser;
import ch.derlin.cybe.cmdline.parsing.CliStringOption;
import ch.derlin.cybe.lib.gson.GsonUtils;
import ch.derlin.cybe.lib.network.Connector;
import ch.derlin.cybe.lib.network.CybeConnector;
import ch.derlin.cybe.lib.network.CybeParser;
import ch.derlin.cybe.lib.props.GlobalConfig;
import ch.derlin.cybe.lib.props.LocalConfig;
import ch.derlin.cybe.lib.utils.CybeUtils;
import ch.derlin.cybe.lib.utils.SuperSimpleLogger;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.http.NameValuePair;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.Future;
import java.util.function.Function;

import static ch.derlin.cybe.lib.utils.SuperSimpleLogger.*;

/**
 * @author: Lucy Linder
 * @date: 19.06.2014
 *
 * Updated on november 2016 to work with moodle2
 */
public class Cybe implements AutoCloseable{

    private static final int EXIT_STATUS_ERROR = 1, EXIT_STATUS_OK = 0;
    private static final String LOCAL_CONF_NAME = ".cybe";
    private static final int PULL_TIMEOUT_SEC = 15;  // max time to download one file

    private static final List<String> supportedPlatforms = Arrays.asList(
            "https://cyberlearn.hes-so.ch",
            "https://moodle.msengineering.ch");

    private static final List<String> defaultCtypes = Arrays.asList( "pdf", "text/plain", "zip", "doc" );

    private static Scanner in = new Scanner( System.in );

    private String userDir;
    private LocalConfig localConfig;
    private boolean isLocalConfigLoaded;

    private Collection<String> existingResources;
    private Connector connector;
    private CybeParser parser;
    private CmdDoc doc;
    private SuperSimpleLogger logger =  // debug, info, warn, error
            SuperSimpleLogger.getInstance( SILENT_OPT, SYSOUT_OPT, SYSOUT_OPT, SYSERR_OPT );
    private boolean lastCmdret;
    private Map<String, String> courses;


    @FunctionalInterface
    public interface CommandExecutor<String>{
        boolean process( List<String> args );
    }

    @FunctionalInterface
    public interface VarargFunction<A, B>{
        A apply( B... args );
    }


    private Map<String, CommandExecutor<String>> connectionlessHandlers = new TreeMap<>(),
            connectionfullHandlers = new TreeMap<>(), alwaysValidHandlers = new TreeMap<>(  );

    /* *****************************************************************
     * MAIN
     * ****************************************************************/


    public static void main( String[] args ) throws Exception{

        final SuperSimpleLogger logger =  // debug, info, warn, error
                SuperSimpleLogger.getInstance( SILENT_OPT, SYSOUT_OPT, SYSOUT_OPT, SYSERR_OPT );

        // ----------------------------------------------------
        // arguments parsing

        CliParser parser = new CliParser();
        parser.registerOption( "-s", new CliFlag( () -> {  // silent
            logger.setInfo( SILENT_OPT );
            logger.setWarn( SILENT_OPT );
            logger.setVerbose( SILENT_OPT );
            // keep errors
            logger.debug.printf( "silent mode on%n." );
        } ) );

        parser.registerOption( "-v", new CliFlag( () -> { // debug
            logger.setDebug( SYSOUT_OPT );
            logger.debug.printf( "debug mode on.%n" );
        } ) );

        CliStringOption userDir = new CliStringOption( System.getProperty( "user.dir" ) );
        parser.registerOption( "-p", userDir );   // userDir

        CliFlag interactiveFlag = new CliFlag();
        parser.registerOption( "-i", interactiveFlag ); // interactive

        CliFlag updateAllOption = new CliFlag();  // apply to all
        parser.registerOption( "--all", updateAllOption );
        parser.registerOption( "-a", updateAllOption );

        List<String> params;
        try{
            params = parser.parse( args );
        }catch( Exception e ){
            logger.debug.printf( "Error while parsing arguments%n" );
            params = new ArrayList<>();
        }

        // prepare command and params
        params.removeIf( p -> p.startsWith( "-" ) );

        // ----------------------------------------------------
        int exitStatus = EXIT_STATUS_OK;
        try( Cybe cybe = new Cybe( logger ) ){
            cybe.setUserDir( userDir.getValue() ); // update the working directory

            // get the command
            if( updateAllOption.getValue() ){
                String command;
                if( params.isEmpty() ){
                    logger.info.printf( "No arguments. Assuming pull.%n" );
                    command = "pull";

                }else{
                    command = params.remove( 0 );
                }

                if( cybe.can( command ) ){
                    // if command exist
                    cybe.forAll( command, params );

                }else{
                    printUsageAndQuit( cybe.getUnknownCommandMessage( command ), EXIT_STATUS_ERROR );
                }

            }else if( params.isEmpty() ){
                // no command specified, switch to interactive mode
                cybe.interactivePrompt();

            }else{
                // a command is specified, exec
                String command = params.remove( 0 );
                cybe.execute( command, params );
                if( interactiveFlag.getValue() ) cybe.interactivePrompt();
            }

            exitStatus =  cybe.lastCmdret() ? EXIT_STATUS_OK : EXIT_STATUS_ERROR;

        }

        System.exit( exitStatus );
    }//end main

    /* ****************************************************************/


    public Cybe(){

        userDir = System.getProperty( "user.dir" );
        doc = new CmdDoc( this.getClass().getResourceAsStream( "/man.json" ) );
        logger = SuperSimpleLogger.silentInstance();

        fillCommandMaps();
        loadLocalConfig();
        addShutdownHook();

    }


    public Cybe( SuperSimpleLogger logger ){
        this();
        this.logger = logger;
    }

    // ----------------------------------------------------


    /*
     * check if the given command exists
     */
    public boolean can( String command ){
        return  alwaysValidHandlers.containsKey( command ) || //
                connectionfullHandlers.containsKey( command ) || //
                connectionlessHandlers.containsKey( command );
    }//end can


    /*
     * execute the given command for all .cybe folders found under the current directory
     */
    public void forAll( String command, List<String> params ){
        Collection<File> files = FileUtils.listFiles( FileUtils.getFile( userDir ),
                FileFilterUtils.nameFileFilter( LOCAL_CONF_NAME ), TrueFileFilter.INSTANCE );


        lastCmdret = !files.stream().anyMatch( confFile -> { // stop if an error occurs
            userDir = confFile.getParent();  //TODO: the next line is not really clean...
            isLocalConfigLoaded = false; // reinit, so the userDir change is taken into account
            logger.info.printf( "%n-------------------------------%n" );
            logger.info.printf( "Changed working directory to %s%n", userDir );

            if( loadLocalConfig() ){
                if( execute( command, params ) ){
                    if( localConfig.isModified() ){
                        localConfig.save();
                        logger.debug.printf( "Saved local config %s%n", getLocalConfigFilePath() );
                    }
                }else{
                    System.out.printf( "An error occurred while processing %s", getLocalConfigFilePath() );
                    System.out.print( "continue ? [y|N] " );
                    String s = new Scanner( System.in ).nextLine();

                    if( !s.matches( "^y|Y|(yes)$" ) ){
                        return true; // true => an error occurred
                    }
                }

            }else{
                logger.debug.printf( "Could not load local config (%s)%n", getLocalConfigFilePath() );
            }
            return false; // we can keep going
        } );

        localConfig = null; // don't save the localConfig in the shutdown hook

    }//end updateAll

    //----------------------------------------------------


    private void fillCommandMaps(){

        alwaysValidHandlers.put( "init-global", Cybe::initGlobal );
        alwaysValidHandlers.put( "help", args -> helpOrMan( args, false ) );
        alwaysValidHandlers.put( "man", args -> helpOrMan( args, true ) );

        connectionlessHandlers.put( "dump", p -> {
            System.out.println( GsonUtils.toJson( localConfig ).replaceAll( "\\\"|\\{|\\}|\\[|\\]|,", "" ) );
            return true;
        } );

        connectionlessHandlers.put("open", p -> {
            try {
                java.awt.Desktop.getDesktop().browse(URI.create(localConfig.getCourseUrl()));
            } catch (IOException e) {
                System.err.println("Course URI is invalid...");
            }
            return true;
        });

        connectionlessHandlers.put( "add-origin", args -> add( localConfig::addOrigin, args ) );  //
        connectionlessHandlers.put( "rm-origin", args -> remove( localConfig::removeOrigin, args ) );  //

        connectionlessHandlers.put( "add-ctype", args -> add( localConfig::addCtype, args ) );  //
        connectionlessHandlers.put( "rm-ctype", args -> remove( localConfig::removeCtype, args ) );  //

        connectionlessHandlers.put( "add-dir", args -> add( localConfig::addDir, args ) );  //
        connectionlessHandlers.put( "rm-dir", args -> remove( localConfig::removeDir, args ) );  //

        connectionfullHandlers.put( "pull", this::pull );
        connectionfullHandlers.put( "init", this::init );
        connectionfullHandlers.put( "resync", this::resyncInodesToNameMapping );
    }

    //----------------------------------------------------


    @Override
    protected void finalize() throws Throwable{
        logger.debug.printf( "finalize%n");
        super.finalize();
    }


    @Override
    public void close() throws IOException{
        if( localConfig != null ){
            localConfig.close();
        }
        if( connector != null ) connector.close();
    }

    //----------------------------------------------------


    private void interactivePrompt(){

        List<String> params;
        String command;

        while( true ){
            String input = CmdUtils.prompt( in, "> ", i -> true );
            params = new ArrayList<>( Arrays.asList( input.split( " +" ) ) );
            command = params.remove( 0 );

            if( CybeUtils.isNullOrEmpty( command ) ||   //
                    command.equalsIgnoreCase( "quit" ) || command.equalsIgnoreCase( "exit" ) ){
                break;
            }

            lastCmdret = execute( command, params );
        }

    }//end interactivePrompt

    //----------------------------------------------------


    public boolean execute( String cmd, List<String> args ){

        // TODO just a quick fix in case the directory is not initialised (see if below)
        if( alwaysValidHandlers.containsKey( cmd )){
            lastCmdret = alwaysValidHandlers.get( cmd ).process( args );

        }else{
            if( !cmd.equals("init") && !loadLocalConfig() ){
                logger.info.printf( "Directory not initialised. Try cybe init.%n" );
                return false;
            }

            if( connectionlessHandlers.containsKey( cmd ) ){
                lastCmdret = connectionlessHandlers.get( cmd ).process( args );

            }else if( connectionfullHandlers.containsKey( cmd ) ){
                if( !createConnectorAndParser() ) printUsageAndQuit( "Could not connect...", EXIT_STATUS_ERROR );
                lastCmdret = connectionfullHandlers.get( cmd ).process( args );

            }else{
                logger.error.printf( getUnknownCommandMessage( cmd ) );
                return false;
            }
        }
        if( !lastCmdret ) System.out.println( "Usage: " + doc.get( cmd ).syntax() );

        return lastCmdret;

    }//end execute


    public String getUnknownCommandMessage( String cmd ){
        return String.format( "Unknown command: '%s'.%nDid you mean '%s' ?%n", //
                cmd, doc.betterMatch( cmd ).getName() );

    }


    /* *****************************************************************
     * lambda commands
     * ****************************************************************/


    private boolean add( VarargFunction<Boolean, String> method, List<String> params ){
        if( params.size() == 0 ) return false;
        params.stream().map( p -> p + " : " + ( method.apply( p ) ? "added.%n" : "failed.%n" ) ) //
                .forEach( logger.info::printf );
        return true;
    }//end add


    private boolean remove( VarargFunction<Boolean, String> method, List<String> params ){
        if( params.size() == 0 ) return false;
        int sum = params.stream().mapToInt( p -> ( method.apply( p ) ? 1 : 0 ) ).sum();
        logger.info.printf( "%d items removed.%n", sum );
        return true;
    }//end remove


    /*
     * save username and password in home folder
     */
    private static boolean initGlobal( List<String> args ){
        GlobalConfig config = new GlobalConfig();

        config.setHomeUrl( CmdUtils.choice( in, "For which platform do you want to use Cybe ?",
                supportedPlatforms, ( s ) -> CmdUtils.ChoiceMismatchAction.QUIT ) );
        config.setUsername( CmdUtils.prompt( in, "Your username: ", i -> true ) );
        config.setPassword( CmdUtils.prompt( in, "Your password: ", i -> true ) );

        return config.save();
    }//end initGlobal


    /*
     * create a .cybe file in the current userDir with the course and courseUrl infos:
     * - if the file already exists, prompt for a confirmation
     * - get the available courses by parsing the welcome page
     * - let the user choose a course
     */
    private boolean init( List<String> args ){
        try{

            if( localConfigFileExists() ){
                // if the file already exists, prompt for a confirmation
                String answer = CmdUtils.prompt( in, "A local config already exist. Override ? [Y|n] ", //
                        s -> ( s.isEmpty() || s.matches( "[y|n|Y|N]" ) ) );

                if( !( answer.isEmpty() || answer.matches( "Y|y" ) ) ) return true;
            }

            // get the list of courses
            if( courses == null ) courses = parser.getListOfCourses();
            if( courses == null || courses.size() == 0 ){
                System.out.println( "I couldn't find any class on this platform" );
                return true;
            }
            // create the config file
            localConfig = new LocalConfig( getLocalConfigFilePath() );

            String selectedCourse = CmdUtils.choice( in, "Select a course", new ArrayList<>( courses.keySet() ),
                    ( s ) -> CmdUtils.ChoiceMismatchAction.RETURN_NULL );

            if( selectedCourse == null ){
                System.out.println( "Initialisation cancelled." );
                return true;
            }

            localConfig.setCourse( selectedCourse );
            localConfig.setCourseUrl( courses.get( selectedCourse ) );
            localConfig.save();
            existingResources = new HashSet<>();
            isLocalConfigLoaded = true;

        }catch( Exception e ){
            logger.error.printf( "error while init.%n" );
            e.printStackTrace();
            return false;
        }

        return true;
    }//end init


    /*
     * download new files
     */
    private boolean pull( List<String> args ){
        try{
            List<Future<NameValuePair>> futures = parser.findCourseResources( //
                    localConfig.getCourseUrl(), ( ctype, name, in ) -> {
                try{
                    logger.debug.printf( "=== %s [%s]%n", name, ctype );
                    if( isFileAccepted( ctype, name ) && !existingResources.contains( name ) ){
                        existingResources.add( name ); // mark this file as handled
                        String path = CybeUtils.concatPath( userDir, name );
                        CybeUtils.saveResource( path, in ); // save the resource
                        logger.info.printf( "  --> SAVING %s (thread: %s)%n", name, Thread.currentThread().getId() );
                        // add its unique id to the inodesToNameMapping
                        localConfig.putFileRef( CybeUtils.getUniqueFileId( path ), name );
                    }

                }catch( Exception e ){
                    logger.warn.printf( "Error while downloading resource %s%n", name );
                    logger.error.printf( "Exception inside pull handler : %s%s%n", e, e.getMessage() );
                }
            }, ( url, e ) -> System.err.println( url + ": " + e.getStatusLine() ) );

            parser.futuresToMap( futures, PULL_TIMEOUT_SEC );
            logger.debug.printf( "FUTURES GATHERED%n" );

        }catch( Exception e ){
            logger.error.printf( "error while pulling.%n" );
            e.printStackTrace();
            return false;
        }

        return true;
    }//end pull


    /*
     * remove all the inode-to-names mapping from the config file and
     * reconstruct it...
     */
    private boolean resyncInodesToNameMapping( List<String> args ){
        try{
            localConfig.removeAllFileRefs(); // clear the list totally

            List<Future<NameValuePair>> futures = parser.findCourseResources( //
                    localConfig.getCourseUrl(), ( ctype, name, in ) -> {
                try{
                    if( existingResources.contains( name ) ){
                        String path = CybeUtils.concatPath( userDir, name );
                        String id = CybeUtils.getUniqueFileId( path );
                        // add its unique id to the inodesToNameMapping
                        localConfig.putFileRef( id, name );
                        logger.info.printf( "--> ADDED ref %s [%s]%n", name, id );
                    }

                }catch( Exception e ){
                    logger.warn.printf( "Error while getting resource %s%n", name );
                    logger.error.printf( "Exception inside resync handler : %s%s%n", e, e.getMessage() );
                }
            }, ( url, e ) -> System.err.println( url + ": " + e.getStatusLine() ) );

            parser.futuresToMap( futures, PULL_TIMEOUT_SEC );
            logger.debug.printf( "Resync done.%n" );

        }catch( Exception e ){
            logger.error.printf( "error while resync.%n" );
            e.printStackTrace();
            return false;
        }
        logger.debug.printf( "Resync done. Modified : %b%n", localConfig.isModified());
        return true;
    }//end pull


    /*
     * display help: man = commands + description,help = commands only
     */
    private boolean helpOrMan( List<String> args, boolean isMan ){
        // no arguments, print the list of available commands
        if( args.size() == 0 ){
            System.out.println( isMan ? doc.man() : doc.help() );
        }else{
            // if a command name was specified, print its description
            String cmd = args.get( 0 );
            CmdDoc.CmdDescription descr = doc.get( cmd );

            if( descr != null ){
                System.out.println( descr.fullDescription() );
            }else{
                // the command does not exist -> print the closest command name available
                System.out.printf( "No such command %s. Did you mean %s ?%n", cmd, doc.betterMatch( cmd ) );
            }
        }
        return true;

    }//end help

    /* *****************************************************************
     * config files management
     * ****************************************************************/


    /*
     * look for the .cybe in the current directory
     */
    private boolean loadLocalConfig(){
        if( isLocalConfigLoaded ) return true;
        File configFile = new File( getLocalConfigFilePath() );
        if( configFile.exists() ){
            localConfig = ( LocalConfig ) GsonUtils.getJsonFromFile( configFile, new LocalConfig() );
            if( localConfig != null && !CybeUtils.isNullOrEmpty( localConfig.getCourseUrl() ) ){
                isLocalConfigLoaded = true;
                localConfig.setFilepath( configFile.getPath() ); // where to save the config
                existingResources = new HashSet<>( //
                        getExistingResources( userDir, localConfig::getFileFromId ).values() );
            }
        }
        isLocalConfigLoaded = localConfig != null &&  //
                !CybeUtils.isNullOrEmpty( localConfig.getCourseUrl() );
        return isLocalConfigLoaded;

    }


    private boolean localConfigFileExists(){
        return new File( getLocalConfigFilePath() ).exists();
    }


    private String getLocalConfigFilePath(){
        return userDir.concat( File.separator ).concat( LOCAL_CONF_NAME );
    }

    /* *****************************************************************
     * getters and setters
     * ****************************************************************/


    public boolean lastCmdret(){
        return lastCmdret;
    }


    public void setUserDir( String userDir ){
        this.userDir = userDir;
    }

    /* *****************************************************************
     * private ch.derlin.cybe.lib.utils
     * ****************************************************************/


    private boolean isFileAccepted( String ctype, String name ){
        final String extension = FilenameUtils.getExtension( name );
        for( String ct : defaultCtypes ){
            if( ct.contains( ctype ) || extension.equals( ct ) ) return true;
        }//end for

        return localConfig.isFileAccepted( ctype, name );
    }


    /*
     * get the list of files in the current folder
     */
    public static Map<String, String> getExistingResources( String directory, Function<String,
            String> inodeToNameResolver ){
        File rootDir = new File( directory );
        Map<String, String> results = new HashMap<>();
        if( !rootDir.isDirectory() ) return results;

        for( File file : rootDir.listFiles( f -> !f.isDirectory() ) ){
            String id = CybeUtils.getUniqueFileId( file.getAbsolutePath() );
            String resolvedName = inodeToNameResolver.apply( id );
            results.put( id, resolvedName != null ? resolvedName : file.getName() );
        }//end for
        return results;
    }//end getExistingResources


    private boolean createConnectorAndParser(){

        if( connector != null && connector.isConnected() ) return true;

        GlobalConfig globalConfig = GlobalConfig.getInstance();
        if( globalConfig == null ){
            printUsageAndQuit( "No credentials found.\nUse cybe init-global to specify your username and " +
                    "password", 1 );
        }

        if( globalConfig.getHomeUrl() == null ){
            System.out.println( "The platform URL was not specified." );

            String choice = CmdUtils.choice( in, "Which platform do you use ?", supportedPlatforms, ( s ) -> {
                if( CmdUtils.isQuitInput( s ) ) printUsageAndQuit( "Quitting.", 0 );
                System.out.println( "Wrong input" );
                return CmdUtils.ChoiceMismatchAction.IGNORE;
            } );

            globalConfig.setHomeUrl( choice );
            globalConfig.save();
        }

        try{
            // TODO
            connector = new CybeConnector( globalConfig.getHomeUrl() );
            parser = new CybeParser( connector, logger );
            connector.connect( globalConfig );
        }catch( Exception e ){
            logger.error.printf( "error while creating connector and parser.%n" );
            e.printStackTrace();
            return false;
        }
        return true;

    }//end createConnectorAndParser


    private void addShutdownHook(){
        Runtime.getRuntime().addShutdownHook( new Thread( () -> {
            logger.info.printf( "Cleaning up.%n" );
            try{
                close();
            }catch( IOException e ){
                e.printStackTrace();
            }
            logger.info.printf( "Done.%n" );
        } ) );
    }//end addShutdownHook


    private static void printUsageAndQuit( String s, int exitStatus ){
        System.out.println( s );
        System.exit( exitStatus );
    }
}//end class
