package com.hellblazer.luciferase.render.voxel.parallel;

import com.hellblazer.luciferase.render.voxel.quality.QualityController.QualityMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for the multi-threaded slice-based octree builder.
 * 
 * Validates parallel octree construction performance, correctness,
 * and quality-driven subdivision behavior.
 */
public class SliceBasedOctreeBuilderTest {
    private static final Logger log = LoggerFactory.getLogger(SliceBasedOctreeBuilderTest.class);
    
    private SliceBasedOctreeBuilder builder;
    
    @BeforeEach
    void setUp() {
        var qualityMetrics = QualityMetrics.mediumQuality();
        builder = new SliceBasedOctreeBuilder(qualityMetrics, 4, 8, 100);
    }
    
    @AfterEach
    void tearDown() {
        if (builder != null) {
            builder.shutdown();
        }
    }
    
    @Test
    void testBasicOctreeConstruction() {
        // Create simple 8x8x8 grid with a cross pattern
        int gridSize = 8;
        int[] denseGrid = new int[gridSize * gridSize * gridSize];
        float[][] voxelColors = new float[denseGrid.length][4];
        
        // Fill cross pattern
        int center = gridSize / 2;
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                for (int z = 0; z < gridSize; z++) {
                    boolean isCenter = (x == center || y == center || z == center);
                    if (isCenter && Math.abs(x - center) <= 1 && 
                        Math.abs(y - center) <= 1 && Math.abs(z - center) <= 1) {
                        
                        int idx = getVoxelIndex(x, y, z, gridSize);
                        denseGrid[idx] = 1;
                        voxelColors[idx][0] = (float)x / gridSize; // R
                        voxelColors[idx][1] = (float)y / gridSize; // G
                        voxelColors[idx][2] = (float)z / gridSize; // B
                        voxelColors[idx][3] = 1.0f; // A
                    }
                }
            }
        }
        
        float[] boundsMin = {0.0f, 0.0f, 0.0f};
        float[] boundsMax = {1.0f, 1.0f, 1.0f};
        
        long startTime = System.nanoTime();
        var octree = builder.buildOctree(denseGrid, voxelColors, gridSize, boundsMin, boundsMax);
        long buildTime = System.nanoTime() - startTime;
        
        // Validate results
        assertNotNull(octree, "Octree should be created");
        assertTrue(octree.getNodeCount() > 0, "Octree should have nodes");
        
        var stats = builder.getStatistics();
        assertTrue(stats.voxelsProcessed > 0, "Should have processed voxels");
        assertTrue(stats.nodesCreated > 0, "Should have created nodes");
        
        log.info("Basic construction test: {}ms, {}", 
                buildTime / 1_000_000.0, stats);
    }
    
    @Test
    void testQualityDrivenSubdivision() {
        // Create grid with high color variation to trigger quality subdivision
        int gridSize = 16;
        int[] denseGrid = new int[gridSize * gridSize * gridSize];
        float[][] voxelColors = new float[denseGrid.length][4];
        
        // Create checkerboard pattern with high color variation
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                for (int z = 0; z < gridSize; z++) {
                    int idx = getVoxelIndex(x, y, z, gridSize);
                    
                    // Checkerboard pattern
                    if ((x + y + z) % 2 == 0) {
                        denseGrid[idx] = 1;
                        
                        // High color variation
                        voxelColors[idx][0] = ((x + y + z) % 3) / 2.0f;
                        voxelColors[idx][1] = ((x * y) % 3) / 2.0f;
                        voxelColors[idx][2] = ((y * z) % 3) / 2.0f;
                        voxelColors[idx][3] = 1.0f;
                    }
                }
            }
        }
        
        float[] boundsMin = {0.0f, 0.0f, 0.0f};
        float[] boundsMax = {1.0f, 1.0f, 1.0f};
        
        var octree = builder.buildOctree(denseGrid, voxelColors, gridSize, boundsMin, boundsMax);
        var stats = builder.getStatistics();
        
        // Should trigger quality subdivisions due to high color variation
        assertTrue(stats.qualitySubdivisions > 0, "Should have quality-driven subdivisions");
        
        log.info("Quality subdivision test: {}", stats);
    }
    
    @Test
    void testParallelPerformance() {
        // Create larger grid to test parallel performance
        int gridSize = 32;
        int[] denseGrid = new int[gridSize * gridSize * gridSize];
        float[][] voxelColors = new float[denseGrid.length][4];
        
        // Create sphere pattern for realistic geometry
        float centerX = gridSize / 2.0f;
        float centerY = gridSize / 2.0f;
        float centerZ = gridSize / 2.0f;
        float radius = gridSize / 3.0f;
        
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                for (int z = 0; z < gridSize; z++) {
                    float dx = x - centerX;
                    float dy = y - centerY;
                    float dz = z - centerZ;
                    float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    
                    if (distance <= radius) {
                        int idx = getVoxelIndex(x, y, z, gridSize);
                        denseGrid[idx] = 1;
                        
                        // Color based on distance from center
                        float normalizedDist = distance / radius;
                        voxelColors[idx][0] = normalizedDist;
                        voxelColors[idx][1] = 1.0f - normalizedDist;
                        voxelColors[idx][2] = 0.5f;
                        voxelColors[idx][3] = 1.0f;
                    }
                }
            }
        }
        
        float[] boundsMin = {0.0f, 0.0f, 0.0f};
        float[] boundsMax = {1.0f, 1.0f, 1.0f};
        
        long startTime = System.nanoTime();
        var octree = builder.buildOctree(denseGrid, voxelColors, gridSize, boundsMin, boundsMax);
        long buildTime = System.nanoTime() - startTime;
        
        var stats = builder.getStatistics();
        
        // Validate performance
        assertTrue(buildTime < 5_000_000_000L, "Build should complete in under 5 seconds");
        assertTrue(stats.voxelsProcessed > 1000, "Should process significant number of voxels");
        assertTrue(stats.threadsUsed > 1, "Should use multiple threads");
        
        double buildTimeMs = buildTime / 1_000_000.0;
        double voxelsPerMs = stats.voxelsProcessed / buildTimeMs;
        
        log.info("Performance test: {}ms, {} voxels/ms, {} threads", 
                buildTimeMs, voxelsPerMs, stats.threadsUsed);
        
        // Performance should be reasonable
        assertTrue(voxelsPerMs > 10, "Should process at least 10 voxels/ms");
    }
    
    @Test
    void testEmptyGrid() {
        int gridSize = 8;
        int[] denseGrid = new int[gridSize * gridSize * gridSize]; // All zeros
        float[][] voxelColors = new float[denseGrid.length][4];
        
        float[] boundsMin = {0.0f, 0.0f, 0.0f};
        float[] boundsMax = {1.0f, 1.0f, 1.0f};
        
        var octree = builder.buildOctree(denseGrid, voxelColors, gridSize, boundsMin, boundsMax);
        var stats = builder.getStatistics();
        
        // Should handle empty grid gracefully
        assertNotNull(octree, "Should create octree even for empty grid");
        assertEquals(0, stats.voxelsProcessed, "Should not process any voxels");
        assertEquals(0, stats.qualitySubdivisions, "Should not have subdivisions");
        
        log.info("Empty grid test: {}", stats);
    }
    
    @Test
    void testSingleVoxel() {
        int gridSize = 8;
        int[] denseGrid = new int[gridSize * gridSize * gridSize];
        float[][] voxelColors = new float[denseGrid.length][4];
        
        // Single voxel at center
        int center = gridSize / 2;
        int idx = getVoxelIndex(center, center, center, gridSize);
        denseGrid[idx] = 1;
        voxelColors[idx] = new float[]{1.0f, 0.0f, 0.0f, 1.0f};
        
        float[] boundsMin = {0.0f, 0.0f, 0.0f};
        float[] boundsMax = {1.0f, 1.0f, 1.0f};
        
        var octree = builder.buildOctree(denseGrid, voxelColors, gridSize, boundsMin, boundsMax);
        var stats = builder.getStatistics();
        
        // Should handle single voxel correctly
        assertNotNull(octree);
        assertEquals(1, stats.voxelsProcessed, "Should process exactly one voxel");
        assertTrue(stats.nodesCreated > 0, "Should create at least one node");
        
        log.info("Single voxel test: {}", stats);
    }
    
    @Test
    void testDifferentQualityLevels() {
        // Test high, medium, and low quality settings
        var qualityLevels = new QualityMetrics[] {
            QualityMetrics.highQuality(),
            QualityMetrics.mediumQuality(), 
            QualityMetrics.lowQuality()
        };
        
        String[] qualityNames = {"High", "Medium", "Low"};
        
        // Create test grid with color variation
        int gridSize = 16;
        int[] denseGrid = new int[gridSize * gridSize * gridSize];
        float[][] voxelColors = new float[denseGrid.length][4];
        
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                for (int z = 0; z < gridSize; z++) {
                    if ((x + y + z) % 3 == 0) {
                        int idx = getVoxelIndex(x, y, z, gridSize);
                        denseGrid[idx] = 1;
                        
                        voxelColors[idx][0] = (float) Math.sin(x * 0.5) * 0.5f + 0.5f;
                        voxelColors[idx][1] = (float) Math.sin(y * 0.5) * 0.5f + 0.5f;
                        voxelColors[idx][2] = (float) Math.sin(z * 0.5) * 0.5f + 0.5f;
                        voxelColors[idx][3] = 1.0f;
                    }
                }
            }
        }
        
        float[] boundsMin = {0.0f, 0.0f, 0.0f};
        float[] boundsMax = {1.0f, 1.0f, 1.0f};
        
        for (int i = 0; i < qualityLevels.length; i++) {
            try (var qualityBuilder = new SliceBasedOctreeBuilder(qualityLevels[i], 4, 8, 100)) {
                var octree = qualityBuilder.buildOctree(denseGrid, voxelColors, gridSize, 
                                                       boundsMin, boundsMax);
                var stats = qualityBuilder.getStatistics();
                
                assertNotNull(octree);
                assertTrue(stats.voxelsProcessed > 0);
                
                log.info("{} quality test: {}", qualityNames[i], stats);
            }
        }
    }
    
    private int getVoxelIndex(int x, int y, int z, int gridSize) {
        return x + y * gridSize + z * gridSize * gridSize;
    }
}