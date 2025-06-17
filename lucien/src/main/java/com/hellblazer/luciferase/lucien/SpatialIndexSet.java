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
package com.hellblazer.luciferase.lucien;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * High-performance spatial index set optimized for O(1) operations.
 * Replaces TreeSet to eliminate O(log n) overhead in spatial operations.
 * 
 * This implementation uses a hybrid approach:
 * - Hash-based storage for O(1) add/remove/contains
 * - Level-based bucketing for efficient range queries
 * - Lazy sorting only when needed for traversal
 *
 * @author hal.hildebrand
 */
public class SpatialIndexSet implements NavigableSet<Long> {
    
    // Primary storage - O(1) operations
    private final Set<Long> indices = ConcurrentHashMap.newKeySet();
    
    // Level-based buckets for efficient range queries
    // Key: level (0-21), Value: Set of indices at that level
    private final Map<Byte, Set<Long>> levelBuckets = new ConcurrentHashMap<>();
    
    // Cached sorted view (lazily computed)
    private volatile NavigableSet<Long> sortedView = null;
    
    // Statistics for optimization
    private long modificationCount = 0;
    private static final int SORT_THRESHOLD = 100; // Re-sort after this many modifications
    
    @Override
    public boolean add(Long index) {
        if (index == null) {
            throw new NullPointerException();
        }
        
        boolean added = indices.add(index);
        if (added) {
            // Add to level bucket
            byte level = extractLevel(index);
            levelBuckets.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet()).add(index);
            
            // Invalidate sorted view
            invalidateSortedView();
        }
        return added;
    }
    
    @Override
    public boolean remove(Object o) {
        if (!(o instanceof Long)) {
            return false;
        }
        
        Long index = (Long) o;
        boolean removed = indices.remove(index);
        if (removed) {
            // Remove from level bucket
            byte level = extractLevel(index);
            Set<Long> bucket = levelBuckets.get(level);
            if (bucket != null) {
                bucket.remove(index);
                if (bucket.isEmpty()) {
                    levelBuckets.remove(level);
                }
            }
            
            // Invalidate sorted view
            invalidateSortedView();
        }
        return removed;
    }
    
    @Override
    public boolean contains(Object o) {
        return o instanceof Long && indices.contains(o);
    }
    
    @Override
    public int size() {
        return indices.size();
    }
    
    @Override
    public boolean isEmpty() {
        return indices.isEmpty();
    }
    
    @Override
    public void clear() {
        indices.clear();
        levelBuckets.clear();
        sortedView = null;
    }
    
    // NavigableSet methods - these require sorting
    
    @Override
    public NavigableSet<Long> subSet(Long fromElement, boolean fromInclusive, 
                                     Long toElement, boolean toInclusive) {
        return getSortedView().subSet(fromElement, fromInclusive, toElement, toInclusive);
    }
    
    @Override
    public NavigableSet<Long> headSet(Long toElement, boolean inclusive) {
        return getSortedView().headSet(toElement, inclusive);
    }
    
    @Override
    public NavigableSet<Long> tailSet(Long fromElement, boolean inclusive) {
        return getSortedView().tailSet(fromElement, inclusive);
    }
    
    @Override
    public SortedSet<Long> subSet(Long fromElement, Long toElement) {
        return subSet(fromElement, true, toElement, false);
    }
    
    @Override
    public SortedSet<Long> headSet(Long toElement) {
        return headSet(toElement, false);
    }
    
    @Override
    public SortedSet<Long> tailSet(Long fromElement) {
        return tailSet(fromElement, true);
    }
    
    @Override
    public Long first() {
        return getSortedView().first();
    }
    
    @Override
    public Long last() {
        return getSortedView().last();
    }
    
    @Override
    public Long lower(Long e) {
        return getSortedView().lower(e);
    }
    
    @Override
    public Long floor(Long e) {
        return getSortedView().floor(e);
    }
    
    @Override
    public Long ceiling(Long e) {
        return getSortedView().ceiling(e);
    }
    
    @Override
    public Long higher(Long e) {
        return getSortedView().higher(e);
    }
    
    @Override
    public Long pollFirst() {
        Long first = getSortedView().pollFirst();
        if (first != null) {
            remove(first);
        }
        return first;
    }
    
    @Override
    public Long pollLast() {
        Long last = getSortedView().pollLast();
        if (last != null) {
            remove(last);
        }
        return last;
    }
    
    @Override
    public Iterator<Long> iterator() {
        return getSortedView().iterator();
    }
    
    @Override
    public NavigableSet<Long> descendingSet() {
        return getSortedView().descendingSet();
    }
    
    @Override
    public Iterator<Long> descendingIterator() {
        return getSortedView().descendingIterator();
    }
    
    @Override
    public Comparator<? super Long> comparator() {
        return null; // Natural ordering
    }
    
    // Collection methods
    
    @Override
    public Object[] toArray() {
        return indices.toArray();
    }
    
    @Override
    public <T> T[] toArray(T[] a) {
        return indices.toArray(a);
    }
    
    @Override
    public boolean containsAll(Collection<?> c) {
        return indices.containsAll(c);
    }
    
    @Override
    public boolean addAll(Collection<? extends Long> c) {
        boolean modified = false;
        for (Long e : c) {
            if (add(e)) {
                modified = true;
            }
        }
        return modified;
    }
    
    @Override
    public boolean retainAll(Collection<?> c) {
        boolean modified = false;
        Iterator<Long> it = indices.iterator();
        while (it.hasNext()) {
            Long index = it.next();
            if (!c.contains(index)) {
                it.remove();
                
                // Remove from level bucket
                byte level = extractLevel(index);
                Set<Long> bucket = levelBuckets.get(level);
                if (bucket != null) {
                    bucket.remove(index);
                    if (bucket.isEmpty()) {
                        levelBuckets.remove(level);
                    }
                }
                
                modified = true;
            }
        }
        if (modified) {
            invalidateSortedView();
        }
        return modified;
    }
    
    @Override
    public boolean removeAll(Collection<?> c) {
        boolean modified = false;
        for (Object o : c) {
            if (remove(o)) {
                modified = true;
            }
        }
        return modified;
    }
    
    // Optimized range query methods
    
    /**
     * Get all indices at a specific level - O(1) operation
     */
    public Set<Long> getIndicesAtLevel(byte level) {
        Set<Long> bucket = levelBuckets.get(level);
        return bucket != null ? new HashSet<>(bucket) : Collections.emptySet();
    }
    
    /**
     * Get all indices between levels (inclusive) - O(levels) operation
     */
    public Set<Long> getIndicesBetweenLevels(byte minLevel, byte maxLevel) {
        Set<Long> result = new HashSet<>();
        for (byte level = minLevel; level <= maxLevel; level++) {
            Set<Long> bucket = levelBuckets.get(level);
            if (bucket != null) {
                result.addAll(bucket);
            }
        }
        return result;
    }
    
    // Private helper methods
    
    private void invalidateSortedView() {
        sortedView = null;
        modificationCount++;
    }
    
    private NavigableSet<Long> getSortedView() {
        NavigableSet<Long> view = sortedView;
        if (view == null) {
            synchronized (this) {
                view = sortedView;
                if (view == null) {
                    // Create sorted view from hash set
                    view = new ConcurrentSkipListSet<>(indices);
                    sortedView = view;
                    modificationCount = 0;
                }
            }
        }
        return view;
    }
    
    private byte extractLevel(long index) {
        // Delegate to appropriate implementation (Octree or Tetree)
        // This is a simplified version - actual implementation would use
        // the correct level extraction based on the tree type
        if (index == 0) {
            return 0;
        }
        
        // Try to use the TetreeLevelCache if available
        try {
            Class<?> cacheClass = Class.forName("com.hellblazer.luciferase.lucien.tetree.TetreeLevelCache");
            java.lang.reflect.Method method = cacheClass.getMethod("getLevelFromIndex", long.class);
            return (byte) method.invoke(null, index);
        } catch (Exception e) {
            // Fallback to basic calculation
            int highBit = 63 - Long.numberOfLeadingZeros(index);
            return (byte) ((highBit / 3) + 1);
        }
    }
}