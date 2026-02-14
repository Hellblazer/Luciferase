/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RenderingServerConfig and its composed sub-records.
 *
 * Validates the V3 composition pattern with SecurityConfig, CacheConfig, and BuildConfig.
 */
class RenderingServerConfigTest {

    @Test
    void testSecurityConfigFields() {
        // Verify all 8 SecurityConfig fields are accessible
        var secureConfig = SecurityConfig.secure("test-api-key", true);

        assertEquals("test-api-key", secureConfig.apiKey());
        assertTrue(secureConfig.redactSensitiveInfo());
        assertTrue(secureConfig.tlsEnabled());
        assertNull(secureConfig.keystorePath());  // Not set yet
        assertNull(secureConfig.keystorePassword());
        assertNull(secureConfig.keyManagerPassword());
        assertTrue(secureConfig.rateLimitEnabled());
        assertEquals(100, secureConfig.rateLimitRequestsPerMinute());
    }

    @Test
    void testSecurityConfigPermissive() {
        // Verify permissive factory has all security features disabled
        var permissive = SecurityConfig.permissive();

        assertNull(permissive.apiKey());
        assertFalse(permissive.redactSensitiveInfo());
        assertFalse(permissive.tlsEnabled());
        assertNull(permissive.keystorePath());
        assertNull(permissive.keystorePassword());
        assertNull(permissive.keyManagerPassword());
        assertFalse(permissive.rateLimitEnabled());
        assertEquals(0, permissive.rateLimitRequestsPerMinute());
    }

    @Test
    void testConfigValidationApiKeyWithoutTls() {
        // V3: Verify validate() throws IllegalArgumentException for apiKey without TLS
        var config = new RenderingServerConfig(
            7090,
            List.of(),
            4,
            new SecurityConfig("test-key", false, false, null, null, null, false, 0),  // API key but TLS disabled
            new CacheConfig(256 * 1024 * 1024L),
            BuildConfig.defaults(), 10_000
        );

        var exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("TLS must be enabled when using API key authentication"));
    }

    @Test
    void testConfigValidationPass() {
        // Verify validate() passes for valid config (API key with TLS)
        var config = new RenderingServerConfig(
            7090,
            List.of(),
            4,
            new SecurityConfig("test-key", true, true, null, null, null, true, 100),  // API key with TLS enabled
            new CacheConfig(256 * 1024 * 1024L),
            BuildConfig.defaults(), 10_000
        );

        assertDoesNotThrow(config::validate);
    }

    @Test
    void testConfigValidationPassNoApiKey() {
        // Verify validate() passes when no API key (TLS not required)
        var config = new RenderingServerConfig(
            7090,
            List.of(),
            4,
            SecurityConfig.permissive(),  // No API key, TLS disabled
            new CacheConfig(256 * 1024 * 1024L),
            BuildConfig.defaults(), 10_000
        );

        assertDoesNotThrow(config::validate);
    }

    @Test
    void testSecureDefaults() {
        // Verify secure factory method requires apiKey and enables TLS
        var config = RenderingServerConfig.secureDefaults("my-api-key");

        assertEquals("my-api-key", config.security().apiKey());
        assertTrue(config.security().tlsEnabled());
        assertTrue(config.security().redactSensitiveInfo());
        assertTrue(config.security().rateLimitEnabled());
        assertEquals(100, config.security().rateLimitRequestsPerMinute());

        // Verify other fields have defaults
        assertEquals(7090, config.port());
        assertEquals(4, config.regionLevel());
        assertEquals(256 * 1024 * 1024L, config.cache().maxCacheMemoryBytes());
        assertEquals(1, config.build().buildPoolSize());
    }

    @Test
    void testTestingConfig() {
        // Verify permissive test factory method
        var config = RenderingServerConfig.testing();

        assertEquals(0, config.port());  // Dynamic port
        assertEquals(2, config.regionLevel());  // Small for tests
        assertNull(config.security().apiKey());  // Permissive - no auth
        assertFalse(config.security().tlsEnabled());
        assertEquals(16 * 1024 * 1024L, config.cache().maxCacheMemoryBytes());
        assertEquals(1, config.build().buildPoolSize());
    }

    @Test
    void testCustomQueueDepth() {
        // Verify builder gets configured queue depth
        var buildConfig = new BuildConfig(
            1,      // buildPoolSize
            8,      // maxBuildDepth
            64,     // gridResolution
            500,    // maxQueueDepth (custom)
            60_000L, // circuitBreakerTimeoutMs
            3       // circuitBreakerFailureThreshold
        );

        var config = new RenderingServerConfig(
            0,
            List.of(),
            4,
            SecurityConfig.permissive(),
            new CacheConfig(256 * 1024 * 1024L),
            buildConfig,
            10_000
        );

        assertEquals(500, config.build().maxQueueDepth());
    }

    @Test
    void testCustomCircuitBreakerConfig() {
        // Verify timeout/threshold honored
        var buildConfig = new BuildConfig(
            1,
            8,
            64,
            100,
            30_000L,  // Custom timeout: 30 seconds
            5         // Custom threshold: 5 failures
        );

        var config = new RenderingServerConfig(
            0,
            List.of(),
            4,
            SecurityConfig.permissive(),
            new CacheConfig(256 * 1024 * 1024L),
            buildConfig,
            10_000
        );

        assertEquals(30_000L, config.build().circuitBreakerTimeoutMs());
        assertEquals(5, config.build().circuitBreakerFailureThreshold());
    }

    @Test
    void testBuildConfigDefaults() {
        var buildConfig = BuildConfig.defaults();

        assertEquals(1, buildConfig.buildPoolSize());
        assertEquals(8, buildConfig.maxBuildDepth());
        assertEquals(64, buildConfig.gridResolution());
        assertEquals(100, buildConfig.maxQueueDepth());
        assertEquals(60_000L, buildConfig.circuitBreakerTimeoutMs());
        assertEquals(3, buildConfig.circuitBreakerFailureThreshold());
    }

    @Test
    void testBuildConfigTesting() {
        var buildConfig = BuildConfig.testing();

        assertEquals(1, buildConfig.buildPoolSize());
        assertEquals(4, buildConfig.maxBuildDepth());  // Shallow for tests
        assertEquals(16, buildConfig.gridResolution());  // Small for tests
        assertEquals(50, buildConfig.maxQueueDepth());  // Small for tests
        assertEquals(10_000L, buildConfig.circuitBreakerTimeoutMs());  // Short for tests
        assertEquals(3, buildConfig.circuitBreakerFailureThreshold());
    }

    @Test
    void testCacheConfigDefaults() {
        var cacheConfig = CacheConfig.defaults();
        assertEquals(256 * 1024 * 1024L, cacheConfig.maxCacheMemoryBytes());
    }

    @Test
    void testCacheConfigTesting() {
        var cacheConfig = CacheConfig.testing();
        assertEquals(16 * 1024 * 1024L, cacheConfig.maxCacheMemoryBytes());
    }

    @Test
    @SuppressWarnings("deprecation")
    void testDefaultsDeprecated() {
        // Verify defaults() still works but is deprecated
        var config = RenderingServerConfig.defaults();

        assertEquals(7090, config.port());
        assertNull(config.security().apiKey());  // Should use permissive security
    }
}
