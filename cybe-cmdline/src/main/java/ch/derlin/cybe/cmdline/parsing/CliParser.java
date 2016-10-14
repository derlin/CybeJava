package ch.derlin.cybe.cmdline.parsing;

import java.io.File;
import java.util.*;

/**
 * Utility class to ease the parsing of command line arguments.
 * <br />
 * There are three kind of options:
 * <ul>
 * <li>basic string option; a simple option requiring a string argument</li>
 * <li>flag: an option which sets a boolean to true when encountered. It is possible to provide a
 * callback method to
 * take some action if the flag is present</li>
 * <li>more complex options: for example, an option requiring a valid filepath. In this case, the
 * user must provide a
 * consumer method, i.e. a method able to convert the string to whatever. If the argument is not
 * correct, the method
 * can
 * throw an exception, which will stop the parsing. </li>
 * <br />
 * Each option or flag processed will be removed from the argument list. Inputs which do not match
 * any registered
 * option
 * will be gathered in a list and returned upon successful parsing.
 * Example:
 * <pre>
 * CliParser parser = new CliParser();
 *
 * // create options
 * // create options
 * CliFlag silentFlag = new CliFlag();
 * CliStringOption stringOption = new CliStringOption( "default" );
 *
 * CliOption<File> fileOption = new CliOption<File>( null, s -> {
 *      File f = new File( s );
 *      if( !f.exists() ) throw new Exception( "file does not exist" );
 *      return f;
 * } );
 *
 * // register options
 * parser.registerOption( "-s", silentFlag );
 * parser.registerOption( "-truc", stringOption );
 * parser.registerOption( "--file", fileOption );
 *
 * // parse
 * List&lt;String&gt; remainingArgs = parser.parse(args);
 * </pre>
 * </ul>
 *
 * @author: Lucy Linder
 * @date: 21.09.2014
 */
public class CliParser{

    private Map<String, CliOption> options = new HashMap<>();


    public static void main( String[] args ){
        CliParser parser = new CliParser();

        // create options
        CliFlag silentFlag = new CliFlag();
        CliStringOption stringOption = new CliStringOption( "default" );
        CliOption<File> fileOption = new CliOption<File>( null, s -> {
            File f = new File( s );
            if( !f.exists() ) throw new Exception( "file does not exist" );
            return f;
        } );
    }//end main


    /**
     * parse the given commandline arguments
     *
     * @param args the cli arguments
     * @return all the inputs which did not match any option, as a list
     * @throws Exception
     */
    public List<String> parse( String[] args ) throws Exception{

        // use a stack since we process arguments one by one
        Stack<String> stack = new Stack<>();
        for( int i = args.length - 1; i >= 0; i-- ){
            stack.push( args[ i ] );
        }//end for

        // list to store input which does not correspond to a registered option
        List<String> nonParsedArgs = new ArrayList<>();

        while( !stack.isEmpty() ){
            String curArg = stack.pop();
            if( options.containsKey( curArg ) ){
                try{
                    CliOption cliOption = options.get( curArg );
                    cliOption.parse( stack );

                }catch( Exception e ){
                    throw new Exception( curArg + ": " + e.getMessage() );
                }
            }else{
                // does not match an option
                nonParsedArgs.add( curArg );
            }
        }//end while

        return nonParsedArgs;
    }//end parse


    /**
     * register a new option. Example:
     * <pre>
     *      CliStringOption stringOption = new CliStringOption( "default" );
     *      parser.registerOption( "-foo", stringOption );
     *      // parse input "-foo arg other args"
     *      stringOption.getValue(); // will return "arg"
     * </pre>
     *
     * @param optionName the name, i.e. what the user enter at the cli
     * @param option     the option associated to the argument
     */
    public void registerOption( String optionName, CliOption option ){
        options.put( optionName, option );
    }//end registerOption
}//end class
