/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClientRateLimiter (Luciferase-heam).
 */
class ClientRateLimiterTest {

    @Test
    void testRateLimit_withinLimit() {
        var clock = new TestClock();
        clock.setTime(1000L);
        var limiter = new ClientRateLimiter(3, clock);

        // First 3 messages should succeed
        assertTrue(limiter.allowMessage());
        assertTrue(limiter.allowMessage());
        assertTrue(limiter.allowMessage());
        assertEquals(3, limiter.getCurrentCount());

        // 4th message should fail
        assertFalse(limiter.allowMessage());
        assertEquals(3, limiter.getCurrentCount());
    }

    @Test
    void testRateLimit_windowReset() {
        var clock = new TestClock();
        clock.setTime(1000L);
        var limiter = new ClientRateLimiter(3, clock);

        // Fill the window
        assertTrue(limiter.allowMessage());
        assertTrue(limiter.allowMessage());
        assertTrue(limiter.allowMessage());
        assertFalse(limiter.allowMessage());

        // Advance time by 1 second
        clock.advance(1000);

        // Next message should succeed (window reset)
        assertTrue(limiter.allowMessage(), "Should allow message after window reset");
        assertEquals(1, limiter.getCurrentCount(), "Count should reset to 1");
    }
}
