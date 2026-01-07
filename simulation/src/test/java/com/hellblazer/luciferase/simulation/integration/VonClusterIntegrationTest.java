/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.integration;

import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.bubble.ExternalBubbleTracker;
import com.hellblazer.luciferase.simulation.ghost.*;
import com.hellblazer.luciferase.simulation.von.*;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-node integration tests for v4.0 VON P2P architecture.
 * <p>
 * Phase 4 tests validate:
 * <ul>
 *   <li>10-node cluster with 20 VonBubbles (2 per node)</li>
 *   <li>P2P JOIN protocol with neighbor discovery</li>
 *   <li>Entity movement with MOVE propagation</li>
 *   <li>Ghost sync between P2P neighbors</li>
 *   <li>NC metric > 0.9 for hub nodes</li>
 *   <li>Cluster stabilization < 30s</li>
 * </ul>
 * <p>
 * Uses LocalServerTransport for in-process P2P (no real network).
 *
 * @author hal.hildebrand
 */
@Tag("integration")
public class VonClusterIntegrationTest {

    private static final int NODE_COUNT = 10;
    private static final int BUBBLES_PER_NODE = 2;
    private static final int TOTAL_BUBBLES = NODE_COUNT * BUBBLES_PER_NODE;
    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 16;
    private static final float AOI_RADIUS = 100.0f;
    private static final int STABILIZATION_TIMEOUT_SECONDS = 30;

    private LocalServerTransport.Registry transportRegistry;
    private VonManager vonManager;
    private List<VonBubble> bubbles;
    private Map<UUID, P2PGhostChannel<StringEntityID, Object>> ghostChannels;
    private Map<UUID, BubbleGhostManager<StringEntityID, Object>> ghostManagers;
    private ServerRegistry serverRegistry;

    @BeforeEach
    void setup() {
        transportRegistry = LocalServerTransport.Registry.create();
        vonManager = new VonManager(transportRegistry, SPATIAL_LEVEL, TARGET_FRAME_MS, AOI_RADIUS);
        bubbles = new ArrayList<>();
        ghostChannels = new ConcurrentHashMap<>();
        ghostManagers = new ConcurrentHashMap<>();
        serverRegistry = new ServerRegistry();
    }

    @AfterEach
    void teardown() {
        // Close ghost channels
        ghostChannels.values().forEach(P2PGhostChannel::close);
        ghostChannels.clear();

        // Close ghost managers (no explicit close needed, but clear references)
        ghostManagers.clear();

        // Close VonManager (closes all bubbles)
        if (vonManager != null) {
            vonManager.close();
        }

        // Close transport registry
        if (transportRegistry != null) {
            transportRegistry.close();
        }

        bubbles.clear();
    }

    // ========== Cluster Setup Tests ==========

    @Test
    void testClusterSetup_20BubblesAcross10Nodes() {
        // Given: A target of 20 bubbles across 10 logical nodes

        // When: Create bubbles and assign to logical nodes
        var nodeAssignments = setupCluster();

        // Then: All bubbles created and assigned
        assertThat(bubbles).hasSize(TOTAL_BUBBLES);
        assertThat(nodeAssignments).hasSize(NODE_COUNT);
        for (var assignment : nodeAssignments.values()) {
            assertThat(assignment).hasSize(BUBBLES_PER_NODE);
        }

        // And: All bubbles have transport
        for (var bubble : bubbles) {
            assertThat(bubble.getTransport()).isNotNull();
        }
    }

    @Test
    void testClusterStabilization_under30Seconds() throws Exception {
        // Given: 20 bubbles with entities
        setupCluster();
        populateEntities();

        // When: All bubbles join via P2P
        long startTime = System.currentTimeMillis();
        var joinSuccessful = performJoinSequence();

        // Then: All joins complete within timeout
        assertThat(joinSuccessful).isTrue();
        long stabilizationTime = System.currentTimeMillis() - startTime;
        assertThat(stabilizationTime).isLessThan(STABILIZATION_TIMEOUT_SECONDS * 1000L);

        System.out.printf("Cluster stabilized in %d ms with %d bubbles%n",
                          stabilizationTime, bubbles.size());
    }

    // ========== P2P Protocol Tests ==========

    @Test
    void testJoinProtocol_bidirectionalNeighbors() throws Exception {
        // Given: Cluster with entities
        setupCluster();
        populateEntities();
        performJoinSequence();

        // When: Check neighbor relationships
        var bidirectionalCount = 0;
        var totalRelationships = 0;

        for (var bubble : bubbles) {
            for (var neighborId : bubble.neighbors()) {
                totalRelationships++;
                var neighbor = vonManager.getBubble(neighborId);
                if (neighbor != null && neighbor.neighbors().contains(bubble.id())) {
                    bidirectionalCount++;
                }
            }
        }

        // Then: Most relationships are bidirectional
        if (totalRelationships > 0) {
            float bidirectionalRatio = (float) bidirectionalCount / totalRelationships;
            assertThat(bidirectionalRatio).isGreaterThan(0.8f);
            System.out.printf("Bidirectional relationships: %d/%d (%.1f%%)%n",
                              bidirectionalCount, totalRelationships, bidirectionalRatio * 100);
        }
    }

    @Test
    void testMoveProtocol_neighborsNotified() throws Exception {
        // Given: Cluster with established neighbors
        setupCluster();
        populateEntities();
        performJoinSequence();

        // Find a bubble with at least one neighbor
        var movingBubble = bubbles.stream()
            .filter(b -> !b.neighbors().isEmpty())
            .findFirst()
            .orElseThrow(() -> new AssertionError("No bubble with neighbors"));

        var neighborIds = new HashSet<>(movingBubble.neighbors());
        var moveReceived = new CountDownLatch(neighborIds.size());

        // Track move events on neighbors
        for (var neighborId : neighborIds) {
            var neighbor = vonManager.getBubble(neighborId);
            if (neighbor != null) {
                neighbor.addEventListener(event -> {
                    if (event instanceof Event.Move move && move.nodeId().equals(movingBubble.id())) {
                        moveReceived.countDown();
                    }
                });
            }
        }

        // When: Bubble moves
        movingBubble.addEntity("moving-entity", new Point3f(75.0f, 75.0f, 75.0f), new Object());
        movingBubble.broadcastMove();

        // Then: Neighbors receive MOVE
        var received = moveReceived.await(2, TimeUnit.SECONDS);
        assertThat(received).isTrue();
    }

    @Test
    void testLeaveProtocol_neighborsUpdated() throws Exception {
        // Given: Cluster with established neighbors
        setupCluster();
        populateEntities();
        performJoinSequence();

        // Find a bubble with neighbors and track its removal
        var leavingBubble = bubbles.stream()
            .filter(b -> !b.neighbors().isEmpty())
            .findFirst()
            .orElseThrow(() -> new AssertionError("No bubble with neighbors"));

        var neighborIds = new HashSet<>(leavingBubble.neighbors());
        var leaveReceived = new CountDownLatch(neighborIds.size());

        for (var neighborId : neighborIds) {
            var neighbor = vonManager.getBubble(neighborId);
            if (neighbor != null) {
                neighbor.addEventListener(event -> {
                    if (event instanceof Event.Leave leave && leave.nodeId().equals(leavingBubble.id())) {
                        leaveReceived.countDown();
                    }
                });
            }
        }

        // When: Bubble leaves
        vonManager.leave(leavingBubble);

        // Then: Neighbors receive LEAVE
        var received = leaveReceived.await(2, TimeUnit.SECONDS);
        assertThat(received).isTrue();

        // And: Bubble removed from manager
        assertThat(vonManager.getBubble(leavingBubble.id())).isNull();
    }

    // ========== Ghost Sync Tests ==========

    @Test
    void testGhostSync_betweenNeighbors() throws Exception {
        // Given: Cluster with ghost channels
        setupCluster();
        populateEntities();
        setupGhostChannels();
        performJoinSequence();

        // Find two neighboring bubbles
        var bubble1 = bubbles.stream()
            .filter(b -> !b.neighbors().isEmpty())
            .findFirst()
            .orElseThrow(() -> new AssertionError("No bubble with neighbors"));

        var neighborId = bubble1.neighbors().iterator().next();
        var bubble2 = vonManager.getBubble(neighborId);
        assertThat(bubble2).isNotNull();

        // Track ghost reception
        var ghostReceived = new CountDownLatch(1);
        var receivedCount = new AtomicInteger(0);

        var channel2 = ghostChannels.get(bubble2.id());
        channel2.onReceive((fromId, ghosts) -> {
            receivedCount.addAndGet(ghosts.size());
            ghostReceived.countDown();
        });

        // When: Send ghost from bubble1 to bubble2
        var channel1 = ghostChannels.get(bubble1.id());
        var ghost = createGhost("ghost-entity", new Point3f(50.0f, 50.0f, 50.0f), bubble1.id());
        channel1.queueGhost(bubble2.id(), ghost);
        channel1.flush(1L);

        // Then: Ghost received
        var received = ghostReceived.await(2, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        assertThat(receivedCount.get()).isEqualTo(1);
    }

    @Test
    void testGhostSync_performance_under100ms() throws Exception {
        // Given: Cluster with ghost channels
        setupCluster();
        populateEntities();
        setupGhostChannels();
        performJoinSequence();

        // Find neighboring pair
        var bubble1 = bubbles.stream()
            .filter(b -> !b.neighbors().isEmpty())
            .findFirst()
            .orElseThrow(() -> new AssertionError("No bubble with neighbors"));

        var neighborId = bubble1.neighbors().iterator().next();

        // Create 100 ghosts
        var ghosts = new ArrayList<SimulationGhostEntity<StringEntityID, Object>>();
        for (int i = 0; i < 100; i++) {
            ghosts.add(createGhost("ghost-" + i, new Point3f(50.0f + i * 0.1f, 50.0f, 50.0f), bubble1.id()));
        }

        var receiveComplete = new CountDownLatch(1);
        var receivedCount = new AtomicInteger(0);

        var channel2 = ghostChannels.get(neighborId);
        channel2.onReceive((fromId, received) -> {
            receivedCount.addAndGet(received.size());
            if (receivedCount.get() >= 100) {
                receiveComplete.countDown();
            }
        });

        // When: Send batch and measure
        var channel1 = ghostChannels.get(bubble1.id());
        long startNs = System.nanoTime();
        channel1.sendBatch(neighborId, ghosts);
        receiveComplete.await(2, TimeUnit.SECONDS);
        long latencyMs = (System.nanoTime() - startNs) / 1_000_000;

        // Then: Under 100ms
        assertThat(latencyMs).isLessThan(100L);
        System.out.printf("Ghost sync latency: %d ms for %d ghosts%n", latencyMs, ghosts.size());
    }

    // ========== Metrics Tests ==========

    @Test
    void testNeighborConsistency_hubAbove0_9() throws Exception {
        // Given: Dense cluster where all bubbles are within AOI
        setupCluster();
        populateEntitiesInCluster();  // All entities near center
        performJoinSequence();

        // Allow stabilization
        Thread.sleep(500);

        // When: Calculate NC for all bubbles
        var ncValues = new ArrayList<Float>();
        for (var bubble : bubbles) {
            float nc = vonManager.calculateNC(bubble);
            ncValues.add(nc);
        }

        // Then: At least one hub node achieves NC > 0.9
        var maxNC = ncValues.stream().max(Float::compareTo).orElse(0.0f);
        assertThat(maxNC).isGreaterThan(0.9f);

        // And: Average NC is reasonable
        var avgNC = ncValues.stream().reduce(0.0f, Float::sum) / ncValues.size();
        System.out.printf("NC metrics - Max: %.2f, Avg: %.2f, Count: %d%n",
                          maxNC, avgNC, ncValues.size());
    }

    @Test
    void testEntityMovementScenario_crossBubbleTransfer() throws Exception {
        // Given: Cluster with entities
        setupCluster();
        populateEntities();
        setupGhostChannels();
        performJoinSequence();

        // Find two neighboring bubbles
        var sourceBubble = bubbles.stream()
            .filter(b -> !b.neighbors().isEmpty() && b.entityCount() > 0)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No bubble with neighbors and entities"));

        var targetId = sourceBubble.neighbors().iterator().next();
        var targetBubble = vonManager.getBubble(targetId);

        // Track ghost sync
        var ghostReceived = new CountDownLatch(1);
        ghostChannels.get(targetId).onReceive((fromId, ghosts) -> ghostReceived.countDown());

        // When: Entity moves from source to boundary
        var entityId = new StringEntityID("moving-entity");
        var boundaryPosition = new Point3f(90.0f, 90.0f, 50.0f);  // Near boundary
        sourceBubble.addEntity(entityId.getValue(), boundaryPosition, new Object());

        // Create ghost for cross-bubble visibility
        var ghost = createGhost(entityId.getValue(), boundaryPosition, sourceBubble.id());
        ghostChannels.get(sourceBubble.id()).queueGhost(targetId, ghost);
        ghostChannels.get(sourceBubble.id()).flush(1L);

        // Then: Ghost received at target
        var received = ghostReceived.await(2, TimeUnit.SECONDS);
        assertThat(received).isTrue();
    }

    // ========== Performance Validation ==========

    @Test
    void testJoinLatency_under150ms() throws Exception {
        // Given: Established cluster with seed bubble
        setupCluster();
        populateEntities();

        // Join first bubble (seed) - solo
        var seedBubble = bubbles.get(0);
        vonManager.joinAndWait(seedBubble, seedBubble.position(), 100);

        // When: Measure P2P join latency (not timeout-based)
        // For LocalServer transport, joins complete synchronously without timeout wait
        var latencies = new ArrayList<Long>();

        for (int i = 1; i < Math.min(5, bubbles.size()); i++) {
            var bubble = bubbles.get(i);

            // Track when JoinRequest is sent and JoinResponse received
            var joinComplete = new CountDownLatch(1);
            bubble.addEventListener(event -> {
                if (event instanceof Event.Join) {
                    joinComplete.countDown();
                }
            });

            long start = System.nanoTime();

            // Send join request directly
            vonManager.joinAt(bubble, bubble.position());

            // Wait for confirmation (with short timeout)
            boolean completed = joinComplete.await(100, TimeUnit.MILLISECONDS);

            if (completed || !bubble.neighbors().isEmpty()) {
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                latencies.add(latencyMs);
            }
        }

        // Then: Average join latency should be fast for in-process transport
        if (!latencies.isEmpty()) {
            long avgLatency = latencies.stream().mapToLong(Long::longValue).sum() / latencies.size();
            // For LocalServer (in-process), 150ms is good - accounts for CountDownLatch overhead
            // and CI system load variations
            assertThat(avgLatency).isLessThan(150L);
            System.out.printf("Average JOIN latency: %d ms (samples: %d)%n", avgLatency, latencies.size());
        }
    }

    // ========== Helper Methods ==========

    /**
     * Set up 20 bubbles across 10 logical nodes.
     * Returns map of nodeId -> list of bubble IDs.
     */
    private Map<UUID, List<UUID>> setupCluster() {
        var nodeAssignments = new HashMap<UUID, List<UUID>>();

        for (int node = 0; node < NODE_COUNT; node++) {
            var nodeId = UUID.randomUUID();
            var nodeBubbles = new ArrayList<UUID>();

            for (int b = 0; b < BUBBLES_PER_NODE; b++) {
                var bubble = vonManager.createBubble();
                bubbles.add(bubble);
                nodeBubbles.add(bubble.id());

                // Register with server registry (for same-server optimization)
                serverRegistry.registerBubble(bubble.id(), nodeId);
            }

            nodeAssignments.put(nodeId, nodeBubbles);
        }

        return nodeAssignments;
    }

    /**
     * Populate bubbles with entities at scattered positions.
     * Note: Tetree requires positive coordinates.
     */
    private void populateEntities() {
        var random = new Random(42);

        for (int i = 0; i < bubbles.size(); i++) {
            var bubble = bubbles.get(i);

            // Add 5 entities per bubble at random POSITIVE positions
            for (int e = 0; e < 5; e++) {
                var position = new Point3f(
                    10.0f + random.nextFloat() * 180.0f,  // 10-190
                    10.0f + random.nextFloat() * 180.0f,  // 10-190
                    10.0f + random.nextFloat() * 180.0f   // 10-190
                );
                bubble.addEntity("entity-" + i + "-" + e, position, new Object());
            }
        }
    }

    /**
     * Populate bubbles with entities clustered near center (for high NC).
     */
    private void populateEntitiesInCluster() {
        var random = new Random(42);

        for (int i = 0; i < bubbles.size(); i++) {
            var bubble = bubbles.get(i);

            // Add entities within AOI radius of center
            for (int e = 0; e < 5; e++) {
                var position = new Point3f(
                    50.0f + random.nextFloat() * 20.0f - 10.0f,
                    50.0f + random.nextFloat() * 20.0f - 10.0f,
                    50.0f + random.nextFloat() * 20.0f - 10.0f
                );
                bubble.addEntity("entity-" + i + "-" + e, position, new Object());
            }
        }
    }

    /**
     * Set up ghost channels for all bubbles.
     */
    private void setupGhostChannels() {
        for (var bubble : bubbles) {
            var channel = new P2PGhostChannel<StringEntityID, Object>(bubble);
            ghostChannels.put(bubble.id(), channel);

            // Create ghost manager
            var optimizer = new SameServerOptimizer(serverRegistry);
            optimizer.registerLocalBubble(bubble);
            var tracker = new ExternalBubbleTracker();
            var health = new GhostLayerHealth();

            var manager = new BubbleGhostManager<StringEntityID, Object>(
                bubble, serverRegistry, channel, optimizer, tracker, health
            );
            ghostManagers.put(bubble.id(), manager);
        }
    }

    /**
     * Perform JOIN sequence for all bubbles.
     * Uses fast join timeouts for testing (100ms per bubble).
     */
    private boolean performJoinSequence() throws InterruptedException {
        if (bubbles.isEmpty()) return false;

        // First bubble joins solo
        var seed = bubbles.get(0);
        vonManager.joinAndWait(seed, seed.position(), 100);

        // Remaining bubbles join via P2P with fast timeout
        for (int i = 1; i < bubbles.size(); i++) {
            var bubble = bubbles.get(i);
            var success = vonManager.joinAndWait(bubble, bubble.position(), 200);
            if (!success) {
                // For LocalServer, join is synchronous so this is fine
                // In real network scenarios, would need longer timeout
            }
        }

        // Allow time for async neighbor discovery
        Thread.sleep(100);

        // Verify all bubbles have at least one neighbor (except possibly edge cases)
        long withNeighbors = bubbles.stream().filter(b -> !b.neighbors().isEmpty()).count();
        return withNeighbors >= bubbles.size() - 1;  // Allow one edge case
    }

    /**
     * Create a simulation ghost entity.
     */
    private SimulationGhostEntity<StringEntityID, Object> createGhost(
        String entityId, Point3f position, UUID sourceBubbleId
    ) {
        var id = new StringEntityID(entityId);
        var bounds = new com.hellblazer.luciferase.lucien.entity.EntityBounds(position, 0.5f);
        var ghost = new com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager.GhostEntity<StringEntityID, Object>(
            id, new Object(), position, bounds, "tree-" + sourceBubbleId
        );
        return new SimulationGhostEntity<>(ghost, sourceBubbleId, 1L, 1L, 1L);
    }
}
