/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the 3D Incremental Voronoi system
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
package com.hellblazer.sentry;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Object pool for Tetrahedron instances to reduce GC pressure during flip operations.
 * Each MutableGrid instance should have its own pool to avoid interference.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class TetrahedronPool {
    private static final int DEFAULT_MAX_SIZE = 1024;
    private static final int INITIAL_SIZE = 64;
    
    private final Deque<Tetrahedron> pool = new ArrayDeque<>(INITIAL_SIZE);
    private int size = 0;
    private final int maxSize;
    
    // Metrics for monitoring
    private int acquireCount = 0;
    private int releaseCount = 0;
    private int createCount = 0;
    
    /**
     * Create a new TetrahedronPool with the specified maximum size
     */
    public TetrahedronPool(int maxSize) {
        this.maxSize = maxSize;
        // Pre-populate the pool
        for (int i = 0; i < INITIAL_SIZE; i++) {
            pool.addLast(new Tetrahedron(null));
            size++;
        }
    }
    
    /**
     * Create a new TetrahedronPool with the default maximum size
     */
    public TetrahedronPool() {
        this(DEFAULT_MAX_SIZE);
    }
    
    /**
     * Acquire a Tetrahedron from the pool or create a new one if pool is empty.
     * The Tetrahedron is reset before being returned.
     */
    public Tetrahedron acquire(Vertex a, Vertex b, Vertex c, Vertex d) {
        acquireCount++;
        
        Tetrahedron t = pool.pollFirst();
        if (t == null) {
            createCount++;
            t = new Tetrahedron(null);
        } else {
            size--;
        }
        
        // Reset and initialize the tetrahedron
        t.reset(a, b, c, d);
        return t;
    }
    
    /**
     * Acquire a Tetrahedron from the pool for the four corners case.
     */
    public Tetrahedron acquire(Vertex[] fourCorners) {
        return acquire(fourCorners[0], fourCorners[1], fourCorners[2], fourCorners[3]);
    }
    
    /**
     * Release a Tetrahedron back to the pool.
     * The tetrahedron should be cleaned up before release.
     * 
     * WARNING: Only call this when you are certain no other
     * tetrahedra hold references to this one through neighbor pointers.
     */
    public void release(Tetrahedron t) {
        if (t == null || size >= maxSize) {
            return;
        }
        
        // Safety check - don't release if it's not truly deleted
        if (!t.isDeleted()) {
            return;
        }
        
        releaseCount++;
        
        // Clear the tetrahedron before returning to pool
        t.clearForReuse();
        
        pool.addLast(t);
        size++;
    }
    
    /**
     * Release multiple tetrahedra back to the pool
     */
    public void releaseAll(Tetrahedron[] tetrahedra) {
        if (tetrahedra == null) {
            return;
        }
        
        for (Tetrahedron t : tetrahedra) {
            release(t);
        }
    }
    
    /**
     * Get current pool size
     */
    public int getPoolSize() {
        return size;
    }
    
    /**
     * Get maximum pool size
     */
    public int getMaxSize() {
        return maxSize;
    }
    
    /**
     * Get pool statistics for monitoring
     */
    public String getStatistics() {
        return String.format("Pool[size=%d, acquired=%d, released=%d, created=%d, reuse-rate=%.2f%%]",
            size, 
            acquireCount, 
            releaseCount, 
            createCount,
            acquireCount > 0 ? 
                (100.0 * (acquireCount - createCount) / acquireCount) : 0.0);
    }
    
    /**
     * Clear the pool and reset statistics (mainly for testing)
     */
    public void clear() {
        pool.clear();
        size = 0;
        acquireCount = 0;
        releaseCount = 0;
        createCount = 0;
    }
}