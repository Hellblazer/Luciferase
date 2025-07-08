/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.VolumeBounds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests demonstrating memory efficiency of lazy evaluation.
 *
 * @author hal.hildebrand
 */
public class LazyEvaluationMemoryTest {
    
    @Test
    void demonstrateLazyMemoryEfficiency() {
        System.out.println("\n=== Lazy Evaluation Memory Demonstration ===");
        
        // Create a large range
        byte level = 15;
        int cellSize = 1 << (21 - level);
        
        // Create start and end tets spanning a large range
        var startTet = new Tet(0, 0, 0, level, (byte)0);
        var endTet = new Tet(100 * cellSize, 100 * cellSize, 100 * cellSize, level, (byte)5);
        
        // Demonstrate lazy iterator memory usage
        System.gc();
        long beforeLazy = getUsedMemory();
        
        var lazyIterator = new LazyRangeIterator(startTet, endTet);
        long estimatedSize = lazyIterator.estimateSize();
        
        long afterLazy = getUsedMemory();
        
        System.out.printf("Lazy iterator created for range of ~%d keys%n", estimatedSize);
        System.out.printf("Memory used by lazy iterator: %d bytes%n", afterLazy - beforeLazy);
        
        // Demonstrate eager collection memory usage
        System.gc();
        long beforeEager = getUsedMemory();
        
        // Collect just first 10000 to avoid OOM
        List<TetreeKey<? extends TetreeKey>> eagerList = new ArrayList<>();
        int count = 0;
        while (lazyIterator.hasNext() && count < 10000) {
            eagerList.add(lazyIterator.next());
            count++;
        }
        
        long afterEager = getUsedMemory();
        
        System.out.printf("Eager list created with %d keys%n", eagerList.size());
        System.out.printf("Memory used by eager list: %d bytes%n", afterEager - beforeEager);
        System.out.printf("Memory per key: %.2f bytes%n", 
            (double)(afterEager - beforeEager) / eagerList.size());
        
        // Show memory savings
        long projectedEagerMemory = (afterEager - beforeEager) * estimatedSize / eagerList.size();
        System.out.printf("\nProjected memory for full eager collection: %.2f MB%n", 
            projectedEagerMemory / (1024.0 * 1024.0));
        System.out.printf("Lazy iterator memory: %.2f KB%n", 
            (afterLazy - beforeLazy) / 1024.0);
        System.out.printf("Memory savings: %.1f%%%n", 
            100.0 * (1 - (double)(afterLazy - beforeLazy) / projectedEagerMemory));
    }
    
    @Test
    void demonstrateEarlyTermination() {
        System.out.println("\n=== Early Termination Demonstration ===");
        
        byte level = 12;
        int cellSize = 1 << (21 - level);
        
        var startTet = new Tet(0, 0, 0, level, (byte)0);
        var endTet = new Tet(50 * cellSize, 50 * cellSize, 50 * cellSize, level, (byte)5);
        
        var range = new Tet.SFCRange(startTet.tmIndex(), endTet.tmIndex());
        
        // Time finding first 100 elements with lazy stream
        long lazyStart = System.nanoTime();
        var firstHundred = range.stream()
            .limit(100)
            .toList();
        long lazyTime = System.nanoTime() - lazyStart;
        
        // Compare with collecting all then limiting
        long eagerStart = System.nanoTime();
        var allKeys = new ArrayList<TetreeKey<? extends TetreeKey>>();
        var iter = range.iterator();
        while (iter.hasNext()) {
            allKeys.add(iter.next());
        }
        var eagerHundred = allKeys.stream()
            .limit(100)
            .toList();
        long eagerTime = System.nanoTime() - eagerStart;
        
        System.out.printf("Lazy approach (limit 100): %.2f ms%n", lazyTime / 1_000_000.0);
        System.out.printf("Eager approach (collect all %d then limit): %.2f ms%n", 
            allKeys.size(), eagerTime / 1_000_000.0);
        System.out.printf("Speedup: %.2fx%n", (double)eagerTime / lazyTime);
        
        // Note: Results may differ slightly due to range boundaries
        // The important metric is the performance difference
        System.out.printf("Lazy found %d elements, Eager found %d elements%n", 
            firstHundred.size(), eagerHundred.size());
    }
    
    @Test
    void demonstrateRangeHandleEfficiency() {
        System.out.println("\n=== Range Handle Efficiency ===");
        
        byte level = 10;
        int cellSize = 1 << (21 - level);
        
        // Create multiple range handles without materializing
        List<RangeHandle> handles = new ArrayList<>();
        
        System.gc();
        long beforeHandles = getUsedMemory();
        
        // Create 1000 range handles
        for (int i = 0; i < 1000; i++) {
            var bounds = new VolumeBounds(i * cellSize, 0, 0, 
                                         (i + 1) * cellSize, cellSize, cellSize);
            var rootTet = new Tet(i * cellSize, 0, 0, level, (byte)0);
            handles.add(new RangeHandle(rootTet, bounds, true, level));
        }
        
        long afterHandles = getUsedMemory();
        
        System.out.printf("Created %d range handles%n", handles.size());
        System.out.printf("Total memory for handles: %d bytes%n", afterHandles - beforeHandles);
        System.out.printf("Memory per handle: %.2f bytes%n", 
            (double)(afterHandles - beforeHandles) / handles.size());
        
        // Show that handles can be used on demand
        var sampleHandle = handles.get(500);
        long totalKeys = sampleHandle.stream().count();
        System.out.printf("Sample handle contains %d keys (computed on demand)%n", totalKeys);
    }
    
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}