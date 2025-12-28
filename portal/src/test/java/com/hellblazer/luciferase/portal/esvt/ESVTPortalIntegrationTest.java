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

import com.hellblazer.luciferase.esvt.traversal.MollerTrumboreIntersection;
import com.hellblazer.luciferase.lucien.Constants;
import javax.vecmath.Point3f;

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
        // First, verify ray-tetrahedron intersection works with S0
        var intersector = MollerTrumboreIntersection.create();
        var tetResult = new MollerTrumboreIntersection.TetrahedronResult();

        // S0 vertices from Constants.SIMPLEX_STANDARD[0]
        var s0Verts = Constants.SIMPLEX_STANDARD[0];
        var v0 = new Point3f(s0Verts[0].x, s0Verts[0].y, s0Verts[0].z);
        var v1 = new Point3f(s0Verts[1].x, s0Verts[1].y, s0Verts[1].z);
        var v2 = new Point3f(s0Verts[2].x, s0Verts[2].y, s0Verts[2].z);
        var v3 = new Point3f(s0Verts[3].x, s0Verts[3].y, s0Verts[3].z);

        System.out.printf("S0 vertices: v0=%s, v1=%s, v2=%s, v3=%s%n", v0, v1, v2, v3);

        var rayOrigin = new Point3f(-0.1f, 0.5f, 0.5f);
        var rayDir = new Vector3f(1, 0, 0);

        boolean hits = intersector.intersectTetrahedron(rayOrigin, rayDir, v0, v1, v2, v3, tetResult);
        System.out.printf("S0 intersection: hit=%b, tEntry=%.4f, tExit=%.4f, entryFace=%d%n",
            hits, tetResult.tEntry, tetResult.tExit, tetResult.entryFace);

        // Build solid test cube - use full voxel grid (0 to resolution-1)
        // ProceduralVoxelGenerator.Shape.CUBE has a 10% margin which creates sparse
        // regions. For testing, generate a solid cube directly.
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
                int childIdx = root.getChildIndex(i);
                var childNode = data.nodes()[childIdx];
                System.out.printf("  Child %d: idx=%d, valid=%b, childMask=0x%02X, leafMask=0x%02X%n",
                    i, childIdx, childNode.isValid(), childNode.getChildMask(), childNode.getLeafMask());
            }
        }

        // Cast ray through center - ESVTBuilder normalizes voxels to [0.1, 0.9] in Tetree
        // which maps to [0.1, 0.9] in [0,1] traversal space
        // Ray from outside the unit cube towards the center (should hit the normalized cube)
        var origin = new Vector3f(-0.1f, 0.5f, 0.5f);
        var direction = new Vector3f(1, 0, 0);

        var result = bridge.castRay(origin, direction);

        System.out.printf("Ray result: hit=%b, t=%.4f, point=(%.3f,%.3f,%.3f), iterations=%d%n",
            result.isHit(), result.t, result.x, result.y, result.z, result.iterations);

        // Verify metrics - ray was cast regardless of hit
        assertEquals(1, bridge.getTotalRaysCast(), "Should have cast 1 ray");

        // After coordinate normalization, rays through center should hit
        // The cube occupies roughly [0.1, 0.9] in the normalized space
        assertTrue(result.isHit(), "Ray through center should hit normalized cube");
        assertTrue(result.t > 0, "Hit distance should be positive");
        assertTrue(result.t < 1.0f, "Hit should be within reasonable distance");
        assertEquals(1, bridge.getTotalHits(), "Should have 1 hit");

        // Hit point should be within [0,1] space
        assertTrue(result.x >= 0 && result.x <= 1, "Hit x should be in [0,1]");
        assertTrue(result.y >= 0 && result.y <= 1, "Hit y should be in [0,1]");
        assertTrue(result.z >= 0 && result.z <= 1, "Hit z should be in [0,1]");
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
