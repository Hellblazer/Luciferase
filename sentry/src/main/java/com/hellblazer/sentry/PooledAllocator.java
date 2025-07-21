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
package com.hellblazer.sentry;

/**
 * Pooled allocator implementation using TetrahedronPool.
 * Provides object pooling for performance optimization.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class PooledAllocator implements TetrahedronAllocator {
    private final TetrahedronPool pool;
    
    public PooledAllocator(TetrahedronPool pool) {
        this.pool = pool;
    }
    
    @Override
    public Tetrahedron acquire(Vertex a, Vertex b, Vertex c, Vertex d) {
        return pool.acquire(a, b, c, d);
    }
    
    @Override
    public Tetrahedron acquire(Vertex[] vertices) {
        return pool.acquire(vertices);
    }
    
    @Override
    public void release(Tetrahedron t) {
        pool.release(t);
    }
    
    @Override
    public void releaseBatch(Tetrahedron[] batch, int count) {
        pool.releaseBatch(batch, count);
    }
    
    @Override
    public String getStatistics() {
        return pool.getStatistics();
    }
    
    @Override
    public void warmUp(int size) {
        pool.warmUp(size);
    }
    
    @Override
    public int getPoolSize() {
        return pool.getPoolSize();
    }
    
    @Override
    public boolean supportsDeferredRelease() {
        return true;
    }
    
    @Override
    public double getReuseRate() {
        return pool.getReuseRate();
    }
    
    // Package-private access to underlying pool for testing
    TetrahedronPool getPool() {
        return pool;
    }
}