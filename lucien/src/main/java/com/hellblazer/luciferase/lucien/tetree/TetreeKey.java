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

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.SpatialKey;

import java.util.Objects;

/**
 * Spatial key implementation for Tetree structures.
 * 
 * Unlike Morton codes used in Octrees, Tetree SFC indices are NOT unique
 * across levels. The same index value can represent different tetrahedra
 * at different levels. This key implementation combines both the level and
 * the SFC index to ensure uniqueness.
 * 
 * The comparison ordering is lexicographic: first by level, then by SFC index.
 * This maintains spatial locality within each level while ensuring keys from
 * different levels don't collide.
 * 
 * @author hal.hildebrand
 */
public final class TetreeKey implements SpatialKey<TetreeKey> {
    
    private final byte level;
    private final long sfcIndex;
    
    /**
     * Create a new TetreeKey from level and SFC index.
     * 
     * @param level the hierarchical level (0-based)
     * @param sfcIndex the space-filling curve index at this level
     */
    public TetreeKey(byte level, long sfcIndex) {
        if (level < 0 || level > Constants.getMaxRefinementLevel()) {
            throw new IllegalArgumentException(
                "Level must be between 0 and " + Constants.getMaxRefinementLevel() + ", got: " + level);
        }
        if (sfcIndex < 0) {
            throw new IllegalArgumentException("SFC index must be non-negative, got: " + sfcIndex);
        }
        this.level = level;
        this.sfcIndex = sfcIndex;
    }
    
    /**
     * Get the SFC index component of this key.
     * 
     * @return the space-filling curve index
     */
    public long getSfcIndex() {
        return sfcIndex;
    }
    
    @Override
    public byte getLevel() {
        return level;
    }
    
    @Override
    public boolean isValid() {
        // Validate that the SFC index is within bounds for this level
        // At level L, there are 8^L possible tetrahedra
        if (level == 0) {
            return sfcIndex == 0;  // Root level has only one tetrahedron
        }
        long maxIndex = (1L << (3 * level)) - 1;  // 8^level - 1
        return sfcIndex >= 0 && sfcIndex <= maxIndex;
    }
    
    @Override
    public int compareTo(TetreeKey other) {
        Objects.requireNonNull(other, "Cannot compare to null TetreeKey");
        
        // Lexicographic ordering: first by level, then by SFC index
        // This ensures:
        // 1. Keys at different levels never collide
        // 2. Within a level, spatial locality is preserved by SFC ordering
        int levelCmp = Byte.compare(this.level, other.level);
        if (levelCmp != 0) {
            return levelCmp;
        }
        return Long.compare(this.sfcIndex, other.sfcIndex);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TetreeKey)) return false;
        TetreeKey tetreeKey = (TetreeKey) o;
        return level == tetreeKey.level && sfcIndex == tetreeKey.sfcIndex;
    }
    
    @Override
    public int hashCode() {
        // Combine level and sfcIndex for good hash distribution
        // Use prime multiplier for level to reduce collisions
        return Objects.hash(level, sfcIndex);
    }
    
    @Override
    public String toString() {
        return String.format("TetreeKey[level=%d, sfcIndex=%d]", level, sfcIndex);
    }
    
    /**
     * Factory method to create a TetreeKey from a Tet instance.
     * 
     * @param tet the tetrahedron
     * @return a new TetreeKey encoding the tet's level and index
     */
    public static TetreeKey fromTet(Tet tet) {
        byte level = tet.l();
        long sfcIndex = tet.index();
        return new TetreeKey(level, sfcIndex);
    }
    
    /**
     * Create a root-level TetreeKey.
     * 
     * @return the key for the root tetrahedron
     */
    public static TetreeKey root() {
        return new TetreeKey((byte) 0, 0L);
    }
}