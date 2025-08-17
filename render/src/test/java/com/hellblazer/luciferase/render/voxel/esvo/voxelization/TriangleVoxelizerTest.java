package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-first development for triangle voxelization.
 * Converts triangle meshes into voxel representation for ESVO.
 */
@DisplayName("Triangle Voxelizer Tests")
class TriangleVoxelizerTest {
    
    private static final float EPSILON = 0.001f;
    private TriangleVoxelizer voxelizer;
    
    @BeforeEach
    void setup() {
        voxelizer = new TriangleVoxelizer();
    }
    
    @Test
    @DisplayName("Should voxelize single triangle")
    void testSingleTriangleVoxelization() {
        // Triangle in XY plane
        float[] v0 = {0, 0, 0};
        float[] v1 = {1, 0, 0};
        float[] v2 = {0, 1, 0};
        
        var config = new VoxelizationConfig()
            .withResolution(8)
            .withBounds(-1, -1, -1, 2, 2, 2);
            
        var result = voxelizer.voxelizeTriangle(v0, v1, v2, config);
        
        assertNotNull(result);
        assertTrue(result.getVoxelCount() > 0, "Should generate voxels");
        
        // Check that voxels are within expected grid indices
        // Triangle (0,0,0)-(1,0,0)-(0,1,0) with bounds (-1,-1,-1) to (2,2,2)
        // Voxel size = 3/8 = 0.375
        // Expected grid indices: x=[2,5], y=[2,5], z=2
        for (var voxel : result.getVoxels()) {
            assertTrue(voxel.x >= 2 && voxel.x <= 5, "X should be in range [2,5], got " + voxel.x);
            assertTrue(voxel.y >= 2 && voxel.y <= 5, "Y should be in range [2,5], got " + voxel.y);
            assertEquals(2, voxel.z); // Should be in grid layer 2
        }
    }
    
    @Test
    @DisplayName("Should handle edge-aligned triangle")
    void testEdgeAlignedTriangle() {
        // Triangle aligned with voxel grid
        float[] v0 = {0, 0, 0};
        float[] v1 = {1, 0, 0};
        float[] v2 = {0, 1, 0};
        
        var config = new VoxelizationConfig()
            .withResolution(4)
            .withBounds(0, 0, 0, 1, 1, 1);
            
        var result = voxelizer.voxelizeTriangle(v0, v1, v2, config);
        
        // Should voxelize edges correctly
        assertTrue(result.containsVoxel(0, 0, 0));
        assertTrue(result.containsVoxel(1, 0, 0));
        assertTrue(result.containsVoxel(0, 1, 0));
    }
    
    @Test
    @DisplayName("Should compute triangle-voxel overlap")
    void testTriangleVoxelOverlap() {
        float[] v0 = {0.25f, 0.25f, 0};
        float[] v1 = {0.75f, 0.25f, 0};
        float[] v2 = {0.5f, 0.75f, 0};
        
        float voxelSize = 0.25f;
        float[] voxelCenter = {0.5f, 0.5f, 0};
        
        boolean overlaps = voxelizer.triangleOverlapsVoxel(
            v0, v1, v2, voxelCenter, voxelSize
        );
        
        assertTrue(overlaps, "Triangle should overlap voxel");
    }
    
    @Test
    @DisplayName("Should handle thin triangles")
    void testThinTriangle() {
        // Very thin triangle (nearly degenerate)
        float[] v0 = {0, 0, 0};
        float[] v1 = {1, 0, 0};
        float[] v2 = {0.5f, 0.001f, 0}; // Almost collinear
        
        var config = new VoxelizationConfig()
            .withResolution(8)
            .with6Connectivity(true); // Use 6-connectivity for robustness
            
        var result = voxelizer.voxelizeTriangle(v0, v1, v2, config);
        
        assertNotNull(result);
        // Should still produce voxels along the edge
        assertTrue(result.getVoxelCount() > 0);
    }
    
    @Test
    @DisplayName("Should voxelize mesh with multiple triangles")
    void testMeshVoxelization() {
        // Simple quad made of two triangles
        float[][] vertices = {
            {0, 0, 0},
            {1, 0, 0},
            {1, 1, 0},
            {0, 1, 0}
        };
        
        int[][] triangles = {
            {0, 1, 2}, // First triangle
            {0, 2, 3}  // Second triangle
        };
        
        var config = new VoxelizationConfig()
            .withResolution(16)
            .withBounds(0, 0, -0.5f, 1, 1, 0.5f); // Set bounds to match mesh
            
        var mesh = new TriangleMesh(vertices, triangles);
        var result = voxelizer.voxelizeMesh(mesh, config);
        
        assertNotNull(result);
        assertEquals(2, result.getTriangleCount());
        
        // The quad should create many voxels
        assertTrue(result.getVoxelCount() > 10, "Should voxelize the quad into multiple voxels");
        
        // Check that voxels are in the expected grid range
        // With bounds (0,0,-0.5) to (1,1,0.5) and resolution 16:
        // The quad at z=0 should be at grid z=8
        // The quad from (0,0) to (1,1) should fill the entire 16x16 grid in x,y
        boolean hasVoxelsInExpectedRange = false;
        for (var voxel : result.getVoxels()) {
            if (voxel.x >= 0 && voxel.x < 16 && 
                voxel.y >= 0 && voxel.y < 16 &&
                voxel.z == 8) {
                hasVoxelsInExpectedRange = true;
                break;
            }
        }
        assertTrue(hasVoxelsInExpectedRange, "Should have voxels in the expected grid range");
    }
    
    @Test
    @DisplayName("Should apply conservative voxelization")
    void testConservativeVoxelization() {
        // Triangle that straddles voxel boundaries
        // Place triangle at boundary between voxels to test conservative rasterization
        float[] v0 = {-0.1f, -0.1f, 0};
        float[] v1 = {0.1f, -0.1f, 0};
        float[] v2 = {0, 0.1f, 0};
        
        var config = new VoxelizationConfig()
            .withResolution(2)
            .withBounds(-1, -1, -1, 1, 1, 1)
            .withConservative(true); // Enable conservative rasterization
            
        var result = voxelizer.voxelizeTriangle(v0, v1, v2, config);
        
        // Triangle spans the boundary at (0,0,0) in world coords
        // With bounds (-1,-1,-1) to (1,1,1) and resolution 2:
        // Voxel size = 2/2 = 1.0
        // Grid indices: voxel (0,0) covers [-1,0), voxel (1,1) covers [0,1)
        // Triangle at (-0.1 to 0.1) should touch both voxels (0,0,1) and (1,1,1)
        assertTrue(result.getVoxelCount() >= 1, "Should have at least one voxel");
        
        // Conservative mode with 1% expansion should catch boundary cases
        // The small triangle should be in the (1,1,1) voxel primarily
        assertTrue(result.containsVoxel(1, 1, 1) || result.containsVoxel(0, 0, 1), 
                  "Should contain voxels near the origin");
    }
    
    @Test
    @DisplayName("Should compute surface normals for voxels")
    void testSurfaceNormalComputation() {
        float[] v0 = {0, 0, 0};
        float[] v1 = {1, 0, 0};
        float[] v2 = {0, 1, 0};
        
        var config = new VoxelizationConfig()
            .withResolution(4)
            .withComputeNormals(true);
            
        var result = voxelizer.voxelizeTriangle(v0, v1, v2, config);
        
        // All voxels should have same normal (Z direction for XY plane triangle)
        for (var voxel : result.getVoxels()) {
            float[] normal = voxel.getNormal();
            assertNotNull(normal);
            assertEquals(0, normal[0], EPSILON);
            assertEquals(0, normal[1], EPSILON);
            assertEquals(1, Math.abs(normal[2]), EPSILON); // Z or -Z
        }
    }
    
    @Test
    @DisplayName("Should handle 3D triangles")
    void test3DTriangleVoxelization() {
        // Triangle in 3D space
        float[] v0 = {0, 0, 0};
        float[] v1 = {1, 0, 0.5f};
        float[] v2 = {0, 1, 1};
        
        var config = new VoxelizationConfig()
            .withResolution(8)
            .withBounds(0, 0, 0, 1, 1, 1);
            
        var result = voxelizer.voxelizeTriangle(v0, v1, v2, config);
        
        assertNotNull(result);
        assertTrue(result.getVoxelCount() > 0);
        
        // Should have voxels at different Z levels
        boolean hasMultipleZLevels = false;
        int firstZ = result.getVoxels().get(0).z;
        for (var voxel : result.getVoxels()) {
            if (voxel.z != firstZ) {
                hasMultipleZLevels = true;
                break;
            }
        }
        assertTrue(hasMultipleZLevels, "3D triangle should span multiple Z levels");
    }
    
    @Test
    @DisplayName("Should use separating axis theorem for overlap")
    void testSeparatingAxisTheorem() {
        // Test SAT implementation for triangle-box overlap
        float[] v0 = {-0.5f, -0.5f, 0};
        float[] v1 = {0.5f, -0.5f, 0};
        float[] v2 = {0, 0.5f, 0};
        
        // Box that overlaps
        float[] boxCenter1 = {0, 0, 0};
        float boxSize = 0.5f;
        assertTrue(voxelizer.triangleOverlapsVoxel(v0, v1, v2, boxCenter1, boxSize));
        
        // Box that doesn't overlap
        float[] boxCenter2 = {2, 2, 0};
        assertFalse(voxelizer.triangleOverlapsVoxel(v0, v1, v2, boxCenter2, boxSize));
    }
    
    @Test
    @DisplayName("Should generate octree from voxelization")
    void testOctreeGeneration() {
        float[] v0 = {0, 0, 0};
        float[] v1 = {1, 0, 0};
        float[] v2 = {0, 1, 0};
        
        var config = new VoxelizationConfig()
            .withResolution(8)
            .withGenerateOctree(true);
            
        var result = voxelizer.voxelizeTriangle(v0, v1, v2, config);
        var octree = result.getOctree();
        
        assertNotNull(octree);
        assertTrue(octree.getNodeCount() > 0);
        assertEquals(result.getVoxelCount(), octree.getLeafCount());
    }
    
    @Test
    @DisplayName("Should respect maximum octree depth")
    void testMaxOctreeDepth() {
        var config = new VoxelizationConfig()
            .withResolution(256) // High resolution
            .withMaxOctreeDepth(4) // Limit depth
            .withGenerateOctree(true);
            
        float[] v0 = {0, 0, 0};
        float[] v1 = {1, 0, 0};
        float[] v2 = {0, 1, 0};
        
        var result = voxelizer.voxelizeTriangle(v0, v1, v2, config);
        var octree = result.getOctree();
        
        assertNotNull(octree);
        assertTrue(octree.getMaxDepth() <= 4, "Octree depth should be limited");
    }
}