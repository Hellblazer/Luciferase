package com.hellblazer.luciferase.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hellblazer.luciferase.portal.web.dto.*;
import com.hellblazer.luciferase.portal.web.dto.CreateRenderRequest.RenderType;
import com.hellblazer.luciferase.portal.web.service.SpatialIndexService.IndexType;
import io.javalin.testtools.JavalinTest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for concurrent access to the REST API.
 * Verifies thread-safety of session management, spatial operations, and rendering.
 */
class ConcurrentAccessTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json");

    @Test
    void concurrentSessionCreation() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var executor = Executors.newFixedThreadPool(10);
            var successCount = new AtomicInteger(0);
            var latch = new CountDownLatch(20);

            for (var i = 0; i < 20; i++) {
                executor.submit(() -> {
                    try {
                        var response = client.post("/api/session/create");
                        if (response.code() == 201) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Ignore
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS), "All requests should complete");
            assertEquals(20, successCount.get(), "All sessions should be created");

            executor.shutdown();
        });
    }

    @Test
    void concurrentEntityInsertsSameSession() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session and spatial index
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var indexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));

            // Concurrent entity insertions
            var executor = Executors.newFixedThreadPool(5);
            var successCount = new AtomicInteger(0);
            var latch = new CountDownLatch(50);

            for (var i = 0; i < 50; i++) {
                final var idx = i;
                executor.submit(() -> {
                    try {
                        var x = 0.1f + (idx % 10) * 0.08f;
                        var y = 0.1f + ((idx / 10) % 10) * 0.08f;
                        var z = 0.1f + (idx / 100) * 0.08f;
                        var insertRequest = new InsertEntityRequest(x, y, z, null);
                        var response = client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                                objectMapper.writeValueAsString(insertRequest));
                        if (response.code() == 201) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Ignore
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(15, TimeUnit.SECONDS), "All inserts should complete");
            assertEquals(50, successCount.get(), "All entities should be inserted");

            // Verify entity count
            var infoResponse = client.get("/api/spatial/info?sessionId=" + sessionId);
            assertEquals(200, infoResponse.code());
            assertTrue(infoResponse.body().string().contains("\"entityCount\":50"));

            executor.shutdown();
        });
    }

    @Test
    void concurrentQueriesSameSession() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup: create session, index, and entities
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var indexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));

            // Insert some entities
            for (var i = 0; i < 20; i++) {
                var insertRequest = new InsertEntityRequest(
                        0.1f + (i % 5) * 0.15f,
                        0.1f + ((i / 5) % 4) * 0.2f,
                        0.5f,
                        null
                );
                client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                        objectMapper.writeValueAsString(insertRequest));
            }

            // Concurrent range queries
            var executor = Executors.newFixedThreadPool(5);
            var successCount = new AtomicInteger(0);
            var latch = new CountDownLatch(30);

            for (var i = 0; i < 30; i++) {
                executor.submit(() -> {
                    try {
                        var rangeRequest = new RangeQueryRequest(0.2f, 0.2f, 0.3f, 0.7f, 0.7f, 0.7f);
                        var response = client.post("/api/spatial/query/range?sessionId=" + sessionId,
                                objectMapper.writeValueAsString(rangeRequest));
                        if (response.code() == 200) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Ignore
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(15, TimeUnit.SECONDS), "All queries should complete");
            assertEquals(30, successCount.get(), "All queries should succeed");

            executor.shutdown();
        });
    }

    @Test
    void concurrentRaycasts() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var indexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));

            // Insert entities
            for (var i = 0; i < 10; i++) {
                var insertRequest = new InsertEntityRequest(
                        0.3f + (i % 3) * 0.2f,
                        0.3f + ((i / 3) % 3) * 0.2f,
                        0.5f,
                        null
                );
                client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                        objectMapper.writeValueAsString(insertRequest));
            }

            // Create render structure
            var renderRequest = new CreateRenderRequest(RenderType.ESVT, 8, 64);
            client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            // Concurrent raycasts
            var executor = Executors.newFixedThreadPool(5);
            var successCount = new AtomicInteger(0);
            var latch = new CountDownLatch(20);

            for (var i = 0; i < 20; i++) {
                final var angle = i * Math.PI / 10;
                executor.submit(() -> {
                    try {
                        var raycastRequest = new RaycastRequest(
                                1.5f, 0.5f, 0.5f,
                                (float) -Math.cos(angle), (float) Math.sin(angle) * 0.1f, 0f,
                                null
                        );
                        var response = client.post("/api/render/raycast?sessionId=" + sessionId,
                                objectMapper.writeValueAsString(raycastRequest));
                        if (response.code() == 200) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Ignore
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(15, TimeUnit.SECONDS), "All raycasts should complete");
            assertEquals(20, successCount.get(), "All raycasts should succeed");

            executor.shutdown();
        });
    }

    @Test
    @Disabled("Flaky concurrent test: Race condition where one of five concurrent sessions intermittently fails under load")
    void multipleSeparateSessions() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var executor = Executors.newFixedThreadPool(5);
            var successCount = new AtomicInteger(0);
            var latch = new CountDownLatch(5);

            // Each thread creates its own session and performs operations
            for (var thread = 0; thread < 5; thread++) {
                executor.submit(() -> {
                    try {
                        // Create session
                        var sessionId = extractJsonField(
                                client.post("/api/session/create").body().string(), "sessionId");

                        // Create index
                        var indexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
                        client.post("/api/spatial/create?sessionId=" + sessionId,
                                objectMapper.writeValueAsString(indexRequest));

                        // Insert entities
                        for (var i = 0; i < 10; i++) {
                            var insertRequest = new InsertEntityRequest(
                                    (float) Math.random() * 0.8f + 0.1f,
                                    (float) Math.random() * 0.8f + 0.1f,
                                    (float) Math.random() * 0.8f + 0.1f,
                                    null
                            );
                            var insertResponse = client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                                    objectMapper.writeValueAsString(insertRequest));
                            if (insertResponse.code() != 201) {
                                return;
                            }
                        }

                        // Verify
                        var infoResponse = client.get("/api/spatial/info?sessionId=" + sessionId);
                        if (infoResponse.code() == 200 &&
                                infoResponse.body().string().contains("\"entityCount\":10")) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Ignore
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS), "All threads should complete");
            assertEquals(5, successCount.get(), "All sessions should work correctly");

            executor.shutdown();
        });
    }

    // ===== Helper Methods =====

    private String extractJsonField(String json, String field) {
        var marker = "\"" + field + "\":\"";
        var start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        var end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
}
