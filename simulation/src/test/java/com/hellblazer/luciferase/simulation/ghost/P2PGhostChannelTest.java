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

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import com.hellblazer.luciferase.simulation.von.Bubble;
import com.hellblazer.luciferase.simulation.von.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for P2PGhostChannel - P2P ghost synchronization over Transport.
 * <p>
 * Tests validate:
 * - Ghost queuing and flushing at bucket boundaries
 * - P2P transmission via Transport
 * - Receiving and converting TransportGhosts back to SimulationGhostEntities
 * - Handler notification on incoming ghosts
 * - Neighbor-only transmission (non-neighbors ignored)
 * - Performance: Ghost sync < 100ms
 *
 * @author hal.hildebrand
 */
class P2PGhostChannelTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 10;

    private LocalServerTransport.Registry registry;
    private Bubble bubble1;
    private Bubble bubble2;
    private P2PGhostChannel<StringEntityID, Object> channel1;
    private P2PGhostChannel<StringEntityID, Object> channel2;

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

        // Add some entities so bubbles have positions
        bubble1.addEntity("entity-1", new Point3f(50.0f, 50.0f, 50.0f), new Object());
        bubble2.addEntity("entity-2", new Point3f(55.0f, 55.0f, 50.0f), new Object());

        // Establish neighbor relationship (bidirectional)
        bubble1.addNeighbor(bubble2.id());
        bubble2.addNeighbor(bubble1.id());

        // Create P2P ghost channels
        channel1 = new P2PGhostChannel<>(bubble1);
        channel2 = new P2PGhostChannel<>(bubble2);
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
    void testQueueGhost_queuesForNeighbor() {
        // Given: A ghost entity
        var ghost = createGhost("ghost-1", new Point3f(51.0f, 51.0f, 50.0f));

        // When: Queue ghost for neighbor
        channel1.queueGhost(bubble2.id(), ghost);

        // Then: Ghost is pending
        assertThat(channel1.getPendingCount(bubble2.id())).isEqualTo(1);
    }

    @Test
    void testQueueGhost_ignoresNonNeighbor() {
        // Given: A non-neighbor bubble ID
        var nonNeighbor = UUID.randomUUID();
        var ghost = createGhost("ghost-1", new Point3f(51.0f, 51.0f, 50.0f));

        // When: Queue ghost for non-neighbor
        channel1.queueGhost(nonNeighbor, ghost);

        // Then: Ghost is NOT queued
        assertThat(channel1.getPendingCount(nonNeighbor)).isEqualTo(0);
    }

    @Test
    void testFlush_sendsBatchToNeighbor() throws Exception {
        // Given: Queued ghosts
        var ghost1 = createGhost("ghost-1", new Point3f(51.0f, 51.0f, 50.0f));
        var ghost2 = createGhost("ghost-2", new Point3f(52.0f, 52.0f, 50.0f));
        channel1.queueGhost(bubble2.id(), ghost1);
        channel1.queueGhost(bubble2.id(), ghost2);

        // When: Flush at bucket boundary
        var receivedGhosts = new AtomicReference<List<SimulationGhostEntity<StringEntityID, Object>>>();
        var receiveLatch = new CountDownLatch(1);
        channel2.onReceive((fromId, ghosts) -> {
            receivedGhosts.set(new ArrayList<>(ghosts));
            receiveLatch.countDown();
        });

        channel1.flush(100);

        // Then: Ghosts received at bubble2
        assertThat(receiveLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedGhosts.get()).hasSize(2);
        assertThat(channel1.getPendingCount(bubble2.id())).isEqualTo(0);
    }

    @Test
    void testIsConnected_checkNeighborStatus() {
        // Given: Established neighbor relationship
        // Then: Connected to neighbor
        assertThat(channel1.isConnected(bubble2.id())).isTrue();

        // And: Not connected to non-neighbor
        var nonNeighbor = UUID.randomUUID();
        assertThat(channel1.isConnected(nonNeighbor)).isFalse();
    }

    @Test
    void testSendBatch_transmitsViaTransport() throws Exception {
        // Given: A batch of ghosts
        var ghosts = List.of(
            createGhost("ghost-1", new Point3f(51.0f, 51.0f, 50.0f)),
            createGhost("ghost-2", new Point3f(52.0f, 52.0f, 50.0f)),
            createGhost("ghost-3", new Point3f(53.0f, 53.0f, 50.0f))
        );

        // When: Send batch directly
        var receivedGhosts = new AtomicReference<List<SimulationGhostEntity<StringEntityID, Object>>>();
        var receiveLatch = new CountDownLatch(1);
        channel2.onReceive((fromId, received) -> {
            receivedGhosts.set(new ArrayList<>(received));
            receiveLatch.countDown();
        });

        channel1.sendBatch(bubble2.id(), ghosts);

        // Then: All ghosts received
        assertThat(receiveLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedGhosts.get()).hasSize(3);
    }

    @Test
    void testGhostConversion_preservesMetadata() throws Exception {
        // Given: A ghost with specific metadata
        var position = new Point3f(51.5f, 51.5f, 50.5f);
        var ghost = createGhostWithMetadata("ghost-meta", position, 42L, 7L);

        // When: Send and receive
        var receivedGhosts = new AtomicReference<List<SimulationGhostEntity<StringEntityID, Object>>>();
        var receiveLatch = new CountDownLatch(1);
        channel2.onReceive((fromId, received) -> {
            receivedGhosts.set(new ArrayList<>(received));
            receiveLatch.countDown();
        });

        channel1.sendBatch(bubble2.id(), List.of(ghost));

        // Then: Metadata preserved
        assertThat(receiveLatch.await(2, TimeUnit.SECONDS)).isTrue();
        var received = receivedGhosts.get().get(0);
        assertThat(received.epoch()).isEqualTo(42L);
        assertThat(received.version()).isEqualTo(7L);
        assertThat(received.sourceBubbleId()).isEqualTo(bubble1.id());
    }

    @Test
    void testMultipleHandlers_allNotified() throws Exception {
        // Given: Multiple handlers registered
        var handler1Count = new AtomicReference<>(0);
        var handler2Count = new AtomicReference<>(0);
        var latch = new CountDownLatch(2);

        channel2.onReceive((fromId, ghosts) -> {
            handler1Count.set(ghosts.size());
            latch.countDown();
        });
        channel2.onReceive((fromId, ghosts) -> {
            handler2Count.set(ghosts.size());
            latch.countDown();
        });

        // When: Send ghosts
        var ghost = createGhost("ghost-1", new Point3f(51.0f, 51.0f, 50.0f));
        channel1.sendBatch(bubble2.id(), List.of(ghost));

        // Then: Both handlers notified
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(handler1Count.get()).isEqualTo(1);
        assertThat(handler2Count.get()).isEqualTo(1);
    }

    @Test
    void testClose_clearsStateAndHandlers() {
        // Given: Channel with pending ghosts and handler
        var ghost = createGhost("ghost-1", new Point3f(51.0f, 51.0f, 50.0f));
        channel1.queueGhost(bubble2.id(), ghost);
        channel1.onReceive((fromId, ghosts) -> {});

        // When: Close channel
        channel1.close();

        // Then: State cleared
        assertThat(channel1.getPendingCount(bubble2.id())).isEqualTo(0);
    }

    @Test
    void testPerformance_ghostSyncUnder100ms() throws Exception {
        // Given: 100 ghosts to sync
        var ghosts = new ArrayList<SimulationGhostEntity<StringEntityID, Object>>();
        for (int i = 0; i < 100; i++) {
            ghosts.add(createGhost("ghost-" + i, new Point3f(50.0f + i * 0.1f, 50.0f, 50.0f)));
        }

        // When: Send batch and measure latency
        var receiveLatch = new CountDownLatch(1);
        channel2.onReceive((fromId, received) -> receiveLatch.countDown());

        long start = System.nanoTime();
        channel1.sendBatch(bubble2.id(), ghosts);
        assertThat(receiveLatch.await(2, TimeUnit.SECONDS)).isTrue();
        long latencyNs = System.nanoTime() - start;

        // Then: Latency < 100ms
        double latencyMs = latencyNs / 1_000_000.0;
        assertThat(latencyMs).isLessThan(100.0);
        System.out.printf("Ghost sync latency: %.2f ms for %d ghosts%n", latencyMs, ghosts.size());
    }

    @Test
    void testBidirectionalSync_bothDirections() throws Exception {
        // Given: Handlers on both channels
        var bubble1Received = new AtomicReference<List<SimulationGhostEntity<StringEntityID, Object>>>();
        var bubble2Received = new AtomicReference<List<SimulationGhostEntity<StringEntityID, Object>>>();
        var latch = new CountDownLatch(2);

        channel1.onReceive((fromId, ghosts) -> {
            bubble1Received.set(new ArrayList<>(ghosts));
            latch.countDown();
        });
        channel2.onReceive((fromId, ghosts) -> {
            bubble2Received.set(new ArrayList<>(ghosts));
            latch.countDown();
        });

        // When: Send in both directions
        var ghost1 = createGhost("from-1", new Point3f(51.0f, 51.0f, 50.0f));
        var ghost2 = createGhost("from-2", new Point3f(52.0f, 52.0f, 50.0f));

        channel1.sendBatch(bubble2.id(), List.of(ghost1));
        channel2.sendBatch(bubble1.id(), List.of(ghost2));

        // Then: Both received
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(bubble1Received.get()).hasSize(1);
        assertThat(bubble2Received.get()).hasSize(1);
    }

    // ========== Helper Methods ==========

    private SimulationGhostEntity<StringEntityID, Object> createGhost(String entityId, Point3f position) {
        return createGhostWithMetadata(entityId, position, 1L, 1L);
    }

    private SimulationGhostEntity<StringEntityID, Object> createGhostWithMetadata(
        String entityId, Point3f position, long epoch, long version
    ) {
        var id = new StringEntityID(entityId);
        var bounds = new EntityBounds(position, 0.5f);
        var ghost = new GhostZoneManager.GhostEntity<StringEntityID, Object>(
            id, new Object(), position, bounds, "tree-" + bubble1.id()
        );
        return new SimulationGhostEntity<>(
            ghost, bubble1.id(), 1L, epoch, version
        );
    }
}
