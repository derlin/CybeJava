package ch.derlin.cybe.lib.network;

import ch.derlin.cybe.lib.network.Connector.HttpErrorHandler;
import ch.derlin.cybe.lib.network.Connector.ResourceConsumer;
import org.apache.commons.io.IOUtils;
import org.apache.http.NameValuePair;
import org.apache.http.entity.ContentType;
import org.apache.http.message.BasicNameValuePair;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ch.derlin.cybe.lib.utils.CybeUtils;
import ch.derlin.cybe.lib.utils.SuperSimpleLogger;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.*;

import static ch.derlin.cybe.lib.utils.CybeUtils.*;

/**
 * User: lucy
 * Date: 20/06/14
 * Version: 0.1
 */
public class CybeParser {

    private ExecutorService pool = Executors.newWorkStealingPool();
    private Connector connector;

    private SuperSimpleLogger logger = SuperSimpleLogger.silentInstance();
    private HttpErrorHandler errorHandler;


    /**
     * Create a parser using the given cybeConnector.
     *
     * @param connector the connector to use
     */
    public CybeParser( Connector connector ){
        this.connector = connector;
        logger.setDebug( null );
    }


    /**
     * Create a parser using the given cybeConnector.
     *
     * @param connector the connector to use
     */
    public CybeParser( Connector connector, SuperSimpleLogger logger ){
        this( connector );
        this.logger = logger;
    }


    /**
     * see {@link #findCourseResources(String, ResourceConsumer, HttpErrorHandler)}    *
     */
    public List<Future<NameValuePair>> findCourseResources( String baseUrl,
                                                            ResourceConsumer consumer ) throws Exception{
        return findCourseResources( baseUrl, consumer, null );
    }


    /**
     * Parse a course page and iterate over all the resources it contains,
     * calling the consumer.accept method for each
     * one.
     * Careful: the consumer can be called in parallel from different threads..
     *
     * @param baseUrl      the url of the course page
     * @param consumer     the consumer. Note: the consumer could be called from different threads !
     * @param errorHandler the error handler to use
     * @return a list of futures, allowing you to retrieve a map of (resource name, resource url). See {@link
     *         #futuresToMap(java.util.List, int)}.
     * @throws Exception {@link IOException} and any other exception that could be thrown by the consumer
     */
    public List<Future<NameValuePair>> findCourseResources( String baseUrl, ResourceConsumer consumer,
                                                            HttpErrorHandler errorHandler ) throws Exception{

        final Set<String> alreadySeen = new TreeSet<>();
        List<Future<NameValuePair>> list = new ArrayList<>();

        connector.getResource( baseUrl, ( type, name, in ) -> {
            Document doc = Jsoup.parse( IOUtils.toString( in ) );
            Elements links = doc.select( "#region-main a[href]" );  // get all links

            links.stream()
                    // get the href attr
                    .map( link -> link.attr( "href" ) )
                            // keep only potential resource links
                    .filter( CybeParser::isLinkOfInterest )
                            // don't process a link twice
                    .filter( alreadySeen::add )
                            // submit the job to the pool
                    .forEach( href -> {
                        list.add( pool.submit( new CallableResourceFinder( href, consumer ) ) );
                    } );

        }, errorHandler );

        return list;
    }//end getAllResources




    /**
     * Wait for all the future tasks to complete and return their results into a map
     *
     * @param futures the list of futures
     * @param timeout the timeout, in milliseconds. It must be positive.
     * @return a map of resource names and resources urls.
     */
    public Map<String, String> futuresToMap( List<Future<NameValuePair>> futures, final int timeout ){
        // using a parallel stream here divides the runtime by a factor 2
        final Map<String, String> result = new TreeMap<>(  );
        futures.stream()
                // wait for each task to finish
                .map( f -> CybeParser.getWithTimeout( f, timeout, logger ) )
                        // don't process null values
                .filter( f -> f != null )
                        // convert the result to a map
                .forEach( (pair) -> { result.put( pair.getName(), pair.getValue() ); } );

        return result;
    }//end getListOfCourses



    /**
     * Parse the welcome page and return the list of "My Course", with their url
     *
     * @return the map
     * @throws Exception see {@link CybeConnector#getResource(String, CybeConnector.ResourceConsumer,
     *                   CybeConnector.HttpErrorHandler)}
     */
    public Map<String, String> getListOfCourses() throws Exception{
        return connector.getListOfCourses();
    }//end getListOfCourses


    /* *****************************************************************
     * private ch.derlin.cybe.lib.utils
     * ****************************************************************/

    /**
     * Test if the link found in the main course page is of interest, i.e. is either a direct link or a
     * link to a subpage potentially holding an iframe or pdf viewer.
     * <br />
     * Note that the links should come from the main-content section only, since there is a lot of garbage
     * in the sidebards that are difficult to identify/parse
     *
     * @param href the url
     * @return true if the link should be parsed further, false otherwise
     */
    private static boolean isLinkOfInterest( String href ){
        boolean res;

        res = href.matches( ".+((\\.pdf)|(resource)).*" ); // direct link or subpage with <object>
        res |= href.matches( ".+/mod/url.+" );  // link to a subpage with an iframe viewer
        res |= href.matches( ".+/mod/folder.+" );  // link to a subpage with a folder view

        return res;
    }//end isLinkOfInterest


    /*
     * get a filename from  a link of the course page
     * @param   link  the link DOM element
     * @return  the filename, or an empty string
     */
    private String getFileName( Element link ){

        String href = link.attr( "href" );
        String filename = lastPartOfUrl( href );

        if( !isNullOrEmpty( filename ) && filename.matches( ".+\\.[a-z]{2,4}$" ) ){
            return normaliseFilname( filename );
        }

        // TODO
        Element span = link.select( "span.instancename" ).first();
        span.child( 0 ).remove();
        filename = CybeUtils.normaliseFilname( span.text() ).trim();
        if( link.toString().contains( "pdf-24" ) ) filename += ".pdf";
        filename = link.select( "span.instancename" ).text();
        if( !isNullOrEmpty( filename ) ){
            return filename;
        }

        return "";
    }


    /*
     * get the result of a future (or null if the timeout is reached). The timeout is in seconds.
     */
    private static NameValuePair getWithTimeout( Future<NameValuePair> f, int timeout,
                                                 SuperSimpleLogger logger ){
        try{
            return f.get( timeout, TimeUnit.SECONDS );
        }catch( Exception e ){
            logger.error.printf( "Exception while gathering future resources: %s%n", e.getMessage() );
            return null;
        }
    }


    /* *****************************************************************
     * resource finder class
     * ****************************************************************/

    /*
     * Callable task which finds a resource given a starting url and then
     * calls the given consumer if anything is found.
     *
     * We need this class since the Cyberlearn platform uses redirects a lot
     * and loves to wrap resources into embedded viewers...
     */
    private class CallableResourceFinder implements Callable<NameValuePair> {
        // the starting url: can lead either to the resource or to an embedded viewer (or to nothing)
        String url;
        NameValuePair nameUrlPair; // the result
        ResourceConsumer consumer; // the consumer of the resource (callback)


        private CallableResourceFinder( String url, ResourceConsumer consumer ){
            this.consumer = consumer;
            this.url = url;
        }


        @Override
        public NameValuePair call() throws Exception{
            findResource( this.url );
            return nameUrlPair;
        }


        /* try to find the resource, doing potentially multiple http gets */
        private void findResource( String url ) throws Exception{

            connector.getResource( url, ( type, finalUrl, in ) -> {

                if( type.equals( ContentType.TEXT_HTML.getMimeType() ) ){
                    // we have an html page => check for an embedded resource
                    // the viewers always have a div.resourceworkaround element
                    Document doc = Jsoup.parse( IOUtils.toString( in ) );
                    Element link = doc.select( "div.resourceworkaround a" ).first();
                    String href = null;

                    if( link != null ){
                        // only one viewer, it is probably an embedded resource
                        // check the link
                        href = link.attr( "href" );
                    }else{
                        // try to find a pdf link in the subpage (inside iframe etc.)
                        href = parseSubpage( doc );
                    }


                    if( href != null ){
                        findResource( href );
                    } else{
                        // treat folders a bit differently.
                        tryFindFolder( doc );
                    }
                    // else: neither a resource, nor a "viewer", nor a folder. Nothing to do


                }else{  // we have a real resource (not html)
                    // get the name, probably the last part of the url (if it is done properly)
                    String name = CybeUtils.lastPartOfUrl( URLDecoder.decode( finalUrl, "UTF-8" ) );
                    //logger.debug.printf( " --------- found: %s%n", name );
                    // store the result
                    nameUrlPair = new BasicNameValuePair( name, finalUrl );
                    // callback: call the client
                    consumer.accept( type, name, in );
                }
            }, errorHandler );

        }

        private void tryFindFolder( final Document doc ) throws Exception {
            Element object = doc.select( "form[action$=download_folder.php]" ).first();
            if(object != null) {
                System.out.println( "Got a download folder !" );
                List<NameValuePair> postData = new ArrayList<>();
                for( Element input : object.select( "input[type=hidden]" ) ){
                    postData.add( new BasicNameValuePair( input.attr( "name" ), input.attr( "value" ) ) );
                }//end for
                String action = object.attr( "action" );
                connector.getPostResource(action, postData, (mimeType, name, in) -> {
                    if( name == null || name.startsWith( "http" ) ){
                        System.err.println( "Error getting folder: attachment name is null." );
                    }else{
                        consumer.accept( mimeType, name, in );
                    }
                }, errorHandler);

            }
        }


        private String parseSubpage( Document doc ){
            Element object;

            // could be an embedded pdf (<object data="..." ... />)
            object = doc.select( "object#resourceobject[data]" ).first();
            if( object != null ) return object.attr( "data" );

            // the embedded pdf could also be inside an iframe
            object = doc.select( "iframe#resourceobject[src]" ).first();
            if( object != null ) return object.attr( "src" );

            return null;
        }//end parseObjectTag

    }

}//end class
