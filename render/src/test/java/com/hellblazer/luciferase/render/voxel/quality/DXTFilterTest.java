package com.hellblazer.luciferase.render.voxel.quality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Color3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DXTFilter implementation.
 */
class DXTFilterTest {
    
    private DXTFilter filter;
    private AttributeFilter.VoxelData[] testNeighborhood;
    
    @BeforeEach
    void setUp() {
        filter = new DXTFilter();
        testNeighborhood = createTestNeighborhood();
    }
    
    @Test
    void testFilterColor_ValidNeighborhood() {
        var result = filter.filterColor(testNeighborhood, 13);
        
        assertNotNull(result);
        assertTrue(result.x >= 0.0f && result.x <= 1.0f);
        assertTrue(result.y >= 0.0f && result.y <= 1.0f);
        assertTrue(result.z >= 0.0f && result.z <= 1.0f);
        
        // Should be quantized for DXT compression
        checkDXTQuantization(result);
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
        
        // Should be optimized for BC5 compression
        // BC5 reconstructs Z component, so X and Y should be quantized
        checkBC5Optimization(result);
    }
    
    @Test
    void testFilterOpacity_ValidNeighborhood() {
        var result = filter.filterOpacity(testNeighborhood, 13);
        
        assertTrue(result >= 0.0f && result <= 1.0f);
        
        // Should be quantized to DXT alpha levels (256 levels for BC3)
        float quantized = Math.round(result * 255.0f) / 255.0f;
        assertEquals(quantized, result, 0.001f);
    }
    
    @Test
    void testDXTQuantization_Colors() {
        // Test with pure colors that should quantize differently
        var redVoxel = new AttributeFilter.VoxelData(
            new Color3f(1, 0, 0), new Vector3f(0, 1, 0), 1.0f, 0.0f
        );
        var neighborhood = new AttributeFilter.VoxelData[]{redVoxel};
        
        var result = filter.filterColor(neighborhood, 0);
        
        // Red and blue use 5-bit precision, green uses 6-bit
        checkDXTQuantization(result);
    }
    
    @Test
    void testBC5NormalOptimization() {
        // Test normal that's nearly in XY plane
        var flatNormal = new Vector3f(0.7f, 0.7f, 0.1f);
        flatNormal.normalize();
        
        var voxel = new AttributeFilter.VoxelData(
            new Color3f(1, 1, 1), flatNormal, 1.0f, 0.0f
        );
        var neighborhood = new AttributeFilter.VoxelData[]{voxel};
        
        var result = filter.filterNormal(neighborhood, 0);
        
        // Should maintain reasonable Z component even after BC5 optimization
        assertTrue(result.z >= 0.02f); // Minimum compression tolerance
        checkBC5Optimization(result);
    }
    
    @Test
    void testColorVarianceAnalysis() {
        // Create neighborhood with low color variance
        var neighborhood = new AttributeFilter.VoxelData[9];
        var baseColor = new Color3f(0.5f, 0.3f, 0.8f);
        
        for (int i = 0; i < 9; i++) {
            var color = new Color3f(baseColor);
            // Add small variance
            color.x += (i - 4) * 0.01f;
            color.y += (i - 4) * 0.01f;
            color.z += (i - 4) * 0.01f;
            
            neighborhood[i] = new AttributeFilter.VoxelData(
                color, new Vector3f(0, 1, 0), 1.0f, i * 0.1f
            );
        }
        
        var result = filter.filterColor(neighborhood, 4);
        
        // With low variance, should bias towards dominant color
        float distance = colorDistance(result, baseColor);
        assertTrue(distance < 0.1f);
    }
    
    @Test
    void testColorVarianceAnalysis_HighVariance() {
        // Create neighborhood with high color variance
        var neighborhood = new AttributeFilter.VoxelData[9];
        
        for (int i = 0; i < 9; i++) {
            var color = new Color3f(
                i / 8.0f,        // Red varies from 0 to 1
                (8 - i) / 8.0f,  // Green varies from 1 to 0
                0.5f             // Blue constant
            );
            
            neighborhood[i] = new AttributeFilter.VoxelData(
                color, new Vector3f(0, 1, 0), 1.0f, i * 0.1f
            );
        }
        
        var result = filter.filterColor(neighborhood, 4);
        
        // With high variance, should not bias as much
        assertNotNull(result);
        checkDXTQuantization(result);
    }
    
    @Test
    void testFilterCharacteristics() {
        var characteristics = filter.getCharacteristics();
        
        assertNotNull(characteristics);
        assertEquals("DXT-Aware Filter", characteristics.name);
        assertEquals(4.0f, characteristics.computationalCost);
        assertEquals(0.8f, characteristics.qualityImprovement);
        assertEquals(27, characteristics.neighborhoodSize);
        assertTrue(characteristics.supportsBatch);
        assertEquals(AttributeFilter.FilterType.DXT_AWARE, characteristics.type);
    }
    
    @Test
    void testGetName() {
        assertEquals("DXT-Aware Filter", filter.getName());
    }
    
    @Test
    void testBatchProcessing_Colors() {
        int batchSize = 3;
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
            checkDXTQuantization(results[i]);
        }
    }
    
    @Test
    void testBatchProcessing_Normals() {
        int batchSize = 3;
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
            checkBC5Optimization(results[i]);
        }
    }
    
    @Test
    void testBatchProcessing_Opacity() {
        int batchSize = 3;
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
            // Check DXT alpha quantization
            float quantized = Math.round(results[i] * 255.0f) / 255.0f;
            assertEquals(quantized, results[i], 0.001f);
        }
    }
    
    @Test
    void testComparisonWithOtherFilters() {
        var boxFilter = new BoxFilter();
        var pyramidFilter = new PyramidFilter();
        
        var dxtColor = filter.filterColor(testNeighborhood, 13);
        var boxColor = boxFilter.filterColor(testNeighborhood, 13);
        var pyramidColor = pyramidFilter.filterColor(testNeighborhood, 13);
        
        // DXT filter should produce different results due to compression optimization
        assertTrue(colorDistance(dxtColor, boxColor) > 0.01f);
        assertTrue(colorDistance(dxtColor, pyramidColor) > 0.01f);
    }
    
    @Test
    void testPerformanceCharacteristics() {
        // DXT filter should be the slowest due to additional processing
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 100; i++) {
            filter.filterColor(testNeighborhood, 13);
        }
        
        long dxtTime = System.nanoTime() - startTime;
        
        // Compare with box filter
        var boxFilter = new BoxFilter();
        startTime = System.nanoTime();
        
        for (int i = 0; i < 100; i++) {
            boxFilter.filterColor(testNeighborhood, 13);
        }
        
        long boxTime = System.nanoTime() - startTime;
        
        // DXT should be significantly slower
        assertTrue(dxtTime >= boxTime * 1.5f);
    }
    
    /**
     * Check that color is properly quantized for DXT compression.
     */
    private void checkDXTQuantization(Color3f color) {
        // Red and blue use 5 bits (32 levels), green uses 6 bits (64 levels)
        int redLevels = 31;
        int greenLevels = 63;
        int blueLevels = 31;
        
        int quantizedRed = Math.round(color.x * redLevels);
        int quantizedGreen = Math.round(color.y * greenLevels);
        int quantizedBlue = Math.round(color.z * blueLevels);
        
        float expectedRed = quantizedRed / (float) redLevels;
        float expectedGreen = quantizedGreen / (float) greenLevels;
        float expectedBlue = quantizedBlue / (float) blueLevels;
        
        assertEquals(expectedRed, color.x, 0.02f);
        assertEquals(expectedGreen, color.y, 0.02f);
        assertEquals(expectedBlue, color.z, 0.02f);
    }
    
    /**
     * Check that normal is optimized for BC5 compression.
     */
    private void checkBC5Optimization(Vector3f normal) {
        // BC5 stores X and Y, reconstructs Z
        // X and Y should be quantized to 8-bit precision in [-1,1] range
        float quantizedX = Math.round((normal.x * 0.5f + 0.5f) * 255.0f) / 255.0f * 2.0f - 1.0f;
        float quantizedY = Math.round((normal.y * 0.5f + 0.5f) * 255.0f) / 255.0f * 2.0f - 1.0f;
        
        assertEquals(quantizedX, normal.x, 0.01f);
        assertEquals(quantizedY, normal.y, 0.01f);
        
        // Z should be properly reconstructed
        float expectedZ = (float) Math.sqrt(Math.max(0.0f, 1.0f - normal.x * normal.x - normal.y * normal.y));
        assertEquals(expectedZ, normal.z, 0.02f);
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
            
            // Create colors that will test DXT quantization
            var color = new Color3f(
                (x + 1) / 4.0f,  // 0.25, 0.5, 0.75
                (y + 1) / 4.0f,
                (z + 1) / 4.0f
            );
            
            // Create normals that will test BC5 optimization
            var normal = new Vector3f(
                (x - 1.0f) / 3.0f,  // -0.33, 0, 0.33
                (y - 1.0f) / 3.0f,
                1.0f
            );
            normal.normalize();
            
            float opacity = Math.max(0.0f, 1.0f - distance * 0.3f);
            
            neighborhood[i] = new AttributeFilter.VoxelData(color, normal, opacity, distance);
        }
        
        return neighborhood;
    }
}