package ch.derlin.cybe.lib.network;

import ch.derlin.cybe.lib.utils.SuperSimpleLogger;
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

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

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
public abstract class Connector implements Closeable{


    protected static final String DEFAULT_ENCODING = "UTF-8";
    protected static final String COOKIE_TMP_FILE_PREFIX = "CybeJava-cookies-";
    protected static final String COOKIE_TEMP_FILE_EXTENSION = ".ser";

    // TODO make it customisable => instance fields
    /* maximum number of connections in the pool */
    protected static final int MAX_CONNECTION = 50;
    /* maximum number of connections per given route */
    protected static final int MAX_CONNECTION_PER_ROUTE = 20;
    /* maximum number of connections for the platform */
    protected static final int MAX_CONNECTION_TO_TARGET = 20;

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
    protected SuperSimpleLogger logger = SuperSimpleLogger.silentInstance();

    // connections management
    protected CloseableHttpClient httpclient;
    protected PoolingHttpClientConnectionManager connectionManager;
    protected BasicCookieStore cookieStore;
    protected HttpHost targetHost;

    protected String homeUrl;
    protected boolean connected = false;


    /* *****************************************************************
     * Constructors
     * ****************************************************************/


    /**
     * Create a connector for the platform.
     *
     * @param homeUrl the platform url
     */
    public Connector( String homeUrl ) throws URISyntaxException{
        this.homeUrl = homeUrl;
        cookieStore = new BasicCookieStore();

        // create a multithreaded manager and increase the number of parallel connections
        connectionManager = new PoolingHttpClientConnectionManager();
        targetHost = new HttpHost( new URI( homeUrl ).getHost(), 80 ); // Increase max connections
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


    protected Connector(){
    }

    /* *****************************************************************
     * Connection
     * ****************************************************************/


    public abstract void connect( AuthContainer auth ) throws Exception;


    /**
     * Logout from Cyberlearn. This will invalidate all the cookies previously stored.
     *
     * @return true if the logout succeeded, false otherwise
     * @throws Exception
     */
    public boolean logout() throws Exception{
        HttpGet get = new HttpGet( String.format( "%s/login/logout.php", homeUrl ) );

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


    /**
     * @return true if the client is currently connected to
     * the platform
     */
    public boolean isConnected(){
        return connected;
    }


    /**
     * @return the authenticated http client in case we need to do "raw"
     * requests ourselves
     */
    public HttpClient getHttpclient(){
        return httpclient;
    }


    /**
     * @return the home url (welcome page) of the platform
     */
    public String getHomeUrl(){
        return targetHost.toURI();
    }


    /**
     * @param logger the logger to use
     */
    public void setLogger( SuperSimpleLogger logger ){
        this.logger = logger;
    }

    /* *****************************************************************
     * Resources download
     * ****************************************************************/


    /**
     * Parse the welcome page and return the list of "My Course", with their url
     *
     * @return the map
     * @throws Exception see {@link Connector#getResource(String, ResourceConsumer, HttpErrorHandler)}
     */
    public abstract Map<String, String> getListOfCourses() throws Exception;


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
     * See {@link #getResource(String, ResourceConsumer, HttpErrorHandler)}
     */
    public void getResource( String url, ResourceConsumer consumer ) throws Exception{
        getResource( url, consumer, null );
    }//end getResource

    /* *****************************************************************
     * utils
     * ****************************************************************/


    /**
     * decode the HTML entity of an http response
     *
     * @param response the HTTP response
     * @return the html entity as a #DEFAULT_ENCODING string
     * @throws IOException the entity could not be decoded
     */
    protected String readEntity( HttpResponse response ) throws IOException{
        HttpEntity entity = response.getEntity();
        String content = EntityUtils.toString( entity, DEFAULT_ENCODING );
        EntityUtils.consume( entity );
        return content;
    }


    /**
     * do a get request
     *
     * @param url the url
     * @return the HTTP response
     * @throws IOException
     */
    protected HttpResponse doGet( String url ) throws IOException{
        return httpclient.execute( new HttpGet( url ) );
    }


    /**
     * do a post request
     *
     * @param url  the url
     * @param args the post parameters: key, value [, key, value]
     * @return the HTTP response
     * @throws Exception
     */
    protected HttpResponse doPost( String url, String... args ) throws Exception{
        HttpPost post = new HttpPost( url );
        List<NameValuePair> postKeyValuePairs = new ArrayList<>();
        if( args.length % 2 != 0 ){
            return null; // todo
        }
        for( int i = 0; i < args.length; i += 2 ){
            postKeyValuePairs.add( new BasicNameValuePair( args[ i ], args[ i + 1 ] ) );
        }//end for
        post.setEntity( new UrlEncodedFormEntity( postKeyValuePairs ) );
        return httpclient.execute( post );
    }

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
    protected boolean checkForViableCookies() throws IOException{
        File file = new File( getCookieTmpPath() );
        return file.exists() && file.length() > 0 && restoreCookies( file.getAbsolutePath() );
    }//end checkForViableCookies


    /* save the cookies to a tmp file */
    protected void saveCookiesToTempFile(){
        saveCookies( getCookieTmpPath() );
    }


    /* get the tmp filename used to store the cookies (one per platform) */
    protected String getCookieTmpPath(){
        return String.format( "%s%s%s%s%s", System.getProperty( "java.io.tmpdir" ), File.separator,
                COOKIE_TMP_FILE_PREFIX, getOrganisationName(), COOKIE_TEMP_FILE_EXTENSION );
    }


    /**
     * get the organisation name, i.e. moodle fribourg, etc.
     *
     * @return
     */
    public abstract String getOrganisationName();

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
