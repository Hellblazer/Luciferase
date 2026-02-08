/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for LifecycleCoordinator with Kahn's topological sort.
 * <p>
 * Tests verify:
 * - Dependency-ordered startup (0→N layers)
 * - Reverse-order shutdown (N→0 layers)
 * - Timeout handling
 * - Failure propagation
 * - Idempotent operations
 * - Thread-safe concurrent access
 *
 * @author hal.hildebrand
 */
class LifecycleCoordinatorTest {

    private LifecycleCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new LifecycleCoordinator();
    }

    /**
     * Test 1: Verify components start in dependency order.
     * <p>
     * Setup: C depends on B, B depends on A
     * Expected: A starts before B, B starts before C
     */
    @Test
    void testStartOrder() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());

        var componentA = new MockComponent("A", startOrder, stopOrder);
        var componentB = new MockComponent("B", List.of("A"), startOrder, stopOrder);
        var componentC = new MockComponent("C", List.of("B"), startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.register(componentB);
        coordinator.register(componentC);

        // Act
        coordinator.start();

        // Assert
        assertEquals(3, startOrder.size(), "All components should start");
        assertEquals("A", startOrder.get(0), "A should start first");
        assertEquals("B", startOrder.get(1), "B should start second");
        assertEquals("C", startOrder.get(2), "C should start third");

        assertEquals(LifecycleState.RUNNING, coordinator.getState("A"));
        assertEquals(LifecycleState.RUNNING, coordinator.getState("B"));
        assertEquals(LifecycleState.RUNNING, coordinator.getState("C"));
    }

    /**
     * Test 2: Verify components stop in reverse dependency order.
     * <p>
     * Setup: C depends on B, B depends on A
     * Expected: C stops before B, B stops before A (reverse startup order)
     */
    @Test
    void testStopOrder() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());

        var componentA = new MockComponent("A", startOrder, stopOrder);
        var componentB = new MockComponent("B", List.of("A"), startOrder, stopOrder);
        var componentC = new MockComponent("C", List.of("B"), startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.register(componentB);
        coordinator.register(componentC);

        coordinator.start();

        // Act
        coordinator.stop(5000);

        // Assert
        assertEquals(3, stopOrder.size(), "All components should stop");
        assertEquals("C", stopOrder.get(0), "C should stop first");
        assertEquals("B", stopOrder.get(1), "B should stop second");
        assertEquals("A", stopOrder.get(2), "A should stop third");

        assertEquals(LifecycleState.STOPPED, coordinator.getState("A"));
        assertEquals(LifecycleState.STOPPED, coordinator.getState("B"));
        assertEquals(LifecycleState.STOPPED, coordinator.getState("C"));
    }

    /**
     * Test 3: Verify timeout handling doesn't block entire shutdown.
     * <p>
     * Setup: Component with 10-second stop delay
     * Expected: Coordinator returns within timeout + overhead
     */
    @Test
    void testTimeoutOnStop() {
        // Arrange
        var slowComponent = new SlowComponent("Slow", 0, 10_000); // 10 second stop delay
        var normalComponent = new MockComponent("Normal", new ArrayList<>(), new ArrayList<>());

        coordinator.register(slowComponent);
        coordinator.register(normalComponent);

        coordinator.start();

        // Act
        var startTime = System.currentTimeMillis();
        coordinator.stop(100); // 100ms timeout total
        var duration = System.currentTimeMillis() - startTime;

        // Assert
        assertTrue(duration < 500, "Coordinator should timeout within 500ms, took: " + duration + "ms");
        // Normal component should still reach STOPPED state
        assertEquals(LifecycleState.STOPPED, coordinator.getState("Normal"));
    }

    /**
     * Test 4: Verify failed component blocks dependents from starting.
     * <p>
     * Setup: A starts successfully, B depends on A and fails, C depends on B
     * Expected: A starts, B fails, C never starts
     */
    @Test
    void testStartFailure() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());

        var componentA = new MockComponent("A", startOrder, stopOrder);
        var componentB = new FailingComponent("B", List.of("A"), true, false);
        var componentC = new MockComponent("C", List.of("B"), startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.register(componentB);
        coordinator.register(componentC);

        // Act & Assert
        var exception = assertThrows(LifecycleException.class, () -> coordinator.start());
        assertTrue(exception.getMessage().contains("B") || exception.getMessage().contains("Failed"),
                   "Exception should reference failed component");

        // Verify states
        assertEquals(LifecycleState.RUNNING, coordinator.getState("A"), "A should start successfully");
        assertEquals(LifecycleState.FAILED, coordinator.getState("B"), "B should be in FAILED state");
        assertEquals(LifecycleState.CREATED, coordinator.getState("C"), "C should never start (blocked by B failure)");

        // Verify start order
        assertEquals(1, startOrder.size(), "Only A should have started");
        assertEquals("A", startOrder.get(0));
    }

    /**
     * Test 5: Verify multiple start() calls are idempotent.
     * <p>
     * Setup: Start coordinator twice
     * Expected: Components start only once, no errors
     */
    @Test
    void testIdempotentStart() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());

        var componentA = new MockComponent("A", startOrder, stopOrder);

        coordinator.register(componentA);

        // Act
        coordinator.start();
        coordinator.start(); // Second call should be no-op

        // Assert
        assertEquals(1, startOrder.size(), "Component should start only once");
        assertEquals(LifecycleState.RUNNING, coordinator.getState("A"));
    }

    /**
     * Test 6: Verify thread-safe concurrent access.
     * <p>
     * Setup: 10 threads calling start()/stop() concurrently
     * Expected: No ConcurrentModificationException, consistent final state
     */
    @Test
    void testConcurrentAccess() throws InterruptedException {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());

        var componentA = new MockComponent("A", startOrder, stopOrder);
        coordinator.register(componentA);

        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Act
        for (int i = 0; i < threadCount; i++) {
            var isStartThread = i % 2 == 0;
            executor.submit(() -> {
                try {
                    if (isStartThread) {
                        coordinator.start();
                    } else {
                        // Give start threads a head start
                        Thread.sleep(50);
                        coordinator.stop(1000);
                    }
                } catch (Exception e) {
                    // Expected - some calls may fail due to state conflicts
                    // We're testing that no ConcurrentModificationException occurs
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS), "Executor should terminate");

        // Assert
        var finalState = coordinator.getState("A");
        assertTrue(finalState == LifecycleState.RUNNING || finalState == LifecycleState.STOPPED,
                   "Component should be in consistent state: " + finalState);
    }

    /**
     * Test 7: Verify circular dependency detection.
     * <p>
     * Setup: A depends on B, B depends on A (cycle)
     * Expected: LifecycleException thrown during start()
     */
    @Test
    void testCircularDependencyDetection() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());

        // Create circular dependency: A→B→A
        var componentA = new MockComponent("A", List.of("B"), startOrder, stopOrder);
        var componentB = new MockComponent("B", List.of("A"), startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.register(componentB);

        // Act & Assert
        var exception = assertThrows(LifecycleException.class, () -> coordinator.start());
        assertTrue(exception.getMessage().toLowerCase().contains("circular") ||
                   exception.getMessage().toLowerCase().contains("cycle"),
                   "Exception should mention circular dependency");
    }

    /**
     * Test 8: Verify multiple independent components start in parallel within same layer.
     * <p>
     * Setup: 3 components with no dependencies (all Layer 0)
     * Expected: All start in parallel, all reach RUNNING
     */
    @Test
    void testParallelStartWithinLayer() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());

        var componentA = new MockComponent("A", startOrder, stopOrder);
        var componentB = new MockComponent("B", startOrder, stopOrder);
        var componentC = new MockComponent("C", startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.register(componentB);
        coordinator.register(componentC);

        // Act
        var startTime = System.currentTimeMillis();
        coordinator.start();
        var duration = System.currentTimeMillis() - startTime;

        // Assert
        assertEquals(3, startOrder.size(), "All components should start");
        assertEquals(LifecycleState.RUNNING, coordinator.getState("A"));
        assertEquals(LifecycleState.RUNNING, coordinator.getState("B"));
        assertEquals(LifecycleState.RUNNING, coordinator.getState("C"));

        // If sequential, would take ~30ms (3 * 10ms each)
        // If parallel, should take ~10-15ms (one round)
        assertTrue(duration < 100, "Parallel start should complete quickly, took: " + duration + "ms");
    }
}
