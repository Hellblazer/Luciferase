/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.geometry.MortonCurve;

import javax.vecmath.Point3f;
import java.util.*;

/**
 * NavigableMap implementation that provides compatibility with the old Octree API
 * by delegating to SingleContentAdapter methods.
 *
 * @param <Content> the type of content stored
 * @author hal.hildebrand
 */
public class SingleContentNodeDataMap<Content> implements NavigableMap<Long, Content> {
    
    private final SingleContentAdapter<Content> adapter;
    
    public SingleContentNodeDataMap(SingleContentAdapter<Content> adapter) {
        this.adapter = adapter;
    }
    
    @Override
    public Content get(Object key) {
        if (key instanceof Long morton) {
            return adapter.get(morton);
        }
        return null;
    }
    
    @Override
    public Content put(Long key, Content value) {
        var point = MortonCurve.decode(key);
        byte level = Constants.toLevel(key);
        Content previous = adapter.lookup(new Point3f(point[0], point[1], point[2]), level);
        adapter.insert(new Point3f(point[0], point[1], point[2]), level, value);
        return previous;
    }
    
    @Override
    public Content remove(Object key) {
        if (key instanceof Long morton) {
            var point = MortonCurve.decode(morton);
            byte level = Constants.toLevel(morton);
            Content previous = adapter.lookup(new Point3f(point[0], point[1], point[2]), level);
            adapter.remove(new Point3f(point[0], point[1], point[2]), level);
            return previous;
        }
        return null;
    }
    
    @Override
    public boolean containsKey(Object key) {
        if (key instanceof Long morton) {
            return adapter.hasNode(morton);
        }
        return false;
    }
    
    @Override
    public boolean containsValue(Object value) {
        // This would require iterating all entities, which is expensive
        // For now, return false as this is rarely used
        return false;
    }
    
    @Override
    public int size() {
        return adapter.size();
    }
    
    @Override
    public boolean isEmpty() {
        return size() == 0;
    }
    
    @Override
    public void clear() {
        // Not supported - would need to track all keys
        throw new UnsupportedOperationException("Clear not supported");
    }
    
    @Override
    public Set<Long> keySet() {
        // Use getAllNodes() to get all keys
        return adapter.getAllNodes().keySet();
    }
    
    @Override
    public Collection<Content> values() {
        // Use getAllNodes() to get all values
        return adapter.getAllNodes().values();
    }
    
    @Override
    public Set<Entry<Long, Content>> entrySet() {
        // Use getAllNodes() from adapter to get spatial information
        Map<Long, Content> allNodes = adapter.getAllNodes();
        
        // Convert to entry set
        Set<Entry<Long, Content>> entries = new HashSet<>();
        for (Map.Entry<Long, Content> entry : allNodes.entrySet()) {
            entries.add(new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
        }
        
        return entries;
    }
    
    @Override
    public void putAll(Map<? extends Long, ? extends Content> m) {
        for (Entry<? extends Long, ? extends Content> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }
    
    // NavigableMap methods - most are not efficiently implementable
    // without maintaining a separate index of Morton codes
    
    @Override
    public Entry<Long, Content> lowerEntry(Long key) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Long lowerKey(Long key) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Entry<Long, Content> floorEntry(Long key) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Long floorKey(Long key) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Entry<Long, Content> ceilingEntry(Long key) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Long ceilingKey(Long key) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Entry<Long, Content> higherEntry(Long key) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Long higherKey(Long key) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Entry<Long, Content> firstEntry() {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Entry<Long, Content> lastEntry() {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Entry<Long, Content> pollFirstEntry() {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Entry<Long, Content> pollLastEntry() {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public NavigableMap<Long, Content> descendingMap() {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public NavigableSet<Long> navigableKeySet() {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public NavigableSet<Long> descendingKeySet() {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public NavigableMap<Long, Content> subMap(Long fromKey, boolean fromInclusive, Long toKey, boolean toInclusive) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public NavigableMap<Long, Content> headMap(Long toKey, boolean inclusive) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public NavigableMap<Long, Content> tailMap(Long fromKey, boolean inclusive) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Comparator<? super Long> comparator() {
        return null; // Natural ordering
    }
    
    @Override
    public SortedMap<Long, Content> subMap(Long fromKey, Long toKey) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public SortedMap<Long, Content> headMap(Long toKey) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public SortedMap<Long, Content> tailMap(Long fromKey) {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Long firstKey() {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
    
    @Override
    public Long lastKey() {
        throw new UnsupportedOperationException("Navigation operations not supported");
    }
}