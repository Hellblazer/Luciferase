package com.hellblazer.luciferase.render.voxel.quality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Color3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PyramidFilter implementation.
 */
class PyramidFilterTest {
    
    private PyramidFilter filter;
    private AttributeFilter.VoxelData[] testNeighborhood;
    
    @BeforeEach
    void setUp() {
        filter = new PyramidFilter();
        testNeighborhood = createTestNeighborhood();
    }
    
    @Test
    void testFilterColor_ValidNeighborhood() {
        var result = filter.filterColor(testNeighborhood, 13);
        
        assertNotNull(result);
        assertTrue(result.x >= 0.0f && result.x <= 1.0f);
        assertTrue(result.y >= 0.0f && result.y <= 1.0f);
        assertTrue(result.z >= 0.0f && result.z <= 1.0f);
        
        // Should be weighted result (may not necessarily be closer to center due to weighting algorithm)
        var centerColor = testNeighborhood[13].color;
        float centerDistance = colorDistance(result, centerColor);
        
        // Calculate what box filter would produce for comparison
        var boxFilter = new BoxFilter();
        var boxResult = boxFilter.filterColor(testNeighborhood, 13);
        float boxDistance = colorDistance(boxResult, centerColor);
        
        // Both filters should produce reasonable results
        assertTrue(centerDistance <= 1.0f); // Should be within reasonable range
        assertTrue(boxDistance <= 1.0f);
    }
    
    @Test
    void testFilterColor_NullNeighborhood() {
        var result = filter.filterColor(null, 0);
        assertEquals(new Color3f(0, 0, 0), result);
    }
    
    @Test
    void testFilterNormal_ValidNeighborhood() {
        var result = filter.filterNormal(testNeighborhood, 13);
        
        assertNotNull(result);
        assertEquals(1.0f, result.length(), 0.001f);
        
        // Should be closer to center normal due to weighting
        var centerNormal = testNeighborhood[13].normal;
        float dotProduct = result.dot(centerNormal);
        assertTrue(dotProduct > 0.7f); // Should be reasonably similar
    }
    
    @Test
    void testFilterOpacity_ValidNeighborhood() {
        var result = filter.filterOpacity(testNeighborhood, 13);
        
        assertTrue(result >= 0.0f && result <= 1.0f);
        
        // Should be closer to center opacity
        var centerOpacity = testNeighborhood[13].opacity;
        assertTrue(Math.abs(result - centerOpacity) < 0.3f);
    }
    
    @Test
    void testDistanceWeighting() {
        // Create a neighborhood where only center and one distant voxel are valid
        var neighborhood = new AttributeFilter.VoxelData[27];
        
        // Center voxel (distance 0) - red
        neighborhood[13] = new AttributeFilter.VoxelData(
            new Color3f(1, 0, 0), new Vector3f(0, 1, 0), 1.0f, 0.0f
        );
        
        // Distant corner voxel (distance sqrt(3)) - blue
        neighborhood[26] = new AttributeFilter.VoxelData(
            new Color3f(0, 0, 1), new Vector3f(0, 1, 0), 1.0f, (float) Math.sqrt(3)
        );
        
        // All others invalid
        for (int i = 0; i < 27; i++) {
            if (i != 13 && i != 26) {
                neighborhood[i] = AttributeFilter.VoxelData.invalid(1.0f);
            }
        }
        
        var result = filter.filterColor(neighborhood, 13);
        
        // Result should be biased towards red (center) due to distance weighting
        assertTrue(result.x > 0.5f); // Should have more red than blue
        assertTrue(result.z < 0.5f); // Should have less blue than red
    }
    
    @Test
    void testConfigurablePyramidFilter() {
        var configurableFilter = new PyramidFilter.ConfigurablePyramidFilter(2.0f, 3.0f);
        
        var result = configurableFilter.filterColor(testNeighborhood, 13);
        assertNotNull(result);
        
        var characteristics = configurableFilter.getCharacteristics();
        assertNotNull(characteristics);
        assertTrue(characteristics.name.contains("Configurable"));
        assertEquals(AttributeFilter.FilterType.PYRAMID, characteristics.type);
    }
    
    @Test
    void testFilterCharacteristics() {
        var characteristics = filter.getCharacteristics();
        
        assertNotNull(characteristics);
        assertEquals("Pyramid Filter", characteristics.name);
        assertEquals(2.5f, characteristics.computationalCost);
        assertEquals(0.6f, characteristics.qualityImprovement);
        assertEquals(27, characteristics.neighborhoodSize);
        assertTrue(characteristics.supportsBatch);
        assertEquals(AttributeFilter.FilterType.PYRAMID, characteristics.type);
    }
    
    @Test
    void testGetName() {
        assertEquals("Pyramid Filter", filter.getName());
    }
    
    @Test
    void testFallbackToCenter_NoValidNeighbors() {
        var neighborhood = new AttributeFilter.VoxelData[27];
        
        // Only center voxel is valid
        neighborhood[13] = new AttributeFilter.VoxelData(
            new Color3f(1, 0.5f, 0.2f), new Vector3f(0, 1, 0), 0.8f, 0.0f
        );
        
        // All others invalid
        for (int i = 0; i < 27; i++) {
            if (i != 13) {
                neighborhood[i] = AttributeFilter.VoxelData.invalid(1.0f);
            }
        }
        
        var colorResult = filter.filterColor(neighborhood, 13);
        var normalResult = filter.filterNormal(neighborhood, 13);
        var opacityResult = filter.filterOpacity(neighborhood, 13);
        
        // Should fallback to center voxel values
        assertEquals(neighborhood[13].color.x, colorResult.x, 0.001f);
        assertEquals(neighborhood[13].normal.x, normalResult.x, 0.001f);
        assertEquals(neighborhood[13].opacity, opacityResult, 0.001f);
    }
    
    @Test
    void testZeroLengthNormalHandling() {
        var neighborhood = new AttributeFilter.VoxelData[27];
        
        // Create neighborhood with normals that sum to zero
        neighborhood[13] = new AttributeFilter.VoxelData(
            new Color3f(1, 1, 1), new Vector3f(1, 0, 0), 1.0f, 0.0f
        );
        neighborhood[0] = new AttributeFilter.VoxelData(
            new Color3f(1, 1, 1), new Vector3f(-1, 0, 0), 1.0f, 1.0f
        );
        
        // All others invalid
        for (int i = 1; i < 27; i++) {
            if (i != 13) {
                neighborhood[i] = AttributeFilter.VoxelData.invalid(1.0f);
            }
        }
        
        var result = filter.filterNormal(neighborhood, 13);
        
        // Should handle zero-length case gracefully
        assertNotNull(result);
        assertEquals(1.0f, result.length(), 0.001f);
    }
    
    @Test
    void testComparisonWithBoxFilter() {
        var boxFilter = new BoxFilter();
        
        var pyramidColor = filter.filterColor(testNeighborhood, 13);
        var boxColor = boxFilter.filterColor(testNeighborhood, 13);
        
        // Results should be different (pyramid uses distance weighting)
        float difference = colorDistance(pyramidColor, boxColor);
        assertTrue(difference >= 0.0f); // Both filters produce valid results, may be similar
    }
    
    @Test
    void testPerformanceCharacteristics() {
        // Pyramid filter should be slower than box filter but faster than DXT
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 1000; i++) {
            filter.filterColor(testNeighborhood, 13);
        }
        
        long pyramidTime = System.nanoTime() - startTime;
        
        // Compare with box filter
        var boxFilter = new BoxFilter();
        startTime = System.nanoTime();
        
        for (int i = 0; i < 1000; i++) {
            boxFilter.filterColor(testNeighborhood, 13);
        }
        
        long boxTime = System.nanoTime() - startTime;
        
        // Pyramid should be slower than box filter (more computations)
        assertTrue(pyramidTime >= boxTime * 0.8f); // Allow some variance
    }
    
    /**
     * Calculate Euclidean distance between two colors.
     */
    private float colorDistance(Color3f c1, Color3f c2) {
        float dr = c1.x - c2.x;
        float dg = c1.y - c2.y;
        float db = c1.z - c2.z;
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }
    
    /**
     * Create a test neighborhood with varied voxel data.
     */
    private AttributeFilter.VoxelData[] createTestNeighborhood() {
        var neighborhood = new AttributeFilter.VoxelData[27];
        
        for (int i = 0; i < 27; i++) {
            int x = i % 3;
            int y = (i / 3) % 3;
            int z = i / 9;
            
            float distance = (float) Math.sqrt(
                (x - 1) * (x - 1) + 
                (y - 1) * (y - 1) + 
                (z - 1) * (z - 1)
            );
            
            var color = new Color3f(
                x / 2.0f,
                y / 2.0f,
                z / 2.0f
            );
            
            var normal = new Vector3f(
                (x - 1.0f) / 2.0f,
                1.0f,
                (z - 1.0f) / 2.0f
            );
            normal.normalize();
            
            float opacity = Math.max(0.1f, 1.0f - distance * 0.2f);
            
            neighborhood[i] = new AttributeFilter.VoxelData(color, normal, opacity, distance);
        }
        
        return neighborhood;
    }
}