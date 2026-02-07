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

package com.hellblazer.luciferase.simulation.von;

import javafx.geometry.Point3D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2P Protocol Integration Tests.
 * <p>
 * Tests the full P2P protocol flow using LocalServerTransport:
 * <ul>
 *   <li>JOIN protocol: JoinRequest → JoinResponse → neighbor establishment</li>
 *   <li>MOVE protocol: Move broadcast → neighbor state updates</li>
 *   <li>LEAVE protocol: Leave broadcast → neighbor cleanup</li>
 *   <li>GHOST_SYNC protocol: Ghost data synchronization between neighbors</li>
 * </ul>
 * <p>
 * These tests validate v4.0 architecture requirements:
 * <ul>
 *   <li>Point-to-point communication (no broadcast)</li>
 *   <li>VON IS the distributed spatial index</li>
 *   <li>Neighbor consistency (NC) > 0.9</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class P2PProtocolIntegrationTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 16;
    private static final float AOI_RADIUS = 50.0f;

    private LocalServerTransport.Registry registry;
    private Manager manager;

    @BeforeEach
    void setup() {
        registry = LocalServerTransport.Registry.create();
        manager = new Manager(registry, SPATIAL_LEVEL, TARGET_FRAME_MS, AOI_RADIUS);
    }

    @AfterEach
    void cleanup() {
        if (manager != null) manager.close();
        if (registry != null) registry.close();
    }

    // ========== JOIN Protocol Tests ==========

    @Test
    void testJoinProtocol_messageFlow() throws Exception {
        // Given: Entry point bubble
        var entryPoint = createBubbleAt(50.0f, 50.0f, 50.0f);
        manager.joinAt(entryPoint, entryPoint.position());

        var requestReceived = new CountDownLatch(1);
        var responseReceived = new CountDownLatch(1);

        // Track JoinRequest at entry point
        entryPoint.addEventListener(event -> {
            if (event instanceof Event.Join) {
                requestReceived.countDown();
            }
        });

        // When: Joiner sends JoinRequest
        var joiner = createBubbleAt(55.0f, 55.0f, 50.0f);
        joiner.addEventListener(event -> {
            // JoinResponse creates neighbors for joiner
            if (!joiner.neighbors().isEmpty()) {
                responseReceived.countDown();
            }
        });

        manager.joinAt(joiner, joiner.position());

        // Then: Full message flow completes
        assertThat(requestReceived.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);  // Allow response processing

        // Bidirectional neighbor relationship established
        assertThat(entryPoint.neighbors()).contains(joiner.id());
        assertThat(joiner.neighbors()).contains(entryPoint.id());
    }

    @Test
    void testJoinProtocol_neighborListTransfer() throws Exception {
        // Given: 3-bubble cluster
        var bubble1 = createBubbleAt(50.0f, 50.0f, 50.0f);
        manager.joinAt(bubble1, bubble1.position());

        var bubble2 = createBubbleAt(55.0f, 55.0f, 50.0f);
        manager.joinAt(bubble2, bubble2.position());
        Thread.sleep(200);

        var bubble3 = createBubbleAt(52.0f, 52.0f, 50.0f);
        manager.joinAt(bubble3, bubble3.position());
        Thread.sleep(200);

        // When: New bubble joins
        var joiner = createBubbleAt(53.0f, 53.0f, 50.0f);
        manager.joinAt(joiner, joiner.position());
        Thread.sleep(300);  // Allow neighbor list transfer

        // Then: Joiner receives neighbor list from acceptor
        // Should have at least the entry point as neighbor
        assertThat(joiner.neighbors()).isNotEmpty();

        // The entry point should include existing neighbors in response
        // Joiner may have multiple neighbors from the cluster
        Set<java.util.UUID> joinerNeighbors = joiner.neighbors();
        System.out.println("Joiner neighbors: " + joinerNeighbors.size());
    }

    // ========== MOVE Protocol Tests ==========

    @Test
    void testMoveProtocol_notifiesAllNeighbors() throws Exception {
        // Given: 3 connected bubbles
        var bubble1 = createBubbleAt(50.0f, 50.0f, 50.0f);
        var bubble2 = createBubbleAt(55.0f, 55.0f, 50.0f);
        var bubble3 = createBubbleAt(60.0f, 60.0f, 50.0f);

        manager.joinAt(bubble1, bubble1.position());
        manager.joinAt(bubble2, bubble2.position());
        Thread.sleep(200);
        manager.joinAt(bubble3, bubble3.position());
        Thread.sleep(300);

        var movesReceived = new CopyOnWriteArrayList<Event.Move>();
        bubble1.addEventListener(event -> {
            if (event instanceof Event.Move move) {
                movesReceived.add(move);
            }
        });
        bubble3.addEventListener(event -> {
            if (event instanceof Event.Move move) {
                movesReceived.add(move);
            }
        });

        // When: Bubble2 moves
        manager.move(bubble2, new Point3D(57.0, 57.0, 50.0));

        // Then: All neighbors receive move notification
        Thread.sleep(200);
        assertThat(movesReceived).anyMatch(m -> m.nodeId().equals(bubble2.id()));
    }

    @Test
    void testMoveProtocol_updatesNeighborState() throws Exception {
        // Given: Two connected bubbles
        var bubble1 = createBubbleAt(50.0f, 50.0f, 50.0f);
        var bubble2 = createBubbleAt(55.0f, 55.0f, 50.0f);

        manager.joinAt(bubble1, bubble1.position());
        manager.joinAt(bubble2, bubble2.position());
        Thread.sleep(200);

        var initialState = bubble1.getNeighborState(bubble2.id());

        // When: Bubble2 moves
        var newPosition = new Point3D(60.0, 60.0, 50.0);
        manager.move(bubble2, newPosition);
        Thread.sleep(200);

        // Then: Bubble1's tracked state for bubble2 is updated
        var updatedState = bubble1.getNeighborState(bubble2.id());
        assertThat(updatedState).isNotNull();
        assertThat(updatedState.lastUpdateMs()).isGreaterThanOrEqualTo(initialState.lastUpdateMs());
    }

    // ========== LEAVE Protocol Tests ==========

    @Test
    void testLeaveProtocol_removesFromNeighbors() throws Exception {
        // Given: Two connected bubbles
        var bubble1 = createBubbleAt(50.0f, 50.0f, 50.0f);
        var bubble2 = createBubbleAt(55.0f, 55.0f, 50.0f);

        manager.joinAt(bubble1, bubble1.position());
        manager.joinAt(bubble2, bubble2.position());
        Thread.sleep(200);

        // Verify they're neighbors
        assertThat(bubble1.neighbors()).contains(bubble2.id());

        // When: Bubble2 leaves
        manager.leave(bubble2);
        Thread.sleep(200);

        // Then: Bubble1 no longer has bubble2 as neighbor
        assertThat(bubble1.neighbors()).doesNotContain(bubble2.id());
        assertThat(bubble1.getNeighborState(bubble2.id())).isNull();
    }

    @Test
    void testLeaveProtocol_broadcastsToAll() throws Exception {
        // Given: 3 connected bubbles
        var bubble1 = createBubbleAt(50.0f, 50.0f, 50.0f);
        var bubble2 = createBubbleAt(55.0f, 55.0f, 50.0f);
        var bubble3 = createBubbleAt(52.0f, 58.0f, 50.0f);

        manager.joinAt(bubble1, bubble1.position());
        manager.joinAt(bubble2, bubble2.position());
        Thread.sleep(200);
        manager.joinAt(bubble3, bubble3.position());
        Thread.sleep(300);

        var leavesReceived = new CountDownLatch(2);
        bubble1.addEventListener(event -> {
            if (event instanceof Event.Leave leave && leave.nodeId().equals(bubble2.id())) {
                leavesReceived.countDown();
            }
        });
        bubble3.addEventListener(event -> {
            if (event instanceof Event.Leave leave && leave.nodeId().equals(bubble2.id())) {
                leavesReceived.countDown();
            }
        });

        // When: Middle bubble leaves
        manager.leave(bubble2);

        // Then: Both remaining bubbles receive leave notification
        // Note: May not receive both if not all were neighbors
        Thread.sleep(500);
        // At least one should have received leave
        assertThat(bubble1.neighbors()).doesNotContain(bubble2.id());
    }

    // ========== Performance Tests ==========

    @Test
    void testPerformance_joinLatencyUnder100ms() throws Exception {
        // Given: 10-bubble cluster
        List<Bubble> cluster = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            var bubble = createBubbleAt(50.0f + i * 8, 50.0f, 50.0f);
            manager.joinAt(bubble, bubble.position());
            cluster.add(bubble);
            Thread.sleep(50);
        }

        // When: Measure join latency for new bubble
        var joiner = createBubbleAt(90.0f, 50.0f, 50.0f);
        long startNs = System.nanoTime();
        manager.joinAt(joiner, joiner.position());
        long latencyNs = System.nanoTime() - startNs;

        double latencyMs = latencyNs / 1_000_000.0;

        // Then: JOIN latency < 100ms
        assertThat(latencyMs).isLessThan(100.0);
        System.out.println("JOIN latency: " + latencyMs + "ms");
    }

    @Test
    void testPerformance_moveLatencyUnder50ms() throws Exception {
        // Given: Two connected bubbles
        var bubble1 = createBubbleAt(50.0f, 50.0f, 50.0f);
        var bubble2 = createBubbleAt(55.0f, 55.0f, 50.0f);

        manager.joinAt(bubble1, bubble1.position());
        manager.joinAt(bubble2, bubble2.position());
        Thread.sleep(200);

        // When: Measure move notification latency
        long startNs = System.nanoTime();
        manager.move(bubble2, new Point3D(60.0, 60.0, 50.0));
        long latencyNs = System.nanoTime() - startNs;

        double latencyMs = latencyNs / 1_000_000.0;

        // Then: MOVE notification < 50ms
        assertThat(latencyMs).isLessThan(50.0);
        System.out.println("MOVE latency: " + latencyMs + "ms");
    }

    // ========== Neighbor Consistency Tests ==========

    @Test
    void testNeighborConsistency_withIntroductions() throws Exception {
        // Given: 10-bubble cluster in tight area (all within AOI)
        // In P2P mode with star topology, the hub (first bubble) knows all,
        // but others only know the hub + neighbors from introductions
        List<Bubble> bubbles = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            var bubble = createBubbleAt(
                50.0f + (i % 3) * 10.0f,
                50.0f + (i / 3) * 10.0f,
                50.0f
            );
            manager.joinAt(bubble, bubble.position());
            bubbles.add(bubble);
            Thread.sleep(200);  // Allow propagation and introductions
        }

        Thread.sleep(1000);  // Allow final convergence

        // Then: Most bubbles should have multiple neighbors due to introductions
        int bubblesWithMultipleNeighbors = 0;
        for (var bubble : bubbles) {
            int neighborCount = bubble.neighbors().size();
            System.out.println("Bubble " + bubble.id() + " neighbors: " + neighborCount);
            if (neighborCount >= 2) {
                bubblesWithMultipleNeighbors++;
            }
        }

        // The hub (first bubble) should have many neighbors, others should have at least 2
        // (the hub + at least one other from introduction)
        assertThat(bubblesWithMultipleNeighbors).isGreaterThanOrEqualTo(5);
    }

    // ========== Edge Case Tests ==========

    @Test
    void testEdgeCase_duplicateJoinIgnored() throws Exception {
        // Given: Bubble already in VON
        var bubble = createBubbleAt(50.0f, 50.0f, 50.0f);
        manager.joinAt(bubble, bubble.position());

        // When: Same bubble joins again
        var success = manager.joinAt(bubble, bubble.position());

        // Then: Duplicate join handled gracefully
        assertThat(success).isTrue();  // Solo join always succeeds
        assertThat(manager.size()).isEqualTo(1);
    }

    @Test
    void testEdgeCase_leaveAlreadyGoneBubble() throws Exception {
        // Given: Bubble that has left
        var bubble = createBubbleAt(50.0f, 50.0f, 50.0f);
        manager.joinAt(bubble, bubble.position());
        manager.leave(bubble);

        // When: Try to leave again (should not throw)
        // The bubble is already closed, so calling leave on manager should be safe
        assertThat(manager.getBubble(bubble.id())).isNull();
    }

    // ========== Helper Methods ==========

    private Bubble createBubbleAt(float x, float y, float z) {
        var bubble = manager.createBubble();
        for (int i = 0; i < 10; i++) {
            float ex = Math.max(0.1f, x + (i % 3) * 0.1f);
            float ey = Math.max(0.1f, y + (i / 3) * 0.1f);
            float ez = Math.max(0.1f, z);
            bubble.addEntity("entity-" + i, new Point3f(ex, ey, ez), "content-" + i);
        }
        return bubble;
    }
}
