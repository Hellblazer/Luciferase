package com.hellblazer.luciferase.simulation.viz;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MultiBubbleVisualizationServer.
 * Tests the REST API and JSON serialization without requiring a browser.
 */
class MultiBubbleVisualizationServerTest {

    private MultiBubbleVisualizationServer server;
    private int port;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        // Use dynamic port to avoid conflicts
        port = 7000 + new Random().nextInt(1000);
        server = new MultiBubbleVisualizationServer(port);
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testServerStartsAndStops() {
        server.start();
        assertTrue(server != null, "Server should be created");

        server.stop();
    }

    @Test
    void testHealthEndpoint() throws Exception {
        server.start();

        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/health"))
            .GET()
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Health endpoint should return 200 OK");

        String body = response.body();
        assertTrue(body.contains("\"status\""), "Health response should contain status");
        assertTrue(body.contains("\"bubbles\""), "Health response should contain bubble count");
    }

    @Test
    void testBubblesEndpointWithoutBubbles() throws Exception {
        server.start();

        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/bubbles"))
            .GET()
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode(), "Should return 404 when no bubbles configured");
    }

    @Test
    void testBubblesEndpointWithBubbles() throws Exception {
        // Create a small tetree grid
        var grid = new TetreeBubbleGrid((byte) 2);
        grid.createBubbles(4, (byte) 2, 10);
        var bubbles = grid.getAllBubbles().stream().toList();

        server.setBubbles(bubbles);
        server.start();

        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/bubbles"))
            .GET()
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Should return 200 OK with bubbles configured");

        String body = response.body();
        assertTrue(body.contains("\"bubbles\""), "Response should contain bubbles array");
        assertTrue(body.contains("\"id\""), "Bubble data should contain id");
        assertTrue(body.contains("\"entityCount\""), "Bubble data should contain entityCount");
    }

    @Test
    void testBubbleVerticesInJson() throws Exception {
        // Create a small tetree grid
        var grid = new TetreeBubbleGrid((byte) 2);
        grid.createBubbles(4, (byte) 2, 10);
        var bubbles = grid.getAllBubbles().stream().toList();

        // Extract vertices
        var vertices = extractBubbleVertices(grid, bubbles);

        server.setBubbles(bubbles);
        server.setBubbleVertices(vertices);
        server.start();

        // Give server time to start
        Thread.sleep(100);

        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/bubbles"))
            .GET()
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();

        assertEquals(200, response.statusCode(), "Should return 200 OK");

        // Verify vertices are included in JSON
        assertTrue(body.contains("\"vertices\""), "Bubble data should contain vertices array");
        assertTrue(body.contains("\"x\""), "Vertices should contain x coordinate");
        assertTrue(body.contains("\"y\""), "Vertices should contain y coordinate");
        assertTrue(body.contains("\"z\""), "Vertices should contain z coordinate");

        // Count how many bubbles have vertices
        int vertexCount = countOccurrences(body, "\"vertices\"");
        assertEquals(bubbles.size(), vertexCount,
            "All bubbles should have vertices in JSON (expected " + bubbles.size() + ", got " + vertexCount + ")");

        System.out.println("✓ Test passed: All " + bubbles.size() + " bubbles have tetrahedral vertices in JSON");
        System.out.println("Sample JSON (first 500 chars): " + body.substring(0, Math.min(500, body.length())));
    }

    private static Map<UUID, Point3f[]> extractBubbleVertices(TetreeBubbleGrid grid, List<EnhancedBubble> bubbles) {
        var vertices = new HashMap<UUID, Point3f[]>();
        var bubblesWithKeys = grid.getBubblesWithKeys();

        // Transform from Morton space [0, 2^20] to a normalized range for testing
        // In tests we don't have WorldBounds, so we'll scale to a reasonable range
        final float MORTON_MAX = 1 << 20; // 2^20 = 1048576
        final float TARGET_SIZE = 200f; // Match production world size
        final float scale = TARGET_SIZE / MORTON_MAX;

        for (var bubble : bubbles) {
            for (var entry : bubblesWithKeys.entrySet()) {
                if (entry.getValue().id().equals(bubble.id())) {
                    var tetreeKey = entry.getKey();
                    var tet = tetreeKey.toTet();
                    var coords = tet.coordinates();

                    var bubbleVertices = new Point3f[4];
                    for (int i = 0; i < 4; i++) {
                        // Scale from Morton space to world space
                        bubbleVertices[i] = new Point3f(
                            coords[i].x * scale,
                            coords[i].y * scale,
                            coords[i].z * scale
                        );
                    }

                    vertices.put(bubble.id(), bubbleVertices);
                    break;
                }
            }
        }

        return vertices;
    }

    @Test
    void testWebSocketBubbleBoundariesWithVertices() throws Exception {
        // Create a small tetree grid
        var grid = new TetreeBubbleGrid((byte) 2);
        grid.createBubbles(4, (byte) 2, 10);
        var bubbles = grid.getAllBubbles().stream().toList();

        // Extract vertices
        var vertices = extractBubbleVertices(grid, bubbles);

        server.setBubbles(bubbles);
        server.setBubbleVertices(vertices);
        server.start();

        // Give server time to start
        Thread.sleep(100);

        // Connect to WebSocket and capture initial bubble boundaries message
        var messageReceived = new CompletableFuture<String>();
        var webSocket = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + port + "/ws/bubbles"), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    messageReceived.complete(data.toString());
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    messageReceived.completeExceptionally(error);
                }
            })
            .get(5, TimeUnit.SECONDS);

        // Wait for initial bubble boundaries message
        String bubbleJson = messageReceived.get(5, TimeUnit.SECONDS);

        // Verify vertices are in WebSocket JSON
        assertTrue(bubbleJson.contains("\"bubbles\""), "WebSocket message should contain bubbles array");
        assertTrue(bubbleJson.contains("\"vertices\""), "Bubble data should contain vertices array");
        assertTrue(bubbleJson.contains("\"x\""), "Vertices should contain x coordinate");
        assertTrue(bubbleJson.contains("\"y\""), "Vertices should contain y coordinate");
        assertTrue(bubbleJson.contains("\"z\""), "Vertices should contain z coordinate");

        // Count how many bubbles have vertices
        int vertexCount = countOccurrences(bubbleJson, "\"vertices\"");
        assertEquals(bubbles.size(), vertexCount,
            "All bubbles should have vertices in WebSocket JSON (expected " + bubbles.size() + ", got " + vertexCount + ")");

        System.out.println("✓ WebSocket test passed: All " + bubbles.size() + " bubbles have tetrahedral vertices");
        System.out.println("Sample WebSocket JSON (first 500 chars): " + bubbleJson.substring(0, Math.min(500, bubbleJson.length())));

        // Close WebSocket
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
    }

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}
