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

/**
 * Helper class for creating valid TetreeKey test data.
 * 
 * The tmIndex in a TetreeKey encodes the path from root to a specific tetrahedron.
 * Each level adds 6 bits: 3 bits for coordinates (0-7) and 3 bits for type (0-5).
 * 
 * @author hal.hildebrand
 */
public class TetreeKeyTestHelper {
    
    /**
     * Creates a valid tmIndex for the given level with specified parameters.
     * 
     * @param level the target level (0-21)
     * @param pattern a pattern for generating the bits at each level
     * @return a valid tmIndex for the specified level
     */
    public static long createValidTmIndex(int level, BitPattern pattern) {
        long tmIndex = 0L;
        
        for (int i = 0; i < level && i < 10; i++) {
            long levelBits = pattern.getBitsForLevel(i);
            tmIndex |= (levelBits << (i * 6));
        }
        
        return tmIndex;
    }
    
    /**
     * Creates a valid tmIndex for a child of the given parent.
     * 
     * @param parentTmIndex the parent's tmIndex
     * @param parentLevel the parent's level
     * @param childCoords the child's coordinates (0-7)
     * @param childType the child's type (0-5)
     * @return tmIndex for the child
     */
    public static long createChildTmIndex(long parentTmIndex, int parentLevel, 
                                         int childCoords, int childType) {
        if (childCoords < 0 || childCoords > 7) {
            throw new IllegalArgumentException("Child coords must be 0-7, got: " + childCoords);
        }
        if (childType < 0 || childType > 5) {
            throw new IllegalArgumentException("Child type must be 0-5, got: " + childType);
        }
        
        // Shift parent tmIndex left by 6 bits and add child bits
        long childBits = ((long)childCoords << 3) | childType;
        return (parentTmIndex << 6) | childBits;
    }
    
    /**
     * Creates tmIndex values for extended (level > 10) TetreeKeys.
     * Returns an array of [lowBits, highBits].
     * 
     * @param level the target level (11-21)
     * @param pattern a pattern for generating the bits at each level
     * @return array containing [lowBits, highBits]
     */
    public static long[] createExtendedTmIndex(int level, BitPattern pattern) {
        if (level <= 10) {
            throw new IllegalArgumentException("Use createValidTmIndex for levels <= 10");
        }
        
        long lowBits = 0L;
        long highBits = 0L;
        
        // First 10 levels go in lowBits
        for (int i = 0; i < 10; i++) {
            long levelBits = pattern.getBitsForLevel(i);
            lowBits |= (levelBits << (i * 6));
        }
        
        // Remaining levels go in highBits
        for (int i = 10; i < level && i < 21; i++) {
            long levelBits = pattern.getBitsForLevel(i);
            highBits |= (levelBits << ((i - 10) * 6));
        }
        
        return new long[] { lowBits, highBits };
    }
    
    /**
     * Interface for generating bit patterns at each level.
     */
    public interface BitPattern {
        /**
         * Get the 6-bit value for a specific level.
         * The high 3 bits are coordinates (0-7), low 3 bits are type (0-5).
         * 
         * @param level the level (0-based)
         * @return 6-bit value combining coords and type
         */
        long getBitsForLevel(int level);
    }
    
    /**
     * Common bit patterns for testing.
     */
    public static class BitPatterns {
        
        /**
         * All zeros pattern.
         */
        public static final BitPattern ZEROS = level -> 0L;
        
        /**
         * Incrementing pattern: level 0 = 0, level 1 = 1, etc.
         */
        public static final BitPattern INCREMENTING = level -> {
            int coords = level % 8;
            int type = level % 6;
            return ((long)coords << 3) | type;
        };
        
        /**
         * Alternating pattern between two values.
         */
        public static final BitPattern ALTERNATING = level -> {
            if (level % 2 == 0) {
                return 0b010001L; // coords=2, type=1
            } else {
                return 0b101010L; // coords=5, type=2
            }
        };
        
        /**
         * Maximum valid values at each level.
         */
        public static final BitPattern MAX_VALID = level -> {
            return ((long)7 << 3) | 5; // coords=7, type=5 -> 111101
        };
        
        /**
         * Create a custom pattern with fixed coords and type.
         */
        public static BitPattern fixed(final int coords, final int type) {
            if (coords < 0 || coords > 7) {
                throw new IllegalArgumentException("Coords must be 0-7");
            }
            if (type < 0 || type > 5) {
                throw new IllegalArgumentException("Type must be 0-5");
            }
            return level -> ((long)coords << 3) | type;
        }
    }
}