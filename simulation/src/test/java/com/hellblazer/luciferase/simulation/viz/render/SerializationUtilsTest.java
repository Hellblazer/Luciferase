package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SerializationUtils - GZIP compression and ESVO/ESVT serialization.
 */
class SerializationUtilsTest {

    @Test
    void testESVORoundTrip() {
        // Create sample ESVO data
        var octreeData = new ESVOOctreeData(1024);
        octreeData.setMaxDepth(5);
        octreeData.setLeafCount(10);
        octreeData.setInternalCount(5);

        // Add sample nodes
        octreeData.setNode(0, new ESVONodeUnified((byte) 0xFF, (byte) 0x0F, false, 1, (byte) 0, 0));
        octreeData.setNode(1, new ESVONodeUnified((byte) 0x00, (byte) 0xFF, false, 2, (byte) 0, 0));
        octreeData.setNode(2, new ESVONodeUnified((byte) 0xAA, (byte) 0x55, false, 0, (byte) 0, 0));

        // Add far pointers
        octreeData.setFarPointers(new int[]{1000, 2000, 3000});

        // Serialize
        byte[] serialized = SerializationUtils.serializeESVO(octreeData);
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);

        // Deserialize
        var deserialized = SerializationUtils.deserializeESVO(serialized);
        assertNotNull(deserialized);
        assertEquals(octreeData.maxDepth(), deserialized.maxDepth());
        assertEquals(octreeData.leafCount(), deserialized.leafCount());
        assertEquals(octreeData.internalCount(), deserialized.internalCount());
        assertEquals(octreeData.getNodeCount(), deserialized.getNodeCount());

        // Verify nodes
        var node0 = deserialized.getNode(0);
        assertNotNull(node0);
        assertEquals(0xFF, node0.getLeafMask());
        assertEquals(0x0F, node0.getChildMask());

        // Verify far pointers
        assertArrayEquals(new int[]{1000, 2000, 3000}, deserialized.getFarPointers());
    }

    @Test
    void testESVTRoundTrip() {
        // Create sample ESVT data
        var nodes = new ESVTNodeUnified[]{
                new ESVTNodeUnified((byte) 0), // S0
                new ESVTNodeUnified((byte) 1), // S1
                new ESVTNodeUnified((byte) 2)  // S2
        };

        var contours = new int[]{100, 200, 300};
        var farPointers = new int[]{500, 600};
        var esvtData = new ESVTData(nodes, contours, farPointers, 0, 5, 2, 1, 64, new int[0]);

        // Serialize
        byte[] serialized = SerializationUtils.serializeESVT(esvtData);
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);

        // Deserialize
        var deserialized = SerializationUtils.deserializeESVT(serialized);
        assertNotNull(deserialized);
        assertEquals(esvtData.rootType(), deserialized.rootType());
        assertEquals(esvtData.maxDepth(), deserialized.maxDepth());
        assertEquals(esvtData.leafCount(), deserialized.leafCount());
        assertEquals(esvtData.internalCount(), deserialized.internalCount());
        assertEquals(esvtData.gridResolution(), deserialized.gridResolution());
        assertEquals(esvtData.nodes().length, deserialized.nodes().length);

        // Verify contours and far pointers
        assertArrayEquals(contours, deserialized.contours());
        assertArrayEquals(farPointers, deserialized.farPointers());
    }

    @Test
    void testGZIPCompressionRatio() throws IOException {
        // Create compressible data (lots of zeros)
        byte[] original = new byte[1000];
        for (int i = 0; i < 100; i++) {
            original[i] = (byte) i;
        }
        // Rest are zeros

        byte[] compressed = SerializationUtils.compress(original);

        // Verify compression happened (should be much smaller)
        assertTrue(compressed.length < original.length * 0.5,
                String.format("Expected compression, got %d bytes from %d bytes",
                        compressed.length, original.length));

        // Verify GZIP magic number
        assertTrue(SerializationUtils.isCompressed(compressed),
                "Compressed data should have GZIP magic number");
    }

    @Test
    void testGZIPRoundTrip() throws IOException {
        byte[] original = "Hello, ESVO/ESVT compression test!".repeat(20).getBytes();

        // Compress
        byte[] compressed = SerializationUtils.compress(original);
        assertTrue(SerializationUtils.isCompressed(compressed));

        // Decompress
        byte[] decompressed = SerializationUtils.decompress(compressed);

        // Verify round-trip
        assertArrayEquals(original, decompressed);
    }

    @Test
    void testVersionValidationRejection_ESVO() {
        // Create data with invalid version
        byte[] invalidData = new byte[50];
        invalidData[0] = (byte) 99; // Invalid version

        // Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            SerializationUtils.deserializeESVO(invalidData);
        }, "Should reject unsupported ESVO version");
    }

    @Test
    void testFormatValidationRejection_ESVT() {
        // Create data with invalid version
        byte[] invalidData = new byte[50];
        invalidData[0] = (byte) 99; // Invalid version

        // Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            SerializationUtils.deserializeESVT(invalidData);
        }, "Should reject unsupported ESVT version");
    }

    @Test
    void testCompressionThreshold() throws IOException {
        // Small data (below 200 byte threshold)
        byte[] smallData = "Small data".getBytes(); // ~10 bytes
        byte[] compressedSmall = SerializationUtils.compress(smallData);

        // Should NOT be compressed (returned as-is)
        assertFalse(SerializationUtils.isCompressed(compressedSmall),
                "Data below 200 bytes should not be GZIP compressed");
        assertArrayEquals(smallData, compressedSmall,
                "Small data should be returned unmodified");

        // Large data (above 200 byte threshold)
        byte[] largeData = "Large data ".repeat(30).getBytes(); // ~300 bytes
        byte[] compressedLarge = SerializationUtils.compress(largeData);

        // Should be compressed
        assertTrue(SerializationUtils.isCompressed(compressedLarge),
                "Data above 200 bytes should be GZIP compressed");
        assertTrue(compressedLarge.length < largeData.length,
                "Large data should be compressed to smaller size");

        // Verify decompression works correctly for both
        assertArrayEquals(smallData, SerializationUtils.decompress(compressedSmall));
        assertArrayEquals(largeData, SerializationUtils.decompress(compressedLarge));
    }
}
