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

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * High-performance spatial index set optimized for O(1) operations. Replaces TreeSet to eliminate O(log n) overhead in
 * spatial operations.
 *
 * This implementation uses a hybrid approach: - Hash-based storage for O(1) add/remove/contains - Level-based bucketing
 * for efficient range queries - Lazy sorting only when needed for traversal
 *
 * @author hal.hildebrand
 */
public class SpatialIndexSet<Key extends SpatialKey<Key>> implements NavigableSet<Key> {

    private static final int                 SORT_THRESHOLD    = 100; // Re-sort after this many modifications
    // Primary storage - O(1) operations
    private final        Set<Key>            indices           = ConcurrentHashMap.newKeySet();
    // Level-based buckets for efficient range queries
    // Key: level (0-21), Value: Set of indices at that level
    private final        Map<Byte, Set<Key>> levelBuckets      = new ConcurrentHashMap<>();
    // Cached sorted view (lazily computed)
    private volatile     NavigableSet<Key>   sortedView        = null;
    // Statistics for optimization
    private              long                modificationCount = 0;

    @Override
    public boolean add(Key index) {
        if (index == null) {
            throw new NullPointerException();
        }

        boolean added = indices.add(index);
        if (added) {
            // Add to level bucket
            levelBuckets.computeIfAbsent(index.getLevel(), k -> ConcurrentHashMap.newKeySet()).add(index);

            // Invalidate sorted view
            invalidateSortedView();
        }
        return added;
    }

    @Override
    public boolean addAll(Collection<? extends Key> c) {
        boolean modified = false;
        for (var e : c) {
            if (add(e)) {
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public Key ceiling(Key e) {
        return getSortedView().ceiling(e);
    }

    @Override
    public void clear() {
        indices.clear();
        levelBuckets.clear();
        sortedView = null;
    }

    @Override
    public Comparator<? super Key> comparator() {
        return null; // Natural ordering
    }

    @Override
    public boolean contains(Object o) {
        return indices.contains(o);
    }

    // NavigableSet methods - these require sorting

    @Override
    public boolean containsAll(Collection<?> c) {
        return indices.containsAll(c);
    }

    @Override
    public Iterator<Key> descendingIterator() {
        return getSortedView().descendingIterator();
    }

    @Override
    public NavigableSet<Key> descendingSet() {
        return getSortedView().descendingSet();
    }

    @Override
    public Key first() {
        return getSortedView().first();
    }

    @Override
    public Key floor(Key e) {
        return getSortedView().floor(e);
    }

    /**
     * Get all indices at a specific level - O(1) operation
     */
    public Set<Key> getIndicesAtLevel(byte level) {
        var bucket = levelBuckets.get(level);
        return bucket != null ? new HashSet<>(bucket) : Collections.emptySet();
    }

    /**
     * Get all indices between levels (inclusive) - O(levels) operation
     */
    public Set<Key> getIndicesBetweenLevels(byte minLevel, byte maxLevel) {
        Set<Key> result = new HashSet<>();
        for (byte level = minLevel; level <= maxLevel; level++) {
            var bucket = levelBuckets.get(level);
            if (bucket != null) {
                result.addAll(bucket);
            }
        }
        return result;
    }

    @Override
    public NavigableSet<Key> headSet(Key toElement, boolean inclusive) {
        return getSortedView().headSet(toElement, inclusive);
    }

    @Override
    public SortedSet<Key> headSet(Key toElement) {
        return headSet(toElement, false);
    }

    @Override
    public Key higher(Key e) {
        return getSortedView().higher(e);
    }

    @Override
    public boolean isEmpty() {
        return indices.isEmpty();
    }

    @Override
    public Iterator<Key> iterator() {
        return getSortedView().iterator();
    }

    @Override
    public Key last() {
        return getSortedView().last();
    }

    @Override
    public Key lower(Key e) {
        return getSortedView().lower(e);
    }

    @Override
    public Key pollFirst() {
        Key first = getSortedView().pollFirst();
        if (first != null) {
            remove(first);
        }
        return first;
    }

    @Override
    public Key pollLast() {
        Key last = getSortedView().pollLast();
        if (last != null) {
            remove(last);
        }
        return last;
    }

    @Override
    public boolean remove(Object o) {
        var index = (Key) o;

        boolean removed = indices.remove(index);
        if (removed) {
            // Remove from level bucket
            byte level = index.getLevel();
            Set<Key> bucket = levelBuckets.get(level);
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
    public boolean removeAll(Collection<?> c) {
        boolean modified = false;
        for (Object o : c) {
            if (remove(o)) {
                modified = true;
            }
        }
        return modified;
    }

    // Collection methods

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean modified = false;
        Iterator<Key> it = indices.iterator();
        while (it.hasNext()) {
            Key index = it.next();
            if (!c.contains(index)) {
                it.remove();

                // Remove from level bucket
                byte level = index.getLevel();
                Set<Key> bucket = levelBuckets.get(level);
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
    public int size() {
        return indices.size();
    }

    @Override
    public NavigableSet<Key> subSet(Key fromElement, boolean fromInclusive, Key toElement, boolean toInclusive) {
        return getSortedView().subSet(fromElement, fromInclusive, toElement, toInclusive);
    }

    @Override
    public SortedSet<Key> subSet(Key fromElement, Key toElement) {
        return subSet(fromElement, true, toElement, false);
    }

    @Override
    public NavigableSet<Key> tailSet(Key fromElement, boolean inclusive) {
        return getSortedView().tailSet(fromElement, inclusive);
    }

    @Override
    public SortedSet<Key> tailSet(Key fromElement) {
        return tailSet(fromElement, true);
    }

    // Optimized range query methods

    @Override
    public Object[] toArray() {
        return indices.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return indices.toArray(a);
    }

    private NavigableSet<Key> getSortedView() {
        NavigableSet<Key> view = sortedView;
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

    private void invalidateSortedView() {
        sortedView = null;
        modificationCount++;
    }
}
