/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pdfbox.xref;

import static org.apache.pdfbox.util.RequireUtils.requireIOCondition;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.io.PushBackInputStream;
import org.apache.pdfbox.load.BaseCOSParser;
import org.apache.pdfbox.load.IndirectObjectsProvider;
import org.apache.pdfbox.util.Charsets;

/**
 * @author Andrea Vacondio
 *
 */
public class XrefParser extends BaseCOSParser
{
    private static final Log LOG = LogFactory.getLog(XrefParser.class);
    /**
     * How many trailing bytes to read for EOF marker.
     */
    private static final int DEFAULT_TRAIL_BYTECOUNT = 2048;
    private static final String STARTXREF = "startxref";
    private static final String XREF = "xref";
    private static final String TRAILER = "trailer";

    private Xref xref = new Xref();
    private TrailerMerger trailerMerger = new TrailerMerger();
    private XrefStreamParser xrefStreamParser;
    // TODO set this somehow
    private long sourceLength;

    public XrefParser(PushBackInputStream source, IndirectObjectsProvider provider)
    {
        super(source, provider);
        this.xrefStreamParser = new XrefStreamParser(source, provider, xref, trailerMerger);
    }

    public void parse() throws IOException
    {
        long xrefOffset = findXrefOffset();
        if (xrefOffset > 0)
        {
            LOG.debug("Found xref offset at " + xrefOffset);
            parseXref(xrefOffset);
        }
        else
        {
            rebuildTrailer();
        }
    }

    /**
     * Looks for the startxref keyword within the latest {@link #DEFAULT_TRAIL_BYTECOUNT} bytes of the source. If found
     * it returns the Long read after the keyword, if not it returns -1.
     * 
     * @return the xref offset or -1 if the startxref keyword is not found
     * @throws IOException If something went wrong.
     */
    private final long findXrefOffset() throws IOException
    {
        int chunkSize = (int) Math.min(sourceLength, DEFAULT_TRAIL_BYTECOUNT);
        byte[] buffer = new byte[chunkSize];
        long startPosition = sourceLength - chunkSize;
        offset(startPosition);
        source().read(buffer, 0, chunkSize);
        int relativeIndex = new String(buffer, Charsets.ISO_8859_1).lastIndexOf(STARTXREF);
        if (relativeIndex < 0)
        {
            LOG.warn("Unable to find 'startxref' keyword");
            return -1;
        }
        offset(startPosition + relativeIndex + STARTXREF.length());
        skipSpaces();
        return readLong();
    }

    private void parseXref(long xrefOffset) throws IOException
    {
        if (!isValidXrefOffset(xrefOffset))
        {
            LOG.warn("Offset '" + xrefOffset
                    + "' doesn't point to an xref table or stream, applying fallback strategy");
            // fallback strategy
        }
        requireIOCondition(xrefOffset > 0, "Unable to find correct xref table or stream offset");

        while (xrefOffset > -1)
        {
            offset(xrefOffset);
            skipSpaces();

            if (isNextToken(XREF))
            {
                parseXrefTable(xrefOffset);
                skipSpaces();
                // PDFBOX-1739 skip extra xref entries in RegisSTAR documents
                while (source().peek() != 't')
                {
                    LOG.warn("Expected trailer object at position " + offset() + ", skipping line.");
                    readLine();

                }
                COSDictionary trailer = parseTrailer();
                long streamOffset = trailer.getLong(COSName.XREF_STM);
                if (streamOffset > 0)
                {
                    if (!isValidXrefStreamOffset(streamOffset))
                    {
                        LOG.warn("Offset '" + streamOffset
                                + "' doesn't point to an xref stream, applying fallback strategy");
                        // fallback
                        streamOffset = (int) fixedOffset;

                    }
                    if (streamOffset > 0)
                    {
                        trailer.setLong(COSName.XREF_STM, streamOffset);
                        offset(streamOffset);
                        skipSpaces();
                        xrefStreamParser.parse(xrefOffset);
                    }
                    else
                    {
                        LOG.warn("Skipping xref stream due to a corrupt offset.");
                    }

                }
                xrefOffset = amendPrevIfInvalid(trailer);
            }
            else
            {
                COSDictionary streamDictionary = xrefStreamParser.parseAndMergeTrailer(xrefOffset);
                xrefOffset = amendPrevIfInvalid(streamDictionary);
            }
        }
        // check the offsets of all referenced objects
        checkXrefOffsets();
    }

    private COSDictionary parseTrailer() throws IOException
    {
        long offset = offset();
        LOG.debug("Parsing trailer at " + offset);
        skipExpected(TRAILER);
        skipSpaces();
        COSDictionary dictionary = nextDictionary();
        trailerMerger.mergeTrailerWithoutOverwriting(offset, dictionary);
        skipSpaces();
        return dictionary;
    }

    /**
     * Validates the PREV entry in the given dictionary and if not valid it applies a fallback strategy
     * 
     * @param dictionary
     * @return the original PREV value if valid, the fallback amended one otherwise.
     * @throws IOException
     */
    private long amendPrevIfInvalid(COSDictionary dictionary) throws IOException
    {
        long prevOffset = dictionary.getLong(COSName.PREV);
        if (prevOffset > 0)
        {
            if (!isValidXrefOffset(prevOffset))
            {
                LOG.warn("Offset '" + prevOffset
                        + "' doesn't point to an xref table or stream, applying fallback strategy");
                // fallback strategy
                dictionary.setLong(COSName.PREV, amendedOffset);
            }
        }
        return prevOffset;
    }

    /**
     * @param xrefOffset
     * @return true if the given offset points to an xref table or and xref stream
     * @throws IOException
     */
    private boolean isValidXrefOffset(long xrefOffset) throws IOException
    {
        if (isValidXrefStreamOffset(xrefOffset))
        {
            return true;
        }
        offset(xrefOffset);
        return isNextToken(XREF);
    }

    /**
     * @param xrefStreamOffset
     * @return true if the given offset points to a valid xref stream
     * @throws IOException
     */
    private boolean isValidXrefStreamOffset(long xrefStreamOffset) throws IOException
    {
        offset(xrefStreamOffset);
        try
        {
            skipIndirectObjectDefinition();
        }
        catch (IOException exception)
        {
            return false;
        }
        offset(xrefStreamOffset);
        return true;
    }

    /**
     * Rebuild the trailer dictionary if startxref can't be found.
     * 
     * @return the rebuild trailer dictionary
     * 
     * @throws IOException if something went wrong
     */
    protected final COSDictionary rebuildTrailer() throws IOException
    {
        COSDictionary trailer = null;
        bfSearchForObjects();
        if (bfSearchCOSObjectKeyOffsets != null)
        {
            for (COSObjectKey objectKey : bfSearchCOSObjectKeyOffsets.keySet())
            {
                xref.add(XrefEntry.inUseEntry(objectKey.getNumber(),
                        bfSearchCOSObjectKeyOffsets.get(objectKey), objectKey.getGeneration()));
            }
            trailer = trailerMerger.getTrailer();
            getDocument().setTrailer(trailer);
            // search for the different parts of the trailer dictionary
            for (COSObjectKey key : bfSearchCOSObjectKeyOffsets.keySet())
            {
                Long offset = bfSearchCOSObjectKeyOffsets.get(key);
                pdfSource.seek(offset);
                readObjectNumber();
                readGenerationNumber();
                readExpectedString(OBJ_MARKER, true);
                try
                {
                    COSDictionary dictionary = parseCOSDictionary();
                    if (dictionary != null)
                    {
                        // document catalog
                        if (COSName.CATALOG.equals(dictionary.getCOSName(COSName.TYPE)))
                        {
                            trailer.setItem(COSName.ROOT, document.getObjectFromPool(key));
                        }
                        // info dictionary
                        else if (dictionary.containsKey(COSName.TITLE)
                                || dictionary.containsKey(COSName.AUTHOR)
                                || dictionary.containsKey(COSName.SUBJECT)
                                || dictionary.containsKey(COSName.KEYWORDS)
                                || dictionary.containsKey(COSName.CREATOR)
                                || dictionary.containsKey(COSName.PRODUCER)
                                || dictionary.containsKey(COSName.CREATION_DATE))
                        {
                            trailer.setItem(COSName.INFO, document.getObjectFromPool(key));
                        }
                        // TODO encryption dictionary
                    }
                }
                catch (IOException exception)
                {
                    LOG.debug("Skipped object " + key + ", either it's corrupt or not a dictionary");
                }
            }
        }
        return trailer;
    }

    public COSDictionary getTrailer()
    {
        return this.trailerMerger.getTrailer();
    }

    public Xref getXref()
    {
        return this.xref;
    }

}
