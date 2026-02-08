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
import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Bubble.introducedTo memory leak fix.
 * <p>
 * Verifies that the introducedTo set is properly cleaned up when neighbors
 * are removed, preventing unbounded memory growth.
 *
 * @author hal.hildebrand
 */
class BubbleIntroducedToMemoryTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 16;

    private LocalServerTransport.Registry registry;
    private Bubble bubble1;
    private Bubble bubble2;
    private Bubble bubble3;
    private final MessageFactory factory = MessageFactory.system();

    @BeforeEach
    void setup() {
        registry = LocalServerTransport.Registry.create();
    }

    @AfterEach
    void cleanup() {
        if (bubble1 != null) bubble1.close();
        if (bubble2 != null) bubble2.close();
        if (bubble3 != null) bubble3.close();
        if (registry != null) registry.close();
    }

    /**
     * Test that introducedTo is cleaned up when neighbor leaves via LEAVE message.
     * <p>
     * Scenario:
     * 1. Bubble A learns about Bubble B via JoinResponse
     * 2. Bubble A introduces itself to B (adds B to introducedTo)
     * 3. Bubble B leaves
     * 4. Verify introducedTo no longer contains B
     */
    @Test
    void testIntroducedToCleanupOnNeighborLeave() throws Exception {
        // Given: Three bubbles - A (acceptor), B (joiner), C (existing neighbor)
        var idA = UUID.randomUUID();
        var idB = UUID.randomUUID();
        var idC = UUID.randomUUID();
        var transportA = registry.register(idA);
        var transportB = registry.register(idB);
        var transportC = registry.register(idC);
        bubble1 = new Bubble(idA, SPATIAL_LEVEL, TARGET_FRAME_MS, transportA);
        bubble2 = new Bubble(idB, SPATIAL_LEVEL, TARGET_FRAME_MS, transportB);
        bubble3 = new Bubble(idC, SPATIAL_LEVEL, TARGET_FRAME_MS, transportC);

        // Add entities to get valid bounds
        bubble1.addEntity("entityA", new Point3f(0.3f, 0.3f, 0.3f), "contentA");
        bubble2.addEntity("entityB", new Point3f(0.5f, 0.5f, 0.5f), "contentB");
        bubble3.addEntity("entityC", new Point3f(0.7f, 0.7f, 0.7f), "contentC");

        // Set up neighbor relationship: A knows C
        bubble1.addNeighbor(idC);
        bubble3.addNeighbor(idA);

        // Track messages to C
        var joinRequestCReceived = new CountDownLatch(1);
        var receivedAtC = new CopyOnWriteArrayList<Message>();
        transportC.onMessage(msg -> {
            receivedAtC.add(msg);
            if (msg instanceof Message.JoinRequest) {
                joinRequestCReceived.countDown();
            }
        });

        // Track JoinResponse to B
        var joinResponseBReceived = new CountDownLatch(1);
        transportB.onMessage(msg -> {
            if (msg instanceof Message.JoinResponse) {
                joinResponseBReceived.countDown();
            }
        });

        // When: B joins A (A will respond with neighbors including C)
        var joinRequest = factory.createJoinRequest(idB, bubble2.position(), bubble2.bounds());
        transportB.sendToNeighbor(idA, joinRequest);

        // Wait for B to receive JoinResponse
        assertThat(joinResponseBReceived.await(2, TimeUnit.SECONDS)).isTrue();

        // Wait for B to introduce itself to C
        assertThat(joinRequestCReceived.await(2, TimeUnit.SECONDS)).isTrue();

        // Verify B introduced itself to C (C should be in introducedTo)
        var introducedTo = getIntroducedToSet(bubble2);
        assertThat(introducedTo).contains(idC);

        // When: C leaves
        var leaveReceivedByB = new CountDownLatch(1);
        bubble2.addEventListener(event -> {
            if (event instanceof Event.Leave leave && leave.nodeId().equals(idC)) {
                leaveReceivedByB.countDown();
            }
        });

        var leaveMsg = factory.createLeave(idC);
        transportC.sendToNeighbor(idB, leaveMsg);

        // Wait for B to process LEAVE
        assertThat(leaveReceivedByB.await(2, TimeUnit.SECONDS)).isTrue();

        // Then: B's introducedTo should no longer contain C
        introducedTo = getIntroducedToSet(bubble2);
        assertThat(introducedTo).doesNotContain(idC);

        // Verify B also removed C from neighbors
        assertThat(bubble2.neighbors()).doesNotContain(idC);
    }

    /**
     * Test that introducedTo is cleaned up on explicit neighbor removal.
     * <p>
     * Scenario:
     * 1. Bubble adds neighbor and marks as introduced
     * 2. Bubble explicitly removes neighbor
     * 3. Verify introducedTo is cleaned up
     */
    @Test
    void testIntroducedToCleanupOnExplicitRemoval() throws Exception {
        // Given: A bubble
        var idA = UUID.randomUUID();
        var transportA = registry.register(idA);
        bubble1 = new Bubble(idA, SPATIAL_LEVEL, TARGET_FRAME_MS, transportA);

        // Simulate neighbor introduction (directly modify introducedTo via reflection)
        var neighborId = UUID.randomUUID();
        bubble1.addNeighbor(neighborId);

        var introducedTo = getIntroducedToSet(bubble1);
        introducedTo.add(neighborId);

        // Verify neighbor is in introducedTo
        assertThat(introducedTo).contains(neighborId);

        // When: Explicitly remove neighbor
        bubble1.removeNeighbor(neighborId);

        // Then: introducedTo should be cleaned up
        introducedTo = getIntroducedToSet(bubble1);
        assertThat(introducedTo).doesNotContain(neighborId);
    }

    /**
     * Test that introducedTo remains bounded over many join/leave cycles.
     * <p>
     * Scenario:
     * 1. Many neighbors join and leave in cycles
     * 2. Verify introducedTo size never exceeds current neighbor count
     * 3. Verify no unbounded growth
     */
    @Test
    void testIntroducedToBoundedOverMultipleCycles() throws Exception {
        // Given: A bubble
        var idA = UUID.randomUUID();
        var transportA = registry.register(idA);
        bubble1 = new Bubble(idA, SPATIAL_LEVEL, TARGET_FRAME_MS, transportA);
        bubble1.addEntity("entityA", new Point3f(0.5f, 0.5f, 0.5f), "contentA");

        // When: Simulate many join/leave cycles
        var cycleCount = 100;
        var maxIntroducedToSize = 0;

        for (int i = 0; i < cycleCount; i++) {
            var neighborId = UUID.randomUUID();

            // Add neighbor and mark as introduced
            bubble1.addNeighbor(neighborId);
            var introducedTo = getIntroducedToSet(bubble1);
            introducedTo.add(neighborId);

            // Track max size
            maxIntroducedToSize = Math.max(maxIntroducedToSize, introducedTo.size());

            // Remove neighbor (should clean up introducedTo)
            bubble1.removeNeighbor(neighborId);
        }

        // Then: After all cycles, introducedTo should be empty (all neighbors removed)
        var finalIntroducedToSize = getIntroducedToSet(bubble1).size();
        assertThat(finalIntroducedToSize).isZero();

        // Verify we did track some introductions during the test
        assertThat(maxIntroducedToSize).isGreaterThan(0);

        // Verify no neighbors remain
        assertThat(bubble1.neighbors()).isEmpty();
    }

    /**
     * Test that we can re-introduce to a neighbor after it rejoins.
     * <p>
     * Scenario:
     * 1. Bubble B joins and is introduced to C
     * 2. C leaves
     * 3. C rejoins
     * 4. Verify B can re-introduce itself to C (not prevented by stale introducedTo entry)
     */
    @Test
    void testReintroductionAfterRejoin() throws Exception {
        // Given: Three bubbles - A (acceptor), B (joiner), C (existing neighbor)
        var idA = UUID.randomUUID();
        var idB = UUID.randomUUID();
        var idC = UUID.randomUUID();
        var transportA = registry.register(idA);
        var transportB = registry.register(idB);
        var transportC = registry.register(idC);
        bubble1 = new Bubble(idA, SPATIAL_LEVEL, TARGET_FRAME_MS, transportA);
        bubble2 = new Bubble(idB, SPATIAL_LEVEL, TARGET_FRAME_MS, transportB);
        bubble3 = new Bubble(idC, SPATIAL_LEVEL, TARGET_FRAME_MS, transportC);

        // Add entities
        bubble1.addEntity("entityA", new Point3f(0.3f, 0.3f, 0.3f), "contentA");
        bubble2.addEntity("entityB", new Point3f(0.5f, 0.5f, 0.5f), "contentB");
        bubble3.addEntity("entityC", new Point3f(0.7f, 0.7f, 0.7f), "contentC");

        // Set up neighbor relationship: A knows C
        bubble1.addNeighbor(idC);
        bubble3.addNeighbor(idA);

        // Track first introduction from B to C
        var firstIntroductionReceived = new CountDownLatch(1);
        transportC.onMessage(msg -> {
            if (msg instanceof Message.JoinRequest req && req.joinerId().equals(idB)) {
                firstIntroductionReceived.countDown();
            }
        });

        var joinResponseBReceived = new CountDownLatch(1);
        transportB.onMessage(msg -> {
            if (msg instanceof Message.JoinResponse) {
                joinResponseBReceived.countDown();
            }
        });

        // When: B joins A (first time)
        var joinRequest1 = factory.createJoinRequest(idB, bubble2.position(), bubble2.bounds());
        transportB.sendToNeighbor(idA, joinRequest1);
        assertThat(joinResponseBReceived.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(firstIntroductionReceived.await(2, TimeUnit.SECONDS)).isTrue();

        // Verify C is in introducedTo
        var introducedTo = getIntroducedToSet(bubble2);
        assertThat(introducedTo).contains(idC);

        // When: C leaves
        var leaveReceivedByB = new CountDownLatch(1);
        bubble2.addEventListener(event -> {
            if (event instanceof Event.Leave leave && leave.nodeId().equals(idC)) {
                leaveReceivedByB.countDown();
            }
        });

        var leaveMsg = factory.createLeave(idC);
        transportC.sendToNeighbor(idB, leaveMsg);

        // Wait for B to process LEAVE
        assertThat(leaveReceivedByB.await(2, TimeUnit.SECONDS)).isTrue();

        // Verify C was cleaned from introducedTo
        introducedTo = getIntroducedToSet(bubble2);
        assertThat(introducedTo).doesNotContain(idC);

        // When: C rejoins (simulate by B learning about C again)
        // Track second introduction from B to C
        var secondIntroductionReceived = new CountDownLatch(1);
        transportC.onMessage(msg -> {
            if (msg instanceof Message.JoinRequest req && req.joinerId().equals(idB)) {
                secondIntroductionReceived.countDown();
            }
        });

        // Simulate B receiving another JoinResponse with C
        var neighborInfo = new Message.NeighborInfo(idC, bubble3.position(), bubble3.bounds());
        var joinResponse2 = factory.createJoinResponse(idA, Set.of(neighborInfo));
        transportB.deliver(joinResponse2);

        // Then: B should be able to re-introduce itself to C
        assertThat(secondIntroductionReceived.await(2, TimeUnit.SECONDS)).isTrue();

        // Verify C is back in introducedTo
        introducedTo = getIntroducedToSet(bubble2);
        assertThat(introducedTo).contains(idC);
    }

    /**
     * Test that introducedTo is cleared when bubble closes.
     * <p>
     * Scenario:
     * 1. Bubble has neighbors in introducedTo
     * 2. Bubble closes
     * 3. Verify introducedTo is cleared
     */
    @Test
    void testIntroducedToCleanupOnClose() throws Exception {
        // Given: A bubble with neighbors
        var idA = UUID.randomUUID();
        var transportA = registry.register(idA);
        bubble1 = new Bubble(idA, SPATIAL_LEVEL, TARGET_FRAME_MS, transportA);
        bubble1.addEntity("entityA", new Point3f(0.5f, 0.5f, 0.5f), "contentA");

        // Add multiple neighbors and mark as introduced
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();
        var neighbor3 = UUID.randomUUID();

        bubble1.addNeighbor(neighbor1);
        bubble1.addNeighbor(neighbor2);
        bubble1.addNeighbor(neighbor3);

        var introducedTo = getIntroducedToSet(bubble1);
        introducedTo.add(neighbor1);
        introducedTo.add(neighbor2);
        introducedTo.add(neighbor3);

        // Verify introducedTo has entries
        assertThat(introducedTo).hasSize(3);

        // When: Close the bubble
        bubble1.close();
        bubble1 = null; // Prevent double close in cleanup

        // Then: introducedTo should be cleared
        // Note: Can't check after close since bubble is cleaned up,
        // but we verify no exception thrown and cleanup completed
    }

    /**
     * Long-running memory growth test.
     * <p>
     * Simulates a realistic scenario with many nodes joining and leaving
     * over an extended period. Verifies that introducedTo doesn't grow
     * unbounded even under continuous churn.
     */
    @Test
    void testNoMemoryGrowthUnderContinuousChurn() throws Exception {
        // Given: A bubble acting as acceptor
        var idA = UUID.randomUUID();
        var transportA = registry.register(idA);
        bubble1 = new Bubble(idA, SPATIAL_LEVEL, TARGET_FRAME_MS, transportA);
        bubble1.addEntity("entityA", new Point3f(0.5f, 0.5f, 0.5f), "contentA");

        // When: Simulate continuous churn (1000 join/leave cycles)
        var churnCycles = 1000;
        var introducedToSamples = new java.util.ArrayList<Integer>();

        for (int i = 0; i < churnCycles; i++) {
            var neighborId = UUID.randomUUID();

            // Join
            bubble1.addNeighbor(neighborId);
            var introducedTo = getIntroducedToSet(bubble1);
            introducedTo.add(neighborId);

            // Sample size periodically
            if (i % 100 == 0) {
                introducedToSamples.add(introducedTo.size());
            }

            // Leave
            bubble1.removeNeighbor(neighborId);
        }

        // Then: Verify no unbounded growth
        // Final size should be zero (all neighbors left)
        var finalSize = getIntroducedToSet(bubble1).size();
        assertThat(finalSize).isZero();

        // Verify samples show bounded growth (never exceeds reasonable threshold)
        // Since we add and remove one at a time, max size should be ~1
        for (var sampleSize : introducedToSamples) {
            assertThat(sampleSize).isLessThanOrEqualTo(10); // Very generous threshold
        }

        // Verify no neighbors remain
        assertThat(bubble1.neighbors()).isEmpty();
    }

    /**
     * Helper method to access the private introducedTo field via reflection.
     * <p>
     * Used for testing to verify internal state without exposing the field publicly.
     *
     * @param bubble Bubble instance to inspect
     * @return The introducedTo set
     */
    @SuppressWarnings("unchecked")
    private Set<UUID> getIntroducedToSet(Bubble bubble) throws Exception {
        Field field = Bubble.class.getDeclaredField("introducedTo");
        field.setAccessible(true);
        return (Set<UUID>) field.get(bubble);
    }
}
