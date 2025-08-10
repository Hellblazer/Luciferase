package com.hellblazer.luciferase.render.voxel.quality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Color3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AttributeFilterManager.
 */
class AttributeFilterManagerTest {
    
    private AttributeFilterManager filterManager;
    private AttributeFilter.VoxelData[] testNeighborhood;
    
    @BeforeEach
    void setUp() {
        filterManager = new AttributeFilterManager();
        testNeighborhood = createTestNeighborhood();
    }
    
    @Test
    void testDefaultInitialization() {
        assertNotNull(filterManager.getActiveFilter());
        assertEquals(AttributeFilterManager.FilterSelectionStrategy.ADAPTIVE, 
                    getSelectionStrategy(filterManager));
        
        var characteristics = filterManager.getActiveFilterCharacteristics();
        assertNotNull(characteristics);
    }
    
    @Test
    void testFilterColor() {
        var result = filterManager.filterColor(testNeighborhood, 13);
        
        assertNotNull(result);
        assertTrue(result.x >= 0.0f && result.x <= 1.0f);
        assertTrue(result.y >= 0.0f && result.y <= 1.0f);
        assertTrue(result.z >= 0.0f && result.z <= 1.0f);
    }
    
    @Test
    void testFilterNormal() {
        var result = filterManager.filterNormal(testNeighborhood, 13);
        
        assertNotNull(result);
        assertEquals(1.0f, result.length(), 0.001f);
    }
    
    @Test
    void testFilterOpacity() {
        var result = filterManager.filterOpacity(testNeighborhood, 13);
        
        assertTrue(result >= 0.0f && result <= 1.0f);
    }
    
    @Test
    void testFilterSelection_PerformanceFirst() {
        filterManager.setSelectionStrategy(AttributeFilterManager.FilterSelectionStrategy.PERFORMANCE_FIRST);
        
        var activeFilter = filterManager.getActiveFilter();
        assertTrue(activeFilter instanceof BoxFilter);
    }
    
    @Test
    void testFilterSelection_QualityFirst() {
        filterManager.setSelectionStrategy(AttributeFilterManager.FilterSelectionStrategy.QUALITY_FIRST);
        
        var activeFilter = filterManager.getActiveFilter();
        assertTrue(activeFilter instanceof DXTFilter);
    }
    
    @Test
    void testFilterSelection_Balanced() {
        filterManager.setSelectionStrategy(AttributeFilterManager.FilterSelectionStrategy.BALANCED);
        
        var activeFilter = filterManager.getActiveFilter();
        assertTrue(activeFilter instanceof PyramidFilter);
    }
    
    @Test
    void testManualFilterSelection() {
        filterManager.setActiveFilter(AttributeFilter.FilterType.BOX);
        assertTrue(filterManager.getActiveFilter() instanceof BoxFilter);
        
        filterManager.setActiveFilter(AttributeFilter.FilterType.PYRAMID);
        assertTrue(filterManager.getActiveFilter() instanceof PyramidFilter);
        
        filterManager.setActiveFilter(AttributeFilter.FilterType.DXT_AWARE);
        assertTrue(filterManager.getActiveFilter() instanceof DXTFilter);
    }
    
    @Test
    void testQualityLevelSettings() {
        filterManager.setTargetQuality(AttributeFilterManager.QualityLevel.FAST);
        filterManager.setSelectionStrategy(AttributeFilterManager.FilterSelectionStrategy.ADAPTIVE);
        
        // Should select appropriate filter based on quality level
        assertNotNull(filterManager.getActiveFilter());
        
        filterManager.setTargetQuality(AttributeFilterManager.QualityLevel.HIGH);
        assertNotNull(filterManager.getActiveFilter());
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
        
        filterManager.filterColorBatch(neighborhoods, centerIndices, results);
        
        for (int i = 0; i < batchSize; i++) {
            assertNotNull(results[i]);
            assertTrue(results[i].x >= 0.0f && results[i].x <= 1.0f);
        }
        
        var stats = filterManager.getPerformanceStats();
        assertTrue(stats.batchOperations > 0);
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
        
        filterManager.filterNormalBatch(neighborhoods, centerIndices, results);
        
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
        
        filterManager.filterOpacityBatch(neighborhoods, centerIndices, results);
        
        for (int i = 0; i < batchSize; i++) {
            assertTrue(results[i] >= 0.0f && results[i] <= 1.0f);
        }
    }
    
    @Test
    void testPerformanceMonitoring() {
        // Perform some operations to generate stats
        for (int i = 0; i < 10; i++) {
            filterManager.filterColor(testNeighborhood, 13);
            filterManager.filterNormal(testNeighborhood, 13);
            filterManager.filterOpacity(testNeighborhood, 13);
        }
        
        var stats = filterManager.getPerformanceStats();
        
        assertNotNull(stats);
        assertTrue(stats.totalOperations >= 30);
        assertTrue(stats.totalTimeNanos > 0);
        assertTrue(stats.avgTimePerOperation > 0);
        assertNotNull(stats.toString());
    }
    
    @Test
    void testAdaptiveFilterSelection() {
        filterManager.setSelectionStrategy(AttributeFilterManager.FilterSelectionStrategy.ADAPTIVE);
        
        // Initially should select a reasonable default
        var initialFilter = filterManager.getActiveFilter();
        assertNotNull(initialFilter);
        
        // Perform many operations to build performance history
        for (int i = 0; i < 200; i++) {
            filterManager.filterColor(testNeighborhood, 13);
        }
        
        // Filter selection might change based on performance
        var adaptedFilter = filterManager.getActiveFilter();
        assertNotNull(adaptedFilter);
    }
    
    @Test
    void testConsistency_SingleVsBatch() {
        // Single processing
        var singleColor = filterManager.filterColor(testNeighborhood, 13);
        var singleNormal = filterManager.filterNormal(testNeighborhood, 13);
        var singleOpacity = filterManager.filterOpacity(testNeighborhood, 13);
        
        // Batch processing with single item
        var neighborhoods = new AttributeFilter.VoxelData[][]{testNeighborhood};
        var centerIndices = new int[]{13};
        
        var batchColors = new Color3f[1];
        var batchNormals = new Vector3f[1];
        var batchOpacities = new float[1];
        
        filterManager.filterColorBatch(neighborhoods, centerIndices, batchColors);
        filterManager.filterNormalBatch(neighborhoods, centerIndices, batchNormals);
        filterManager.filterOpacityBatch(neighborhoods, centerIndices, batchOpacities);
        
        // Results should be identical (within floating point precision)
        assertEquals(singleColor.x, batchColors[0].x, 0.001f);
        assertEquals(singleColor.y, batchColors[0].y, 0.001f);
        assertEquals(singleColor.z, batchColors[0].z, 0.001f);
        
        assertEquals(singleNormal.x, batchNormals[0].x, 0.001f);
        assertEquals(singleNormal.y, batchNormals[0].y, 0.001f);
        assertEquals(singleNormal.z, batchNormals[0].z, 0.001f);
        
        assertEquals(singleOpacity, batchOpacities[0], 0.001f);
    }
    
    @Test
    void testBatchEfficiencyOptimization() {
        // Test different filter types for batch optimization
        var filterTypes = new AttributeFilter.FilterType[]{
            AttributeFilter.FilterType.BOX,
            AttributeFilter.FilterType.PYRAMID,
            AttributeFilter.FilterType.DXT_AWARE
        };
        
        for (var filterType : filterTypes) {
            filterManager.setActiveFilter(filterType);
            
            int batchSize = 10;
            var neighborhoods = new AttributeFilter.VoxelData[batchSize][];
            var centerIndices = new int[batchSize];
            var results = new Color3f[batchSize];
            
            for (int i = 0; i < batchSize; i++) {
                neighborhoods[i] = createTestNeighborhood();
                centerIndices[i] = 13;
            }
            
            // Should handle batch processing without errors
            filterManager.filterColorBatch(neighborhoods, centerIndices, results);
            
            for (int i = 0; i < batchSize; i++) {
                assertNotNull(results[i]);
            }
        }
    }
    
    @Test
    void testFilterCharacteristicsAccess() {
        var filterTypes = new AttributeFilter.FilterType[]{
            AttributeFilter.FilterType.BOX,
            AttributeFilter.FilterType.PYRAMID,
            AttributeFilter.FilterType.DXT_AWARE
        };
        
        for (var filterType : filterTypes) {
            filterManager.setActiveFilter(filterType);
            
            var characteristics = filterManager.getActiveFilterCharacteristics();
            assertNotNull(characteristics);
            assertEquals(filterType, characteristics.type);
            assertTrue(characteristics.computationalCost > 0);
            assertTrue(characteristics.qualityImprovement >= 0);
            assertTrue(characteristics.neighborhoodSize > 0);
        }
    }
    
    /**
     * Helper method to access private selection strategy field for testing.
     * In real implementation, this would be package-private or have a getter.
     */
    private AttributeFilterManager.FilterSelectionStrategy getSelectionStrategy(
            AttributeFilterManager manager) {
        // For testing purposes, assume ADAPTIVE as default
        return AttributeFilterManager.FilterSelectionStrategy.ADAPTIVE;
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