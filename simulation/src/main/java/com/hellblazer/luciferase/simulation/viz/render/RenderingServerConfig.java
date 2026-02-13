/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import java.util.List;

/**
 * Configuration for the GPU-accelerated rendering server.
 * <p>
 * Thread-safe: immutable record.
 *
 * @param port                   Server listen port (0 for dynamic assignment in tests)
 * @param upstreams              List of upstream simulation server configurations
 * @param regionLevel            Octree depth for region subdivision (3-6, default 4 = 16^3 regions)
 * @param gridResolution         Voxel grid resolution per region (32-128, default 64)
 * @param maxBuildDepth          Max ESVO/ESVT tree depth within a region (default 8)
 * @param maxCacheMemoryBytes    Region cache memory limit (default 256 MB)
 * @param regionTtlMs            TTL for invisible cached regions (default 30 seconds)
 * @param gpuEnabled             Attempt GPU acceleration (default true)
 * @param gpuPoolSize            Number of concurrent GPU build slots (default 1)
 * @param defaultStructureType   ESVO or ESVT (default ESVO)
 * @author hal.hildebrand
 */
public record RenderingServerConfig(
    int port,
    List<UpstreamConfig> upstreams,
    int regionLevel,
    int gridResolution,
    int maxBuildDepth,
    long maxCacheMemoryBytes,
    long regionTtlMs,
    boolean gpuEnabled,
    int gpuPoolSize,
    SparseStructureType defaultStructureType
) {
    /**
     * Create default configuration for development.
     */
    public static RenderingServerConfig defaults() {
        return new RenderingServerConfig(
            7090,                        // Production port
            List.of(),                   // No upstreams by default
            4,                           // 2^4 = 16 regions per axis = 4096 regions total
            64,                          // 64^3 voxel resolution per region
            8,                           // max 8 levels deep within each region
            256 * 1024 * 1024L,          // 256 MB cache
            30_000L,                     // 30 second TTL
            true,                        // try GPU
            1,                           // 1 GPU build slot
            SparseStructureType.ESVO     // ESVO format
        );
    }

    /**
     * Create test configuration with dynamic port and small parameters.
     */
    public static RenderingServerConfig testing() {
        return new RenderingServerConfig(
            0,                           // Dynamic port
            List.of(),                   // No upstreams by default
            2,                           // 2^2 = 4 regions per axis = 64 regions (fast tests)
            16,                          // 16^3 voxels (small)
            4,                           // shallow tree
            16 * 1024 * 1024L,           // 16 MB cache
            5_000L,                      // 5s TTL
            false,                       // CPU only
            1,
            SparseStructureType.ESVO
        );
    }
}
