package com.hellblazer.luciferase.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hellblazer.luciferase.portal.web.dto.*;
import com.hellblazer.luciferase.portal.web.service.SpatialIndexService.IndexType;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Spatial Index REST API endpoints.
 * Tests all three index types: OCTREE, TETREE, SFC.
 */
class SpatialIndexEndpointTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(IndexType.class)
    void createSpatialIndexForAllTypes(IndexType indexType) throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session first
            var sessionResponse = client.post("/api/session/create");
            var sessionBody = sessionResponse.body().string();
            var sessionId = extractJsonField(sessionBody, "sessionId");

            // Create spatial index
            var createRequest = new CreateIndexRequest(indexType, (byte) 8, 5);
            var response = client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            assertEquals(201, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"indexType\":\"" + indexType.name().toLowerCase() + "\""));
            assertTrue(body.contains("\"entityCount\":0"));
            assertTrue(body.contains("\"maxDepth\":8"));
        });
    }

    @Test
    void createSpatialIndexRequiresSession() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            var response = client.post("/api/spatial/create?sessionId=nonexistent",
                    objectMapper.writeValueAsString(createRequest));

            assertEquals(404, response.code());
        });
    }

    @Test
    void createSpatialIndexRequiresSessionIdParam() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            var response = client.post("/api/spatial/create",
                    objectMapper.writeValueAsString(createRequest));

            assertEquals(400, response.code());
            assertTrue(response.body().string().contains("sessionId"));
        });
    }

    @Test
    void getSpatialIndexInfo() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup: create session
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");

            // Create index
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Get info
            var response = client.get("/api/spatial/info?sessionId=" + sessionId);
            assertEquals(200, response.code());

            var body = response.body().string();
            assertTrue(body.contains("\"indexType\":\"octree\""));
            assertTrue(body.contains("\"sessionId\":\"" + sessionId + "\""));
        });
    }

    @Test
    void deleteSpatialIndex() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Delete
            var deleteResponse = client.delete("/api/spatial?sessionId=" + sessionId);
            assertEquals(200, deleteResponse.code());

            // Verify deleted
            var infoResponse = client.get("/api/spatial/info?sessionId=" + sessionId);
            assertEquals(404, infoResponse.code());
        });
    }

    @ParameterizedTest
    @EnumSource(IndexType.class)
    void insertEntityForAllIndexTypes(IndexType indexType) throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(indexType, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Insert entity
            var insertRequest = new InsertEntityRequest(0.5f, 0.5f, 0.5f, Map.of("name", "test"));
            var response = client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(insertRequest));

            assertEquals(201, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"entityId\""));
            assertTrue(body.contains("\"x\":0.5"));
            assertTrue(body.contains("\"name\":\"test\""));

            // Verify count increased
            var infoResponse = client.get("/api/spatial/info?sessionId=" + sessionId);
            assertTrue(infoResponse.body().string().contains("\"entityCount\":1"));
        });
    }

    @Test
    void bulkInsertEntities() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Bulk insert
            var requests = List.of(
                    new InsertEntityRequest(0.1f, 0.1f, 0.1f, null),
                    new InsertEntityRequest(0.5f, 0.5f, 0.5f, null),
                    new InsertEntityRequest(0.9f, 0.9f, 0.9f, null)
            );
            var response = client.post("/api/spatial/entities/bulk-insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(requests));

            assertEquals(201, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"inserted\":3"));

            // Verify count
            var infoResponse = client.get("/api/spatial/info?sessionId=" + sessionId);
            assertTrue(infoResponse.body().string().contains("\"entityCount\":3"));
        });
    }

    @Test
    void removeEntity() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Insert entity
            var insertRequest = new InsertEntityRequest(0.5f, 0.5f, 0.5f, null);
            var insertResponse = client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(insertRequest));
            var entityId = extractJsonField(insertResponse.body().string(), "entityId");

            // Remove entity
            var removeResponse = client.delete("/api/spatial/entities/" + entityId + "?sessionId=" + sessionId);
            assertEquals(200, removeResponse.code());
            assertTrue(removeResponse.body().string().contains("Entity removed"));

            // Verify count decreased
            var infoResponse = client.get("/api/spatial/info?sessionId=" + sessionId);
            assertTrue(infoResponse.body().string().contains("\"entityCount\":0"));
        });
    }

    @Test
    void removeNonexistentEntity() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            var response = client.delete("/api/spatial/entities/00000000-0000-0000-0000-000000000000?sessionId=" + sessionId);
            assertEquals(404, response.code());
        });
    }

    @Test
    void updateEntityPosition() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Insert entity
            var insertRequest = new InsertEntityRequest(0.1f, 0.1f, 0.1f, Map.of("data", "test"));
            var insertResponse = client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(insertRequest));
            var entityId = extractJsonField(insertResponse.body().string(), "entityId");

            // Update position
            var updateRequest = new UpdateEntityRequest(entityId, 0.9f, 0.9f, 0.9f);
            var updateResponse = client.put("/api/spatial/entities/update?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(updateRequest));

            assertEquals(200, updateResponse.code());
            var body = updateResponse.body().string();
            assertTrue(body.contains("\"x\":0.9"));
            assertTrue(body.contains("\"y\":0.9"));
            assertTrue(body.contains("\"z\":0.9"));
            assertTrue(body.contains("\"data\":\"test\"")); // Content preserved
        });
    }

    @Test
    void listEntitiesWithPagination() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Insert 5 entities
            for (var i = 0; i < 5; i++) {
                var insertRequest = new InsertEntityRequest(i * 0.1f, i * 0.1f, i * 0.1f, null);
                client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                        objectMapper.writeValueAsString(insertRequest));
            }

            // Get first page (size 2)
            var page0Response = client.get("/api/spatial/entities?sessionId=" + sessionId + "&page=0&size=2");
            assertEquals(200, page0Response.code());
            var page0Body = page0Response.body().string();
            assertTrue(page0Body.contains("\"totalCount\":5"));
            assertTrue(page0Body.contains("\"totalPages\":3"));
            assertTrue(page0Body.contains("\"page\":0"));

            // Get second page
            var page1Response = client.get("/api/spatial/entities?sessionId=" + sessionId + "&page=1&size=2");
            assertEquals(200, page1Response.code());
            assertTrue(page1Response.body().string().contains("\"page\":1"));
        });
    }

    @Test
    void cannotCreateDuplicateIndex() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Try to create another
            var createRequest2 = new CreateIndexRequest(IndexType.TETREE, (byte) 10, 10);
            var response = client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest2));

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
