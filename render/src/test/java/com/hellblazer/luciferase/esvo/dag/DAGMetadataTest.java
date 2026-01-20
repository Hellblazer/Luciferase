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
package com.hellblazer.luciferase.esvo.dag;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for DAGMetadata record.
 *
 * @author hal.hildebrand
 */
class DAGMetadataTest {

    @Test
    void testCompressionRatio() {
        var metadata = new DAGMetadata(100, 500, 10, 50, Map.of(), Duration.ofMillis(100),
                                       HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        assertEquals(5.0f, metadata.compressionRatio(), 0.01f);
    }

    @Test
    void testCompressionRatio_SameNodes() {
        var metadata = new DAGMetadata(100, 100, 10, 0, Map.of(), Duration.ofMillis(100),
                                       HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        assertEquals(1.0f, metadata.compressionRatio(), 0.01f);
    }

    @Test
    void testCompressionRatio_ZeroUnique() {
        var metadata = new DAGMetadata(0, 500, 10, 50, Map.of(), Duration.ofMillis(100),
                                       HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        assertEquals(1.0f, metadata.compressionRatio(), 0.01f);
    }

    @Test
    void testMemorySavedBytes() {
        var metadata = new DAGMetadata(100, 500, 10, 50, Map.of(), Duration.ofMillis(100),
                                       HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        assertEquals(3200L, metadata.memorySavedBytes()); // (500-100)*8
    }

    @Test
    void testMemorySavedBytes_NoCompression() {
        var metadata = new DAGMetadata(100, 100, 10, 0, Map.of(), Duration.ofMillis(100),
                                       HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        assertEquals(0L, metadata.memorySavedBytes());
    }

    @Test
    void testMemorySavedBytes_Negative() {
        // Edge case: unique > original (shouldn't happen but test robustness)
        var metadata = new DAGMetadata(500, 100, 10, 0, Map.of(), Duration.ofMillis(100),
                                       HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        assertEquals(-3200L, metadata.memorySavedBytes());
    }

    @Test
    void testCompressionPercent() {
        var metadata = new DAGMetadata(100, 500, 10, 50, Map.of(), Duration.ofMillis(100),
                                       HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        assertEquals(80.0f, metadata.compressionPercent(), 0.01f); // (1 - 100/500)*100
    }

    @Test
    void testCompressionPercent_NoCompression() {
        var metadata = new DAGMetadata(500, 500, 10, 0, Map.of(), Duration.ofMillis(100),
                                       HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        assertEquals(0.0f, metadata.compressionPercent(), 0.01f);
    }

    @Test
    void testCompressionPercent_ZeroOriginal() {
        var metadata = new DAGMetadata(0, 0, 10, 0, Map.of(), Duration.ofMillis(100),
                                       HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        assertEquals(0.0f, metadata.compressionPercent(), 0.01f);
    }

    @Test
    void testMetadataImmutability() {
        var metadata = new DAGMetadata(100, 500, 10, 50, Map.of(), Duration.ofMillis(100),
                                       HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        var sharingByDepth = Map.of(1, 10, 2, 20);
        var metadata2 = new DAGMetadata(100, 500, 10, 50, sharingByDepth, Duration.ofMillis(100),
                                        HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        // Verify records with different sharing maps are not equal
        assertNotEquals(metadata, metadata2);
    }

    @Test
    void testMetadataEquality() {
        var sharingByDepth = Map.of(1, 10, 2, 20);
        var metadata1 = new DAGMetadata(100, 500, 10, 50, sharingByDepth, Duration.ofMillis(100),
                                        HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 12345L);
        var metadata2 = new DAGMetadata(100, 500, 10, 50, sharingByDepth, Duration.ofMillis(100),
                                        HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 12345L);
        assertEquals(metadata1, metadata2);
        assertEquals(metadata1.hashCode(), metadata2.hashCode());
    }

    @Test
    void testSharingByDepth() {
        var sharingByDepth = Map.of(1, 10, 2, 20, 3, 5);
        var metadata = new DAGMetadata(100, 500, 10, 50, sharingByDepth, Duration.ofMillis(100),
                                       HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        assertEquals(sharingByDepth, metadata.sharingByDepth());
    }

    @Test
    void testAllFields() {
        var sharingByDepth = Map.of(1, 10);
        var buildTime = Duration.ofMillis(1234);
        var metadata = new DAGMetadata(150, 600, 12, 75, sharingByDepth, buildTime,
                                       HashAlgorithm.SHA256, CompressionStrategy.CONSERVATIVE, 98765L);

        assertEquals(150, metadata.uniqueNodeCount());
        assertEquals(600, metadata.originalNodeCount());
        assertEquals(12, metadata.maxDepth());
        assertEquals(75, metadata.sharedSubtreeCount());
        assertEquals(sharingByDepth, metadata.sharingByDepth());
        assertEquals(buildTime, metadata.buildTime());
        assertEquals(HashAlgorithm.SHA256, metadata.hashAlgorithm());
        assertEquals(CompressionStrategy.CONSERVATIVE, metadata.strategy());
        assertEquals(98765L, metadata.sourceHash());
    }

    @Test
    void testToString() {
        var metadata = new DAGMetadata(100, 500, 10, 50, Map.of(), Duration.ofMillis(100),
                                       HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        var str = metadata.toString();
        assertTrue(str.contains("100"));
        assertTrue(str.contains("500"));
    }
}
