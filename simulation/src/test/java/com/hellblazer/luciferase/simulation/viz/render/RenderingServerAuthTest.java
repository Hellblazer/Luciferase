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
import java.util.List;
import java.net.http.HttpClient;
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
            PerformanceConfig.testing()             // Test performance settings
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
