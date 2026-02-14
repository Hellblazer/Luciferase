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
 * Uses composition pattern with SecurityConfig, CacheConfig, and BuildConfig sub-records.
 * <p>
 * Thread-safe: immutable record.
 *
 * @param port         Server listen port (0 for dynamic assignment in tests)
 * @param upstreams    List of upstream simulation server configurations
 * @param regionLevel  Octree depth for region subdivision (3-6, default 4 = 16^3 regions)
 * @param security     Security configuration (API keys, TLS, rate limiting)
 * @param cache        Cache configuration (memory limits)
 * @param build        Build configuration (pool size, queue depth, circuit breaker)
 * @author hal.hildebrand
 */
public record RenderingServerConfig(
    int port,
    List<UpstreamConfig> upstreams,
    int regionLevel,
    SecurityConfig security,
    CacheConfig cache,
    BuildConfig build,
    int maxEntitiesPerRegion  // vtet: Limit entities per region to prevent unbounded accumulation
) {
    /**
     * Validate configuration for cross-field consistency.
     * <p>
     * Throws IllegalArgumentException if API key authentication is enabled without TLS.
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate() {
        if (security.apiKey() != null && !security.tlsEnabled()) {
            throw new IllegalArgumentException(
                "TLS must be enabled when using API key authentication"
            );
        }
    }

    /**
     * Create secure configuration with API key authentication and TLS.
     * <p>
     * Recommended for production deployments.
     *
     * @param apiKey API key for authentication (required)
     * @return Secure configuration with TLS enabled
     */
    public static RenderingServerConfig secureDefaults(String apiKey) {
        var config = new RenderingServerConfig(
            7090,                                      // Production port
            List.of(),                                 // No upstreams by default
            4,                                         // 2^4 = 16 regions per axis
            SecurityConfig.secure(apiKey, true),       // Secure with TLS enabled
            CacheConfig.defaults(),                    // 256 MB cache
            BuildConfig.defaults(),                    // Default build params
            10_000                                     // Max 10k entities per region
        );
        config.validate();  // Ensure valid
        return config;
    }

    /**
     * Create default configuration for development.
     *
     * @deprecated Use secureDefaults(apiKey) for production or testing() for tests
     */
    @Deprecated(since = "2026-02-14")
    public static RenderingServerConfig defaults() {
        return new RenderingServerConfig(
            7090,                        // Production port
            List.of(),                   // No upstreams by default
            4,                           // 2^4 = 16 regions per axis = 4096 regions total
            SecurityConfig.permissive(), // No authentication
            CacheConfig.defaults(),      // 256 MB cache
            BuildConfig.defaults(),      // Default build params
            10_000                       // Max 10k entities per region
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
            SecurityConfig.permissive(), // Permissive for tests
            CacheConfig.testing(),       // 16 MB cache
            BuildConfig.testing(),       // Small build params
            1_000                        // Lower limit for tests (1k per region)
        );
    }
}
