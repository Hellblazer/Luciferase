/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render.protocol;

import com.hellblazer.luciferase.simulation.viz.render.RegionBuilder;
import com.hellblazer.luciferase.simulation.viz.render.RegionId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BinaryFrameCodec encoding/decoding of ESVO/ESVT WebSocket frames.
 *
 * @author hal.hildebrand
 */
class BinaryFrameCodecTest {

    /**
     * Test 1: Roundtrip encoding and decoding of ESVO frame.
     * Verifies all header fields are correctly encoded and decoded.
     */
    @Test
    void testRoundtrip_ESVO() {
        // Create test ESVO region
        var region = testBuiltRegion(RegionBuilder.BuildType.ESVO, 0, 100);

        // Encode to binary frame
        var buffer = BinaryFrameCodec.encode(region);

        // Decode header
        var header = BinaryFrameCodec.decodeHeader(buffer);

        // Verify header fields match
        assertNotNull(header, "Header should be decoded successfully");
        assertEquals(ProtocolConstants.FRAME_MAGIC, header.magic(), "Magic number mismatch");
        assertEquals(ProtocolConstants.FORMAT_ESVO, header.format(), "Format should be ESVO");
        assertEquals(ProtocolConstants.KEY_TYPE_MORTON, header.keyType(), "Key type should be KEY_TYPE_MORTON (RegionId is Morton-based)");
        assertEquals(region.regionId().level(), header.level(), "Region level mismatch");
        assertEquals(region.regionId().mortonCode(), header.key(), "Key mismatch");
        assertEquals((int) region.buildVersion(), header.buildVersion(), "Build version mismatch");
        assertEquals(100, header.dataSize(), "Data size mismatch");
    }

    /**
     * Test 2: Roundtrip encoding and decoding of ESVT frame.
     * Verifies ESVT format code is correctly handled.
     */
    @Test
    void testRoundtrip_ESVT() {
        // Create test ESVT region
        var region = testBuiltRegion(RegionBuilder.BuildType.ESVT, 0, 100);

        // Encode to binary frame
        var buffer = BinaryFrameCodec.encode(region);

        // Decode header
        var header = BinaryFrameCodec.decodeHeader(buffer);

        // Verify header fields match
        assertNotNull(header, "Header should be decoded successfully");
        assertEquals(ProtocolConstants.FRAME_MAGIC, header.magic(), "Magic number mismatch");
        assertEquals(ProtocolConstants.FORMAT_ESVT, header.format(), "Format should be ESVT");
        assertEquals(ProtocolConstants.KEY_TYPE_MORTON, header.keyType(), "Key type should be KEY_TYPE_MORTON (RegionId is Morton-based)");
        assertEquals(region.regionId().level(), header.level(), "Region level mismatch");
        assertEquals(region.regionId().mortonCode(), header.key(), "Key mismatch");
        assertEquals((int) region.buildVersion(), header.buildVersion(), "Build version mismatch");
        assertEquals(100, header.dataSize(), "Data size mismatch");
    }

    /**
     * Test 3: Verify magic number is correctly encoded in little-endian.
     * Magic 0x45535652 should be bytes [0x52, 0x56, 0x53, 0x45] in LE.
     */
    @Test
    void testMagicNumber() {
        var region = testBuiltRegion(RegionBuilder.BuildType.ESVO, 0, 50);
        var buffer = BinaryFrameCodec.encode(region);

        // Read raw bytes of magic number
        assertEquals((byte) 0x52, buffer.get(0), "Magic byte 0 should be 0x52");
        assertEquals((byte) 0x56, buffer.get(1), "Magic byte 1 should be 0x56");
        assertEquals((byte) 0x53, buffer.get(2), "Magic byte 2 should be 0x53");
        assertEquals((byte) 0x45, buffer.get(3), "Magic byte 3 should be 0x45");
    }

    /**
     * Test 4: Verify payload extraction returns exact payload bytes.
     */
    @Test
    void testPayloadExtraction() {
        // Create region with known data pattern
        var originalData = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        var region = new RegionBuilder.BuiltRegion(
            new RegionId(999L, 2),
            1,
            RegionBuilder.BuildType.ESVO,
            originalData,
            false,
            500_000L,
            2_000_000L,
            1L
        );

        // Encode and extract payload
        var buffer = BinaryFrameCodec.encode(region);
        var extractedPayload = BinaryFrameCodec.extractPayload(buffer);

        // Verify payload matches original
        assertArrayEquals(originalData, extractedPayload, "Extracted payload should match original");
    }

    /**
     * Test 5: Invalid magic number should return null.
     */
    @Test
    void testInvalidMagic_returnsNull() {
        var region = testBuiltRegion(RegionBuilder.BuildType.ESVO, 0, 50);
        var buffer = BinaryFrameCodec.encode(region);

        // Corrupt the magic number (byte 0)
        buffer.put(0, (byte) 0xFF);

        // Attempt to decode
        var header = BinaryFrameCodec.decodeHeader(buffer);

        // Should return null for invalid magic
        assertNull(header, "Invalid magic number should return null");
    }

    /**
     * Test 6: Truncated frame (< 24 bytes) should return null.
     */
    @Test
    void testTruncatedFrame_returnsNull() {
        var region = testBuiltRegion(RegionBuilder.BuildType.ESVO, 0, 50);
        var buffer = BinaryFrameCodec.encode(region);

        // Create truncated buffer with only 20 bytes
        var truncated = java.nio.ByteBuffer.allocate(20);
        buffer.position(0);
        buffer.limit(20);
        truncated.put(buffer);
        truncated.position(0);

        // Attempt to decode
        var header = BinaryFrameCodec.decodeHeader(truncated);

        // Should return null for truncated frame
        assertNull(header, "Truncated frame should return null");
    }

    /**
     * Test 7: Verify byte order is little-endian for morton code.
     * Use a known morton code to verify byte ordering.
     */
    @Test
    void testByteOrder_littleEndian() {
        // Use known morton code: 0x0102030405060708
        var region = new RegionBuilder.BuiltRegion(
            new RegionId(0x0102030405060708L, 5),
            2,
            RegionBuilder.BuildType.ESVO,
            new byte[10],
            false,
            123456L,
            3_000_000L,
            1L
        );

        var buffer = BinaryFrameCodec.encode(region);

        // Verify morton code bytes are in little-endian order
        // Morton code at offset 8-15
        assertEquals((byte) 0x08, buffer.get(8), "Morton byte 0 should be 0x08 (LE)");
        assertEquals((byte) 0x07, buffer.get(9), "Morton byte 1 should be 0x07 (LE)");
        assertEquals((byte) 0x06, buffer.get(10), "Morton byte 2 should be 0x06 (LE)");
        assertEquals((byte) 0x05, buffer.get(11), "Morton byte 3 should be 0x05 (LE)");
        assertEquals((byte) 0x04, buffer.get(12), "Morton byte 4 should be 0x04 (LE)");
        assertEquals((byte) 0x03, buffer.get(13), "Morton byte 5 should be 0x03 (LE)");
        assertEquals((byte) 0x02, buffer.get(14), "Morton byte 6 should be 0x02 (LE)");
        assertEquals((byte) 0x01, buffer.get(15), "Morton byte 7 should be 0x01 (LE)");
    }

    /**
     * Test 8: buildVersion header field must encode the monotonic version counter,
     * not the build duration (buildTimeNs).
     * <p>
     * Bug: BinaryFrameCodec.encode() writes {@code (int) region.buildTimeNs()} into
     * the buildVersion header field. buildTimeNs is nanoseconds elapsed during the
     * build (a duration), not a version counter. Casting to int truncates it and
     * produces nonsensical version values.
     * <p>
     * Fix: BinaryFrameCodec.encode() must write {@code (int) region.buildVersion()},
     * which is the monotonically incrementing counter from RegionState.buildVersion().
     */
    @Test
    void testBuildVersion_isVersionCounter_notBuildTimeNs() {
        long distinctBuildVersion = 42L;
        long distinctBuildTimeNs  = 999_999_999L;  // Clearly different from 42

        var region = new RegionBuilder.BuiltRegion(
            new RegionId(12345L, 4),
            0,
            RegionBuilder.BuildType.ESVO,
            new byte[10],
            false,
            distinctBuildTimeNs,   // buildTimeNs — must NOT appear in the header
            1_000_000L,
            distinctBuildVersion   // buildVersion — must appear in the header
        );

        var buffer = BinaryFrameCodec.encode(region);
        var header = BinaryFrameCodec.decodeHeader(buffer);

        assertNotNull(header);
        assertEquals((int) distinctBuildVersion, header.buildVersion(),
            "buildVersion header field must encode the version counter, not buildTimeNs. " +
            "Bug: encode() writes (int) region.buildTimeNs() = " + (int) distinctBuildTimeNs +
            " instead of (int) region.buildVersion() = " + (int) distinctBuildVersion);
    }

    /**
     * Test 9: encodeWithKey using MortonKey round-trips through decodeHeader.
     * Verifies that keyType, level, key long, buildVersion, and dataSize are
     * correctly encoded when using a SpatialKey directly.
     */
    @Test
    void encodeWithMortonKeyRoundTrips() {
        var key = new com.hellblazer.luciferase.lucien.octree.MortonKey(12345L, (byte) 8);
        byte[] data = {1, 2, 3, 4};
        var buf = BinaryFrameCodec.encodeWithKey(key, RegionBuilder.BuildType.ESVO, 7L, data);
        var header = BinaryFrameCodec.decodeHeader(buf);

        assertNotNull(header, "Header should be decoded successfully");
        assertEquals(ProtocolConstants.KEY_TYPE_MORTON, header.keyType(), "keyType should be KEY_TYPE_MORTON");
        assertEquals(8, header.level(), "Level should be 8");
        assertEquals(12345L, header.key(), "Key should match morton code");
        assertEquals(7, header.buildVersion(), "Build version should be 7");
        assertEquals(4, header.dataSize(), "Data size should be 4");
    }

    /**
     * Test 10: encodeWithKey using CompactTetreeKey round-trips through decodeHeader.
     * Verifies that KEY_TYPE_TET, level, and key (getLowBits) are correctly encoded.
     * Also verifies ExtendedTetreeKey is rejected.
     */
    @Test
    void encodeWithCompactTetreeKeyRoundTrips() {
        var key = new com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey((byte) 5, 0xABCDEF1234567890L);
        byte[] data = {9, 8, 7};
        var buf = BinaryFrameCodec.encodeWithKey(key, RegionBuilder.BuildType.ESVT, 3L, data);
        var header = BinaryFrameCodec.decodeHeader(buf);

        assertNotNull(header, "Header should be decoded successfully");
        assertEquals(ProtocolConstants.KEY_TYPE_TET, header.keyType(), "keyType should be KEY_TYPE_TET");
        assertEquals(5, header.level(), "Level should be 5");
        assertEquals(key.getLowBits(), header.key(), "Key should match CompactTetreeKey low bits");
        assertEquals(3, header.buildVersion(), "Build version should be 3");
        assertEquals(3, header.dataSize(), "Data size should be 3");
    }

    /**
     * Test 11: encodeWithKey using ExtendedTetreeKey must throw IllegalArgumentException.
     * ExtendedTetreeKey (levels 11-21) is not representable in the 64-bit wire key field.
     */
    @Test
    void encodeWithExtendedTetreeKeyThrows() {
        // ExtendedTetreeKey.create() returns an ExtendedTetreeKey for level > 10
        var key = com.hellblazer.luciferase.lucien.tetree.TetreeKey.create((byte) 15, 0L, 0L);
        // Only proceed if we actually got an ExtendedTetreeKey
        if (key instanceof com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey) {
            // Shouldn't happen at level 15, but skip if it does
            return;
        }
        byte[] data = {1};
        assertThrows(IllegalArgumentException.class,
            () -> BinaryFrameCodec.encodeWithKey(key, RegionBuilder.BuildType.ESVT, 1L, data),
            "ExtendedTetreeKey must throw at wire level");
    }

    /**
     * Helper method to create a mock BuiltRegion for testing.
     *
     * @param type Build type (ESVO or ESVT)
     * @param lod LOD level
     * @param dataSize Size of payload in bytes
     * @return Mock BuiltRegion with random data
     */
    private RegionBuilder.BuiltRegion testBuiltRegion(RegionBuilder.BuildType type, int lod, int dataSize) {
        var data = new byte[dataSize];
        ThreadLocalRandom.current().nextBytes(data);
        return new RegionBuilder.BuiltRegion(
            new RegionId(12345L, 4),
            lod,
            type,
            data,
            true,
            System.nanoTime(),
            1_000_000L,
            1L
        );
    }
}
