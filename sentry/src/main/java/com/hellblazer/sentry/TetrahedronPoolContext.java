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
 * Thread-local context for TetrahedronPool access during flip operations.
 * This allows deep method calls to access the appropriate pool without
 * passing it through every method parameter.
 * Also provides deferred release collection for safer memory management.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class TetrahedronPoolContext {
    private static final ThreadLocal<TetrahedronPool> CURRENT_POOL = new ThreadLocal<>();
    private static final ThreadLocal<DeferredReleaseCollector> RELEASE_COLLECTOR = new ThreadLocal<>();
    
    /**
     * Set the current pool for this thread.
     * Should be called at the start of operations that need pool access.
     */
    public static void setPool(TetrahedronPool pool) {
        CURRENT_POOL.set(pool);
    }
    
    /**
     * Get the current pool for this thread.
     * Returns null if no pool has been set.
     */
    public static TetrahedronPool getPool() {
        return CURRENT_POOL.get();
    }
    
    /**
     * Clear the current pool for this thread.
     * Should be called when operations are complete to avoid memory leaks.
     */
    public static void clearPool() {
        CURRENT_POOL.remove();
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
     * If no collector is active, releases immediately.
     */
    public static void deferRelease(Tetrahedron t) {
        DeferredReleaseCollector collector = RELEASE_COLLECTOR.get();
        if (collector != null) {
            collector.markForRelease(t);
        } else {
            // No deferred release active, release immediately
            TetrahedronPool pool = CURRENT_POOL.get();
            if (pool != null && t != null && t.isDeleted()) {
                pool.release(t);
            }
        }
    }
    
    /**
     * Execute a task with a specific pool context.
     * The pool is automatically cleared after the task completes.
     */
    public static void withPool(TetrahedronPool pool, Runnable task) {
        TetrahedronPool previous = CURRENT_POOL.get();
        DeferredReleaseCollector collector = new DeferredReleaseCollector(pool);
        DeferredReleaseCollector previousCollector = RELEASE_COLLECTOR.get();
        try {
            CURRENT_POOL.set(pool);
            RELEASE_COLLECTOR.set(collector);
            task.run();
            // Release all deferred tetrahedra after task completes
            collector.releaseAll();
        } finally {
            if (previous != null) {
                CURRENT_POOL.set(previous);
            } else {
                CURRENT_POOL.remove();
            }
            if (previousCollector != null) {
                RELEASE_COLLECTOR.set(previousCollector);
            } else {
                RELEASE_COLLECTOR.remove();
            }
        }
    }
    
    /**
     * Execute a task with a specific pool context and return a result.
     * The pool is automatically cleared after the task completes.
     */
    public static <T> T withPool(TetrahedronPool pool, java.util.function.Supplier<T> task) {
        TetrahedronPool previous = CURRENT_POOL.get();
        DeferredReleaseCollector collector = new DeferredReleaseCollector(pool);
        DeferredReleaseCollector previousCollector = RELEASE_COLLECTOR.get();
        try {
            CURRENT_POOL.set(pool);
            RELEASE_COLLECTOR.set(collector);
            T result = task.get();
            // Release all deferred tetrahedra after task completes
            collector.releaseAll();
            return result;
        } finally {
            if (previous != null) {
                CURRENT_POOL.set(previous);
            } else {
                CURRENT_POOL.remove();
            }
            if (previousCollector != null) {
                RELEASE_COLLECTOR.set(previousCollector);
            } else {
                RELEASE_COLLECTOR.remove();
            }
        }
    }
}