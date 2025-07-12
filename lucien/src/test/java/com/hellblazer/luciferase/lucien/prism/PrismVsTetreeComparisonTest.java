package com.hellblazer.luciferase.lucien.prism;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;

import javax.vecmath.Point3f;

/**
 * Performance and accuracy comparison between Prism and Tetree spatial indices.
 * Tests relative performance characteristics and validates consistent behavior.
 */
public class PrismVsTetreeComparisonTest {

    private Prism<LongEntityID, String> prism;
    private Tetree<LongEntityID, String> tetree;
    private SequentialLongIDGenerator idGenerator;
    private final float worldSize = 100.0f;
    private final Random random = new Random(42); // Fixed seed for reproducibility
    
    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        prism = new Prism<>(idGenerator, worldSize, 21);
        tetree = new Tetree<>(idGenerator, 100, (byte)21);
    }
    
    @Test
    void testInsertionPerformanceComparison() {
        var positions = generateValidPrismPositions(1000);
        var contents = generateContents(1000);
        
        // Test Prism insertion performance
        long prismStartTime = System.nanoTime();
        var prismIds = prism.insertBatch(positions, contents, (byte)5);
        long prismInsertTime = System.nanoTime() - prismStartTime;
        
        // Reset id generator for fair comparison
        idGenerator = new SequentialLongIDGenerator();
        tetree = new Tetree<>(idGenerator, 100, (byte)21);
        
        // Test Tetree insertion performance  
        long tetreeStartTime = System.nanoTime();
        var tetreeIds = tetree.insertBatch(positions, contents, (byte)5);
        long tetreeInsertTime = System.nanoTime() - tetreeStartTime;
        
        // Validate both inserted same number of entities
        assertEquals(1000, prismIds.size());
        assertEquals(1000, tetreeIds.size());
        assertEquals(1000, prism.entityCount());
        assertEquals(1000, tetree.entityCount());
        
        // Log performance ratio for analysis
        double performanceRatio = (double)prismInsertTime / tetreeInsertTime;
        System.out.printf("Insertion Performance Ratio (Prism/Tetree): %.2fx%n", performanceRatio);
        System.out.printf("Prism insert time: %.2f ms%n", prismInsertTime / 1_000_000.0);
        System.out.printf("Tetree insert time: %.2f ms%n", tetreeInsertTime / 1_000_000.0);
        
        // Both should complete within reasonable time (10 seconds)
        assertTrue(prismInsertTime < 10_000_000_000L, "Prism insertion took too long");
        assertTrue(tetreeInsertTime < 10_000_000_000L, "Tetree insertion took too long");
    }
    
    @Test
    void testKNNPerformanceComparison() {
        // Insert test entities
        var positions = generateValidPrismPositions(500);
        var contents = generateContents(500);
        
        prism.insertBatch(positions, contents, (byte)3); // Use coarser level for better distribution
        
        idGenerator = new SequentialLongIDGenerator();
        tetree = new Tetree<>(idGenerator, 100, (byte)21);
        tetree.insertBatch(positions, contents, (byte)3);
        
        var queryPoint = new Point3f(30.0f, 25.0f, 50.0f);
        int k = 10;
        
        // Test Prism k-NN performance
        long prismStartTime = System.nanoTime();
        var prismNeighbors = prism.kNearestNeighbors(queryPoint, k, Float.MAX_VALUE);
        long prismKNNTime = System.nanoTime() - prismStartTime;
        
        // Test Tetree k-NN performance
        long tetreeStartTime = System.nanoTime();
        var tetreeNeighbors = tetree.kNearestNeighbors(queryPoint, k, Float.MAX_VALUE);
        long tetreeKNNTime = System.nanoTime() - tetreeStartTime;
        
        // Log performance comparison
        double knnRatio = (double)prismKNNTime / tetreeKNNTime;
        System.out.printf("k-NN Performance Ratio (Prism/Tetree): %.2fx%n", knnRatio);
        System.out.printf("Prism k-NN time: %.2f μs%n", prismKNNTime / 1_000.0);
        System.out.printf("Tetree k-NN time: %.2f μs%n", tetreeKNNTime / 1_000.0);
        
        // Both should find neighbors and complete quickly
        assertTrue(prismNeighbors.size() >= 1, "Prism should find at least 1 neighbor");
        assertTrue(tetreeNeighbors.size() >= 1, "Tetree should find at least 1 neighbor");
        assertTrue(prismKNNTime < 10_000_000L, "Prism k-NN took too long"); // 10ms limit
        assertTrue(tetreeKNNTime < 10_000_000L, "Tetree k-NN took too long");
    }
    
    @Test
    void testRangeQueryPerformanceComparison() {
        // Insert test entities
        var positions = generateValidPrismPositions(300);
        var contents = generateContents(300);
        
        prism.insertBatch(positions, contents, (byte)4);
        
        idGenerator = new SequentialLongIDGenerator();
        tetree = new Tetree<>(idGenerator, 100, (byte)21);
        tetree.insertBatch(positions, contents, (byte)4);
        
        // Create search region
        var searchCube = new com.hellblazer.luciferase.lucien.Spatial.Cube(20.0f, 20.0f, 20.0f, 20.0f);
        
        // Test Prism range query performance
        long prismStartTime = System.nanoTime();
        var prismResults = prism.entitiesInRegion(searchCube);
        long prismRangeTime = System.nanoTime() - prismStartTime;
        
        // Test Tetree range query performance
        long tetreeStartTime = System.nanoTime();
        var tetreeResults = tetree.entitiesInRegion(searchCube);
        long tetreeRangeTime = System.nanoTime() - tetreeStartTime;
        
        // Log performance comparison
        double rangeRatio = (double)prismRangeTime / tetreeRangeTime;
        System.out.printf("Range Query Performance Ratio (Prism/Tetree): %.2fx%n", rangeRatio);
        System.out.printf("Prism range time: %.2f μs%n", prismRangeTime / 1_000.0);
        System.out.printf("Tetree range time: %.2f μs%n", tetreeRangeTime / 1_000.0);
        
        // Both should complete quickly
        assertTrue(prismRangeTime < 5_000_000L, "Prism range query took too long"); // 5ms limit
        assertTrue(tetreeRangeTime < 5_000_000L, "Tetree range query took too long");
        
        // Results should be reasonable (some entities found)
        System.out.printf("Prism found %d entities, Tetree found %d entities%n", 
                         prismResults.size(), tetreeResults.size());
    }
    
    @Test
    void testMemoryUsageComparison() {
        // Insert significant number of entities
        var positions = generateValidPrismPositions(2000);
        var contents = generateContents(2000);
        
        // Measure memory before insertion
        System.gc(); // Suggest garbage collection
        long beforeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        prism.insertBatch(positions, contents, (byte)4);
        
        System.gc();
        long afterPrismMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long prismMemoryUsage = afterPrismMemory - beforeMemory;
        
        // Reset for Tetree test
        idGenerator = new SequentialLongIDGenerator();
        tetree = new Tetree<>(idGenerator, 100, (byte)21);
        System.gc();
        long beforeTetreeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        tetree.insertBatch(positions, contents, (byte)4);
        
        System.gc();
        long afterTetreeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long tetreeMemoryUsage = afterTetreeMemory - beforeTetreeMemory;
        
        // Log memory comparison
        double memoryRatio = (double)prismMemoryUsage / tetreeMemoryUsage;
        System.out.printf("Memory Usage Ratio (Prism/Tetree): %.2fx%n", memoryRatio);
        System.out.printf("Prism memory usage: %.2f KB%n", prismMemoryUsage / 1024.0);
        System.out.printf("Tetree memory usage: %.2f KB%n", tetreeMemoryUsage / 1024.0);
        
        // Both should have reasonable memory usage (not more than 100MB for 2000 entities)
        assertTrue(prismMemoryUsage < 100_000_000L, "Prism memory usage excessive");
        assertTrue(tetreeMemoryUsage < 100_000_000L, "Tetree memory usage excessive");
    }
    
    @Test
    void testEntityCountConsistency() {
        // Test that both indices handle same operations consistently
        var positions = generateValidPrismPositions(100);
        var contents = generateContents(100);
        
        var prismIds = prism.insertBatch(positions, contents, (byte)5);
        
        idGenerator = new SequentialLongIDGenerator();
        tetree = new Tetree<>(idGenerator, 100, (byte)21);
        var tetreeIds = tetree.insertBatch(positions, contents, (byte)5);
        
        // Both should have same entity count
        assertEquals(prism.entityCount(), tetree.entityCount());
        assertEquals(100, prism.entityCount());
        assertEquals(100, tetree.entityCount());
        
        // Test removal consistency
        int removeCount = 10;
        for (int i = 0; i < removeCount; i++) {
            assertTrue(prism.removeEntity(prismIds.get(i)));
            assertTrue(tetree.removeEntity(tetreeIds.get(i)));
        }
        
        assertEquals(90, prism.entityCount());
        assertEquals(90, tetree.entityCount());
        assertEquals(prism.entityCount(), tetree.entityCount());
    }
    
    /**
     * Generate valid positions that respect the triangular constraint x + y < worldSize.
     */
    private List<Point3f> generateValidPrismPositions(int count) {
        var positions = new ArrayList<Point3f>(count);
        
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * (worldSize * 0.7f);  // 0 to 70% of worldSize
            float y = random.nextFloat() * (worldSize * 0.7f - x); // Ensure x + y < 70% of worldSize
            float z = random.nextFloat() * worldSize;
            
            positions.add(new Point3f(x, y, z));
        }
        
        return positions;
    }
    
    /**
     * Generate content strings for testing.
     */
    private List<String> generateContents(int count) {
        var contents = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            contents.add("Entity" + i);
        }
        return contents;
    }
}