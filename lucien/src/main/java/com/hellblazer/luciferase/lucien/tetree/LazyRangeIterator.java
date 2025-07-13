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

import com.hellblazer.luciferase.geometry.MortonCurve;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * A lazy iterator that generates TetreeKeys on-demand for a spatial range.
 * This avoids materializing all keys in memory and supports early termination.
 *
 * @author hal.hildebrand
 */
public class LazyRangeIterator implements Iterator<TetreeKey<? extends TetreeKey>> {
    
    private final Tet startTet;
    private final Tet endTet;
    private final byte level;
    private Tet currentTet;
    private boolean hasNext;
    
    /**
     * Create a lazy range iterator.
     *
     * @param startTet The first tetrahedron in the range
     * @param endTet The last tetrahedron in the range (inclusive)
     */
    public LazyRangeIterator(Tet startTet, Tet endTet) {
        Objects.requireNonNull(startTet, "Start tetrahedron cannot be null");
        Objects.requireNonNull(endTet, "End tetrahedron cannot be null");
        
        if (startTet.l != endTet.l) {
            throw new IllegalArgumentException("Start and end tetrahedra must be at the same level");
        }
        
        this.startTet = startTet;
        this.endTet = endTet;
        this.level = startTet.l;
        this.currentTet = startTet;
        this.hasNext = true;
        
        // Check if range is valid
        if (startTet.tmIndex().compareTo(endTet.tmIndex()) > 0) {
            this.hasNext = false;
        }
    }
    
    @Override
    public boolean hasNext() {
        return hasNext;
    }
    
    @Override
    public TetreeKey<? extends TetreeKey> next() {
        if (!hasNext) {
            throw new NoSuchElementException("No more elements in range");
        }
        
        TetreeKey<? extends TetreeKey> result = currentTet.tmIndex();
        
        // Check if we've reached the end
        if (currentTet.equals(endTet)) {
            hasNext = false;
        } else {
            // Advance to next tetrahedron in SFC order
            currentTet = findNextTet(currentTet);
            if (currentTet == null || currentTet.tmIndex().compareTo(endTet.tmIndex()) > 0) {
                hasNext = false;
            }
        }
        
        return result;
    }
    
    /**
     * Find the next tetrahedron in SFC order at the same level.
     * This uses the spatial structure to navigate without computing all intermediate keys.
     */
    private Tet findNextTet(Tet current) {
        // Strategy: Use the tetrahedral grid structure to find the next cell
        // We iterate through the 6 types at the current position, then move to the next grid cell
        
        if (current.type < 5) {
            // Next type at same position
            return new Tet(current.x, current.y, current.z, current.l, (byte)(current.type + 1));
        }
        
        // Need to move to next grid cell
        int cellSize = current.length();
        int maxCoord = (1 << MortonCurve.MAX_REFINEMENT_LEVEL);
        
        // Try next cell in X direction
        if (current.x + cellSize < maxCoord) {
            return new Tet(current.x + cellSize, current.y, current.z, current.l, (byte)0);
        }
        
        // Wrap X, increment Y
        if (current.y + cellSize < maxCoord) {
            return new Tet(0, current.y + cellSize, current.z, current.l, (byte)0);
        }
        
        // Wrap X and Y, increment Z
        if (current.z + cellSize < maxCoord) {
            return new Tet(0, 0, current.z + cellSize, current.l, (byte)0);
        }
        
        // Reached end of space at this level
        return null;
    }
    
    /**
     * Estimate the number of elements in this range without iterating.
     * This is an approximation based on grid positions.
     */
    public long estimateSize() {
        if (!hasNext) {
            return 0;
        }
        
        int cellSize = startTet.length();
        
        // Calculate grid coordinates
        long startX = startTet.x / cellSize;
        long startY = startTet.y / cellSize;
        long startZ = startTet.z / cellSize;
        long endX = endTet.x / cellSize;
        long endY = endTet.y / cellSize;
        long endZ = endTet.z / cellSize;
        
        // Estimate based on grid cells
        long cellCount = ((endX - startX + 1) * (endY - startY + 1) * (endZ - startZ + 1));
        
        // Each cell has 6 tetrahedra
        return cellCount * 6;
    }
}
