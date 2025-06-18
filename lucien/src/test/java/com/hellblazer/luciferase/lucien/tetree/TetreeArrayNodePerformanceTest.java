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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Performance comparison tests between Set-based and Array-based node storage
 *
 * @author hal.hildebrand
 */
class TetreeArrayNodePerformanceTest {

    private static final int WARM_UP_ITERATIONS = 1000;
    private static final int TEST_ITERATIONS = 10000;
    private static final int ENTITY_COUNT = 100;

    @Test
    void compareAddPerformance() {
        System.out.println("\n=== Add Performance Comparison ===");
        
        // Prepare entity IDs
        List<LongEntityID> entityIds = new ArrayList<>();
        for (int i = 0; i < ENTITY_COUNT; i++) {
            entityIds.add(new LongEntityID(i));
        }
        
        // Test Set-based node
        TetreeNodeImpl<LongEntityID> setNode = new TetreeNodeImpl<>(ENTITY_COUNT * 2);
        long setTime = measureAddPerformance(setNode, entityIds);
        
        // Test Array-based node
        TetreeArrayNode<LongEntityID> arrayNode = new TetreeArrayNode<>(ENTITY_COUNT * 2, ENTITY_COUNT);
        long arrayTime = measureAddPerformance(arrayNode, entityIds);
        
        System.out.printf("Set-based node: %d ns total, %.2f ns per add%n", 
                          setTime, (double) setTime / ENTITY_COUNT);
        System.out.printf("Array-based node: %d ns total, %.2f ns per add%n", 
                          arrayTime, (double) arrayTime / ENTITY_COUNT);
        System.out.printf("Array is %.2fx %s%n", 
                          (double) Math.max(setTime, arrayTime) / Math.min(setTime, arrayTime),
                          arrayTime < setTime ? "faster" : "slower");
    }

    @Test
    void compareContainsPerformance() {
        System.out.println("\n=== Contains Performance Comparison ===");
        
        // Prepare nodes with entities
        TetreeNodeImpl<LongEntityID> setNode = new TetreeNodeImpl<>(ENTITY_COUNT * 2);
        TetreeArrayNode<LongEntityID> arrayNode = new TetreeArrayNode<>(ENTITY_COUNT * 2, ENTITY_COUNT);
        
        for (int i = 0; i < ENTITY_COUNT; i++) {
            LongEntityID id = new LongEntityID(i);
            setNode.addEntity(id);
            arrayNode.addEntity(id);
        }
        
        // Test random lookups
        Random random = new Random(42);
        List<LongEntityID> lookupIds = new ArrayList<>();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            lookupIds.add(new LongEntityID(random.nextInt(ENTITY_COUNT * 2))); // Some hits, some misses
        }
        
        // Warm up
        for (int i = 0; i < WARM_UP_ITERATIONS; i++) {
            setNode.containsEntity(lookupIds.get(i % lookupIds.size()));
            arrayNode.containsEntity(lookupIds.get(i % lookupIds.size()));
        }
        
        // Test Set-based
        long setStart = System.nanoTime();
        for (LongEntityID id : lookupIds) {
            setNode.containsEntity(id);
        }
        long setTime = System.nanoTime() - setStart;
        
        // Test Array-based
        long arrayStart = System.nanoTime();
        for (LongEntityID id : lookupIds) {
            arrayNode.containsEntity(id);
        }
        long arrayTime = System.nanoTime() - arrayStart;
        
        System.out.printf("Set-based node: %d ns total, %.2f ns per lookup%n", 
                          setTime, (double) setTime / TEST_ITERATIONS);
        System.out.printf("Array-based node: %d ns total, %.2f ns per lookup%n", 
                          arrayTime, (double) arrayTime / TEST_ITERATIONS);
        System.out.printf("Set is %.2fx %s for contains (expected for O(1) hash lookups)%n", 
                          (double) Math.max(setTime, arrayTime) / Math.min(setTime, arrayTime),
                          setTime < arrayTime ? "faster" : "slower");
    }

    @Test
    void compareIterationPerformance() {
        System.out.println("\n=== Iteration Performance Comparison ===");
        
        // Prepare nodes with entities
        TetreeNodeImpl<LongEntityID> setNode = new TetreeNodeImpl<>(ENTITY_COUNT * 2);
        TetreeArrayNode<LongEntityID> arrayNode = new TetreeArrayNode<>(ENTITY_COUNT * 2, ENTITY_COUNT);
        
        for (int i = 0; i < ENTITY_COUNT; i++) {
            LongEntityID id = new LongEntityID(i);
            setNode.addEntity(id);
            arrayNode.addEntity(id);
        }
        
        // Warm up
        for (int i = 0; i < WARM_UP_ITERATIONS; i++) {
            for (LongEntityID id : setNode.getEntityIds()) {
                // Just iterate
            }
            for (LongEntityID id : arrayNode.getEntityIds()) {
                // Just iterate
            }
        }
        
        // Test Set-based iteration
        long setStart = System.nanoTime();
        long setSum = 0;
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            for (LongEntityID id : setNode.getEntityIds()) {
                setSum += id.value();
            }
        }
        long setTime = System.nanoTime() - setStart;
        
        // Test Array-based iteration
        long arrayStart = System.nanoTime();
        long arraySum = 0;
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            for (LongEntityID id : arrayNode.getEntityIds()) {
                arraySum += id.value();
            }
        }
        long arrayTime = System.nanoTime() - arrayStart;
        
        // Verify same results
        assert setSum == arraySum : "Iteration produced different results!";
        
        System.out.printf("Set-based node: %d ns total, %.2f ns per iteration%n", 
                          setTime, (double) setTime / TEST_ITERATIONS);
        System.out.printf("Array-based node: %d ns total, %.2f ns per iteration%n", 
                          arrayTime, (double) arrayTime / TEST_ITERATIONS);
        System.out.printf("Array is %.2fx %s for iteration (better cache locality)%n", 
                          (double) Math.max(setTime, arrayTime) / Math.min(setTime, arrayTime),
                          arrayTime < setTime ? "faster" : "slower");
    }

    @Test
    void compareMemoryEfficiency() {
        System.out.println("\n=== Memory Efficiency Comparison ===");
        
        // Estimate memory usage
        int entityCount = 1000;
        
        // Set-based: HashSet overhead + Entry objects
        // Rough estimate: 32 bytes per entry (Entry object) + HashSet overhead
        long setMemoryPerEntity = 32 + 16; // Entry + overhead
        long setTotalMemory = setMemoryPerEntity * entityCount;
        
        // Array-based: Array overhead + references
        // Rough estimate: 8 bytes per reference + array object overhead
        long arrayMemoryPerEntity = 8;
        long arrayTotalMemory = arrayMemoryPerEntity * entityCount + 24; // Array object overhead
        
        System.out.printf("Estimated memory for %d entities:%n", entityCount);
        System.out.printf("Set-based: ~%d bytes (%.2f bytes per entity)%n", 
                          setTotalMemory, (double) setTotalMemory / entityCount);
        System.out.printf("Array-based: ~%d bytes (%.2f bytes per entity)%n", 
                          arrayTotalMemory, (double) arrayTotalMemory / entityCount);
        System.out.printf("Array uses ~%.2fx less memory%n", 
                          (double) setTotalMemory / arrayTotalMemory);
        
        // Note: These are estimates. Actual memory usage depends on JVM implementation
        System.out.println("\nNote: Memory estimates are approximate and JVM-dependent");
    }

    private long measureAddPerformance(TetreeNodeImpl<LongEntityID> node, List<LongEntityID> entityIds) {
        // Warm up
        TetreeNodeImpl<LongEntityID> warmupNode = node instanceof TetreeArrayNode 
            ? new TetreeArrayNode<>(ENTITY_COUNT * 2, ENTITY_COUNT)
            : new TetreeNodeImpl<>(ENTITY_COUNT * 2);
            
        for (int i = 0; i < 10; i++) {
            warmupNode.clearEntities();
            for (LongEntityID id : entityIds) {
                warmupNode.addEntity(id);
            }
        }
        
        // Actual test
        node.clearEntities();
        long start = System.nanoTime();
        for (LongEntityID id : entityIds) {
            node.addEntity(id);
        }
        return System.nanoTime() - start;
    }

    private long measureAddPerformance(TetreeArrayNode<LongEntityID> node, List<LongEntityID> entityIds) {
        // Warm up
        TetreeArrayNode<LongEntityID> warmupNode = new TetreeArrayNode<>(ENTITY_COUNT * 2, ENTITY_COUNT);
        for (int i = 0; i < 10; i++) {
            warmupNode.clearEntities();
            for (LongEntityID id : entityIds) {
                warmupNode.addEntity(id);
            }
        }
        
        // Actual test
        node.clearEntities();
        long start = System.nanoTime();
        for (LongEntityID id : entityIds) {
            node.addEntity(id);
        }
        return System.nanoTime() - start;
    }
}