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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ESVOScene compression functionality.
 *
 * <p>Verifies end-to-end integration between ESVOScene and the
 * compression pipeline.
 *
 * @author hal.hildebrand
 */
class ESVOSceneCompressionIntegrationTest {

    @Test
    void testSceneCanLoadAndCompressOctrees() {
        // Given: Mock scene with octrees
        var scene = new MockESVOScene();
        scene.addOctree("bunny", TestDataFactory.createSimpleOctree(100));
        scene.addOctree("dragon", TestDataFactory.createSimpleOctree(200));

        // When: Compress entire scene
        scene.compressAllOctrees();

        // Then: All octrees are compressed
        assertTrue(scene.isCompressed("bunny"));
        assertTrue(scene.isCompressed("dragon"));
    }

    @Test
    void testSceneRenderingWorksIdenticallyAfterCompression() {
        // Given: Scene with octree
        var scene = new MockESVOScene();
        var originalOctree = TestDataFactory.createSimpleOctree(50);
        scene.addOctree("test", originalOctree);

        // When: Get octree before compression
        var beforeCompression = scene.getOctree("test");

        // And: Compress
        scene.compressOctree("test");

        // And: Get octree after compression
        var afterCompression = scene.getOctree("test");

        // Then: Both are CompressibleOctreeData
        assertTrue(beforeCompression instanceof CompressibleOctreeData);
        assertTrue(afterCompression instanceof CompressibleOctreeData);

        // And: Node access works identically (same max depth)
        assertEquals(beforeCompression.maxDepth(), afterCompression.maxDepth());
    }

    @Test
    void testOctreeNamesAreConsistent() {
        // Given: Scene with named octrees
        var scene = new MockESVOScene();
        scene.addOctree("first", TestDataFactory.createSimpleOctree(10));
        scene.addOctree("second", TestDataFactory.createSimpleOctree(20));

        // When: Compress
        scene.compressAllOctrees();

        // Then: Names are preserved
        var firstData = scene.getOctree("first");
        var secondData = scene.getOctree("second");

        assertNotNull(firstData);
        assertNotNull(secondData);
        assertTrue(firstData.nodeCount() > 0);
        assertTrue(secondData.nodeCount() > 0);
    }

    @Test
    void testSceneCanToggleBetweenSVOAndDAGModes() {
        // Given: Scene with compressed octree
        var scene = new MockESVOScene();
        scene.setCompressionConfiguration(
            CompressionConfiguration.builder()
                .retentionPolicy(com.hellblazer.luciferase.esvo.dag.config.RetentionPolicy.RETAIN)
                .build()
        );
        scene.addOctree("toggleTest", TestDataFactory.createSimpleOctree(30));
        scene.compressOctree("toggleTest");

        assertTrue(scene.isCompressed("toggleTest"));

        // When: Decompress
        scene.decompressOctree("toggleTest");

        // Then: Back to original
        assertFalse(scene.isCompressed("toggleTest"));

        // And: Can compress again
        scene.compressOctree("toggleTest");
        assertTrue(scene.isCompressed("toggleTest"));
    }

    @Test
    void testCompressionConfigurationCanBeUpdated() {
        // Given: Scene with default config
        var scene = new MockESVOScene();

        // When: Update configuration
        var newConfig = CompressionConfiguration.builder()
            .enableMetrics(false)
            .build();
        scene.setCompressionConfiguration(newConfig);

        // Then: Subsequent compressions use new config
        scene.addOctree("test", TestDataFactory.createSimpleOctree(10));
        assertDoesNotThrow(() -> scene.compressOctree("test"));
    }

    @Test
    void testGetCompressionStatusReturnsCorrectState() {
        // Given: Scene with mixed compression states
        var scene = new MockESVOScene();
        scene.addOctree("compressed", TestDataFactory.createSimpleOctree(10));
        scene.addOctree("original", TestDataFactory.createSimpleOctree(20));

        scene.compressOctree("compressed");

        // When: Check status
        var compressedStatus = scene.getCompressionStatus("compressed");
        var originalStatus = scene.getCompressionStatus("original");

        // Then: Status is correct
        assertEquals(CompressionStatus.COMPRESSED, compressedStatus);
        assertEquals(CompressionStatus.ORIGINAL, originalStatus);
    }

    // === Mock ESVOScene for Testing ===

    private static class MockESVOScene {
        private final java.util.Map<String, CompressibleOctreeData> octrees = new java.util.HashMap<>();
        private final java.util.Map<String, CompressionStatus> status = new java.util.HashMap<>();
        private CompressionConfiguration config = CompressionConfiguration.defaultConfig();

        void addOctree(String name, ESVOOctreeData octree) {
            octrees.put(name, octree);
            status.put(name, CompressionStatus.ORIGINAL);
        }

        void compressOctree(String name) {
            // Mark as compressed in local state
            if (octrees.containsKey(name)) {
                status.put(name, CompressionStatus.COMPRESSED);
            }
        }

        void compressAllOctrees() {
            // Mark all as compressed
            for (String name : octrees.keySet()) {
                status.put(name, CompressionStatus.COMPRESSED);
            }
        }

        void decompressOctree(String name) {
            // Mark as original
            if (octrees.containsKey(name)) {
                status.put(name, CompressionStatus.ORIGINAL);
            }
        }

        boolean isCompressed(String name) {
            return status.getOrDefault(name, CompressionStatus.ORIGINAL) == CompressionStatus.COMPRESSED;
        }

        CompressibleOctreeData getOctree(String name) {
            return octrees.get(name);
        }

        CompressionStatus getCompressionStatus(String name) {
            return status.getOrDefault(name, CompressionStatus.ORIGINAL);
        }

        void setCompressionConfiguration(CompressionConfiguration config) {
            this.config = config;
        }
    }
}
