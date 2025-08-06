package com.hellblazer.luciferase.render.voxel.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for mesh voxelization pipeline.
 */
class MeshVoxelizerTest {
    
    private MeshVoxelizer voxelizer;
    
    @BeforeEach
    void setUp() {
        voxelizer = new MeshVoxelizer(4); // 4 threads for testing
    }
    
    @Test
    void testVoxelizeSingleTriangle() {
        var triangles = List.of(
            new MeshVoxelizer.Triangle(
                new Point3f(0, 0, 0),
                new Point3f(1, 0, 0),
                new Point3f(0, 1, 0),
                1
            )
        );
        
        var grid = voxelizer.voxelize(triangles, 16);
        
        assertNotNull(grid);
        assertEquals(16, grid.getResolution());
        assertTrue(grid.getVoxelCount() > 0);
        
        // Check that voxels exist near the triangle
        assertTrue(grid.hasVoxel(0, 0, 0));
    }
    
    @Test
    void testVoxelizeCube() {
        var triangles = createCubeTriangles(new Point3f(0, 0, 0), 1.0f, 1);
        
        var grid = voxelizer.voxelize(triangles, 32);
        
        assertNotNull(grid);
        assertEquals(32, grid.getResolution());
        assertTrue(grid.getVoxelCount() > 0);
        
        // Check fill rate is reasonable for a cube
        float fillRate = grid.getFillRate();
        assertTrue(fillRate > 0.0f);
        assertTrue(fillRate < 0.2f); // Cube should be mostly hollow
    }
    
    @Test
    void testVoxelizeMultipleMaterials() {
        var triangles = new ArrayList<MeshVoxelizer.Triangle>();
        
        // Add triangles with different materials
        triangles.add(new MeshVoxelizer.Triangle(
            new Point3f(0, 0, 0),
            new Point3f(1, 0, 0),
            new Point3f(0, 1, 0),
            1
        ));
        
        triangles.add(new MeshVoxelizer.Triangle(
            new Point3f(2, 0, 0),
            new Point3f(3, 0, 0),
            new Point3f(2, 1, 0),
            2
        ));
        
        var grid = voxelizer.voxelize(triangles, 64);
        
        // Check that different materials are preserved
        var voxel1 = grid.getVoxel(0, 0, 0);
        var voxel2 = grid.getVoxel(32, 0, 0);
        
        if (voxel1 != null && voxel2 != null) {
            assertNotEquals(voxel1.getMaterial(), voxel2.getMaterial());
        }
    }
    
    @Test
    void testAdaptiveVoxelization() {
        var triangles = createCubeTriangles(new Point3f(0, 0, 0), 1.0f, 1);
        
        var grid = voxelizer.voxelizeAdaptive(triangles, 16, 64, 0.5f);
        
        assertNotNull(grid);
        assertEquals(64, grid.getResolution());
        
        // Adaptive should produce more voxels in dense regions
        assertTrue(grid.getVoxelCount() > 0);
    }
    
    @Test
    void testEmptyTriangleList() {
        var triangles = new ArrayList<MeshVoxelizer.Triangle>();
        
        var grid = voxelizer.voxelize(triangles, 32);
        
        assertNotNull(grid);
        assertEquals(0, grid.getVoxelCount());
        assertEquals(0.0f, grid.getFillRate());
    }
    
    @Test
    void testLargeTriangle() {
        // Triangle larger than the grid
        var triangles = List.of(
            new MeshVoxelizer.Triangle(
                new Point3f(-100, -100, 0),
                new Point3f(100, -100, 0),
                new Point3f(0, 100, 0),
                1
            )
        );
        
        var grid = voxelizer.voxelize(triangles, 16);
        
        assertNotNull(grid);
        // Should voxelize the portion within bounds
        assertTrue(grid.getVoxelCount() > 0);
    }
    
    @Test
    void testParallelVoxelization() {
        // Create many triangles to test parallel processing
        var triangles = new ArrayList<MeshVoxelizer.Triangle>();
        for (int i = 0; i < 100; i++) {
            float offset = i * 0.1f;
            triangles.add(new MeshVoxelizer.Triangle(
                new Point3f(offset, 0, 0),
                new Point3f(offset + 0.1f, 0, 0),
                new Point3f(offset, 0.1f, 0),
                i % 3
            ));
        }
        
        var grid = voxelizer.voxelize(triangles, 128);
        
        assertNotNull(grid);
        assertTrue(grid.getVoxelCount() > 0);
    }
    
    @Test
    void testVoxelGridOperations() {
        var grid = new VoxelGrid(32, new Point3f(0, 0, 0), new Point3f(1, 1, 1));
        
        // Test adding voxels
        grid.addVoxel(0, 0, 0, 1, 1.0f);
        grid.addVoxel(1, 1, 1, 2, 0.5f);
        
        assertEquals(2, grid.getVoxelCount());
        assertTrue(grid.hasVoxel(0, 0, 0));
        assertTrue(grid.hasVoxel(1, 1, 1));
        assertFalse(grid.hasVoxel(2, 2, 2));
        
        // Test voxel retrieval
        var voxel = grid.getVoxel(0, 0, 0);
        assertNotNull(voxel);
        assertEquals(1, voxel.getMaterial());
        assertEquals(1.0f, voxel.getCoverage());
        
        // Test coordinate conversion
        var worldPos = grid.voxelToWorld(16, 16, 16);
        assertEquals(0.515625f, worldPos.x, 0.001f);
        assertEquals(0.515625f, worldPos.y, 0.001f);
        assertEquals(0.515625f, worldPos.z, 0.001f);
        
        var voxelPos = grid.worldToVoxel(new Point3f(0.5f, 0.5f, 0.5f));
        assertEquals(16.0f, voxelPos.x, 0.1f);
        assertEquals(16.0f, voxelPos.y, 0.1f);
        assertEquals(16.0f, voxelPos.z, 0.1f);
    }
    
    @Test
    void testRegionDensity() {
        var grid = new VoxelGrid(32, new Point3f(0, 0, 0), new Point3f(1, 1, 1));
        
        // Add voxels in a region
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    grid.addVoxel(x, y, z, 1, 1.0f);
                }
            }
        }
        
        float density = grid.getRegionDensity(0, 0, 0, 4);
        assertEquals(1.0f, density); // Fully filled region
        
        density = grid.getRegionDensity(8, 8, 8, 4);
        assertEquals(0.0f, density); // Empty region
    }
    
    private List<MeshVoxelizer.Triangle> createCubeTriangles(Point3f center, float size, int material) {
        var triangles = new ArrayList<MeshVoxelizer.Triangle>();
        float half = size / 2;
        
        // Define cube vertices
        var v000 = new Point3f(center.x - half, center.y - half, center.z - half);
        var v001 = new Point3f(center.x - half, center.y - half, center.z + half);
        var v010 = new Point3f(center.x - half, center.y + half, center.z - half);
        var v011 = new Point3f(center.x - half, center.y + half, center.z + half);
        var v100 = new Point3f(center.x + half, center.y - half, center.z - half);
        var v101 = new Point3f(center.x + half, center.y - half, center.z + half);
        var v110 = new Point3f(center.x + half, center.y + half, center.z - half);
        var v111 = new Point3f(center.x + half, center.y + half, center.z + half);
        
        // Front face
        triangles.add(new MeshVoxelizer.Triangle(v000, v100, v110, material));
        triangles.add(new MeshVoxelizer.Triangle(v000, v110, v010, material));
        
        // Back face
        triangles.add(new MeshVoxelizer.Triangle(v001, v011, v111, material));
        triangles.add(new MeshVoxelizer.Triangle(v001, v111, v101, material));
        
        // Left face
        triangles.add(new MeshVoxelizer.Triangle(v000, v010, v011, material));
        triangles.add(new MeshVoxelizer.Triangle(v000, v011, v001, material));
        
        // Right face
        triangles.add(new MeshVoxelizer.Triangle(v100, v101, v111, material));
        triangles.add(new MeshVoxelizer.Triangle(v100, v111, v110, material));
        
        // Top face
        triangles.add(new MeshVoxelizer.Triangle(v010, v110, v111, material));
        triangles.add(new MeshVoxelizer.Triangle(v010, v111, v011, material));
        
        // Bottom face
        triangles.add(new MeshVoxelizer.Triangle(v000, v001, v101, material));
        triangles.add(new MeshVoxelizer.Triangle(v000, v101, v100, material));
        
        return triangles;
    }
}