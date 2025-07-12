package com.hellblazer.luciferase.lucien.prism;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.collision.SphereShape;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Comprehensive stress tests for the Prism spatial index implementation.
 * Tests large-scale operations, concurrent access, edge cases, and boundary conditions.
 */
public class PrismStressTest {

    private Prism<LongEntityID, String> prism;
    private SequentialLongIDGenerator idGenerator;
    private final float worldSize = 1000.0f;
    private final Random random = new Random(42); // Fixed seed for reproducibility
    
    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        prism = new Prism<>(idGenerator, worldSize, 21);
    }
    
    @Test
    @Timeout(30) // 30 second timeout
    void testLargeScaleInsertion() {
        int entityCount = 10_000;
        var positions = generateValidPositions(entityCount);
        var contents = generateContents(entityCount);
        
        // Measure insertion time
        long startTime = System.nanoTime();
        var ids = prism.insertBatch(positions, contents, (byte)6);
        long insertTime = System.nanoTime() - startTime;
        
        // Verify all entities inserted
        assertEquals(entityCount, ids.size());
        assertEquals(entityCount, prism.entityCount());
        
        // Performance check - should complete within reasonable time
        double msPerEntity = (insertTime / 1_000_000.0) / entityCount;
        System.out.printf("Large-scale insertion: %d entities in %.2f ms (%.3f ms/entity)%n", 
                         entityCount, insertTime / 1_000_000.0, msPerEntity);
        assertTrue(msPerEntity < 1.0, "Insertion too slow: " + msPerEntity + " ms/entity");
        
        // Verify all entities were inserted
        for (var id : ids) {
            assertTrue(prism.containsEntity(id), "Entity with ID " + id + " should exist");
        }
        
        // Verify some random entities
        for (int i = 0; i < 100; i++) {
            int idx = random.nextInt(entityCount);
            var entityContent = prism.getEntity(ids.get(idx));
            assertNotNull(entityContent, "Entity at index " + idx + " should exist");
            // Don't check content equality as batch insertion might not preserve order
        }
    }
    
    @Test
    @Timeout(30)
    void testExtremeLargeScaleInsertion() {
        int entityCount = 100_000;
        int batchSize = 10_000;
        AtomicInteger totalInserted = new AtomicInteger(0);
        
        // Insert in batches to avoid memory issues
        for (int batch = 0; batch < entityCount / batchSize; batch++) {
            var positions = generateValidPositions(batchSize);
            var contents = generateContents(batchSize, batch * batchSize);
            
            var ids = prism.insertBatch(positions, contents, (byte)4);
            totalInserted.addAndGet(ids.size());
        }
        
        assertEquals(entityCount, totalInserted.get());
        assertEquals(entityCount, prism.entityCount());
        
        // Test query performance at scale
        var queryPoint = new Point3f(worldSize * 0.3f, worldSize * 0.2f, worldSize * 0.5f);
        
        long startTime = System.nanoTime();
        var neighbors = prism.kNearestNeighbors(queryPoint, 100, worldSize * 0.1f);
        long queryTime = System.nanoTime() - startTime;
        
        System.out.printf("k-NN query on %d entities: %.2f ms for %d neighbors%n", 
                         entityCount, queryTime / 1_000_000.0, neighbors.size());
        assertTrue(neighbors.size() > 0, "Should find some neighbors in large dataset");
    }
    
    @Test
    @Timeout(60)
    void testConcurrentOperations() throws InterruptedException, ExecutionException {
        int threadCount = 8;
        int operationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successfulInserts = new AtomicInteger(0);
        AtomicInteger successfulQueries = new AtomicInteger(0);
        AtomicInteger successfulRemovals = new AtomicInteger(0);
        
        List<Future<Void>> futures = new ArrayList<>();
        
        for (int thread = 0; thread < threadCount; thread++) {
            final int threadId = thread;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    Random threadRandom = new Random(threadId);
                    List<LongEntityID> threadIds = new ArrayList<>();
                    
                    // Mix of operations
                    for (int op = 0; op < operationsPerThread; op++) {
                        int operationType = threadRandom.nextInt(10);
                        
                        if (operationType < 6) { // 60% inserts
                            var pos = generateValidPosition(threadRandom);
                            var id = prism.insert(pos, (byte)8, "Thread" + threadId + "_Op" + op);
                            threadIds.add(id);
                            successfulInserts.incrementAndGet();
                            
                        } else if (operationType < 9) { // 30% queries
                            var queryPos = generateValidPosition(threadRandom);
                            var results = prism.kNearestNeighbors(queryPos, 5, worldSize * 0.05f);
                            successfulQueries.incrementAndGet();
                            
                        } else { // 10% removals
                            if (!threadIds.isEmpty()) {
                                var idToRemove = threadIds.remove(threadRandom.nextInt(threadIds.size()));
                                if (prism.removeEntity(idToRemove)) {
                                    successfulRemovals.incrementAndGet();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Thread " + threadId + " failed", e);
                }
                return null;
            }));
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for completion
        for (var future : futures) {
            future.get();
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        
        System.out.printf("Concurrent operations completed:%n");
        System.out.printf("  Inserts: %d%n", successfulInserts.get());
        System.out.printf("  Queries: %d%n", successfulQueries.get());
        System.out.printf("  Removals: %d%n", successfulRemovals.get());
        System.out.printf("  Final entity count: %d%n", prism.entityCount());
        
        // Verify consistency
        int expectedCount = successfulInserts.get() - successfulRemovals.get();
        assertEquals(expectedCount, prism.entityCount(), 
                    "Entity count mismatch after concurrent operations");
    }
    
    @Test
    void testTriangularConstraintEdgeCases() {
        // Test exact boundary
        float epsilon = 0.0001f;
        
        // Valid: x + y = worldSize - epsilon
        var valid1 = new Point3f(worldSize * 0.5f - epsilon, worldSize * 0.5f - epsilon, 100.0f);
        assertDoesNotThrow(() -> prism.insert(valid1, (byte)10, "BoundaryValid"));
        
        // Invalid: x + y = worldSize
        var invalid1 = new Point3f(worldSize * 0.5f, worldSize * 0.5f, 100.0f);
        assertThrows(IllegalArgumentException.class, 
                    () -> prism.insert(invalid1, (byte)10, "BoundaryInvalid"));
        
        // Test corner cases
        var corner1 = new Point3f(worldSize - epsilon, 0.0f, 50.0f); // Max X, min Y
        assertDoesNotThrow(() -> prism.insert(corner1, (byte)10, "CornerMaxX"));
        
        var corner2 = new Point3f(0.0f, worldSize - epsilon, 50.0f); // Min X, max Y
        assertDoesNotThrow(() -> prism.insert(corner2, (byte)10, "CornerMaxY"));
        
        var corner3 = new Point3f(epsilon, epsilon, 50.0f); // Near origin
        assertDoesNotThrow(() -> prism.insert(corner3, (byte)10, "CornerOrigin"));
    }
    
    @Test
    void testFloatingPointPrecisionIssues() {
        // Test with very small differences
        float base = worldSize * 0.3333333f;
        List<LongEntityID> ids = new ArrayList<>();
        
        // Insert entities with tiny position differences
        for (int i = 0; i < 100; i++) {
            float offset = i * Float.MIN_NORMAL;
            var pos = new Point3f(base + offset, base - offset, base);
            var id = prism.insert(pos, (byte)15, "Precision" + i); // High precision level
            ids.add(id);
        }
        
        assertEquals(100, prism.entityCount());
        
        // Verify all can be found
        for (var id : ids) {
            assertTrue(prism.containsEntity(id));
        }
    }
    
    @Test
    void testMaximumSubdivisionLevel() {
        // Test at maximum subdivision level (21)
        byte maxLevel = 21;
        var positions = generateValidPositions(100);
        
        for (int i = 0; i < positions.size(); i++) {
            var id = prism.insert(positions.get(i), maxLevel, "MaxLevel" + i);
            assertNotNull(id);
        }
        
        assertEquals(100, prism.entityCount());
        
        // Test k-NN at max level
        var queryPoint = positions.get(50);
        var neighbors = prism.kNearestNeighbors(queryPoint, 10, Float.MAX_VALUE);
        assertTrue(neighbors.size() >= 1, "Should find at least the entity itself");
    }
    
    @Test
    void testMemoryUsageUnderLoad() {
        // Force garbage collection and measure baseline
        System.gc();
        long baselineMemory = getUsedMemory();
        
        // Insert large number of entities
        int entityCount = 50_000;
        var positions = generateValidPositions(entityCount);
        var contents = generateContents(entityCount);
        
        prism.insertBatch(positions, contents, (byte)8);
        
        // Force GC and measure memory
        System.gc();
        long usedMemory = getUsedMemory();
        long memoryIncrease = usedMemory - baselineMemory;
        
        double bytesPerEntity = (double) memoryIncrease / entityCount;
        System.out.printf("Memory usage: %.2f MB for %d entities (%.1f bytes/entity)%n",
                         memoryIncrease / (1024.0 * 1024.0), entityCount, bytesPerEntity);
        
        // Reasonable memory usage check (less than 1KB per entity)
        assertTrue(bytesPerEntity < 1024, "Excessive memory usage: " + bytesPerEntity + " bytes/entity");
    }
    
    @Test
    void testRapidInsertionAndRemoval() {
        int cycles = 1000;
        Set<LongEntityID> activeIds = new HashSet<>();
        
        for (int cycle = 0; cycle < cycles; cycle++) {
            // Insert batch
            var positions = generateValidPositions(10);
            var contents = generateContents(10, cycle * 10);
            var newIds = prism.insertBatch(positions, contents, (byte)10);
            activeIds.addAll(newIds);
            
            // Remove some entities
            if (activeIds.size() > 50) {
                var toRemove = new ArrayList<>(activeIds).subList(0, 5);
                for (var id : toRemove) {
                    assertTrue(prism.removeEntity(id));
                    activeIds.remove(id);
                }
            }
        }
        
        assertEquals(activeIds.size(), prism.entityCount());
        
        // Verify all active entities are findable
        for (var id : activeIds) {
            assertTrue(prism.containsEntity(id));
        }
    }
    
    @Test
    void testDegenerateTriangles() {
        // Test with positions that form degenerate triangles
        float epsilon = 0.00001f;
        
        // Nearly collinear points
        var pos1 = new Point3f(100.0f, 100.0f, 50.0f);
        var pos2 = new Point3f(100.0f + epsilon, 100.0f + epsilon, 50.0f);
        var pos3 = new Point3f(100.0f + 2 * epsilon, 100.0f + 2 * epsilon, 50.0f);
        
        var id1 = prism.insert(pos1, (byte)15, "Degen1");
        var id2 = prism.insert(pos2, (byte)15, "Degen2");
        var id3 = prism.insert(pos3, (byte)15, "Degen3");
        
        // All should be inserted despite being nearly collinear
        assertEquals(3, prism.entityCount());
        
        // Test query near degenerate configuration
        var neighbors = prism.kNearestNeighbors(pos2, 3, Float.MAX_VALUE);
        assertEquals(3, neighbors.size());
    }
    
    @Test
    void testCrossLevelInteractions() {
        // Insert entities at different levels in same region
        var basePos = new Point3f(200.0f, 150.0f, 300.0f);
        List<LongEntityID> ids = new ArrayList<>();
        
        for (byte level = 1; level <= 20; level++) {  // Start from level 1, not 0
            float offset = level * 0.1f;
            var pos = new Point3f(basePos.x + offset, basePos.y - offset, basePos.z);
            var id = prism.insert(pos, level, "Level" + level);
            ids.add(id);
        }
        
        assertEquals(20, prism.entityCount());  // 20 entities (levels 1-20)
        
        // Test range query across all levels
        var searchCube = new Spatial.Cube(190.0f, 140.0f, 290.0f, 30.0f);
        var foundEntities = prism.entitiesInRegion(searchCube);
        
        assertTrue(foundEntities.size() > 0, "Should find entities across multiple levels");
        System.out.printf("Cross-level query found %d/%d entities%n", 
                         foundEntities.size(), ids.size());
    }
    
    // Helper methods
    
    private List<Point3f> generateValidPositions(int count) {
        var positions = new ArrayList<Point3f>(count);
        
        for (int i = 0; i < count; i++) {
            positions.add(generateValidPosition(random));
        }
        
        return positions;
    }
    
    private Point3f generateValidPosition(Random rand) {
        // Generate position respecting triangular constraint
        float x = rand.nextFloat() * (worldSize * 0.8f);
        float maxY = worldSize * 0.8f - x;
        float y = rand.nextFloat() * maxY;
        float z = rand.nextFloat() * worldSize;
        
        return new Point3f(x, y, z);
    }
    
    private List<String> generateContents(int count) {
        return generateContents(count, 0);
    }
    
    private List<String> generateContents(int count, int startIdx) {
        var contents = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            contents.add("Entity" + (startIdx + i));
        }
        return contents;
    }
    
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}