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
package com.hellblazer.luciferase.lucien.profiler;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SpatialIndexProfiler
 */
public class SpatialIndexProfilerTest {

    @Test
    public void testBasicProfiling() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var profiler = new SpatialIndexProfiler<>(octree);
        
        // Profile insertions
        for (int i = 0; i < 100; i++) {
            final int index = i;
            profiler.profileVoid(SpatialIndexProfiler.OperationType.INSERT, () -> {
                octree.insert(new Point3f(index * 10, index * 10, index * 10), (byte) 3, "Entity " + index);
            });
        }
        
        // Profile queries
        for (int i = 0; i < 50; i++) {
            final int index = i;
            var result = profiler.profile(SpatialIndexProfiler.OperationType.QUERY_POINT, () -> {
                return octree.entitiesInRegion(new Spatial.Cube(index * 10 - 1, index * 10 - 1, index * 10 - 1, 2));
            });
            assertNotNull(result);
        }
        
        // Generate report
        var report = profiler.generateReport();
        
        // Verify statistics
        var insertReport = report.operationReports.get(SpatialIndexProfiler.OperationType.INSERT);
        assertNotNull(insertReport);
        assertEquals(100, insertReport.count);
        assertTrue(insertReport.avgTimeMillis >= 0);
        assertTrue(insertReport.minTimeMillis >= 0);
        assertTrue(insertReport.maxTimeMillis >= insertReport.minTimeMillis);
        
        var queryReport = report.operationReports.get(SpatialIndexProfiler.OperationType.QUERY_POINT);
        assertNotNull(queryReport);
        assertEquals(50, queryReport.count);
        
        // Print the report
        System.out.println("Basic Profiling Test:");
        System.out.println(report);
    }

    @Test
    public void testCustomCounters() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var profiler = new SpatialIndexProfiler<>(octree);
        
        // Track custom events
        profiler.incrementCounter("cache_hits");
        profiler.incrementCounter("cache_hits");
        profiler.incrementCounter("cache_misses");
        
        assertEquals(2, profiler.getCounter("cache_hits"));
        assertEquals(1, profiler.getCounter("cache_misses"));
        assertEquals(0, profiler.getCounter("non_existent"));
        
        var report = profiler.generateReport();
        assertEquals(2L, report.customCounters.get("cache_hits"));
        assertEquals(1L, report.customCounters.get("cache_misses"));
    }

    @Test
    public void testPerformanceComparison() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGenerator, 10, (byte) 5);
        var tetree = new Tetree<LongEntityID, String>(idGenerator, 10, (byte) 5);
        
        var octreeProfiler = new SpatialIndexProfiler<>(octree);
        var tetreeProfiler = new SpatialIndexProfiler<>(tetree);
        
        // Set sampling rate for more accurate measurements
        octreeProfiler.setSamplingRate(10);
        tetreeProfiler.setSamplingRate(10);
        
        // Generate test data
        var random = new Random(42);
        var points = new ArrayList<Point3f>();
        for (int i = 0; i < 1000; i++) {
            points.add(new Point3f(
                random.nextFloat() * 1000,
                random.nextFloat() * 1000,
                random.nextFloat() * 1000
            ));
        }
        
        // Profile insertions for both
        System.out.println("\nInserting 1000 points...");
        
        for (int i = 0; i < points.size(); i++) {
            final var point = points.get(i);
            final var content = "Entity " + i;
            
            octreeProfiler.profileVoid(SpatialIndexProfiler.OperationType.INSERT, () -> {
                octree.insert(point, (byte) 4, content);
            });
            
            tetreeProfiler.profileVoid(SpatialIndexProfiler.OperationType.INSERT, () -> {
                tetree.insert(point, (byte) 4, content);
            });
        }
        
        // Profile k-NN queries
        System.out.println("Performing k-NN queries...");
        
        for (int i = 0; i < 100; i++) {
            final var queryPoint = points.get(i * 10);
            
            octreeProfiler.profile(SpatialIndexProfiler.OperationType.QUERY_KNN, () -> {
                return octree.kNearestNeighbors(queryPoint, 10, 1000f);
            });
            
            tetreeProfiler.profile(SpatialIndexProfiler.OperationType.QUERY_KNN, () -> {
                return tetree.kNearestNeighbors(queryPoint, 10, 1000f);
            });
        }
        
        // Compare reports
        System.out.println("\n=== OCTREE PERFORMANCE ===");
        octreeProfiler.printReport();
        
        System.out.println("\n=== TETREE PERFORMANCE ===");
        tetreeProfiler.printReport();
    }

    @Test
    public void testMemoryProfiling() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, byte[]>(idGenerator);
        var profiler = new SpatialIndexProfiler<>(octree);
        
        // Enable memory profiling
        profiler.setSamplingRate(1); // Sample every operation
        
        // Insert large content to see memory impact
        for (int i = 0; i < 10; i++) {
            final int index = i;
            profiler.profileVoid(SpatialIndexProfiler.OperationType.INSERT, () -> {
                var largeContent = new byte[1024 * 1024]; // 1MB
                octree.insert(new Point3f(index * 100, index * 100, index * 100), 
                             (byte) 3, largeContent);
            });
        }
        
        var report = profiler.generateReport();
        var insertReport = report.operationReports.get(SpatialIndexProfiler.OperationType.INSERT);
        
        System.out.println("\nMemory Profiling Test:");
        System.out.println(insertReport);
        
        // Memory delta might be positive due to allocations
        assertTrue(insertReport.count > 0);
    }

    @Test
    public void testSlowOperationDetection() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var profiler = new SpatialIndexProfiler<>(octree);
        
        // Set a very low threshold to trigger warnings
        profiler.setSlowOperationThreshold(1); // 1ms
        
        // Perform an operation that might be slow
        profiler.profileVoid(SpatialIndexProfiler.OperationType.BULK_INSERT, () -> {
            for (int i = 0; i < 1000; i++) {
                octree.insert(new Point3f(i, i, i), (byte) 5, "Entity " + i);
            }
        });
        
        var report = profiler.generateReport();
        var bulkReport = report.operationReports.get(SpatialIndexProfiler.OperationType.BULK_INSERT);
        
        assertNotNull(bulkReport);
        assertTrue(bulkReport.maxTimeMillis > 0);
    }

    @Test
    public void testErrorTracking() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var profiler = new SpatialIndexProfiler<>(octree);
        
        // Try operations that will fail
        for (int i = 0; i < 5; i++) {
            try {
                profiler.profile(SpatialIndexProfiler.OperationType.REMOVE, () -> {
                    // Try to remove non-existent entity
                    var removed = octree.removeEntity(new LongEntityID(999999));
                    if (!removed) {
                        throw new RuntimeException("Entity not found");
                    }
                    return removed;
                });
            } catch (RuntimeException e) {
                // Expected
            }
        }
        
        var report = profiler.generateReport();
        var removeReport = report.operationReports.get(SpatialIndexProfiler.OperationType.REMOVE);
        
        assertNotNull(removeReport);
        assertEquals(5, removeReport.errors);
    }

    @Test
    public void testPercentileCalculations() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var profiler = new SpatialIndexProfiler<>(octree);
        
        // Generate operations with varying performance
        var random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            final int index = i;
            final int delay = i < 950 ? 0 : 1; // Last 5% are slower
            
            profiler.profileVoid(SpatialIndexProfiler.OperationType.INSERT, () -> {
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                octree.insert(new Point3f(index, index, index), (byte) 3, "Entity " + index);
            });
        }
        
        var report = profiler.generateReport();
        var insertReport = report.operationReports.get(SpatialIndexProfiler.OperationType.INSERT);
        
        System.out.println("\nPercentile Test:");
        System.out.println(insertReport);
        
        // p95 and p99 should be higher than p50 due to the slow operations
        assertTrue(insertReport.p95TimeMillis >= insertReport.p50TimeMillis);
        assertTrue(insertReport.p99TimeMillis >= insertReport.p95TimeMillis);
    }

    @Test
    public void testReset() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var profiler = new SpatialIndexProfiler<>(octree);
        
        // Perform some operations
        profiler.profileVoid(SpatialIndexProfiler.OperationType.INSERT, () -> {
            octree.insert(new Point3f(1, 1, 1), (byte) 3, "Test");
        });
        profiler.incrementCounter("test_counter");
        
        // Verify data exists
        var report1 = profiler.generateReport();
        assertFalse(report1.operationReports.isEmpty());
        assertEquals(1L, report1.customCounters.get("test_counter"));
        
        // Reset
        profiler.reset();
        
        // Verify data is cleared
        var report2 = profiler.generateReport();
        assertTrue(report2.operationReports.isEmpty() || 
                  report2.operationReports.values().stream().allMatch(r -> r.count == 0));
        assertTrue(report2.customCounters.isEmpty());
    }
}