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
 * Build configuration for the rendering server.
 * <p>
 * Contains parameters for region building, queue management, and circuit breaker.
 * <p>
 * Thread-safe: immutable record.
 *
 * @param buildPoolSize Number of concurrent build threads
 * @param maxBuildDepth Maximum octree/tetree depth within a region
 * @param gridResolution Voxel grid resolution per region (e.g., 64 for 64³)
 * @param maxQueueDepth Maximum build queue depth
 * @param circuitBreakerTimeoutMs Circuit breaker timeout in milliseconds
 * @param circuitBreakerFailureThreshold Number of consecutive failures before circuit opens
 * @author hal.hildebrand
 */
public record BuildConfig(
    int buildPoolSize,
    int maxBuildDepth,
    int gridResolution,
    int maxQueueDepth,
    long circuitBreakerTimeoutMs,
    int circuitBreakerFailureThreshold
) {
    /**
     * Create default build configuration for production.
     *
     * @return Default build config
     */
    public static BuildConfig defaults() {
        return new BuildConfig(
            1,       // buildPoolSize
            8,       // maxBuildDepth
            64,      // gridResolution (64³ voxels)
            100,     // maxQueueDepth
            60_000L, // circuitBreakerTimeoutMs (60 seconds)
            3        // circuitBreakerFailureThreshold
        );
    }

    /**
     * Create build configuration for testing.
     *
     * @return Test build config with smaller parameters
     */
    public static BuildConfig testing() {
        return new BuildConfig(
            1,       // buildPoolSize
            4,       // maxBuildDepth (shallow for fast tests)
            16,      // gridResolution (16³ voxels - small)
            50,      // maxQueueDepth (small queue)
            10_000L, // circuitBreakerTimeoutMs (10 seconds - fast tests)
            3        // circuitBreakerFailureThreshold
        );
    }
}
