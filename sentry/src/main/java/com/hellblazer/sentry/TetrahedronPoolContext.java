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

/**
 * Thread-local context for TetrahedronAllocator access during flip operations.
 * This allows deep method calls to access the appropriate allocator without
 * passing it through every method parameter.
 * Also provides deferred release collection for safer memory management.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class TetrahedronPoolContext {
    private static final ThreadLocal<TetrahedronAllocator> CURRENT_ALLOCATOR = new ThreadLocal<>();
    private static final ThreadLocal<DeferredReleaseCollector> RELEASE_COLLECTOR = new ThreadLocal<>();
    
    /**
     * Set the current allocator for this thread.
     */
    public static void setAllocator(TetrahedronAllocator allocator) {
        CURRENT_ALLOCATOR.set(allocator);
    }
    
    /**
     * Get the current allocator for this thread.
     * Falls back to direct allocation if none set.
     */
    public static TetrahedronAllocator getAllocator() {
        TetrahedronAllocator allocator = CURRENT_ALLOCATOR.get();
        return allocator != null ? allocator : DirectAllocatorSingleton.INSTANCE;
    }
    
    /**
     * Clear the current allocator for this thread.
     */
    public static void clearAllocator() {
        CURRENT_ALLOCATOR.remove();
    }
    
    /**
     * Get the current release collector for this thread.
     * Returns null if no collector has been set.
     */
    public static DeferredReleaseCollector getReleaseCollector() {
        return RELEASE_COLLECTOR.get();
    }
    
    /**
     * Mark a tetrahedron for deferred release.
     * If allocator doesn't support deferred release, releases immediately.
     */
    public static void deferRelease(Tetrahedron t) {
        if (t == null || !t.isDeleted()) {
            return;
        }
        
        DeferredReleaseCollector collector = RELEASE_COLLECTOR.get();
        if (collector != null) {
            collector.markForRelease(t);
        } else {
            // No deferred release active, release immediately
            TetrahedronAllocator allocator = getAllocator();
            allocator.release(t);
        }
    }
    
    /**
     * Execute a task with a specific allocator context.
     */
    public static void withAllocator(TetrahedronAllocator allocator, Runnable task) {
        TetrahedronAllocator previous = CURRENT_ALLOCATOR.get();
        DeferredReleaseCollector collector = null;
        DeferredReleaseCollector previousCollector = RELEASE_COLLECTOR.get();
        
        // Only set up deferred release if allocator supports it
        if (allocator.supportsDeferredRelease()) {
            collector = new DeferredReleaseCollector(allocator);
        }
        
        try {
            CURRENT_ALLOCATOR.set(allocator);
            if (collector != null) {
                RELEASE_COLLECTOR.set(collector);
            }
            task.run();
            // Release all deferred tetrahedra after task completes
            if (collector != null) {
                collector.releaseAll();
            }
        } finally {
            // Restore previous state
            if (previous != null) {
                CURRENT_ALLOCATOR.set(previous);
            } else {
                CURRENT_ALLOCATOR.remove();
            }
            if (previousCollector != null) {
                RELEASE_COLLECTOR.set(previousCollector);
            } else {
                RELEASE_COLLECTOR.remove();
            }
        }
    }
    
    /**
     * Execute a task with a specific allocator context and return a result.
     */
    public static <T> T withAllocator(TetrahedronAllocator allocator, 
                                      java.util.function.Supplier<T> task) {
        TetrahedronAllocator previous = CURRENT_ALLOCATOR.get();
        DeferredReleaseCollector collector = null;
        DeferredReleaseCollector previousCollector = RELEASE_COLLECTOR.get();
        
        // Only set up deferred release if allocator supports it
        if (allocator.supportsDeferredRelease()) {
            collector = new DeferredReleaseCollector(allocator);
        }
        
        try {
            CURRENT_ALLOCATOR.set(allocator);
            if (collector != null) {
                RELEASE_COLLECTOR.set(collector);
            }
            T result = task.get();
            // Release all deferred tetrahedra after task completes
            if (collector != null) {
                collector.releaseAll();
            }
            return result;
        } finally {
            // Restore previous state
            if (previous != null) {
                CURRENT_ALLOCATOR.set(previous);
            } else {
                CURRENT_ALLOCATOR.remove();
            }
            if (previousCollector != null) {
                RELEASE_COLLECTOR.set(previousCollector);
            } else {
                RELEASE_COLLECTOR.remove();
            }
        }
    }
    
    /**
     * Clear all thread-local state. Useful for testing.
     */
    public static void clear() {
        CURRENT_ALLOCATOR.remove();
        RELEASE_COLLECTOR.remove();
    }
    
    // Backward compatibility methods (deprecated)
    
    /**
     * @deprecated Use setAllocator instead
     */
    @Deprecated
    public static void setPool(TetrahedronPool pool) {
        setAllocator(new PooledAllocator(pool));
    }
    
    /**
     * @deprecated Use getAllocator instead
     */
    @Deprecated
    public static TetrahedronPool getPool() {
        TetrahedronAllocator allocator = getAllocator();
        if (allocator instanceof PooledAllocator) {
            return ((PooledAllocator) allocator).getPool();
        }
        return null;
    }
    
    /**
     * @deprecated Use clearAllocator instead
     */
    @Deprecated
    public static void clearPool() {
        clearAllocator();
    }
    
    /**
     * @deprecated Use withAllocator instead
     */
    @Deprecated
    public static void withPool(TetrahedronPool pool, Runnable task) {
        withAllocator(new PooledAllocator(pool), task);
    }
    
    /**
     * @deprecated Use withAllocator instead
     */
    @Deprecated
    public static <T> T withPool(TetrahedronPool pool, 
                                 java.util.function.Supplier<T> task) {
        return withAllocator(new PooledAllocator(pool), task);
    }
}