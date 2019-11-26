package htsjdk.samtools;

import htsjdk.HtsjdkTest;
import htsjdk.samtools.cram.ref.ReferenceSource;
import htsjdk.samtools.cram.structure.CRAMEncodingStrategy;
import htsjdk.samtools.reference.FakeReferenceSequenceFile;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

/**
 * A collection of CRAM test based on round trip comparison of SAMRecord before and after CRAM compression.
 */
public class CRAMEdgeCasesTest extends HtsjdkTest {

    private static final File TEST_DATA_DIR = new File("src/test/resources/htsjdk/samtools/cram");

    @Test
    public final void testReadSamtoolsCRAM() throws IOException {
        final File inputFile = new File(TEST_DATA_DIR, "mateResolution2.samtools.cram");
        final File referenceFile = new File("/Users/cnorman/projects/references/hg38/Homo_sapiens_assembly38.fasta");

        try (final SamReader origReader = SamReaderFactory.makeDefault()
                .referenceSequence(referenceFile)
                .validationStringency((ValidationStringency.SILENT))
                .open(inputFile)) {
            final SAMRecordIterator origIterator = origReader.iterator();
            final List<SAMRecord> origSamRecords  = new ArrayList<>();
            while (origIterator.hasNext()) {
                origSamRecords.add(origIterator.next());
            }
            Assert.assertEquals(origSamRecords.size(), 3);
        }
    }

    // NOTE: The test file has 3 reads, with the first read mated to the third, and the third mated
    // to the second (see with BAM flags and mate alignment start):
    //
    //HK35MCCXX160204:8:2209:4005:44116       99      20      7000    0       151M    =       7173    281...
    //      paired, proper, mate reversed, first in pair
    //HK35MCCXX160204:8:2209:4005:44116       2179    20      7172    0       44M107S =       7000    -281...
    //      paired, proper, second in pair, supplementary
    //HK35MCCXX160204:8:2209:4005:44116       147     20      7173    0       108M43S =       7000    -281...
    //      paired, proper, read reversed, second in pair
    //
    // On master, after round-tripping, they come back in the same order, but the flags are changed, and the
    // first read is now mated to the second, and the second to thethird):
    //
    //HK35MCCXX160204:8:2209:4005:44116       67      20      7000    0       151M    =       7172    281...
    //HK35MCCXX160204:8:2209:4005:44116       2211    20      7172    0       44M107S =       7173    -281...
    //HK35MCCXX160204:8:2209:4005:44116       147     20      7173    0       108M43S =       7000    -281...
    //
    @Test
    public final void testMateResolution() throws IOException {
        final File inputFile = new File(TEST_DATA_DIR, "mateResolution.sam");
        final File referenceFile = new File(TEST_DATA_DIR, "human_g1k_v37.20.subset.fasta");

        final CRAMEncodingStrategy testStrategy = new CRAMEncodingStrategy();
        final File tempOutCRAM = File.createTempFile("testMateResolution", ".cram");
        CRAMTestUtils.writeToCRAMWithEncodingStrategy(testStrategy, inputFile, tempOutCRAM, referenceFile);

        try (final SamReader origReader = SamReaderFactory.makeDefault()
                .referenceSequence(referenceFile)
                .validationStringency((ValidationStringency.SILENT))
                .open(inputFile);
             final CRAMFileReader copyReader = new CRAMFileReader(tempOutCRAM, new ReferenceSource(referenceFile))) {
            final SAMRecordIterator origIterator = origReader.iterator();
            final SAMRecordIterator copyIterator = copyReader.getIterator();
            final List<SAMRecord> origSamRecords  = new ArrayList<>();
            final List<SAMRecord> cramRecords  = new ArrayList<>();
            while (origIterator.hasNext() && copyIterator.hasNext()) {
                origSamRecords.add(origIterator.next());
                cramRecords.add(copyIterator.next());
            }
            Assert.assertEquals(origIterator.hasNext(), copyIterator.hasNext());
            Assert.assertEquals(cramRecords.size(), origSamRecords.size());
            Assert.assertEquals(cramRecords, origSamRecords);
        }
    }

    @Test
    public final void testMateResolution2() throws IOException {
        final File inputFile = new File(TEST_DATA_DIR, "mateResolution2.sam");
        //final File referenceFile = new File(TEST_DATA_DIR, "human_g1k_v37.20.subset.fasta");
        final ReferenceSource fakeReferenceSource =
                new ReferenceSource(new FakeReferenceSequenceFile(
                        SamReaderFactory.makeDefault().getFileHeader(inputFile).getSequenceDictionary().getSequences()
                ));

        final CRAMEncodingStrategy testStrategy = new CRAMEncodingStrategy();
        final File tempOutCRAM = File.createTempFile("testMateResolution", ".cram");
        CRAMTestUtils.writeToCRAMWithEncodingStrategy(testStrategy, inputFile, tempOutCRAM, fakeReferenceSource);

        try (final SamReader origReader = SamReaderFactory.makeDefault()
                .referenceSource(fakeReferenceSource)
                .validationStringency((ValidationStringency.SILENT))
                .open(inputFile);
             final CRAMFileReader copyReader = new CRAMFileReader(tempOutCRAM, fakeReferenceSource)) {
            final SAMRecordIterator origIterator = origReader.iterator();
            final SAMRecordIterator copyIterator = copyReader.getIterator();
            final List<SAMRecord> origSamRecords  = new ArrayList<>();
            final List<SAMRecord> cramRecords  = new ArrayList<>();
            while (origIterator.hasNext() && copyIterator.hasNext()) {
                origSamRecords.add(origIterator.next());
                cramRecords.add(copyIterator.next());
            }
            Assert.assertEquals(origIterator.hasNext(), copyIterator.hasNext());
            Assert.assertEquals(cramRecords.size(), origSamRecords.size());
            Assert.assertEquals(cramRecords, origSamRecords);
        }
    }

    @Test
    public final void testSamtoolsMateResolution() throws IOException {
        final File inputFile = new File(TEST_DATA_DIR, "mateResolution2.samtools.cram");
        //final File referenceFile = new File(TEST_DATA_DIR, "human_g1k_v37.20.subset.fasta");
        final File referenceFile = new File("/Users/cnorman/projects/references/hg38/Homo_sapiens_assembly38.fasta");

        final CRAMEncodingStrategy testStrategy = new CRAMEncodingStrategy();
        final File tempOutCRAM = File.createTempFile("testMateResolution", ".cram");
        CRAMTestUtils.writeToCRAMWithEncodingStrategy(testStrategy, inputFile, tempOutCRAM, referenceFile);

        try (final SamReader origReader = SamReaderFactory.makeDefault()
                .referenceSequence(referenceFile)
                .validationStringency((ValidationStringency.SILENT))
                .open(inputFile);
             final CRAMFileReader copyReader = new CRAMFileReader(tempOutCRAM, new ReferenceSource(referenceFile))) {
            final SAMRecordIterator origIterator = origReader.iterator();
            final SAMRecordIterator copyIterator = copyReader.getIterator();
            final List<SAMRecord> origSamRecords  = new ArrayList<>();
            final List<SAMRecord> cramRecords  = new ArrayList<>();
            while (origIterator.hasNext() && copyIterator.hasNext()) {
                origSamRecords.add(origIterator.next());
                cramRecords.add(copyIterator.next());
            }
            Assert.assertEquals(origIterator.hasNext(), copyIterator.hasNext());
            Assert.assertEquals(cramRecords.size(), origSamRecords.size());
            Assert.assertEquals(cramRecords, origSamRecords);
        }
    }


    @Test
    public void testUnsorted() throws IOException {
        final SAMRecordSetBuilder builder = new SAMRecordSetBuilder(false, SAMFileHeader.SortOrder.unsorted);
        builder.addFrag("1", 0, 2, false);
        builder.addFrag("1", 0, 1, false);
        final Collection<SAMRecord> records = builder.getRecords();

        testRecords(records, records.iterator().next().getReadBases());
    }

    // testing for a contig found in the reads but not in the reference
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testContigNotFoundInRef() throws IOException {
        boolean sawException = false;
        final File CRAMFile = new File("src/test/resources/htsjdk/samtools/cram/CRAMException/testContigNotInRef.cram");
        final File refFile = new File("src/test/resources/htsjdk/samtools/cram/CRAMException/testContigNotInRef.fa");
        final ReferenceSource refSource = new ReferenceSource(refFile);
        final CRAMIterator iterator = new CRAMIterator(new FileInputStream(CRAMFile), refSource, ValidationStringency.STRICT);
        while (iterator.hasNext()) {
            iterator.next();
        }
    }

    @Test
    public void testBizilionTags() throws IOException {
        final SAMRecordSetBuilder builder = new SAMRecordSetBuilder();
        builder.addFrag("1", 0, 1, false);
        SAMRecord record = builder.getRecords().iterator().next();
        for (int i = 0; i < 1000; i++) {
            char b1 = (char) ('A' + i / 26);
            char b2 = (char) ('A' + i % 26);
            String tag = new String(new char[]{b1, b2});
            if ("RG".equals(tag)) {
                continue;
            }
            record.setAttribute(tag, i);
        }

        record.setAlignmentStart(1);
        testSingleRecord(record, record.getReadBases());
    }


    // TODO: this specifically tests that reads that are mapped beyond the end of the reference
    // is ok ??
    @Test
    public void testNullsAndBeyondRef() throws IOException {
        // bases, scores, reference
        testSingleRecord("A".getBytes(), "!".getBytes(), "A".getBytes());
        testSingleRecord("A".getBytes(), SAMRecord.NULL_QUALS, "A".getBytes());
        testSingleRecord(SAMRecord.NULL_SEQUENCE, SAMRecord.NULL_QUALS, "A".getBytes());
        testSingleRecord("AAA".getBytes(), "!!!".getBytes(), "A".getBytes());
    }

    private static void testRecords(Collection<SAMRecord> records, byte[] ref) throws IOException {
        CRAMFileReader cramFileReader = CRAMTestUtils.writeAndReadFromInMemoryCram(records, ref);
        Iterator<SAMRecord> it;
        final SAMRecordIterator iterator = cramFileReader.getIterator();
        Assert.assertTrue(iterator.hasNext());

        it = records.iterator();
        while (it.hasNext()) {
            SAMRecord record = it.next();
            SAMRecord s2 = iterator.next();
            Assert.assertNotNull(s2);
            Assert.assertEquals(record.getFlags(), s2.getFlags());
            Assert.assertEquals(record.getReadName(), s2.getReadName());
            Assert.assertEquals(record.getReferenceName(), s2.getReferenceName());
            Assert.assertEquals(record.getAlignmentStart(), s2.getAlignmentStart());
            Assert.assertEquals(record.getReadBases(), s2.getReadBases());
            Assert.assertEquals(record.getBaseQualities(), s2.getBaseQualities());
        }
        Assert.assertFalse(iterator.hasNext());
    }

    private static void testSingleRecord(SAMRecord record, byte[] ref) throws IOException {
        CRAMFileReader cramFileReader = CRAMTestUtils.writeAndReadFromInMemoryCram(Collections.singletonList(record), ref);
        final SAMRecordIterator iterator = cramFileReader.getIterator();
        Assert.assertTrue(iterator.hasNext());
        SAMRecord s2 = iterator.next();
        Assert.assertNotNull(s2);
        Assert.assertFalse(iterator.hasNext());

        Assert.assertEquals(record.getFlags(), s2.getFlags());
        Assert.assertEquals(record.getReadName(), s2.getReadName());
        Assert.assertEquals(record.getReferenceName(), s2.getReferenceName());
        Assert.assertEquals(record.getAlignmentStart(), s2.getAlignmentStart());
        Assert.assertEquals(record.getReadBases(), s2.getReadBases());
        Assert.assertEquals(record.getBaseQualities(), s2.getBaseQualities());
    }

    private void testSingleRecord(byte[] bases, byte[] scores, byte[] ref) throws IOException {
        SAMFileHeader header = new SAMFileHeader();
        header.addReadGroup(new SAMReadGroupRecord("1"));
        header.addSequence(new SAMSequenceRecord("chr1", ref.length));
        SAMRecord s = new SAMRecord(header);
        s.setReadBases(bases);
        s.setBaseQualities(scores);
        s.setFlags(0);
        s.setAlignmentStart(1);
        s.setReferenceName("chr1");
        s.setReadName("1");
        if (bases == SAMRecord.NULL_SEQUENCE) {
            s.setCigarString("10M");
        } else {
            s.setCigarString(s.getReadLength() + "M");
        }

        testSingleRecord(s, ref);
    }
}
