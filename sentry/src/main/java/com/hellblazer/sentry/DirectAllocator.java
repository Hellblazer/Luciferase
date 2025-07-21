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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct allocator implementation without pooling.
 * Creates new instances on each acquire, relies on GC for cleanup.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class DirectAllocator implements TetrahedronAllocator {
    private final AtomicLong acquiredCount = new AtomicLong();
    private final AtomicLong releasedCount = new AtomicLong();
    
    @Override
    public Tetrahedron acquire(Vertex a, Vertex b, Vertex c, Vertex d) {
        acquiredCount.incrementAndGet();
        return new Tetrahedron(a, b, c, d);
    }
    
    @Override
    public Tetrahedron acquire(Vertex[] vertices) {
        acquiredCount.incrementAndGet();
        return new Tetrahedron(vertices);
    }
    
    @Override
    public void release(Tetrahedron t) {
        releasedCount.incrementAndGet();
        // No-op - let GC handle cleanup
    }
    
    @Override
    public void releaseBatch(Tetrahedron[] batch, int count) {
        releasedCount.addAndGet(count);
        // No-op - let GC handle cleanup
    }
    
    @Override
    public String getStatistics() {
        return String.format("Direct[acquired=%d, released=%d, active=%d]", 
            acquiredCount.get(), 
            releasedCount.get(),
            acquiredCount.get() - releasedCount.get());
    }
    
    @Override
    public void warmUp(int size) {
        // No-op - nothing to warm up
    }
    
    @Override
    public int getPoolSize() {
        return 0; // No pool
    }
    
    @Override
    public boolean supportsDeferredRelease() {
        return false; // No benefit from deferred release
    }
    
    @Override
    public double getReuseRate() {
        return 0.0; // No reuse
    }
}