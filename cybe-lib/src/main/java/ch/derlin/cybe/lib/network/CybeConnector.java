package ch.derlin.cybe.lib.network;

import ch.derlin.cybe.lib.props.PlatformLinks;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A connector for the Cyberlearn and Moodle platforms of Switzerland providing an http client able to authenticate and
 * download resources.
 * <p/>
 * The httpClient offers a pool of reusable tcp connections and can be used in a multithreaded environment.
 *
 * @author: Lucy Linder
 * @date: 18.06.2014
 */
public class CybeConnector extends Connector{

    private PlatformLinks platformLinks; // container for home and login/logout urls



    /* *****************************************************************
     * Constructors
     * ****************************************************************/


    /**
     * Create a connector for the platform.
     *
     * @param platform the platform settings
     */
    public CybeConnector( PlatformLinks platform ) throws URISyntaxException{
        super(platform.homeUrl());
    }

    /* *****************************************************************
     * Connection
     * ****************************************************************/

    @Override
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


    @Override
    public Map<String, String> getListOfCourses() throws Exception{

        final Map<String, String> courses = new HashMap<>();

        getResource( getHomeUrl(), ( ct, n, i ) -> {
            String welcomePage = IOUtils.toString( i );
            Document doc = Jsoup.parse( welcomePage );
            doc.select( "li.type_course a[title]" ).forEach( ( a ) -> {
                courses.put( a.attr( "title" ), a.attr( "href" ) );
            } );
        }, logger.error::printf );

        return courses;
    }//end getListOfCourses


    @Override
    public String getOrganisationName(){
        return platformLinks.organisationName();
    }


}//end class
