/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

/**
 * Cache configuration for the rendering server.
 * <p>
 * Thread-safe: immutable record.
 * <p>
 * <b>Emergency Eviction Thresholds:</b>
 * <ul>
 *   <li><b>Trigger</b>: Emergency eviction activates when total memory usage
 *       (pinned + unpinned) reaches 90% of maxCacheMemoryBytes</li>
 *   <li><b>Target</b>: Eviction removes least-recently-used entries until
 *       total memory drops to 75% of maxCacheMemoryBytes</li>
 *   <li><b>Implementation</b>: See RegionCache.emergencyEvict() for details</li>
 * </ul>
 * <p>
 * These thresholds ensure cache memory stays bounded even when pinned regions
 * temporarily exceed normal limits (e.g., during high-frequency entity updates).
 *
 * @param maxCacheMemoryBytes Maximum cache memory in bytes
 * @author hal.hildebrand
 */
public record CacheConfig(
    long maxCacheMemoryBytes
) {
    /**
     * Create default cache configuration for production.
     *
     * @return Default cache config (256 MB)
     */
    public static CacheConfig defaults() {
        return new CacheConfig(256 * 1024 * 1024L);  // 256 MB
    }

    /**
     * Create cache configuration for testing.
     *
     * @return Test cache config (16 MB)
     */
    public static CacheConfig testing() {
        return new CacheConfig(16 * 1024 * 1024L);  // 16 MB
    }
}
