package ch.derlin.cybe.lib.network;

import org.apache.commons.io.IOUtils;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.*;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ch.derlin.cybe.lib.props.PlatformLinks;
import ch.derlin.cybe.lib.utils.SuperSimpleLogger;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static ch.derlin.cybe.lib.utils.CybeUtils.*;

/**
 * A connector for the Cyberlearn and Moodle platforms of Switzerland providing an http client able to authenticate and
 * download resources.
 * <p/>
 * The httpClient offers a pool of reusable tcp connections and can be used in a multithreaded environment.
 *
 * @author: Lucy Linder
 * @date: 18.06.2014
 */
public class CybeConnector implements Closeable{


    private static final String DEFAULT_ENCODING = "UTF-8";
    private static final String COOKIE_TMP_FILE_PREFIX = "CybeJava-cookies-";
    private static final String COOKIE_TEMP_FILE_EXTENSION = ".ser";

    // TODO make it customisable => instance fields
    /* maximum number of connections in the pool */
    private static final int MAX_CONNECTION = 50;
    /* maximum number of connections per given route */
    private static final int MAX_CONNECTION_PER_ROUTE = 20;
    /* maximum number of connections for the platform */
    private static final int MAX_CONNECTION_TO_TARGET = 20;

    /**
     * Simple consumer which write the httpGet content into a file in the current directory.
     * The name of the file is the last part of the url, or a random name if the url ends with "/".
     */
    public static final ResourceConsumer BASIC_FILE_WRITER = ( type, url, in ) -> {
        String name = normaliseFilname( lastPartOfUrl( url ) );
        if( isNullOrEmpty( name ) ) name = File.createTempFile( "cybe-", "" ).getName();
        try( FileOutputStream out = new FileOutputStream( new File( name ) ) ){
            IOUtils.copy( in, out );
        }catch( Exception e ){
            e.printStackTrace();
        }
    };

    //----------------------------------------------------

    // the logger used by this class. Default to "null"
    private SuperSimpleLogger logger = SuperSimpleLogger.silentInstance();

    // connections management
    private CloseableHttpClient httpclient;
    private PoolingHttpClientConnectionManager connectionManager;
    private BasicCookieStore cookieStore;
    private HttpHost targetHost;

    private PlatformLinks platformLinks; // container for home and login/logout urls
    private boolean connected = false;


    /* *****************************************************************
     * Constructors
     * ****************************************************************/


    /**
     * Create a connector for the platform.
     *
     * @param platform the platform settings
     */
    public CybeConnector( PlatformLinks platform ) throws URISyntaxException{
        platformLinks = platform;
        cookieStore = new BasicCookieStore();

        // create a multithreaded manager and increase the number of parallel connections
        connectionManager = new PoolingHttpClientConnectionManager();
        targetHost = new HttpHost( new URI( platformLinks.homeUrl() ).getHost(), 80 ); // Increase max connections
        // for cybe:80
        connectionManager.setMaxTotal( MAX_CONNECTION );  // Increase max total connection
        connectionManager.setDefaultMaxPerRoute( MAX_CONNECTION_PER_ROUTE );  // Increase default max connection per
        // route
        connectionManager.setMaxPerRoute( new HttpRoute( targetHost ), MAX_CONNECTION_TO_TARGET );

        httpclient = HttpClients.custom()   //
                .setDefaultCookieStore( cookieStore )   //
                .setRedirectStrategy( new LaxRedirectStrategy() )       //
                .setConnectionManager( connectionManager )    //
                .build();
    }


    private CybeConnector(){
    }

    /* *****************************************************************
     * Connection
     * ****************************************************************/


    public void connect( AuthContainer auth ) throws Exception{

        if( checkForViableCookies() ){
            // check that the cookies are still valid
            getResource( platformLinks.homeUrl(), ( t, n, i ) -> {
                // if we are authenticated, a logout button should be present
                // TODO: just check that the url ends with /my
                connected = Jsoup.parse( IOUtils.toString( i ) ).select( "div.logininfo a[href*=logout]" ).size() > 0;
                assert ( !connected || n.endsWith( "my/" ) );
            }, null );
        }

        if( connected ){
            System.out.println( "Valid cookies found. Skipping authentication process." );
        }else{
            authenticate( auth ); // no viable cookies, do the full auth again
        }
        connected = true; // TODO better check ??

        saveCookiesToTempFile();  // save the cookies for later use
        logger.info.printf( "%nConnected.%n" );
    }


    /* do the full three-way authentication */
    private void authenticate( AuthContainer auth ) throws Exception{
        final List<NameValuePair> postKeyValuePairs = new ArrayList<>();
        // fill the post values with username, pass and organisation
        postKeyValuePairs.add( new BasicNameValuePair( "user_idp", platformLinks.organisationFormIdp() ) );
        postKeyValuePairs.add( new BasicNameValuePair( "j_username", auth.username() ) );
        postKeyValuePairs.add( new BasicNameValuePair( "j_password", auth.password() ) );

        ThrowableConsumer<String> doPostFunction = ( url ) -> {

            HttpPost httppost = new HttpPost( url );
            try{
                // create and execute the post
                httppost.setEntity( new UrlEncodedFormEntity( postKeyValuePairs ) );
                HttpResponse response = httpclient.execute( httppost );

                logger.debug.printf( "got %s (%s)%n", url, response.getStatusLine() );
                // check the answer is 200 - OK
                if( response.getStatusLine().getStatusCode() != HttpStatus.SC_OK ){
                    throw new Exception( String.format( "post to %s failed. Status line = %s%n", url,
                            response.getStatusLine() ) );
                }

                // get the hidden SAML fields
                HttpEntity entity = response.getEntity();
                Document doc = Jsoup.parse( EntityUtils.toString( entity, DEFAULT_ENCODING ) );
                for( Element input : doc.select( "input[type=hidden]" ) ){
                    postKeyValuePairs.add( new BasicNameValuePair( input.attr( "name" ), input.attr( "value" ) ) );
                }//end for

                // close the entity streams
                EntityUtils.consume( entity );

                // if no error, we are connected

            }finally{
                httppost.releaseConnection();
            }
        };

        // do the three-way auth process
        logger.info.printf( "Filling organisation form... " );
        doPostFunction.accept( platformLinks.organisationFormUrl() );
        logger.debug.printf( connectionManager.getTotalStats().toString() );

        logger.info.printf( "Ok.%nFilling authentication form with username %s... ", auth.username() );
        doPostFunction.accept( platformLinks.authFormUrl() );
        logger.debug.printf( connectionManager.getTotalStats().toString() );

        logger.debug.printf( connectionManager.getTotalStats().toString() );
        logger.info.printf( "Ok. %nConfirming auth...%n" );
        doPostFunction.accept( platformLinks.confirmAuthUrl() );
        System.out.println( connectionManager.getTotalStats() );

    }//end auth


    /**
     * Logout from Cyberlearn. This will invalidate all the cookies previously stored.
     *
     * @return true if the logout succeeded, false otherwise
     * @throws Exception
     */
    public boolean logout() throws Exception{
        HttpGet get = new HttpGet( platformLinks.logoutUrl() );

        try{
            try( CloseableHttpResponse response = httpclient.execute( get ) ){
                if( response.getStatusLine().getStatusCode() == HttpStatus.SC_OK ){
                    logger.info.printf( "Disconnected.%n" );
                    connected = false;
                    return true;
                }else{
                    return false;
                }
            }

        }finally{
            get.releaseConnection();
        }

    }//end disconnect


    /**
     * Close the connector and release all its resources
     */
    public void close(){
        try{
            connectionManager.shutdown();
            httpclient.close();
            connected = false;
        }catch( IOException e ){
            e.printStackTrace();
        }
    }//end close


    /* *****************************************************************
     * getter and such
     * ****************************************************************/


    public boolean isConnected(){
        return connected;
    }


    public HttpClient getHttpclient(){
        return httpclient;
    }


    public String getHomeUrl(){
        return targetHost.toURI();
    }


    public void setLogger( SuperSimpleLogger logger ){
        this.logger = logger;
    }

    /* *****************************************************************
     * Resources download
     * ****************************************************************/


    /**
     * Get a resource from the platform.
     *
     * @param url          the url
     * @param consumer     the consumer
     * @param errorHandler the error handler
     * @throws Exception
     */
    public void getResource( String url, ResourceConsumer consumer, HttpErrorHandler errorHandler ) throws Exception{
        BasicHttpContext context = new BasicHttpContext();
        HttpGet get = new HttpGet( url );
        logger.error.printf( "%s %n", connectionManager.getTotalStats() );
        try( CloseableHttpResponse response = httpclient.execute( get, context ) ){

            if( response.getStatusLine().getStatusCode() == HttpStatus.SC_OK ){
                HttpEntity entity = response.getEntity();
                // if there was an indirection, get the final url
                RedirectLocations redirects = ( RedirectLocations ) context //
                        .getAttribute( "http.protocol.redirect-locations" );

                if( redirects != null ){
                    url = redirects.get( redirects.size() - 1 ).toString();
                    //url = ( ( HttpRequestWrapper ) context.getAttribute( "http.request" ) ).getURI().toString();
                }

                String mimeType = ContentType.getOrDefault( response.getEntity() ).getMimeType();
                consumer.accept( mimeType, //
                        url,  //
                        entity.getContent() );
                EntityUtils.consume( entity );

            }else{
                if( errorHandler != null ) errorHandler.handleError( url, response );
            }

        }finally{
            get.releaseConnection();

        }

    }//end getResource


    /**
     * See {@link #getResource(String, CybeConnector.ResourceConsumer,
     * CybeConnector.HttpErrorHandler)}
     */
    public void getResource( String url, ResourceConsumer consumer ) throws Exception{
        getResource( url, consumer, null );
    }//end getResource


    /* *****************************************************************
     * cookie handling
     * ****************************************************************/


    /**
     * Serialize the cookies to the given file
     *
     * @param path the filepath
     */
    public void saveCookies( String path ){
        if( cookieStore != null ){
            try{
                ObjectOutputStream out = new ObjectOutputStream( new FileOutputStream( path ) );
                out.writeObject( cookieStore );
                out.close();
                logger.info.printf( "Serialized cookieStore in %s%n", path );

            }catch( IOException e ){
                e.printStackTrace();
            }
        }
    }//end saveCookies


    /**
     * Restore cookies from the given file.
     *
     * @param path the filepath
     * @return true if the operation succeeded, false otherwise
     */
    public boolean restoreCookies( String path ){
        try{
            ObjectInputStream inputStream = new ObjectInputStream( new FileInputStream( path ) );
            BasicCookieStore cookies = ( BasicCookieStore ) inputStream.readObject();
            cookies.clearExpired( new Date() );
            logger.info.printf( "Deserialized cookieStore from %s%n", path );

            for( Cookie cookie : cookies.getCookies() ){
                cookieStore.addCookie( cookie );
            }//end for

            return !cookieStore.getCookies().isEmpty() && cookieStore.toString().contains( "_saml_idp" );
        }catch( IOException | ClassNotFoundException e ){
            e.printStackTrace();
        }
        return false;
    }//end restoreCookies


    //----------------------------------------------------


    /* restore cookies from tmp file, if any */
    private boolean checkForViableCookies() throws IOException{
        File file = new File( getCookieTmpPath() );
        return file.exists() && file.length() > 0 && restoreCookies( file.getAbsolutePath() );
    }//end checkForViableCookies


    /* save the cookies to a tmp file */
    private void saveCookiesToTempFile(){
        saveCookies( getCookieTmpPath() );
    }


    /* get the tmp filename used to store the cookies (one per platform) */
    private String getCookieTmpPath(){
        return String.format( "%s%s%s%s%s", System.getProperty( "java.io.tmpdir" ), File.separator,
                COOKIE_TMP_FILE_PREFIX, platformLinks.organisationName(), COOKIE_TEMP_FILE_EXTENSION );
    }

    /* *****************************************************************
     * Functional interfaces
     * ****************************************************************/

    @FunctionalInterface
    public interface ThrowableConsumer<A>{
        void accept( A a ) throws Exception;
    }

    @FunctionalInterface
    public interface HttpErrorHandler{
        /**
         * Process a response with a status code different from {@link HttpStatus#SC_OK}.
         *
         * @param entity the entity of the response. Note that you don't need to consume it, it will be handled
         *               directly
         *               by the connector.
         */
        void handleError( String url, HttpResponse entity );
    }

    @FunctionalInterface
    public interface ResourceConsumer{
        /**
         * Process a resource.
         *
         * @param contentType the content-type, see {@link org.apache.http.entity.ContentType}
         * @param url         the url of the resource. It could be different from the requested one due to redirects
         * @param stream      the stream. Use {@link IOUtils#toString(java.io.InputStream)} or {@link
         *                    EntityUtils#toString(org.apache.http.HttpEntity, java.nio.charset.Charset)} if you need
         *                    to
         *                    convert it to a string. Note that you don't need to close it, it will be handled by the
         *                    connector directly.
         * @throws Exception
         */
        void accept( String contentType, String url, InputStream stream ) throws Exception;
    }

}//end class
