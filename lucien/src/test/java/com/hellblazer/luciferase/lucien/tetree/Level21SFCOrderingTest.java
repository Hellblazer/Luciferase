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
     * Test basic level 21 bit packing functionality
     */
    @Test
    void testLevel21BitPacking() {
        // Create level 21 key with some test data
        long baseLow = 0x0FFFFFFFFFFFFFFFL;  // Levels 0-9 data (60 bits)
        long baseHigh = 0x0FFFFFFFFFFFFFFFL; // Levels 10-20 data (60 bits)
        byte level21Data = 0x2A; // 101010 in binary (6 bits)
        
        var key = ExtendedTetreeKey.createLevel21Key(baseLow, baseHigh, level21Data);
        
        assertEquals(21, key.getLevel());
        assertTrue(key.isValid());
        
        // Verify bit extraction works
        byte extractedCoord = key.getCoordBitsAtLevel(21);
        byte extractedType = key.getTypeAtLevel(21);
        
        // level21Data = 101010, so coord = 101 (5), type = 010 (2)
        assertEquals(5, extractedCoord);
        assertEquals(2, extractedType);
    }

    /**
     * Test that level 21 parent computation works correctly
     */
    @Test
    void testLevel21ParentChild() {
        // Create level 21 key with base data that doesn't use level 21 bit positions
        long baseLow = 0x0123456789ABCDEFL;  // Clear bits 60-63 (level 21 positions)
        long baseHigh = 0x0EDCBA9876543210L; // Clear bits 60-63 (level 21 positions)
        byte level21Data = 0x15; // 010101
        
        var level21Key = ExtendedTetreeKey.createLevel21Key(baseLow, baseHigh, level21Data);
        var parent = level21Key.parent();
        
        assertNotNull(parent);
        assertEquals(20, parent.getLevel());
        assertTrue(parent instanceof ExtendedTetreeKey);
        
        // Verify parent has the original base bits (level 21 bits removed)
        var parentExtended = (ExtendedTetreeKey) parent;
        assertEquals(baseLow, parentExtended.getLowBits());
        assertEquals(baseHigh, parentExtended.getHighBits());
        
        // Verify the level 21 key actually has the level 21 data
        // 0x15 = 010101 -> coord=010 (2), type=101 (5)
        assertEquals(2, level21Key.getCoordBitsAtLevel(21)); // coord = 010 = 2
        assertEquals(5, level21Key.getTypeAtLevel(21));      // type = 101 = 5
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
     * Test edge cases around bit boundaries
     */
    @Test 
    void testLevel21BitBoundaries() {
        System.out.println("Testing Level 21 bit boundary cases...");
        
        long baseLow = 0x0FFFFFFFFFFFFFFFL;  // Max valid bits for levels 0-9
        long baseHigh = 0x0FFFFFFFFFFFFFFFL; // Max valid bits for levels 10-20
        
        // Test boundary values for level 21 data
        byte[] boundaryValues = {0x00, 0x0F, 0x10, 0x1F, 0x20, 0x2F, 0x30, 0x3F};
        
        ExtendedTetreeKey prevKey = null;
        byte prevValue = 0;
        for (byte value : boundaryValues) {
            var key = ExtendedTetreeKey.createLevel21Key(baseLow, baseHigh, value);
            assertTrue(key.isValid(), "Key should be valid for value " + value);
            
            if (prevKey != null) {
                int comparison = prevKey.compareTo(key);
                if (comparison >= 0) {
                    System.out.printf("Boundary violation: 0x%02X should < 0x%02X but compareTo() = %d\n",
                                    prevValue, value, comparison);
                }
            }
            prevKey = key;
            prevValue = value;
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

    /**
     * Helper method to extract the raw level 21 6-bit value for testing purposes
     */
    private byte getLevel21Data(ExtendedTetreeKey key) {
        if (key.getLevel() != 21) return 0;
        
        long lowPart = (key.getLowBits() >> TetreeKey.LEVEL_21_LOW_BITS_SHIFT) & TetreeKey.LEVEL_21_LOW_MASK;
        long highPart = (key.getHighBits() >> TetreeKey.LEVEL_21_HIGH_BITS_SHIFT) & TetreeKey.LEVEL_21_HIGH_MASK;
        
        return (byte) (lowPart | (highPart << 4));
    }
}