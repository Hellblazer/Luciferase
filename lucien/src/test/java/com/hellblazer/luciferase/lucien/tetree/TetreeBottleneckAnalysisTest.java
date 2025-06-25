/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;

/**
 * Analyze remaining performance bottlenecks in Tetree operations.
 */
public class TetreeBottleneckAnalysisTest {

    @Test
    void analyzeInsertionBreakdown() {
        System.out.println("=== TETREE INSERTION BREAKDOWN ANALYSIS ===\n");
        
        var tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        var position = new Point3f(5000, 5000, 5000);
        
        // Warm up
        for (int i = 0; i < 100; i++) {
            tetree.insert(new Point3f(i * 100, i * 100, i * 100), (byte) 10, "warmup");
        }
        
        // Test 1: tmIndex() performance
        TetreeLevelCache.resetCacheStats();
        long tmIndexTime = 0;
        for (int i = 0; i < 1000; i++) {
            var tet = new Tet(i * 10, i * 10, i * 10, (byte) 10, (byte) 0);
            long start = System.nanoTime();
            tet.tmIndex();
            tmIndexTime += System.nanoTime() - start;
        }
        System.out.printf("1. tmIndex() average: %.2f ns (cache hit rate: %.2f%%)%n", 
                tmIndexTime / 1000.0, TetreeLevelCache.getCacheHitRate() * 100);
        
        // Test 2: Finding tetrahedral position
        long findTetTime = 0;
        for (int i = 0; i < 1000; i++) {
            var pos = new Point3f(i * 10, i * 10, i * 10);
            long start = System.nanoTime();
            var tet = Tet.locateFreudenthal(pos.x, pos.y, pos.z, (byte) 10);
            findTetTime += System.nanoTime() - start;
        }
        System.out.printf("2. Finding tetrahedron: %.2f ns/op%n", findTetTime / 1000.0);
        
        // Test 3: ensureAncestorNodes overhead
        var testTetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        long ancestorTime = 0;
        for (int i = 0; i < 100; i++) {
            // Insert at high level to force ancestor creation
            var pos = new Point3f(i * 10000, i * 10000, i * 10000);
            long start = System.nanoTime();
            testTetree.insert(pos, (byte) 15, "test");
            ancestorTime += System.nanoTime() - start;
        }
        System.out.printf("3. Insert with ancestors: %.2f μs/op%n", ancestorTime / 100.0 / 1000);
        
        // Test 4: Entity manager overhead
        var idGen = new SequentialLongIDGenerator();
        long entityTime = 0;
        for (int i = 0; i < 1000; i++) {
            long start = System.nanoTime();
            var id = idGen.generateID();
            entityTime += System.nanoTime() - start;
        }
        System.out.printf("4. Entity ID generation: %.2f ns/op%n", entityTime / 1000.0);
        
        // Test 5: Complete insertion breakdown
        System.out.println("\n5. Full insertion timing breakdown:");
        var freshTetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        
        // Single insertion with detailed timing
        var pos = new Point3f(12345, 23456, 34567);
        long totalStart = System.nanoTime();
        
        // Step 1: Find tetrahedron
        long step1Start = System.nanoTime();
        var tet = Tet.locateFreudenthal(pos.x, pos.y, pos.z, (byte) 10);
        long step1Time = System.nanoTime() - step1Start;
        
        // Step 2: Get tmIndex
        long step2Start = System.nanoTime();
        var key = tet.tmIndex();
        long step2Time = System.nanoTime() - step2Start;
        
        // Step 3: Insert
        long step3Start = System.nanoTime();
        freshTetree.insert(pos, (byte) 10, "detailed");
        long totalTime = System.nanoTime() - totalStart;
        long step3Time = totalTime - step1Time - step2Time;
        
        System.out.printf("   Find tetrahedron: %.2f ns (%.1f%%)%n", 
                (double) step1Time, (step1Time * 100.0 / totalTime));
        System.out.printf("   Get tmIndex: %.2f ns (%.1f%%)%n", 
                (double) step2Time, (step2Time * 100.0 / totalTime));
        System.out.printf("   Insert operation: %.2f ns (%.1f%%)%n", 
                (double) step3Time, (step3Time * 100.0 / totalTime));
        System.out.printf("   Total: %.2f ns%n", (double) totalTime);
        
        // Test 6: Bulk vs individual comparison
        System.out.println("\n6. Bulk vs Individual Insert Comparison:");
        var positions = new ArrayList<Point3f>();
        var contents = new ArrayList<String>();
        for (int i = 0; i < 1000; i++) {
            positions.add(new Point3f(i * 100, i * 100, i * 100));
            contents.add("entity_" + i);
        }
        
        // Individual inserts
        var individualTetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        long individualStart = System.nanoTime();
        for (int i = 0; i < positions.size(); i++) {
            individualTetree.insert(positions.get(i), (byte) 10, contents.get(i));
        }
        long individualTime = System.nanoTime() - individualStart;
        
        // Bulk insert
        var bulkTetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        long bulkStart = System.nanoTime();
        bulkTetree.insertBatch(positions, contents, (byte) 10);
        long bulkTime = System.nanoTime() - bulkStart;
        
        System.out.printf("   Individual: %.2f ms (%.2f μs/entity)%n", 
                individualTime / 1_000_000.0, individualTime / 1000.0 / 1000);
        System.out.printf("   Bulk: %.2f ms (%.2f μs/entity)%n", 
                bulkTime / 1_000_000.0, bulkTime / 1000.0 / 1000);
        System.out.printf("   Bulk speedup: %.2fx%n", (double) individualTime / bulkTime);
        
        System.out.println("\n=== ANALYSIS COMPLETE ===");
    }
}