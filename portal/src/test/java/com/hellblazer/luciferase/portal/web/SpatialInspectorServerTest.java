package com.hellblazer.luciferase.portal.web;

import com.hellblazer.luciferase.common.time.Clock;
import io.javalin.testtools.JavalinTest;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SpatialInspectorServer REST API endpoints.
 * Uses Javalin TestTools for lightweight HTTP testing.
 */
class SpatialInspectorServerTest {

    private static final String JSON = "application/json";
    private static final String CREATE_OCTREE_BODY =
            "{\"indexType\":\"OCTREE\",\"maxDepth\":10,\"maxEntitiesPerNode\":10}";

    /** Post a raw JSON string body to the given path, bypassing Javalin's serialization. */
    private static okhttp3.Response postJson(io.javalin.testtools.HttpClient client,
                                             String path, String jsonBody) {
        return client.request(path,
                              builder -> builder.post(RequestBody.create(MediaType.parse(JSON), jsonBody)));
    }

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

    // ========== .47: Resource Cleanup Tests ==========

    /**
     * Creating a spatial index then deleting the session must release the index from
     * SpatialIndexService — hasIndex() must be false after the DELETE.
     */
    @Test
    void deleteSessionReleasesServiceResources() {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session
            var sessionId = extractSessionId(client.post("/api/session/create").body().string());
            assertNotNull(sessionId);

            // Create spatial index for the session
            var createIndexResp = postJson(client, "/api/spatial/create?sessionId=" + sessionId, CREATE_OCTREE_BODY);
            assertEquals(201, createIndexResp.code(), "Expected 201 creating index");
            assertTrue(server.spatialService().hasIndex(sessionId), "Index must exist before delete");

            // Delete the session
            var deleteResp = client.delete("/api/session/" + sessionId);
            assertEquals(200, deleteResp.code());

            // Service-side state must be cleaned up
            assertFalse(server.spatialService().hasIndex(sessionId),
                        "spatialService must not hold index after session delete");
            assertFalse(server.renderService().hasRender(sessionId),
                        "renderService must not hold render after session delete");
            assertFalse(server.gpuService().isGpuEnabled(sessionId),
                        "gpuService must not hold GPU session after session delete");
        });
    }

    /**
     * stop() must release service resources for all live sessions — not just call the
     * hollow session.close() no-op.
     * Tests directly without JavalinTest to avoid double-stop conflicts.
     */
    @Test
    void stopReleasesAllSessionServiceResources() throws Exception {
        var server = new SpatialInspectorServer(0);
        server.start();
        var okClient = new okhttp3.OkHttpClient();
        String sessionId;
        int port = server.port();
        // Create session
        var createResp = okClient.newCall(new Request.Builder()
                .url("http://localhost:" + port + "/api/session/create")
                .post(RequestBody.create(MediaType.parse(JSON), ""))
                .build()).execute();
        sessionId = extractSessionId(createResp.body().string());
        assertNotNull(sessionId);

        // Create spatial index
        var idxResp = okClient.newCall(new Request.Builder()
                .url("http://localhost:" + port + "/api/spatial/create?sessionId=" + sessionId)
                .post(RequestBody.create(MediaType.parse(JSON), CREATE_OCTREE_BODY))
                .build()).execute();
        assertEquals(201, idxResp.code(), "Expected 201 creating index");

        assertTrue(server.spatialService().hasIndex(sessionId));

        // stop() must free service resources
        server.stop();

        assertFalse(server.spatialService().hasIndex(sessionId),
                    "spatialService must be empty after stop()");
    }

    // ========== .48: Session Cap Tests ==========

    /**
     * Creating more than MAX_SESSIONS sessions must return HTTP 429.
     */
    @Test
    void exceedingSessionCapReturns429() {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Fill up to the cap
            for (int i = 0; i < SpatialInspectorServer.MAX_SESSIONS; i++) {
                var r = client.post("/api/session/create");
                assertEquals(201, r.code(), "Expected 201 at session " + i);
            }
            assertEquals(SpatialInspectorServer.MAX_SESSIONS, server.sessionCount());

            // One more must be rejected
            var overCapResp = client.post("/api/session/create");
            assertEquals(429, overCapResp.code(), "Expected 429 when over cap");
            assertTrue(overCapResp.body().string().contains("TooManyRequests"));
        });
    }

    // ========== .48: Reaper Tests ==========

    /**
     * After advancing an injected clock past SESSION_TIMEOUT_MS and calling runReaper(),
     * the session must be evicted and its spatial index released.
     */
    @Test
    void reaperEvictsExpiredSessionsAndFreesResources() {
        // Start at real current time so Instant.now() in SpatialSession aligns with the clock
        var clock = new TestClock(System.currentTimeMillis());
        var server = new SpatialInspectorServer(0, clock);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractSessionId(client.post("/api/session/create").body().string());
            assertNotNull(sessionId);

            // Give the session a spatial index
            var createIndexResp = postJson(client, "/api/spatial/create?sessionId=" + sessionId, CREATE_OCTREE_BODY);
            assertEquals(201, createIndexResp.code(), "Expected 201 creating index");
            assertTrue(server.spatialService().hasIndex(sessionId));

            // Session is not expired yet — reaper should not evict it
            server.runReaper();
            assertEquals(1, server.sessionCount(), "Session must not be evicted before timeout");
            assertTrue(server.spatialService().hasIndex(sessionId));

            // Advance clock well past the 30-minute timeout (SESSION_TIMEOUT_MS = 1_800_000 ms).
            // Use 2 hours to avoid any sub-ms skew between TestClock and Instant.now() in session.
            clock.advance(2 * 60 * 60 * 1_000);

            // Now reaper must evict and clean up
            server.runReaper();
            assertEquals(0, server.sessionCount(), "Expired session must be evicted");
            assertFalse(server.spatialService().hasIndex(sessionId),
                        "spatialService must not hold index after reaper eviction");
            assertFalse(server.renderService().hasRender(sessionId),
                        "renderService must not hold render after reaper eviction");
            assertFalse(server.gpuService().isGpuEnabled(sessionId),
                        "gpuService must not hold GPU after reaper eviction");
        });
    }

    /**
     * validateSession on an expired session must return 404, not silently refresh it.
     */
    @Test
    void validateSessionRejectsExpiredSession() {
        // Start at real current time so Instant.now() in SpatialSession aligns with the clock
        var clock = new TestClock(System.currentTimeMillis());
        var server = new SpatialInspectorServer(0, clock);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractSessionId(client.post("/api/session/create").body().string());
            assertNotNull(sessionId);

            // Advance clock well past timeout. Use 2h to absorb any sub-second skew between
            // TestClock construction and Instant.now() in SpatialSession.create().
            clock.advance(2 * 60 * 60 * 1_000);

            // Any endpoint that calls validateSession must reject with 404
            var resp = postJson(client, "/api/spatial/create?sessionId=" + sessionId, CREATE_OCTREE_BODY);
            assertEquals(404, resp.code(), "Expired session must be rejected, not refreshed");
            assertTrue(resp.body().string().contains("expired"),
                       "Error message must mention 'expired'");
        });
    }

    /**
     * Regression for S1 (sessionCount drift on validate-expire path).
     * <p>
     * When validateSession evicts an expired session it must decrement sessionCount,
     * otherwise the counter drifts above the actual live-session count and
     * createSession returns 429 forever even with an empty session map.
     */
    @Test
    void expiredSessionValidationDoesNotDriftSessionCount() {
        var clock = new TestClock(System.currentTimeMillis());
        var server = new SpatialInspectorServer(0, clock);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create one session and let it expire via the validateSession path
            var expiredId = extractSessionId(client.post("/api/session/create").body().string());
            assertNotNull(expiredId);
            assertEquals(1, server.sessionCount());

            // Advance past timeout so the next validateSession call expires it
            clock.advance(2 * 60 * 60 * 1_000);

            // Hit an endpoint that calls validateSession — triggers the expiry branch
            var resp = postJson(client, "/api/spatial/create?sessionId=" + expiredId, CREATE_OCTREE_BODY);
            assertEquals(404, resp.code(), "Expired session must be rejected");

            // Counter must have returned to 0 — no drift
            assertEquals(0, server.sessionCount(),
                         "sessionCount must be decremented when validateSession expires a session");

            // Fill up to MAX_SESSIONS — all must succeed (would fail at MAX_SESSIONS-1 with the bug)
            for (int i = 0; i < SpatialInspectorServer.MAX_SESSIONS; i++) {
                var r = client.post("/api/session/create");
                assertEquals(201, r.code(),
                             "Session " + (i + 1) + " of " + SpatialInspectorServer.MAX_SESSIONS
                             + " must succeed — sessionCount was drifted before fix");
            }
        });
    }

    /**
     * stop() must shut down the reaper executor cleanly (no exception, no thread leak).
     */
    @Test
    void stopShutsDownReaperCleanly() {
        var server = new SpatialInspectorServer(0);
        // stop() should not throw even with an idle executor
        assertDoesNotThrow(server::stop);
    }

    // ========== .218: Bulk-Insert and Per-Session Entity Cap Tests ==========

    /**
     * A bulk-insert request whose array length exceeds MAX_BULK_INSERT must return 413
     * and must NOT insert any entities into the spatial index.
     *
     * Uses a low cap (maxBulkInsert=3) to keep the JSON payload tiny.
     */
    @Test
    void bulkInsertExceedingArrayCapReturns413AndInsertsNothing() {
        // maxBulkInsert=3, maxSessionEntities=1000 — array of 4 exceeds bulk cap
        var server = new SpatialInspectorServer(0, Clock.system(), 3, 1000);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractSessionId(client.post("/api/session/create").body().string());
            postJson(client, "/api/spatial/create?sessionId=" + sessionId, CREATE_OCTREE_BODY);

            // Build an array of 4 entities (over the cap of 3)
            var body = buildEntityArray(4);
            var resp = postJson(client, "/api/spatial/entities/bulk-insert?sessionId=" + sessionId, body);

            assertEquals(413, resp.code(), "Expected 413 when bulk array exceeds cap");
            var respBody = resp.body().string();
            assertTrue(respBody.contains("PayloadTooLarge"), "Body must contain PayloadTooLarge type");

            // Nothing must have been inserted
            assertEquals(0, server.spatialService().sessionEntityCount(sessionId),
                         "No entities must be inserted when bulk array cap is exceeded");
        });
    }

    /**
     * Successive inserts that push the per-session total over maxSessionEntities must return 413.
     * The cap must be enforced by insertEntities (and insertEntity) so both paths are covered.
     *
     * Uses maxSessionEntities=5 to keep the test fast.
     */
    @Test
    void perSessionEntityCapReturns413WhenExceeded() {
        // maxBulkInsert=100, maxSessionEntities=5
        var server = new SpatialInspectorServer(0, Clock.system(), 100, 5);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractSessionId(client.post("/api/session/create").body().string());
            postJson(client, "/api/spatial/create?sessionId=" + sessionId, CREATE_OCTREE_BODY);

            // Insert exactly 5 (at cap) — must succeed
            var firstBatch = buildEntityArray(5);
            var okResp = postJson(client, "/api/spatial/entities/bulk-insert?sessionId=" + sessionId, firstBatch);
            assertEquals(201, okResp.code(), "Insert at cap must succeed");
            assertEquals(5, server.spatialService().sessionEntityCount(sessionId));

            // One more entity via single-insert path — must be rejected (cap=5, current=5)
            var singleInsert = "{\"x\":0.5,\"y\":0.5,\"z\":0.5}";
            var overCapResp = postJson(client, "/api/spatial/entities/insert?sessionId=" + sessionId, singleInsert);
            assertEquals(413, overCapResp.code(), "Single insert over session cap must return 413");
            assertTrue(overCapResp.body().string().contains("PayloadTooLarge"));

            // Also reject a bulk insert that would push over cap
            var extraBulk = buildEntityArray(1);
            var overCapBulkResp = postJson(client,
                    "/api/spatial/entities/bulk-insert?sessionId=" + sessionId, extraBulk);
            assertEquals(413, overCapBulkResp.code(), "Bulk insert over session cap must return 413");

            // Counter must remain at 5 — rejected inserts must not change it
            assertEquals(5, server.spatialService().sessionEntityCount(sessionId),
                         "Entity count must stay at cap after rejected inserts");
        });
    }

    /**
     * A bulk insert within both caps (array length ≤ maxBulkInsert and session total ≤ maxSessionEntities)
     * must return 201 and all entities must be counted.
     */
    @Test
    void withinCapBulkInsertSucceedsWithStatus201() {
        // maxBulkInsert=10, maxSessionEntities=20
        var server = new SpatialInspectorServer(0, Clock.system(), 10, 20);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractSessionId(client.post("/api/session/create").body().string());
            postJson(client, "/api/spatial/create?sessionId=" + sessionId, CREATE_OCTREE_BODY);

            var body = buildEntityArray(5);
            var resp = postJson(client, "/api/spatial/entities/bulk-insert?sessionId=" + sessionId, body);

            assertEquals(201, resp.code(), "Within-cap bulk insert must return 201");
            var respBody = resp.body().string();
            assertTrue(respBody.contains("\"inserted\":5"), "Response must report inserted count");

            assertEquals(5, server.spatialService().sessionEntityCount(sessionId),
                         "Session entity count must reflect inserted entities");
        });
    }

    /**
     * Build a JSON array of n minimal InsertEntityRequest objects with distinct positions.
     */
    private static String buildEntityArray(int n) {
        return IntStream.range(0, n)
                        .mapToObj(i -> "{\"x\":" + (i * 0.01f) + ",\"y\":0.0,\"z\":0.0}")
                        .collect(Collectors.joining(",", "[", "]"));
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
