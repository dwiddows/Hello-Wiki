/* Copyright 2011 Manuel Wahle
 *
 * This file is part of Hello-Wiki.
 *
 *    Hello-Wiki is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    Hello-Wiki is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with Hello-Wiki.  If not, see <http://www.gnu.org/licenses/>.
 */

package mw.wikidump;


import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import mw.utils.NanoTimeFormatter;
import mw.utils.PlainLogger;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.Version;

/**
 * @author mwahle
 * 
 */
public class MakeLuceneIndex
{

    /**
     * @param args
     * @throws IOException
     * @throws ParseException
     */
    public static void main(String[] args) throws IOException, ParseException
    {
        String baseDir = "/users/tcohen/helloWiki";
        String wikiDumpFile = "enwiki-20160601-pages-articles-multistream.xml";
        String luceneIndexName = "enwiki-20160601-lucene";
        String logFile = luceneIndexName + ".log";
        boolean bIgnoreStubs = false;

        for ( int i = 0; i < args.length; ++i )
        {
            if ( args[i].equals( "-luceneindex" ) )
                luceneIndexName = args[++i];

            if ( args[i].equals( "-basedir" ) )
                baseDir = args[++i];

            if ( args[i].equals( "-logfile" ) )
                logFile = args[++i];

            if ( args[i].equals( "-dumpfile" ) )
                wikiDumpFile = args[++i];

            if ( args[i].equals( "-ignorestubs" ) )
                bIgnoreStubs = true;
        }

        
        Map<String,Analyzer> analyzerPerField = new HashMap<>();
        analyzerPerField.put("tokenized_title", new StandardAnalyzer(CharArraySet.EMPTY_SET));
        analyzerPerField.put("contents", new StandardAnalyzer(CharArraySet.EMPTY_SET));

        PerFieldAnalyzerWrapper analyzer = new PerFieldAnalyzerWrapper( new WhitespaceAnalyzer( ), analyzerPerField);

        File basePath = new File( baseDir );
        File luceneIndex = new File( basePath.getCanonicalPath() + File.separator + luceneIndexName );

        logFile = basePath.getCanonicalPath() + File.separator + logFile;

        // log to file and console:
        // PlainLogger logger = new PlainLogger( logFile );
        // log only to console:
        PlainLogger logger = new PlainLogger();

        logger.log( "Work directory:     " + basePath.getCanonicalPath() );
        logger.log( "Lucene index:       " + luceneIndexName );
        logger.log( "Wikipedia dumpfile: " + wikiDumpFile );
        logger.log( "" );
        if ( bIgnoreStubs )
            logger.log( "Ignoring stubs" );
        else
            logger.log( "Including stubs" );
        logger.log( "" );


        // create the index
        Directory indexDirectory = new MMapDirectory(luceneIndex.toPath() ); //original implementation used a noLockingFactory (or some such thing), which doesn't seem to exist in Lucene 5
        IndexWriterConfig iConfig = new IndexWriterConfig(analyzer);
        IndexWriter indexWriter = new IndexWriter( indexDirectory, iConfig); //original implementation set maxFieldLength, deprecated in Lucene 5

        Extractor wikidumpExtractor = new Extractor( basePath.getCanonicalPath() + File.separator + wikiDumpFile );
        wikidumpExtractor.setLinkSeparator( "_" );
        wikidumpExtractor.setCategorySeparator( "_" );

        int iStubs = 0;
        int iArticleCount = 0;
        int iSkippedPageCount = 0;
        long iStartTime = java.lang.System.nanoTime();
        long iTime = iStartTime;

        while ( wikidumpExtractor.nextPage() )
        {
            if ( wikidumpExtractor.getPageType() != Extractor.PageType.ARTICLE )
            {
                ++iSkippedPageCount;
                continue;
            }

            if ( bIgnoreStubs && wikidumpExtractor.getStub() )
            {
                ++iStubs;
                continue;
            }

            Document doc = new Document();
            ++iArticleCount;

            //create new FieldType to store term positions (TextField is not sufficiently configurable)
            FieldType ft = new FieldType();
            ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
            ft.setTokenized(true);
            ft.setStoreTermVectors(true);
            ft.setStoreTermVectorPositions(true);
            ft.setStored(false);
            
            FieldType ft_stored = new FieldType();
            ft_stored.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
            ft_stored.setTokenized(true);
            ft_stored.setStoreTermVectors(true);
            ft_stored.setStoreTermVectorPositions(true);
            ft_stored.setStored(true);
            
            wikidumpExtractor.setTitleSeparator( "_" );
            Field titleField = new Field("title", wikidumpExtractor.getPageTitle( false ).toLowerCase(), ft_stored);
            doc.add(titleField);
      
            wikidumpExtractor.setTitleSeparator( " " );
            Field tokenizedTitleField = new Field("tokenized_title", wikidumpExtractor.getPageTitle( false ).toLowerCase(), ft);
            doc.add(tokenizedTitleField);
            
            Field categoryField = new Field("categories", wikidumpExtractor.getPageCategories().toLowerCase(),ft_stored);
            doc.add(categoryField);
            
            Field linksField = new Field( "links", wikidumpExtractor.getPageLinks().toLowerCase(), ft_stored);
            doc.add(linksField);
            
            Field contentsField = new Field ( "contents", wikidumpExtractor.getPageText().toLowerCase(), ft );
            doc.add(contentsField);

            indexWriter.addDocument( doc );

            if ( iArticleCount % 50000 == 0 )
            {
                logger.add( iArticleCount + " (" + NanoTimeFormatter.getS( System.nanoTime() - iTime ) + "s) " );
                iTime = System.nanoTime();

                if ( iArticleCount % 250000 == 0 )
                {
                    try
                    {
                        indexWriter.commit();
                        logger.add( "-- commit. Skipped page count " + iSkippedPageCount + " (+ " + iStubs + " stubs)" );
                        logger.log( String.format( ", time %sm", NanoTimeFormatter.getM( System.nanoTime() - iStartTime ) ) );
                    }
                    catch ( Exception e )
                    {
                        e.printStackTrace();
                    }
                }
            }
        }

        logger.log( "" );
        logger.log( String.format( "Overall time %s minutes, ", NanoTimeFormatter.getM( System.nanoTime() - iStartTime ) ) );
        logger.add( "collected " + iArticleCount + " articles, " );
        logger.add( "skipped " + iSkippedPageCount + " nonarticle pages," );
        logger.log( "skipped " + iStubs + " stubs." );
        logger.log( "" );

        iTime = System.nanoTime();
        logger.add( "Commiting... " );
        indexWriter.commit(); //original implementation "optimized", n/a in Lucene 5
        logger.add( "done in " + NanoTimeFormatter.getS( System.nanoTime() - iTime ) + "s," );

        iTime = System.nanoTime();
        logger.add( " closing..." );
        indexWriter.close();
        logger.log( " done in " + NanoTimeFormatter.getS( System.nanoTime() - iTime ) + "s." );

        logger.close();
        System.exit( 0 );
    }
}
