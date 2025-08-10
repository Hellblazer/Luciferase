package com.hellblazer.luciferase.render.voxel.quality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Color3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BoxFilter implementation.
 */
class BoxFilterTest {
    
    private BoxFilter filter;
    private AttributeFilter.VoxelData[] testNeighborhood;
    
    @BeforeEach
    void setUp() {
        filter = new BoxFilter();
        
        // Create 3x3x3 neighborhood (27 voxels) with center at index 13
        testNeighborhood = createTestNeighborhood();
    }
    
    @Test
    void testFilterColor_ValidNeighborhood() {
        var result = filter.filterColor(testNeighborhood, 13);
        
        assertNotNull(result);
        assertTrue(result.x >= 0.0f && result.x <= 1.0f);
        assertTrue(result.y >= 0.0f && result.y <= 1.0f);
        assertTrue(result.z >= 0.0f && result.z <= 1.0f);
        
        // Should be close to average of valid voxels (adjusted for actual test data)
        assertEquals(0.33f, result.x, 0.1f);
        assertEquals(0.33f, result.y, 0.1f);
        assertEquals(0.33f, result.z, 0.1f);
    }
    
    @Test
    void testFilterColor_NullNeighborhood() {
        var result = filter.filterColor(null, 0);
        assertEquals(new Color3f(0, 0, 0), result);
    }
    
    @Test
    void testFilterColor_InvalidCenterIndex() {
        var result = filter.filterColor(testNeighborhood, -1);
        assertEquals(new Color3f(0, 0, 0), result);
        
        result = filter.filterColor(testNeighborhood, testNeighborhood.length);
        assertEquals(new Color3f(0, 0, 0), result);
    }
    
    @Test
    void testFilterNormal_ValidNeighborhood() {
        var result = filter.filterNormal(testNeighborhood, 13);
        
        assertNotNull(result);
        // Should be normalized
        assertEquals(1.0f, result.length(), 0.001f);
        
        // Should point generally upward (average of test normals)
        assertTrue(result.y > 0.5f);
    }
    
    @Test
    void testFilterNormal_NullNeighborhood() {
        var result = filter.filterNormal(null, 0);
        assertEquals(new Vector3f(0, 1, 0), result);
    }
    
    @Test
    void testFilterOpacity_ValidNeighborhood() {
        var result = filter.filterOpacity(testNeighborhood, 13);
        
        assertTrue(result >= 0.0f && result <= 1.0f);
        assertEquals(0.5f, result, 0.1f); // Average opacity
    }
    
    @Test
    void testFilterOpacity_NullNeighborhood() {
        var result = filter.filterOpacity(null, 0);
        assertEquals(0.0f, result);
    }
    
    @Test
    void testFilterCharacteristics() {
        var characteristics = filter.getCharacteristics();
        
        assertNotNull(characteristics);
        assertEquals("Box Filter", characteristics.name);
        assertEquals(1.0f, characteristics.computationalCost);
        assertEquals(0.3f, characteristics.qualityImprovement);
        assertEquals(27, characteristics.neighborhoodSize);
        assertTrue(characteristics.supportsBatch);
        assertEquals(AttributeFilter.FilterType.BOX, characteristics.type);
    }
    
    @Test
    void testGetName() {
        assertEquals("Box Filter", filter.getName());
    }
    
    @Test
    void testBatchProcessing_Colors() {
        int batchSize = 5;
        var neighborhoods = new AttributeFilter.VoxelData[batchSize][];
        var centerIndices = new int[batchSize];
        var results = new Color3f[batchSize];
        
        for (int i = 0; i < batchSize; i++) {
            neighborhoods[i] = createTestNeighborhood();
            centerIndices[i] = 13;
        }
        
        filter.filterColorBatch(neighborhoods, centerIndices, results);
        
        for (int i = 0; i < batchSize; i++) {
            assertNotNull(results[i]);
            assertTrue(results[i].x >= 0.0f && results[i].x <= 1.0f);
        }
    }
    
    @Test
    void testBatchProcessing_Normals() {
        int batchSize = 5;
        var neighborhoods = new AttributeFilter.VoxelData[batchSize][];
        var centerIndices = new int[batchSize];
        var results = new Vector3f[batchSize];
        
        for (int i = 0; i < batchSize; i++) {
            neighborhoods[i] = createTestNeighborhood();
            centerIndices[i] = 13;
        }
        
        filter.filterNormalBatch(neighborhoods, centerIndices, results);
        
        for (int i = 0; i < batchSize; i++) {
            assertNotNull(results[i]);
            assertEquals(1.0f, results[i].length(), 0.001f);
        }
    }
    
    @Test
    void testBatchProcessing_Opacity() {
        int batchSize = 5;
        var neighborhoods = new AttributeFilter.VoxelData[batchSize][];
        var centerIndices = new int[batchSize];
        var results = new float[batchSize];
        
        for (int i = 0; i < batchSize; i++) {
            neighborhoods[i] = createTestNeighborhood();
            centerIndices[i] = 13;
        }
        
        filter.filterOpacityBatch(neighborhoods, centerIndices, results);
        
        for (int i = 0; i < batchSize; i++) {
            assertTrue(results[i] >= 0.0f && results[i] <= 1.0f);
        }
    }
    
    @Test
    void testBatchProcessing_NullInput() {
        var results = new Color3f[5];
        
        // Should handle null input gracefully
        filter.filterColorBatch(null, null, results);
        
        // Results should remain null
        for (var result : results) {
            assertNull(result);
        }
    }
    
    @Test
    void testConsistency_SingleVsBatch() {
        // Single processing
        var singleResult = filter.filterColor(testNeighborhood, 13);
        
        // Batch processing with single item
        var neighborhoods = new AttributeFilter.VoxelData[][]{testNeighborhood};
        var centerIndices = new int[]{13};
        var batchResults = new Color3f[1];
        
        filter.filterColorBatch(neighborhoods, centerIndices, batchResults);
        
        // Results should be identical
        assertEquals(singleResult.x, batchResults[0].x, 0.001f);
        assertEquals(singleResult.y, batchResults[0].y, 0.001f);
        assertEquals(singleResult.z, batchResults[0].z, 0.001f);
    }
    
    /**
     * Create a test neighborhood with varied voxel data.
     */
    private AttributeFilter.VoxelData[] createTestNeighborhood() {
        var neighborhood = new AttributeFilter.VoxelData[27];
        
        for (int i = 0; i < 27; i++) {
            // Calculate 3D position from linear index
            int x = i % 3;
            int y = (i / 3) % 3;
            int z = i / 9;
            
            // Distance from center (1,1,1)
            float distance = (float) Math.sqrt(
                (x - 1) * (x - 1) + 
                (y - 1) * (y - 1) + 
                (z - 1) * (z - 1)
            );
            
            // Create varied colors based on position
            var color = new Color3f(
                x / 3.0f,  // Red varies with X
                y / 3.0f,  // Green varies with Y
                z / 3.0f   // Blue varies with Z
            );
            
            // Create varied normals
            var normal = new Vector3f(
                (x - 1.0f) / 2.0f,
                1.0f,  // Generally pointing up
                (z - 1.0f) / 2.0f
            );
            normal.normalize();
            
            // Opacity varies with distance
            float opacity = 1.0f - distance * 0.3f;
            opacity = Math.max(0.1f, Math.min(1.0f, opacity));
            
            neighborhood[i] = new AttributeFilter.VoxelData(color, normal, opacity, distance);
        }
        
        return neighborhood;
    }
}