/*
 * Copyright (c) 2019 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package htsjdk.samtools.cram.build;

import htsjdk.samtools.*;
import htsjdk.samtools.cram.CRAMException;
import htsjdk.samtools.cram.common.CramVersions;
import htsjdk.samtools.cram.ref.CRAMReferenceSource;
import htsjdk.samtools.cram.ref.ReferenceContext;
import htsjdk.samtools.cram.structure.CRAMEncodingStrategy;
import htsjdk.samtools.cram.structure.CRAMRecord;
import htsjdk.samtools.cram.structure.CompressionHeader;
import htsjdk.samtools.cram.structure.Slice;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Factory for creating {@link Slice}s when writing a CRAM stream. Determines when to emit a {@link Slice}, based on
 * a set of rules implemented by this class; the accumulated SliceEntry state objects; and the parameter values in
 * the provided {@link CRAMEncodingStrategy} object.
 */
public class SliceFactory {
    private final CRAMEncodingStrategy encodingStrategy;

    private final List<SliceStagingEntry> cramRecordSliceEntries;
    private final CRAMReferenceRegion cramReferenceRegion;

    private long sliceRecordCounter;
    private final int maxRecordsPerSlice;
    private final int minimumSingleReferenceSliceThreshold;
    private final boolean coordinateSorted;

    private final Map<String, Integer> readGroupNameToID = new HashMap<>();

    /**
     * @param cramEncodingStrategy {@link CRAMEncodingStrategy} to use for {@link Slice}s that are created
     * @param cramReferenceSource {@link CRAMReferenceSource} to use for {@link Slice}s that are created
     * @param samFileHeader for input records, used for finding read groups, sort order, etc.
     * @param globalRecordCounter initial global record counter for {@link Slice}s that are created
     */
    public SliceFactory(
            final CRAMEncodingStrategy cramEncodingStrategy,
            final CRAMReferenceSource cramReferenceSource,
            final SAMFileHeader samFileHeader,
            final long globalRecordCounter) {
        this.encodingStrategy = cramEncodingStrategy;
        this.cramReferenceRegion = new CRAMReferenceRegion(cramReferenceSource, samFileHeader);
        minimumSingleReferenceSliceThreshold = encodingStrategy.getMinimumSingleReferenceSliceSize();
        maxRecordsPerSlice = this.encodingStrategy.getReadsPerSlice();
        this.coordinateSorted = samFileHeader.getSortOrder() == SAMFileHeader.SortOrder.coordinate;
        this.sliceRecordCounter = globalRecordCounter;
        cramRecordSliceEntries = new ArrayList<>(this.encodingStrategy.getSlicesPerContainer());

        // create our read group id map
        final List<SAMReadGroupRecord> readGroups = samFileHeader.getReadGroups();
        for (int i = 0; i < readGroups.size(); i++) {
            final SAMReadGroupRecord readGroupRecord = readGroups.get(i);
            readGroupNameToID.put(readGroupRecord.getId(), i);
        }
    }

    /**
     * Add a new slice entry, and return the number of sliceEntries.
     *
     * @param currentReferenceContextID
     * @param sliceSAMRecords
     * @return
     */
    public long createNewSliceEntry(final int currentReferenceContextID, final List<SAMRecord> sliceSAMRecords) {
        cramRecordSliceEntries.add(
                new SliceStagingEntry(
                        currentReferenceContextID,
                        convertToCRAMRecords(sliceSAMRecords, sliceRecordCounter),
                        sliceRecordCounter));
        return sliceRecordCounter + sliceSAMRecords.size();
    }

    /**
     * Get the CRAM records accumulated for the current Slice.
     *
     * @return the list of all CRAMRecords
     */
    public List<CRAMRecord> getCRAMRecords() {
        // Create a list of ALL reads from all accumulated slices (used to create the container
        // compression header, which must be presented with ALL reads that will be included in the
        // container, no matter how they may be distributed across slices). So if more than one slice
        // entry has been accumulated, we need to temporarily stream all the records into a single
        // list to present to compressionHeaderFactory.
        return cramRecordSliceEntries.size() > 1 ?
                cramRecordSliceEntries.stream().flatMap(e -> e.records.stream()).collect(Collectors.toList()) :
                cramRecordSliceEntries.get(0).getRecords();
    }

    public int getNumberOfSliceEntries() {
        return cramRecordSliceEntries.size();
    }

    public List<Slice> getSlices(
            final CompressionHeader compressionHeader,
            final long containerByteOffset) {
        final List<Slice> slices = new ArrayList<>(cramRecordSliceEntries.size());
        for (final SliceStagingEntry sliceStagingEntry : cramRecordSliceEntries) {
            final Slice slice = new Slice(
                    sliceStagingEntry.getRecords(),
                    compressionHeader,
                    containerByteOffset,
                    sliceStagingEntry.getGlobalRecordCounter()
            );
            slice.setReferenceMD5(cramReferenceRegion.getCurrentReferenceBases());
            slices.add(slice);
        }
        cramRecordSliceEntries.clear();
        return slices;
    }

    private final List<CRAMRecord> convertToCRAMRecords(final List<SAMRecord> samRecords, final long sliceRecordCounter) {
        long recordIndex = sliceRecordCounter;
        final List<CRAMRecord> cramRecords = new ArrayList<>();
        for (final SAMRecord samRecord : samRecords) {
            int referenceIndex = samRecord.getReferenceIndex();
            final CRAMRecord cramRecord = new CRAMRecord(
                    CramVersions.DEFAULT_CRAM_VERSION,
                    encodingStrategy,
                    samRecord,
                    cramReferenceRegion.getReferenceBases(referenceIndex),
                    recordIndex++,
                    readGroupNameToID);
            cramRecords.add(cramRecord);
        }
        resolveMatesForSlice(cramRecords);
        return cramRecords;
    }

    private void resolveMatesForSlice(final List<CRAMRecord> cramRecords) {
        if (!coordinateSorted) {
            for (final CRAMRecord r : cramRecords) {
                r.setDetached(true);
            }
        } else {
            //TODO: these maps are flawed in that while there should only be one primary record
            // for a given read name, there can be multiple secondary reads, and they are not
            // required to be encountered in order for coord-sorted, so its possible that we need
            // to accumulate more than one read of a given name in the
            final Map<String, CRAMRecord> primaryMateMap = new TreeMap<>();
            final Map<String, CRAMRecord> secondaryMateMap = new TreeMap<>();
            for (final CRAMRecord r : cramRecords) {
                if (r.isReadPaired()) {
                    final Map<String, CRAMRecord> mateMap =
                            r.isSecondaryAlignment() ?
                                    //TODO: is this right ?
                                    primaryMateMap :
                                    secondaryMateMap;
                    CRAMRecord mate = mateMap.get(r.getReadName());
                    mate = rejectBadMateChoice(mate, r);
                    if (mate == null) {
                        if (r.isSecondaryAlignment()) {
                            secondaryMateMap.put(r.getReadName(), r);
                        } else {
                            primaryMateMap.put(r.getReadName(), r);
                        }
                    } else {
                        mate.attachToMate(r);
                    }
                }
            }

            // mark unpredictable reads as detached:
            for (final CRAMRecord cramRecord : cramRecords) {
                cramRecord.updateDetachedState();
            }
        }
    }

    // We want to make sure that we choose the next mate in the fragment sequence to preserve
    // the original order. If we just choose the first name that matches, it might not be the right one.
    // So test the candidate mate to see if it matches the mate properties specified in the original read.
    private CRAMRecord rejectBadMateChoice(final CRAMRecord firstMate, final CRAMRecord candidateMate) {
        if (firstMate == null) {
            return null;
        }
        //if the first mate's mate is on the reverse strand, and the candidate isn't, reject it
        if ((firstMate.getBAMFlags() & SAMFlag.MATE_REVERSE_STRAND.intValue()) != 0 &&
                (candidateMate.getBAMFlags() & SAMFlag.READ_REVERSE_STRAND.intValue()) == 0) {
            return null;
        }

        //if the first mate's mate is unmapped, and the candidate is mapped, reject it
        if ((firstMate.getBAMFlags() & SAMFlag.MATE_UNMAPPED.intValue()) != 0 &&
                (candidateMate.getBAMFlags() & SAMFlag.READ_UNMAPPED.intValue()) == 0) {
            return null;
        }

        if  (candidateMate.getAlignmentStart() != firstMate.getMateAlignmentStart()) {
            return null;
        }
        return candidateMate;
    }

    /**
     * Decide if the current records should be flushed based on the current reference context, the reference context
     * for the next record to be written, and the number of records seen so far.
     *
     * Slices with the Multiple Reference flag (-2) set as the sequence ID in the header may contain reads mapped to
     * multiple external references, including unmapped reads (placed on these references or unplaced), but multiple
     * embedded references cannot be combined in this way. When multiple references are used, the RI data series will
     * be used to determine the reference sequence ID for each record. This data series is not present when only a
     * single reference is used within a slice.
     *
     * The Unmapped (-1) sequence ID in the header is for slices containing only unplaced unmapped reads.
     * A slice containing data that does not use the external reference in any sequence may set the reference MD5 sum
     * to zero. This can happen because the data is unmapped or the sequence has been stored verbatim instead of via
     * reference-differencing. This latter scenario is recommended for unsorted or non-coordinate-sorted data.
     *
     * @param nextReferenceIndex reference index of the next record to be emitted
     * @return ReferenceContext.UNINITIALIZED_REFERENCE_ID if a current container should be flushed and
     * subsequent records should go into a new container; otherwise the updated reference context.
     */
    public int shouldEmitSlice(
            final int currentReferenceContext,
            final int nextReferenceIndex,
            final int numberOfSAMRecords) {
        switch (currentReferenceContext) {
            // uninitialized can go to: unmapped or mapped
            case ReferenceContext.UNINITIALIZED_REFERENCE_ID:
                if (numberOfSAMRecords != 0) {
                    throw new CRAMException(
                            "Reference context should have been initialized if records have previously been processed");
                }
                return nextReferenceIndex;

            case ReferenceContext.UNMAPPED_UNPLACED_ID:
                if (nextReferenceIndex == currentReferenceContext) {
                    // still unmapped...
                    return numberOfSAMRecords < maxRecordsPerSlice ?
                            ReferenceContext.UNMAPPED_UNPLACED_ID :
                            ReferenceContext.UNINITIALIZED_REFERENCE_ID;
                } else if (coordinateSorted) {
                    // coordinate sorted, and we're going from unmapped to mapped ??
                    throw new CRAMException("Invalid coord-sorted input - unmapped records must be last");
                } else {
                    // Not coordinate sorted, and we're going from unmapped to mapped, so allow the mapped
                    // record into the same slice with the unmapped ones, since there is no index query
                    // concern since we're not coord sorted anyway (though there is no reference compression
                    // happening in this container).
                    return numberOfSAMRecords >= maxRecordsPerSlice ?
                            ReferenceContext.UNINITIALIZED_REFERENCE_ID :
                            ReferenceContext.MULTIPLE_REFERENCE_ID;
                }

            case ReferenceContext.MULTIPLE_REFERENCE_ID:
                // This always accumulates a full multi-ref slice, but maybe it would be more efficient
                // to emit smaller multi-ref slices on the theory that the stream will get back on track
                // for single ref, at least for coord-sorted.
                if (coordinateSorted) {
                    return numberOfSAMRecords < minimumSingleReferenceSliceThreshold ?
                            ReferenceContext.MULTIPLE_REFERENCE_ID :
                            ReferenceContext.UNINITIALIZED_REFERENCE_ID; // emit a small mutli-ref
                } else {
                    // multi-ref, not coord sorted
                    return numberOfSAMRecords >= maxRecordsPerSlice ?
                            ReferenceContext.UNINITIALIZED_REFERENCE_ID :
                            ReferenceContext.MULTIPLE_REFERENCE_ID;
                }

            default:
                // So far everything we've accumulated for the next slice is on a single reference contig
                if (nextReferenceIndex == currentReferenceContext) {
                    // still on the same reference contig
                    return numberOfSAMRecords >= maxRecordsPerSlice ?
                            ReferenceContext.UNINITIALIZED_REFERENCE_ID :
                            nextReferenceIndex;
                } else {
                    // switching to either a new reference contig, or to unmapped
                    return numberOfSAMRecords < minimumSingleReferenceSliceThreshold ?
                            // if we already have accumulated at least one slice, then we emit it rather than
                            // switch to multi-ref so we can prevent a multi-ref slice from being packed into
                            // a container with a single-ref slice (which violates the spec, so to do so
                            // would require making both slices multi-ref)
                            getNumberOfSliceEntries() > 0 ?
                                    ReferenceContext.UNINITIALIZED_REFERENCE_ID:
                                    ReferenceContext.MULTIPLE_REFERENCE_ID :
                            ReferenceContext.UNINITIALIZED_REFERENCE_ID;
                }
        }
    }

    // We can't create a Slice until we have a compression header, and we can't create a compression
    // header until we've seen all records that will live in a container. SliceStagingEntry objects are
    // used to accumulate and hold sets of records that will populate a Slice until we're ready to create
    // the actual container with real Slice objects.
    private static class SliceStagingEntry {
        private final List<CRAMRecord> records;
        private final ReferenceContext referenceContext;
        private final long sliceRecordCounter;

        public SliceStagingEntry(final int referenceContextID, final List<CRAMRecord> sourceRecords, final long sliceRecordCounter) {
            this.records = new ArrayList<>(sourceRecords);
            this.referenceContext = new ReferenceContext(referenceContextID);
            this.sliceRecordCounter = sliceRecordCounter;
        }
        public ReferenceContext getReferenceContext() {
            return referenceContext;
        }
        public List<CRAMRecord> getRecords() {
            return records;
        }
        public long getGlobalRecordCounter() { return sliceRecordCounter; }
    }
}
