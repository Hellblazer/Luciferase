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
 * Strategy interface for tetrahedron allocation.
 * Implementations can provide pooled or direct allocation.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public interface TetrahedronAllocator {
    
    /**
     * Acquire a tetrahedron with the given vertices.
     */
    Tetrahedron acquire(Vertex a, Vertex b, Vertex c, Vertex d);
    
    /**
     * Acquire a tetrahedron from an array of vertices.
     */
    Tetrahedron acquire(Vertex[] vertices);
    
    /**
     * Release a tetrahedron back to the allocator.
     */
    void release(Tetrahedron t);
    
    /**
     * Release multiple tetrahedra efficiently.
     */
    void releaseBatch(Tetrahedron[] batch, int count);
    
    /**
     * Get allocator statistics.
     */
    String getStatistics();
    
    /**
     * Warm up the allocator with pre-allocated instances.
     */
    void warmUp(int size);
    
    /**
     * Get the current pool size (0 for direct allocation).
     */
    int getPoolSize();
    
    /**
     * Check if this allocator benefits from deferred release.
     */
    boolean supportsDeferredRelease();
    
    /**
     * Get the reuse rate (0-100%).
     */
    double getReuseRate();
}
