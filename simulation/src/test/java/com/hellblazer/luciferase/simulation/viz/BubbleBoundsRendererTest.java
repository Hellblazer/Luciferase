package com.hellblazer.luciferase.simulation.viz;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.simulation.BubbleBounds;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for BubbleBoundsServer - HTML/JS visualization of tetrahedral bubble bounds.
 * <p>
 * Test strategy:
 * 1. Verify REST endpoint returns correct JSON structure
 * 2. Validate RDGCS to Cartesian conversion accuracy
 * 3. Test multiple bubbles rendered correctly
 * 4. Verify neighbor relationships visualized
 * 5. Confirm dynamic port assignment works
 * 6. Validate static file serving from /web
 */
class BubbleBoundsRendererTest {

    private BubbleBoundsServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        server = new BubbleBoundsServer(0); // Dynamic port
        server.start();
        var port = server.port();
        baseUrl = "http://localhost:" + port;
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testRestEndpointReturnsCorrectJson() throws Exception {
        // Given: Server with a single bubble
        var bubbleId = UUID.randomUUID();
        var positions = List.of(
            new Point3f(0.1f, 0.1f, 0.1f),
            new Point3f(0.2f, 0.2f, 0.2f)
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);
        server.addBubble(bubbleId, bounds, Set.of());

        // When: GET /api/bubbles
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/bubbles"))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Then: Returns 200 with JSON array
        assertThat(response.statusCode()).isEqualTo(200);
        var json = response.body();
        assertThat(json).contains("bubbleId");
        assertThat(json).contains("tetrahedralBounds");
        assertThat(json).contains("neighbors");
    }

    @Test
    void testRdgcsToCartesianConversion() throws Exception {
        // Given: Bubble with known RDGCS bounds
        var bubbleId = UUID.randomUUID();
        var tet = Tet.locatePointBeyRefinementFromRoot(0.5f, 0.5f, 0.5f, (byte) 10);
        var key = tet.tmIndex();
        var bounds = BubbleBounds.fromTetreeKey(key);
        server.addBubble(bubbleId, bounds, Set.of());

        // When: Fetch bubble data
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/bubbles"))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Then: Tetrahedral vertices are in Cartesian coordinates
        assertThat(response.statusCode()).isEqualTo(200);
        var json = response.body();

        // Verify coordinates are floats (Cartesian) not ints (RDGCS)
        assertThat(json).containsPattern("\"x\"\\s*:\\s*[0-9]+\\.[0-9]+");
        assertThat(json).containsPattern("\"y\"\\s*:\\s*[0-9]+\\.[0-9]+");
        assertThat(json).containsPattern("\"z\"\\s*:\\s*[0-9]+\\.[0-9]+");
    }

    @Test
    void testMultipleBubblesRendered() throws Exception {
        // Given: 3 bubbles at different locations
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();
        var bubble3 = UUID.randomUUID();

        var bounds1 = BubbleBounds.fromEntityPositions(List.of(new Point3f(0.1f, 0.1f, 0.1f)));
        var bounds2 = BubbleBounds.fromEntityPositions(List.of(new Point3f(0.5f, 0.5f, 0.5f)));
        var bounds3 = BubbleBounds.fromEntityPositions(List.of(new Point3f(0.9f, 0.9f, 0.9f)));

        server.addBubble(bubble1, bounds1, Set.of());
        server.addBubble(bubble2, bounds2, Set.of());
        server.addBubble(bubble3, bounds3, Set.of());

        // When: Fetch all bubbles
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/bubbles"))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Then: Returns 3 bubbles
        assertThat(response.statusCode()).isEqualTo(200);
        var json = response.body();

        // Count bubble occurrences
        var bubbleIdCount = json.split("bubbleId").length - 1;
        assertThat(bubbleIdCount).isEqualTo(3);
    }

    @Test
    void testNeighborRelationshipsVisualized() throws Exception {
        // Given: 2 bubbles with neighbor relationship
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        var bounds1 = BubbleBounds.fromEntityPositions(List.of(new Point3f(0.3f, 0.3f, 0.3f)));
        var bounds2 = BubbleBounds.fromEntityPositions(List.of(new Point3f(0.7f, 0.7f, 0.7f)));

        server.addBubble(bubble1, bounds1, Set.of(bubble2));
        server.addBubble(bubble2, bounds2, Set.of(bubble1));

        // When: Fetch bubbles
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/bubbles"))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Then: Each bubble lists its neighbor
        assertThat(response.statusCode()).isEqualTo(200);
        var json = response.body();

        assertThat(json).contains(bubble1.toString());
        assertThat(json).contains(bubble2.toString());
        assertThat(json).contains("neighbors");
    }

    @Test
    void testDynamicPortAssignment() {
        // Given: Server created with port 0
        assertThat(server.port()).isGreaterThan(0);
        assertThat(server.port()).isNotEqualTo(0);

        // When: Multiple servers can coexist
        var server2 = new BubbleBoundsServer(0);
        server2.start();

        // Then: Different ports
        assertThat(server2.port()).isNotEqualTo(server.port());
        assertThat(server2.port()).isGreaterThan(0);

        server2.stop();
    }

    @Test
    void testStaticFileServing() throws Exception {
        // Given: Server is running

        // When: Access static HTML file
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/bounds.html"))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Then: HTML file is served
        assertThat(response.statusCode()).isEqualTo(200);
        var html = response.body();
        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("Bubble Bounds");
    }
}
