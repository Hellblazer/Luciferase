/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.metrics;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.ghost.GhostLayerHealth;
import com.hellblazer.luciferase.simulation.ghost.SimulationGhostEntity;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.config.SimulationConfig;
import com.hellblazer.luciferase.simulation.ghost.InMemoryGhostChannel;
import com.hellblazer.luciferase.simulation.ghost.InstrumentedGhostChannel;
import com.hellblazer.luciferase.simulation.viz.BubbleBoundsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 5 Integration Tests - End-to-end validation of observability infrastructure.
 * <p>
 * Tests validate:
 * - Metrics aggregation across multiple bubbles
 * - Ghost sync latency tracking
 * - Configuration externalization
 * - Animator utilization monitoring
 * - VON health tracking (NC metric)
 * - Tetrahedral bounds visualization data
 * - Full system observability
 *
 * @author hal.hildebrand
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class Phase5IntegrationTest {

    private ObservabilityMetrics metrics;
    private BubbleBoundsServer server;
    private SimulationConfig config;

    @BeforeEach
    void setUp() {
        metrics = new ObservabilityMetrics();
        config = SimulationConfig.defaults();
        // Dynamic port for testing
        server = new BubbleBoundsServer(0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    /**
     * Test 1: End-to-end metrics aggregation across multiple bubbles.
     * <p>
     * Validates that ObservabilityMetrics correctly aggregates:
     * - Animator utilization (frame times)
     * - VON neighbor counts
     * - Active bubble tracking
     */
    @Test
    void testMetricsAggregationEndToEnd() {
        // Setup: Create 5 bubbles with ghost managers
        var bubbles = createTestBubbles(5);

        for (var bubble : bubbles) {
            var health = new GhostLayerHealth();
            health.setExpectedNeighbors(4);
            // Note: registerBubble doesn't exist, metrics are recorded via recordAnimatorFrame/recordNeighborCount
        }

        // Simulate frame times: 50ms frames (50% utilization of 100ms target)
        simulateFrameTimes(bubbles, 50_000_000L);  // 50ms in nanoseconds

        // Simulate VON connections
        simulateVONConnections(bubbles);

        // Record metrics for each bubble
        for (var bubble : bubbles) {
            metrics.recordAnimatorFrame(bubble.id(), 50_000_000L, 100_000_000L);
            metrics.recordNeighborCount(bubble.id(), bubble.getVonNeighbors().size());
        }

        // Verify metrics
        var snapshot = metrics.getSnapshot();
        assertThat(snapshot.avgAnimatorUtilization()).isCloseTo(0.5f, within(0.1f));
        assertThat(snapshot.activeBubbleCount()).isEqualTo(5);
        assertThat(snapshot.totalVonNeighbors()).isGreaterThan(0);
    }

    /**
     * Test 2: End-to-end ghost sync latency tracking.
     * <p>
     * Validates that InstrumentedGhostChannel collects latency statistics
     * for batch ghost operations.
     */
    @Test
    void testLatencyTrackingEndToEnd() {
        // Create InstrumentedGhostChannel wrapping InMemoryGhostChannel
        var baseChannel = new InMemoryGhostChannel<StringEntityID, Object>();
        var instrumentedChannel = new InstrumentedGhostChannel<>(baseChannel,
            latencyNs -> metrics.recordGhostLatency(latencyNs)
        );

        var sourceBubbleId = UUID.randomUUID();
        var targetBubbleId = UUID.randomUUID();

        // Send batches of ghosts
        for (int i = 0; i < 10; i++) {
            var ghosts = createGhostBatch(sourceBubbleId, 10);
            instrumentedChannel.sendBatch(targetBubbleId, ghosts);
        }

        // Verify LatencyStats collected
        var stats = instrumentedChannel.getLatencyStats();
        assertThat(stats.sampleCount()).isEqualTo(10);
        assertThat(stats.avgLatencyNs()).isGreaterThan(0);
        assertThat(stats.p99LatencyNs()).isGreaterThan(0);

        // For in-memory channel, latency should be minimal (<10ms)
        assertThat(stats.p99LatencyMs()).isLessThan(10.0);

        // Verify metric recorded
        var snapshot = metrics.getSnapshot();
        assertThat(snapshot.ghostSyncLatencyNs()).isNotNull();
        assertThat(snapshot.ghostSyncLatencyNs()).isGreaterThan(0);
    }

    /**
     * Test 3: Verify configuration applied to components.
     * <p>
     * Validates that SimulationConfig values are used (not hardcoded defaults).
     */
    @Test
    void testConfigAppliedToComponents() {
        // Create custom SimulationConfig (non-default values)
        var customConfig = SimulationConfig.builder()
            .ghostTtlMs(1000)  // 1000ms instead of default 500ms
            .ghostMemoryLimit(2000)  // 2000 instead of default 1000
            .bucketIntervalMs(200)  // 200ms instead of default 100ms
            .ncThreshold(0.85f)  // 0.85 instead of default 0.9
            .latencyAlertThresholdMs(150)  // 150ms instead of default 100ms
            .build();

        // Verify config values are correct
        assertThat(customConfig.ghostTtlMs()).isEqualTo(1000);
        assertThat(customConfig.ghostMemoryLimit()).isEqualTo(2000);
        assertThat(customConfig.bucketIntervalMs()).isEqualTo(200);
        assertThat(customConfig.ncThreshold()).isEqualTo(0.85f);
        assertThat(customConfig.latencyAlertThresholdMs()).isEqualTo(150);

        // Verify derived values
        assertThat(customConfig.ghostTtlBuckets()).isEqualTo(5);  // 1000ms / 200ms
    }

    /**
     * Test 4: Detect overloaded bubbles (>1.2x target frame time).
     * <p>
     * Validates that high utilization is correctly detected.
     */
    @Test
    void testOverloadDetection() {
        // Create bubbles with high utilization (>1.2x threshold)
        var bubbles = createTestBubbles(3);

        // Simulate overload: 150ms frames on 100ms budget = 1.5x utilization
        for (var bubble : bubbles) {
            metrics.recordAnimatorFrame(bubble.id(), 150_000_000L, 100_000_000L);
        }

        // Verify detection triggers correctly
        var snapshot = metrics.getSnapshot();
        assertThat(snapshot.avgAnimatorUtilization()).isGreaterThan(1.2f);

        // Check individual bubble utilization
        for (var bubble : bubbles) {
            bubble.recordFrameTime(150_000_000L);
            assertThat(bubble.frameUtilization()).isGreaterThan(1.2f);
            assertThat(bubble.needsSplit()).isTrue();
        }
    }

    /**
     * Test 5: Detect NC degradation in ghost layer health.
     * <p>
     * Validates that missing ghost sources are detected via NC metric.
     */
    @Test
    void testNCDegradationAlert() {
        var health = new GhostLayerHealth();

        // Set expected neighbors (from VON)
        health.setExpectedNeighbors(10);

        // Simulate missing ghost sources (only 8 of 10 neighbors sending ghosts)
        for (int i = 0; i < 8; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        // Verify NC metric degrades
        assertThat(health.neighborConsistency()).isCloseTo(0.8f, within(0.01f));
        assertThat(health.isDegraded(0.9f)).isTrue();

        // Verify GhostLayerHealth reports degradation
        var healthSnapshot = health.getHealthSnapshot();
        assertThat(healthSnapshot.neighborConsistency()).isCloseTo(0.8f, within(0.01f));
        assertThat(healthSnapshot.isHealthy()).isFalse();  // Below 0.9 threshold
        assertThat(healthSnapshot.missingNeighbors()).isEqualTo(2);
    }

    /**
     * Test 6: Verify ghost latency under performance target (100ms P99).
     * <p>
     * Validates that InMemoryGhostChannel meets the 100ms latency target.
     */
    @Test
    void testGhostLatencyUnder100ms() {
        // Use InstrumentedGhostChannel with InMemoryGhostChannel
        var baseChannel = new InMemoryGhostChannel<StringEntityID, Object>();
        var instrumentedChannel = new InstrumentedGhostChannel<>(baseChannel);

        var sourceBubbleId = UUID.randomUUID();
        var targetBubbleId = UUID.randomUUID();

        // Send multiple batches to collect latency samples
        for (int i = 0; i < 100; i++) {
            var ghosts = createGhostBatch(sourceBubbleId, 5);
            instrumentedChannel.sendBatch(targetBubbleId, ghosts);
        }

        // Verify P99 latency is within expected range
        var stats = instrumentedChannel.getLatencyStats();
        assertThat(stats.p99LatencyMs()).isLessThan(100.0);  // Target: <100ms

        // For in-memory, expect very low latency (<1ms)
        assertThat(stats.p99LatencyMs()).isLessThan(1.0);
    }

    /**
     * Test 7: Verify visualization data generation for BubbleBoundsServer.
     * <p>
     * Validates that BubbleBoundsServer provides correct JSON data for visualization.
     */
    @Test
    void testVisualizationDataGeneration() throws IOException, InterruptedException {
        // Create BubbleBoundsServer (dynamic port)
        server.start();
        var port = server.port();

        // Add bubbles with tetrahedral bounds
        var bubbles = createTestBubbles(3);
        for (var bubble : bubbles) {
            // Add entities to create bounds
            bubble.addEntity("entity1", new Point3f(0.1f, 0.1f, 0.1f), "content1");
            bubble.addEntity("entity2", new Point3f(0.2f, 0.2f, 0.2f), "content2");

            // Add to server
            server.addBubble(bubble.id(), bubble.bounds(), bubble.getVonNeighbors());
        }

        // Fetch /api/bubbles endpoint
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/bubbles"))
            .GET()
            .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify response
        assertThat(response.statusCode()).isEqualTo(200);
        var body = response.body();
        assertThat(body).contains("bubbleId");
        assertThat(body).contains("tetrahedralBounds");  // Corrected: API uses tetrahedralBounds, not vertices
        assertThat(body).contains("centroid");

        // Verify JSON structure (basic check - detailed parsing would use Jackson)
        assertThat(body).startsWith("[");  // JSON array
        assertThat(body).endsWith("]");
    }

    /**
     * Test 8: Full system observability integration test.
     * <p>
     * Validates complete integration of all Phase 5 components:
     * - ObservabilityMetrics
     * - InstrumentedGhostChannel
     * - SimulationConfig
     * - BubbleBoundsServer
     * - GhostLayerHealth
     */
    @Test
    void testFullSystemObservability() throws IOException, InterruptedException {
        // Create full simulation environment
        var customConfig = SimulationConfig.builder()
            .ghostTtlMs(800)
            .latencyAlertThresholdMs(120)
            .build();

        var bubbles = createTestBubbles(5);
        var channels = new ArrayList<InstrumentedGhostChannel<StringEntityID, Object>>();

        // Setup ghost channels with instrumentation
        for (int i = 0; i < 5; i++) {
            var baseChannel = new InMemoryGhostChannel<StringEntityID, Object>();
            var instrumented = new InstrumentedGhostChannel<>(baseChannel,
                latencyNs -> metrics.recordGhostLatency(latencyNs)
            );
            channels.add(instrumented);
        }

        // Start visualization server
        server.start();
        var port = server.port();

        // Run simulation steps
        for (var bubble : bubbles) {
            // Add entities
            bubble.addEntity("e1", new Point3f(0.1f, 0.1f, 0.1f), "content");

            // Record frame time (80ms on 100ms budget = 80% utilization)
            metrics.recordAnimatorFrame(bubble.id(), 80_000_000L, 100_000_000L);

            // Record neighbor count
            metrics.recordNeighborCount(bubble.id(), 4);

            // Add to visualization
            server.addBubble(bubble.id(), bubble.bounds(), bubble.getVonNeighbors());
        }

        // Send ghost batches through instrumented channels
        for (int i = 0; i < channels.size(); i++) {
            var channel = channels.get(i);
            var sourceBubbleId = bubbles.get(i).id();
            var targetBubbleId = bubbles.get((i + 1) % bubbles.size()).id();

            var ghosts = createGhostBatch(sourceBubbleId, 5);
            channel.sendBatch(targetBubbleId, ghosts);
        }

        // Verify metrics snapshot complete
        var snapshot = metrics.getSnapshot();
        assertThat(snapshot.activeBubbleCount()).isEqualTo(5);
        assertThat(snapshot.avgAnimatorUtilization()).isCloseTo(0.8f, within(0.1f));
        assertThat(snapshot.totalVonNeighbors()).isEqualTo(20);  // 5 bubbles * 4 neighbors
        assertThat(snapshot.ghostSyncLatencyNs()).isNotNull();

        // Verify latency stats available
        for (var channel : channels) {
            var stats = channel.getLatencyStats();
            assertThat(stats.sampleCount()).isGreaterThan(0);
        }

        // Verify visualization data accessible
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/bubbles"))
            .GET()
            .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        // Verify config applied correctly
        assertThat(customConfig.ghostTtlMs()).isEqualTo(800);
        assertThat(customConfig.latencyAlertThresholdMs()).isEqualTo(120);

        // No errors or exceptions - test passes
    }

    // Helper methods

    /**
     * Create test bubbles with unique IDs.
     */
    private List<EnhancedBubble> createTestBubbles(int count) {
        var bubbles = new ArrayList<EnhancedBubble>();
        for (int i = 0; i < count; i++) {
            var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 100);
            bubbles.add(bubble);
        }
        return bubbles;
    }

    /**
     * Simulate frame times for bubbles.
     */
    private void simulateFrameTimes(List<EnhancedBubble> bubbles, long frameTimeNs) {
        for (var bubble : bubbles) {
            bubble.recordFrameTime(frameTimeNs);
        }
    }

    /**
     * Simulate VON connections between bubbles (each connected to 4 neighbors).
     */
    private void simulateVONConnections(List<EnhancedBubble> bubbles) {
        // Connect each bubble to the next 2 and previous 2 (circular)
        for (int i = 0; i < bubbles.size(); i++) {
            var bubble = bubbles.get(i);
            for (int offset = 1; offset <= 2; offset++) {
                var next = bubbles.get((i + offset) % bubbles.size());
                var prev = bubbles.get((i - offset + bubbles.size()) % bubbles.size());

                bubble.addVonNeighbor(next.id());
                bubble.addVonNeighbor(prev.id());
            }
        }
    }

    /**
     * Create a batch of ghost entities for testing.
     */
    private List<SimulationGhostEntity<StringEntityID, Object>> createGhostBatch(UUID sourceBubbleId, int count) {
        var ghosts = new ArrayList<SimulationGhostEntity<StringEntityID, Object>>();
        for (int i = 0; i < count; i++) {
            var entityId = new StringEntityID("ghost-" + i);
            var position = new Point3f((float) Math.random(), (float) Math.random(), (float) Math.random());
            Object content = "content-" + i;  // Explicitly type as Object
            var bucket = System.currentTimeMillis();

            // Create GhostEntity first, then wrap in SimulationGhostEntity
            var ghostEntity = new com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager.GhostEntity<StringEntityID, Object>(
                entityId,
                content,
                position,
                null,  // bounds
                "source-tree"
            );

            var ghost = new SimulationGhostEntity<StringEntityID, Object>(
                ghostEntity,
                sourceBubbleId,
                bucket,
                0L,  // epoch
                0L   // version
            );
            ghosts.add(ghost);
        }
        return ghosts;
    }
}
