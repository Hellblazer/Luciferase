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
        
        // Check that voxels are within triangle bounds
        for (var voxel : result.getVoxels()) {
            assertTrue(voxel.x >= 0 && voxel.x <= 1);
            assertTrue(voxel.y >= 0 && voxel.y <= 1);
            assertEquals(0, voxel.z); // Should be in Z=0 plane
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
            .withResolution(16);
            
        var mesh = new TriangleMesh(vertices, triangles);
        var result = voxelizer.voxelizeMesh(mesh, config);
        
        assertNotNull(result);
        assertEquals(2, result.getTriangleCount());
        
        // Should form a filled quad
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                if (x < 16 && y < 16) {
                    assertTrue(result.hasVoxelNear(x/16.0f, y/16.0f, 0, 0.1f),
                        String.format("Should have voxel near (%f, %f)", x/16.0f, y/16.0f));
                }
            }
        }
    }
    
    @Test
    @DisplayName("Should apply conservative voxelization")
    void testConservativeVoxelization() {
        // Triangle that barely touches voxels
        float[] v0 = {0.49f, 0.49f, 0};
        float[] v1 = {0.51f, 0.49f, 0};
        float[] v2 = {0.5f, 0.51f, 0};
        
        var config = new VoxelizationConfig()
            .withResolution(2)
            .withConservative(true); // Enable conservative rasterization
            
        var result = voxelizer.voxelizeTriangle(v0, v1, v2, config);
        
        // Conservative voxelization should include all touched voxels
        assertTrue(result.containsVoxel(0, 0, 0));
        assertTrue(result.containsVoxel(1, 0, 0));
        assertTrue(result.containsVoxel(0, 1, 0));
        assertTrue(result.containsVoxel(1, 1, 0));
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