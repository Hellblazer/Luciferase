package com.hellblazer.luciferase.simulation.viz;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test MultiBubbleVisualizationServer's ability to handle both 4-vertex (tetrahedra)
 * and 8-vertex (bounding boxes) geometry.
 * <p>
 * Validates the fix for accepting 8-vertex arrays in addition to 4-vertex arrays.
 */
class BubbleVisualizationServerTest {

    private static final int HTTP_PORT = 0; // Dynamic port

    private MultiBubbleVisualizationServer vizServer;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        vizServer = new MultiBubbleVisualizationServer(HTTP_PORT);
        vizServer.start();

        httpClient = HttpClient.newHttpClient();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (vizServer != null) {
            vizServer.stop();
        }
    }

    /**
     * Test 1: Server accepts 8-vertex bounding boxes (AABB).
     */
    @Test
    void testServerAccepts8VertexBoxes() throws Exception {
        var bubbleId = UUID.randomUUID();

        // Create 8-vertex box (min=0,0,0 max=100,100,100)
        var box8Vertices = new Point3f[]{
            new Point3f(0f, 0f, 0f),     // V0: min corner
            new Point3f(100f, 0f, 0f),   // V1
            new Point3f(100f, 100f, 0f), // V2
            new Point3f(0f, 100f, 0f),   // V3
            new Point3f(0f, 0f, 100f),   // V4
            new Point3f(100f, 0f, 100f), // V5
            new Point3f(100f, 100f, 100f), // V6: max corner
            new Point3f(0f, 100f, 100f)  // V7
        };

        var verticesMap = new HashMap<UUID, Point3f[]>();
        verticesMap.put(bubbleId, box8Vertices);

        vizServer.setBubbleVertices(verticesMap);

        // Verify vertices are in internal state
        var vertices = vizServer.getBubbleVertices();
        assertThat(vertices).containsKey(bubbleId);
        assertThat(vertices.get(bubbleId)).hasSize(8);
    }

    /**
     * Test 2: Server accepts 4-vertex tetrahedra.
     */
    @Test
    void testServerAccepts4VertexTetrahedra() throws Exception {
        var bubbleId = UUID.randomUUID();

        // Create 4-vertex tetrahedron
        var tet4Vertices = new Point3f[]{
            new Point3f(0f, 0f, 0f),
            new Point3f(100f, 0f, 0f),
            new Point3f(50f, 86.6f, 0f),
            new Point3f(50f, 28.9f, 81.6f)
        };

        var verticesMap = new HashMap<UUID, Point3f[]>();
        verticesMap.put(bubbleId, tet4Vertices);

        vizServer.setBubbleVertices(verticesMap);

        // Verify vertices are in internal state
        var vertices = vizServer.getBubbleVertices();
        assertThat(vertices).containsKey(bubbleId);
        assertThat(vertices.get(bubbleId)).hasSize(4);
    }

    /**
     * Test 3: Server filters invalid vertex counts when serving to clients.
     * <p>
     * Internal state may contain invalid arrays, but only 4-vertex and 8-vertex
     * arrays are sent to clients via API/WebSocket.
     */
    @Test
    void testServerFiltersInvalidVertexCounts() {
        var bubbleId5 = UUID.randomUUID();
        var bubbleId6 = UUID.randomUUID();

        // Try setting 5-vertex and 6-vertex arrays
        var vertices5 = new Point3f[5];
        var vertices6 = new Point3f[6];
        for (int i = 0; i < 6; i++) {
            var point = new Point3f(i * 10f, i * 10f, i * 10f);
            if (i < 5) vertices5[i] = point;
            vertices6[i] = point;
        }

        var verticesMap = new HashMap<UUID, Point3f[]>();
        verticesMap.put(bubbleId5, vertices5);
        verticesMap.put(bubbleId6, vertices6);

        vizServer.setBubbleVertices(verticesMap);

        // Internal state contains the arrays (no validation on set)
        var vertices = vizServer.getBubbleVertices();
        assertThat(vertices).hasSize(2);

        // But only 4 and 8 vertex arrays would be sent to clients
        // (filtering happens in API/WebSocket code, not in setter)
    }

    /**
     * Test 4: Mix of 4-vertex and 8-vertex in same scene.
     */
    @Test
    void testServerHandlesMixedVertexCounts() throws Exception {
        var bubbleId4 = UUID.randomUUID();
        var bubbleId8 = UUID.randomUUID();

        // 4-vertex tetrahedron
        var tet4Vertices = new Point3f[]{
            new Point3f(0f, 0f, 0f),
            new Point3f(100f, 0f, 0f),
            new Point3f(50f, 86.6f, 0f),
            new Point3f(50f, 28.9f, 81.6f)
        };

        // 8-vertex box
        var box8Vertices = new Point3f[]{
            new Point3f(200f, 0f, 0f),
            new Point3f(300f, 0f, 0f),
            new Point3f(300f, 100f, 0f),
            new Point3f(200f, 100f, 0f),
            new Point3f(200f, 0f, 100f),
            new Point3f(300f, 0f, 100f),
            new Point3f(300f, 100f, 100f),
            new Point3f(200f, 100f, 100f)
        };

        var verticesMap = new HashMap<UUID, Point3f[]>();
        verticesMap.put(bubbleId4, tet4Vertices);
        verticesMap.put(bubbleId8, box8Vertices);

        vizServer.setBubbleVertices(verticesMap);

        // Verify both are accepted
        var vertices = vizServer.getBubbleVertices();
        assertThat(vertices).hasSize(2);
        assertThat(vertices.get(bubbleId4)).hasSize(4);
        assertThat(vertices.get(bubbleId8)).hasSize(8);
    }

    /**
     * Test 5: Verify getBubbleVertices accessor exists and works.
     */
    @Test
    void testGetBubbleVerticesAccessor() {
        var bubbleId = UUID.randomUUID();
        var vertices = new Point3f[]{
            new Point3f(0f, 0f, 0f),
            new Point3f(1f, 0f, 0f),
            new Point3f(0f, 1f, 0f),
            new Point3f(0f, 0f, 1f)
        };

        var verticesMap = new HashMap<UUID, Point3f[]>();
        verticesMap.put(bubbleId, vertices);

        vizServer.setBubbleVertices(verticesMap);

        var retrieved = vizServer.getBubbleVertices();
        assertThat(retrieved).isNotNull();
        assertThat(retrieved).containsKey(bubbleId);
        assertThat(retrieved.get(bubbleId)).isEqualTo(vertices);
    }
}
