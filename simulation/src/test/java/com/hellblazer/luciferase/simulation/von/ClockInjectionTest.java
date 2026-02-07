/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Clock injection improvements in VON components.
 * <p>
 * Phase 1 of F4.2.4 VON + Simulation Integration plan (bead: Luciferase-mvba).
 * Validates deterministic time handling across:
 * <ul>
 *   <li>Manager.setClock() propagation to bubbles</li>
 *   <li>Manager constructor with Clock parameter</li>
 *   <li>Bubble.setClock() updates factory timestamps</li>
 *   <li>Cross-component clock consistency</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class ClockInjectionTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 16;
    private static final float AOI_RADIUS = 50.0f;

    private LocalServerTransport.Registry registry;
    private Manager manager;
    private TestClock testClock;

    @BeforeEach
    void setup() {
        registry = LocalServerTransport.Registry.create();
        testClock = new TestClock(1000L);  // Start at 1000ms for deterministic timestamps
    }

    @AfterEach
    void cleanup() {
        if (manager != null) manager.close();
        if (registry != null) registry.close();
    }

    /**
     * Test 1: Manager.setClock() propagates clock to all existing bubbles.
     */
    @Test
    void testManager_setClock_propagatesToBubbles() {
        // Given: Manager with bubbles using system clock
        manager = new Manager(registry, SPATIAL_LEVEL, TARGET_FRAME_MS, AOI_RADIUS);
        var bubble1 = manager.createBubble();
        var bubble2 = manager.createBubble();
        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 5);
        addEntities(bubble2, new Point3f(55.0f, 55.0f, 50.0f), 5);

        // When: Set clock on manager
        manager.setClock(testClock);

        // Then: Clock propagated to bubbles - verify by checking timestamps in messages
        testClock.setTime(5000L);

        // Create a Move message and verify timestamp uses the test clock
        var moveMsg = bubble1.createMoveMessage();
        assertThat(moveMsg.timestamp()).isEqualTo(5000L);

        var moveMsg2 = bubble2.createMoveMessage();
        assertThat(moveMsg2.timestamp()).isEqualTo(5000L);
    }

    /**
     * Test 2: Manager constructor with Clock uses that clock for factory and propagates to bubbles.
     */
    @Test
    void testManager_constructorWithClock_bubblesUseClock() {
        // Given: Manager constructed with test clock
        manager = new Manager(registry, SPATIAL_LEVEL, TARGET_FRAME_MS, AOI_RADIUS, testClock);

        // When: Create a bubble
        var bubble = manager.createBubble();
        addEntities(bubble, new Point3f(50.0f, 50.0f, 50.0f), 5);

        // Then: Bubble uses the injected clock
        testClock.setTime(3000L);
        var moveMsg = bubble.createMoveMessage();
        assertThat(moveMsg.timestamp()).isEqualTo(3000L);
    }

    /**
     * Test 3: Bubble.setClock() updates factory to use new clock for message timestamps.
     */
    @Test
    void testBubble_setClock_updatesFactoryTimestamps() {
        // Given: Bubble with default clock
        var id = UUID.randomUUID();
        var transport = registry.register(id);
        var bubble = new Bubble(id, SPATIAL_LEVEL, TARGET_FRAME_MS, transport);
        addEntities(bubble, new Point3f(50.0f, 50.0f, 50.0f), 5);

        // When: Set clock on bubble
        bubble.setClock(testClock);
        testClock.setTime(7777L);

        // Then: Message timestamps use the test clock
        var moveMsg = bubble.createMoveMessage();
        assertThat(moveMsg.timestamp()).isEqualTo(7777L);

        // And: Advance clock, verify message timestamp changes
        testClock.advance(1000);  // Now at 8777ms
        var moveMsg2 = bubble.createMoveMessage();
        assertThat(moveMsg2.timestamp()).isEqualTo(8777L);
    }

    /**
     * Test 4: Cross-component clock consistency - manager, bubbles, and messages all use same clock.
     * Verifies that Move messages broadcast between bubbles have correct timestamps.
     */
    @Test
    void testClockConsistency_acrossComponents() throws Exception {
        // Given: Manager with test clock and two connected bubbles
        manager = new Manager(registry, SPATIAL_LEVEL, TARGET_FRAME_MS, AOI_RADIUS, testClock);
        var bubble1 = manager.createBubble();
        var bubble2 = manager.createBubble();

        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
        addEntities(bubble2, new Point3f(55.0f, 55.0f, 50.0f), 10);

        // Join bubbles to establish neighbor relationship
        manager.joinAt(bubble1, bubble1.position());
        Thread.sleep(100);
        manager.joinAt(bubble2, bubble2.position());
        Thread.sleep(200);

        // Capture Move messages received by bubble1
        var receivedMessages = new CopyOnWriteArrayList<Message.Move>();
        var moveReceived = new CountDownLatch(1);

        bubble1.getTransport().onMessage(msg -> {
            if (msg instanceof Message.Move move) {
                receivedMessages.add(move);
                moveReceived.countDown();
            }
        });

        // When: Set a specific time and bubble2 broadcasts move
        testClock.setTime(9999L);
        bubble2.broadcastMove();

        // Then: Received message has correct timestamp
        assertThat(moveReceived.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedMessages).isNotEmpty();
        assertThat(receivedMessages.get(0).timestamp()).isEqualTo(9999L);
    }

    // ========== Helper Methods ==========

    /**
     * Add entities to a bubble to establish spatial bounds.
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
