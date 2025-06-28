/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Performance test for new Tetree batch optimization methods.
 * 
 * @author hal.hildebrand
 */
public class TetreeBatchOptimizationTest {

    private Tetree<LongEntityID, String> tetree;
    private List<Tetree.EntityData<LongEntityID, String>> testData;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        
        // Generate test data
        testData = new ArrayList<>();
        Random random = new Random(42);
        
        for (int i = 0; i < 10000; i++) {
            var id = new LongEntityID(i);
            var position = new Point3f(
                random.nextFloat() * 100000,
                random.nextFloat() * 100000,
                random.nextFloat() * 100000
            );
            var content = "Entity " + i;
            var level = (byte) 15;
            
            testData.add(new Tetree.EntityData<>(id, position, level, content));
        }
    }

    @Test
    void testBatchInsertionPerformance() {
        System.out.println("=== BATCH INSERTION PERFORMANCE TEST ===");
        
        // Test 1: Standard batch insert
        var tetree1 = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        long start1 = System.nanoTime();
        List<Point3f> positions = testData.stream().map(Tetree.EntityData::position).toList();
        List<String> contents = testData.stream().map(Tetree.EntityData::content).toList();
        var ids1 = tetree1.insertBatch(positions, contents, (byte) 15);
        long end1 = System.nanoTime();
        
        // Test 2: Pre-computation batch insert
        var tetree2 = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        long start2 = System.nanoTime();
        var ids2 = tetree2.insertBatchWithPrecomputation(testData);
        long end2 = System.nanoTime();
        
        // Test 3: Locality-aware batch insert
        var tetree3 = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        long start3 = System.nanoTime();
        var ids3 = tetree3.insertLocalityAware(testData);
        long end3 = System.nanoTime();
        
        // Test 4: Parallel batch insert
        var tetree4 = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        long start4 = System.nanoTime();
        var ids4 = tetree4.insertBatchParallel(testData);
        long end4 = System.nanoTime();
        
        double time1 = (end1 - start1) / 1_000_000.0;
        double time2 = (end2 - start2) / 1_000_000.0;
        double time3 = (end3 - start3) / 1_000_000.0;
        double time4 = (end4 - start4) / 1_000_000.0;
        
        System.out.printf("Standard batch:       %.2f ms%n", time1);
        System.out.printf("Pre-computation:      %.2f ms (%.2fx speedup)%n", time2, time1 / time2);
        System.out.printf("Locality-aware:       %.2f ms (%.2fx speedup)%n", time3, time1 / time3);
        System.out.printf("Parallel:             %.2f ms (%.2fx speedup)%n", time4, time1 / time4);
        
        System.out.printf("Entities inserted: %d, %d, %d, %d%n", ids1.size(), ids2.size(), ids3.size(), ids4.size());
        
        // Verify all methods inserted the same number of entities
        assert ids1.size() == ids2.size() && ids2.size() == ids3.size() && ids3.size() == ids4.size();
    }

    @Test
    void testParallelThreshold() {
        System.out.println("=== PARALLEL THRESHOLD TEST ===");
        
        // Test with small batch (should use sequential)
        var smallData = testData.subList(0, 100);
        long start1 = System.nanoTime();
        var ids1 = tetree.insertBatchParallelThreshold(smallData, 1000);
        long end1 = System.nanoTime();
        
        // Test with large batch (should use parallel)
        var tetree2 = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        var largeData = testData.subList(0, 5000);
        long start2 = System.nanoTime();
        var ids2 = tetree2.insertBatchParallelThreshold(largeData, 1000);
        long end2 = System.nanoTime();
        
        double time1 = (end1 - start1) / 1_000_000.0;
        double time2 = (end2 - start2) / 1_000_000.0;
        
        System.out.printf("Small batch (100):    %.2f ms%n", time1);
        System.out.printf("Large batch (5000):   %.2f ms%n", time2);
        System.out.printf("Entities inserted: %d, %d%n", ids1.size(), ids2.size());
        
        assert ids1.size() == 100;
        assert ids2.size() == 5000;
    }
}