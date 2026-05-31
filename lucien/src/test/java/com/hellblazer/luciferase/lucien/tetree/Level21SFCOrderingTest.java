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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify whether the level 21 bit packing approach preserves Space-Filling Curve (SFC) ordering.
 * This is critical for spatial indexing performance as SFC ordering ensures spatial locality.
 * 
 * @author hal.hildebrand
 */
class Level21SFCOrderingTest {

    /**
     * Descend from the root to a real level-21 tetrahedron via a deterministic child chain. The
     * resulting key is valid by construction in the coarsest-at-MSB uniform layout (Luciferase-tkvb).
     */
    private static Tet descendToLevel21(int seed) {
        var tet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        for (int lvl = 0; lvl < 21; lvl++) {
            tet = tet.child((seed + lvl * 3) % 8);
        }
        return tet;
    }

    /**
     * A real level-21 key round-trips Tet -> tmIndex -> Tet and is valid (uniform layout, no split).
     */
    @Test
    void testLevel21BitPacking() {
        var tet = descendToLevel21(1);
        var key = tet.tmIndex();

        assertEquals(21, key.getLevel());
        assertTrue(key.isValid(), "real level-21 key must be valid");

        // The leaf (deepest) group is step level-1 == 20, at bits 0-5; its type is the tet's type.
        assertEquals(tet.type(), key.getTypeAtLevel(20), "leaf type at step 20");

        // Decode round-trips back to the same tetrahedron.
        assertEquals(tet, Tet.tetrahedron(key), "level-21 tmIndex must round-trip");
    }

    /**
     * Parent of a real level-21 key is the level-20 key of the parent tetrahedron.
     */
    @Test
    void testLevel21ParentChild() {
        var tet = descendToLevel21(2);
        var level21Key = tet.tmIndex();
        var parent = level21Key.parent();

        assertNotNull(parent);
        assertEquals(20, parent.getLevel());
        assertTrue(parent instanceof ExtendedTetreeKey);

        // The key-level parent must equal the ground-truth parent (encode the parent Tet).
        assertEquals(tet.parent().tmIndex(), parent, "level-21 parent key must match parent tet key");
    }

    /**
     * Critical test: Does level 21 bit packing preserve SFC ordering?
     * 
     * This test creates consecutive level 21 indices and verifies that their
     * comparison order matches their numerical order. If bit packing breaks
     * this property, spatial locality is lost.
     */
    @Test
    void testLevel21SFCOrdering() {
        System.out.println("Testing Level 21 SFC Ordering Preservation...");
        
        // Test data: consecutive level 21 indices that should maintain order
        List<TestCase> testCases = new ArrayList<>();
        
        // Base data for levels 0-20 (same for all test cases)
        long baseLow = 0x0123456789ABCDEFL;  // Some arbitrary level 0-9 data
        long baseHigh = 0x0FEDCBA987654321L; // Some arbitrary level 10-20 data
        
        // Create keys with consecutive level 21 indices
        for (int i = 0; i < 64; i++) { // Test all 6-bit values (0-63)
            var key = ExtendedTetreeKey.createLevel21Key(baseLow, baseHigh, (byte) i);
            testCases.add(new TestCase(i, key));
        }
        
        // Test 1: Natural order should match comparison order
        System.out.println("Testing natural order preservation...");
        boolean orderingPreserved = true;
        List<String> violations = new ArrayList<>();
        
        for (int i = 0; i < testCases.size() - 1; i++) {
            var current = testCases.get(i);
            var next = testCases.get(i + 1);
            
            // Keys with smaller indices should compare as less than keys with larger indices
            int comparison = current.key.compareTo(next.key);
            if (comparison >= 0) { // Should be < 0 for proper ordering
                orderingPreserved = false;
                violations.add(String.format(
                    "Order violation: index %d should < index %d, but compareTo() = %d\n" +
                    "  Key[%d]: low=0x%016X, high=0x%016X\n" +
                    "  Key[%d]: low=0x%016X, high=0x%016X",
                    current.index, next.index, comparison,
                    current.index, current.key.getLowBits(), current.key.getHighBits(),
                    next.index, next.key.getLowBits(), next.key.getHighBits()
                ));
            }
        }
        
        // Test 2: Sorted order should match natural order
        System.out.println("Testing sort consistency...");
        List<TestCase> sorted = new ArrayList<>(testCases);
        Collections.shuffle(sorted); // Randomize order
        sorted.sort((a, b) -> a.key.compareTo(b.key)); // Sort by key comparison
        
        boolean sortConsistent = true;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).index != i) {
                sortConsistent = false;
                violations.add(String.format(
                    "Sort inconsistency: expected index %d at position %d, got index %d",
                    i, i, sorted.get(i).index
                ));
                break;
            }
        }
        
        // Test 3: Analyze bit patterns causing violations
        if (!orderingPreserved || !sortConsistent) {
            System.out.println("\nBit pattern analysis for violations:");
            analyzeViolations(testCases);
        }
        
        // Report results
        System.out.printf("SFC Ordering Test Results:\n");
        System.out.printf("  Natural order preserved: %s\n", orderingPreserved);
        System.out.printf("  Sort consistency: %s\n", sortConsistent);
        System.out.printf("  Total violations: %d\n", violations.size());
        
        if (!violations.isEmpty()) {
            System.out.println("\nFirst few violations:");
            for (int i = 0; i < Math.min(5, violations.size()); i++) {
                System.out.println(violations.get(i));
            }
        }
        
        // The test fails if ordering is not preserved
        if (!orderingPreserved || !sortConsistent) {
            fail("Level 21 bit packing BREAKS SFC ordering! This is a critical issue for spatial indexing.");
        }
    }

    /**
     * Real level-21 sibling keys (the 8 children of a common level-20 parent) are all valid, share
     * the common parent, and are pairwise distinct with a consistent strict total order under
     * {@code compareTo} (coarsest-at-MSB layout).
     */
    @Test
    void testLevel21BitBoundaries() {
        var parent = descendToLevel21(3).parent(); // a level-20 tetrahedron
        var parentKey = parent.tmIndex();

        var keys = new ArrayList<TetreeKey<?>>();
        for (int child = 0; child < 8; child++) {
            var key = parent.child(child).tmIndex();
            assertEquals(21, key.getLevel());
            assertTrue(key.isValid(), "level-21 child key must be valid for child " + child);
            assertEquals(parentKey, key.parent(), "child's parent key must equal the parent");
            keys.add(key);
        }
        // Pairwise distinct and antisymmetric ordering (a strict total order).
        for (int a = 0; a < keys.size(); a++) {
            for (int b = a + 1; b < keys.size(); b++) {
                int cmp = keys.get(a).compareTo(keys.get(b));
                assertTrue(cmp != 0, "distinct level-21 siblings must not compare equal");
                assertEquals(Integer.signum(cmp), -Integer.signum(keys.get(b).compareTo(keys.get(a))),
                             "compareTo must be antisymmetric");
            }
        }
    }

    /**
     * Analyze why bit packing breaks ordering
     */
    private void analyzeViolations(List<TestCase> testCases) {
        System.out.println("Analysis of comparison logic with split encoding:");
        
        // Show how the first few consecutive pairs compare
        for (int i = 0; i < Math.min(8, testCases.size() - 1); i++) {
            var curr = testCases.get(i);
            var next = testCases.get(i + 1);
            
            // Show the bit layouts
            long currLow = curr.key.getLowBits();
            long currHigh = curr.key.getHighBits();
            long nextLow = next.key.getLowBits();
            long nextHigh = next.key.getHighBits();
            
            // Extract level 21 bits
            long currLevel21Low = (currLow >> 60) & 0xF;
            long currLevel21High = (currHigh >> 60) & 0x3;
            long nextLevel21Low = (nextLow >> 60) & 0xF;
            long nextLevel21High = (nextHigh >> 60) & 0x3;
            
            int comparison = curr.key.compareTo(next.key);
            
            System.out.printf("Index %d→%d (should be <0, actual: %d):\n", i, i+1, comparison);
            System.out.printf("  Curr[%d]: level21=0x%02X, low=0x%X, high=0x%X\n", 
                             i, i, currLevel21Low, currLevel21High);
            System.out.printf("  Next[%d]: level21=0x%02X, low=0x%X, high=0x%X\n", 
                             i+1, i+1, nextLevel21Low, nextLevel21High);
            
            // Show why comparison fails
            if (currHigh != nextHigh) {
                System.out.printf("  → High bits differ: 0x%X vs 0x%X\n", currLevel21High, nextLevel21High);
            }
            if (currLow != nextLow) {
                System.out.printf("  → Low bits differ: 0x%X vs 0x%X\n", currLevel21Low, nextLevel21Low);
            }
            System.out.println();
        }
    }

    /**
     * Helper class for test data
     */
    private static class TestCase {
        final int index;
        final ExtendedTetreeKey key;
        
        TestCase(int index, ExtendedTetreeKey key) {
            this.index = index;
            this.key = key;
        }
    }

}