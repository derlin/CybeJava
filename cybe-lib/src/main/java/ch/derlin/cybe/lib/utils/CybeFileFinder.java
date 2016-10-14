package ch.derlin.cybe.lib.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;

import static java.nio.file.FileVisitOption.*;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;

/**
 * @author: Lucy Linder
 * @date: 28.06.2014
 */
public class CybeFileFinder extends SimpleFileVisitor<Path>{

    protected Collection<Path> matches;
    protected Consumer<Path> consumer;
    protected Path start, searchPath;


    public CybeFileFinder find( String start, String target ) throws IOException{
        return find( start, target, ( p ) -> {
        } );
    }


    public CybeFileFinder find( String start, String target, Consumer<Path> consumer ) throws IOException{
        this.searchPath = Paths.get( target );
        this.consumer = consumer;
        this.matches = new HashSet<>();

        Files.walkFileTree( Paths.get( start ), EnumSet.of( FOLLOW_LINKS ), Integer.MAX_VALUE, this );
        return this;
    }


    public Collection<Path> get() throws IOException{
        return matches;
    }//end get


    protected boolean processPath( Path path ){
        Path name = path.getFileName();
        if( name != null && name.equals( searchPath ) ){
            try{
                // avoid duplicates (symlinks) -> get the canonical path
                Path canonicalPath = path.toFile().getCanonicalFile().toPath();
                if( matches.add( canonicalPath ) ){  // avoid calling the consumer twice for the same file
                    consumer.accept( canonicalPath );
                }
                return true;

            }catch( IOException e ){
                e.printStackTrace();
            }
        }
        return false;
    }


    @Override
    public FileVisitResult preVisitDirectory( Path dir, BasicFileAttributes attrs ) throws IOException{
        File file = dir.toFile();
        // skip hidden or not writeable directories
        if( !file.canWrite() || file.isHidden() ) return SKIP_SUBTREE;
        return super.preVisitDirectory( dir, attrs );
    }


    @Override
    public FileVisitResult visitFileFailed( Path file, IOException exc ){
        System.err.println( exc );
        return SKIP_SUBTREE;
    }


    @Override
    public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ){
        processPath( file );
        return CONTINUE;
    }

}//end class
