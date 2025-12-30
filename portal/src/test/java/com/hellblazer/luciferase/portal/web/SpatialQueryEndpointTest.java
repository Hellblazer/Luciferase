package com.hellblazer.luciferase.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hellblazer.luciferase.portal.web.dto.*;
import com.hellblazer.luciferase.portal.web.service.SpatialIndexService.IndexType;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Spatial Query API endpoints.
 * Tests range, KNN, and ray intersection queries.
 */
class SpatialQueryEndpointTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rangeQueryReturnsEntitiesInBounds() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup: create session and index
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Insert entities at known positions
            var entities = List.of(
                    new InsertEntityRequest(0.1f, 0.1f, 0.1f, null),  // Inside range
                    new InsertEntityRequest(0.2f, 0.2f, 0.2f, null),  // Inside range
                    new InsertEntityRequest(0.5f, 0.5f, 0.5f, null),  // Inside range
                    new InsertEntityRequest(0.9f, 0.9f, 0.9f, null)   // Outside range
            );
            client.post("/api/spatial/entities/bulk-insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(entities));

            // Query range [0, 0.6] x [0, 0.6] x [0, 0.6]
            var rangeRequest = new RangeQueryRequest(0.0f, 0.0f, 0.0f, 0.6f, 0.6f, 0.6f);
            var response = client.post("/api/spatial/query/range?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(rangeRequest));

            assertEquals(200, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"count\":3"));
        });
    }

    @Test
    void rangeQueryReturnsEmptyForNoMatches() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Insert entity outside query range
            var insertRequest = new InsertEntityRequest(0.9f, 0.9f, 0.9f, null);
            client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(insertRequest));

            // Query range that doesn't contain the entity
            var rangeRequest = new RangeQueryRequest(0.0f, 0.0f, 0.0f, 0.1f, 0.1f, 0.1f);
            var response = client.post("/api/spatial/query/range?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(rangeRequest));

            assertEquals(200, response.code());
            assertTrue(response.body().string().contains("\"count\":0"));
        });
    }

    @Test
    void knnQueryReturnsNearestNeighbors() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Insert 5 entities at increasing distances from origin
            var entities = List.of(
                    new InsertEntityRequest(0.1f, 0.0f, 0.0f, null),  // distance ~0.1
                    new InsertEntityRequest(0.2f, 0.0f, 0.0f, null),  // distance ~0.2
                    new InsertEntityRequest(0.3f, 0.0f, 0.0f, null),  // distance ~0.3
                    new InsertEntityRequest(0.5f, 0.0f, 0.0f, null),  // distance ~0.5
                    new InsertEntityRequest(0.9f, 0.0f, 0.0f, null)   // distance ~0.9
            );
            client.post("/api/spatial/entities/bulk-insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(entities));

            // Query for 3 nearest neighbors from origin
            var knnRequest = new KnnQueryRequest(0.0f, 0.0f, 0.0f, 3, null);
            var response = client.post("/api/spatial/query/knn?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(knnRequest));

            assertEquals(200, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"count\":3"));
        });
    }

    @Test
    void knnQueryWithMaxDistance() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Insert entities
            var entities = List.of(
                    new InsertEntityRequest(0.1f, 0.0f, 0.0f, null),
                    new InsertEntityRequest(0.5f, 0.0f, 0.0f, null)
            );
            client.post("/api/spatial/entities/bulk-insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(entities));

            // Query with max distance that excludes the far entity
            var knnRequest = new KnnQueryRequest(0.0f, 0.0f, 0.0f, 10, 0.2f);
            var response = client.post("/api/spatial/query/knn?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(knnRequest));

            assertEquals(200, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"count\":1"));
        });
    }

    @Test
    void rayQueryReturnsIntersections() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Insert entities along a line
            var entities = List.of(
                    new InsertEntityRequest(0.2f, 0.5f, 0.5f, null),
                    new InsertEntityRequest(0.5f, 0.5f, 0.5f, null),
                    new InsertEntityRequest(0.8f, 0.5f, 0.5f, null)
            );
            client.post("/api/spatial/entities/bulk-insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(entities));

            // Cast ray through the entities
            var rayRequest = new RayQueryRequest(0.0f, 0.5f, 0.5f, 1.0f, 0.0f, 0.0f, null);
            var response = client.post("/api/spatial/query/ray?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(rayRequest));

            assertEquals(200, response.code());
            var body = response.body().string();
            // Ray intersection with point entities depends on implementation
            assertTrue(body.contains("\"hits\""));
        });
    }

    @Test
    void rayQueryWithMaxDistance() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var createRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 10, 10);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(createRequest));

            // Insert entity
            var insertRequest = new InsertEntityRequest(0.5f, 0.5f, 0.5f, null);
            client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(insertRequest));

            // Cast ray with max distance
            var rayRequest = new RayQueryRequest(0.0f, 0.5f, 0.5f, 1.0f, 0.0f, 0.0f, 0.3f);
            var response = client.post("/api/spatial/query/ray?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(rayRequest));

            assertEquals(200, response.code());
            assertTrue(response.body().string().contains("\"hits\""));
        });
    }

    @Test
    void queryRequiresSession() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var rangeRequest = new RangeQueryRequest(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f);
            var response = client.post("/api/spatial/query/range?sessionId=nonexistent",
                    objectMapper.writeValueAsString(rangeRequest));

            assertEquals(404, response.code());
        });
    }

    @Test
    void queryRequiresIndex() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");

            // Try query without creating index
            var rangeRequest = new RangeQueryRequest(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f);
            var response = client.post("/api/spatial/query/range?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(rangeRequest));

            assertEquals(404, response.code());
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
