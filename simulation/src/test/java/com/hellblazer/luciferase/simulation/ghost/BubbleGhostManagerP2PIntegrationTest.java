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

package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.bubble.ExternalBubbleTracker;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import com.hellblazer.luciferase.simulation.von.Bubble;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BubbleGhostManager with P2PGhostChannel.
 * <p>
 * These tests validate the complete v4.0 ghost synchronization pipeline:
 * - P2P ghost transmission via Transport
 * - Same-server optimization bypass
 * - Batched ghost sync at bucket boundaries
 * - ExternalBubbleTracker integration for VON discovery
 * - GhostLayerHealth NC metric tracking
 *
 * @author hal.hildebrand
 */
class BubbleGhostManagerP2PIntegrationTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 10;

    private LocalServerTransport.Registry registry;
    private Bubble bubble1;
    private Bubble bubble2;
    private P2PGhostChannel<StringEntityID, Object> channel1;
    private P2PGhostChannel<StringEntityID, Object> channel2;
    private BubbleGhostManager<StringEntityID, Object> manager1;
    private BubbleGhostManager<StringEntityID, Object> manager2;
    private ServerRegistry serverRegistry;
    private SameServerOptimizer optimizer1;
    private SameServerOptimizer optimizer2;
    private ExternalBubbleTracker tracker1;
    private ExternalBubbleTracker tracker2;
    private GhostLayerHealth health1;
    private GhostLayerHealth health2;
    private UUID server1Id;
    private UUID server2Id;

    @BeforeEach
    void setup() {
        registry = LocalServerTransport.Registry.create();

        // Create two Bubbles with P2P transport
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();

        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);

        bubble1 = new Bubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new Bubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);

        // Add entities so bubbles have positions
        bubble1.addEntity("entity-1", new Point3f(50.0f, 50.0f, 50.0f), new Object());
        bubble2.addEntity("entity-2", new Point3f(55.0f, 55.0f, 50.0f), new Object());

        // Establish neighbor relationship
        bubble1.addNeighbor(bubble2.id());
        bubble2.addNeighbor(bubble1.id());

        // Create P2P ghost channels
        channel1 = new P2PGhostChannel<>(bubble1);
        channel2 = new P2PGhostChannel<>(bubble2);

        // Create supporting components with UUID server IDs
        serverRegistry = new ServerRegistry();
        server1Id = UUID.randomUUID();
        server2Id = UUID.randomUUID();
        serverRegistry.registerBubble(bubble1.id(), server1Id);
        serverRegistry.registerBubble(bubble2.id(), server2Id);

        optimizer1 = new SameServerOptimizer(serverRegistry);
        optimizer2 = new SameServerOptimizer(serverRegistry);
        optimizer1.registerLocalBubble(bubble1);
        optimizer2.registerLocalBubble(bubble2);

        tracker1 = new ExternalBubbleTracker();
        tracker2 = new ExternalBubbleTracker();

        health1 = new GhostLayerHealth();
        health2 = new GhostLayerHealth();

        // Create BubbleGhostManagers with P2PGhostChannels
        manager1 = new BubbleGhostManager<>(
            bubble1, serverRegistry, channel1, optimizer1, tracker1, health1
        );
        manager2 = new BubbleGhostManager<>(
            bubble2, serverRegistry, channel2, optimizer2, tracker2, health2
        );

        // Register ghost batch handlers
        channel1.onReceive((fromId, ghosts) -> manager1.handleGhostBatch(fromId, ghosts));
        channel2.onReceive((fromId, ghosts) -> manager2.handleGhostBatch(fromId, ghosts));
    }

    @AfterEach
    void teardown() {
        if (channel1 != null) channel1.close();
        if (channel2 != null) channel2.close();
        if (bubble1 != null) bubble1.close();
        if (bubble2 != null) bubble2.close();
        if (registry != null) registry.close();
    }

    @Test
    void testGhostTransmission_p2pChannel() throws Exception {
        // Given: Entity near boundary on bubble1
        var entityId = new StringEntityID("boundary-entity");
        var position = new Point3f(52.0f, 52.0f, 50.0f);
        var content = new Object();

        // When: Notify manager and complete bucket
        var receiveLatch = new CountDownLatch(1);
        var receivedCount = new AtomicInteger(0);
        channel2.onReceive((fromId, ghosts) -> {
            receivedCount.addAndGet(ghosts.size());
            receiveLatch.countDown();
        });

        manager1.notifyEntityNearBoundary(entityId, position, content, bubble2.id(), 1L);
        manager1.onBucketComplete(1L);

        // Then: Ghost transmitted via P2P
        assertThat(receiveLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedCount.get()).isEqualTo(1);
    }

    @Test
    void testSameServerOptimization_bypassesGhostSync() {
        // Given: Both bubbles on same server
        var sameServerId = UUID.randomUUID();
        serverRegistry.registerBubble(bubble1.id(), sameServerId);
        serverRegistry.registerBubble(bubble2.id(), sameServerId);

        var entityId = new StringEntityID("same-server-entity");
        var position = new Point3f(52.0f, 52.0f, 50.0f);
        var content = new Object();

        // When: Notify manager (should be bypassed)
        manager1.notifyEntityNearBoundary(entityId, position, content, bubble2.id(), 1L);
        manager1.onBucketComplete(1L);

        // Then: No ghost queued (bypassed)
        assertThat(channel1.getPendingCount(bubble2.id())).isEqualTo(0);
    }

    @Test
    void testBatchedTransmission_multipleGhosts() throws Exception {
        // Given: Multiple entities near boundary
        var entities = List.of(
            new StringEntityID("entity-a"),
            new StringEntityID("entity-b"),
            new StringEntityID("entity-c")
        );

        var receivedGhosts = new ArrayList<SimulationGhostEntity<StringEntityID, Object>>();
        var receiveLatch = new CountDownLatch(1);
        channel2.onReceive((fromId, ghosts) -> {
            receivedGhosts.addAll(ghosts);
            if (receivedGhosts.size() >= 3) {
                receiveLatch.countDown();
            }
        });

        // When: Notify for all entities, then complete bucket
        for (var entity : entities) {
            var position = new Point3f(52.0f + entities.indexOf(entity) * 0.1f, 52.0f, 50.0f);
            manager1.notifyEntityNearBoundary(entity, position, new Object(), bubble2.id(), 1L);
        }
        manager1.onBucketComplete(1L);

        // Then: All ghosts received in batch
        assertThat(receiveLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedGhosts).hasSize(3);
    }

    @Test
    void testExternalBubbleTracker_updatedOnReceive() throws Exception {
        // Given: Ghost from bubble1 to bubble2
        var entityId = new StringEntityID("tracker-test-entity");
        var position = new Point3f(52.0f, 52.0f, 50.0f);

        var receiveLatch = new CountDownLatch(1);
        channel2.onReceive((fromId, ghosts) -> receiveLatch.countDown());

        // When: Send ghost
        manager1.notifyEntityNearBoundary(entityId, position, new Object(), bubble2.id(), 1L);
        manager1.onBucketComplete(1L);
        receiveLatch.await(2, TimeUnit.SECONDS);

        // Then: External bubble tracker updated
        assertThat(tracker2.getDiscoveredBubbles()).contains(bubble1.id());
    }

    @Test
    void testGhostLayerHealth_ncMetricUpdated() throws Exception {
        // Given: Expected neighbor count set
        health2.setExpectedNeighbors(1);

        var entityId = new StringEntityID("health-test-entity");
        var position = new Point3f(52.0f, 52.0f, 50.0f);

        var receiveLatch = new CountDownLatch(1);
        channel2.onReceive((fromId, ghosts) -> receiveLatch.countDown());

        // When: Send ghost
        manager1.notifyEntityNearBoundary(entityId, position, new Object(), bubble2.id(), 1L);
        manager1.onBucketComplete(1L);
        receiveLatch.await(2, TimeUnit.SECONDS);

        // Then: NC metric updated
        assertThat(manager2.getNeighborConsistency()).isGreaterThan(0.0f);
    }

    @Test
    void testVONNeighborLifecycle() {
        // Given: Initial state
        assertThat(manager1.getActiveGhostCount()).isEqualTo(0);

        // When: Add VON neighbor
        manager1.onVONNeighborAdded(bubble2.id());

        // Then: Neighbor tracked
        assertThat(tracker1.getDiscoveredBubbles()).contains(bubble2.id());

        // When: Remove VON neighbor
        manager1.onVONNeighborRemoved(bubble2.id());

        // Then: Ghost state cleaned up (no pending ghosts for removed neighbor)
        assertThat(channel1.getPendingCount(bubble2.id())).isEqualTo(0);
    }

    @Test
    void testBidirectionalGhostSync() throws Exception {
        // Given: Entities on both bubbles
        var entity1 = new StringEntityID("entity-from-1");
        var entity2 = new StringEntityID("entity-from-2");

        var received1 = new AtomicInteger(0);
        var received2 = new AtomicInteger(0);
        var latch = new CountDownLatch(2);

        channel1.onReceive((fromId, ghosts) -> {
            received1.addAndGet(ghosts.size());
            latch.countDown();
        });
        channel2.onReceive((fromId, ghosts) -> {
            received2.addAndGet(ghosts.size());
            latch.countDown();
        });

        // When: Both managers send ghosts
        manager1.notifyEntityNearBoundary(entity1, new Point3f(52.0f, 52.0f, 50.0f), new Object(), bubble2.id(), 1L);
        manager2.notifyEntityNearBoundary(entity2, new Point3f(48.0f, 48.0f, 50.0f), new Object(), bubble1.id(), 1L);
        manager1.onBucketComplete(1L);
        manager2.onBucketComplete(1L);

        // Then: Both received ghosts
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received1.get()).isEqualTo(1);
        assertThat(received2.get()).isEqualTo(1);
    }

    @Test
    void testPerformance_endToEndUnder100ms() throws Exception {
        // Given: 50 entities near boundary
        var entities = new ArrayList<StringEntityID>();
        for (int i = 0; i < 50; i++) {
            entities.add(new StringEntityID("perf-entity-" + i));
        }

        var receiveLatch = new CountDownLatch(1);
        var receivedCount = new AtomicInteger(0);
        channel2.onReceive((fromId, ghosts) -> {
            receivedCount.addAndGet(ghosts.size());
            if (receivedCount.get() >= 50) {
                receiveLatch.countDown();
            }
        });

        // When: Queue all entities and measure end-to-end latency
        long start = System.nanoTime();
        for (var entity : entities) {
            var position = new Point3f(52.0f + entities.indexOf(entity) * 0.01f, 52.0f, 50.0f);
            manager1.notifyEntityNearBoundary(entity, position, new Object(), bubble2.id(), 1L);
        }
        manager1.onBucketComplete(1L);
        assertThat(receiveLatch.await(2, TimeUnit.SECONDS)).isTrue();
        long latencyNs = System.nanoTime() - start;

        // Then: End-to-end latency < 100ms
        double latencyMs = latencyNs / 1_000_000.0;
        assertThat(latencyMs).isLessThan(100.0);
        System.out.printf("End-to-end ghost sync latency: %.2f ms for %d ghosts%n", latencyMs, receivedCount.get());
    }

    @Test
    void testMixedServerScenario_sameAndDifferent() throws Exception {
        // Given: Third bubble on same server as bubble1
        var id3 = UUID.randomUUID();
        var transport3 = registry.register(id3);
        var bubble3 = new Bubble(id3, SPATIAL_LEVEL, TARGET_FRAME_MS, transport3);
        bubble3.addEntity("entity-3", new Point3f(45.0f, 45.0f, 50.0f), new Object());

        // Same server as bubble1
        serverRegistry.registerBubble(bubble3.id(), server1Id);
        bubble1.addNeighbor(bubble3.id());
        bubble3.addNeighbor(bubble1.id());

        var channel3 = new P2PGhostChannel<StringEntityID, Object>(bubble3);
        var optimizer3 = new SameServerOptimizer(serverRegistry);
        optimizer3.registerLocalBubble(bubble3);
        optimizer1.registerLocalBubble(bubble3);

        try {
            // Track what gets transmitted
            var toBubble2 = new AtomicInteger(0);
            var toBubble3 = new AtomicInteger(0);

            channel2.onReceive((fromId, ghosts) -> toBubble2.addAndGet(ghosts.size()));
            channel3.onReceive((fromId, ghosts) -> toBubble3.addAndGet(ghosts.size()));

            // When: Send ghosts to both neighbors
            var entity = new StringEntityID("mixed-entity");
            var position = new Point3f(52.0f, 52.0f, 50.0f);

            // To bubble2 (different server) - should transmit
            manager1.notifyEntityNearBoundary(entity, position, new Object(), bubble2.id(), 1L);

            // To bubble3 (same server) - should bypass
            manager1.notifyEntityNearBoundary(entity, position, new Object(), bubble3.id(), 1L);

            manager1.onBucketComplete(1L);
            Thread.sleep(500); // Allow time for any transmission

            // Then: Only bubble2 received ghost (bubble3 bypassed via same-server optimization)
            assertThat(toBubble2.get()).isEqualTo(1);
            assertThat(toBubble3.get()).isEqualTo(0); // Bypassed
        } finally {
            channel3.close();
            bubble3.close();
        }
    }
}
