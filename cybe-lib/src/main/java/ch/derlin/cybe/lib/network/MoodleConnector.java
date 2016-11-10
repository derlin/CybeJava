package ch.derlin.cybe.lib.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A connector for the Cyberlearn and Moodle platforms of Switzerland providing an http client able to authenticate and
 * download resources.
 * <p/>
 * The httpClient offers a pool of reusable tcp connections and can be used in a multithreaded environment.
 *
 * @author: Lucy Linder
 * @date: 18.06.2014
 */
public class MoodleConnector extends Connector{

    private String sesskey;

    /* *****************************************************************
     * Constructors
     * ****************************************************************/


    /**
     * Create a connector for the platform.
     */
    public MoodleConnector(String homeUrl) throws Exception{
        super( homeUrl );
    }

    /* *****************************************************************
     * Connection
     * ****************************************************************/

    @Override
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
        HttpResponse response = doGet( homeUrl );  // get cookies
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
     * getting courses
     * ****************************************************************/

    @Override
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
     * other
     * ****************************************************************/


    @Override
    public String getOrganisationName(){
        return "msengineering";
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
