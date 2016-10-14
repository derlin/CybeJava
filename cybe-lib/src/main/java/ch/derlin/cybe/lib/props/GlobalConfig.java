package ch.derlin.cybe.lib.props;

import com.google.gson.annotations.SerializedName;
import ch.derlin.cybe.lib.gson.GsonContainable;
import ch.derlin.cybe.lib.gson.GsonUtils;
import ch.derlin.cybe.lib.network.AuthContainer;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * @author: Lucy Linder
 * @date: 19.06.2014
 */
public class GlobalConfig implements GsonContainable, AuthContainer{

    public static final List<String> SUPPORTED_PLATFORMS = Arrays.asList( "cyberlearn.hes-so", "moodle.unil" );

    public static final String GLOBAL_CONFIG_FILEPATH = //
            System.getProperty( "user.home" ) + File.separator + ".cybeconf";

    private String username;

    @SerializedName( "password" )
    private String pass;

    @SerializedName( "target platform" )
    private String platform;


    public GlobalConfig(){

    }//end constructor


    public GlobalConfig( String platform, String username, String pass ){
        this.username = username;
        this.pass = pass;
        this.platform = platform;
    }


    public static GlobalConfig getInstance(){
        return exists() ? ( GlobalConfig ) GsonUtils.getJsonFromFile( GLOBAL_CONFIG_FILEPATH,
                new GlobalConfig() ) : null;
    }


    public static boolean exists(){
        return new File( GLOBAL_CONFIG_FILEPATH ).exists();
    }//end exists


    public boolean save(){
        return GsonUtils.writeJsonFile( GLOBAL_CONFIG_FILEPATH, this, true );
    }//end save

    /* *****************************************************************
     * getter/setter
     * ****************************************************************/


    public String username(){
        return username;
    }


    public void setUsername( String username ){
        this.username = username;
    }


    public String password(){
        return perlPack( pass );
    }


    public void setPassword( String pass ){
        this.pass = perlUnpack( pass );
    }


    public String getPlatform(){
        return platform;
    }


    public void setPlatform( String platform ){
        this.platform = platform;
    }

    /* *****************************************************************
     * ch.derlin.cybe.lib.utils
     * ****************************************************************/


    /**
     * The java equivalent to
     * <code>
     * pack "H*", $vartoconvert
     * </code>
     *
     * @param hex the hexadecimal representation of str
     * @return str the string equivalent
     */
    public static String perlPack( String hex ){
        StringBuilder builder = new StringBuilder();

        for( int i = 0; i < hex.length() - 1; i += 2 ){
            //grab the hex in pairs
            String output = hex.substring( i, ( i + 2 ) );
            //convert hex to decimal
            int decimal = Integer.parseInt( output, 16 );
            //convert the decimal to character
            builder.append( ( char ) decimal );

        }
        return builder.toString();
    }//end packH

    //----------------------------------------------------


    /**
     * The java equivalent to
     * <code>
     * unpack "H*", $vartoconvert
     * </code>
     *
     * @param str the string
     * @return the hexadecimal representation of str
     */
    public static String perlUnpack( String str ){
        StringBuilder builder = new StringBuilder();

        for( char c : str.toCharArray() ){
            builder.append( Integer.toHexString( c ) );
        }//end for

        return builder.toString();
    }//end unpack


}//end class
