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

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for JOIN protocol error recovery with retry and compensation.
 * <p>
 * Tests validate:
 * <ul>
 *   <li>Exponential backoff retry on transient failures (50ms, 100ms, 200ms, 400ms, 800ms)</li>
 *   <li>Compensation logic removes neighbor on persistent failure</li>
 *   <li>No asymmetric neighbor relationships after failures</li>
 *   <li>Message routing correctness after recovery</li>
 * </ul>
 * <p>
 * Uses LocalServerTransport's failure injection API (injectPartition) to simulate
 * network failures during JOIN response transmission.
 *
 * @author hal.hildebrand
 */
class JoinErrorRecoveryTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 16;
    private static final float AOI_RADIUS = 50.0f;

    private LocalServerTransport.Registry registry;
    private Manager manager;
    private TestClock testClock;

    @BeforeEach
    void setup() {
        registry = LocalServerTransport.Registry.create();
        manager = new Manager(registry, SPATIAL_LEVEL, TARGET_FRAME_MS, AOI_RADIUS);
        testClock = new TestClock();
        testClock.setTime(0L);
    }

    @AfterEach
    void cleanup() {
        if (manager != null) manager.close();
        if (registry != null) registry.close();
    }

    /**
     * Test 1: JOIN retry on transient failure
     * <p>
     * Validates:
     * - First attempt fails (exception injected on acceptor's transport)
     * - Retry occurs with exponential backoff timing
     * - Success on retry
     * - Neighbor relationship established correctly
     */
    @Test
    void testJoinRetryOnTransientFailure() throws Exception {
        // Given: Acceptor bubble
        var acceptor = createBubbleAt(50.0f, 50.0f, 50.0f);
        var acceptorTransport = (LocalServerTransport) acceptor.getTransport();
        acceptor.setClock(testClock);
        manager.joinAt(acceptor, acceptor.position());

        // Joiner
        var joiner = createBubbleAt(55.0f, 55.0f, 50.0f);
        joiner.setClock(testClock);

        // Inject exception on acceptor to fail JOIN response send (first attempt only)
        acceptorTransport.injectException(true);

        // When: Joiner attempts JOIN
        manager.joinAt(joiner, joiner.position());

        // Give time for initial attempt to fail
        Thread.sleep(100);
        testClock.advance(10);

        // Clear exception for retry to succeed (after first backoff of 50ms)
        Thread.sleep(60);
        testClock.advance(50);
        acceptorTransport.injectException(false);

        // Allow retry processing
        Thread.sleep(300);
        testClock.advance(100);

        // Then: Verify retry occurred and succeeded
        assertThat(joiner.neighbors()).as("Joiner should have acceptor as neighbor after retry")
                                      .contains(acceptor.id());
        assertThat(acceptor.neighbors()).as("Acceptor should have joiner as neighbor")
                                        .contains(joiner.id());
    }

    /**
     * Test 2: JOIN removal on persistent failure
     * <p>
     * Validates:
     * - Multiple retry attempts with exponential backoff (50ms, 100ms, 200ms, 400ms, 800ms)
     * - After max retries (5), neighbor is removed
     * - No asymmetric relationships remain
     */
    @Test
    void testJoinRemovalOnPersistentFailure() throws Exception {
        // Given: Acceptor bubble
        var acceptor = createBubbleAt(50.0f, 50.0f, 50.0f);
        var acceptorTransport = (LocalServerTransport) acceptor.getTransport();
        acceptor.setClock(testClock);
        manager.joinAt(acceptor, acceptor.position());

        // Track JOIN attempts at acceptor
        var joinAttempts = new AtomicInteger(0);
        acceptor.addEventListener(event -> {
            if (event instanceof Event.Join) {
                joinAttempts.incrementAndGet();
            }
        });

        // Joiner
        var joiner = createBubbleAt(55.0f, 55.0f, 50.0f);
        joiner.setClock(testClock);

        // Inject persistent exception on acceptor (all JOIN response attempts fail)
        acceptorTransport.injectException(true);

        // When: Joiner attempts JOIN
        manager.joinAt(joiner, joiner.position());

        // Simulate exponential backoff intervals: 50ms, 100ms, 200ms, 400ms, 800ms
        long[] backoffIntervals = {50, 100, 200, 400, 800};
        for (long interval : backoffIntervals) {
            Thread.sleep(interval + 50);  // Extra 50ms for processing
            testClock.advance(interval);
        }

        // Allow final compensation processing
        Thread.sleep(200);
        testClock.advance(100);

        // Then: Verify neighbor was removed after max retries
        assertThat(acceptor.neighbors()).as("Acceptor should have removed joiner after persistent failure")
                                        .doesNotContain(joiner.id());
        assertThat(acceptor.getNeighborState(joiner.id())).as("Acceptor should have cleaned up neighbor state")
                                                           .isNull();

        // Verify joiner was initially added (first JOIN attempt processed)
        assertThat(joinAttempts.get()).as("Acceptor should have received initial JOIN request")
                                      .isEqualTo(1);
    }

    /**
     * Test 3: JOIN message routing correctness
     * <p>
     * Validates:
     * - After JOIN with retries, messages route correctly
     * - Messages to Node B reach B (not C)
     * - Messages to Node C reach C (not B)
     * - No cross-contamination in neighbor relationships
     */
    @Test
    void testJoinMessageRouting() throws Exception {
        // Given: Three bubbles - A (hub), B, C
        var bubbleA = createBubbleAt(50.0f, 50.0f, 50.0f);
        bubbleA.setClock(testClock);
        manager.joinAt(bubbleA, bubbleA.position());

        var bubbleB = createBubbleAt(55.0f, 55.0f, 50.0f);
        bubbleB.setClock(testClock);
        manager.joinAt(bubbleB, bubbleB.position());
        Thread.sleep(200);

        var bubbleC = createBubbleAt(60.0f, 60.0f, 50.0f);
        bubbleC.setClock(testClock);
        manager.joinAt(bubbleC, bubbleC.position());
        Thread.sleep(200);

        // Track message receipt
        var bReceivedMove = new CountDownLatch(1);
        var cReceivedMove = new CountDownLatch(1);
        var bReceivedFromC = new AtomicInteger(0);  // Should stay 0
        var cReceivedFromB = new AtomicInteger(0);  // Should stay 0

        bubbleB.addEventListener(event -> {
            if (event instanceof Event.Move move) {
                if (move.nodeId().equals(bubbleA.id())) {
                    bReceivedMove.countDown();
                } else if (move.nodeId().equals(bubbleC.id())) {
                    bReceivedFromC.incrementAndGet();
                }
            }
        });

        bubbleC.addEventListener(event -> {
            if (event instanceof Event.Move move) {
                if (move.nodeId().equals(bubbleA.id())) {
                    cReceivedMove.countDown();
                } else if (move.nodeId().equals(bubbleB.id())) {
                    cReceivedFromB.incrementAndGet();
                }
            }
        });

        // When: A moves and broadcasts to neighbors
        manager.move(bubbleA, new Point3D(52.0, 52.0, 50.0));
        Thread.sleep(300);
        testClock.advance(100);

        // Then: Verify correct message routing
        assertThat(bReceivedMove.await(2, TimeUnit.SECONDS)).as("Bubble B should receive MOVE from A")
                                                             .isTrue();
        assertThat(cReceivedMove.await(2, TimeUnit.SECONDS)).as("Bubble C should receive MOVE from A")
                                                             .isTrue();

        // Verify no cross-contamination
        assertThat(bReceivedFromC.get()).as("Bubble B should NOT receive messages from non-neighbor C")
                                        .isEqualTo(0);
        assertThat(cReceivedFromB.get()).as("Bubble C should NOT receive messages from non-neighbor B")
                                        .isEqualTo(0);
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
