package ch.derlin.cybe.cmdline.parsing;

import java.util.Stack;

/**
 * Represents a commandline option taking one string parameter.
 * @author: Lucy Linder
 * @date: 21.09.2014
 */
public class CliStringOption extends CliOption<String>{

    public CliStringOption( String defaultValue ){
        super( defaultValue, null );
    }


    @Override
    protected void parse( Stack<String> remainingArgs ) throws Exception{
        if( remainingArgs.isEmpty() ){
            // no more arguments..
            throw  new Exception( "missing value." );

        }else{
            // just get the string following the option name
            value = remainingArgs.pop();
        }
    }
}//end class
