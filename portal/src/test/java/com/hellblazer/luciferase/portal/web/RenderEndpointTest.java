package com.hellblazer.luciferase.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hellblazer.luciferase.portal.web.dto.*;
import com.hellblazer.luciferase.portal.web.dto.CreateRenderRequest.RenderType;
import com.hellblazer.luciferase.portal.web.service.SpatialIndexService.IndexType;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVO/ESVT Render REST API endpoints.
 */
class RenderEndpointTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createESVTRender() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");

            // Create Tetree spatial index (required for ESVT)
            var indexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));

            // Insert some entities
            for (var i = 0; i < 10; i++) {
                var insertRequest = new InsertEntityRequest(
                        (float) Math.random() * 0.8f + 0.1f,
                        (float) Math.random() * 0.8f + 0.1f,
                        (float) Math.random() * 0.8f + 0.1f,
                        Map.of("index", i)
                );
                client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                        objectMapper.writeValueAsString(insertRequest));
            }

            // Create ESVT render
            var renderRequest = new CreateRenderRequest(RenderType.ESVT, 8, 64);
            var response = client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            assertEquals(201, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"type\":\"esvt\""));
            assertTrue(body.contains("\"sessionId\":\"" + sessionId + "\""));
        });
    }

    @Test
    void createESVORender() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");

            // Create Octree spatial index (required for ESVO)
            var indexRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));

            // Insert some entities
            for (var i = 0; i < 10; i++) {
                var insertRequest = new InsertEntityRequest(
                        (float) Math.random() * 0.8f + 0.1f,
                        (float) Math.random() * 0.8f + 0.1f,
                        (float) Math.random() * 0.8f + 0.1f,
                        null
                );
                client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                        objectMapper.writeValueAsString(insertRequest));
            }

            // Create ESVO render
            var renderRequest = new CreateRenderRequest(RenderType.ESVO, 8, 64);
            var response = client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            assertEquals(201, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"type\":\"esvo\""));
        });
    }

    @Test
    void createRenderRequiresSpatialIndex() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session only (no spatial index)
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");

            // Try to create render without spatial index
            var renderRequest = new CreateRenderRequest(RenderType.ESVT, 8, 64);
            var response = client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            assertEquals(409, response.code()); // Conflict
            assertTrue(response.body().string().contains("No spatial index"));
        });
    }

    @Test
    void createRenderRequiresCorrectIndexType() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");

            // Create Octree (not Tetree)
            var indexRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));

            // Insert an entity
            var insertRequest = new InsertEntityRequest(0.5f, 0.5f, 0.5f, null);
            client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(insertRequest));

            // Try to create ESVT (requires Tetree, not Octree)
            var renderRequest = new CreateRenderRequest(RenderType.ESVT, 8, 64);
            var response = client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            assertEquals(400, response.code()); // Bad request
            assertTrue(response.body().string().contains("Tetree"));
        });
    }

    @Test
    void getRenderInfo() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup: create session, tetree, entities, and render
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var indexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));
            for (var i = 0; i < 5; i++) {
                var insertRequest = new InsertEntityRequest(0.1f + i * 0.15f, 0.1f + i * 0.15f, 0.1f + i * 0.15f, null);
                client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                        objectMapper.writeValueAsString(insertRequest));
            }
            var renderRequest = new CreateRenderRequest(RenderType.ESVT, 8, 64);
            client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            // Get render info
            var response = client.get("/api/render/info?sessionId=" + sessionId);
            assertEquals(200, response.code());

            var body = response.body().string();
            assertTrue(body.contains("\"type\":\"esvt\""));
            assertTrue(body.contains("\"nodeCount\""));
            assertTrue(body.contains("\"leafCount\""));
        });
    }

    @Test
    void getRenderInfoWithoutRender() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session only
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");

            // Try to get render info without creating render
            var response = client.get("/api/render/info?sessionId=" + sessionId);
            assertEquals(404, response.code());
        });
    }

    @Test
    void deleteRender() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var indexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));
            var insertRequest = new InsertEntityRequest(0.5f, 0.5f, 0.5f, null);
            client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(insertRequest));
            var renderRequest = new CreateRenderRequest(RenderType.ESVT, 8, 64);
            client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            // Delete render
            var deleteResponse = client.delete("/api/render?sessionId=" + sessionId);
            assertEquals(200, deleteResponse.code());
            assertTrue(deleteResponse.body().string().contains("Render structure deleted"));

            // Verify deleted
            var infoResponse = client.get("/api/render/info?sessionId=" + sessionId);
            assertEquals(404, infoResponse.code());
        });
    }

    @Test
    void setCamera() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var indexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));
            var insertRequest = new InsertEntityRequest(0.5f, 0.5f, 0.5f, null);
            client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(insertRequest));
            var renderRequest = new CreateRenderRequest(RenderType.ESVT, 8, 64);
            client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            // Set camera
            var cameraRequest = new CameraRequest(
                    1.5f, 1.5f, 1.5f,  // position
                    0.5f, 0.5f, 0.5f,  // target
                    0f, 1f, 0f,         // up
                    60.0f               // fov
            );
            var response = client.post("/api/render/camera?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(cameraRequest));

            assertEquals(200, response.code());
            assertTrue(response.body().string().contains("Camera updated"));
        });
    }

    @Test
    void raycastESVT() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var indexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));
            var insertRequest = new InsertEntityRequest(0.5f, 0.5f, 0.5f, null);
            client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(insertRequest));
            var renderRequest = new CreateRenderRequest(RenderType.ESVT, 8, 64);
            client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            // Raycast from outside towards center
            var raycastRequest = new RaycastRequest(
                    1.5f, 0.5f, 0.5f,  // origin (outside)
                    -1f, 0f, 0f,       // direction (towards center)
                    null               // max distance
            );
            var response = client.post("/api/render/raycast?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(raycastRequest));

            assertEquals(200, response.code());
            var body = response.body().string();
            // Response will contain hit field (may be true or false depending on data)
            assertTrue(body.contains("\"hit\""));
        });
    }

    @Test
    void raycastRequiresRenderStructure() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session and spatial index but no render
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var indexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));

            // Try raycast without render
            var raycastRequest = new RaycastRequest(1.5f, 0.5f, 0.5f, -1f, 0f, 0f, null);
            var response = client.post("/api/render/raycast?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(raycastRequest));

            assertEquals(404, response.code());
        });
    }

    @Test
    void getRenderStats() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var indexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));
            var insertRequest = new InsertEntityRequest(0.5f, 0.5f, 0.5f, null);
            client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(insertRequest));
            var renderRequest = new CreateRenderRequest(RenderType.ESVT, 8, 64);
            client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            var response = client.get("/api/render/stats?sessionId=" + sessionId);
            assertEquals(200, response.code());

            var body = response.body().string();
            assertTrue(body.contains("\"type\":\"esvt\""));
            assertTrue(body.contains("\"nodeCount\""));
            assertTrue(body.contains("\"memoryBytes\""));
            assertTrue(body.contains("\"farPointerCount\""));
        });
    }

    @Test
    void cannotCreateDuplicateRender() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var indexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));
            var insertRequest = new InsertEntityRequest(0.5f, 0.5f, 0.5f, null);
            client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(insertRequest));
            var renderRequest = new CreateRenderRequest(RenderType.ESVT, 8, 64);
            client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            // Try to create another render
            var renderRequest2 = new CreateRenderRequest(RenderType.ESVT, 8, 64);
            var response = client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest2));

            assertEquals(409, response.code()); // Conflict
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
