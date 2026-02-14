/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RenderingServer lifecycle and REST endpoints.
 *
 * @author hal.hildebrand
 */
class RenderingServerTest {

    private RenderingServer server;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void testServerLifecycle() {
        var config = RenderingServerConfig.testing();
        server = new RenderingServer(config);

        assertFalse(server.isStarted(), "Server should not be started initially");
        assertEquals(-1, server.port(), "Port should be -1 before start");

        server.start();

        assertTrue(server.isStarted(), "Server should be started");
        assertTrue(server.port() > 0, "Port should be assigned after start");

        server.stop();

        assertFalse(server.isStarted(), "Server should be stopped");
    }

    @Test
    void testDynamicPortAssignment() {
        var config = RenderingServerConfig.testing();  // port = 0
        server = new RenderingServer(config);

        assertEquals(0, config.port(), "Config should specify dynamic port (0)");

        server.start();

        int actualPort = server.port();
        assertTrue(actualPort > 0 && actualPort < 65536, "Should assign valid port: " + actualPort);

        server.stop();
    }

    @Test
    void testDoubleStartThrows() {
        var config = RenderingServerConfig.testing();
        server = new RenderingServer(config);

        server.start();

        assertThrows(IllegalStateException.class, () -> server.start(),
                    "Starting already-started server should throw");

        server.stop();
    }

    @Test
    void testHealthEndpoint() throws Exception {
        var config = RenderingServerConfig.testing();
        server = new RenderingServer(config);
        server.start();

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + server.port() + "/api/health"))
                                 .GET()
                                 .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Health endpoint should return 200 OK");

        var json = jsonMapper.readTree(response.body());
        assertTrue(json.has("status"), "Response should have 'status' field");
        assertEquals("healthy", json.get("status").asText(), "Status should be 'healthy'");
        assertTrue(json.has("uptime"), "Response should have 'uptime' field");
        assertTrue(json.has("regions"), "Response should have 'regions' field");

        server.stop();
    }

    @Test
    void testInfoEndpoint() throws Exception {
        var upstreams = List.of(
            new UpstreamConfig(URI.create("ws://localhost:7080/ws/entities"), "upstream1")
        );
        var config = new RenderingServerConfig(
            0, upstreams, 4, 64, 8, 256*1024*1024L, 30_000L, 1, SparseStructureType.ESVO
        );
        server = new RenderingServer(config);
        server.start();

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + server.port() + "/api/info"))
                                 .GET()
                                 .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Info endpoint should return 200 OK");

        var json = jsonMapper.readTree(response.body());
        assertTrue(json.has("version"), "Response should have 'version' field");
        assertTrue(json.has("upstreams"), "Response should have 'upstreams' field");
        assertTrue(json.has("regionLevel"), "Response should have 'regionLevel' field");
        assertEquals(4, json.get("regionLevel").asInt(), "Region level should match config");

        var upstreamsArray = json.get("upstreams");
        assertTrue(upstreamsArray.isArray(), "Upstreams should be array");
        assertEquals(1, upstreamsArray.size(), "Should have 1 upstream");

        server.stop();
    }

    @Test
    void testWebSocketEndpointAcceptsConnections() throws Exception {
        var config = RenderingServerConfig.testing();
        server = new RenderingServer(config);
        server.start();

        // Connect WebSocket client (will be simple connection for Phase 1)
        var client = HttpClient.newHttpClient();
        var ws = client.newWebSocketBuilder()
                       .buildAsync(URI.create("ws://localhost:" + server.port() + "/ws/render"),
                                 new java.net.http.WebSocket.Listener() {})
                       .join();

        assertTrue(ws != null, "WebSocket should connect successfully");

        ws.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "Test complete");

        server.stop();
    }

    @Test
    void testGracefulShutdown() throws Exception {
        var config = RenderingServerConfig.testing();
        server = new RenderingServer(config);
        server.start();

        assertTrue(server.isStarted(), "Server should be started");

        // Make a request while server is running
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + server.port() + "/api/health"))
                                 .GET()
                                 .build();

        var response1 = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response1.statusCode(), "Should respond before shutdown");

        // Graceful shutdown
        server.stop();

        assertFalse(server.isStarted(), "Server should be stopped");

        // Verify server no longer responds
        assertThrows(Exception.class, () -> {
            client.send(request, HttpResponse.BodyHandlers.ofString());
        }, "Should not respond after shutdown");
    }

    @Test
    void testGetRegionManager() {
        var config = RenderingServerConfig.testing();
        server = new RenderingServer(config);

        var regionManager = server.getRegionManager();
        assertNotNull(regionManager, "Region manager should be accessible");

        server.start();

        // Region manager should still be accessible after start
        assertSame(regionManager, server.getRegionManager(), "Should return same region manager");

        server.stop();
    }
}
