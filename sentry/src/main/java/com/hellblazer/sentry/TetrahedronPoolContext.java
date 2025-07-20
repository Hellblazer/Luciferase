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
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class TetrahedronPoolContext {
    private static final ThreadLocal<TetrahedronPool> CURRENT_POOL = new ThreadLocal<>();
    
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
     * Execute a task with a specific pool context.
     * The pool is automatically cleared after the task completes.
     */
    public static void withPool(TetrahedronPool pool, Runnable task) {
        TetrahedronPool previous = CURRENT_POOL.get();
        try {
            CURRENT_POOL.set(pool);
            task.run();
        } finally {
            if (previous != null) {
                CURRENT_POOL.set(previous);
            } else {
                CURRENT_POOL.remove();
            }
        }
    }
    
    /**
     * Execute a task with a specific pool context and return a result.
     * The pool is automatically cleared after the task completes.
     */
    public static <T> T withPool(TetrahedronPool pool, java.util.function.Supplier<T> task) {
        TetrahedronPool previous = CURRENT_POOL.get();
        try {
            CURRENT_POOL.set(pool);
            return task.get();
        } finally {
            if (previous != null) {
                CURRENT_POOL.set(previous);
            } else {
                CURRENT_POOL.remove();
            }
        }
    }
}