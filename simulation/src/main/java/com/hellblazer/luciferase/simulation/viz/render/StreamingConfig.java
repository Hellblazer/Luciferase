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
 * Configuration for viewport tracking and region streaming.
 * <p>
 * Follows the composition pattern used by BuildConfig and CacheConfig.
 * <p>
 * <strong>LOD Threshold Scale Dependence:</strong> The {@code lodThresholds}
 * are specified in world coordinates and are scale-dependent. The default thresholds
 * [100, 300, 700] assume world coordinates in the range [0, 1024]. If the world scale
 * changes (e.g., to [0, 10240]), thresholds must be recalibrated proportionally
 * (e.g., [1000, 3000, 7000]).
 * <p>
 * <strong>Future Enhancement (Phase 4):</strong> Logarithmic LOD calculation
 * ({@code floor(log2(distance/base))}) may replace threshold tables for
 * scale-independent behavior.
 *
 * @param streamingIntervalMs          Interval between streaming cycles (default 100ms = 10 FPS)
 * @param maxClientsPerServer          Maximum concurrent WebSocket clients (default 50)
 * @param maxPendingSendsPerClient     Backpressure threshold per client (default 50)
 * @param lodThresholds                Distance thresholds in world units (scale-dependent, ascending order)
 * @param maxLodLevel                  Maximum LOD level (0 = highest detail)
 * @param clientTimeoutMs              Disconnect clients inactive for this duration (default 30s)
 * @param maxViewportUpdatesPerSecond  Throttle viewport update frequency (default 30)
 * @param rateLimitEnabled             Enable rate limiting per client (default true, Luciferase-heam)
 * @param maxMessagesPerSecond         Maximum messages per second per client (default 100, Luciferase-heam)
 * @param maxMessageSizeBytes          Maximum message size in bytes (default 65536 = 64KB, Luciferase-heam)
 * @author hal.hildebrand
 */
public record StreamingConfig(
    long streamingIntervalMs,
    int maxClientsPerServer,
    int maxPendingSendsPerClient,
    float[] lodThresholds,
    int maxLodLevel,
    long clientTimeoutMs,
    int maxViewportUpdatesPerSecond,
    boolean rateLimitEnabled,
    int maxMessagesPerSecond,
    int maxMessageSizeBytes
) {
    /**
     * Compact constructor with validation and defensive copy.
     */
    public StreamingConfig {
        if (streamingIntervalMs < 16) {
            throw new IllegalArgumentException("streamingIntervalMs must be >= 16");
        }
        if (maxClientsPerServer < 1) {
            throw new IllegalArgumentException("maxClientsPerServer must be >= 1");
        }
        if (maxPendingSendsPerClient < 1) {
            throw new IllegalArgumentException("maxPendingSendsPerClient must be >= 1");
        }
        if (clientTimeoutMs < 1000) {
            throw new IllegalArgumentException("clientTimeoutMs must be >= 1000");
        }
        if (maxViewportUpdatesPerSecond < 1) {
            throw new IllegalArgumentException("maxViewportUpdatesPerSecond must be >= 1");
        }
        if (maxMessagesPerSecond < 1) {
            throw new IllegalArgumentException("maxMessagesPerSecond must be >= 1");
        }
        if (maxMessageSizeBytes < 1024) {
            throw new IllegalArgumentException("maxMessageSizeBytes must be >= 1024");
        }

        // Clone array for defensive copy
        lodThresholds = lodThresholds.clone();

        // Validate strictly ascending thresholds
        for (int i = 1; i < lodThresholds.length; i++) {
            if (lodThresholds[i] <= lodThresholds[i - 1]) {
                throw new IllegalArgumentException("lodThresholds must be strictly ascending");
            }
        }

        // Validate maxLodLevel matches threshold count
        if (maxLodLevel != lodThresholds.length) {
            throw new IllegalArgumentException("maxLodLevel must equal lodThresholds.length");
        }
    }

    /**
     * Default production configuration.
     */
    public static StreamingConfig defaults() {
        return new StreamingConfig(
            100,                          // 100ms = 10 FPS streaming
            50,                           // 50 clients max
            50,                           // 50 pending sends before backpressure
            new float[]{100f, 300f, 700f}, // LOD distance thresholds
            3,                            // LOD 0-3
            30_000L,                      // 30s timeout
            30,                           // 30 viewport updates/sec
            true,                         // Enable rate limiting
            100,                          // 100 messages/sec per client
            65536                         // 64KB max message size
        );
    }

    /**
     * Test configuration with relaxed limits.
     */
    public static StreamingConfig testing() {
        return new StreamingConfig(
            50,                            // 50ms for faster test cycles
            10,                            // 10 clients
            20,                            // 20 pending sends
            new float[]{50f, 150f, 350f},  // Smaller thresholds for test world
            3,                             // LOD 0-3
            5_000L,                        // 5s timeout
            60,                            // 60 updates/sec
            false,                         // Disable rate limiting in tests (easier to test other features)
            100,                           // 100 messages/sec per client
            65536                          // 64KB max message size
        );
    }
}
