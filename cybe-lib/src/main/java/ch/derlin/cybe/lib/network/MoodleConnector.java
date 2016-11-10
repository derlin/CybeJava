package ch.derlin.cybe.lib.network;

import ch.derlin.cybe.lib.props.PlatformLinks;
import ch.derlin.cybe.lib.utils.SuperSimpleLogger;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import org.jsoup.select.Elements;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
public class MoodleConnector implements Closeable{


    private static final String DEFAULT_ENCODING = "UTF-8";
    private static final String COOKIE_TMP_FILE_PREFIX = "cybe-cookies-";
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

    // ----------------------------------------------------
    private String homeUrl = "https://moodle.msengineering.ch";
    private String sesskey;

    /* *****************************************************************
     * Constructors
     * ****************************************************************/


    /**
     * Create a connector for the platform.
     *
     * @param platform the platform settings
     */
    public MoodleConnector( PlatformLinks platform ) throws URISyntaxException{
        platformLinks = platform;
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


    private MoodleConnector(){
    }


    public static void main( String[] args ) throws Exception{
        MoodleConnector conn = new MoodleConnector( null );
        AuthContainer auth = new AuthContainer(){
            @Override
            public String username(){
                return args[0];
            }


            @Override
            public String password(){
                return args[1];
            }
        };
        conn.connect( auth );
        conn.getListOfCourses();
    }//end main
    /* *****************************************************************
     * Connection
     * ****************************************************************/


    public void connect( AuthContainer auth ) throws Exception{

        if( checkForViableCookies() ){
            // check that the cookies are still valid
            getResource( homeUrl, ( t, n, i ) -> {
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


    private void authenticate( AuthContainer auth ) throws Exception{
        HttpResponse response = doGet( homeUrl );
        HttpEntity entity;
        String content;
        Document doc;

        // get the organisation page
        response = doPost( "https://wayf.switch.ch/SWITCHaai/WAYF?entityID=https%3A%2F%2Fmoodle.msengineering" +
                ".ch%2Fshibboleth&return=https%3A%2F%2Fmoodle.msengineering.ch%2FShibboleth" +
                ".sso%2FLogin%3FSAMLDS%3D1%26target%3Dhttps%253A%252F%252Fmoodle.msengineering" +
                ".ch%252Fauth%252Fshibboleth%252Findex.php",  //
                "request_type", "embedded",  //
                "user_idp", "https://aai-logon.hes-so.ch/idp/shibboleth",  //
                "Login", "Login" );
        System.out.println( "organisation page " + response.getStatusLine() );

        // get login page
        response = doPost( "https://moodle.msengineering.ch/Shibboleth.sso/Login?SAMLDS=1&target=https%3A%2F%2Fmoodle" +
                ".msengineering.ch%2Fauth%2Fshibboleth%2Findex.php&entityID=https%3A%2F%2Faai-logon.hes-so" +
                ".ch%2Fidp%2Fshibboleth",  //
                "SAMLDS", "1",  //
                "target", "https://moodle.msengineering.ch/auth/shibboleth/index.php", //
                "entityID", "https://aai-logon.hes-so.ch/idp/shibboleth" );
        System.out.println( "login page " + response.getStatusLine() );
        content = readEntity( response );

        // get the "temp" param from js body
        Pattern r = Pattern.compile( ".*temp=(\\d+)" );
        Matcher m = r.matcher( content );
        m.find();  // needs to be called before group()
        String temp = m.group( 1 );
        System.out.println( "temp token " + temp );

        // do the ajax call to LoginBonus
        response = doGet( "https://aai-logon.hes-so.ch/idp/isLoginBonus.jsp?uid=" + auth.username() + "&temp=" + temp );

        // do the auth
        response = doPost( "https://aai-logon.hes-so.ch/idp/profile/SAML2/Redirect/SSO?execution=e2s1", "j_username", auth.username(), "j_password", auth.password(), "_eventId_proceed", "" );
        System.out.println( "auth " + response.getStatusLine() );

        // get the hidden SAML fields
        content = readEntity( response );
        List<String> hiddenInputs = new ArrayList<>();
        doc = Jsoup.parse( content);
        for( Element input : doc.select( "input[type=hidden]" ) ){
            hiddenInputs.add( input.attr( "name" ) );
            hiddenInputs.add( input.attr( "value" ) );
        }//end for

        // finish the auth
        response = doPost( "https://moodle.msengineering.ch/Shibboleth.sso/SAML2/POST", hiddenInputs.toArray( new
                String[]{} ) );
        System.out.println( "finish auth " + response.getStatusLine() );

        // get home url again
        response = doGet( homeUrl );
        System.out.println( "home " + response.getStatusLine() );
        content = readEntity( response );
        doc = Jsoup.parse( content );
        Elements logoutLink = doc.select( ".logininfo a[href*=\"logout\"]" );
        System.out.println( logoutLink.get( 0 ) );
    }

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

    public String getSesskey() {
        if(sesskey == null){
            try{
                findSesskeyInHomePage();
            }catch( Exception e ){
                logger.error.printf( "error finding sesskey in home page" );
            }
        }
        return sesskey;
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
     * See {@link #getResource(String, MoodleConnector.ResourceConsumer,
     * MoodleConnector.HttpErrorHandler)}
     */
    public void getResource( String url, ResourceConsumer consumer ) throws Exception{
        getResource( url, consumer, null );
    }//end getResource


    /**
     * Parse the welcome page and return the list of "My Course", with their url
     *
     * @return the map
     * @throws Exception see {@link CybeConnector#getResource(String, CybeConnector.ResourceConsumer,
     *                   CybeConnector.HttpErrorHandler)}
     */
    public Map<String, String> getListOfCourses() throws Exception{
        if(sesskey == null) findSesskeyInHomePage();
        final Map<String, String> courses = new HashMap<>();
        HttpResponse response = doPost( "https://moodle.msengineering.ch/lib/ajax/getnavbranch.php", "elementid",
                "expandable_branch_0_mycourses", "id", "mycourses", "type", "0", "sesskey", sesskey, "instance",
                "5724" );

        String content = readEntity( response );
        JsonObject json = new JsonParser().parse( content ).getAsJsonObject();

        for( JsonElement child : json.get( "children" ).getAsJsonArray() ){
            JsonObject course = child.getAsJsonObject();
                 courses.put( course.get( "title" ).getAsString(), course.get( "link" ).getAsString() );
        }//end for

        return courses;
    }//end getListOfCourses

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
                COOKIE_TMP_FILE_PREFIX, "moodle", COOKIE_TEMP_FILE_EXTENSION );
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
         * @param contentType the content-type, see {@link ContentType}
         * @param url         the url of the resource. It could be different from the requested one due to redirects
         * @param stream      the stream. Use {@link IOUtils#toString(InputStream)} or {@link
         *                    EntityUtils#toString(HttpEntity, java.nio.charset.Charset)} if you need
         *                    to
         *                    convert it to a string. Note that you don't need to close it, it will be handled by the
         *                    connector directly.
         * @throws Exception
         */
        void accept( String contentType, String url, InputStream stream ) throws Exception;
    }


    /* *****************************************************************
     * private utils
     * ****************************************************************/


    private String readEntity(HttpResponse response) throws IOException{
        HttpEntity entity = response.getEntity();
        String content = EntityUtils.toString( entity, DEFAULT_ENCODING );
        EntityUtils.consume( entity );
        return content;
    }

    private HttpResponse doGet( String url ) throws IOException{
        return httpclient.execute( new HttpGet( url ) );
    }


    private HttpResponse doPost( String url, String... args ) throws Exception{
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

    private void findSesskeyInHomePage() throws Exception{
        HttpResponse response = doGet( homeUrl );
        String content = readEntity( response );

        // get the "temp" param from js body
        Pattern r = Pattern.compile( ".*sesskey\"\\s*:\\s*\"(\\w+)\".*" );
        Matcher m = r.matcher( content );
        m.find();  // needs to be called before group()
        if(m.groupCount() < 1) throw new Exception( "sesskey not found" );
        sesskey = m.group( 1 );
    }

}//end class
