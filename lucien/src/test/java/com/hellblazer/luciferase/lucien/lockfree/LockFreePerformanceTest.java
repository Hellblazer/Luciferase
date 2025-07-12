/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.lockfree;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.lockfree.LockFreeEntityMover.MovementConfig;
import com.hellblazer.luciferase.lucien.lockfree.LockFreeEntityMover.MovementResult;
import com.hellblazer.luciferase.lucien.lockfree.VersionedEntityState.AtomicVersionedState;
import com.hellblazer.luciferase.lucien.lockfree.VersionedEntityState.MovementState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for lock-free entity movement operations.
 * Validates throughput, latency, and scalability under concurrent load.
 * 
 * @author hal.hildebrand
 */
public class LockFreePerformanceTest {

    private ConcurrentSkipListMap<MortonKey, AtomicSpatialNode<LongEntityID>> spatialIndex;
    private LockFreeEntityMover<MortonKey, LongEntityID, String> entityMover;
    private List<AtomicVersionedState<MortonKey, LongEntityID, String>> entities;

    @BeforeEach
    void setUp() {
        spatialIndex = new ConcurrentSkipListMap<>();
        
        // Simple position to keys function for testing
        java.util.function.Function<Point3f, Set<MortonKey>> positionToKeys = (Point3f pos) -> {
            var morton = new MortonKey(Constants.calculateMortonIndex(pos, (byte) 10));
            return Set.of(morton);
        };

        var config = new MovementConfig(10, 1000, 1_000_000, true);
        entityMover = new LockFreeEntityMover<>(spatialIndex, positionToKeys, config);
        
        entities = new ArrayList<>();
    }

    @Test
    void testSingleThreadedPerformance() {
        System.out.println("=== Single-Threaded Performance Test ===");
        
        int entityCount = 1000; // Reduced for faster testing
        prepareEntities(entityCount);
        
        long startTime = System.nanoTime();
        
        // Perform movements
        for (int i = 0; i < entityCount; i++) {
            var newPos = randomPosition();
            var result = entityMover.moveEntity(entities.get(i), newPos);
            assertEquals(MovementResult.SUCCESS, result, "Movement should succeed");
        }
        
        long duration = System.nanoTime() - startTime;
        double throughput = (double) entityCount / (duration / 1_000_000_000.0);
        
        System.out.printf("Single-threaded throughput: %.0f movements/sec%n", throughput);
        System.out.printf("Average latency: %.3f ms%n", (duration / 1_000_000.0) / entityCount);
        
        var stats = entityMover.getStats();
        System.out.println("Movement stats: " + stats);
        
        assertTrue(throughput > 100, "Should achieve at least 100 movements/sec");
    }

    @Test
    void testConcurrentPerformance() throws InterruptedException {
        System.out.println("=== Concurrent Performance Test ===");
        
        int entityCount = 500; // Reduced for faster testing
        int threadCount = 4;
        prepareEntities(entityCount);
        
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);
        var successCount = new AtomicLong(0);
        var startTime = System.nanoTime();
        
        // Distribute entities across threads
        int entitiesPerThread = entityCount / threadCount;
        
        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            executor.submit(() -> {
                try {
                    int startIdx = threadIndex * entitiesPerThread;
                    int endIdx = Math.min(startIdx + entitiesPerThread, entityCount);
                    
                    for (int i = startIdx; i < endIdx; i++) {
                        var newPos = randomPosition();
                        var result = entityMover.moveEntity(entities.get(i), newPos);
                        if (result == MovementResult.SUCCESS) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test should complete within 30 seconds");
        executor.shutdown();
        
        long duration = System.nanoTime() - startTime;
        double throughput = (double) successCount.get() / (duration / 1_000_000_000.0);
        
        System.out.printf("Concurrent throughput: %.0f movements/sec%n", throughput);
        System.out.printf("Success rate: %.1f%%n", (double) successCount.get() / entityCount * 100);
        
        var stats = entityMover.getStats();
        System.out.println("Movement stats: " + stats);
        
        assertTrue(successCount.get() > entityCount * 0.90, "Should have >90% success rate");
        assertTrue(throughput > 50, "Should achieve at least 50 movements/sec concurrently");
    }

    @Test
    void testContentUpdatePerformance() {
        System.out.println("=== Content Update Performance Test ===");
        
        int entityCount = 1000; // Reduced for faster testing
        prepareEntities(entityCount);
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < entityCount; i++) {
            var result = entityMover.updateContent(entities.get(i), "Updated content " + i);
            assertEquals(MovementResult.SUCCESS, result, "Content update should succeed");
        }
        
        long duration = System.nanoTime() - startTime;
        double throughput = (double) entityCount / (duration / 1_000_000_000.0);
        
        System.out.printf("Content update throughput: %.0f updates/sec%n", throughput);
        System.out.printf("Average latency: %.3f ms%n", (duration / 1_000_000.0) / entityCount);
        
        assertTrue(throughput > 1000, "Should achieve at least 1K content updates/sec");
    }

    @Test
    void testMemoryEfficiency() {
        System.out.println("=== Memory Efficiency Test ===");
        
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        int entityCount = 5000; // Reduced for faster testing
        prepareEntities(entityCount);
        
        // Perform some movements to populate spatial index
        for (int i = 0; i < entityCount / 10; i++) {
            var newPos = randomPosition();
            entityMover.moveEntity(entities.get(i), newPos);
        }
        
        runtime.gc();
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryUsed = finalMemory - initialMemory;
        
        double bytesPerEntity = (double) memoryUsed / entityCount;
        
        System.out.printf("Total memory used: %.2f MB%n", memoryUsed / (1024.0 * 1024.0));
        System.out.printf("Memory per entity: %.1f bytes%n", bytesPerEntity);
        System.out.printf("Spatial index size: %d nodes%n", spatialIndex.size());
        
        assertTrue(bytesPerEntity < 2000, "Should use less than 2000 bytes per entity");
    }

    private void prepareEntities(int count) {
        entities.clear();
        spatialIndex.clear();
        
        for (int i = 0; i < count; i++) {
            var entityId = new LongEntityID(i);
            var position = randomPosition();
            var content = "Content " + i;
            
            var state = new VersionedEntityState<MortonKey, LongEntityID, String>(entityId, position, content, Set.of(), MovementState.STABLE);
            var atomicState = new AtomicVersionedState<>(state);
            
            entities.add(atomicState);
        }
    }

    private Point3f randomPosition() {
        var random = ThreadLocalRandom.current();
        return new Point3f(
            random.nextFloat() * 100,
            random.nextFloat() * 100,
            random.nextFloat() * 100
        );
    }
}