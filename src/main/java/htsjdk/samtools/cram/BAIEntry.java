package htsjdk.samtools.cram;

import htsjdk.samtools.cram.ref.ReferenceContext;
import htsjdk.samtools.cram.structure.AlignmentSpan;

/**
 * Class used to construct a BAI index for a CRAM file. Each BAIEntry represents a Slice or a subset
 * of a Slice (since MULTI_REF slices can contain records for more than one reference context), as these
 * need to be separated for BAI index creation).
 */
//TODO: Change the name of this to CRAMBAIEntry
public class BAIEntry implements Comparable<BAIEntry> {
    final ReferenceContext referenceContext;
    final AlignmentSpan alignmentSpan;
    final long containerStartByteOffset;
    final long sliceByteOffsetFromCompressionHeaderStart;
    final int landmarkIndex;

    public BAIEntry(
            final ReferenceContext referenceContext,
            final AlignmentSpan alignmentSpan,
            final long containerStartByteOffset,
            final long sliceHeaderBlockByteOffset,
            final int landmarkIndex
    ) {
        // Note: a BAIEntry should never be made from a MULTI_REF reference context, because for a BAI index
        // MUTLI_REF slices need to be resolved down to constituent BAIEntry for each reference context, including
        // unmapped
        if (referenceContext.equals(ReferenceContext.MULTIPLE_REFERENCE_CONTEXT)) {
            throw new CRAMException("Attempt to create BAI entry from a multi ref context");
        } else if ((referenceContext.equals(ReferenceContext.UNMAPPED_UNPLACED_CONTEXT) &&
                //TODO: many tests fail if we don't allow alignmentStart == -1 or alignmentSpan == 1
                ((alignmentSpan.getAlignmentStart() != 0 && alignmentSpan.getAlignmentStart() != -1) ||
                        (alignmentSpan.getAlignmentSpan() != 0 && alignmentSpan.getAlignmentSpan() != 1)))) {
            throw new CRAMException(
                    String.format("Attempt to unmapped with non zero alignment start (%d) or span (%d)",
                            alignmentSpan.getAlignmentStart(),
                            alignmentSpan.getAlignmentSpan()));
        }
        this.referenceContext = referenceContext;
        this.alignmentSpan = alignmentSpan;
        this.containerStartByteOffset = containerStartByteOffset;
        this.sliceByteOffsetFromCompressionHeaderStart = sliceHeaderBlockByteOffset;
        this.landmarkIndex = landmarkIndex;
    }

    /**
     * Create a BAIEntry from a CRAIEntry (used to read a .crai as a .bai). Note that
     * there are no mapped/unmapped/unplaced counts present in the crai, which makes
     * BAIEntries created this way less full featured (i.e., wrong), but that is inherent
     * in the idea of converting a CRAi to a BAI to satisfy an index query).
     *
     * HTSJDK needs a native implementation satifying queries using a CRAI directly.
     * see https://github.com/samtools/htsjdk/issues/851
     *
     * @param craiEntry
     */
    public BAIEntry(final CRAIEntry craiEntry) {
        this(new ReferenceContext(craiEntry.getSequenceId()),
                new AlignmentSpan(
                    craiEntry.getAlignmentStart(),
                    craiEntry.getAlignmentSpan(),
                    0,
                    0,
                    0),
                craiEntry.getContainerStartByteOffset(),
                craiEntry.getSliceByteOffsetFromCompressionHeaderStart(),
                0);
    }

//    //TODO: unused!
//    public static BAIEntry combine(final BAIEntry a, final BAIEntry b) {
//        if (!a.getEntryReferenceContext().equals(b.getEntryReferenceContext()) ||
//                (a.getSliceHeaderBlockByteOffset() != b.getSliceHeaderBlockByteOffset()) ||
//                (a.getLandmarkIndex() != b.getLandmarkIndex()) ||
//                (a.getContainerOffset() == b.getContainerOffset())) {
//            throw new CRAMException(String.format(
//                    "Can't combine BAIEntries from different ref contexts (%s/%s)",
//                    a.getEntryReferenceContext(),
//                    b.getEntryReferenceContext()));
//        }
//        final int start = Math.min(a.getAlignmentStart(), b.getAlignmentStart());
//
//        int span;
//        if (a.getAlignmentStart() == b.getAlignmentStart()) {
//            span = Math.max(a.getAlignmentSpan(), b.getAlignmentSpan());
//        }
//        else {
//            span = Math.max(a.getAlignmentStart() + a.getAlignmentSpan(), b.getAlignmentStart() + b.getAlignmentSpan()) - start;
//        }
//
//        final int mappedCount = a.getMappedReadsCount() + b.getMappedReadsCount();
//        final int unmappedCount = a.getUnmappedReadsCount() + b.getUnmappedReadsCount();
//        final int unmappedUunplacedCount = a.getUnmappedUnplacedReadsCount() + b.getUnmappedUnplacedReadsCount();
//
//        return new BAIEntry(
//                a.getEntryReferenceContext(),
//                new AlignmentSpan(
//                        start,
//                        span,
//                        mappedCount,
//                        unmappedCount,
//                        unmappedUunplacedCount),
//                a.getContainerOffset(),
//                a.getSliceHeaderBlockByteOffset(),
//                a.getLandmarkIndex());
//    }

    /**
     * Sort by numerical order of reference sequence ID, except that unmapped-unplaced reads come last
     *
     * For valid reference sequence ID (placed reads):
     * - sort by alignment start
     * - if alignment start is equal, sort by container offset
     * - if alignment start and container offset are equal, sort by slice offset
     *
     * For unmapped-unplaced reads:
     * - ignore (invalid) alignment start value
     * - sort by container offset
     * - if container offset is equal, sort by slice offset
     *
     * @param other the CRAIEntry to compare against
     * @return int representing the comparison result, suitable for ordering
     */
    @Override
    public int compareTo(final BAIEntry other) {
        // we need to call getReferenceContextID here since we might be unmapped
        if (getReferenceContext().getReferenceContextID() != other.getReferenceContext().getReferenceContextID()) {
            if (getReferenceContext().getReferenceContextID() == ReferenceContext.UNMAPPED_UNPLACED_ID)
                return 1;
            if (other.getReferenceContext().getReferenceContextID() == ReferenceContext.UNMAPPED_UNPLACED_ID)
                return -1;
            return Integer.compare(getReferenceContext().getReferenceSequenceID(), other.getReferenceContext().getReferenceSequenceID());
        }

        // only sort by alignment start values for placed entries
        // we need to call getReferenceContextID here since we might be unmapped
        if (getReferenceContext().getReferenceContextID() != ReferenceContext.UNMAPPED_UNPLACED_ID &&
                getAlignmentStart() != other.getAlignmentStart()) {
            return Integer.compare(getAlignmentStart(), other.getAlignmentStart());
        }

        if (getContainerStartByteOffset() != other.getContainerStartByteOffset()) {
            return Long.compare(getContainerStartByteOffset(), other.getContainerStartByteOffset());
        }

        return Long.compare(getSliceByteOffsetFromCompressionHeaderStart(), other.getSliceByteOffsetFromCompressionHeaderStart());
    };

    // Note: this should never be a multiple ref context
    public ReferenceContext getReferenceContext() {
        return referenceContext;
    }

    public int getAlignmentStart() {
        return alignmentSpan.getAlignmentStart();
    }

    public int getAlignmentSpan() {
        return alignmentSpan.getAlignmentSpan();
    }

    public int getMappedReadsCount() {
        return alignmentSpan.getMappedCount();
    }

    public int getUnmappedReadsCount() {
        return alignmentSpan.getUnmappedCount();
    }
    public int getUnmappedUnplacedReadsCount() {
        return alignmentSpan.getUnmappedUnplacedCount();
    }

    public long getContainerStartByteOffset() {
        return containerStartByteOffset;
    }

    public long getSliceByteOffsetFromCompressionHeaderStart() {
        return sliceByteOffsetFromCompressionHeaderStart;
    }

    public int getLandmarkIndex() {
        return landmarkIndex;
    }

}
