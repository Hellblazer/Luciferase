package com.hellblazer.luciferase.portal.mesh.explorer;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import javafx.application.Platform;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;

/**
 * Comprehensive performance benchmarks for transform-based entity system.
 * Tests memory usage, update performance, and scalability.
 */
@RequiresJavaFX
public class TransformBasedEntityBenchmarkTest {
    
    @BeforeAll
    public static void initJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Already initialized
        }
    }
    
    @Test
    public void testEntityScalabilityBenchmark() {
        System.out.println("\n=== Transform-Based Entity Scalability Benchmark ===\n");
        
        int[] entityCounts = {100, 1000, 10000};
        
        for (int count : entityCounts) {
            System.out.printf("Testing %d entities:\n", count);
            benchmarkEntityCount(count);
            System.out.println();
        }
    }
    
    private void benchmarkEntityCount(int entityCount) {
        // Create transform-based system
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(1000);
        var entityManager = new TransformBasedEntity.EntityManager(
            transformManager, materialPool, 100.0, Math.min(entityCount / 10, 5000)
        );
        
        Random rand = new Random(42);
        List<String> entityIds = new ArrayList<>();
        List<Point3f> positions = new ArrayList<>();
        
        // Memory before entities
        System.gc();
        long memoryBefore = getUsedMemory();
        
        // Insertion benchmark
        long insertStart = System.nanoTime();
        
        for (int i = 0; i < entityCount; i++) {
            String id = "entity" + i;
            Point3f pos = new Point3f(
                rand.nextFloat() * 10000,
                rand.nextFloat() * 10000,
                rand.nextFloat() * 10000
            );
            
            entityIds.add(id);
            positions.add(pos);
            
            // Vary entity states
            Color color = getRandomColor(rand);
            boolean selected = rand.nextDouble() < 0.1;
            boolean hasContainer = rand.nextDouble() < 0.8;
            
            entityManager.updateEntity(id, pos, color, selected, hasContainer);
        }
        
        long insertEnd = System.nanoTime();
        double insertMs = (insertEnd - insertStart) / 1_000_000.0;
        
        // Memory after insertion
        System.gc();
        long memoryAfter = getUsedMemory();
        long memoryUsed = memoryAfter - memoryBefore;
        
        // Update benchmark
        long updateStart = System.nanoTime();
        int updates = entityCount * 10; // 10 updates per entity
        
        for (int i = 0; i < updates; i++) {
            int idx = rand.nextInt(entityCount);
            String id = entityIds.get(idx);
            Point3f oldPos = positions.get(idx);
            
            // Move entity slightly
            Point3f newPos = new Point3f(
                oldPos.x + rand.nextFloat() * 10 - 5,
                oldPos.y + rand.nextFloat() * 10 - 5,
                oldPos.z + rand.nextFloat() * 10 - 5
            );
            positions.set(idx, newPos);
            
            // Random state changes
            Color color = getRandomColor(rand);
            boolean selected = rand.nextDouble() < 0.1;
            boolean hasContainer = rand.nextDouble() < 0.8;
            
            entityManager.updateEntity(id, newPos, color, selected, hasContainer);
        }
        
        long updateEnd = System.nanoTime();
        double updateMs = (updateEnd - updateStart) / 1_000_000.0;
        
        // Removal benchmark
        long removeStart = System.nanoTime();
        
        for (String id : entityIds) {
            entityManager.removeEntity(id);
        }
        
        long removeEnd = System.nanoTime();
        double removeMs = (removeEnd - removeStart) / 1_000_000.0;
        
        // Report results
        System.out.printf("  Insertion: %.2f ms (%.0f entities/sec)\n", 
            insertMs, entityCount / insertMs * 1000);
        System.out.printf("  Updates: %.2f ms (%.0f updates/sec)\n", 
            updateMs, updates / updateMs * 1000);
        System.out.printf("  Removal: %.2f ms (%.0f entities/sec)\n", 
            removeMs, entityCount / removeMs * 1000);
        System.out.printf("  Memory: %.2f MB (%.0f bytes/entity)\n", 
            memoryUsed / 1_048_576.0, (double) memoryUsed / entityCount);
        System.out.println("  " + entityManager.getStatistics());
        System.out.println("  Material pool size: " + materialPool.getPoolSize());
    }
    
    @Test
    public void testMemoryEfficiencyComparison() {
        System.out.println("\n=== Memory Efficiency Comparison ===\n");
        
        int entityCount = 5000;
        
        // Traditional approach (simulated)
        System.gc();
        long traditionalMemBefore = getUsedMemory();
        
        // Simulate traditional spheres
        List<DummySphere> traditionalSpheres = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            traditionalSpheres.add(new DummySphere());
        }
        
        System.gc();
        long traditionalMemAfter = getUsedMemory();
        long traditionalMemUsed = traditionalMemAfter - traditionalMemBefore;
        
        // Clear traditional objects
        traditionalSpheres.clear();
        System.gc();
        
        // Transform-based approach
        long transformMemBefore = getUsedMemory();
        
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(1000);
        var entityManager = new TransformBasedEntity.EntityManager(
            transformManager, materialPool, 100.0, 1000
        );
        
        Random rand = new Random(42);
        for (int i = 0; i < entityCount; i++) {
            Point3f pos = new Point3f(
                rand.nextFloat() * 10000,
                rand.nextFloat() * 10000,
                rand.nextFloat() * 10000
            );
            entityManager.updateEntity("entity" + i, pos, Color.LIME, false, true);
        }
        
        System.gc();
        long transformMemAfter = getUsedMemory();
        long transformMemUsed = transformMemAfter - transformMemBefore;
        
        // Report comparison
        System.out.printf("Traditional approach (%d entities):\n", entityCount);
        System.out.printf("  Total memory: %.2f MB\n", traditionalMemUsed / 1_048_576.0);
        System.out.printf("  Per entity: %.0f bytes\n", (double) traditionalMemUsed / entityCount);
        
        System.out.printf("\nTransform-based approach (%d entities):\n", entityCount);
        System.out.printf("  Total memory: %.2f MB\n", transformMemUsed / 1_048_576.0);
        System.out.printf("  Per entity: %.0f bytes\n", (double) transformMemUsed / entityCount);
        System.out.println("  " + entityManager.getStatistics());
        
        double savings = (1.0 - (double) transformMemUsed / traditionalMemUsed) * 100;
        System.out.printf("\nMemory savings: %.1f%%\n", savings);
    }
    
    @Test
    public void testConcurrentUpdateStress() {
        System.out.println("\n=== Concurrent Update Stress Test ===\n");
        
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(1000);
        var entityManager = new TransformBasedEntity.EntityManager(
            transformManager, materialPool, 100.0, 5000
        );
        
        int entityCount = 1000;
        int threadCount = 4;
        int updatesPerThread = 10000;
        
        // Create initial entities
        Random rand = new Random(42);
        for (int i = 0; i < entityCount; i++) {
            Point3f pos = new Point3f(
                rand.nextFloat() * 10000,
                rand.nextFloat() * 10000,
                rand.nextFloat() * 10000
            );
            entityManager.updateEntity("entity" + i, pos, Color.LIME, false, true);
        }
        
        // Concurrent update test
        List<Thread> threads = new ArrayList<>();
        long startTime = System.nanoTime();
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            Thread thread = new Thread(() -> {
                Random threadRand = new Random(threadId);
                for (int i = 0; i < updatesPerThread; i++) {
                    int entityIdx = threadRand.nextInt(entityCount);
                    String id = "entity" + entityIdx;
                    Point3f pos = new Point3f(
                        threadRand.nextFloat() * 10000,
                        threadRand.nextFloat() * 10000,
                        threadRand.nextFloat() * 10000
                    );
                    Color color = getRandomColor(threadRand);
                    entityManager.updateEntity(id, pos, color, false, true);
                }
            });
            threads.add(thread);
            thread.start();
        }
        
        // Wait for all threads
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        long endTime = System.nanoTime();
        double totalMs = (endTime - startTime) / 1_000_000.0;
        int totalUpdates = threadCount * updatesPerThread;
        
        System.out.printf("Concurrent updates: %d threads × %d updates\n", 
            threadCount, updatesPerThread);
        System.out.printf("Total time: %.2f ms\n", totalMs);
        System.out.printf("Updates per second: %.0f\n", totalUpdates / totalMs * 1000);
        System.out.println("Final state: " + entityManager.getStatistics());
        System.out.println("Material pool size: " + materialPool.getPoolSize());
    }
    
    @Test
    public void testRealWorldScenario() {
        System.out.println("\n=== Real-World Scenario Test ===\n");
        
        // Simulate a real tetree with entities
        Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(1000);
        var entityManager = new TransformBasedEntity.EntityManager(
            transformManager, materialPool, 3276.8, 5000
        );
        
        Random rand = new Random(42);
        int entityCount = 2000;
        List<LongEntityID> entityIds = new ArrayList<>();
        
        System.out.println("Simulating real-world entity lifecycle:");
        
        // Phase 1: Initial population
        long phase1Start = System.nanoTime();
        for (int i = 0; i < entityCount / 2; i++) {
            float scale = (float) Math.pow(2, 18);
            Point3f pos = new Point3f(
                rand.nextFloat() * scale + scale,
                rand.nextFloat() * scale + scale,
                rand.nextFloat() * scale + scale
            );
            LongEntityID id = tetree.insert(pos, (byte) 5, "Entity " + i);
            entityIds.add(id);
            entityManager.updateEntity(id, pos, Color.LIME, false, true);
        }
        long phase1End = System.nanoTime();
        
        // Phase 2: Movement and state changes
        long phase2Start = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            int idx = rand.nextInt(entityIds.size());
            LongEntityID id = entityIds.get(idx);
            Point3f oldPos = tetree.getEntityPosition(id);
            
            // Small movement
            Point3f newPos = new Point3f(
                oldPos.x + rand.nextFloat() * 1000 - 500,
                oldPos.y + rand.nextFloat() * 1000 - 500,
                oldPos.z + rand.nextFloat() * 1000 - 500
            );
            
            tetree.updateEntity(id, newPos, (byte) 5);
            
            // State changes
            boolean selected = rand.nextDouble() < 0.05;
            boolean collision = rand.nextDouble() < 0.1;
            Color color = collision ? Color.RED : (selected ? Color.YELLOW : Color.LIME);
            
            entityManager.updateEntity(id, newPos, color, selected, !collision);
        }
        long phase2End = System.nanoTime();
        
        // Phase 3: Add more entities
        long phase3Start = System.nanoTime();
        for (int i = entityCount / 2; i < entityCount; i++) {
            float scale = (float) Math.pow(2, 18);
            Point3f pos = new Point3f(
                rand.nextFloat() * scale + scale,
                rand.nextFloat() * scale + scale,
                rand.nextFloat() * scale + scale
            );
            LongEntityID id = tetree.insert(pos, (byte) 5, "Entity " + i);
            entityIds.add(id);
            entityManager.updateEntity(id, pos, Color.BLUE, false, true);
        }
        long phase3End = System.nanoTime();
        
        // Phase 4: Remove some entities
        long phase4Start = System.nanoTime();
        Collections.shuffle(entityIds, rand);
        for (int i = 0; i < entityCount / 4; i++) {
            LongEntityID id = entityIds.get(i);
            tetree.removeEntity(id);
            entityManager.removeEntity(id);
        }
        long phase4End = System.nanoTime();
        
        // Report results
        System.out.printf("Phase 1 - Initial population (%d entities): %.2f ms\n",
            entityCount / 2, (phase1End - phase1Start) / 1_000_000.0);
        System.out.printf("Phase 2 - Movement and states (5000 updates): %.2f ms\n",
            (phase2End - phase2Start) / 1_000_000.0);
        System.out.printf("Phase 3 - Add more entities (%d entities): %.2f ms\n",
            entityCount / 2, (phase3End - phase3Start) / 1_000_000.0);
        System.out.printf("Phase 4 - Remove entities (%d entities): %.2f ms\n",
            entityCount / 4, (phase4End - phase4Start) / 1_000_000.0);
        
        System.out.println("\nFinal state:");
        System.out.println("  Tetree size: " + tetree.size());
        System.out.println("  " + entityManager.getStatistics());
        System.out.println("  Material pool size: " + materialPool.getPoolSize());
        System.out.println("  Transform cache: " + transformManager.getStatistics());
    }
    
    private Color getRandomColor(Random rand) {
        Color[] colors = {Color.LIME, Color.RED, Color.BLUE, Color.YELLOW, 
                         Color.ORANGE, Color.PURPLE, Color.CYAN};
        return colors[rand.nextInt(colors.length)];
    }
    
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
    
    // Dummy class to simulate traditional sphere memory usage
    private static class DummySphere {
        private final float[] vertices = new float[1000]; // Simulate mesh data
        private final Object material = new Object(); // Simulate material
        private final double[] transform = new double[16]; // Transform matrix
        private final Object userData = new Object(); // User data
        
        public DummySphere() {
            // Initialize arrays to prevent optimization
            Arrays.fill(vertices, 1.0f);
            Arrays.fill(transform, 1.0);
        }
    }
}
