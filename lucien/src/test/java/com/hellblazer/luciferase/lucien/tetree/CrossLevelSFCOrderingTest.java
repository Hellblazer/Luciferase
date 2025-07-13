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
 * Critical test to verify that level 21 bit packing preserves Space-Filling Curve (SFC) ordering
 * when comparing keys across different levels. This is essential for spatial indexing performance.
 * 
 * The key insight is that SFC ordering must be preserved not just within level 21, but also
 * when level 21 keys are compared with keys at levels ≤20. If this property is broken,
 * spatial locality is lost and range queries become inefficient.
 * 
 * @author hal.hildebrand
 */
class CrossLevelSFCOrderingTest {

    /**
     * Test SFC ordering preservation across levels 19, 20, and 21.
     * This is the most critical test for verifying that level 21 bit packing doesn't break
     * the fundamental SFC ordering property that spatial indexing depends on.
     */
    @Test
    void testCrossLevelSFCOrdering() {
        System.out.println("Testing Cross-Level SFC Ordering Preservation...");
        
        List<KeyWithLevel> testKeys = new ArrayList<>();
        
        // Create a sequence of keys at different levels that should maintain SFC ordering
        // Using a base pattern that should give us predictable ordering
        long baseLow = 0x0123456789ABCDEFL;
        long baseHigh = 0x0FEDCBA987654321L;
        
        // Level 9 keys (CompactTetreeKey - single long, max level for CompactTetreeKey is 10)
        for (int i = 0; i < 8; i++) {
            // Clear bits 54-59 in baseLow before setting new value
            long mask = ~(0x3FL << 54); // Mask to clear bits 54-59
            long level9Data = (baseLow & mask) | ((long) i << 54); // Put data in level 9 position (54-59)
            var key = new CompactTetreeKey((byte) 9, level9Data);
            testKeys.add(new KeyWithLevel(9, i, key));
        }
        
        // Level 20 keys (ExtendedTetreeKey - dual long, standard encoding)
        for (int i = 0; i < 8; i++) {
            // Clear bits 54-59 in baseHigh before setting new value
            long mask = ~(0x3FL << 54); // Mask to clear bits 54-59
            long level20Data = (baseHigh & mask) | ((long) i << 54); // Put data in level 10 position (54-59 in high bits)
            var key = new ExtendedTetreeKey((byte) 20, baseLow, level20Data);
            testKeys.add(new KeyWithLevel(20, i, key));
        }
        
        // Level 21 keys (ExtendedTetreeKey - special bit packing)
        for (int i = 0; i < 8; i++) {
            var key = ExtendedTetreeKey.createLevel21Key(baseLow, baseHigh, (byte) i);
            testKeys.add(new KeyWithLevel(21, i, key));
        }
        
        // Test 1: Level-based ordering should be preserved
        System.out.println("Testing level-based ordering...");
        testLevelBasedOrdering(testKeys);
        
        // Test 2: Within-level ordering should be preserved  
        System.out.println("Testing within-level ordering...");
        testWithinLevelOrdering(testKeys);
        
        // Test 3: Full sort consistency
        System.out.println("Testing full sort consistency...");
        testFullSortConsistency(testKeys);
        
        // Test 4: Adjacent level transitions
        System.out.println("Testing adjacent level transitions...");
        testAdjacentLevelTransitions();
        
        System.out.println("Cross-level SFC ordering test completed successfully!");
    }
    
    /**
     * Test that keys are properly ordered by level first
     */
    private void testLevelBasedOrdering(List<KeyWithLevel> testKeys) {
        // Group by level and verify ordering
        var level9Keys = testKeys.stream().filter(k -> k.level == 9).toList();
        var level20Keys = testKeys.stream().filter(k -> k.level == 20).toList();
        var level21Keys = testKeys.stream().filter(k -> k.level == 21).toList();
        
        // Any level 9 key should be < any level 20 key
        for (var k9 : level9Keys) {
            for (var k20 : level20Keys) {
                int comparison = k9.key.compareTo(k20.key);
                assertTrue(comparison < 0, 
                    String.format("Level 9 key should < level 20 key, but got %d\n" +
                                "  L9: %s\n  L20: %s", comparison, k9, k20));
            }
        }
        
        // Any level 20 key should be < any level 21 key  
        for (var k20 : level20Keys) {
            for (var k21 : level21Keys) {
                int comparison = k20.key.compareTo(k21.key);
                assertTrue(comparison < 0,
                    String.format("Level 20 key should < level 21 key, but got %d\n" +
                                "  L20: %s\n  L21: %s", comparison, k20, k21));
            }
        }
    }
    
    /**
     * Test that within each level, SFC ordering is preserved
     */
    private void testWithinLevelOrdering(List<KeyWithLevel> testKeys) {
        // Test level 9 ordering
        var level9Keys = testKeys.stream().filter(k -> k.level == 9)
                                          .sorted((a, b) -> Integer.compare(a.index, b.index))
                                          .toList();
        for (int i = 0; i < level9Keys.size() - 1; i++) {
            var curr = level9Keys.get(i);
            var next = level9Keys.get(i + 1);
            int comparison = curr.key.compareTo(next.key);
            assertTrue(comparison < 0,
                String.format("Level 9 index %d should < index %d, but got %d", 
                            curr.index, next.index, comparison));
        }
        
        // Test level 20 ordering
        var level20Keys = testKeys.stream().filter(k -> k.level == 20)
                                          .sorted((a, b) -> Integer.compare(a.index, b.index))
                                          .toList();
        for (int i = 0; i < level20Keys.size() - 1; i++) {
            var curr = level20Keys.get(i);
            var next = level20Keys.get(i + 1);
            int comparison = curr.key.compareTo(next.key);
            assertTrue(comparison < 0,
                String.format("Level 20 index %d should < index %d, but got %d", 
                            curr.index, next.index, comparison));
        }
        
        // Test level 21 ordering (this is the critical one for bit packing)
        var level21Keys = testKeys.stream().filter(k -> k.level == 21)
                                          .sorted((a, b) -> Integer.compare(a.index, b.index))
                                          .toList();
        for (int i = 0; i < level21Keys.size() - 1; i++) {
            var curr = level21Keys.get(i);
            var next = level21Keys.get(i + 1);
            int comparison = curr.key.compareTo(next.key);
            assertTrue(comparison < 0,
                String.format("Level 21 index %d should < index %d, but got %d\n" +
                            "  This indicates level 21 bit packing breaks SFC ordering!", 
                            curr.index, next.index, comparison));
        }
    }
    
    /**
     * Test full sort consistency across all levels
     */
    private void testFullSortConsistency(List<KeyWithLevel> testKeys) {
        // Create expected order: level 9 (indices 0-7), then level 20 (indices 0-7), then level 21 (indices 0-7)
        List<KeyWithLevel> expected = new ArrayList<>();
        int[] levels = {9, 20, 21}; // The three levels we're testing
        for (int level : levels) {
            final int finalLevel = level; // Make effectively final for lambda
            for (int index = 0; index < 8; index++) {
                final int finalIndex = index; // Make effectively final for lambda
                var key = testKeys.stream()
                    .filter(k -> k.level == finalLevel && k.index == finalIndex)
                    .findFirst()
                    .orElseThrow();
                expected.add(key);
            }
        }
        
        // Shuffle and sort by key comparison
        List<KeyWithLevel> actual = new ArrayList<>(testKeys);
        Collections.shuffle(actual);
        actual.sort((a, b) -> a.key.compareTo(b.key));
        
        // Verify the sorted order matches expected order
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            var exp = expected.get(i);
            var act = actual.get(i);
            assertEquals(exp.level, act.level, 
                String.format("Position %d: expected level %d, got level %d", i, exp.level, act.level));
            assertEquals(exp.index, act.index,
                String.format("Position %d: expected index %d, got index %d", i, exp.index, act.index));
        }
    }
    
    /**
     * Test boundary conditions at level transitions
     */
    private void testAdjacentLevelTransitions() {
        // Test the boundary between CompactTetreeKey (level 10) and ExtendedTetreeKey (level 11)
        // This verifies that the key type transition doesn't break ordering
        
        // Create max level 10 key (last CompactTetreeKey level)
        long maxLevel10Data = 0x0FFFFFFFFFFFFFFFL; // All valid bits set
        var maxLevel10 = new CompactTetreeKey((byte) 10, maxLevel10Data);
        
        // Create min level 11 key (first ExtendedTetreeKey level)
        var minLevel11 = new ExtendedTetreeKey((byte) 11, 0L, 0L);
        
        int comparison = maxLevel10.compareTo(minLevel11);
        assertTrue(comparison < 0, 
            String.format("Max level 10 key should < min level 11 key, but got %d", comparison));
        
        // Test the boundary between level 20 and level 21 (bit packing transition)
        // Create max level 20 key
        long maxHighBits = 0x0FFFFFFFFFFFFFFFL; // All valid level 10-20 bits
        var maxLevel20 = new ExtendedTetreeKey((byte) 20, 0x0FFFFFFFFFFFFFFFL, maxHighBits);
        
        // Create min level 21 key
        var minLevel21 = ExtendedTetreeKey.createLevel21Key(0L, 0L, (byte) 0);
        
        comparison = maxLevel20.compareTo(minLevel21);
        assertTrue(comparison < 0,
            String.format("Max level 20 key should < min level 21 key, but got %d\n" +
                        "This indicates level 21 bit packing breaks ordering at level boundaries!", 
                        comparison));
    }
    
    /**
     * Test specific edge case: verify that level 21 bit packing doesn't interfere with
     * the comparison logic when comparing with non-level-21 keys
     */
    @Test
    void testLevel21BitMaskingInComparisons() {
        System.out.println("Testing level 21 bit masking in cross-level comparisons...");
        
        // Create level 20 key with data in positions that level 21 uses for bit packing
        long lowWithLevel21Bits = 0xF000000000000000L; // Set bits 60-63 (level 21 low bits)
        long highWithLevel21Bits = 0x3000000000000000L; // Set bits 60-61 (level 21 high bits)
        var level20Key = new ExtendedTetreeKey((byte) 20, lowWithLevel21Bits, highWithLevel21Bits);
        
        // Create level 21 key with no level 21 data
        var level21Key = ExtendedTetreeKey.createLevel21Key(0L, 0L, (byte) 0);
        
        // The level 20 key should still be < level 21 key despite having bits set in level 21 positions
        int comparison = level20Key.compareTo(level21Key);
        assertTrue(comparison < 0,
            String.format("Level 20 key should < level 21 key even with level 21 bit positions set, but got %d\n" +
                        "This suggests comparison logic doesn't properly handle level 21 bit positions", 
                        comparison));
    }
    
    /**
     * Test with realistic SFC indices to ensure spatial locality is preserved
     */
    @Test
    void testRealisticSFCSequence() {
        System.out.println("Testing realistic SFC sequence across levels...");
        
        // Create a realistic sequence that might occur during spatial subdivision
        List<TetreeKey<?>> sequence = new ArrayList<>();
        
        // Parent at level 9
        var parent9 = new CompactTetreeKey((byte) 9, 0x123456789ABCDEFL);
        sequence.add(parent9);
        
        // Child at level 20 (should be > parent)
        var child20 = new ExtendedTetreeKey((byte) 20, 0x123456789ABCDEFL, 0x1L);
        sequence.add(child20);
        
        // Grandchild at level 21 (should be > child)
        var grandchild21 = ExtendedTetreeKey.createLevel21Key(0x123456789ABCDEFL, 0x1L, (byte) 1);
        sequence.add(grandchild21);
        
        // Verify ordering is preserved
        for (int i = 0; i < sequence.size() - 1; i++) {
            var curr = sequence.get(i);
            var next = sequence.get(i + 1);
            int comparison = curr.compareTo(next);
            assertTrue(comparison < 0,
                String.format("Realistic sequence violation at position %d->%d: comparison = %d\n" +
                            "  Current: level %d\n  Next: level %d", 
                            i, i+1, comparison, curr.getLevel(), next.getLevel()));
        }
    }
    
    /**
     * Critical edge case test: (0,0,0) anchor coordinates at all levels 0-21.
     * This tests the exact scenario mentioned in the conversation where anchor coordinates
     * can appear at all levels, which is why level must be stored separately.
     */
    @Test
    void testAnchorCoordinatesAcrossAllLevels() {
        System.out.println("Testing (0,0,0) anchor coordinates across levels 0-21...");
        
        List<TetreeKey<?>> anchorKeys = new ArrayList<>();
        
        // Create (0,0,0) keys at every level from 0 to 21
        for (byte level = 0; level <= 21; level++) {
            TetreeKey<?> key;
            if (level <= 10) {
                // CompactTetreeKey for levels 0-10
                key = new CompactTetreeKey(level, 0L); // 0L represents (0,0,0) coordinates
            } else if (level <= 20) {
                // ExtendedTetreeKey for levels 11-20
                key = new ExtendedTetreeKey(level, 0L, 0L);
            } else {
                // Level 21 with bit packing
                key = ExtendedTetreeKey.createLevel21Key(0L, 0L, (byte) 0);
            }
            anchorKeys.add(key);
        }
        
        // Verify all keys are valid
        for (var key : anchorKeys) {
            assertTrue(key.isValid(), 
                String.format("Anchor key at level %d should be valid", key.getLevel()));
        }
        
        // Critical test: level-based ordering must be preserved even with identical coordinates
        System.out.println("Testing level-based ordering for identical (0,0,0) coordinates...");
        for (int i = 0; i < anchorKeys.size() - 1; i++) {
            var currentKey = anchorKeys.get(i);
            var nextKey = anchorKeys.get(i + 1);
            
            // Current level should always be < next level
            assertTrue(currentKey.getLevel() < nextKey.getLevel(), 
                String.format("Level ordering assumption violated: %d >= %d", 
                            currentKey.getLevel(), nextKey.getLevel()));
            
            // Key comparison should reflect level ordering
            int comparison = currentKey.compareTo(nextKey);
            assertTrue(comparison < 0,
                String.format("Anchor coordinate ordering violation: level %d should < level %d, but compareTo() = %d\n" +
                            "  This is critical because the same (0,0,0) coordinates appear at different levels\n" +
                            "  Level %d key: %s\n  Level %d key: %s",
                            currentKey.getLevel(), nextKey.getLevel(), comparison,
                            currentKey.getLevel(), formatKey(currentKey),
                            nextKey.getLevel(), formatKey(nextKey)));
        }
        
        // Test sort consistency with anchor coordinates
        System.out.println("Testing sort consistency for anchor coordinates...");
        List<TetreeKey<?>> shuffled = new ArrayList<>(anchorKeys);
        Collections.shuffle(shuffled);
        shuffled.sort(TetreeKey::compareTo);
        
        // After sorting, should be in level order 0, 1, 2, ..., 21
        for (int i = 0; i < shuffled.size(); i++) {
            assertEquals(i, shuffled.get(i).getLevel(),
                String.format("Sort position %d should have level %d, but got level %d", 
                            i, i, shuffled.get(i).getLevel()));
        }
        
        // Test specific transitions that are most likely to fail
        testCriticalTransitions(anchorKeys);
        
        System.out.println("Anchor coordinates test passed - level 21 bit packing preserves ordering!");
    }
    
    /**
     * Test the most critical transitions that could break with level 21 bit packing
     */
    private void testCriticalTransitions(List<TetreeKey<?>> anchorKeys) {
        System.out.println("Testing critical level transitions...");
        
        // Transition 1: Level 10 (max CompactTetreeKey) -> Level 11 (min ExtendedTetreeKey)
        var level10 = anchorKeys.get(10);
        var level11 = anchorKeys.get(11);
        assertTrue(level10.compareTo(level11) < 0,
            "Critical transition failure: CompactTetreeKey(10) should < ExtendedTetreeKey(11)");
        
        // Transition 2: Level 20 (max standard ExtendedTetreeKey) -> Level 21 (bit-packed ExtendedTetreeKey)
        var level20 = anchorKeys.get(20);
        var level21 = anchorKeys.get(21);
        assertTrue(level20.compareTo(level21) < 0,
            "CRITICAL FAILURE: Level 20 should < Level 21 with bit packing! This breaks SFC ordering.");
        
        // Verify level 21 key has correct structure
        assertTrue(level21 instanceof ExtendedTetreeKey,
            "Level 21 key should be ExtendedTetreeKey");
        var level21Extended = (ExtendedTetreeKey) level21;
        assertEquals(21, level21Extended.getLevel());
        assertEquals(0L, level21Extended.getLowBits()); // Should have no level 21 bits for (0,0,0)
        assertEquals(0L, level21Extended.getHighBits());
    }
    
    /**
     * Test non-zero coordinates at level boundaries to ensure bit packing doesn't interfere
     */
    @Test
    void testNonZeroCoordinatesAtLevelBoundaries() {
        System.out.println("Testing non-zero coordinates at critical level boundaries...");
        
        // Test coordinates (1,1,1) type 1 at levels 19, 20, 21
        // This should create predictable tmIndex values that we can verify
        
        // Level 9: single long with coordinate/type data
        long level9Data = 0x249L; // Binary: 001001001001 (coord=1,type=1 for 3 levels)
        var level9Key = new CompactTetreeKey((byte) 9, level9Data);
        
        // Level 20: dual long with data in high bits
        long level20HighData = 0x249L; // Same pattern in high bits
        var level20Key = new ExtendedTetreeKey((byte) 20, level9Data, level20HighData);
        
        // Level 21: bit-packed with coord=1, type=1 -> 0x09 (001001)
        var level21Key = ExtendedTetreeKey.createLevel21Key(level9Data, level20HighData, (byte) 0x09);
        
        // Verify level ordering is preserved
        assertTrue(level9Key.compareTo(level20Key) < 0,
            "Level 9 should < Level 20 for non-zero coordinates");
        assertTrue(level20Key.compareTo(level21Key) < 0,
            "Level 20 should < Level 21 for non-zero coordinates - bit packing test!");
        
        // Verify we can extract the coordinates correctly from level 21
        assertEquals(1, level21Key.getCoordBitsAtLevel(21));
        assertEquals(1, level21Key.getTypeAtLevel(21));
    }
    
    /**
     * Format a key for debugging output
     */
    private String formatKey(TetreeKey<?> key) {
        if (key instanceof CompactTetreeKey compact) {
            return String.format("CompactTetreeKey(level=%d, data=0x%016X)", 
                               compact.getLevel(), compact.getLowBits());
        } else if (key instanceof ExtendedTetreeKey extended) {
            return String.format("ExtendedTetreeKey(level=%d, low=0x%016X, high=0x%016X)",
                               extended.getLevel(), extended.getLowBits(), extended.getHighBits());
        } else {
            return key.toString();
        }
    }

    /**
     * Helper class for test data
     */
    private static class KeyWithLevel {
        final int level;
        final int index;
        final TetreeKey<?> key;
        
        KeyWithLevel(int level, int index, TetreeKey<?> key) {
            this.level = level;
            this.index = index;
            this.key = key;
        }
        
        @Override
        public String toString() {
            return String.format("L%d[%d]", level, index);
        }
    }
}