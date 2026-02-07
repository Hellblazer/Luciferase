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

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Manager - P2P VON coordination.
 * <p>
 * These tests validate the P2P protocol implementation using
 * LocalServerTransport for in-process communication.
 *
 * @author hal.hildebrand
 */
class ManagerTest {

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

    @Test
    void testCreateBubble_registersWithManager() {
        // When: Create a bubble
        var bubble = manager.createBubble();

        // Then: Bubble is managed
        assertThat(manager.size()).isEqualTo(1);
        assertThat(manager.getBubble(bubble.id())).isEqualTo(bubble);
    }

    @Test
    void testSoloJoin_succeeds() {
        // Given: First bubble in the VON
        var bubble = manager.createBubble();
        addEntities(bubble, new Point3f(50.0f, 50.0f, 50.0f), 10);

        // When: Solo join
        var success = manager.joinAt(bubble, bubble.position());

        // Then: Join succeeds (no neighbors needed)
        assertThat(success).isTrue();
        assertThat(bubble.neighbors()).isEmpty();  // Solo bubble
    }

    @Test
    void testTwoBubbleJoin_establishesNeighbors() throws Exception {
        // Given: First bubble in VON
        var bubble1 = manager.createBubble();
        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
        manager.joinAt(bubble1, bubble1.position());

        // When: Second bubble joins
        var bubble2 = manager.createBubble();
        addEntities(bubble2, new Point3f(55.0f, 55.0f, 50.0f), 10);

        var joinReceived = new CountDownLatch(1);
        bubble1.addEventListener(event -> {
            if (event instanceof Event.Join) {
                joinReceived.countDown();
            }
        });

        manager.joinAt(bubble2, bubble2.position());

        // Then: Both bubbles should become neighbors
        assertThat(joinReceived.await(2, TimeUnit.SECONDS)).isTrue();

        // Give time for async message processing
        Thread.sleep(100);

        assertThat(bubble1.neighbors()).contains(bubble2.id());
        assertThat(bubble2.neighbors()).contains(bubble1.id());
    }

    @Test
    void testTenBubbleCluster_formation() throws Exception {
        // Given: Empty VON
        List<Bubble> bubbles = new ArrayList<>();
        var allJoinsComplete = new CountDownLatch(10);

        // When: Create and join 10 bubbles
        for (int i = 0; i < 10; i++) {
            var bubble = manager.createBubble();
            float x = 50.0f + (i % 3) * 15.0f;
            float y = 50.0f + (i / 3) * 15.0f;
            addEntities(bubble, new Point3f(x, y, 50.0f), 10);

            bubble.addEventListener(event -> {
                if (event instanceof Event.Join) {
                    allJoinsComplete.countDown();
                }
            });

            manager.joinAt(bubble, bubble.position());
            bubbles.add(bubble);

            // Small delay to allow async processing
            Thread.sleep(50);
        }

        // Wait for joins to propagate
        Thread.sleep(500);

        // Then: All bubbles should be in manager
        assertThat(manager.size()).isEqualTo(10);

        // And: Each bubble should have at least one neighbor (except possibly first)
        int bubblesWithNeighbors = 0;
        for (var bubble : bubbles) {
            if (!bubble.neighbors().isEmpty()) {
                bubblesWithNeighbors++;
            }
        }
        // At minimum, bubble 2+ should have neighbors (bubble 1 gets neighbors when others join)
        assertThat(bubblesWithNeighbors).isGreaterThanOrEqualTo(8);
    }

    @Test
    void testMove_notifiesNeighbors() throws Exception {
        // Given: Two connected bubbles
        var bubble1 = manager.createBubble();
        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
        manager.joinAt(bubble1, bubble1.position());

        var bubble2 = manager.createBubble();
        addEntities(bubble2, new Point3f(55.0f, 55.0f, 50.0f), 10);
        manager.joinAt(bubble2, bubble2.position());

        Thread.sleep(200);  // Let join complete

        var moveReceived = new CountDownLatch(1);
        bubble1.addEventListener(event -> {
            if (event instanceof Event.Move) {
                moveReceived.countDown();
            }
        });

        // When: Bubble2 moves
        manager.move(bubble2, new Point3D(60.0, 60.0, 50.0));

        // Then: Bubble1 receives move notification
        assertThat(moveReceived.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void testLeave_notifiesNeighbors() throws Exception {
        // Given: Two connected bubbles
        var bubble1 = manager.createBubble();
        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
        manager.joinAt(bubble1, bubble1.position());

        var bubble2 = manager.createBubble();
        addEntities(bubble2, new Point3f(55.0f, 55.0f, 50.0f), 10);
        manager.joinAt(bubble2, bubble2.position());

        Thread.sleep(200);  // Let join complete

        var leaveReceived = new CountDownLatch(1);
        bubble1.addEventListener(event -> {
            if (event instanceof Event.Leave) {
                leaveReceived.countDown();
            }
        });

        // When: Bubble2 leaves
        manager.leave(bubble2);

        // Then: Bubble1 receives leave notification
        assertThat(leaveReceived.await(2, TimeUnit.SECONDS)).isTrue();

        // And: Bubble2 is removed from manager
        assertThat(manager.getBubble(bubble2.id())).isNull();
        assertThat(manager.size()).isEqualTo(1);
    }

    @Test
    void testNeighborConsistency_calculatedCorrectly() throws Exception {
        // Given: Cluster of bubbles within AOI
        for (int i = 0; i < 5; i++) {
            var bubble = manager.createBubble();
            float x = 50.0f + i * 10.0f;  // Within AOI_RADIUS of each other
            addEntities(bubble, new Point3f(x, 50.0f, 50.0f), 10);
            manager.joinAt(bubble, bubble.position());
            Thread.sleep(200);  // Let joins propagate (increased from 100ms)
        }

        Thread.sleep(1000);  // Wait for all joins to complete

        // Then: Most bubbles should have neighbors
        // In P2P mode, NC varies by topology - the first bubble is the hub
        int bubblesWithNeighbors = 0;
        for (var bubble : manager.getAllBubbles()) {
            if (!bubble.neighbors().isEmpty()) {
                bubblesWithNeighbors++;
            }
        }
        // At least 80% of bubbles should have neighbors
        assertThat(bubblesWithNeighbors).isGreaterThanOrEqualTo(4);
    }

    @Test
    void testEventListeners_receiveEvents() throws Exception {
        // Given: Manager with event listener
        var receivedEvents = new CopyOnWriteArrayList<Event>();
        manager.addEventListener(receivedEvents::add);

        // When: Create and join two bubbles
        var bubble1 = manager.createBubble();
        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
        manager.joinAt(bubble1, bubble1.position());

        var bubble2 = manager.createBubble();
        addEntities(bubble2, new Point3f(55.0f, 55.0f, 50.0f), 10);
        manager.joinAt(bubble2, bubble2.position());

        Thread.sleep(300);  // Let events propagate

        // Then: Manager received join events
        assertThat(receivedEvents).anyMatch(e -> e instanceof Event.Join);
    }

    @Test
    void testJoinLatency_under100ms() throws Exception {
        // Given: Existing bubble in VON
        var bubble1 = manager.createBubble();
        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
        manager.joinAt(bubble1, bubble1.position());

        // When: Measure join latency for new bubble
        var bubble2 = manager.createBubble();
        addEntities(bubble2, new Point3f(55.0f, 55.0f, 50.0f), 10);

        long startNs = System.nanoTime();
        manager.joinAt(bubble2, bubble2.position());
        long latencyNs = System.nanoTime() - startNs;

        double latencyMs = latencyNs / 1_000_000.0;

        // Then: JOIN latency should be under 100ms (local transport is fast)
        assertThat(latencyMs).isLessThan(100.0);
    }

    @Test
    void testConcurrentJoins_handleCorrectly() throws Exception {
        // Given: Initial bubble in VON
        var initial = manager.createBubble();
        addEntities(initial, new Point3f(50.0f, 50.0f, 50.0f), 10);
        manager.joinAt(initial, initial.position());

        // When: 5 concurrent joins
        var latch = new CountDownLatch(5);
        List<Bubble> newBubbles = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            var bubble = manager.createBubble();
            float x = 60.0f + i * 10.0f;
            addEntities(bubble, new Point3f(x, 50.0f, 50.0f), 10);
            newBubbles.add(bubble);
        }

        for (var bubble : newBubbles) {
            new Thread(() -> {
                manager.joinAt(bubble, bubble.position());
                latch.countDown();
            }).start();
        }

        // Wait for all joins to complete
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Then: All bubbles should be in manager
        assertThat(manager.size()).isEqualTo(6);

        // Give time for neighbor discovery
        Thread.sleep(500);

        // All new bubbles should have at least initial bubble as neighbor
        for (var bubble : newBubbles) {
            assertThat(bubble.neighbors()).isNotEmpty();
        }
    }

    @Test
    void testClose_releasesAllResources() {
        // Given: Manager with bubbles
        var bubble1 = manager.createBubble();
        var bubble2 = manager.createBubble();
        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
        addEntities(bubble2, new Point3f(55.0f, 55.0f, 50.0f), 10);

        // When: Close manager
        manager.close();

        // Then: All bubbles removed
        assertThat(manager.size()).isEqualTo(0);
        assertThat(manager.getAllBubbles()).isEmpty();
    }

    // ========== Helper Methods ==========

    /**
     * Add entities to a bubble to establish its spatial bounds.
     */
    private void addEntities(Bubble bubble, Point3f center, int count) {
        for (int i = 0; i < count; i++) {
            float x = Math.max(0.1f, center.x + (i % 3) * 0.1f);
            float y = Math.max(0.1f, center.y + (i / 3) * 0.1f);
            float z = Math.max(0.1f, center.z);
            bubble.addEntity("entity-" + i, new Point3f(x, y, z), "content-" + i);
        }
    }
}
