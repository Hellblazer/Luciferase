/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.collision.SphereShape;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify ray intersection optimizations with ObjectPools
 */
public class RayIntersectionOptimizationTest {
    
    @Test
    void testRayIntersectionWithObjectPools() {
        // Create octree with many entities
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var random = new Random(42);
        
        // Insert entities in a grid pattern for predictable ray hits
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                for (int z = 0; z < 20; z++) {
                    var pos = new Point3f(x * 5.0f, y * 5.0f, z * 5.0f);
                    var entityId = new LongEntityID(x * 400 + y * 20 + z);
                    octree.insert(entityId, pos, (byte) 10, "Entity-" + entityId);
                    octree.setCollisionShape(entityId, new SphereShape(pos, 1.0f));
                }
            }
        }
        
        // Measure memory before ray intersection operations
        System.gc();
        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        // Perform ray intersection operations
        long startTime = System.nanoTime();
        int totalIntersections = 0;
        
        // Test rayIntersectAll - shooting rays through the grid
        for (int i = 0; i < 100; i++) {
            var origin = new Point3f(
                -10.0f,
                random.nextFloat() * 100,
                random.nextFloat() * 100
            );
            var direction = new Vector3f(1.0f, 0.0f, 0.0f); // Shoot along X axis
            direction.normalize();
            
            var ray = new Ray3D(origin, direction, 150.0f);
            var hits = octree.rayIntersectAll(ray);
            totalIntersections += hits.size();
        }
        
        // Test rayIntersectFirst - finding nearest intersections
        for (int i = 0; i < 100; i++) {
            var origin = new Point3f(
                random.nextFloat() * 100,
                random.nextFloat() * 100,
                -10.0f
            );
            var direction = new Vector3f(0.0f, 0.0f, 1.0f); // Shoot along Z axis
            direction.normalize();
            
            var ray = new Ray3D(origin, direction);
            var hit = octree.rayIntersectFirst(ray);
            if (hit.isPresent()) {
                totalIntersections++;
            }
        }
        
        // Test rayIntersectWithin - limited distance rays
        for (int i = 0; i < 100; i++) {
            var origin = new Point3f(
                random.nextFloat() * 100,
                -10.0f,
                random.nextFloat() * 100
            );
            var direction = new Vector3f(0.0f, 1.0f, 0.0f); // Shoot along Y axis
            direction.normalize();
            
            var ray = new Ray3D(origin, direction);
            var hits = octree.rayIntersectWithin(ray, 50.0f);
            totalIntersections += hits.size();
        }
        
        long endTime = System.nanoTime();
        
        // Measure memory after
        System.gc();
        long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        double timeMs = (endTime - startTime) / 1_000_000.0;
        double memIncreaseMB = (memAfter - memBefore) / (1024.0 * 1024.0);
        
        System.out.println("Ray Intersection Performance with ObjectPools:");
        System.out.println("  300 ray operations completed in: " + String.format("%.2f ms", timeMs));
        System.out.println("  Total intersections found: " + totalIntersections);
        System.out.println("  Memory increase: " + String.format("%.2f MB", memIncreaseMB));
        System.out.println("  Average time per ray: " + String.format("%.3f ms", timeMs / 300));
        
        // Verify reasonable performance
        assertTrue(timeMs < 5000, "Ray intersection should complete in under 5 seconds");
        assertTrue(memIncreaseMB < 20, "Memory increase should be minimal with object pooling");
        assertTrue(totalIntersections > 0, "Should find some intersections");
    }
    
    @Test 
    void testConcurrentRayIntersections() {
        var tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        var random = new Random(42);
        
        // Populate with entities
        for (int i = 0; i < 1000; i++) {
            var pos = new Point3f(
                random.nextFloat() * 50,
                random.nextFloat() * 50,
                random.nextFloat() * 50
            );
            var entityId = new LongEntityID(i);
            tetree.insert(entityId, pos, (byte) 10, "Entity-" + i);
        }
        
        // Test concurrent ray intersections
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
                    
                    for (int i = 0; i < 100; i++) {
                        var origin = new Point3f(
                            threadRandom.nextFloat() * 50,
                            threadRandom.nextFloat() * 50,
                            threadRandom.nextFloat() * 50
                        );
                        var direction = new Vector3f(
                            threadRandom.nextFloat() - 0.5f,
                            threadRandom.nextFloat() - 0.5f,
                            threadRandom.nextFloat() - 0.5f
                        );
                        direction.normalize();
                        
                        var ray = new Ray3D(origin, direction, 100.0f);
                        
                        if (i % 3 == 0) {
                            tetree.rayIntersectAll(ray);
                        } else if (i % 3 == 1) {
                            tetree.rayIntersectFirst(ray);
                        } else {
                            tetree.rayIntersectWithin(ray, 25.0f);
                        }
                        
                        totalOps.incrementAndGet();
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
        
        System.out.println("Concurrent ray intersection:");
        System.out.println("  " + totalOps.get() + " operations in " + String.format("%.2f ms", timeMs));
        System.out.println("  " + String.format("%.0f rays/sec", opsPerSec));
        
        assertEquals(0, errors.get(), "No errors should occur during concurrent ray intersection");
    }
}