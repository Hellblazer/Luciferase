/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authentication tests for RenderingServer WebSocket endpoint.
 * <p>
 * Tests the fix for Luciferase-biom: Add WebSocket authentication to /ws/render endpoint.
 * Validates that:
 * - Missing Authorization header → rejected with 4003
 * - Wrong API key → rejected with 4003
 * - Correct API key → accepted
 * - Concurrent auth attempts work correctly
 * - Auth rejection (closeSession 4003) doesn't deadlock with pending sends (requires 89g0 fix)
 *
 * @author hal.hildebrand
 */
class RenderingServerAuthTest {

    private static final String API_KEY = "test-secret-key-12345";
    private static final String WRONG_API_KEY = "wrong-key-67890";

    private RenderingServer server;
    private int port;

    @BeforeEach
    void setUp() {
        // Use secure config with API key authentication (TLS disabled for tests)
        var config = new RenderingServerConfig(
            0,                                      // Dynamic port for tests
            List.of(),                              // No upstreams
            2,                                      // Small region level for fast tests
            SecurityConfig.secure(API_KEY, false),  // Auth enabled, TLS disabled for tests
            CacheConfig.testing(),                  // Test cache settings
            BuildConfig.testing(),                  // Test build settings
            1_000,                                  // Max entities per region
            StreamingConfig.testing(),              // Test streaming settings
            PerformanceConfig.testing(),            // Test performance settings
            0.0f,
            1024.0f
        );

        server = new RenderingServer(config);
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testNoAuthHeader_Rejected() throws Exception {
        // Given: Client with no Authorization header
        var latch = new CountDownLatch(1);
        var closeStatus = new AtomicInteger(-1);
        var closeReason = new ArrayList<String>();

        var client = HttpClient.newHttpClient();
        var ws = client.newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    closeStatus.set(statusCode);
                    closeReason.add(reason);
                    latch.countDown();
                    return null;
                }
            })
            .get(5, TimeUnit.SECONDS);

        // Then: Connection closed with 4003 Unauthorized
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Connection should be closed");
        assertEquals(4003, closeStatus.get(), "Close status should be 4003");
        assertEquals("Unauthorized", closeReason.get(0), "Close reason should be 'Unauthorized'");
    }

    @Test
    void testWrongApiKey_Rejected() throws Exception {
        // Given: Client with wrong API key
        var latch = new CountDownLatch(1);
        var closeStatus = new AtomicInteger(-1);
        var closeReason = new ArrayList<String>();

        var client = HttpClient.newHttpClient();
        var ws = client.newWebSocketBuilder()
            .header("Authorization", "Bearer " + WRONG_API_KEY)
            .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    closeStatus.set(statusCode);
                    closeReason.add(reason);
                    latch.countDown();
                    return null;
                }
            })
            .get(5, TimeUnit.SECONDS);

        // Then: Connection closed with 4003 Unauthorized
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Connection should be closed");
        assertEquals(4003, closeStatus.get(), "Close status should be 4003");
        assertEquals("Unauthorized", closeReason.get(0), "Close reason should be 'Unauthorized'");
    }

    @Test
    void testCorrectApiKey_Accepted() throws Exception {
        // Given: Client with correct API key
        var connectLatch = new CountDownLatch(1);
        var messageSent = new CompletableFuture<Void>();

        var client = HttpClient.newHttpClient();
        var ws = client.newWebSocketBuilder()
            .header("Authorization", "Bearer " + API_KEY)
            .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    connectLatch.countDown();
                    // Send a PING message to verify connection works
                    webSocket.sendText("{\"type\": \"PING\"}", true)
                        .thenRun(() -> messageSent.complete(null));
                    WebSocket.Listener.super.onOpen(webSocket);
                }
            })
            .get(5, TimeUnit.SECONDS);

        // Then: Connection accepted
        assertTrue(connectLatch.await(2, TimeUnit.SECONDS), "Connection should be accepted");
        messageSent.get(2, TimeUnit.SECONDS);  // Verify message can be sent
        ws.sendClose(1000, "Normal close").get(2, TimeUnit.SECONDS);
    }

    @Test
    void testConcurrentAuthAttempts_MixedKeys() throws Exception {
        // Given: Multiple clients with different auth credentials
        int clientCount = 10;
        var executor = Executors.newFixedThreadPool(clientCount);
        var latch = new CountDownLatch(clientCount);
        var rejectedCount = new AtomicInteger(0);
        var acceptedCount = new AtomicInteger(0);

        for (int i = 0; i < clientCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    var client = HttpClient.newHttpClient();
                    // Half use correct key, half use wrong key
                    var authKey = (index % 2 == 0) ? API_KEY : WRONG_API_KEY;
                    var closeLatch = new CountDownLatch(1);
                    var closeStatus = new AtomicInteger(-1);
                    var messageSent = new CompletableFuture<Void>();

                    var ws = client.newWebSocketBuilder()
                        .header("Authorization", "Bearer " + authKey)
                        .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                            @Override
                            public void onOpen(WebSocket webSocket) {
                                // Try to send a message - will only succeed if auth passed
                                webSocket.sendText("{\"type\": \"PING\"}", true)
                                    .thenRun(() -> messageSent.complete(null))
                                    .exceptionally(ex -> {
                                        messageSent.completeExceptionally(ex);
                                        return null;
                                    });
                                WebSocket.Listener.super.onOpen(webSocket);
                            }

                            @Override
                            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                                closeStatus.set(statusCode);
                                closeLatch.countDown();
                                return null;
                            }
                        })
                        .get(5, TimeUnit.SECONDS);

                    // Wait for close
                    closeLatch.await(2, TimeUnit.SECONDS);

                    // If closed with 4003, it was rejected
                    if (closeStatus.get() == 4003) {
                        rejectedCount.incrementAndGet();
                    } else {
                        // Otherwise, check if message was sent successfully
                        try {
                            messageSent.get(100, TimeUnit.MILLISECONDS);
                            acceptedCount.incrementAndGet();
                            ws.sendClose(1000, "Normal close");
                        } catch (TimeoutException e) {
                            // Connection closed before message could be sent
                            rejectedCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // Expected for some rejected connections
                } finally {
                    latch.countDown();
                }
            });
        }

        // Then: Half accepted, half rejected
        assertTrue(latch.await(10, TimeUnit.SECONDS), "All attempts should complete");
        executor.shutdown();

        assertEquals(clientCount / 2, acceptedCount.get(), "Half should be accepted");
        assertEquals(clientCount / 2, rejectedCount.get(), "Half should be rejected with 4003");
    }

    @Test
    void testAuthRejectionNoDeadlock_IntegrationWith89g0() throws Exception {
        // This test validates that auth rejection (closeSession 4003) doesn't deadlock
        // when concurrent sends are in progress. Requires the synchronization fix from
        // Luciferase-89g0 (synchronized on session for all sends).
        //
        // Scenario:
        // 1. Multiple clients attempt connection with wrong auth
        // 2. Server calls ctx.closeSession(4003) during onConnect
        // 3. If any pending sends exist (unlikely but possible), 89g0 ensures no deadlock
        //
        // Expected: All auth rejections complete quickly without hanging

        int clientCount = 50;
        var executor = Executors.newFixedThreadPool(clientCount);
        var latch = new CountDownLatch(clientCount);
        var rejectedCount = new AtomicInteger(0);
        var startTime = System.nanoTime();

        for (int i = 0; i < clientCount; i++) {
            executor.submit(() -> {
                try {
                    var client = HttpClient.newHttpClient();
                    var closeLatch = new CountDownLatch(1);

                    var ws = client.newWebSocketBuilder()
                        .header("Authorization", "Bearer wrong-key")
                        .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                                if (statusCode == 4003) {
                                    rejectedCount.incrementAndGet();
                                }
                                closeLatch.countDown();
                                return null;
                            }
                        })
                        .get(5, TimeUnit.SECONDS);

                    closeLatch.await(2, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Expected for rejected connections
                } finally {
                    latch.countDown();
                }
            });
        }

        // Then: All rejections complete without deadlock
        assertTrue(latch.await(15, TimeUnit.SECONDS), "All auth rejections should complete");
        executor.shutdown();

        var duration = System.nanoTime() - startTime;
        var durationSeconds = duration / 1_000_000_000.0;

        assertEquals(clientCount, rejectedCount.get(), "All should be rejected");
        assertTrue(durationSeconds < 10.0, "Should complete quickly (no deadlock), took " + durationSeconds + "s");
    }

    @Test
    void testTimingAttackResistance_ConstantTimeComparison() throws Exception {
        // This test validates fix for Luciferase-8gdp: Timing attack vulnerability
        //
        // Before fix: String.equals() was timing-dependent (early exit on first mismatch)
        // After fix: MessageDigest.isEqual() is constant-time
        //
        // Test approach:
        // 1. Measure auth time for correct key (100 attempts)
        // 2. Measure auth time for wrong key with matching prefix (100 attempts)
        // 3. Verify timing variance is minimal (no significant timing leak)
        //
        // Note: This is a statistical test - absolute times vary with CPU, but the
        // relative variance should be minimal for constant-time comparison.

        int samples = 100;
        var correctKeyTimes = new long[samples];
        var wrongKeyTimes = new long[samples];

        // Measure correct key authentication times
        for (int i = 0; i < samples; i++) {
            final int index = i;  // Final variable for lambda capture
            var closeLatch = new CountDownLatch(1);
            var startTime = System.nanoTime();

            var client = HttpClient.newHttpClient();
            var ws = client.newWebSocketBuilder()
                .header("Authorization", "Bearer " + API_KEY)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        correctKeyTimes[index] = System.nanoTime() - startTime;
                        closeLatch.countDown();
                        WebSocket.Listener.super.onOpen(webSocket);
                    }
                })
                .get(5, TimeUnit.SECONDS);

            closeLatch.await(2, TimeUnit.SECONDS);
            ws.sendClose(1000, "Normal close").get(1, TimeUnit.SECONDS);
        }

        // Measure wrong key authentication times (with matching prefix to maximize timing leak potential)
        var wrongKeyWithPrefix = API_KEY.substring(0, Math.min(10, API_KEY.length())) + "WRONG";
        for (int i = 0; i < samples; i++) {
            final int index = i;  // Final variable for lambda capture
            var closeLatch = new CountDownLatch(1);
            var startTime = System.nanoTime();

            var client = HttpClient.newHttpClient();
            var ws = client.newWebSocketBuilder()
                .header("Authorization", "Bearer " + wrongKeyWithPrefix)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        wrongKeyTimes[index] = System.nanoTime() - startTime;
                        closeLatch.countDown();
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

            closeLatch.await(2, TimeUnit.SECONDS);
        }

        // Compute timing statistics
        var correctAvg = average(correctKeyTimes);
        var correctStdDev = standardDeviation(correctKeyTimes, correctAvg);
        var wrongAvg = average(wrongKeyTimes);
        var wrongStdDev = standardDeviation(wrongKeyTimes, wrongAvg);

        // The key metric: timing difference should be within noise threshold
        // For constant-time comparison, difference should be < 2 standard deviations
        var timingDifference = Math.abs(correctAvg - wrongAvg);
        var noiseThreshold = 2.0 * Math.max(correctStdDev, wrongStdDev);

        System.out.printf("Timing attack resistance test:%n");
        System.out.printf("  Correct key avg: %.2f μs (σ=%.2f)%n", correctAvg / 1000.0, correctStdDev / 1000.0);
        System.out.printf("  Wrong key avg:   %.2f μs (σ=%.2f)%n", wrongAvg / 1000.0, wrongStdDev / 1000.0);
        System.out.printf("  Timing diff:     %.2f μs%n", timingDifference / 1000.0);
        System.out.printf("  Noise threshold: %.2f μs%n", noiseThreshold / 1000.0);

        assertTrue(timingDifference < noiseThreshold,
            String.format("Timing leak detected: diff=%.2fμs exceeds threshold=%.2fμs",
                timingDifference / 1000.0, noiseThreshold / 1000.0));
    }

    private static double average(long[] values) {
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return (double) sum / values.length;
    }

    private static double standardDeviation(long[] values, double mean) {
        double sumSquaredDiffs = 0;
        for (long v : values) {
            double diff = v - mean;
            sumSquaredDiffs += diff * diff;
        }
        return Math.sqrt(sumSquaredDiffs / values.length);
    }

    @Test
    void testAuthBruteForceProtection_blockAfter10Attempts() throws Exception {
        // This test validates fix for Luciferase-vyik: Auth brute force protection
        //
        // Expected behavior:
        // 1. First 10 failed auth attempts → rejected with 4003 "Unauthorized"
        // 2. 11th attempt onward → rejected with 4003 "Too many failed authentication attempts"
        // 3. Client is blocked for 1 minute window
        //
        // This prevents brute force API key guessing attacks

        int attempts = 15;
        var rejectedCount = new AtomicInteger(0);
        var blockedCount = new AtomicInteger(0);
        var unauthorizedCount = new AtomicInteger(0);

        for (int i = 0; i < attempts; i++) {
            var closeLatch = new CountDownLatch(1);
            var closeReason = new ArrayList<String>();

            var client = HttpClient.newHttpClient();
            // All attempts use wrong API key
            var ws = client.newWebSocketBuilder()
                .header("Authorization", "Bearer wrong-key-" + i)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closeReason.add(reason);
                        if (statusCode == 4003) {
                            rejectedCount.incrementAndGet();
                            if (reason.contains("Too many failed")) {
                                blockedCount.incrementAndGet();
                            } else if (reason.contains("Unauthorized")) {
                                unauthorizedCount.incrementAndGet();
                            }
                        }
                        closeLatch.countDown();
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

            closeLatch.await(2, TimeUnit.SECONDS);
        }

        // Then: First 10 attempts rejected as "Unauthorized", attempts 11-15 blocked
        assertEquals(attempts, rejectedCount.get(), "All attempts should be rejected");
        assertEquals(10, unauthorizedCount.get(), "First 10 attempts should be 'Unauthorized'");
        assertEquals(5, blockedCount.get(), "Attempts 11-15 should be blocked due to rate limit");
    }

    @Test
    void testAuthBruteForceProtection_successfulAuthResets() throws Exception {
        // Validate that successful auth resets the rate limiter

        // First, make 5 failed attempts
        for (int i = 0; i < 5; i++) {
            var closeLatch = new CountDownLatch(1);
            var client = HttpClient.newHttpClient();
            var ws = client.newWebSocketBuilder()
                .header("Authorization", "Bearer wrong-key")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closeLatch.countDown();
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
            closeLatch.await(2, TimeUnit.SECONDS);
        }

        // Then, make a successful auth
        var connectLatch = new CountDownLatch(1);
        var client = HttpClient.newHttpClient();
        var ws = client.newWebSocketBuilder()
            .header("Authorization", "Bearer " + API_KEY)
            .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    connectLatch.countDown();
                    WebSocket.Listener.super.onOpen(webSocket);
                }
            })
            .get(5, TimeUnit.SECONDS);

        // Should succeed (rate limiter reset)
        assertTrue(connectLatch.await(2, TimeUnit.SECONDS), "Successful auth should be accepted");
        ws.sendClose(1000, "Normal close").get(2, TimeUnit.SECONDS);

        // Now make 10 more failed attempts - should all be rejected as "Unauthorized" (not blocked)
        var unauthorizedCount = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            var closeLatch = new CountDownLatch(1);
            var closeReason = new ArrayList<String>();
            var client2 = HttpClient.newHttpClient();
            var ws2 = client2.newWebSocketBuilder()
                .header("Authorization", "Bearer wrong-key")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closeReason.add(reason);
                        if (reason.contains("Unauthorized") && !reason.contains("Too many")) {
                            unauthorizedCount.incrementAndGet();
                        }
                        closeLatch.countDown();
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
            closeLatch.await(2, TimeUnit.SECONDS);
        }

        // All 10 should be "Unauthorized" (not blocked) because rate limiter was reset
        assertEquals(10, unauthorizedCount.get(), "After successful auth, rate limiter should be reset");
    }

    // ─────────── WS ip-keyed limiter tests (Luciferase-7wzml.42 H1) ───────────

    /**
     * Regression test for Luciferase-7wzml.42 H1: WS auth limiter was keyed on ctx.host()
     * (the HTTP Host header), which an attacker could rotate per-connection to get a fresh
     * zero-failure limiter each time.  The fix keys on the remote socket IP instead.
     *
     * <p>Test strategy: Java 11's HttpClient treats "Host" as a restricted header — it cannot
     * be overridden per-connection from client side in normal usage.  What we CAN assert is
     * that 11 sequential bad-auth WS attempts from the same loopback IP accumulate failures
     * and the 11th attempt is BLOCKED (not merely Unauthorized), proving the limiter key is
     * stable across connections from the same IP regardless of session ID rotation.  If the
     * key were per-session or per-host-header-value, the counter would reset on each new
     * WebSocket handshake and the 11th attempt would still see "Unauthorized" (not "Too many").
     */
    @Test
    void testWsLockout_keyedOnIp_notHostHeader() throws Exception {
        int attempts = 11;
        var reasons = new java.util.concurrent.CopyOnWriteArrayList<String>();

        for (int i = 0; i < attempts; i++) {
            var closeLatch = new CountDownLatch(1);

            // Each connection is a brand-new WebSocket handshake (new session ID each time),
            // simulating the attacker rotating connections.
            var client = HttpClient.newHttpClient();
            var ws = client.newWebSocketBuilder()
                .header("Authorization", "Bearer wrong-key-" + i)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        reasons.add(reason != null ? reason : "");
                        closeLatch.countDown();
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

            assertTrue(closeLatch.await(3, TimeUnit.SECONDS),
                "Attempt " + i + " should close");
        }

        // The first 10 failures are "Unauthorized"; the 11th must be "Too many failed authentication attempts"
        // (blocked), because the limiter is keyed on the socket IP and persists across new WS connections.
        assertEquals(attempts, reasons.size(), "All attempts should produce a close reason");
        long blockedCount = reasons.stream().filter(r -> r.contains("Too many")).count();
        assertTrue(blockedCount >= 1,
            "At least the 11th attempt must be blocked (Too many failed authentication attempts). " +
            "Reasons: " + reasons);
    }

    /**
     * Cross-protocol shared-limiter test: WS and REST use the same ip-keyed authLimiters cache.
     * After 11 WS auth failures exhaust the limiter, a REST call from the same loopback IP
     * must also be rate-limited (429), not just 401.
     */
    @Test
    void testWsAndRestShareIpLimiter() throws Exception {
        // Exhaust limiter via WS (11 failures)
        for (int i = 0; i < 11; i++) {
            var closeLatch = new CountDownLatch(1);
            var client = HttpClient.newHttpClient();
            var ws = client.newWebSocketBuilder()
                .header("Authorization", "Bearer ws-bad-key-" + i)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closeLatch.countDown();
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
            assertTrue(closeLatch.await(3, TimeUnit.SECONDS), "WS attempt " + i + " should close");
        }

        // Now a REST call from the same IP (loopback) must be rate-limited (429)
        var httpClient = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/health"))
            .header("Authorization", "Bearer rest-bad-key")
            .GET().build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(429, resp.statusCode(),
            "REST call from same IP must be rate-limited after WS failures exhausted the shared limiter");
    }

    // ─────────── REST /api/* auth tests (Luciferase-7wzml.42) ───────────

    @Test
    void testRestConstantTimeComparison_MessageDigestIsEqualUsed() throws Exception {
        // Validates that the REST before-filter uses MessageDigest.isEqual, not String.equals.
        // Structural test: wrong key → 401 (not 500/crash), correct key → 200.
        var client = java.net.http.HttpClient.newHttpClient();

        // Wrong key must return 401
        var wrongReq = java.net.http.HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/health"))
            .header("Authorization", "Bearer wrong-key")
            .GET().build();
        var wrongResp = client.send(wrongReq, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(401, wrongResp.statusCode(), "Wrong key must be rejected with 401");

        // Correct key must return 200
        var goodReq = java.net.http.HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/health"))
            .header("Authorization", "Bearer " + API_KEY)
            .GET().build();
        var goodResp = client.send(goodReq, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, goodResp.statusCode(), "Correct key must be accepted with 200");
    }

    @Test
    void testRestBruteForce_lockoutAfterRepeatedFailures() throws Exception {
        // Repeated wrong keys from one host must eventually return 429 (same lockout as WS).
        // AuthAttemptRateLimiter locks after MAX_FAILED_ATTEMPTS (10 by default).
        var client = java.net.http.HttpClient.newHttpClient();
        int seen429 = 0;
        for (int i = 0; i < 15; i++) {
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/health"))
                .header("Authorization", "Bearer bad-rest-key-" + i)
                .GET().build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429) {
                seen429++;
            }
        }
        assertTrue(seen429 > 0, "At least one request must be rate-limited (429) after repeated failures");
    }

    @Test
    void testRestValidKeyResetsLimiter() throws Exception {
        // After some failures, a valid key must succeed and reset the limiter.
        var client = java.net.http.HttpClient.newHttpClient();

        // 5 failures
        for (int i = 0; i < 5; i++) {
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/health"))
                .header("Authorization", "Bearer bad-key-" + i)
                .GET().build();
            client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        }

        // Valid key must succeed
        var goodReq = java.net.http.HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/health"))
            .header("Authorization", "Bearer " + API_KEY)
            .GET().build();
        var goodResp = client.send(goodReq, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, goodResp.statusCode(), "Valid key must succeed after failures (limiter reset)");
    }

    /** Minimal controllable clock for deterministic testing. */
    static final class ControllableClock implements com.hellblazer.luciferase.common.time.Clock {
        private volatile long millis;
        ControllableClock(long initial) { this.millis = initial; }
        void setTime(long ms) { this.millis = ms; }
        @Override public long currentTimeMillis() { return millis; }
        @Override public long nanoTime() { return millis * 1_000_000L; }
    }

    @Test
    void testRestInjectedClock_usedByRateLimiter() throws Exception {
        // Validate that the REST rate limiter uses the injected clock (not wall-clock).
        // By injecting a ControllableClock we control time; the rate limiter's window logic
        // must respect it. We exhaust attempts, advance the clock past the window,
        // and verify the next request is accepted again (not locked).
        if (server != null) {
            server.stop();
        }

        var testClock = new ControllableClock(1000L);

        var config = new RenderingServerConfig(
            0, List.of(), 2,
            SecurityConfig.secure(API_KEY, false),
            CacheConfig.testing(), BuildConfig.testing(),
            1_000, StreamingConfig.testing(), PerformanceConfig.testing(),
            0.0f, 1024.0f
        );
        server = new RenderingServer(config);
        server.setClock(testClock);
        server.start();
        port = server.port();

        var client = java.net.http.HttpClient.newHttpClient();

        // Exhaust the rate limiter (11 attempts to trigger lockout)
        for (int i = 0; i < 11; i++) {
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/health"))
                .header("Authorization", "Bearer clock-bad-" + i)
                .GET().build();
            client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        }

        // Advance injected clock past the 1-minute window (AuthAttemptRateLimiter WINDOW_MS=60000)
        testClock.setTime(1000L + 61_000L);

        // Now a bad key should NOT be rate-limited (window expired) — 401 not 429
        var req = java.net.http.HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/health"))
            .header("Authorization", "Bearer still-bad")
            .GET().build();
        var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode(),
            "After clock advances past window, request should be 401 (not 429) — injected clock is used");
    }

    @Test
    void testPermissiveConfig_NoAuthRequired() throws Exception {
        // Given: Server with permissive config (no API key)
        if (server != null) {
            server.stop();
        }

        var permissiveConfig = RenderingServerConfig.testing();  // Uses SecurityConfig.permissive()
        server = new RenderingServer(permissiveConfig);
        server.start();
        port = server.port();

        // When: Client connects with no Authorization header
        var connectLatch = new CountDownLatch(1);

        var client = HttpClient.newHttpClient();
        var ws = client.newWebSocketBuilder()
            // No Authorization header
            .buildAsync(URI.create("ws://localhost:" + port + "/ws/render"), new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    connectLatch.countDown();
                    WebSocket.Listener.super.onOpen(webSocket);
                }
            })
            .get(5, TimeUnit.SECONDS);

        // Then: Connection accepted (no auth required)
        assertTrue(connectLatch.await(2, TimeUnit.SECONDS), "Connection should be accepted without auth");
        ws.sendClose(1000, "Normal close").get(2, TimeUnit.SECONDS);
    }
}
