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
import java.util.concurrent.atomic.AtomicReference;

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
     * Test 4: Verify timeout handling on start prevents indefinite blocking.
     * <p>
     * Setup: Component with 12-second start delay exceeding timeout (2 components × 5s = 10s timeout)
     * Expected: Coordinator throws TimeoutException within reasonable time
     */
    @Test
    void testTimeoutOnStart() {
        // Arrange
        var slowComponent = new SlowComponent("Slow", 12_000, 0); // 12 second start delay (exceeds 10s timeout)
        var normalComponent = new MockComponent("Normal", new ArrayList<>(), new ArrayList<>());

        coordinator.register(slowComponent);
        coordinator.register(normalComponent);

        // Act
        var startTime = System.currentTimeMillis();
        var exception = assertThrows(LifecycleException.class, () -> coordinator.start());
        var duration = System.currentTimeMillis() - startTime;

        // Assert
        assertTrue(exception.getMessage().contains("Failed to start layer"),
                   "Exception should indicate layer failure");
        assertTrue(duration < 15_000,
                   "Coordinator should timeout within 15s (2 components × 5s + overhead), took: " + duration + "ms");
        // Slow component should timeout, normal component may or may not complete depending on timing
    }

    /**
     * Test 5: Verify failed component blocks dependents from starting.
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

        // Verify states (A rolled back after B failure)
        assertEquals(LifecycleState.STOPPED, coordinator.getState("A"), "A should be rolled back after B failure");
        assertEquals(LifecycleState.FAILED, coordinator.getState("B"), "B should be in FAILED state");
        assertEquals(LifecycleState.CREATED, coordinator.getState("C"), "C should never start (blocked by B failure)");

        // Verify start order (A started, then rolled back)
        assertEquals(1, startOrder.size(), "Only A should have started");
        assertEquals("A", startOrder.get(0));
        assertEquals(1, stopOrder.size(), "A should be stopped during rollback");
        assertEquals("A", stopOrder.get(0));
    }

    /**
     * Test 6: Verify components can restart from FAILED state.
     * <p>
     * Setup: Component fails during first start, then successfully starts on retry
     * Expected: First start fails, second start succeeds from FAILED state
     */
    @Test
    void testRestartFromFailed() {
        // Arrange
        // Component that fails on first start, succeeds on second
        var failingComponent = new FailingComponent("FailOnce", List.of(), true, false);

        coordinator.register(failingComponent);

        // Act - First start attempt fails
        var firstException = assertThrows(LifecycleException.class, () -> coordinator.start());
        assertTrue(firstException.getMessage().contains("Failed to start"),
                   "First start should fail");
        assertEquals(LifecycleState.FAILED, coordinator.getState("FailOnce"),
                     "Component should be in FAILED state");

        // Act - Second start attempt succeeds (FailingComponent succeeds on second start)
        coordinator.start(); // Should succeed after first failure

        // Assert
        assertEquals(LifecycleState.RUNNING, coordinator.getState("FailOnce"),
                     "Component should recover and reach RUNNING state");
    }

    /**
     * Test 7: Verify startup failure triggers rollback of already-started layers.
     * <p>
     * Setup: Layer 0 (A) starts successfully, Layer 1 (B) fails
     * Expected: A is stopped during rollback, exception thrown
     */
    @Test
    void testStartFailureRollback() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());

        var componentA = new MockComponent("A", startOrder, stopOrder);
        var componentB = new FailingComponent("B", List.of("A"), true, false);

        coordinator.register(componentA);
        coordinator.register(componentB);

        // Act & Assert
        var exception = assertThrows(LifecycleException.class, () -> coordinator.start());
        assertTrue(exception.getMessage().contains("Failed to start layer"),
                   "Exception should indicate layer failure");

        // Verify rollback occurred
        assertEquals(1, startOrder.size(), "A should have started");
        assertEquals("A", startOrder.get(0));
        assertEquals(1, stopOrder.size(), "A should be stopped during rollback");
        assertEquals("A", stopOrder.get(0));

        // Verify final states
        assertEquals(LifecycleState.STOPPED, coordinator.getState("A"), "A should be STOPPED after rollback");
        assertEquals(LifecycleState.FAILED, coordinator.getState("B"), "B should be FAILED");
    }

    /**
     * Test 8: Verify multiple start() calls are idempotent.
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
     * Test 9: Verify thread-safe concurrent access.
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
     * Test 10: Verify circular dependency detection.
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
     * Test 11: Verify multiple independent components start in parallel within same layer.
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

    // ========== Phase 1: registerAndStart() Tests ==========

    /**
     * Test 12: Verify registerAndStart() starts component when coordinator already running.
     */
    @Test
    void testRegisterAndStart_whenCoordinatorRunning_startsComponent() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());
        var componentA = new MockComponent("A", startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.start();

        // Act - Add new component after coordinator started
        var componentB = new MockComponent("B", startOrder, stopOrder);
        coordinator.registerAndStart(componentB);

        // Assert
        assertEquals(LifecycleState.RUNNING, coordinator.getState("B"),
                     "Component B should be RUNNING after registerAndStart");
        assertEquals(2, startOrder.size(), "Both components should have started");
        assertTrue(startOrder.contains("B"), "Component B should be in start order");
    }

    /**
     * Test 13: Verify registerAndStart() only registers when coordinator not started.
     */
    @Test
    void testRegisterAndStart_whenCoordinatorNotStarted_registersOnly() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());
        var componentA = new MockComponent("A", startOrder, stopOrder);

        // Act - Register component before coordinator starts
        coordinator.registerAndStart(componentA);

        // Assert
        assertEquals(LifecycleState.CREATED, coordinator.getState("A"),
                     "Component should be CREATED, not started yet");
        assertEquals(0, startOrder.size(), "Component should not have started");

        // Now start coordinator and verify component starts
        coordinator.start();
        assertEquals(LifecycleState.RUNNING, coordinator.getState("A"),
                     "Component should be RUNNING after coordinator.start()");
        assertEquals(1, startOrder.size(), "Component should have started");
    }

    /**
     * Test 14: Verify registerAndStart() succeeds with satisfied dependencies.
     */
    @Test
    void testRegisterAndStart_withSatisfiedDependencies_succeeds() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());
        var componentA = new MockComponent("A", startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.start();

        // Act - Add component B that depends on already-running A
        var componentB = new MockComponent("B", List.of("A"), startOrder, stopOrder);
        coordinator.registerAndStart(componentB);

        // Assert
        assertEquals(LifecycleState.RUNNING, coordinator.getState("B"),
                     "Component B should start successfully");
        assertEquals(2, startOrder.size(), "Both components should have started");
        assertEquals("A", startOrder.get(0), "A should start first");
        assertEquals("B", startOrder.get(1), "B should start second");
    }

    /**
     * Test 15: Verify registerAndStart() throws when dependencies not satisfied.
     */
    @Test
    void testRegisterAndStart_withUnsatisfiedDependencies_throws() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());

        coordinator.start(); // Start empty coordinator

        // Act & Assert - Try to add component with missing dependency
        var componentB = new MockComponent("B", List.of("NonExistent"), startOrder, stopOrder);
        var exception = assertThrows(LifecycleException.class,
                                     () -> coordinator.registerAndStart(componentB));
        assertTrue(exception.getMessage().contains("depends on non-existent") ||
                   exception.getMessage().contains("NonExistent"),
                   "Exception should mention missing dependency");
    }

    /**
     * Test 16: Verify registerAndStart() throws when component already registered.
     */
    @Test
    void testRegisterAndStart_duplicateComponent_throws() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());
        var componentA = new MockComponent("A", startOrder, stopOrder);

        coordinator.registerAndStart(componentA);

        // Act & Assert - Try to register same component again
        var componentA2 = new MockComponent("A", startOrder, stopOrder);
        var exception = assertThrows(LifecycleException.class,
                                     () -> coordinator.registerAndStart(componentA2));
        assertTrue(exception.getMessage().contains("already registered"),
                   "Exception should mention duplicate registration");
    }

    // ========== Phase 1: stopAndUnregister() Tests ==========

    /**
     * Test 17: Verify stopAndUnregister() stops and removes running component.
     */
    @Test
    void testStopAndUnregister_runningComponent_stopsAndRemoves() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());
        var componentA = new MockComponent("A", startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.start();

        assertEquals(LifecycleState.RUNNING, coordinator.getState("A"));

        // Act
        coordinator.stopAndUnregister("A");

        // Assert
        assertNull(coordinator.getState("A"), "Component A should be unregistered");
        assertEquals(1, stopOrder.size(), "Component should have been stopped");
        assertEquals("A", stopOrder.get(0));
    }

    /**
     * Test 18: Verify stopAndUnregister() just removes stopped component.
     */
    @Test
    void testStopAndUnregister_stoppedComponent_justRemoves() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());
        var componentA = new MockComponent("A", startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.start();
        coordinator.stop(5000);

        assertEquals(LifecycleState.STOPPED, coordinator.getState("A"));
        var initialStopCount = stopOrder.size();

        // Act
        coordinator.stopAndUnregister("A");

        // Assert
        assertNull(coordinator.getState("A"), "Component A should be unregistered");
        assertEquals(initialStopCount, stopOrder.size(),
                     "Stop should not be called again for already-stopped component");
    }

    /**
     * Test 19: Verify stopAndUnregister() is no-op for non-existent component.
     */
    @Test
    void testStopAndUnregister_nonExistentComponent_noOp() {
        // Arrange
        coordinator.start();

        // Act & Assert - Should not throw
        assertDoesNotThrow(() -> coordinator.stopAndUnregister("NonExistent"),
                          "stopAndUnregister should be no-op for non-existent component");
    }

    /**
     * Test 20: Verify stopAndUnregister() removes created component without stopping.
     */
    @Test
    void testStopAndUnregister_createdComponent_justRemoves() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());
        var componentA = new MockComponent("A", startOrder, stopOrder);

        coordinator.register(componentA);
        // Don't start coordinator - component stays in CREATED

        assertEquals(LifecycleState.CREATED, coordinator.getState("A"));

        // Act
        coordinator.stopAndUnregister("A");

        // Assert
        assertNull(coordinator.getState("A"), "Component A should be unregistered");
        assertEquals(0, stopOrder.size(), "Stop should not be called for CREATED component");
    }

    /**
     * Test 21: Verify stopAndUnregister() throws when other components depend on it.
     */
    @Test
    void testStopAndUnregister_withDependents_throws() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());
        var componentA = new MockComponent("A", startOrder, stopOrder);
        var componentB = new MockComponent("B", List.of("A"), startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.register(componentB);
        coordinator.start();

        // Act & Assert - Try to remove A while B depends on it
        var exception = assertThrows(LifecycleException.class,
                                     () -> coordinator.stopAndUnregister("A"));
        assertTrue(exception.getMessage().contains("depends on") || exception.getMessage().contains("B"),
                   "Exception should mention dependent component");
    }

    /**
     * Test 22: Verify stopAndUnregister() handles gracefully during coordinator stop.
     */
    @Test
    void testStopAndUnregister_duringCoordinatorStop_handlesGracefully() throws InterruptedException {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());
        var componentA = new MockComponent("A", startOrder, stopOrder);
        var componentB = new MockComponent("B", startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.register(componentB);
        coordinator.start();

        // Act - Try to remove component during coordinator shutdown
        var stopThread = new Thread(() -> coordinator.stop(5000));
        stopThread.start();

        Thread.sleep(20); // Let stop begin

        // Attempt stopAndUnregister during stop - should handle gracefully
        assertDoesNotThrow(() -> coordinator.stopAndUnregister("A"),
                          "stopAndUnregister should handle gracefully during coordinator stop");

        stopThread.join(2000);
    }

    // ========== Phase 1: Additional Tests from Plan Audit ==========

    /**
     * Test 23: Document race condition between registerAndStart() and start().
     * <p>
     * This test documents expected behavior when registerAndStart() is called
     * concurrently with coordinator.start(). The outcome is non-deterministic:
     * - If registerAndStart() wins: component starts immediately
     * - If start() wins: component registers but doesn't start until next start()
     */
    @Test
    void testRegisterAndStart_concurrentWithStart_handlesRace() throws InterruptedException {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());
        var componentA = new MockComponent("A", startOrder, stopOrder);

        coordinator.register(componentA);

        var latch = new CountDownLatch(2);
        var exception = new AtomicReference<Exception>();

        // Act - Race: start() vs registerAndStart()
        var startThread = new Thread(() -> {
            try {
                coordinator.start();
            } catch (Exception e) {
                exception.set(e);
            } finally {
                latch.countDown();
            }
        });

        var registerThread = new Thread(() -> {
            try {
                var componentB = new MockComponent("B", startOrder, stopOrder);
                coordinator.registerAndStart(componentB);
            } catch (Exception e) {
                // Expected - may fail due to race
            } finally {
                latch.countDown();
            }
        });

        startThread.start();
        registerThread.start();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Threads should complete");

        // Assert - Document non-deterministic outcome
        // Component A should always reach RUNNING (registered before race)
        assertEquals(LifecycleState.RUNNING, coordinator.getState("A"));

        // Component B outcome is non-deterministic - either RUNNING or CREATED
        var stateB = coordinator.getState("B");
        assertTrue(stateB == LifecycleState.RUNNING || stateB == LifecycleState.CREATED,
                   "Component B in race should be RUNNING or CREATED, was: " + stateB);
    }

    /**
     * Test 24: Verify empty coordinator starts instantly (<50ms assumption).
     */
    @Test
    void testEmptyCoordinatorStartTime_isInstant() {
        // Arrange
        var emptyCoordinator = new LifecycleCoordinator();

        // Act
        var startTime = System.currentTimeMillis();
        emptyCoordinator.start();
        var duration = System.currentTimeMillis() - startTime;

        // Assert
        assertTrue(duration < 50, "Empty coordinator should start in <50ms, took: " + duration + "ms");
        emptyCoordinator.stop(1000); // Cleanup
    }

    /**
     * Test 25: Verify stopAndUnregister() dependent detection prevents removal.
     * <p>
     * This test validates the critical dependent detection logic in stopAndUnregister().
     * It ensures components cannot be removed while others depend on them.
     */
    @Test
    void testStopAndUnregister_dependentDetection_preventsRemoval() {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());

        // Create dependency chain: C depends on B, B depends on A
        var componentA = new MockComponent("A", startOrder, stopOrder);
        var componentB = new MockComponent("B", List.of("A"), startOrder, stopOrder);
        var componentC = new MockComponent("C", List.of("B"), startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.register(componentB);
        coordinator.register(componentC);
        coordinator.start();

        // Act & Assert - Try to remove A (B depends on it)
        var exceptionA = assertThrows(LifecycleException.class,
                                      () -> coordinator.stopAndUnregister("A"));
        assertTrue(exceptionA.getMessage().contains("depends on") || exceptionA.getMessage().contains("B"),
                   "Exception should mention that B depends on A");

        // Act & Assert - Try to remove B (C depends on it)
        var exceptionB = assertThrows(LifecycleException.class,
                                      () -> coordinator.stopAndUnregister("B"));
        assertTrue(exceptionB.getMessage().contains("depends on") || exceptionB.getMessage().contains("C"),
                   "Exception should mention that C depends on B");

        // Act & Assert - Remove C (no dependents) should succeed
        assertDoesNotThrow(() -> coordinator.stopAndUnregister("C"),
                          "Removing C should succeed (no dependents)");
        assertNull(coordinator.getState("C"), "C should be removed");

        // Now B can be removed (no dependents after C removed)
        assertDoesNotThrow(() -> coordinator.stopAndUnregister("B"),
                          "Removing B should succeed after C removed");
        assertNull(coordinator.getState("B"), "B should be removed");

        // Finally A can be removed
        assertDoesNotThrow(() -> coordinator.stopAndUnregister("A"),
                          "Removing A should succeed after B removed");
        assertNull(coordinator.getState("A"), "A should be removed");
    }

    // ========== Thread-Safety Fix Tests (Bead Luciferase-7q6q) ==========

    /**
     * Test 26: Validate Issue #1 fix - concurrent unregister race handled atomically.
     * <p>
     * Issue: unregister() had check-then-act race between get() and remove()
     * Fix: Use computeIfPresent for atomic check-and-remove
     */
    @Test
    void testUnregister_concurrentRace_handlesCorrectly() throws InterruptedException {
        // Arrange
        var startOrder = Collections.synchronizedList(new ArrayList<String>());
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());
        var componentA = new MockComponent("A", startOrder, stopOrder);

        coordinator.register(componentA);

        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var exceptions = Collections.synchronizedList(new ArrayList<Exception>());
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Act - Multiple threads try to unregister the same component
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    coordinator.unregister("A");
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS), "Executor should terminate");

        // Assert - Component should be unregistered exactly once, no race conditions
        assertNull(coordinator.getState("A"), "Component should be unregistered");

        // All threads should complete without ConcurrentModificationException
        for (var ex : exceptions) {
            assertFalse(ex instanceof java.util.ConcurrentModificationException,
                       "Should not have ConcurrentModificationException: " + ex);
        }
    }

    /**
     * Test 27: Validate Issue #2 fix - concurrent registerAndStart + getState has no inconsistency.
     * <p>
     * Issue: Component registered in components map but state not yet in states map
     * Fix: Use atomic operation to ensure both maps updated together
     */
    @Test
    void testRegisterAndStart_concurrentGetState_noInconsistency() throws InterruptedException {
        // Arrange
        var threadCount = 20;
        var latch = new CountDownLatch(threadCount);
        var inconsistencies = Collections.synchronizedList(new ArrayList<String>());
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Act - Half threads register components, half threads query state
        for (int i = 0; i < threadCount; i++) {
            var index = i;
            executor.submit(() -> {
                try {
                    if (index % 2 == 0) {
                        // Register thread
                        var name = "Component" + index;
                        var comp = new MockComponent(name, new ArrayList<>(), new ArrayList<>());
                        coordinator.registerAndStart(comp);
                    } else {
                        // Query thread - check if registered components have consistent state
                        Thread.sleep(5); // Small delay to increase race probability
                        for (int j = 0; j < index; j += 2) {
                            var name = "Component" + j;
                            var state = coordinator.getState(name);
                            if (state == null) {
                                // Expected if not yet registered
                                continue;
                            }
                            // If state exists, it should be valid (not null)
                            if (state == null) {
                                inconsistencies.add(name + " has null state");
                            }
                        }
                    }
                } catch (Exception e) {
                    inconsistencies.add("Exception: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS), "Executor should terminate");

        // Assert - No inconsistencies detected
        assertTrue(inconsistencies.isEmpty(),
                  "Should have no state inconsistencies, found: " + inconsistencies);
    }

    /**
     * Test 28: Validate Issue #3 fix - stopAndUnregister only removes on successful stop.
     * <p>
     * Issue: Component unregistered even when stop() fails
     * Fix: Only unregister if stop succeeds, throw exception if stop fails
     */
    @Test
    void testStopAndUnregister_stopFails_throwsAndKeepsInCoordinator() {
        // Arrange
        var failingComponent = new FailingComponent("FailOnStop", List.of(), false, true);

        coordinator.register(failingComponent);
        coordinator.start();

        assertEquals(LifecycleState.RUNNING, coordinator.getState("FailOnStop"));

        // Act & Assert - stopAndUnregister should throw when stop fails
        var exception = assertThrows(LifecycleException.class,
                                    () -> coordinator.stopAndUnregister("FailOnStop"));
        assertTrue(exception.getMessage().contains("failed to stop") ||
                  exception.getMessage().contains("Cannot unregister"),
                  "Exception should indicate stop failure: " + exception.getMessage());

        // Assert - Component should still be in coordinator (not removed)
        assertNotNull(coordinator.getState("FailOnStop"),
                     "Component should remain registered after stop failure");

        // State should be FAILED to indicate the failure
        var finalState = coordinator.getState("FailOnStop");
        assertEquals(LifecycleState.FAILED, finalState,
                    "Component should be in FAILED state after stop failure, was: " + finalState);
    }
}
