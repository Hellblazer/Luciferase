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

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.config.CompressionConfiguration;
import com.hellblazer.luciferase.esvo.dag.config.RetentionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVOCompressionCoordinator.
 *
 * <p>Verifies scene-level compression coordination, state management,
 * and retention policy enforcement.
 *
 * @author hal.hildebrand
 */
class ESVOCompressionCoordinatorTest {

    private ESVOCompressionCoordinator coordinator;
    private Map<String, ESVOOctreeData> mockScene;

    @BeforeEach
    void setUp() {
        coordinator = new ESVOCompressionCoordinator(CompressionConfiguration.defaultConfig());

        // Create mock scene with 3 octrees
        mockScene = new HashMap<>();
        mockScene.put("octree1", TestDataFactory.createSimpleOctree(8));
        mockScene.put("octree2", TestDataFactory.createSimpleOctree(16));
        mockScene.put("octree3", TestDataFactory.createSimpleOctree(32));
    }

    @Test
    void testCompressSceneCompressesAllOctrees() {
        // Given: Scene with 3 octrees
        // When: Compress entire scene
        coordinator.compressScene(mockScene);

        // Then: All octrees are compressed
        assertEquals(CompressionStatus.COMPRESSED, coordinator.getStatus("octree1"));
        assertEquals(CompressionStatus.COMPRESSED, coordinator.getStatus("octree2"));
        assertEquals(CompressionStatus.COMPRESSED, coordinator.getStatus("octree3"));
    }

    @Test
    void testCompressSpecificOctree() {
        // Given: Scene with 3 octrees
        // When: Compress only octree2
        coordinator.compressOctree(mockScene, "octree2");

        // Then: Only octree2 is compressed
        assertEquals(CompressionStatus.ORIGINAL, coordinator.getStatus("octree1"));
        assertEquals(CompressionStatus.COMPRESSED, coordinator.getStatus("octree2"));
        assertEquals(CompressionStatus.ORIGINAL, coordinator.getStatus("octree3"));
    }

    @Test
    void testGetOctreeDataReturnsCompressedAfterCompression() {
        // Given: Compressed octree
        coordinator.compressOctree(mockScene, "octree1");

        // When: Get octree data
        var data = coordinator.getOctreeData("octree1");

        // Then: Returns CompressibleOctreeData
        assertNotNull(data);
        assertTrue(data instanceof CompressibleOctreeData);
    }

    @Test
    void testGetOctreeDataReturnsOriginalBeforeCompression() {
        // Given: Uncompressed octree
        coordinator.registerOctree("octree1", mockScene.get("octree1"));

        // When: Get octree data
        var data = coordinator.getOctreeData("octree1");

        // Then: Returns original data (wrapped as CompressibleOctreeData)
        assertNotNull(data);
    }

    @Test
    void testDecompressAllRestoresOriginalSVOs() {
        // Given: All octrees compressed
        coordinator.compressScene(mockScene);
        assertEquals(CompressionStatus.COMPRESSED, coordinator.getStatus("octree1"));

        // When: Decompress all
        coordinator.decompressAll();

        // Then: All octrees are back to original
        assertEquals(CompressionStatus.ORIGINAL, coordinator.getStatus("octree1"));
        assertEquals(CompressionStatus.ORIGINAL, coordinator.getStatus("octree2"));
        assertEquals(CompressionStatus.ORIGINAL, coordinator.getStatus("octree3"));
    }

    @Test
    void testRetentionPolicyDiscardFreesOriginalData() {
        // Given: Coordinator with DISCARD policy
        var config = CompressionConfiguration.builder()
            .retentionPolicy(RetentionPolicy.DISCARD)
            .build();
        coordinator = new ESVOCompressionCoordinator(config);

        // When: Compress octree
        coordinator.compressOctree(mockScene, "octree1");

        // Then: Original data is freed (cannot decompress)
        assertThrows(IllegalStateException.class,
                     () -> coordinator.decompressAll(),
                     "Cannot decompress with DISCARD policy");
    }

    @Test
    void testRetentionPolicyRetainKeepsBothCopies() {
        // Given: Coordinator with RETAIN policy
        var config = CompressionConfiguration.builder()
            .retentionPolicy(RetentionPolicy.RETAIN)
            .build();
        coordinator = new ESVOCompressionCoordinator(config);

        // When: Compress and decompress
        coordinator.compressOctree(mockScene, "octree1");
        coordinator.decompressAll();

        // Then: Can decompress successfully
        assertEquals(CompressionStatus.ORIGINAL, coordinator.getStatus("octree1"));
    }

    @Test
    void testGetStatusForUnknownOctreeReturnsPending() {
        // When: Get status for non-existent octree
        var status = coordinator.getStatus("unknown");

        // Then: Returns PENDING
        assertEquals(CompressionStatus.PENDING, status);
    }

    @Test
    void testSetCompressionConfigurationUpdatesConfig() {
        // Given: New configuration
        var newConfig = CompressionConfiguration.builder()
            .enableMetrics(false)
            .build();

        // When: Update configuration
        coordinator.setCompressionConfiguration(newConfig);

        // Then: Configuration is updated (verified by behavior)
        // Subsequent compressions use new config
        assertDoesNotThrow(() -> coordinator.compressOctree(mockScene, "octree1"));
    }

    @Test
    void testCompressNonExistentOctreeThrows() {
        // When/Then: Compress non-existent octree throws
        assertThrows(IllegalArgumentException.class,
                     () -> coordinator.compressOctree(mockScene, "nonexistent"));
    }

    @Test
    void testMultipleCompressionsAreIdempotent() {
        // Given: Compressed octree
        coordinator.compressOctree(mockScene, "octree1");
        var firstStatus = coordinator.getStatus("octree1");

        // When: Compress again
        coordinator.compressOctree(mockScene, "octree1");
        var secondStatus = coordinator.getStatus("octree1");

        // Then: Status unchanged
        assertEquals(firstStatus, secondStatus);
        assertEquals(CompressionStatus.COMPRESSED, secondStatus);
    }

    @Test
    void testClearRemovesAllState() {
        // Given: Compressed octrees
        coordinator.compressScene(mockScene);

        // When: Clear coordinator
        coordinator.clear();

        // Then: All statuses are PENDING
        assertEquals(CompressionStatus.PENDING, coordinator.getStatus("octree1"));
        assertEquals(CompressionStatus.PENDING, coordinator.getStatus("octree2"));
        assertEquals(CompressionStatus.PENDING, coordinator.getStatus("octree3"));
    }

    @Test
    void testGetCompressionStatistics() {
        // Given: Mixed compression state
        coordinator.compressOctree(mockScene, "octree1");
        coordinator.compressOctree(mockScene, "octree2");
        // octree3 left uncompressed

        // When: Get statistics
        var stats = coordinator.getCompressionStatistics();

        // Then: Statistics are accurate
        assertEquals(2, stats.compressedCount());
        assertEquals(1, stats.originalCount());
        assertEquals(3, stats.totalCount());
    }
}
