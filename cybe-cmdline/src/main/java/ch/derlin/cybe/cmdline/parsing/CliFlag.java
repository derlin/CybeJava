package ch.derlin.cybe.cmdline.parsing;

import java.util.Stack;

/**
 * An command-line option flag, i.e. taking no arguments.
 * @author: Lucy Linder
 * @date: 21.09.2014
 */
public class CliFlag extends CliOption<Boolean>{

    Runnable callback;

    public CliFlag(){
        // no converter needed
        super( false, null );
    }

    public CliFlag(Runnable callback ){
        this( );
        this.callback = callback;
    }


    @Override
    protected void parse( Stack<String> remainingArgs ) throws Exception{
        // do not pop anything, but just switch the flag on
        value = true;
        if( callback != null ) callback.run();
    }

}//end class
