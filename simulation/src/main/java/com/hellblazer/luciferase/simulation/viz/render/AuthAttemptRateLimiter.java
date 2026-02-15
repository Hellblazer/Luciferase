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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter for authentication attempts (Luciferase-vyik).
 * <p>
 * Prevents brute force attacks on WebSocket authentication by limiting failed auth attempts.
 * Uses a sliding window approach: tracks failed attempts within the last minute.
 * <p>
 * Configuration:
 * - Window: 60 seconds
 * - Limit: 10 failed attempts per window
 * - Reset: On successful authentication or window expiration
 * <p>
 * Thread-safe for concurrent authentication attempts from same client.
 *
 * @author hal.hildebrand
 */
class AuthAttemptRateLimiter {
    private static final int MAX_FAILED_ATTEMPTS = 10;
    private static final long WINDOW_MS = 60_000L;  // 1 minute

    private final Clock clock;
    private final AtomicInteger failedAttempts;
    private final AtomicLong windowStartMs;

    /**
     * Create an auth attempt rate limiter.
     *
     * @param clock Clock for deterministic testing
     */
    AuthAttemptRateLimiter(Clock clock) {
        this.clock = clock;
        this.failedAttempts = new AtomicInteger(0);
        this.windowStartMs = new AtomicLong(clock.currentTimeMillis());
    }

    /**
     * Record a failed authentication attempt.
     *
     * @return true if attempt is allowed (under limit), false if rate limit exceeded (client should be blocked)
     */
    synchronized boolean recordFailedAttempt() {
        var now = clock.currentTimeMillis();
        var windowStart = windowStartMs.get();

        // Reset window if we've moved to a new minute
        if (now - windowStart >= WINDOW_MS) {
            failedAttempts.set(0);
            windowStartMs.set(now);
        }

        // Increment failed attempts
        var currentAttempts = failedAttempts.incrementAndGet();

        // Check if limit exceeded
        return currentAttempts <= MAX_FAILED_ATTEMPTS;
    }

    /**
     * Record a successful authentication (resets the rate limiter).
     */
    synchronized void recordSuccess() {
        failedAttempts.set(0);
        windowStartMs.set(clock.currentTimeMillis());
    }

    /**
     * Get current failed attempt count in the window (for testing).
     */
    int getFailedAttemptCount() {
        return failedAttempts.get();
    }

    /**
     * Check if client is currently blocked due to rate limit.
     *
     * @return true if client exceeded rate limit and should be blocked
     */
    synchronized boolean isBlocked() {
        var now = clock.currentTimeMillis();
        var windowStart = windowStartMs.get();

        // Reset window if expired
        if (now - windowStart >= WINDOW_MS) {
            failedAttempts.set(0);
            windowStartMs.set(now);
            return false;
        }

        return failedAttempts.get() > MAX_FAILED_ATTEMPTS;
    }
}
