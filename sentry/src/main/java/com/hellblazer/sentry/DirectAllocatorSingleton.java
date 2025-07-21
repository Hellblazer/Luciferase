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
 * Singleton instance of DirectAllocator for use as fallback
 * when no allocator context is set.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
enum DirectAllocatorSingleton implements TetrahedronAllocator {
    INSTANCE;
    
    private final DirectAllocator delegate = new DirectAllocator();
    
    @Override
    public Tetrahedron acquire(Vertex a, Vertex b, Vertex c, Vertex d) {
        return delegate.acquire(a, b, c, d);
    }
    
    @Override
    public Tetrahedron acquire(Vertex[] vertices) {
        return delegate.acquire(vertices);
    }
    
    @Override
    public void release(Tetrahedron t) {
        delegate.release(t);
    }
    
    @Override
    public void releaseBatch(Tetrahedron[] batch, int count) {
        delegate.releaseBatch(batch, count);
    }
    
    @Override
    public String getStatistics() {
        return delegate.getStatistics();
    }
    
    @Override
    public void warmUp(int size) {
        delegate.warmUp(size);
    }
    
    @Override
    public int getPoolSize() {
        return delegate.getPoolSize();
    }
    
    @Override
    public boolean supportsDeferredRelease() {
        return delegate.supportsDeferredRelease();
    }
    
    @Override
    public double getReuseRate() {
        return delegate.getReuseRate();
    }
}