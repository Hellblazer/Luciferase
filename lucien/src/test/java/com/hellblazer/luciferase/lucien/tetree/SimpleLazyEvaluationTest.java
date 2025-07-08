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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simplified test cases for lazy evaluation mechanisms.
 * These tests verify the core functionality without complex spatial queries.
 *
 * @author hal.hildebrand
 */
class SimpleLazyEvaluationTest {
    
    @Test
    void testLazyRangeIteratorBasics() {
        // Use level 1 to allow multiple types
        var startTet = new Tet(0, 0, 0, (byte) 1, (byte) 0);
        var endTet = new Tet(0, 0, 0, (byte) 1, (byte) 5);
        
        var iterator = new LazyRangeIterator(startTet, endTet);
        
        // Should have elements
        assertTrue(iterator.hasNext());
        
        // Collect all keys
        List<TetreeKey<? extends TetreeKey>> keys = new ArrayList<>();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        
        // Should have collected 6 keys (types 0-5 at same position)
        assertEquals(6, keys.size(), "Should have 6 tetrahedra types at same position");
        
        // Verify they are in order
        assertEquals(startTet.tmIndex(), keys.get(0));
        assertEquals(endTet.tmIndex(), keys.get(5));
    }
    
    @Test
    void testLazySFCRangeStreamBasics() {
        // Create a simple range
        var tet1 = new Tet(0, 0, 0, (byte) 1, (byte) 0);
        var tet2 = new Tet(0, 0, 0, (byte) 1, (byte) 2);
        
        var range = new Tet.SFCRange(tet1.tmIndex(), tet2.tmIndex());
        
        // Test basic stream operations
        // Note: LazyRangeIterator currently iterates from start through all positions to end
        // For same position, it would iterate through all 6 types
        var list = LazySFCRangeStream.stream(range).limit(3).collect(Collectors.toList());
        assertEquals(3, list.size(), "Should limit to 3 elements");
        
        // Test early termination
        var first = LazySFCRangeStream.stream(range).findFirst();
        assertTrue(first.isPresent());
        assertEquals(tet1.tmIndex(), first.get());
    }
    
    @Test
    void testSFCRangeMethods() {
        var tet1 = new Tet(0, 0, 0, (byte) 2, (byte) 0);
        var tet2 = new Tet(0, 0, 0, (byte) 2, (byte) 3);
        
        var range = new Tet.SFCRange(tet1.tmIndex(), tet2.tmIndex());
        
        // Test isSingle
        assertFalse(range.isSingle());
        
        // Test single range
        var singleRange = new Tet.SFCRange(tet1.tmIndex(), tet1.tmIndex());
        assertTrue(singleRange.isSingle());
        assertEquals(1, singleRange.estimateSize());
        
        // Test iterator
        var iter = range.iterator();
        assertNotNull(iter);
        assertTrue(iter.hasNext());
        
        // Count elements
        int count = 0;
        while (iter.hasNext()) {
            iter.next();
            count++;
        }
        assertEquals(4, count, "Should have 4 elements (types 0-3)");
    }
    
    @Test
    void testLazyIteratorEstimation() {
        // Test size estimation at different scales
        var level = (byte) 3;
        int cellSize = 1 << (21 - level);
        
        var startTet = new Tet(0, 0, 0, level, (byte) 0);
        var endTet = new Tet(cellSize * 2, 0, 0, level, (byte) 0);
        
        var iterator = new LazyRangeIterator(startTet, endTet);
        
        // Estimation should be reasonable
        long estimate = iterator.estimateSize();
        assertTrue(estimate > 0);
        // Should estimate at least 2 cells * 6 types = 12
        assertTrue(estimate >= 12);
    }
    
    @Test
    void testStreamLaziness() {
        // Verify that stream operations are truly lazy
        var tet1 = new Tet(0, 0, 0, (byte) 1, (byte) 0);
        var tet2 = new Tet(0, 0, 0, (byte) 1, (byte) 5);
        
        var range = new Tet.SFCRange(tet1.tmIndex(), tet2.tmIndex());
        
        // This should not iterate through all elements
        var limited = range.stream()
            .limit(2)
            .count();
        
        assertEquals(2, limited, "Limit should restrict to 2 elements");
        
        // Test anyMatch with early termination
        boolean found = range.stream()
            .anyMatch(key -> key.equals(tet1.tmIndex()));
        
        assertTrue(found, "Should find first element quickly");
    }
}