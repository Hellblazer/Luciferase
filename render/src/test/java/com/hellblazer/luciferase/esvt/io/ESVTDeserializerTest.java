/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvt.io;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTDeserializer, focusing on short-read / premature-EOF detection.
 *
 * @author hal.hildebrand
 */
public class ESVTDeserializerTest {

    @TempDir
    Path tempDir;

    // ---- helpers -----------------------------------------------------------

    /**
     * Build a minimal V1 ESVT file in memory.
     * <p>
     * V1 header (32 bytes, little-endian):
     *   magic        int  (0x54565345)
     *   version      int  (1)
     *   nodeCount    int
     *   contourCount int
     *   farPtrCount  int
     *   rootType     int
     *   maxDepth     int
     *   reserved     int
     * <p>
     * Followed by nodeCount * 8 bytes of node data (V1 node size = 8).
     */
    private byte[] buildMinimalV1File(int nodeCount) {
        // V1 header = 32 bytes; each node = 8 bytes (ESVTFileFormat.NODE_SIZE_BYTES)
        int headerSize = ESVTFileFormat.HEADER_SIZE_V1;
        int nodeBytes  = nodeCount * ESVTFileFormat.NODE_SIZE_BYTES;
        var buf = ByteBuffer.allocate(headerSize + nodeBytes).order(ByteOrder.LITTLE_ENDIAN);

        buf.putInt(ESVTFileFormat.MAGIC_NUMBER);
        buf.putInt(ESVTFileFormat.VERSION_1);
        buf.putInt(nodeCount);   // nodeCount
        buf.putInt(0);           // contourCount
        buf.putInt(0);           // farPtrCount
        buf.putInt(0);           // rootType
        buf.putInt(1);           // maxDepth
        buf.putInt(0);           // reserved
        // node data: all zeros (valid zero-filled nodes for this test)
        for (int i = 0; i < nodeBytes; i++) {
            buf.put((byte) 0);
        }
        return buf.array();
    }

    // ---- tests -------------------------------------------------------------

    /**
     * A complete, well-formed file round-trips without error.
     * This exercises the readFully loop path on a normal (non-short) read.
     */
    @Test
    void testValidRoundTripStillWorks() throws IOException {
        var nodes = new ESVTNodeUnified[10];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = new ESVTNodeUnified((byte) (i % 6));
            nodes[i].setChildMask(i);
            nodes[i].setChildPtr(i * 8);
        }
        var data = new ESVTData(nodes, new int[0], new int[0], 3, 5, 7, 3, 0, new int[0]);
        var file = tempDir.resolve("roundtrip.esvt");

        try (var ser = new ESVTSerializer()) {
            ser.serialize(data, file);
        }

        try (var deser = new ESVTDeserializer()) {
            var loaded = deser.deserialize(file);
            assertEquals(data.nodeCount(), loaded.nodeCount());
            assertEquals(data.rootType(), loaded.rootType());
            assertEquals(data.maxDepth(), loaded.maxDepth());
        }
    }

    /**
     * A file that contains a valid header claiming N nodes, but the file is
     * truncated mid-node-section must throw IOException (not silently return
     * zero-filled nodes).
     */
    @Test
    void testTruncatedNodeSectionThrowsIOException() throws IOException {
        int nodeCount = 5; // 5 nodes = 40 bytes of node data after a 32-byte header
        byte[] full = buildMinimalV1File(nodeCount);

        // Truncate: keep header (32 bytes) + only first 4 bytes of node section
        // = 36 bytes total; the header still says nodeCount=5 so readNodes must fail
        byte[] truncated = new byte[ESVTFileFormat.HEADER_SIZE_V1 + 4];
        System.arraycopy(full, 0, truncated, 0, truncated.length);

        var file = tempDir.resolve("truncated_nodes.esvt");
        Files.write(file, truncated);

        try (var deser = new ESVTDeserializer()) {
            assertThrows(IOException.class, () -> deser.deserialize(file),
                "Expected IOException on truncated node section, got silent success");
        }
    }

    /**
     * A file truncated inside the header itself (after magic+version but before
     * the full V1 header is available) must throw IOException.
     */
    @Test
    void testTruncatedHeaderThrowsIOException() throws IOException {
        // Write 8 bytes: valid magic + version 1, but no nodeCount etc.
        var buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(ESVTFileFormat.MAGIC_NUMBER);
        buf.putInt(ESVTFileFormat.VERSION_1);

        var file = tempDir.resolve("truncated_header.esvt");
        Files.write(file, buf.array());

        try (var deser = new ESVTDeserializer()) {
            assertThrows(IOException.class, () -> deser.deserialize(file),
                "Expected IOException on truncated header, got silent success");
        }
    }

    /**
     * An entirely empty file must throw IOException (EOF hit immediately
     * while reading the first 8-byte mini-header).
     */
    @Test
    void testEmptyFileThrowsIOException() throws IOException {
        var file = tempDir.resolve("empty.esvt");
        Files.write(file, new byte[0]);

        try (var deser = new ESVTDeserializer()) {
            assertThrows(IOException.class, () -> deser.deserialize(file),
                "Expected IOException on empty file, got silent success");
        }
    }
}
