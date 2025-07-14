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

import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Provides lazy Stream-based evaluation of SFC ranges using Java Spliterators.
 * This enables efficient functional operations on large spatial ranges without
 * materializing all keys in memory.
 *
 * @author hal.hildebrand
 */
public class LazySFCRangeStream {
    
    /**
     * Create a lazy stream of TetreeKeys for the given range.
     *
     * @param range The SFC range to stream
     * @return A lazy stream of TetreeKeys
     */
    public static Stream<TetreeKey<? extends TetreeKey>> stream(Tet.SFCRange range) {
        // Convert TetreeKey range boundaries to Tet objects for iteration
        var startTet = Tet.tetrahedron(range.start());
        var endTet = Tet.tetrahedron(range.end());
        
        // Create spliterator with known characteristics
        var spliterator = new TetreeRangeSpliterator(startTet, endTet);
        
        // Create stream with parallel support
        return StreamSupport.stream(spliterator, true);
    }
    
    /**
     * Create a lazy stream from multiple SFC ranges.
     *
     * @param ranges The SFC ranges to stream
     * @return A lazy stream of TetreeKeys from all ranges
     */
    public static Stream<TetreeKey<? extends TetreeKey>> streamMultiple(Stream<Tet.SFCRange> ranges) {
        return ranges.flatMap(LazySFCRangeStream::stream);
    }
    
    /**
     * Spliterator implementation for efficient stream operations on TetreeKey ranges.
     */
    private static class TetreeRangeSpliterator implements Spliterator<TetreeKey<? extends TetreeKey>> {
        
        private final LazyRangeIterator iterator;
        private final long estimatedSize;
        
        TetreeRangeSpliterator(Tet startTet, Tet endTet) {
            this.iterator = new LazyRangeIterator(startTet, endTet);
            this.estimatedSize = iterator.estimateSize();
        }
        
        @Override
        public boolean tryAdvance(Consumer<? super TetreeKey<? extends TetreeKey>> action) {
            if (iterator.hasNext()) {
                action.accept(iterator.next());
                return true;
            }
            return false;
        }
        
        @Override
        public Spliterator<TetreeKey<? extends TetreeKey>> trySplit() {
            // For now, don't support splitting - could be enhanced later
            // to split ranges for better parallel performance
            return null;
        }
        
        @Override
        public long estimateSize() {
            return estimatedSize;
        }
        
        @Override
        public int characteristics() {
            return ORDERED | SIZED | SUBSIZED | NONNULL | IMMUTABLE;
        }
    }
    
    /**
     * Enhanced spliterator that supports splitting for parallel operations.
     */
    public static class ParallelTetreeRangeSpliterator implements Spliterator<TetreeKey<? extends TetreeKey>> {
        
        private Tet currentStart;
        private final Tet end;
        private final byte level;
        private long remainingEstimate;
        
        public ParallelTetreeRangeSpliterator(Tet start, Tet end) {
            this.currentStart = start;
            this.end = end;
            this.level = start.l;
            this.remainingEstimate = estimateRange(start, end);
        }
        
        @Override
        public boolean tryAdvance(Consumer<? super TetreeKey<? extends TetreeKey>> action) {
            if (currentStart != null && shouldContinue()) {
                action.accept(currentStart.tmIndex());
                currentStart = nextTet(currentStart);
                remainingEstimate--;
                return true;
            }
            return false;
        }
        
        @Override
        public Spliterator<TetreeKey<? extends TetreeKey>> trySplit() {
            if (remainingEstimate <= 1) {
                return null;
            }
            
            // Find midpoint for splitting
            Tet mid = findMidpoint(currentStart, end);
            if (mid == null || mid.equals(currentStart)) {
                return null;
            }
            
            // Create new spliterator for first half
            var firstHalf = new ParallelTetreeRangeSpliterator(currentStart, mid);
            
            // Update this spliterator to second half
            currentStart = nextTet(mid);
            remainingEstimate = estimateRange(currentStart, end);
            
            return firstHalf;
        }
        
        @Override
        public long estimateSize() {
            return remainingEstimate;
        }
        
        @Override
        public int characteristics() {
            return ORDERED | SIZED | SUBSIZED | NONNULL | IMMUTABLE | CONCURRENT;
        }
        
        private boolean shouldContinue() {
            return currentStart != null && 
                   currentStart.tmIndex().compareTo(end.tmIndex()) <= 0;
        }
        
        private Tet nextTet(Tet current) {
            if (current.type < 5) {
                return new Tet(current.x, current.y, current.z, current.l, (byte)(current.type + 1));
            }
            
            int cellSize = current.length();
            int maxCoord = (1 << MortonCurve.MAX_REFINEMENT_LEVEL);
            
            if (current.x + cellSize < maxCoord) {
                return new Tet(current.x + cellSize, current.y, current.z, current.l, (byte)0);
            }
            
            if (current.y + cellSize < maxCoord) {
                return new Tet(0, current.y + cellSize, current.z, current.l, (byte)0);
            }
            
            if (current.z + cellSize < maxCoord) {
                return new Tet(0, 0, current.z + cellSize, current.l, (byte)0);
            }
            
            return null;
        }
        
        private Tet findMidpoint(Tet start, Tet end) {
            // Simple midpoint calculation based on grid coordinates
            int midX = (start.x + end.x) / 2;
            int midY = (start.y + end.y) / 2;
            int midZ = (start.z + end.z) / 2;
            
            // Align to grid
            int cellSize = start.length();
            midX = (midX / cellSize) * cellSize;
            midY = (midY / cellSize) * cellSize;
            midZ = (midZ / cellSize) * cellSize;
            
            // Use type 3 as midpoint type (middle of 0-5 range)
            return new Tet(midX, midY, midZ, level, (byte)3);
        }
        
        private long estimateRange(Tet start, Tet end) {
            if (start == null || end == null) {
                return 0;
            }
            
            int cellSize = start.length();
            long cells = ((long)(end.x - start.x) / cellSize + 1) *
                        ((long)(end.y - start.y) / cellSize + 1) *
                        ((long)(end.z - start.z) / cellSize + 1);
            return cells * 6; // 6 tetrahedra per cell
        }
    }
}
