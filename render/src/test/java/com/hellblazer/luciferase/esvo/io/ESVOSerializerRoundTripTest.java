package com.hellblazer.luciferase.esvo.io;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip and security tests for ESVOSerializer / ESVODeserializer (VERSION_3).
 *
 * Covers Luciferase-7wzml.24 (farPointers round-trip),
 *        Luciferase-7wzml.25 (sparse format — size O(actual node count)),
 *        Luciferase-7wzml.66 (corrupt-header DoS defence + truncated-file EOF).
 */
class ESVOSerializerRoundTripTest {

    @TempDir
    Path tmp;

    // -------------------------------------------------------------------------
    // 7wzml.24 — farPointers round-trip (isFar() nodes resolve identically)
    // -------------------------------------------------------------------------

    @Test
    void farPointers_roundTrip_byteIdenticalAndChildResolutionPreserved() throws IOException {
        ESVOOctreeData octree = new ESVOOctreeData(1024);

        // Node 0: normal node with children
        ESVONodeUnified root = new ESVONodeUnified();
        root.setChildMask(0b00000011);  // children 0 and 1
        root.setChildPtr(1);
        root.setFar(false);
        octree.setNode(0, root);

        // Node 1: a far-pointer node — childPtr is an index into farPointers[]
        ESVONodeUnified farNode = new ESVONodeUnified();
        farNode.setChildMask(0b00000001);
        farNode.setChildPtr(0);          // index 0 in farPointers
        farNode.setFar(true);
        octree.setNode(1, farNode);

        // Node at index 500 — reachable via farPointers[0]
        ESVONodeUnified distant = new ESVONodeUnified();
        distant.setChildMask(0b00000000); // leaf
        octree.setNode(500, distant);

        // farPointers[0] = relative offset from node-1 to node-500 = 499
        int[] farPtrs = { 499 };
        octree.setFarPointers(farPtrs);

        Path file = tmp.resolve("far.esvo");
        try (ESVOSerializer ser = new ESVOSerializer()) {
            ser.serialize(octree, file);
        }

        ESVODeserializer deser = new ESVODeserializer();
        ESVOOctreeData loaded = deser.deserialize(file);

        // farPointers byte-identical
        assertArrayEquals(farPtrs, loaded.getFarPointers(),
                "getFarPointers() must be byte-identical after round-trip");

        // isFar() node still present
        ESVONodeUnified reloaded = loaded.getNode(1);
        assertNotNull(reloaded, "Far node must survive round-trip");
        assertTrue(reloaded.isFar(), "isFar() must be true after round-trip");

        // Child resolution identical pre and post load
        int[] fpBefore = octree.getFarPointers();
        int[] fpAfter  = loaded.getFarPointers();
        int childBefore = octree.getNode(1).getChildIndex(0, 1, fpBefore);
        int childAfter  = loaded.getNode(1).getChildIndex(0, 1, fpAfter);
        assertEquals(childBefore, childAfter,
                "Child resolution via farPointers must be identical pre/post round-trip");
        assertEquals(500, childAfter, "Far child must resolve to node 500");
    }

    // -------------------------------------------------------------------------
    // 7wzml.25 — sparse format: huge-index node serialises O(actual node count)
    // -------------------------------------------------------------------------

    @Test
    void sparseOctree_hugeIndex_serializedSizeIsConstantNotProportionalToMaxIndex() throws IOException {
        ESVOOctreeData octree = new ESVOOctreeData(1024);

        // A single node at a very large index — was previously O(1_000_001 * 12 B) = ~12 MB
        int hugeIndex = 1_000_000;
        ESVONodeUnified node = new ESVONodeUnified();
        node.setChildMask(0b00000001);
        octree.setNode(hugeIndex, node);

        Path file = tmp.resolve("sparse.esvo");
        try (ESVOSerializer ser = new ESVOSerializer()) {
            ser.serialize(octree, file);
        }

        long fileSize = Files.size(file);
        // Header (40) + 1 node triple (12) = 52 bytes — allow generous headroom but nowhere near 12 MB
        assertTrue(fileSize < 1024,
                "Sparse octree file size must be O(actual node count), was " + fileSize + " bytes");

        // Round-trip: present and absent indices preserved
        ESVODeserializer deser = new ESVODeserializer();
        ESVOOctreeData loaded = deser.deserialize(file);

        assertNotNull(loaded.getNode(hugeIndex),
                "Node at huge index must survive round-trip");
        assertNull(loaded.getNode(0),
                "Absent index 0 must not be materialised after round-trip");
        assertNull(loaded.getNode(500000),
                "Absent index 500000 must not be materialised after round-trip");
        assertEquals(1, loaded.getNodeCount(),
                "Only 1 node should be present after round-trip");
    }

    @Test
    void sparseOctree_multipleNodes_presentAndAbsentIndicesPreserved() throws IOException {
        ESVOOctreeData octree = new ESVOOctreeData(4096);
        // Insert at non-contiguous indices
        int[] presentIndices = { 0, 7, 42, 1000, 9999 };
        for (int idx : presentIndices) {
            ESVONodeUnified n = new ESVONodeUnified();
            n.setChildMask((byte) (idx & 0xFF));
            octree.setNode(idx, n);
        }

        Path file = tmp.resolve("multi.esvo");
        try (ESVOSerializer ser = new ESVOSerializer()) {
            ser.serialize(octree, file);
        }

        ESVODeserializer deser = new ESVODeserializer();
        ESVOOctreeData loaded = deser.deserialize(file);

        assertEquals(presentIndices.length, loaded.getNodeCount());
        for (int idx : presentIndices) {
            assertNotNull(loaded.getNode(idx), "Node at " + idx + " must survive");
        }
        // Absent gaps
        assertNull(loaded.getNode(1), "Gap index 1 must be absent");
        assertNull(loaded.getNode(500), "Gap index 500 must be absent");
    }

    // -------------------------------------------------------------------------
    // 7wzml.66 — malformed header: corrupt nodeCount → IOException not OOM/NPE
    // -------------------------------------------------------------------------

    @Test
    void malformedHeader_nodeCountNearMaxInt_throwsIOExceptionNotOOM() throws IOException {
        Path file = tmp.resolve("corrupt_large.esvo");
        writeCorruptHeader(file, Integer.MAX_VALUE, ESVOFileFormat.VERSION_3);

        ESVODeserializer deser = new ESVODeserializer();
        IOException ex = assertThrows(IOException.class, () -> deser.deserialize(file),
                "Corrupt nodeCount near MAX_VALUE must throw IOException, not OOM");
        // Must not be NegativeArraySizeException or OutOfMemoryError wrapped in IOE
        assertFalse(ex.getCause() instanceof NegativeArraySizeException,
                "Must not be NegativeArraySizeException");
        assertFalse(ex.getCause() instanceof OutOfMemoryError,
                "Must not be OutOfMemoryError");
    }

    @Test
    void malformedHeader_nodeCountNegative_throwsIOException() throws IOException {
        Path file = tmp.resolve("corrupt_neg.esvo");
        writeCorruptHeader(file, -1, ESVOFileFormat.VERSION_3);

        ESVODeserializer deser = new ESVODeserializer();
        assertThrows(IOException.class, () -> deser.deserialize(file),
                "Negative nodeCount must throw IOException");
    }

    @Test
    void malformedHeader_nodeCountExceedsSafeCap_throwsIOException() throws IOException {
        Path file = tmp.resolve("corrupt_cap.esvo");
        // Just over the safety cap — file is otherwise tiny
        writeCorruptHeader(file, ESVOFileFormat.MAX_SAFE_NODE_COUNT + 1, ESVOFileFormat.VERSION_3);

        ESVODeserializer deser = new ESVODeserializer();
        assertThrows(IOException.class, () -> deser.deserialize(file),
                "nodeCount exceeding safety cap must throw IOException");
    }

    // -------------------------------------------------------------------------
    // 7wzml.66 — truncated file → EOFException (not silent stale-buffer parse)
    // -------------------------------------------------------------------------

    @Test
    void truncatedFile_throwsIOException() throws IOException {
        // Build a valid file then truncate it mid-node-section
        ESVOOctreeData octree = new ESVOOctreeData(1024);
        for (int i = 0; i < 10; i++) {
            octree.setNode(i, new ESVONodeUnified());
        }
        Path full = tmp.resolve("full.esvo");
        try (ESVOSerializer ser = new ESVOSerializer()) {
            ser.serialize(octree, full);
        }

        // Truncate after header but before all nodes are written
        long truncateAt = ESVOFileFormat.HEADER_SIZE_V3 + 6; // mid-way through first node triple
        Path truncated = tmp.resolve("truncated.esvo");
        Files.write(truncated, Files.readAllBytes(full));
        try (FileChannel fc = FileChannel.open(truncated, StandardOpenOption.WRITE)) {
            fc.truncate(truncateAt);
        }

        ESVODeserializer deser = new ESVODeserializer();
        // Truncation is caught at validateCount (nodeCount*12 > fileSize) or at fillBuffer (EOFException).
        // Either way the result is an IOException — never a silent stale-buffer parse or OOM.
        assertThrows(IOException.class, () -> deser.deserialize(truncated),
                "Truncated file must throw IOException (or EOFException), not parse stale zeroes");
    }

    @Test
    void truncatedFileMidNodeSection_throwsEOFException() throws IOException {
        // Write a *valid* header with nodeCount=1 but then write only 6 of the 12 node bytes.
        // The validateCount check passes (header claims 1 node = 12 bytes, file is just big enough
        // to look plausible at header-read time), but the fillBuffer loop hits EOF.
        Path file = tmp.resolve("mid_truncated.esvo");
        int headerSize = ESVOFileFormat.HEADER_SIZE_V3;
        // Fabricate a header that claims 1 node but we only write partial data
        ByteBuffer hdr = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(ESVOFileFormat.MAGIC_NUMBER);
        hdr.putInt(ESVOFileFormat.VERSION_3);
        hdr.putInt(1);   // nodeCount = 1 — 12 bytes of node data expected
        hdr.putInt(0);   // reserved
        hdr.putLong(0L); // metadataOffset
        hdr.putLong(0L); // metadataSize
        hdr.putInt(0);   // farPtrCount
        hdr.putInt(0);   // reserved2
        hdr.flip();
        // Write header + only 6 bytes of node data (partial triple)
        ByteBuffer partial = ByteBuffer.allocate(headerSize + 6).order(ByteOrder.LITTLE_ENDIAN);
        partial.put(hdr);
        partial.putInt(42);  // index
        partial.putShort((short) 0); // only 6 of 12 bytes
        partial.flip();
        try (FileChannel fc = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            fc.write(partial);
        }

        ESVODeserializer deser = new ESVODeserializer();
        assertThrows(EOFException.class, () -> deser.deserialize(file),
                "Partial node section must throw EOFException");
    }

    // -------------------------------------------------------------------------
    // fillBuffer helper — unit test the loop boundary
    // -------------------------------------------------------------------------

    @Test
    void fillBuffer_shortChannelThrowsEOFException() throws IOException {
        // Write only 4 bytes then try to fill an 8-byte buffer
        Path tiny = tmp.resolve("tiny.bin");
        Files.write(tiny, new byte[] { 1, 2, 3, 4 });

        try (FileChannel ch = FileChannel.open(tiny, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(8);
            assertThrows(EOFException.class, () -> ESVODeserializer.fillBuffer(ch, buf));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Write a minimal header with the given nodeCount so the deserializer
     * hits the validation check before any large allocation.
     */
    private static void writeCorruptHeader(Path file, int nodeCount, int version) throws IOException {
        // Header size depends on version; always write a V3 header for our corrupt tests
        int headerSize = ESVOFileFormat.HEADER_SIZE_V3;
        ByteBuffer buf = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(ESVOFileFormat.MAGIC_NUMBER); // magic
        buf.putInt(version);                      // version
        buf.putInt(nodeCount);                    // nodeCount — intentionally corrupt
        buf.putInt(0);                            // reserved
        buf.putLong(0L);                          // metadataOffset
        buf.putLong(0L);                          // metadataSize
        buf.putInt(0);                            // farPtrCount
        buf.putInt(0);                            // reserved2
        buf.flip();
        Files.write(file, new byte[0]); // create
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.WRITE)) {
            fc.write(buf);
        }
    }
}
