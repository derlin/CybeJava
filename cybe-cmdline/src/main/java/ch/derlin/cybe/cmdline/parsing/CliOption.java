package ch.derlin.cybe.cmdline.parsing;

import java.util.Stack;

/**
 * Represents a command line option see {@link CliParser}.
 * @author: Lucy Linder
 * @date: 21.09.2014
 */
public class CliOption<E>{

    @FunctionalInterface
    public interface ICliOptionConsumer<E>{
        // takes the raw string and converts it to E
        public E process( String s ) throws Exception;
    }

    protected ICliOptionConsumer<E> consumer;
    protected E value;


    /**
     * create a cli option with the given default value.
     * @param defaultValue  the default value
     * @param consumer a converter from the raw input string to E
     */
    public CliOption( E defaultValue, ICliOptionConsumer<E> consumer ){
        this.consumer = consumer;
        this.value = defaultValue;
    }


    /**
     * remove the next value from the arguments stack and process it
     * @param remainingArgs  the list of remaining arguments from the cli
     * @throws Exception if there is no more arguments left
     */
    protected void parse( Stack<String> remainingArgs ) throws Exception{
        value = consumer.process( remainingArgs.pop() );
    }


    /**
     * @return the processed value (or the default)
     */
    public E getValue(){
        return value;
    }

}//end class
