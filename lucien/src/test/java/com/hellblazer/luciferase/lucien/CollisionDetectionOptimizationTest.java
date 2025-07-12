/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.collision.CollisionShape;
import com.hellblazer.luciferase.lucien.collision.SphereShape;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify collision detection optimizations with ObjectPools
 */
public class CollisionDetectionOptimizationTest {
    
    @Test
    void testCollisionDetectionWithObjectPools() {
        // Create octree with collision shapes
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var random = new Random(42);
        
        // Insert entities with collision shapes
        for (int i = 0; i < 1000; i++) {
            var pos = new Point3f(
                random.nextFloat() * 100,
                random.nextFloat() * 100,
                random.nextFloat() * 100
            );
            var entityId = new LongEntityID(i);
            octree.insert(entityId, pos, (byte) 10, "Entity-" + i);
            octree.setCollisionShape(entityId, new SphereShape(pos, 2.0f)); // 2 unit radius spheres
        }
        
        // Measure memory before collision detection
        System.gc();
        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        // Perform collision detection operations
        long startTime = System.nanoTime();
        int totalCollisions = 0;
        
        // Test findAllCollisions
        for (int i = 0; i < 10; i++) {
            var collisions = octree.findAllCollisions();
            totalCollisions += collisions.size();
        }
        
        // Test findCollisions for specific entities
        for (int i = 0; i < 100; i++) {
            var collisions = octree.findCollisions(new LongEntityID(i));
            totalCollisions += collisions.size();
        }
        
        // Test region-based collision detection
        for (int i = 0; i < 20; i++) {
            var center = new Point3f(
                random.nextFloat() * 100,
                random.nextFloat() * 100,
                random.nextFloat() * 100
            );
            var region = new Spatial.Cube(
                center.x - 10, center.y - 10, center.z - 10, 20  // 20x20x20 cube
            );
            var collisions = octree.findCollisionsInRegion(region);
            totalCollisions += collisions.size();
        }
        
        long endTime = System.nanoTime();
        
        // Measure memory after
        System.gc();
        long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        double timeMs = (endTime - startTime) / 1_000_000.0;
        double memIncreaseMB = (memAfter - memBefore) / (1024.0 * 1024.0);
        
        System.out.println("Collision Detection Performance with ObjectPools:");
        System.out.println("  130 collision operations completed in: " + String.format("%.2f ms", timeMs));
        System.out.println("  Total collisions found: " + totalCollisions);
        System.out.println("  Memory increase: " + String.format("%.2f MB", memIncreaseMB));
        System.out.println("  Average time per operation: " + String.format("%.2f ms", timeMs / 130));
        
        // Verify reasonable performance
        assertTrue(timeMs < 5000, "Collision detection should complete in under 5 seconds");
        assertTrue(memIncreaseMB < 20, "Memory increase should be minimal with object pooling");
        assertTrue(totalCollisions > 0, "Should find some collisions with overlapping spheres");
    }
    
    @Test
    void testConcurrentCollisionDetection() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var random = new Random(42);
        
        // Populate with entities
        for (int i = 0; i < 500; i++) {
            var pos = new Point3f(
                random.nextFloat() * 50,
                random.nextFloat() * 50,
                random.nextFloat() * 50
            );
            var entityId = new LongEntityID(i);
            octree.insert(entityId, pos, (byte) 10, "Entity-" + i);
            octree.setCollisionShape(entityId, new SphereShape(pos, 3.0f)); // Larger spheres for more collisions
        }
        
        // Test concurrent collision detection
        int numThreads = 10;
        var threads = new Thread[numThreads];
        var errors = new java.util.concurrent.atomic.AtomicInteger(0);
        var totalOps = new java.util.concurrent.atomic.AtomicLong(0);
        
        long startTime = System.nanoTime();
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                try {
                    var threadRandom = new Random(threadId);
                    
                    for (int i = 0; i < 50; i++) {
                        if (i % 3 == 0) {
                            // Find all collisions
                            octree.findAllCollisions();
                            totalOps.incrementAndGet();
                        } else if (i % 3 == 1) {
                            // Find specific entity collisions
                            var id = new LongEntityID(threadRandom.nextInt(500));
                            octree.findCollisions(id);
                            totalOps.incrementAndGet();
                        } else {
                            // Find region collisions
                            var center = new Point3f(
                                threadRandom.nextFloat() * 50,
                                threadRandom.nextFloat() * 50,
                                threadRandom.nextFloat() * 50
                            );
                            var region = new Spatial.Cube(
                                center.x - 5, center.y - 5, center.z - 5, 10  // 10x10x10 cube
                            );
                            octree.findCollisionsInRegion(region);
                            totalOps.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    errors.incrementAndGet();
                }
            });
            threads[t].start();
        }
        
        // Wait for all threads
        for (var thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                fail("Thread interrupted");
            }
        }
        
        long endTime = System.nanoTime();
        double timeMs = (endTime - startTime) / 1_000_000.0;
        double opsPerSec = totalOps.get() * 1000.0 / timeMs;
        
        System.out.println("Concurrent collision detection:");
        System.out.println("  " + totalOps.get() + " operations in " + String.format("%.2f ms", timeMs));
        System.out.println("  " + String.format("%.0f ops/sec", opsPerSec));
        
        assertEquals(0, errors.get(), "No errors should occur during concurrent collision detection");
    }
}