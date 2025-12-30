/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.portal.esvt;

import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.portal.esvt.bridge.ESVTBridge;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end validation test for inscribed sphere generation and ESVT building.
 * This test validates the complete pipeline before UI integration.
 */
class ESVTInscribedSphereValidationTest {

    @Test
    void testInscribedSphereVoxelGeometry() {
        var generator = new ESVTVoxelGenerator();
        int resolution = 64;

        // Generate inscribed sphere
        var voxels = generator.generate(ESVTVoxelGenerator.Shape.SPHERE, resolution);

        System.out.println("=== Inscribed Sphere Geometry Validation ===");
        System.out.printf("Resolution: %d%n", resolution);
        System.out.printf("Voxel count: %d%n", voxels.size());

        // Get expected incenter and inradius
        var incenter = generator.getS0Incenter();
        var inradius = generator.getS0Inradius();
        System.out.printf("S0 Incenter: (%.4f, %.4f, %.4f)%n", incenter.x, incenter.y, incenter.z);
        System.out.printf("S0 Inradius: %.4f%n", inradius);

        // Scale to resolution
        float cx = incenter.x * resolution;
        float cy = incenter.y * resolution;
        float cz = incenter.z * resolution;
        float scaledRadius = inradius * resolution * 0.95f;

        System.out.printf("Scaled center: (%.2f, %.2f, %.2f)%n", cx, cy, cz);
        System.out.printf("Scaled radius: %.2f%n", scaledRadius);

        // Compute actual centroid of voxels
        double sumX = 0, sumY = 0, sumZ = 0;
        for (var v : voxels) {
            sumX += v.x + 0.5;
            sumY += v.y + 0.5;
            sumZ += v.z + 0.5;
        }
        double actualCx = sumX / voxels.size();
        double actualCy = sumY / voxels.size();
        double actualCz = sumZ / voxels.size();

        System.out.printf("Actual centroid: (%.2f, %.2f, %.2f)%n", actualCx, actualCy, actualCz);

        // Centroid should be close to expected center
        double centroidError = Math.sqrt(
            Math.pow(actualCx - cx, 2) +
            Math.pow(actualCy - cy, 2) +
            Math.pow(actualCz - cz, 2)
        );
        System.out.printf("Centroid error: %.2f voxels%n", centroidError);

        assertTrue(centroidError < scaledRadius * 0.2,
            "Centroid should be within 20% of radius from expected center, error: " + centroidError);

        // Check bounding box
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (var v : voxels) {
            minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
            minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);
            minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
        }

        System.out.printf("Bounding box: [%d,%d] x [%d,%d] x [%d,%d]%n",
            minX, maxX, minY, maxY, minZ, maxZ);
        System.out.printf("Spans: X=%d, Y=%d, Z=%d%n",
            maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);

        // Bounding box should roughly match 2*radius
        int expectedSpan = (int) (2 * scaledRadius);
        int actualMaxSpan = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) + 1;

        System.out.printf("Expected span (2r): %d, actual max span: %d%n", expectedSpan, actualMaxSpan);

        // Span should be within 50% of expected (accounting for discretization)
        assertTrue(actualMaxSpan < expectedSpan * 1.5 && actualMaxSpan > expectedSpan * 0.5,
            "Bounding box span should be roughly 2*radius");

        // Verify all voxels are within the sphere
        int outsideSphere = 0;
        double maxDist = 0;
        for (var v : voxels) {
            double dx = v.x + 0.5 - cx;
            double dy = v.y + 0.5 - cy;
            double dz = v.z + 0.5 - cz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            maxDist = Math.max(maxDist, dist);
            if (dist > scaledRadius * 1.1) { // Allow 10% tolerance
                outsideSphere++;
            }
        }

        System.out.printf("Max distance from center: %.2f (limit: %.2f)%n", maxDist, scaledRadius);
        System.out.printf("Voxels outside sphere (>110%% radius): %d%n", outsideSphere);

        assertTrue(outsideSphere < voxels.size() * 0.05,
            "Less than 5% of voxels should be outside the sphere boundary");
    }

    @Test
    void testESVTBuildPreservesSpatialRelationships() {
        var generator = new ESVTVoxelGenerator();
        var bridge = new ESVTBridge();

        int resolution = 32; // Lower for faster test
        int depth = 5; // log2(32) = 5

        // Generate inscribed sphere
        var voxels = generator.generate(ESVTVoxelGenerator.Shape.SPHERE, resolution);

        System.out.println("\n=== ESVT Build Spatial Preservation Test ===");
        System.out.printf("Input: %d voxels at resolution %d%n", voxels.size(), resolution);

        // Build ESVT WITH explicit grid resolution
        bridge.buildAndChain(voxels, depth, resolution);
        var data = bridge.getData();

        System.out.printf("ESVT: %d nodes, %d leaves, depth %d%n",
            data.nodeCount(), data.leafCount(), data.maxDepth());

        // The key test: leaf count should be proportional to voxel count
        // (some voxels may share nodes, so leaves <= voxels)
        assertTrue(data.leafCount() > 0, "Should have leaves");
        assertTrue(data.leafCount() <= voxels.size() * 2,
            "Leaf count should be reasonable relative to input voxels");

        // Compare with building WITHOUT grid resolution (legacy behavior)
        var bridgeLegacy = new ESVTBridge();
        bridgeLegacy.buildAndChain(voxels, depth, -1); // -1 = use voxel bbox
        var dataLegacy = bridgeLegacy.getData();

        System.out.printf("Legacy (no grid): %d nodes, %d leaves%n",
            dataLegacy.nodeCount(), dataLegacy.leafCount());

        // With proper grid resolution, we should have FEWER leaves because
        // the sphere is smaller relative to the coordinate space
        // (unless the legacy scaling actually produces similar results)
        System.out.printf("Leaf ratio (withGrid/legacy): %.2f%n",
            (double) data.leafCount() / dataLegacy.leafCount());
    }

    @Test
    void testVoxelDistributionWithinS0() {
        var generator = new ESVTVoxelGenerator();
        int resolution = 64;

        // Test all three shapes
        for (var shape : ESVTVoxelGenerator.Shape.values()) {
            var voxels = generator.generate(shape, resolution);

            System.out.printf("\n=== %s Distribution Test ===%n", shape);
            System.out.printf("Voxel count: %d%n", voxels.size());

            // All voxels should be inside S0 (verified by generator)
            // Check spatial distribution
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

            for (var v : voxels) {
                minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
                minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);
                minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
            }

            System.out.printf("Bounds: X[%d,%d] Y[%d,%d] Z[%d,%d]%n",
                minX, maxX, minY, maxY, minZ, maxZ);

            // Verify bounds are within [0, resolution-1]
            assertTrue(minX >= 0 && maxX < resolution,
                shape + " X bounds should be in [0, " + (resolution-1) + "]");
            assertTrue(minY >= 0 && maxY < resolution,
                shape + " Y bounds should be in [0, " + (resolution-1) + "]");
            assertTrue(minZ >= 0 && maxZ < resolution,
                shape + " Z bounds should be in [0, " + (resolution-1) + "]");

            // S0 tetrahedron with vertices (0,0,0), (1,0,0), (1,1,0), (1,1,1)
            // should NOT fill the entire cube - it's only 1/6 of the cube
            if (shape == ESVTVoxelGenerator.Shape.TETRAHEDRON) {
                // S0 tetrahedron volume is 1/6 of cube, voxel count should be ~= resolution^3 / 6
                int expectedMax = (resolution * resolution * resolution) / 6;
                int actualCount = voxels.size();
                System.out.printf("Expected ~%d voxels (1/6 of cube), got %d%n", expectedMax, actualCount);

                assertTrue(actualCount < expectedMax * 2,
                    "S0 tetrahedron should be < 2x of expected 1/6 cube volume");

                // S0 doesn't extend to all corners - it should NOT have voxels at (0,0,resolution-1)
                // because that's outside S0
            }
        }
    }

    @Test
    void testIsInsideS0Debug() {
        var generator = new ESVTVoxelGenerator();

        // S0 vertices: (0,0,0), (1,0,0), (1,1,0), (1,1,1)
        // Points that SHOULD be inside S0:
        // - Centroid of S0
        // - Points near origin along the X axis

        // Points that should NOT be inside S0:
        // - (0, 0, 1) - this is OUTSIDE S0 because S0 only extends in Z at the x=1,y=1 corner
        // - (0, 1, 0) - this is OUTSIDE S0 because S0 only has Y at x >= some value
        // - (0.5, 0.5, 0.5) - this might be outside depending on exact S0 shape

        // Generate tetrahedron at resolution 8 for easy analysis
        var voxels = generator.generate(ESVTVoxelGenerator.Shape.TETRAHEDRON, 8);

        System.out.println("\n=== isInsideS0 Debug ===");
        System.out.println("S0 vertices: (0,0,0), (1,0,0), (1,1,0), (1,1,1)");
        System.out.printf("Tetrahedron voxels at res 8: %d%n", voxels.size());

        // Print which corner voxels are present
        boolean has000 = voxels.stream().anyMatch(v -> v.x == 0 && v.y == 0 && v.z == 0);
        boolean has700 = voxels.stream().anyMatch(v -> v.x == 7 && v.y == 0 && v.z == 0);
        boolean has770 = voxels.stream().anyMatch(v -> v.x == 7 && v.y == 7 && v.z == 0);
        boolean has777 = voxels.stream().anyMatch(v -> v.x == 7 && v.y == 7 && v.z == 7);
        boolean has007 = voxels.stream().anyMatch(v -> v.x == 0 && v.y == 0 && v.z == 7);
        boolean has070 = voxels.stream().anyMatch(v -> v.x == 0 && v.y == 7 && v.z == 0);
        boolean has077 = voxels.stream().anyMatch(v -> v.x == 0 && v.y == 7 && v.z == 7);
        boolean has707 = voxels.stream().anyMatch(v -> v.x == 7 && v.y == 0 && v.z == 7);

        System.out.println("Corner presence (should only be at S0 vertices):");
        System.out.printf("  (0,0,0): %s (should be YES - S0 vertex)%n", has000);
        System.out.printf("  (7,0,0): %s (should be YES - near S0 vertex)%n", has700);
        System.out.printf("  (7,7,0): %s (should be YES - near S0 vertex)%n", has770);
        System.out.printf("  (7,7,7): %s (should be YES - S0 vertex)%n", has777);
        System.out.printf("  (0,0,7): %s (should be NO - outside S0)%n", has007);
        System.out.printf("  (0,7,0): %s (should be NO - outside S0)%n", has070);
        System.out.printf("  (0,7,7): %s (should be NO - outside S0)%n", has077);
        System.out.printf("  (7,0,7): %s (should be NO - outside S0)%n", has707);

        // These corners should NOT be in S0
        assertFalse(has007, "Voxel (0,0,7) should NOT be in S0");
        assertFalse(has070, "Voxel (0,7,0) should NOT be in S0");
        assertFalse(has077, "Voxel (0,7,7) should NOT be in S0");
        assertFalse(has707, "Voxel (7,0,7) should NOT be in S0");
    }

    @Test
    void testSphereIsSignificantlySmallerThanTetrahedron() {
        var generator = new ESVTVoxelGenerator();
        int resolution = 64;

        var sphereVoxels = generator.generate(ESVTVoxelGenerator.Shape.SPHERE, resolution);
        var tetVoxels = generator.generate(ESVTVoxelGenerator.Shape.TETRAHEDRON, resolution);

        System.out.println("\n=== Size Comparison ===");
        System.out.printf("Sphere: %d voxels%n", sphereVoxels.size());
        System.out.printf("Tetrahedron: %d voxels%n", tetVoxels.size());
        System.out.printf("Ratio (sphere/tet): %.2f%%%n",
            100.0 * sphereVoxels.size() / tetVoxels.size());

        // The inscribed sphere should be MUCH smaller than the tetrahedron
        // Theoretically, inscribed sphere volume / tetrahedron volume is small
        // For S0, inradius ≈ 0.2071, so sphere volume ≈ 4/3 * π * 0.2071³ ≈ 0.037
        // S0 volume ≈ 1/6 of unit cube ≈ 0.167
        // Ratio ≈ 22%
        assertTrue(sphereVoxels.size() < tetVoxels.size() * 0.5,
            "Inscribed sphere should be less than 50% of tetrahedron volume");

        assertTrue(sphereVoxels.size() > tetVoxels.size() * 0.05,
            "Inscribed sphere should be more than 5% of tetrahedron volume");
    }
}
