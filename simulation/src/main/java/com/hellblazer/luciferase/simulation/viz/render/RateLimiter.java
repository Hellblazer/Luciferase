/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple sliding window rate limiter.
 * <p>
 * Tracks requests per IP address using a sliding window of 1 minute.
 * Thread-safe using ConcurrentHashMap and ConcurrentLinkedQueue.
 * <p>
 * Uses injected Clock for deterministic testing.
 *
 * @author hal.hildebrand
 */
public class RateLimiter {
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>> requestTimestamps = new ConcurrentHashMap<>();
    private final int maxRequestsPerMinute;
    private final long windowMs = 60_000L;  // 1 minute window
    private final Clock clock;
    private final AtomicLong rejectionCount = new AtomicLong(0);

    /**
     * Create a rate limiter with the given maximum requests per minute.
     *
     * @param maxRequestsPerMinute Maximum requests allowed per IP per minute
     * @param clock Clock for time tracking (use Clock.system() for production, TestClock for tests)
     */
    public RateLimiter(int maxRequestsPerMinute, Clock clock) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.clock = clock;
    }

    /**
     * Check if request from given IP should be allowed.
     *
     * @param ip Client IP address
     * @return true if allowed, false if rate limit exceeded
     */
    public boolean allowRequest(String ip) {
        long now = clock.currentTimeMillis();  // Use injected clock
        var timestamps = requestTimestamps.computeIfAbsent(ip, k -> new ConcurrentLinkedQueue<>());

        // Remove timestamps outside the sliding window
        timestamps.removeIf(timestamp -> now - timestamp > windowMs);

        // Check if under limit
        if (timestamps.size() < maxRequestsPerMinute) {
            timestamps.offer(now);
            return true;
        }

        rejectionCount.incrementAndGet();  // Track rejection
        return false;  // Rate limit exceeded
    }

    /**
     * Get total number of requests rejected due to rate limiting.
     * <p>
     * This counter never resets and is useful for operator tuning.
     *
     * @return Total rejection count since server start
     */
    public long getRejectionCount() {
        return rejectionCount.get();
    }
}
