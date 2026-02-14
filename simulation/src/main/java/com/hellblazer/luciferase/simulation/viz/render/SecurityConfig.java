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
 * Security configuration for the rendering server.
 * <p>
 * All 8 fields are defined upfront (V3 requirement). Phase 2 beads implement
 * server-side logic that reads these fields.
 * <p>
 * Thread-safe: immutable record.
 *
 * @param apiKey API key for authentication (null = no auth)
 * @param redactSensitiveInfo Whether to redact sensitive information in logs/responses
 * @param tlsEnabled Whether TLS/HTTPS is enabled
 * @param keystorePath Path to TLS keystore file (null until TLS implemented)
 * @param keystorePassword Password for TLS keystore (null until TLS implemented)
 * @param keyManagerPassword Password for TLS key manager (null until TLS implemented)
 * @param rateLimitEnabled Whether rate limiting is enabled
 * @param rateLimitRequestsPerMinute Maximum requests per minute (0 = unlimited)
 * @author hal.hildebrand
 */
public record SecurityConfig(
    String apiKey,
    boolean redactSensitiveInfo,
    boolean tlsEnabled,
    String keystorePath,
    String keystorePassword,
    String keyManagerPassword,
    boolean rateLimitEnabled,
    int rateLimitRequestsPerMinute
) {
    /**
     * Create secure configuration with API key authentication and TLS.
     *
     * @param apiKey API key for authentication (required)
     * @param tlsEnabled Whether to enable TLS (recommended true for production)
     * @return Secure configuration with all protections enabled
     */
    public static SecurityConfig secure(String apiKey, boolean tlsEnabled) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key required for secure configuration");
        }
        return new SecurityConfig(
            apiKey,
            true,      // redactSensitiveInfo
            tlsEnabled,
            null,      // keystorePath - set when TLS implementation complete
            null,      // keystorePassword
            null,      // keyManagerPassword
            true,      // rateLimitEnabled
            100        // rateLimitRequestsPerMinute
        );
    }

    /**
     * Create permissive configuration with all security features disabled.
     * <p>
     * Suitable for local development and testing only.
     *
     * @return Permissive configuration (no authentication, no TLS, no rate limiting)
     */
    public static SecurityConfig permissive() {
        return new SecurityConfig(
            null,      // apiKey - no auth
            false,     // redactSensitiveInfo
            false,     // tlsEnabled
            null,      // keystorePath
            null,      // keystorePassword
            null,      // keyManagerPassword
            false,     // rateLimitEnabled
            0          // rateLimitRequestsPerMinute - unlimited
        );
    }
}
