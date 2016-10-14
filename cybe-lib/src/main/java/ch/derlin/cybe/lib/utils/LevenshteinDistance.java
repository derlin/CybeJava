package ch.derlin.cybe.lib.utils;

import java.util.Arrays;

/**
 * User: lucy
 * Date: 26/11/13
 * Version: 0.1
 */
public class LevenshteinDistance{

    static boolean debug = false;


    public static void main( String[] args ){
        System.out.println( getDistance( "abcde", "bgdfe" ) );
        System.out.println();
        System.out.println( getDistance( "abcde", "abcdef" ) );
    }//end main


    public static int getDistance( String a, String b ){

        if( a.equals( b ) ) return 0;
        // be sure that a is smaller than b
        if( a.length() > b.length() ) return getDistance( b, a );

        // a is the smallest => "horizontal"
        // b is the longest => "vertical"

        int[] lastLine = new int[ a.length() + 1 ], currentLine = new int[ a.length() + 1 ];
        for( int i = 0; i < lastLine.length; i++ ){
            lastLine[ i ] = i;
        }//end for

        if( debug ){
            System.out.println( "a is " + a + ", b is " + b );
            System.out.println( Arrays.toString( lastLine ) );
        }

        for( int lineIndex = 1; lineIndex < b.length() + 1; lineIndex++ ){
            currentLine[ 0 ] = lineIndex;
            char charAdded = b.charAt( lineIndex - 1 );
            for( int col = 1; col < currentLine.length; col++ ){
                // find the max between the upper and left cell
                int min = Math.min( lastLine[ col ], currentLine[ col - 1 ] );
                // find the max between min and the diagonal
                min = Math.min( min, lastLine[ col - 1 ] );

                min++; // add one operation

                // if the two chars added are the same, no further operation
                if( a.charAt( col - 1 ) == charAdded && lastLine[ col - 1 ] < min ){
                    min = lastLine[ col - 1 ];
                }

                currentLine[ col ] = min;
            }//end for

            if( debug ) System.out.println( Arrays.toString( currentLine ) ); // for debug only

            // swap last and current to prepare the next round
            int[] temp = currentLine;
            currentLine = lastLine;
            lastLine = temp;
        }//end for

        return lastLine[ lastLine.length - 1 ]; // since we swapped, get the last cell of the LAST line
    }//end getDistance
}//end class
