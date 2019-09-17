package ch.derlin.cybe.cmdline;

import java.io.Console;
import java.util.InputMismatchException;
import java.util.List;
import java.util.Scanner;
import java.util.function.Predicate;

/**
 * @author: Lucy Linder
 * @date: 29.06.2014
 */
public class CmdUtils{

    public enum ChoiceMismatchAction{RETURN_NULL, IGNORE, QUIT}

    @FunctionalInterface
    public interface ChoiceMismatchHandler{
        ChoiceMismatchAction handle( String mismatch );
    }

    public static boolean isQuitInput( String s ){
        return s.toLowerCase().matches( "(quit)" +
                "" + "|(exit)|(q)" );
    }//end isQuitInput



    public static String prompt( Scanner in, String message, Predicate<String> validator ){
        String answer;
        do{
            System.out.print( message );
            answer = in.nextLine();
        }while( !validator.test( answer ) );

        return answer;
    }//end prompt

    public static String promptPassword( Scanner in, String message, Predicate<String> validator ){
        // c might be null when running inside the IDE...
        Console c = System.console();
        if( c == null ) return prompt( in, message, validator );

        String answer;
        do{
            System.out.print( message );
            answer = new String( c.readPassword() );
        }while( !validator.test( answer ) );

        return answer;
    }//end prompt


    public static String choice( Scanner in, String prompt, List<String> choices ){
        return choice( in, prompt, choices, ( s ) -> ChoiceMismatchAction.IGNORE );
    }


    public static String choice( Scanner in, String prompt, List<String> choices,
                                 ChoiceMismatchHandler mismatchHandler ){
        int i = 0, answer = -1;

        System.out.println( prompt + " " );
        for( String choice : choices )
            System.out.printf( "  [%d] %s%n", i++, choice );

        System.out.println();

        while( answer < 0 || answer >= i ){
            System.out.printf( "Your choice [0-%d] ", i - 1 );
            try{
                answer = in.nextInt();
                in.nextLine();
            }catch( InputMismatchException ignored ){
                String mismatch = in.nextLine();
                ChoiceMismatchAction action = mismatchHandler.handle( mismatch );

                if( action == ChoiceMismatchAction.RETURN_NULL ) return null;
                if( action == ChoiceMismatchAction.QUIT ) System.exit( 0 );
            }
        }//end while

        return choices.get( answer );
    }//end choice
}//end class
