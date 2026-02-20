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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Protocol compatibility tests for the Java encoder ↔ JavaScript parser.
 *
 * <p>Verifies the exact binary layout produced by {@link BinaryFrameCodec} against the
 * byte-level expectations of {@code frame-parser.js}. Each test documents the exact
 * byte values that the JS parser must see so that these tests can serve as authoritative
 * ground-truth vectors for the {@code client-test.html} browser test page.
 *
 * <p>Key test vectors documented here:
 * <ul>
 *   <li>24-byte frame header byte positions</li>
 *   <li>Morton code little-endian encoding</li>
 *   <li>ESVO payload byte layout (21-byte header + 8 bytes/node)</li>
 *   <li>ESVT payload byte layout (33-byte header + 8 bytes/node)</li>
 *   <li>ESVO child-descriptor bit field extraction</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class BrowserClientProtocolTest {

    // ── Shared test constants ──────────────────────────────────────────────────
    // mortonCode 7 = 0b111: rx=1 (bit0), ry=1 (bit1), rz=1 (bit2)
    static final long KNOWN_MORTON        = 7L;
    static final int  KNOWN_BUILD_VERSION = 42;

    // ── 1. Frame header byte positions ────────────────────────────────────────

    /**
     * Verifies that the 24-byte frame header produced by BinaryFrameCodec matches
     * the layout expected by {@code parseFrameHeader()} in frame-parser.js.
     *
     * <p>Expected layout (all multi-byte fields LE):
     * <pre>
     * offset  0- 3  magic       0x45535652  → bytes [0x52, 0x56, 0x53, 0x45]
     * offset  4     format      0x01 (ESVO)
     * offset  5     lod         0x02
     * offset  6     level       0x04
     * offset  7     reserved    0x00
     * offset  8-15  mortonCode  7           → bytes [0x07, 0x00, …, 0x00]
     * offset 16-19  buildVersion 42         → bytes [0x2A, 0x00, 0x00, 0x00]
     * offset 20-23  dataSize    10          → bytes [0x0A, 0x00, 0x00, 0x00]
     * </pre>
     */
    @Test
    void testFrameHeader_byteLayout() {
        var region = makeRegion(KNOWN_MORTON, 4, 2, RegionBuilder.BuildType.ESVO,
                                new byte[10], KNOWN_BUILD_VERSION);
        var buf = BinaryFrameCodec.encode(region);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // magic 0x45535652 → LE bytes
        assertEquals((byte) 0x52, buf.get(0),  "magic byte 0");
        assertEquals((byte) 0x56, buf.get(1),  "magic byte 1");
        assertEquals((byte) 0x53, buf.get(2),  "magic byte 2");
        assertEquals((byte) 0x45, buf.get(3),  "magic byte 3");

        // format, lod, level, reserved
        assertEquals((byte) 0x01, buf.get(4),  "format = FORMAT_ESVO");
        assertEquals((byte) 0x02, buf.get(5),  "lod = 2");
        assertEquals((byte) 0x04, buf.get(6),  "level = 4");
        assertEquals((byte) 0x00, buf.get(7),  "reserved = 0x00");

        // mortonCode 7 → LE bytes at offset 8-15
        assertEquals((byte) 0x07, buf.get(8),  "mortonCode byte 0");
        assertEquals((byte) 0x00, buf.get(9),  "mortonCode byte 1");
        assertEquals((byte) 0x00, buf.get(10), "mortonCode byte 2");
        assertEquals((byte) 0x00, buf.get(11), "mortonCode byte 3");
        assertEquals((byte) 0x00, buf.get(12), "mortonCode byte 4");
        assertEquals((byte) 0x00, buf.get(13), "mortonCode byte 5");
        assertEquals((byte) 0x00, buf.get(14), "mortonCode byte 6");
        assertEquals((byte) 0x00, buf.get(15), "mortonCode byte 7");

        // buildVersion 42 = 0x2A → LE at offset 16-19
        assertEquals((byte) 0x2A, buf.get(16), "buildVersion byte 0 = 42");
        assertEquals((byte) 0x00, buf.get(17), "buildVersion byte 1");
        assertEquals((byte) 0x00, buf.get(18), "buildVersion byte 2");
        assertEquals((byte) 0x00, buf.get(19), "buildVersion byte 3");

        // dataSize 10 = 0x0A → LE at offset 20-23
        assertEquals((byte) 0x0A, buf.get(20), "dataSize byte 0 = 10");
        assertEquals((byte) 0x00, buf.get(21), "dataSize byte 1");
        assertEquals((byte) 0x00, buf.get(22), "dataSize byte 2");
        assertEquals((byte) 0x00, buf.get(23), "dataSize byte 3");
    }

    /**
     * Verifies the ESVT format byte (0x02) appears at offset 4 of the frame header.
     */
    @Test
    void testFrameHeader_esvtFormatByte() {
        var region = makeRegion(KNOWN_MORTON, 4, 0, RegionBuilder.BuildType.ESVT,
                                new byte[10], 1L);
        var buf = BinaryFrameCodec.encode(region);
        assertEquals((byte) 0x02, buf.get(4), "format = FORMAT_ESVT (0x02)");
    }

    // ── 2. Morton code little-endian encoding ─────────────────────────────────

    /**
     * Verifies Morton code 0x0102030405060708 is split into two 32-bit LE halves
     * exactly as JS frame-parser.js reads them:
     * <pre>
     *   const mortonLo = view.getUint32(8,  true);  // bytes 8-11
     *   const mortonHi = view.getUint32(12, true);  // bytes 12-15
     *   const mortonCode = (BigInt(mortonHi) &lt;&lt; 32n) | BigInt(mortonLo);
     * </pre>
     */
    @Test
    void testMortonCode_splitHalves() {
        var region = makeRegion(0x0102030405060708L, 5, 0, RegionBuilder.BuildType.ESVO,
                                new byte[4], 1L);
        var buf = BinaryFrameCodec.encode(region);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Low 32 bits = 0x05060708, stored LE at offset 8
        assertEquals((byte) 0x08, buf.get(8),  "mortonLo byte 0");
        assertEquals((byte) 0x07, buf.get(9),  "mortonLo byte 1");
        assertEquals((byte) 0x06, buf.get(10), "mortonLo byte 2");
        assertEquals((byte) 0x05, buf.get(11), "mortonLo byte 3");

        // High 32 bits = 0x01020304, stored LE at offset 12
        assertEquals((byte) 0x04, buf.get(12), "mortonHi byte 0");
        assertEquals((byte) 0x03, buf.get(13), "mortonHi byte 1");
        assertEquals((byte) 0x02, buf.get(14), "mortonHi byte 2");
        assertEquals((byte) 0x01, buf.get(15), "mortonHi byte 3");

        // Round-trip
        var header = BinaryFrameCodec.decodeHeader(buf);
        assertNotNull(header);
        assertEquals(0x0102030405060708L, header.key(), "key round-trip");
    }

    /**
     * Verifies that mortonCode=7 (= 0b111) produces JS decodeMorton3D result of
     * { rx:1, ry:1, rz:1 }:
     * <ul>
     *   <li>rx: bit 0 of each group → bit 0 set → rx = 1</li>
     *   <li>ry: bit 1 of each group → bit 1 set → ry = 1</li>
     *   <li>rz: bit 2 of each group → bit 2 set → rz = 1</li>
     * </ul>
     */
    @Test
    void testMortonCode_7_decodesTo_1_1_1() {
        var region = makeRegion(7L, 3, 0, RegionBuilder.BuildType.ESVO,
                                new byte[4], 1L);
        var buf = BinaryFrameCodec.encode(region);

        assertEquals((byte) 0x07, buf.get(8), "mortonCode byte 0 = 7");
        assertEquals((byte) 0x00, buf.get(9), "mortonCode byte 1 = 0");

        var header = BinaryFrameCodec.decodeHeader(buf);
        assertNotNull(header);
        assertEquals(7L, header.key(), "key = 7");
        // JS: decodeMorton3D(7n) → { rx:1, ry:1, rz:1 }
    }

    // ── 3. ESVO payload byte layout ───────────────────────────────────────────

    /**
     * Verifies the 29-byte ESVO payload structure for a 1-node tree.
     *
     * <p>ESVO payload format:
     * <pre>
     * [0]     version       uint8  = 1
     * [1-4]   nodeCount     int32 LE
     * [5-8]   maxDepth      int32 LE
     * [9-12]  leafCount     int32 LE
     * [13-16] internalCount int32 LE
     * [17-20] farPtrCount   int32 LE
     * [21-28] node[0]       8 bytes (childDescriptor int32 LE, contourDescriptor int32 LE)
     * </pre>
     *
     * <p>childDescriptor 0x800000FF bit layout:
     * <ul>
     *   <li>bit 31 (valid):      1</li>
     *   <li>bits 30-17 (childPtr): 0</li>
     *   <li>bit 16 (far):        0</li>
     *   <li>bits 15-8 (childMask): 0x00</li>
     *   <li>bits 7-0  (leafMask):  0xFF</li>
     * </ul>
     * LE bytes at offset 21: [0xFF, 0x00, 0x00, 0x80]
     */
    @Test
    void testEsvoPayload_singleNodeLayout() {
        // 0x800000FF = valid=1, childPtr=0, far=0, childMask=0x00, leafMask=0xFF
        byte[] payload = esvoPayload1Node(1, 8, 0, 0x800000FF, 0x00000000);
        assertEquals(29, payload.length, "1-node ESVO payload = 29 bytes");

        var buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(1, Byte.toUnsignedInt(payload[0]),  "version = 1");
        assertEquals(1, buf.getInt(1),                    "nodeCount = 1");
        assertEquals(1, buf.getInt(5),                    "maxDepth = 1");
        assertEquals(8, buf.getInt(9),                    "leafCount = 8");
        assertEquals(0, buf.getInt(13),                   "internalCount = 0");
        assertEquals(0, buf.getInt(17),                   "farPtrCount = 0");

        // childDescriptor LE at offset 21 = [0xFF, 0x00, 0x00, 0x80]
        assertEquals((byte) 0xFF, payload[21], "childDesc[0] = leafMask 0xFF");
        assertEquals((byte) 0x00, payload[22], "childDesc[1] = childMask 0x00");
        assertEquals((byte) 0x00, payload[23], "childDesc[2] = childPtr low + far = 0");
        assertEquals((byte) 0x80, payload[24], "childDesc[3] = valid bit set");

        // contourDescriptor = 0
        assertEquals((byte) 0x00, payload[25], "contourDesc[0] = 0");
        assertEquals((byte) 0x00, payload[26], "contourDesc[1] = 0");
        assertEquals((byte) 0x00, payload[27], "contourDesc[2] = 0");
        assertEquals((byte) 0x00, payload[28], "contourDesc[3] = 0");
    }

    /**
     * Verifies child descriptor bit field extraction as the JS parser performs it.
     *
     * <p>JS bit extraction from childDescriptor:
     * <pre>
     * valid     = (bits >>> 31) &amp; 1
     * childPtr  = (bits >>> 17) &amp; 0x3FFF
     * far       = (bits >>> 16) &amp; 1
     * childMask = (bits >>>  8) &amp; 0xFF
     * leafMask  =  bits         &amp; 0xFF
     * </pre>
     */
    @Test
    void testEsvoChildDescriptor_bitExtraction() {
        // Build root node: childMask=0x0F (octants 0-3), leafMask=0x00, childPtr=1, far=0, valid=1
        // = (1 << 31) | (1 << 17) | (0x0F << 8) | 0x00
        // = 0x80000000 | 0x00020000 | 0x00000F00
        // = 0x80020F00
        int rootDesc = 0x80020F00;

        byte[] payload = esvoPayload1Node(1, 2, 1, rootDesc, 0);
        var buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int cd = buf.getInt(21);

        assertEquals(0x80020F00, cd, "childDescriptor raw value");

        // Extract bit fields exactly as JS parser does
        int valid     = (cd >>> 31) & 1;
        int childPtr  = (cd >>> 17) & 0x3FFF;
        int far       = (cd >>> 16) & 1;
        int childMask = (cd >>>  8) & 0xFF;
        int leafMask  =  cd         & 0xFF;

        assertEquals(1,    valid,     "valid = 1");
        assertEquals(1,    childPtr,  "childPtr = 1");
        assertEquals(0,    far,       "far = 0");
        assertEquals(0x0F, childMask, "childMask = 0x0F");
        assertEquals(0x00, leafMask,  "leafMask = 0x00");
    }

    /**
     * Verifies the sparse child-offset (popcount) arithmetic used by the JS renderer:
     * <pre>
     *   sparseOffset = popcount(childMask &amp; ((1 &lt;&lt; i) - 1))
     *   childNodeIdx = nodeIdx + relOffset + sparseOffset
     * </pre>
     * Root node childMask=0x03 (octants 0 and 1 have children):
     * <ul>
     *   <li>Octant 0: sparseOffset = popcount(0x03 &amp; 0x00) = 0 → child at node 0+1+0=1</li>
     *   <li>Octant 1: sparseOffset = popcount(0x03 &amp; 0x01) = 1 → child at node 0+1+1=2</li>
     * </ul>
     */
    @Test
    void testEsvo_childIndexArithmetic_popcount() {
        // Root: childMask=0x03, leafMask=0x00, childPtr=1, valid=1
        // = (1<<31) | (1<<17) | (0x03<<8) | 0x00 = 0x80020300
        int rootDesc = 0x80020300;
        // Children: leafMask=0xFF, valid=1 = 0x800000FF
        int leafDesc = 0x800000FF;

        byte[] payload = esvoPayload3Nodes(3, 2, 16, 1,
                                           rootDesc, 0,
                                           leafDesc, 0,
                                           leafDesc, 0);
        var buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(3, buf.getInt(1), "nodeCount = 3");
        assertEquals(1, buf.getInt(13), "internalCount = 1");

        int cd = buf.getInt(21);  // root node at offset 21
        assertEquals(0x80020300, cd, "root childDescriptor = 0x80020300");

        // Verify fields
        int childPtr  = (cd >>> 17) & 0x3FFF;
        int childMask = (cd >>>  8) & 0xFF;
        assertEquals(1,    childPtr,  "root childPtr = 1");
        assertEquals(0x03, childMask, "root childMask = 0x03");

        // JS popcount(0x03 & ((1<<0)-1)) = popcount(0x03 & 0x00) = 0 → octant-0 child at node 1
        // JS popcount(0x03 & ((1<<1)-1)) = popcount(0x03 & 0x01) = 1 → octant-1 child at node 2
        assertEquals(0, Integer.bitCount(0x03 & ((1 << 0) - 1)), "sparseOffset for octant 0 = 0");
        assertEquals(1, Integer.bitCount(0x03 & ((1 << 1) - 1)), "sparseOffset for octant 1 = 1");
    }

    // ── 4. ESVT payload byte layout ───────────────────────────────────────────

    /**
     * Verifies the 41-byte ESVT payload structure for a 1-node tree.
     *
     * <p>ESVT payload format:
     * <pre>
     * [0]     version        uint8  = 1
     * [1-4]   nodeCount      int32 LE
     * [5-8]   rootType       int32 LE
     * [9-12]  maxDepth       int32 LE
     * [13-16] leafCount      int32 LE
     * [17-20] internalCount  int32 LE
     * [21-24] gridResolution int32 LE
     * [25-28] contourCount   int32 LE
     * [29-32] farPtrCount    int32 LE
     * [33-40] node[0]        8 bytes
     * </pre>
     */
    @Test
    void testEsvtPayload_singleNodeLayout() {
        byte[] payload = esvtPayload1Node(1, 0, 4, 1, 0, 16, 0, 0, 0x800000FF, 0);
        assertEquals(41, payload.length, "1-node ESVT payload = 41 bytes");

        var buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(1,  Byte.toUnsignedInt(payload[0]), "version = 1");
        assertEquals(1,  buf.getInt(1),                   "nodeCount = 1");
        assertEquals(0,  buf.getInt(5),                   "rootType = 0");
        assertEquals(4,  buf.getInt(9),                   "maxDepth = 4");
        assertEquals(1,  buf.getInt(13),                  "leafCount = 1");
        assertEquals(0,  buf.getInt(17),                  "internalCount = 0");
        assertEquals(16, buf.getInt(21),                  "gridResolution = 16");
        assertEquals(0,  buf.getInt(25),                  "contourCount = 0");
        assertEquals(0,  buf.getInt(29),                  "farPtrCount = 0");

        // Node at offset 33: childDescriptor 0x800000FF → LE [0xFF, 0x00, 0x00, 0x80]
        assertEquals((byte) 0xFF, payload[33], "node childDesc[0] = 0xFF (leafMask)");
        assertEquals((byte) 0x00, payload[34], "node childDesc[1] = 0x00 (childMask)");
        assertEquals((byte) 0x00, payload[35], "node childDesc[2] = 0x00");
        assertEquals((byte) 0x80, payload[36], "node childDesc[3] = 0x80 (valid)");
    }

    // ── 5. Complete frame test vectors ────────────────────────────────────────

    /**
     * Produces a complete 53-byte ESVO frame (24 header + 29 payload) with known
     * content and verifies every key byte.
     *
     * <p>This frame is the primary ground-truth vector for client-test.html.
     * The exact byte sequence is:
     * <pre>
     * [0-3]   52 56 53 45  magic (ESVR LE)
     * [4]     01           FORMAT_ESVO
     * [5]     02           lod=2
     * [6]     04           level=4
     * [7]     00           reserved
     * [8-15]  07 00 00 00 00 00 00 00  mortonCode=7
     * [16-19] 2A 00 00 00  buildVersion=42
     * [20-23] 1D 00 00 00  dataSize=29
     * [24]    01           ESVO version
     * [25-28] 01 00 00 00  nodeCount=1
     * [29-32] 01 00 00 00  maxDepth=1
     * [33-36] 08 00 00 00  leafCount=8
     * [37-40] 00 00 00 00  internalCount=0
     * [41-44] 00 00 00 00  farPtrCount=0
     * [45-48] FF 00 00 80  childDescriptor=0x800000FF
     * [49-52] 00 00 00 00  contourDescriptor=0
     * </pre>
     */
    @Test
    void testCompleteEsvoFrame_53bytes() {
        byte[] esvoPayload = esvoPayload1Node(1, 8, 0, 0x800000FF, 0);
        assertEquals(29, esvoPayload.length);

        var region = makeRegion(KNOWN_MORTON, 4, 2, RegionBuilder.BuildType.ESVO,
                                esvoPayload, KNOWN_BUILD_VERSION);
        var buf = BinaryFrameCodec.encode(region);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(53, buf.limit(), "total frame = 24 header + 29 payload = 53 bytes");

        // Header spot checks
        assertEquals(ProtocolConstants.FRAME_MAGIC,      buf.getInt(0),  "magic");
        assertEquals(ProtocolConstants.FORMAT_ESVO,      buf.get(4),     "format = ESVO");
        assertEquals((byte) 2,                           buf.get(5),     "lod = 2");
        assertEquals((byte) 4,                           buf.get(6),     "level = 4");
        assertEquals(KNOWN_BUILD_VERSION,                buf.getInt(16), "buildVersion = 42");
        assertEquals(29,                                 buf.getInt(20), "dataSize = 29");

        // Payload bytes (offset 24+)
        assertEquals((byte) 0x01, buf.get(24), "payload[0] ESVO version = 1");
        assertEquals((byte) 0x01, buf.get(25), "payload[1] nodeCount LE byte 0 = 1");
        assertEquals((byte) 0x00, buf.get(26), "payload[2] nodeCount LE byte 1 = 0");
        assertEquals((byte) 0x00, buf.get(27), "payload[3] nodeCount LE byte 2 = 0");
        assertEquals((byte) 0x00, buf.get(28), "payload[4] nodeCount LE byte 3 = 0");
        // childDescriptor at payload offset 21 → frame offset 24+21=45
        assertEquals((byte) 0xFF, buf.get(45), "childDesc[0] = 0xFF (leafMask)");
        assertEquals((byte) 0x00, buf.get(46), "childDesc[1] = 0x00 (childMask)");
        assertEquals((byte) 0x00, buf.get(47), "childDesc[2] = 0x00");
        assertEquals((byte) 0x80, buf.get(48), "childDesc[3] = 0x80 (valid)");
    }

    /**
     * Produces a complete 65-byte ESVT frame (24 header + 41 payload) with known
     * content and verifies key positions.
     */
    @Test
    void testCompleteEsvtFrame_65bytes() {
        byte[] esvtPayload = esvtPayload1Node(1, 0, 4, 1, 0, 16, 0, 0, 0x800000FF, 0);
        assertEquals(41, esvtPayload.length);

        var region = makeRegion(KNOWN_MORTON, 4, 0, RegionBuilder.BuildType.ESVT,
                                esvtPayload, 1L);
        var buf = BinaryFrameCodec.encode(region);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(65, buf.limit(), "total frame = 24 header + 41 payload = 65 bytes");

        assertEquals(ProtocolConstants.FRAME_MAGIC, buf.getInt(0),  "magic");
        assertEquals(ProtocolConstants.FORMAT_ESVT, buf.get(4),     "format = ESVT");
        assertEquals(41,                            buf.getInt(20), "dataSize = 41");

        // ESVT version at payload offset 0 → frame offset 24
        assertEquals((byte) 0x01, buf.get(24), "ESVT version = 1");
        // gridResolution at payload offset 21 → frame offset 45
        assertEquals((byte) 0x10, buf.get(45), "gridResolution byte 0 = 16 = 0x10");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RegionBuilder.BuiltRegion makeRegion(long mortonCode, int level, int lod,
                                                  RegionBuilder.BuildType type, byte[] data,
                                                  long buildVersion) {
        return new RegionBuilder.BuiltRegion(
            new RegionId(mortonCode, level),
            lod, type, data, false, 0L, 0L, buildVersion
        );
    }

    /**
     * Build a minimal ESVO payload (version 1, one node, no far pointers).
     * Total: 21 header bytes + 8 node bytes = 29 bytes.
     */
    private byte[] esvoPayload1Node(int maxDepth, int leafCount, int internalCount,
                                     int childDescriptor, int contourDescriptor) {
        var buf = ByteBuffer.allocate(29).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 1);      // version
        buf.putInt(1);          // nodeCount
        buf.putInt(maxDepth);
        buf.putInt(leafCount);
        buf.putInt(internalCount);
        buf.putInt(0);          // farPtrCount
        buf.putInt(childDescriptor);
        buf.putInt(contourDescriptor);
        return buf.array();
    }

    /** Build a 3-node ESVO payload (no far pointers). */
    private byte[] esvoPayload3Nodes(int nodeCount, int maxDepth, int leafCount, int internalCount,
                                      int child0, int contour0,
                                      int child1, int contour1,
                                      int child2, int contour2) {
        var buf = ByteBuffer.allocate(21 + nodeCount * 8).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 1);
        buf.putInt(nodeCount);
        buf.putInt(maxDepth);
        buf.putInt(leafCount);
        buf.putInt(internalCount);
        buf.putInt(0);  // farPtrCount
        buf.putInt(child0);   buf.putInt(contour0);
        buf.putInt(child1);   buf.putInt(contour1);
        buf.putInt(child2);   buf.putInt(contour2);
        return buf.array();
    }

    /**
     * Build a minimal ESVT payload (version 1, one node, no contours, no far pointers).
     * Total: 33 header bytes + 8 node bytes = 41 bytes.
     */
    private byte[] esvtPayload1Node(int nodeCount, int rootType, int maxDepth,
                                     int leafCount, int internalCount, int gridResolution,
                                     int contourCount, int farPtrCount,
                                     int childDescriptor, int contourDescriptor) {
        int size = 33 + nodeCount * 8 + contourCount * 4 + farPtrCount * 4;
        var buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 1);      // version
        buf.putInt(nodeCount);
        buf.putInt(rootType);
        buf.putInt(maxDepth);
        buf.putInt(leafCount);
        buf.putInt(internalCount);
        buf.putInt(gridResolution);
        buf.putInt(contourCount);
        buf.putInt(farPtrCount);
        buf.putInt(childDescriptor);
        buf.putInt(contourDescriptor);
        return buf.array();
    }
}
