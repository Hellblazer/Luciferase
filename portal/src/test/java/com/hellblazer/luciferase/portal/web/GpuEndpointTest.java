package com.hellblazer.luciferase.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hellblazer.luciferase.portal.web.dto.*;
import com.hellblazer.luciferase.portal.web.dto.CreateRenderRequest.RenderType;
import com.hellblazer.luciferase.portal.web.service.SpatialIndexService.IndexType;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GPU OpenCL REST API endpoints.
 * GPU-specific tests require RUN_GPU_TESTS=true environment variable.
 * This is necessary because OpenCL requires unsandboxed execution.
 */
class GpuEndpointTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void getGpuInfo() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var response = client.get("/api/gpu/info");
            assertEquals(200, response.code());

            var body = response.body().string();
            assertTrue(body.contains("\"available\""));

            // If available, should have device info
            if (body.contains("\"available\":true")) {
                assertTrue(body.contains("\"deviceName\""));
                assertTrue(body.contains("\"vendor\""));
                assertTrue(body.contains("\"computeUnits\""));
            }
        });
    }

    @Test
    void enableGpuRequiresRenderStructure() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session but no render
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");

            // Try to enable GPU without render structure
            var enableRequest = new GpuEnableRequest(400, 300);
            var response = client.post("/api/gpu/enable?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(enableRequest));

            assertEquals(409, response.code()); // Conflict
            assertTrue(response.body().string().contains("No ESVT render structure"));
        });
    }

    @Test
    void enableGpuRequiresESVTRenderType() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup with ESVO (not ESVT)
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");
            var indexRequest = new CreateIndexRequest(IndexType.OCTREE, (byte) 8, 5);
            client.post("/api/spatial/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(indexRequest));
            var insertRequest = new InsertEntityRequest(0.5f, 0.5f, 0.5f, null);
            client.post("/api/spatial/entities/insert?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(insertRequest));
            var renderRequest = new CreateRenderRequest(RenderType.ESVO, 8, 64);
            client.post("/api/render/create?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(renderRequest));

            // Try to enable GPU with ESVO render (GPU requires ESVT)
            var enableRequest = new GpuEnableRequest(400, 300);
            var response = client.post("/api/gpu/enable?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(enableRequest));

            var body = response.body().string();
            assertEquals(409, response.code(), "Expected 409 but got " + response.code() + ". Body: " + body);
            assertTrue(body.contains("ESVT"));
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    void enableAndDisableGpu() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup: session, tetree, entities, ESVT render
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

            // Enable GPU
            var enableRequest = new GpuEnableRequest(200, 150);
            var enableResponse = client.post("/api/gpu/enable?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(enableRequest));

            assertEquals(201, enableResponse.code());
            var body = enableResponse.body().string();
            assertTrue(body.contains("\"gpuEnabled\":true"));
            assertTrue(body.contains("\"frameWidth\":200"));
            assertTrue(body.contains("\"frameHeight\":150"));

            // Disable GPU
            var disableResponse = client.post("/api/gpu/disable?sessionId=" + sessionId);
            assertEquals(200, disableResponse.code());
            assertTrue(disableResponse.body().string().contains("GPU disabled"));

            // Stats should show disabled
            var statsResponse = client.get("/api/gpu/stats?sessionId=" + sessionId);
            assertTrue(statsResponse.body().string().contains("\"gpuEnabled\":false"));
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    void gpuRender() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup and enable GPU
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

            var enableRequest = new GpuEnableRequest(100, 75);
            client.post("/api/gpu/enable?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(enableRequest));

            // Render
            var gpuRenderRequest = new GpuRenderRequest(
                    1.5f, 1.5f, 1.5f,  // camera pos
                    0.5f, 0.5f, 0.5f,  // look at
                    60.0f,              // fov
                    "base64"            // output format
            );
            var response = client.post("/api/gpu/render?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(gpuRenderRequest));

            assertEquals(200, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"width\":100"));
            assertTrue(body.contains("\"height\":75"));
            assertTrue(body.contains("\"format\":\"RGBA\""));
            assertTrue(body.contains("\"encoding\":\"base64\""));
            assertTrue(body.contains("\"imageData\""));
            assertTrue(body.contains("\"renderTimeNs\""));

            // Cleanup
            client.post("/api/gpu/disable?sessionId=" + sessionId);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    void gpuBenchmark() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup and enable GPU
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

            var enableRequest = new GpuEnableRequest(100, 75);
            client.post("/api/gpu/enable?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(enableRequest));

            // Benchmark with 5 iterations
            var response = client.post("/api/gpu/benchmark?sessionId=" + sessionId + "&iterations=5");

            assertEquals(200, response.code());
            var body = response.body().string();
            assertTrue(body.contains("\"iterations\":5"));
            assertTrue(body.contains("\"avgRenderTimeMs\""));
            assertTrue(body.contains("\"minRenderTimeMs\""));
            assertTrue(body.contains("\"maxRenderTimeMs\""));
            assertTrue(body.contains("\"raysPerSecond\""));
            assertTrue(body.contains("\"deviceName\""));

            // Cleanup
            client.post("/api/gpu/disable?sessionId=" + sessionId);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    void gpuStats() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup and enable GPU
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

            var enableRequest = new GpuEnableRequest(100, 75);
            client.post("/api/gpu/enable?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(enableRequest));

            // Initial stats
            var statsResponse = client.get("/api/gpu/stats?sessionId=" + sessionId);
            assertEquals(200, statsResponse.code());
            var initialStats = statsResponse.body().string();
            assertTrue(initialStats.contains("\"gpuEnabled\":true"));
            assertTrue(initialStats.contains("\"framesRendered\":0"));

            // Render a frame
            var gpuRenderRequest = new GpuRenderRequest(1.5f, 1.5f, 1.5f, 0.5f, 0.5f, 0.5f, 60.0f, "base64");
            client.post("/api/gpu/render?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(gpuRenderRequest));

            // Check updated stats
            var updatedResponse = client.get("/api/gpu/stats?sessionId=" + sessionId);
            var updatedStats = updatedResponse.body().string();
            assertTrue(updatedStats.contains("\"framesRendered\":1"));

            // Cleanup
            client.post("/api/gpu/disable?sessionId=" + sessionId);
        });
    }

    @Test
    void gpuRenderRequiresGpuEnabled() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Setup ESVT but don't enable GPU
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

            // Try to render without GPU enabled
            var gpuRenderRequest = new GpuRenderRequest(1.5f, 1.5f, 1.5f, 0.5f, 0.5f, 0.5f, 60.0f, "base64");
            var response = client.post("/api/gpu/render?sessionId=" + sessionId,
                    objectMapper.writeValueAsString(gpuRenderRequest));

            assertEquals(404, response.code()); // Not found (GPU not enabled)
        });
    }

    @Test
    void disableGpuRequiresGpuEnabled() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            // Create session only
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");

            // Try to disable GPU when not enabled
            var response = client.post("/api/gpu/disable?sessionId=" + sessionId);

            assertEquals(404, response.code()); // Not found
        });
    }

    @Test
    void gpuStatsWhenDisabled() throws Exception {
        var server = new SpatialInspectorServer(0);
        JavalinTest.test(server.app(), (javalin, client) -> {
            var sessionId = extractJsonField(client.post("/api/session/create").body().string(), "sessionId");

            var response = client.get("/api/gpu/stats?sessionId=" + sessionId);
            assertEquals(200, response.code());
            assertTrue(response.body().string().contains("\"gpuEnabled\":false"));
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
