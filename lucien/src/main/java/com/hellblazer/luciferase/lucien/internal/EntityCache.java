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
package com.hellblazer.luciferase.lucien.internal;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe cache for frequently accessed entity data to reduce repeated lookups.
 * Uses a simple LRU-style eviction when cache size exceeds limit.
 *
 * @param <ID> The entity ID type
 * @author hal.hildebrand
 */
public class EntityCache<ID extends EntityID> {
    
    private final ConcurrentHashMap<ID, CachedEntityData> cache;
    private final int maxSize;
    private final AtomicInteger approximateSize;
    
    // Statistics for monitoring
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger misses = new AtomicInteger();
    
    public EntityCache(int maxSize) {
        this.maxSize = maxSize;
        this.cache = new ConcurrentHashMap<>(maxSize);
        this.approximateSize = new AtomicInteger();
    }
    
    /**
     * Get cached entity bounds
     */
    public EntityBounds getBounds(ID entityId) {
        var data = cache.get(entityId);
        if (data != null) {
            hits.incrementAndGet();
            return data.bounds;
        }
        misses.incrementAndGet();
        return null;
    }
    
    /**
     * Get cached entity position
     */
    public Point3f getPosition(ID entityId) {
        var data = cache.get(entityId);
        if (data != null) {
            hits.incrementAndGet();
            return data.position;
        }
        misses.incrementAndGet();
        return null;
    }
    
    /**
     * Cache entity data
     */
    public void put(ID entityId, Point3f position, EntityBounds bounds) {
        // Simple size control - if too big, clear oldest entries
        if (approximateSize.get() > maxSize) {
            evictOldest();
        }
        
        var data = new CachedEntityData(position, bounds);
        var previous = cache.put(entityId, data);
        
        if (previous == null) {
            approximateSize.incrementAndGet();
        }
    }
    
    /**
     * Remove entity from cache
     */
    public void remove(ID entityId) {
        if (cache.remove(entityId) != null) {
            approximateSize.decrementAndGet();
        }
    }
    
    /**
     * Clear the cache
     */
    public void clear() {
        cache.clear();
        approximateSize.set(0);
    }
    
    /**
     * Get cache statistics
     */
    public CacheStats getStats() {
        return new CacheStats(hits.get(), misses.get(), cache.size());
    }
    
    /**
     * Reset statistics
     */
    public void resetStats() {
        hits.set(0);
        misses.set(0);
    }
    
    private void evictOldest() {
        // Simple eviction - remove approximately 25% of entries
        int toRemove = maxSize / 4;
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext() && toRemove > 0) {
            iterator.next();
            iterator.remove();
            approximateSize.decrementAndGet();
            toRemove--;
        }
    }
    
    /**
     * Cached entity data
     */
    private static class CachedEntityData {
        final Point3f position;
        final EntityBounds bounds;
        
        CachedEntityData(Point3f position, EntityBounds bounds) {
            this.position = position != null ? new Point3f(position) : null;
            this.bounds = bounds;
        }
    }
    
    /**
     * Cache statistics
     */
    public record CacheStats(int hits, int misses, int size) {
        public double hitRate() {
            int total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
    }
}