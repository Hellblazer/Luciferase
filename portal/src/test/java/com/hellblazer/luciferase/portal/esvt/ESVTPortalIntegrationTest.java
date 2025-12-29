/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.portal.esvt;

import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.portal.esvt.bridge.ESVTBridge;
import com.hellblazer.luciferase.portal.esvt.renderer.ESVTNodeMeshRenderer;
import com.hellblazer.luciferase.portal.esvt.renderer.ESVTRenderer;
import com.hellblazer.luciferase.portal.esvo.ProceduralVoxelGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ESVT portal visualization components.
 */
class ESVTPortalIntegrationTest {

    @Test
    void testBuildFromVoxels() {
        // Generate test voxels
        var generator = new ProceduralVoxelGenerator();
        List<Point3i> voxels = generator.generate(ProceduralVoxelGenerator.Shape.SPHERE, 32);

        assertFalse(voxels.isEmpty(), "Should generate voxels");

        // Build ESVT via bridge
        var bridge = new ESVTBridge();
        bridge.buildFromVoxels(voxels, 5);

        assertTrue(bridge.hasData(), "Should have built ESVT data");

        var data = bridge.getData();
        assertNotNull(data, "Data should not be null");
        assertTrue(data.nodeCount() > 0, "Should have nodes");
        assertTrue(data.leafCount() > 0, "Should have leaves");
        assertEquals(5, data.maxDepth(), "Max depth should match");

        System.out.printf("Built ESVT: %d nodes, %d leaves, depth %d, build time %.2fms%n",
            data.nodeCount(), data.leafCount(), data.maxDepth(), bridge.getLastBuildTimeMs());
    }

    @Test
    void testRayCasting() {
        // Build solid test cube - use full voxel grid (0 to resolution-1)
        int resolution = 16;
        List<Point3i> voxels = new java.util.ArrayList<>();
        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }
        System.out.printf("Generated %d solid cube voxels%n", voxels.size());

        var bridge = new ESVTBridge();
        bridge.buildFromVoxels(voxels, 4);

        assertTrue(bridge.hasData(), "Should have data");

        var data = bridge.getData();
        var root = data.root();

        // Debug tree structure
        System.out.printf("Tree: %d nodes, %d leaves, rootType=%d, maxDepth=%d%n",
            data.nodeCount(), data.leafCount(), data.rootType(), data.maxDepth());
        System.out.printf("Root: valid=%b, childMask=0x%02X, leafMask=0x%02X, childPtr=%d%n",
            root.isValid(), root.getChildMask(), root.getLeafMask(), root.getChildPtr());

        // Check children at first level
        for (int i = 0; i < 8; i++) {
            if (root.hasChild(i)) {
                int childIdx = root.getChildIndex(i, 0); // root at index 0
                var childNode = data.nodes()[childIdx];
                System.out.printf("  Child %d: idx=%d, valid=%b, childMask=0x%02X, leafMask=0x%02X%n",
                    i, childIdx, childNode.isValid(), childNode.getChildMask(), childNode.getLeafMask());
            }
        }

        // Test multiple rays towards S0 centroid from various directions
        // ESVT only covers S0 tetrahedron (~1/6 of cube), so not all rays will hit
        float cx = 0.75f, cy = 0.25f, cz = 0.5f;  // S0 centroid

        int hits = 0;
        int total = 100;
        var random = new java.util.Random(42);

        for (int i = 0; i < total; i++) {
            // Random position on sphere around centroid
            float theta = random.nextFloat() * 2 * (float)Math.PI;
            float phi = random.nextFloat() * (float)Math.PI;
            float dist = 1.5f + random.nextFloat();

            float ox = cx + dist * (float)(Math.sin(phi) * Math.cos(theta));
            float oy = cy + dist * (float)(Math.sin(phi) * Math.sin(theta));
            float oz = cz + dist * (float)Math.cos(phi);

            var origin = new Vector3f(ox, oy, oz);
            var direction = new Vector3f(cx - ox, cy - oy, cz - oz);

            var result = bridge.castRay(origin, direction);
            if (result.isHit()) hits++;
        }

        System.out.printf("Ray hit rate: %d/%d (%.1f%%)%n", hits, total, 100.0 * hits / total);

        // Verify metrics
        assertEquals(total, bridge.getTotalRaysCast(), "Should have cast " + total + " rays");

        // At least some rays should hit (S0 covers about 1/6 of cube volume)
        // Note: Hit rate varies based on tree depth and voxel coverage
        assertTrue(hits > 0, "At least some rays should hit: got " + hits + "/" + total);
    }

    @Test
    void testMeshRenderer() {
        // Build test tree
        var generator = new ProceduralVoxelGenerator();
        List<Point3i> voxels = generator.generate(ProceduralVoxelGenerator.Shape.SPHERE, 16);

        var bridge = new ESVTBridge();
        bridge.buildFromVoxels(voxels, 4);

        var data = bridge.getData();
        assertNotNull(data);

        // Create mesh renderer
        var meshRenderer = new ESVTNodeMeshRenderer(data);

        // Test statistics
        String stats = meshRenderer.getStatistics();
        assertNotNull(stats);
        assertTrue(stats.contains("ESVT"), "Stats should mention ESVT");

        System.out.println("Mesh renderer stats: " + stats);
    }

    @Test
    void testRendererBuilder() {
        var renderer = ESVTRenderer.builder()
            .maxDepth(8)
            .colorScheme(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE)
            .renderMode(ESVTRenderer.RenderMode.LEAVES_ONLY)
            .opacity(0.8)
            .build();

        assertEquals(8, renderer.getMaxDepth());
        assertEquals(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE, renderer.getColorScheme());
        assertEquals(ESVTRenderer.RenderMode.LEAVES_ONLY, renderer.getRenderMode());
        assertEquals(0.8, renderer.getOpacity(), 0.001);

        // Without data, should return empty stats
        assertEquals("No data loaded", renderer.getStatistics());
    }

    @Test
    void testPerformanceMetrics() {
        var generator = new ProceduralVoxelGenerator();
        List<Point3i> voxels = generator.generate(ProceduralVoxelGenerator.Shape.TORUS, 32);

        var bridge = new ESVTBridge();
        bridge.buildFromVoxels(voxels, 5);

        // Cast multiple rays
        var origins = new Vector3f[10];
        var directions = new Vector3f[10];
        for (int i = 0; i < 10; i++) {
            origins[i] = new Vector3f(-1, 0.1f * i, 0.5f);
            directions[i] = new Vector3f(1, 0, 0);
        }

        var results = bridge.castRays(origins, directions);
        assertEquals(10, results.length, "Should return 10 results");

        // Check metrics
        var metrics = bridge.getPerformanceMetrics();
        assertEquals(10, metrics.totalRays(), "Should have 10 rays");
        assertTrue(metrics.buildTimeMs() > 0, "Build time should be positive");

        System.out.println("Performance metrics: " + metrics);

        // Reset and verify
        bridge.resetMetrics();
        assertEquals(0, bridge.getTotalRaysCast(), "Should reset to 0");
    }
}
