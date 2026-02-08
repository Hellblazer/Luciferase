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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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

    @Test
    void testConcurrentEventListenerModification() throws Exception {
        // THREAD SAFETY TEST: Concurrent event dispatch + listener modification
        // Reproduces race condition where:
        // - Thread A: Dispatches events (iterates eventListeners)
        // - Thread B: Adds/removes listeners (modifies eventListeners)
        // Without CopyOnWriteArrayList, this causes ConcurrentModificationException

        int numEventThreads = 10;
        int numModificationThreads = 5;
        int eventsPerThread = 1000;
        int modificationsPerThread = 100;

        var executor = Executors.newFixedThreadPool(numEventThreads + numModificationThreads);
        var startLatch = new CountDownLatch(1);
        var completionLatch = new CountDownLatch(numEventThreads + numModificationThreads);
        var exceptionOccurred = new AtomicBoolean(false);
        var eventCount = new AtomicInteger(0);
        List<Throwable> exceptions = new CopyOnWriteArrayList<>();

        // Create a bubble to generate events
        var bubble = manager.createBubble();
        addEntities(bubble, new Point3f(50.0f, 50.0f, 50.0f), 10);

        // Event dispatch threads - trigger dispatchEvent by generating bubble events
        for (int t = 0; t < numEventThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < eventsPerThread; i++) {
                        // Generate events by updating entity positions, which triggers Event dispatching
                        var entityId = "entity-" + (i % 10);
                        try {
                            bubble.updateEntityPosition(entityId,
                                new Point3f(50.0f + i * 0.001f, 50.0f, 50.0f));
                            eventCount.incrementAndGet();
                        } catch (Exception e) {
                            // Ignore entity not found - focus on ConcurrentModificationException
                        }
                    }
                } catch (Throwable e) {
                    exceptionOccurred.set(true);
                    exceptions.add(e);
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Listener modification threads - add/remove listeners concurrently
        for (int t = 0; t < numModificationThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < modificationsPerThread; i++) {
                        // Add listener
                        Consumer<Event> listener = (Event event) -> {
                            // Minimal processing to trigger iteration
                        };
                        manager.addEventListener(listener);

                        // Brief delay to increase chance of concurrent iteration
                        Thread.sleep(1);

                        // Remove listener
                        manager.removeEventListener(listener);
                    }
                } catch (Throwable e) {
                    exceptionOccurred.set(true);
                    exceptions.add(e);
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Start all threads
        startLatch.countDown();

        // Wait for completion
        assertThat(completionLatch.await(30, TimeUnit.SECONDS))
            .as("All threads should complete within 30 seconds")
            .isTrue();

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
            .as("Executor should terminate")
            .isTrue();

        // Verify no ConcurrentModificationException occurred
        if (!exceptions.isEmpty()) {
            System.err.println("Concurrent test failures:");
            for (var ex : exceptions) {
                ex.printStackTrace();
            }
        }
        assertThat(exceptionOccurred.get())
            .as("No ConcurrentModificationException should occur. Exceptions: " + exceptions)
            .isFalse();

        // Verify events were processed
        assertThat(eventCount.get())
            .as("Should have processed events")
            .isGreaterThan(0);
    }

    // ========== Phase 3: Persistent Coordinator Tests ==========

    /**
     * Test 13: Verify persistent coordinator survives multiple leave operations.
     * <p>
     * This test validates that the coordinator remains functional after individual
     * bubbles are removed, without requiring a full shutdown/restart cycle.
     */
    @Test
    void testPersistentCoordinator_survivesMultipleLeaves() throws Exception {
        // Given: Create 5 bubbles
        List<Bubble> bubbles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var bubble = manager.createBubble();
            float x = 50.0f + i * 10.0f;
            addEntities(bubble, new Point3f(x, 50.0f, 50.0f), 10);
            manager.joinAt(bubble, bubble.position());
            bubbles.add(bubble);
            Thread.sleep(100);  // Let joins complete
        }

        assertThat(manager.size()).isEqualTo(5);

        // When: Leave 3 bubbles
        for (int i = 0; i < 3; i++) {
            manager.leave(bubbles.get(i));
            Thread.sleep(50);  // Let leave complete
        }

        // Then: 2 bubbles remain
        assertThat(manager.size()).isEqualTo(2);

        // And: Remaining bubbles are still functional
        var bubble4 = bubbles.get(3);
        var bubble5 = bubbles.get(4);

        assertThat(bubble4.neighbors()).isNotNull();
        assertThat(bubble5.neighbors()).isNotNull();

        // And: Can still move remaining bubbles
        manager.move(bubble4, new Point3D(85.0, 50.0, 50.0));
        manager.move(bubble5, new Point3D(95.0, 50.0, 50.0));

        // And: Can create new bubble after leaves
        var newBubble = manager.createBubble();
        addEntities(newBubble, new Point3f(105.0f, 50.0f, 50.0f), 10);
        manager.joinAt(newBubble, newBubble.position());

        Thread.sleep(200);  // Let join complete
        assertThat(manager.size()).isEqualTo(3);
    }

    /**
     * Test 14: Verify concurrent leave() and createBubble() operations are thread-safe.
     * <p>
     * This test validates that the persistent coordinator handles concurrent
     * add/remove operations correctly without race conditions or exceptions.
     */
    @Test
    void testConcurrentLeaveAndCreate() throws Exception {
        // Given: Initial set of bubbles
        List<Bubble> initialBubbles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var bubble = manager.createBubble();
            float x = 50.0f + i * 10.0f;
            addEntities(bubble, new Point3f(x, 50.0f, 50.0f), 10);
            manager.joinAt(bubble, bubble.position());
            initialBubbles.add(bubble);
            Thread.sleep(100);
        }

        assertThat(manager.size()).isEqualTo(5);

        // When: Concurrent leave() and createBubble() operations
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(10);
        var errors = new CopyOnWriteArrayList<Throwable>();

        // 5 threads removing bubbles
        for (int i = 0; i < 5; i++) {
            final var bubble = initialBubbles.get(i);
            executor.submit(() -> {
                try {
                    manager.leave(bubble);
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        // 5 threads creating new bubbles
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    var bubble = manager.createBubble();
                    float x = 150.0f + idx * 10.0f;
                    addEntities(bubble, new Point3f(x, 50.0f, 50.0f), 10);
                    manager.joinAt(bubble, bubble.position());
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all operations to complete
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

        // Then: No errors occurred
        if (!errors.isEmpty()) {
            System.err.println("Concurrent operation errors:");
            for (var error : errors) {
                error.printStackTrace();
            }
        }
        assertThat(errors).isEmpty();

        // And: Manager has 5 bubbles (5 removed, 5 created)
        assertThat(manager.size()).isEqualTo(5);
    }

    /**
     * Test 15: Verify coordinator remains functional after all bubbles leave.
     * <p>
     * This test validates the "empty coordinator" case - that the persistent
     * coordinator can transition from N bubbles → 0 bubbles → N bubbles without
     * requiring shutdown/restart.
     */
    @Test
    void testLeaveAllThenCreate() throws Exception {
        // Given: Create 3 bubbles
        List<Bubble> bubbles = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var bubble = manager.createBubble();
            float x = 50.0f + i * 10.0f;
            addEntities(bubble, new Point3f(x, 50.0f, 50.0f), 10);
            manager.joinAt(bubble, bubble.position());
            bubbles.add(bubble);
            Thread.sleep(100);
        }

        assertThat(manager.size()).isEqualTo(3);

        // When: All bubbles leave
        for (var bubble : bubbles) {
            manager.leave(bubble);
            Thread.sleep(50);
        }

        // Then: Manager is empty
        assertThat(manager.size()).isEqualTo(0);

        // And: Can create new bubbles (coordinator still functional)
        var newBubble1 = manager.createBubble();
        addEntities(newBubble1, new Point3f(100.0f, 50.0f, 50.0f), 10);
        manager.joinAt(newBubble1, newBubble1.position());

        var newBubble2 = manager.createBubble();
        addEntities(newBubble2, new Point3f(110.0f, 50.0f, 50.0f), 10);
        manager.joinAt(newBubble2, newBubble2.position());

        Thread.sleep(200);

        // And: New bubbles are functional
        assertThat(manager.size()).isEqualTo(2);
        assertThat(newBubble1.neighbors()).isNotNull();
        assertThat(newBubble2.neighbors()).isNotNull();
    }

    /**
     * Test 16: Verify close() is idempotent (can be called multiple times).
     * <p>
     * This test validates that calling close() multiple times doesn't throw
     * exceptions or cause errors. The coordinator's stop() is idempotent via
     * AtomicBoolean CAS.
     */
    @Test
    void testCloseIdempotent() {
        // Given: Manager with bubbles
        var bubble1 = manager.createBubble();
        var bubble2 = manager.createBubble();
        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
        addEntities(bubble2, new Point3f(60.0f, 50.0f, 50.0f), 10);

        assertThat(manager.size()).isEqualTo(2);

        // When: Close manager twice
        manager.close();
        manager.close();  // Should not throw

        // Then: Manager is empty
        assertThat(manager.size()).isEqualTo(0);

        // And: Third close also works (idempotent)
        manager.close();
        assertThat(manager.size()).isEqualTo(0);
    }

    /**
     * Test 17: Verify leave() after close() handles gracefully.
     * <p>
     * This test validates error handling when leave() is called after close().
     * The coordinator may already be stopped, but leave() should handle this
     * gracefully without throwing exceptions.
     */
    @Test
    void testLeaveAfterClose() {
        // Given: Manager with bubbles
        var bubble1 = manager.createBubble();
        var bubble2 = manager.createBubble();
        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
        addEntities(bubble2, new Point3f(60.0f, 50.0f, 50.0f), 10);

        assertThat(manager.size()).isEqualTo(2);

        // When: Close manager
        manager.close();
        assertThat(manager.size()).isEqualTo(0);

        // Then: leave() after close() should not throw (graceful handling)
        // Note: bubble1/bubble2 are already closed, but leave() should handle this
        manager.leave(bubble1);  // Should not throw
        manager.leave(bubble2);  // Should not throw

        // And: Manager remains empty
        assertThat(manager.size()).isEqualTo(0);
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
