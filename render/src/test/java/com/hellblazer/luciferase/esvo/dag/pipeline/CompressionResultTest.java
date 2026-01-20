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
package com.hellblazer.luciferase.esvo.dag.pipeline;

import com.hellblazer.luciferase.esvo.dag.CompressionStrategy;
import com.hellblazer.luciferase.sparse.core.CoordinateSpace;
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import com.hellblazer.luciferase.sparse.core.SparseVoxelData;
import com.hellblazer.luciferase.sparse.core.SparseVoxelNode;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CompressionResult record.
 *
 * <p>Verifies immutability, field access, and computed metrics
 * (compression ratio, memory saved) are correctly calculated.
 *
 * @author hal.hildebrand
 */
class CompressionResultTest {

    @Test
    void testRecordCreationAndFieldAccess() {
        // Given: Compression result components
        var original = createMockSparseVoxelData(1000);
        var compressed = createMockCompressibleData(200);
        var strategy = CompressionStrategy.BALANCED;
        var compressionRatio = 5.0f;
        var buildTimeMs = 150L;
        var memorySaved = 6400L;

        // When: Create CompressionResult
        var result = new CompressionResult(
            original,
            compressed,
            compressionRatio,
            buildTimeMs,
            memorySaved,
            strategy
        );

        // Then: All fields accessible
        assertSame(original, result.originalData());
        assertSame(compressed, result.compressedData());
        assertEquals(compressionRatio, result.compressionRatio());
        assertEquals(buildTimeMs, result.buildTimeMs());
        assertEquals(memorySaved, result.memorySavedBytes());
        assertEquals(strategy, result.strategy());
    }

    @Test
    void testCompressionRatioCalculation() {
        // Given: 1000 original nodes, 200 compressed nodes
        var original = createMockSparseVoxelData(1000);
        var compressed = createMockCompressibleData(200);

        // When: Create result with calculated ratio
        var compressionRatio = (float) original.getTotalNodeCount() / compressed.getTotalNodeCount();
        var result = new CompressionResult(
            original,
            compressed,
            compressionRatio,
            100L,
            6400L,
            CompressionStrategy.BALANCED
        );

        // Then: Compression ratio is 5.0x
        assertEquals(5.0f, result.compressionRatio(), 0.01f);
    }

    @Test
    void testMemorySavedCalculation() {
        // Given: Original 1000 nodes, compressed 200 nodes, 8 bytes per node
        var original = createMockSparseVoxelData(1000);
        var compressed = createMockCompressibleData(200);
        var bytesPerNode = 8L;

        // When: Calculate memory saved
        var memorySaved = (original.getTotalNodeCount() - compressed.getTotalNodeCount()) * bytesPerNode;
        var result = new CompressionResult(
            original,
            compressed,
            5.0f,
            100L,
            memorySaved,
            CompressionStrategy.BALANCED
        );

        // Then: Memory saved is (1000-200) * 8 = 6400 bytes
        assertEquals(6400L, result.memorySavedBytes());
    }

    @Test
    void testIsCompressed() {
        // Given: Compression result
        var result = new CompressionResult(
            createMockSparseVoxelData(1000),
            createMockCompressibleData(200),
            5.0f,
            100L,
            6400L,
            CompressionStrategy.BALANCED
        );

        // Then: isCompressed() returns true
        assertTrue(result.isCompressed());
    }

    @Test
    void testIsCompressedWithIdenticalNodeCount() {
        // Given: No actual compression occurred (same node count)
        var result = new CompressionResult(
            createMockSparseVoxelData(1000),
            createMockCompressibleData(1000),
            1.0f,
            100L,
            0L,
            CompressionStrategy.CONSERVATIVE
        );

        // Then: isCompressed() still returns true (compression was attempted)
        assertTrue(result.isCompressed());
    }

    @Test
    void testGetMetadata() {
        // Given: Compression result with known metrics
        var result = new CompressionResult(
            createMockSparseVoxelData(1000),
            createMockCompressibleData(200),
            5.0f,
            150L,
            6400L,
            CompressionStrategy.AGGRESSIVE
        );

        // When: Get metadata string
        var metadata = result.getMetadata();

        // Then: Contains key information
        assertTrue(metadata.contains("5.0x"));
        assertTrue(metadata.contains("1000"));
        assertTrue(metadata.contains("200"));
        assertTrue(metadata.contains("150ms"));
        assertTrue(metadata.contains("AGGRESSIVE"));
    }

    @Test
    void testGetCompressionDetails() {
        // Given: Compression result
        var result = new CompressionResult(
            createMockSparseVoxelData(1000),
            createMockCompressibleData(200),
            5.0f,
            150L,
            6400L,
            CompressionStrategy.BALANCED
        );

        // When: Get compression details string
        var details = result.getCompressionDetails();

        // Then: Contains detailed breakdown
        assertTrue(details.contains("Original nodes: 1000"));
        assertTrue(details.contains("Compressed nodes: 200"));
        assertTrue(details.contains("Ratio: 5.0x"));
        assertTrue(details.contains("Memory saved: 6400 bytes"));
        assertTrue(details.contains("Build time: 150ms"));
    }

    @Test
    void testImmutability() {
        // Given: Compression result
        var original = createMockSparseVoxelData(1000);
        var compressed = createMockCompressibleData(200);
        var result = new CompressionResult(
            original,
            compressed,
            5.0f,
            100L,
            6400L,
            CompressionStrategy.BALANCED
        );

        // Then: All fields are effectively final (record immutability)
        assertSame(original, result.originalData());
        assertSame(compressed, result.compressedData());

        // Record fields cannot be modified (enforced by compiler)
    }

    @Test
    void testEquality() {
        // Given: Two identical compression results
        var original = createMockSparseVoxelData(1000);
        var compressed = createMockCompressibleData(200);

        var result1 = new CompressionResult(original, compressed, 5.0f, 100L, 6400L, CompressionStrategy.BALANCED);
        var result2 = new CompressionResult(original, compressed, 5.0f, 100L, 6400L, CompressionStrategy.BALANCED);

        // Then: They are equal (record auto-generates equals)
        assertEquals(result1, result2);
        assertEquals(result1.hashCode(), result2.hashCode());
    }

    @Test
    void testToString() {
        // Given: Compression result
        var result = new CompressionResult(
            createMockSparseVoxelData(1000),
            createMockCompressibleData(200),
            5.0f,
            100L,
            6400L,
            CompressionStrategy.BALANCED
        );

        // When: Get string representation
        var str = result.toString();

        // Then: Contains field information (record auto-generates toString)
        assertNotNull(str);
        assertTrue(str.contains("CompressionResult"));
    }

    // === Helper Methods ===

    private SparseVoxelData<?> createMockSparseVoxelData(int nodeCount) {
        return new SparseVoxelData<>() {
            @Override
            public CoordinateSpace getCoordinateSpace() {
                return CoordinateSpace.UNIT_CUBE_CENTERED;
            }

            @Override
            public PointerAddressingMode getAddressingMode() {
                return PointerAddressingMode.RELATIVE;
            }

            @Override
            public SparseVoxelNode getNode(int index) {
                return null;
            }

            @Override
            public int getTotalNodeCount() {
                return nodeCount;
            }

            @Override
            public int resolveChildIndex(int parentIdx, SparseVoxelNode node, int octant) {
                return 0;
            }

            @Override
            public String getName() {
                return "original";
            }

            @Override
            public int getMaxDepth() {
                return 10;
            }

            @Override
            public ByteBuffer serialize() {
                return null;
            }
        };
    }

    private CompressibleOctreeData createMockCompressibleData(int nodeCount) {
        return new CompressibleOctreeData() {
            @Override
            public CoordinateSpace getCoordinateSpace() {
                return CoordinateSpace.UNIT_CUBE_CENTERED;
            }

            @Override
            public PointerAddressingMode getAddressingMode() {
                return PointerAddressingMode.ABSOLUTE;
            }

            @Override
            public SparseVoxelNode getNode(int index) {
                return null;
            }

            @Override
            public int getTotalNodeCount() {
                return nodeCount;
            }

            @Override
            public int resolveChildIndex(int parentIdx, SparseVoxelNode node, int octant) {
                return 0;
            }

            @Override
            public String getName() {
                return "compressed";
            }

            @Override
            public int getMaxDepth() {
                return 10;
            }

            @Override
            public ByteBuffer serialize() {
                return null;
            }
        };
    }
}
