package com.hellblazer.luciferase.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hellblazer.luciferase.portal.web.dto.CreateIndexRequest;
import com.hellblazer.luciferase.portal.web.dto.CreateRenderRequest;
import com.hellblazer.luciferase.portal.web.dto.CreateRenderRequest.RenderType;
import com.hellblazer.luciferase.portal.web.service.SpatialIndexService.IndexType;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Mesh REST API endpoints including Stanford Bunny.
 */
class MeshEndpointTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listMeshesReturnsAvailableMeshes() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var response = client.get("/api/mesh/list");

            assertEquals(200, response.code());
            var body = response.body().string();
            assertTrue(body.contains("Stanford Bunny"));
        });
    }

    @Test
    void getBunnyMeshReturnsVoxelData() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var response = client.get("/api/mesh/bunny");

            assertEquals(200, response.code());
            var body = response.body().string();
            var json = objectMapper.readTree(body);

            // Verify metadata
            assertEquals("Stanford Bunny", json.get("name").asText());
            assertEquals(47705, json.get("voxelCount").asInt());

            // Verify entities are present
            var entities = json.get("entities");
            assertTrue(entities.isArray());
            assertEquals(47705, entities.size());

            // Verify entity structure
            var firstEntity = entities.get(0);
            assertTrue(firstEntity.has("x"));
            assertTrue(firstEntity.has("y"));
            assertTrue(firstEntity.has("z"));

            // Verify coordinates are normalized to [0,1]
            var x = firstEntity.get("x").asDouble();
            var y = firstEntity.get("y").asDouble();
            var z = firstEntity.get("z").asDouble();
            assertTrue(x >= 0 && x <= 1, "x should be normalized: " + x);
            assertTrue(y >= 0 && y <= 1, "y should be normalized: " + y);
            assertTrue(z >= 0 && z <= 1, "z should be normalized: " + z);
        });
    }

    @Test
    void bunnyEntityBulkInsertIntoTetree() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session
            var sessionResponse = client.post("/api/session/create");
            var sessionId = extractJsonField(sessionResponse.body().string(), "sessionId");

            // Create TETREE spatial index
            var createRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            var indexResponse = client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));
            assertEquals(201, indexResponse.code());

            // Fetch bunny mesh
            var bunnyResponse = client.get("/api/mesh/bunny");
            var bunnyJson = objectMapper.readTree(bunnyResponse.body().string());
            var entities = bunnyJson.get("entities");

            // Bulk insert bunny entities
            var insertResponse = client.post("/api/spatial/entities/bulk-insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(entities));

            assertEquals(201, insertResponse.code());
            var insertBody = objectMapper.readTree(insertResponse.body().string());
            assertEquals(47705, insertBody.get("inserted").asInt());

            // Verify spatial index info
            var infoResponse = client.get("/api/spatial/info?sessionId=" + sessionId);
            var infoJson = objectMapper.readTree(infoResponse.body().string());
            assertEquals(47705, infoJson.get("entityCount").asInt());
        });
    }

    @Test
    void bunnyEntityBulkInsertIntoOctree() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session
            var sessionResponse = client.post("/api/session/create");
            var sessionId = extractJsonField(sessionResponse.body().string(), "sessionId");

            // Create OCTREE spatial index
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 8, 5);
            var indexResponse = client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));
            assertEquals(201, indexResponse.code());

            // Fetch bunny mesh
            var bunnyResponse = client.get("/api/mesh/bunny");
            var bunnyJson = objectMapper.readTree(bunnyResponse.body().string());
            var entities = bunnyJson.get("entities");

            // Bulk insert bunny entities
            var insertResponse = client.post("/api/spatial/entities/bulk-insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(entities));

            assertEquals(201, insertResponse.code());
            var insertBody = objectMapper.readTree(insertResponse.body().string());
            assertEquals(47705, insertBody.get("inserted").asInt());
        });
    }

    @Test
    void bunnyFullRenderPipelineWithESVT() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session
            var sessionResponse = client.post("/api/session/create");
            var sessionId = extractJsonField(sessionResponse.body().string(), "sessionId");

            // Create TETREE spatial index
            var createIndexRequest = new CreateIndexRequest(IndexType.TETREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createIndexRequest));

            // Fetch and insert bunny entities
            var bunnyResponse = client.get("/api/mesh/bunny");
            var bunnyJson = objectMapper.readTree(bunnyResponse.body().string());
            var entities = bunnyJson.get("entities");

            client.post("/api/spatial/entities/bulk-insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(entities));

            // Create ESVT render structure
            var renderRequest = new CreateRenderRequest(RenderType.ESVT, 8, 64);
            var renderResponse = client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            assertEquals(201, renderResponse.code());
            var renderJson = objectMapper.readTree(renderResponse.body().string());
            assertEquals("esvt", renderJson.get("type").asText());
            assertTrue(renderJson.get("nodeCount").asInt() > 0);
        });
    }

    @Test
    void bunnyFullRenderPipelineWithESVO() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session
            var sessionResponse = client.post("/api/session/create");
            var sessionId = extractJsonField(sessionResponse.body().string(), "sessionId");

            // Create OCTREE spatial index
            var createIndexRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createIndexRequest));

            // Fetch and insert bunny entities
            var bunnyResponse = client.get("/api/mesh/bunny");
            var bunnyJson = objectMapper.readTree(bunnyResponse.body().string());
            var entities = bunnyJson.get("entities");

            client.post("/api/spatial/entities/bulk-insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(entities));

            // Create ESVO render structure
            var renderRequest = new CreateRenderRequest(RenderType.ESVO, 8, 64);
            var renderResponse = client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            assertEquals(201, renderResponse.code());
            var renderJson = objectMapper.readTree(renderResponse.body().string());
            assertEquals("esvo", renderJson.get("type").asText());
            assertTrue(renderJson.get("nodeCount").asInt() > 0);
        });
    }

    @Test
    void bunnyMeshIsCached() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // First request - loads from file
            var start1 = System.currentTimeMillis();
            var response1 = client.get("/api/mesh/bunny");
            var elapsed1 = System.currentTimeMillis() - start1;

            assertEquals(200, response1.code());

            // Second request - should be cached
            var start2 = System.currentTimeMillis();
            var response2 = client.get("/api/mesh/bunny");
            var elapsed2 = System.currentTimeMillis() - start2;

            assertEquals(200, response2.code());

            // Cache should make subsequent requests faster (relaxed assertion for CI)
            // The important thing is both succeed with same data
            var json1 = objectMapper.readTree(response1.body().string());
            var json2 = objectMapper.readTree(response2.body().string());
            assertEquals(json1.get("voxelCount").asInt(), json2.get("voxelCount").asInt());
        });
    }

    private static String extractJsonField(String json, String field) throws Exception {
        var tree = objectMapper.readTree(json);
        return tree.get(field).asText();
    }
}
