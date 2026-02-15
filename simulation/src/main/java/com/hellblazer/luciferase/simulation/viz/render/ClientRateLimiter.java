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
 * Token bucket rate limiter for client message throttling (Luciferase-heam).
 * <p>
 * Uses a sliding window approach: tracks message count within the last second.
 * When a message arrives, removes messages older than 1 second from the count,
 * then checks if the new message would exceed the limit.
 *
 * @author hal.hildebrand
 */
class ClientRateLimiter {
    private final int maxMessagesPerSecond;
    private final Clock clock;
    private final AtomicInteger messageCount;
    private final AtomicLong windowStartMs;

    /**
     * Create a rate limiter for a single client.
     *
     * @param maxMessagesPerSecond Maximum messages allowed per second
     * @param clock                Clock for deterministic testing
     */
    ClientRateLimiter(int maxMessagesPerSecond, Clock clock) {
        this.maxMessagesPerSecond = maxMessagesPerSecond;
        this.clock = clock;
        this.messageCount = new AtomicInteger(0);
        this.windowStartMs = new AtomicLong(clock.currentTimeMillis());
    }

    /**
     * Check if a new message is allowed under the rate limit.
     *
     * @return true if message is allowed, false if rate limit exceeded
     */
    synchronized boolean allowMessage() {
        var now = clock.currentTimeMillis();
        var windowStart = windowStartMs.get();

        // Reset window if we've moved to a new second
        if (now - windowStart >= 1000) {
            messageCount.set(0);
            windowStartMs.set(now);
        }

        // Check if we can accept another message
        var currentCount = messageCount.get();
        if (currentCount < maxMessagesPerSecond) {
            messageCount.incrementAndGet();
            return true;
        }

        return false;  // Rate limit exceeded
    }

    /**
     * Get current message count in the window (for testing).
     */
    int getCurrentCount() {
        return messageCount.get();
    }
}
