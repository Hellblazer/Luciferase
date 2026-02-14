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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RateLimiter.
 * <p>
 * Uses TestClock for deterministic time control.
 *
 * @author hal.hildebrand
 */
class RateLimiterTest {
    private TestClock testClock;
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        testClock = new TestClock();
        testClock.setTime(1000L);  // Start at t=1000ms
        rateLimiter = new RateLimiter(5, testClock);  // 5 requests per minute
    }

    @Test
    void testAllowRequest_WithinLimit() {
        // All 5 requests within limit should succeed
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.allowRequest("192.168.1.1"),
                       "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void testAllowRequest_ExceedsLimit() {
        // First 5 requests succeed
        for (int i = 0; i < 5; i++) {
            rateLimiter.allowRequest("192.168.1.1");
        }

        // 6th request should be denied
        assertFalse(rateLimiter.allowRequest("192.168.1.1"),
                    "6th request should exceed limit");
    }

    @Test
    void testSlidingWindow_RequestsExpire() {
        // Fill up the limit at t=1000ms
        for (int i = 0; i < 5; i++) {
            rateLimiter.allowRequest("192.168.1.1");
        }

        // 6th request at t=1000ms should fail
        assertFalse(rateLimiter.allowRequest("192.168.1.1"));

        // Advance time by 61 seconds (past the 60-second window)
        testClock.setTime(1000L + 61_000L);

        // Request should now succeed (old timestamps expired)
        assertTrue(rateLimiter.allowRequest("192.168.1.1"),
                   "Request after window expiration should succeed");
    }

    @Test
    void testSlidingWindow_PartialExpiration() {
        // 3 requests at t=1000ms
        for (int i = 0; i < 3; i++) {
            rateLimiter.allowRequest("192.168.1.1");
        }

        // Advance time by 61 seconds
        testClock.setTime(1000L + 61_000L);

        // Old 3 requests expired, can make 5 more
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.allowRequest("192.168.1.1"),
                       "Request " + (i + 1) + " after expiration should succeed");
        }

        // 6th request should fail
        assertFalse(rateLimiter.allowRequest("192.168.1.1"));
    }

    @Test
    void testDifferentIPs_IndependentLimits() {
        String ip1 = "192.168.1.1";
        String ip2 = "192.168.1.2";

        // Fill up limit for ip1
        for (int i = 0; i < 5; i++) {
            rateLimiter.allowRequest(ip1);
        }

        // ip1 should be blocked
        assertFalse(rateLimiter.allowRequest(ip1), "ip1 should be blocked");

        // ip2 should still have full quota
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.allowRequest(ip2),
                       "ip2 request " + (i + 1) + " should succeed");
        }

        // ip2 6th request should fail
        assertFalse(rateLimiter.allowRequest(ip2), "ip2 should be blocked after 5 requests");
    }

    @Test
    void testSlidingWindow_GradualExpiration() {
        // Request at t=1000ms
        rateLimiter.allowRequest("192.168.1.1");

        // Request at t=2000ms
        testClock.setTime(2000L);
        rateLimiter.allowRequest("192.168.1.1");

        // Request at t=3000ms
        testClock.setTime(3000L);
        rateLimiter.allowRequest("192.168.1.1");

        // Fill remaining 2 slots at t=3000ms
        for (int i = 0; i < 2; i++) {
            rateLimiter.allowRequest("192.168.1.1");
        }

        // At t=3000ms, all 5 slots filled (window includes t=1000 and t=2000 requests)
        assertFalse(rateLimiter.allowRequest("192.168.1.1"));

        // Advance to t=61001ms (first request at t=1000ms expires)
        testClock.setTime(61_001L);

        // Should allow 1 more request (t=1000 expired, but t=2000 and t=3000 still in window)
        assertTrue(rateLimiter.allowRequest("192.168.1.1"),
                   "Request should succeed after oldest timestamp expires");

        // But not 2 more
        assertFalse(rateLimiter.allowRequest("192.168.1.1"));
    }

    @Test
    void testRejectionCounter() {
        // Initial rejection count should be 0
        assertEquals(0, rateLimiter.getRejectionCount(), "Initial rejection count should be 0");

        // Fill up the limit (5 requests)
        for (int i = 0; i < 5; i++) {
            rateLimiter.allowRequest("192.168.1.1");
        }

        // Rejection count still 0 (no rejections yet)
        assertEquals(0, rateLimiter.getRejectionCount(), "No rejections yet");

        // Next 3 requests should be rejected
        for (int i = 0; i < 3; i++) {
            assertFalse(rateLimiter.allowRequest("192.168.1.1"),
                        "Request should be rejected");
        }

        // Rejection count should be 3
        assertEquals(3, rateLimiter.getRejectionCount(), "Should have 3 rejections");

        // Additional rejection
        rateLimiter.allowRequest("192.168.1.1");
        assertEquals(4, rateLimiter.getRejectionCount(), "Should have 4 rejections");
    }

    @Test
    void testRejectionCounter_MultipleIPs() {
        String ip1 = "192.168.1.1";
        String ip2 = "192.168.1.2";

        // Fill up both IPs
        for (int i = 0; i < 5; i++) {
            rateLimiter.allowRequest(ip1);
            rateLimiter.allowRequest(ip2);
        }

        // No rejections yet
        assertEquals(0, rateLimiter.getRejectionCount());

        // Reject from ip1 (2 times)
        rateLimiter.allowRequest(ip1);
        rateLimiter.allowRequest(ip1);

        // Reject from ip2 (3 times)
        rateLimiter.allowRequest(ip2);
        rateLimiter.allowRequest(ip2);
        rateLimiter.allowRequest(ip2);

        // Total rejections should be 5 (across all IPs)
        assertEquals(5, rateLimiter.getRejectionCount(),
                     "Should track rejections across all IPs");
    }
}
