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
 * Performance tuning configuration for the rendering server.
 * <p>
 * Contains timeouts, buffer sizes, and cache parameters that affect performance and resource usage.
 * <p>
 * Thread-safe: immutable record.
 *
 * @param regionCacheTtlMs         Time-to-live for cached region data (milliseconds)
 * @param endpointCacheExpireSec   Expire endpoint cache entries after this duration (seconds)
 * @param endpointCacheMaxSize     Maximum number of cached endpoint responses
 * @param httpConnectTimeoutSec    HTTP client connect timeout for upstream connections (seconds)
 * @param decompressionBufferSize  Buffer size for GZIP decompression operations (bytes)
 * @author hal.hildebrand
 */
public record PerformanceConfig(
    long regionCacheTtlMs,
    long endpointCacheExpireSec,
    int endpointCacheMaxSize,
    long httpConnectTimeoutSec,
    int decompressionBufferSize
) {
    /**
     * Compact constructor with validation.
     */
    public PerformanceConfig {
        if (regionCacheTtlMs < 1000) {
            throw new IllegalArgumentException("regionCacheTtlMs must be >= 1000");
        }
        if (endpointCacheExpireSec < 1) {
            throw new IllegalArgumentException("endpointCacheExpireSec must be >= 1");
        }
        if (endpointCacheMaxSize < 1) {
            throw new IllegalArgumentException("endpointCacheMaxSize must be >= 1");
        }
        if (httpConnectTimeoutSec < 1) {
            throw new IllegalArgumentException("httpConnectTimeoutSec must be >= 1");
        }
        if (decompressionBufferSize < 1024) {
            throw new IllegalArgumentException("decompressionBufferSize must be >= 1024");
        }
    }

    /**
     * Default production configuration.
     */
    public static PerformanceConfig defaults() {
        return new PerformanceConfig(
            30_000L,  // 30s region cache TTL
            1L,       // 1s endpoint cache expiry
            10,       // Small endpoint cache
            10L,      // 10s HTTP connect timeout
            8192      // 8KB decompression buffer
        );
    }

    /**
     * Test configuration with shorter timeouts and smaller buffers.
     */
    public static PerformanceConfig testing() {
        return new PerformanceConfig(
            5_000L,  // 5s region cache TTL (faster turnover in tests)
            1L,      // 1s endpoint cache expiry (same as production)
            5,       // Smaller endpoint cache for tests
            5L,      // 5s HTTP connect timeout (faster test failures)
            4096     // 4KB decompression buffer (smaller for tests)
        );
    }
}
