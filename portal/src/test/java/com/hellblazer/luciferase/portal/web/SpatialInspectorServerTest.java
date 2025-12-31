package com.hellblazer.luciferase.portal.web;

import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SpatialInspectorServer REST API endpoints.
 * Uses Javalin TestTools for lightweight HTTP testing.
 */
class SpatialInspectorServerTest {

    @Test
    void healthEndpointReturnsOk() {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var response = client.get("/api/health");
            assertEquals(200, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"status\":\"ok\""));
            assertTrue(body.contains("\"timestamp\""));
        });
    }

    @Test
    void infoEndpointReturnsCapabilities() {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var response = client.get("/api/info");
            assertEquals(200, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"name\":\"Luciferase Spatial Inspector\""));
            assertTrue(body.contains("\"version\":\"0.0.3-SNAPSHOT\""));
            assertTrue(body.contains("\"spatialIndices\""));
            assertTrue(body.contains("octree"));
            assertTrue(body.contains("tetree"));
            assertTrue(body.contains("\"rendering\""));
            assertTrue(body.contains("esvo"));
            assertTrue(body.contains("esvt"));
        });
    }

    @Test
    void createSessionReturnsSessionId() {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var response = client.post("/api/session/create");
            assertEquals(201, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"sessionId\""));
            assertTrue(body.contains("\"created\""));
            assertTrue(body.contains("\"message\":\"Session created successfully\""));
        });
    }

    @Test
    void getSessionReturnsSessionInfo() {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create a session first
            var createResponse = client.post("/api/session/create");
            assertEquals(201, createResponse.code());
            var createBody = createResponse.body().string();

            // Extract session ID from response
            var sessionId = extractSessionId(createBody);
            assertNotNull(sessionId, "Session ID should be present in response");

            // Get the session
            var getResponse = client.get("/api/session/" + sessionId);
            assertEquals(200, getResponse.code());
            var getBody = getResponse.body().string();
            assertTrue(getBody.contains("\"sessionId\":\"" + sessionId + "\""));
            assertTrue(getBody.contains("\"created\""));
            assertTrue(getBody.contains("\"lastAccessed\""));
            assertTrue(getBody.contains("\"expired\""));
        });
    }

    @Test
    void getSessionReturns404ForUnknownSession() {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var response = client.get("/api/session/nonexistent-session-id");
            assertEquals(404, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"error\":\"Session not found\""));
        });
    }

    @Test
    void deleteSessionRemovesSession() {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create a session
            var createResponse = client.post("/api/session/create");
            var sessionId = extractSessionId(createResponse.body().string());

            // Delete the session
            var deleteResponse = client.delete("/api/session/" + sessionId);
            assertEquals(200, deleteResponse.code());
            var deleteBody = deleteResponse.body().string();
            assertTrue(deleteBody.contains("\"message\":\"Session deleted\""));

            // Verify session is gone
            var getResponse = client.get("/api/session/" + sessionId);
            assertEquals(404, getResponse.code());
        });
    }

    @Test
    void deleteSessionReturns404ForUnknownSession() {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var response = client.delete("/api/session/nonexistent-session-id");
            assertEquals(404, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"error\":\"Session not found\""));
        });
    }

    @Test
    void infoEndpointTracksActiveSessions() {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Initially no sessions
            var info1 = client.get("/api/info").body().string();
            assertTrue(info1.contains("\"activeSessions\":0"));

            // Create a session
            client.post("/api/session/create");

            // Now one session
            var info2 = client.get("/api/info").body().string();
            assertTrue(info2.contains("\"activeSessions\":1"));

            // Create another session
            client.post("/api/session/create");

            // Now two sessions
            var info3 = client.get("/api/info").body().string();
            assertTrue(info3.contains("\"activeSessions\":2"));
        });
    }

    @Test
    void staticFilesServedFromWebDirectory() {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var response = client.get("/index.html");
            assertEquals(200, response.code());
            var body = response.body().string();
            assertTrue(body.contains("Luciferase Spatial Inspector"));
            assertTrue(body.contains("Web-based 3D Spatial Visualization"));
        });
    }

    @Test
    void cssFileServed() {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var response = client.get("/styles.css");
            assertEquals(200, response.code());
            var body = response.body().string();
            assertTrue(body.contains("--primary-color"));
            assertTrue(body.contains("--secondary-color"));
        });
    }

    /**
     * Extract session ID from JSON response.
     * Simple extraction without JSON library dependency.
     */
    private String extractSessionId(String jsonResponse) {
        var marker = "\"sessionId\":\"";
        var start = jsonResponse.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        var end = jsonResponse.indexOf("\"", start);
        if (end < 0) return null;
        return jsonResponse.substring(start, end);
    }
}
