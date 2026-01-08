/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for EntityVisualizationServer.
 */
class EntityVisualizationServerTest {

    private EntityVisualizationServer server;
    private EnhancedBubble bubble;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        // Use port 0 for dynamic assignment
        server = new EntityVisualizationServer(0);
        server.start();
        var port = server.port();
        baseUrl = "http://localhost:" + port;
        client = HttpClient.newHttpClient();
        bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testHealthEndpoint_noBubble() throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/health"))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = response.body();
        assertThat(body).contains("\"status\":\"ok\"");
        assertThat(body).contains("\"bubble\":false");
    }

    @Test
    void testHealthEndpoint_withBubble() throws Exception {
        server.setBubble(bubble);

        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/health"))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = response.body();
        assertThat(body).contains("\"status\":\"ok\"");
        assertThat(body).contains("\"bubble\":true");
    }

    @Test
    void testEntitiesEndpoint_noBubble() throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/entities"))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void testEntitiesEndpoint_withEntities() throws Exception {
        // Add entities to bubble
        bubble.addEntity("entity-1", new Point3f(50, 50, 50), null);
        bubble.addEntity("entity-2", new Point3f(100, 100, 100), null);
        server.setBubble(bubble);

        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/entities"))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = response.body();
        assertThat(body).contains("\"entities\":");
        assertThat(body).contains("entity-1");
        assertThat(body).contains("entity-2");
    }

    @Test
    void testDynamicPortAssignment() {
        // Given: Server created with port 0
        assertThat(server.port()).isGreaterThan(0);
        assertThat(server.port()).isNotEqualTo(0);

        // When: Multiple servers can coexist
        var server2 = new EntityVisualizationServer(0);
        server2.start();

        // Then: Different ports
        assertThat(server2.port()).isNotEqualTo(server.port());
        assertThat(server2.port()).isGreaterThan(0);

        server2.stop();
    }

    @Test
    void testSetBubble() {
        assertThat(server.getBubble()).isNull();

        server.setBubble(bubble);

        assertThat(server.getBubble()).isEqualTo(bubble);
    }

    @Test
    void testClientCount_initial() {
        assertThat(server.clientCount()).isEqualTo(0);
    }

    @Test
    void testStreaming_initial() {
        assertThat(server.isStreaming()).isFalse();
    }

    @Test
    void testWebSocketConnection() throws Exception {
        // Add entities
        var random = new Random(42);
        for (int i = 0; i < 10; i++) {
            bubble.addEntity("entity-" + i, new Point3f(
                10 + random.nextFloat() * 180,
                10 + random.nextFloat() * 180,
                10 + random.nextFloat() * 180
            ), null);
        }
        server.setBubble(bubble);

        var latch = new CountDownLatch(1);
        var messageReceived = new AtomicReference<String>();

        var wsClient = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://localhost:" + server.port() + "/ws/entities"),
                new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        messageReceived.set(data.toString());
                        latch.countDown();
                        return CompletableFuture.completedFuture(null);
                    }
                }
            )
            .join();

        // Wait for message
        var received = latch.await(5, TimeUnit.SECONDS);
        wsClient.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();

        assertThat(received).isTrue();
        assertThat(messageReceived.get()).isNotNull();
        assertThat(messageReceived.get()).contains("\"entities\":");
        assertThat(messageReceived.get()).contains("entity-0");
    }

    @Test
    void testEntityDTO() {
        var dto = new EntityVisualizationServer.EntityDTO("test-id", 1.0f, 2.0f, 3.0f, "DEFAULT");

        assertThat(dto.id()).isEqualTo("test-id");
        assertThat(dto.x()).isEqualTo(1.0f);
        assertThat(dto.y()).isEqualTo(2.0f);
        assertThat(dto.z()).isEqualTo(3.0f);
        assertThat(dto.type()).isEqualTo("DEFAULT");
    }

    @Test
    void testStaticFilesServed() throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/entity-viz.html"))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = response.body();
        assertThat(body).contains("Entity Visualization");
        assertThat(body).contains("three");
    }

    @Test
    void testJsFileServed() throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/entity-viz.js"))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = response.body();
        assertThat(body).contains("WebSocket");
        assertThat(body).contains("InstancedMesh");
    }
}
