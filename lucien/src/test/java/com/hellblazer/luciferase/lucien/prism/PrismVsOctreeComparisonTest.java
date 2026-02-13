package com.hellblazer.luciferase.lucien.prism;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;

import javax.vecmath.Point3f;

/**
 * Performance and accuracy comparison between Prism and Octree spatial indices.
 * Tests relative performance characteristics and validates consistent behavior.
 */
public class PrismVsOctreeComparisonTest {

    private Prism<LongEntityID, String> prism;
    private Octree<LongEntityID, String> octree;
    private SequentialLongIDGenerator idGenerator;
    private final float worldSize = 100.0f;
    private final Random random = new Random(42); // Fixed seed for reproducibility
    
    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        prism = new Prism<>(idGenerator, worldSize, 21);
        octree = new Octree<>(idGenerator, 100, (byte)21);
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
        octree = new Octree<>(idGenerator, 100, (byte)21);
        
        // Test Octree insertion performance  
        long octreeStartTime = System.nanoTime();
        var octreeIds = octree.insertBatch(positions, contents, (byte)5);
        long octreeInsertTime = System.nanoTime() - octreeStartTime;
        
        // Validate both inserted same number of entities
        assertEquals(1000, prismIds.size());
        assertEquals(1000, octreeIds.size());
        assertEquals(1000, prism.entityCount());
        assertEquals(1000, octree.entityCount());
        
        // Log performance ratio for analysis
        double performanceRatio = (double)prismInsertTime / octreeInsertTime;
        System.out.printf("Insertion Performance Ratio (Prism/Octree): %.2fx%n", performanceRatio);
        System.out.printf("Prism insert time: %.2f ms%n", prismInsertTime / 1_000_000.0);
        System.out.printf("Octree insert time: %.2f ms%n", octreeInsertTime / 1_000_000.0);
        
        // Both should complete within reasonable time (10 seconds)
        assertTrue(prismInsertTime < 10_000_000_000L, "Prism insertion took too long");
        assertTrue(octreeInsertTime < 10_000_000_000L, "Octree insertion took too long");
    }
    
    @Test
    void testKNNPerformanceComparison() {
        // Insert test entities
        var positions = generateValidPrismPositions(500);
        var contents = generateContents(500);
        
        prism.insertBatch(positions, contents, (byte)3); // Use coarser level for better distribution
        
        idGenerator = new SequentialLongIDGenerator();
        octree = new Octree<>(idGenerator, 100, (byte)21);
        octree.insertBatch(positions, contents, (byte)3);
        
        var queryPoint = new Point3f(30.0f, 25.0f, 50.0f);
        int k = 10;
        
        // Test Prism k-NN performance
        long prismStartTime = System.nanoTime();
        var prismNeighbors = prism.kNearestNeighbors(queryPoint, k, Float.MAX_VALUE);
        long prismKNNTime = System.nanoTime() - prismStartTime;
        
        // Test Octree k-NN performance
        long octreeStartTime = System.nanoTime();
        var octreeNeighbors = octree.kNearestNeighbors(queryPoint, k, Float.MAX_VALUE);
        long octreeKNNTime = System.nanoTime() - octreeStartTime;
        
        // Log performance comparison
        double knnRatio = (double)prismKNNTime / octreeKNNTime;
        System.out.printf("k-NN Performance Ratio (Prism/Octree): %.2fx%n", knnRatio);
        System.out.printf("Prism k-NN time: %.2f μs%n", prismKNNTime / 1_000.0);
        System.out.printf("Octree k-NN time: %.2f μs%n", octreeKNNTime / 1_000.0);
        
        // Both should find neighbors and complete quickly
        assertTrue(prismNeighbors.size() >= 1, "Prism should find at least 1 neighbor");
        assertTrue(octreeNeighbors.size() >= 1, "Octree should find at least 1 neighbor");
        assertTrue(prismKNNTime < 10_000_000L, "Prism k-NN took too long"); // 10ms limit
        assertTrue(octreeKNNTime < 10_000_000L, "Octree k-NN took too long");
    }
    
    @Test
    @DisabledIfEnvironmentVariable(
        named = "CI",
        matches = "true",
        disabledReason = "Performance test: 5ms threshold fails under CI load. Range query performance varies with system contention."
    )
    void testRangeQueryPerformanceComparison() {
        // Insert test entities
        var positions = generateValidPrismPositions(300);
        var contents = generateContents(300);
        
        prism.insertBatch(positions, contents, (byte)4);
        
        idGenerator = new SequentialLongIDGenerator();
        octree = new Octree<>(idGenerator, 100, (byte)21);
        octree.insertBatch(positions, contents, (byte)4);
        
        // Create search region
        var searchCube = new com.hellblazer.luciferase.lucien.Spatial.Cube(20.0f, 20.0f, 20.0f, 20.0f);
        
        // Test Prism range query performance
        long prismStartTime = System.nanoTime();
        var prismResults = prism.entitiesInRegion(searchCube);
        long prismRangeTime = System.nanoTime() - prismStartTime;
        
        // Test Octree range query performance
        long octreeStartTime = System.nanoTime();
        var octreeResults = octree.entitiesInRegion(searchCube);
        long octreeRangeTime = System.nanoTime() - octreeStartTime;
        
        // Log performance comparison
        double rangeRatio = (double)prismRangeTime / octreeRangeTime;
        System.out.printf("Range Query Performance Ratio (Prism/Octree): %.2fx%n", rangeRatio);
        System.out.printf("Prism range time: %.2f μs%n", prismRangeTime / 1_000.0);
        System.out.printf("Octree range time: %.2f μs%n", octreeRangeTime / 1_000.0);
        
        // Both should complete quickly
        assertTrue(prismRangeTime < 5_000_000L, "Prism range query took too long"); // 5ms limit
        assertTrue(octreeRangeTime < 5_000_000L, "Octree range query took too long");
        
        // Results should be reasonable (some entities found)
        System.out.printf("Prism found %d entities, Octree found %d entities%n", 
                         prismResults.size(), octreeResults.size());
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
        
        // Reset for Octree test
        idGenerator = new SequentialLongIDGenerator();
        octree = new Octree<>(idGenerator, 100, (byte)21);
        System.gc();
        long beforeOctreeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        octree.insertBatch(positions, contents, (byte)4);
        
        System.gc();
        long afterOctreeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long octreeMemoryUsage = afterOctreeMemory - beforeOctreeMemory;
        
        // Log memory comparison
        double memoryRatio = (double)prismMemoryUsage / octreeMemoryUsage;
        System.out.printf("Memory Usage Ratio (Prism/Octree): %.2fx%n", memoryRatio);
        System.out.printf("Prism memory usage: %.2f KB%n", prismMemoryUsage / 1024.0);
        System.out.printf("Octree memory usage: %.2f KB%n", octreeMemoryUsage / 1024.0);
        
        // Both should have reasonable memory usage (not more than 100MB for 2000 entities)
        assertTrue(prismMemoryUsage < 100_000_000L, "Prism memory usage excessive");
        assertTrue(octreeMemoryUsage < 100_000_000L, "Octree memory usage excessive");
    }
    
    @Test
    void testEntityCountConsistency() {
        // Test that both indices handle same operations consistently
        var positions = generateValidPrismPositions(100);
        var contents = generateContents(100);
        
        var prismIds = prism.insertBatch(positions, contents, (byte)5);
        
        idGenerator = new SequentialLongIDGenerator();
        octree = new Octree<>(idGenerator, 100, (byte)21);
        var octreeIds = octree.insertBatch(positions, contents, (byte)5);
        
        // Both should have same entity count
        assertEquals(prism.entityCount(), octree.entityCount());
        assertEquals(100, prism.entityCount());
        assertEquals(100, octree.entityCount());
        
        // Test removal consistency
        int removeCount = 10;
        for (int i = 0; i < removeCount; i++) {
            assertTrue(prism.removeEntity(prismIds.get(i)));
            assertTrue(octree.removeEntity(octreeIds.get(i)));
        }
        
        assertEquals(90, prism.entityCount());
        assertEquals(90, octree.entityCount());
        assertEquals(prism.entityCount(), octree.entityCount());
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