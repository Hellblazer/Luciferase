package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.geometry.Point3i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RegionBuilder - records, position conversion, and constructor.
 */
class RegionBuilderTest {

    private RegionBuilder regionBuilder;

    @BeforeEach
    void setUp() {
        regionBuilder = new RegionBuilder(2, 100, 10, 64);
    }

    @AfterEach
    void tearDown() {
        if (regionBuilder != null) {
            regionBuilder.close();
        }
    }

    @Test
    void testBuildESVO_producesNonEmptyResult() {
        // Create sample voxel positions
        var voxels = List.of(
                new Point3i(10, 20, 30),
                new Point3i(15, 25, 35),
                new Point3i(20, 30, 40)
        );

        // Build ESVO octree
        var octreeData = regionBuilder.buildESVO(voxels);

        // Verify non-empty result
        assertNotNull(octreeData, "ESVO octree data should not be null");
        assertTrue(octreeData.getNodeCount() > 0, "ESVO octree should have nodes");

        // Verify nodes were created
        var nodeIndices = octreeData.getNodeIndices();
        assertTrue(nodeIndices.length > 0, "ESVO octree should have node indices");

        // Verify actual nodes exist
        assertTrue(octreeData.getNode(0) != null, "Root node should exist");
    }

    @Test
    void testBuildEmptyRegion_returnsEmptyResult() {
        // Build with empty voxel list
        var emptyVoxels = new ArrayList<Point3i>();
        var octreeData = regionBuilder.buildESVO(emptyVoxels);

        // Verify minimal structure (OctreeBuilder returns minimal structure for empty input)
        assertNotNull(octreeData, "ESVO octree data should not be null even for empty input");
    }

    @Test
    void testPositionsToVoxels_normalizesCorrectly() {
        // Create positions in world space
        var positions = List.of(
                new Point3f(0.5f, 0.5f, 0.5f),  // Center of region
                new Point3f(1.0f, 1.0f, 1.0f),  // Max corner
                new Point3f(0.0f, 0.0f, 0.0f)   // Min corner
        );

        var bounds = new RegionBounds(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f);

        // Convert to voxels
        var voxels = regionBuilder.positionsToVoxels(positions, bounds);

        // Verify correct normalization and quantization
        assertEquals(3, voxels.size());

        // Center (0.5, 0.5, 0.5) → (32, 32, 32) for 64³ grid
        assertEquals(32, voxels.get(0).x);
        assertEquals(32, voxels.get(0).y);
        assertEquals(32, voxels.get(0).z);

        // Max corner (1.0, 1.0, 1.0) → (63, 63, 63) - clamped to gridResolution-1
        assertEquals(63, voxels.get(1).x);
        assertEquals(63, voxels.get(1).y);
        assertEquals(63, voxels.get(1).z);

        // Min corner (0.0, 0.0, 0.0) → (0, 0, 0)
        assertEquals(0, voxels.get(2).x);
        assertEquals(0, voxels.get(2).y);
        assertEquals(0, voxels.get(2).z);
    }

    @Test
    void testPositionsToVoxels_clampsOutOfBounds() {
        // Create positions outside region bounds
        var positions = List.of(
                new Point3f(-0.5f, -0.5f, -0.5f),  // Below min
                new Point3f(1.5f, 1.5f, 1.5f),     // Above max
                new Point3f(0.5f, -1.0f, 2.0f)     // Mixed out of bounds
        );

        var bounds = new RegionBounds(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f);

        // Convert to voxels
        var voxels = regionBuilder.positionsToVoxels(positions, bounds);

        // Verify all clamped to valid range [0, 63]
        assertEquals(3, voxels.size());

        // All coordinates should be within [0, 63]
        for (var voxel : voxels) {
            assertTrue(voxel.x >= 0 && voxel.x <= 63,
                    "Voxel x should be clamped: " + voxel.x);
            assertTrue(voxel.y >= 0 && voxel.y <= 63,
                    "Voxel y should be clamped: " + voxel.y);
            assertTrue(voxel.z >= 0 && voxel.z <= 63,
                    "Voxel z should be clamped: " + voxel.z);
        }

        // Below min should clamp to 0
        assertEquals(0, voxels.get(0).x);
        assertEquals(0, voxels.get(0).y);
        assertEquals(0, voxels.get(0).z);

        // Above max should clamp to 63
        assertEquals(63, voxels.get(1).x);
        assertEquals(63, voxels.get(1).y);
        assertEquals(63, voxels.get(1).z);

        // Mixed: x=32 (in bounds), y=0 (clamped), z=63 (clamped)
        assertEquals(32, voxels.get(2).x); // (0.5-0)/(1-0) * 64 = 32
        assertEquals(0, voxels.get(2).y);  // Clamped from negative
        assertEquals(63, voxels.get(2).z); // Clamped from > 63
    }

    @Test
    void testBuildESVT_producesNonEmptyResult() {
        // Create sample voxel positions
        var voxels = List.of(
                new Point3i(10, 20, 30),
                new Point3i(15, 25, 35),
                new Point3i(20, 30, 40)
        );

        // Build ESVT tetree
        var esvtData = regionBuilder.buildESVT(voxels);

        // Verify non-empty result
        assertNotNull(esvtData, "ESVT data should not be null");
        assertTrue(esvtData.nodes().length > 0, "ESVT should have nodes");
        assertTrue(esvtData.maxDepth() > 0, "ESVT should have depth");
        assertTrue(esvtData.nodeCount() > 0, "ESVT should have node count");
    }
}
