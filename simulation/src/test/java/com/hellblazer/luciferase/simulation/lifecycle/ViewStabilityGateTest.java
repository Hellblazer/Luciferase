/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ViewStabilityGate.
 * Verifies Fireflies virtual synchrony integration with lifecycle shutdown.
 *
 * @author hal.hildebrand
 */
class ViewStabilityGateTest {

    @Test
    void testAwaitStability_StableView() throws Exception {
        // Given: A monitor reporting stable view
        var mockMonitor = mock(FirefliesViewMonitor.class);
        when(mockMonitor.isViewStable()).thenReturn(true);

        var gate = new ViewStabilityGate(mockMonitor, 5000);

        // When: Await stability
        var future = gate.awaitStability();

        // Then: Should complete quickly when view already stable
        assertDoesNotThrow(() -> future.get(100, TimeUnit.MILLISECONDS));
        verify(mockMonitor, atLeastOnce()).isViewStable();
    }

    @Test
    void testAwaitStability_BecomesStable() throws Exception {
        // Given: A monitor that becomes stable after 2 polls
        var mockMonitor = mock(FirefliesViewMonitor.class);
        when(mockMonitor.isViewStable())
            .thenReturn(false)  // First poll
            .thenReturn(false)  // Second poll
            .thenReturn(true);  // Third poll - stable

        var gate = new ViewStabilityGate(mockMonitor, 5000);

        // When: Await stability
        var future = gate.awaitStability();

        // Then: Should complete when view becomes stable
        assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS));
        verify(mockMonitor, atLeast(3)).isViewStable();
    }

    @Test
    void testAwaitStability_Timeout() {
        // Given: A monitor that never becomes stable
        var mockMonitor = mock(FirefliesViewMonitor.class);
        when(mockMonitor.isViewStable()).thenReturn(false);  // Never stable

        var gate = new ViewStabilityGate(mockMonitor, 100);  // 100ms timeout

        // When: Await stability
        var future = gate.awaitStability();

        // Then: Should timeout after 100ms (wrapped in ExecutionException)
        var exception = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> future.get(200, TimeUnit.MILLISECONDS));

        // Verify cause is TimeoutException
        assertTrue(exception.getCause() instanceof TimeoutException,
            "Expected TimeoutException as cause, got: " + exception.getCause().getClass());
    }

    @Test
    void testIsStable_DelegatesToMonitor() {
        // Given: A monitor with stable state
        var mockMonitor = mock(FirefliesViewMonitor.class);
        when(mockMonitor.isViewStable()).thenReturn(true);

        var gate = new ViewStabilityGate(mockMonitor, 5000);

        // When: Check if stable
        var stable = gate.isStable();

        // Then: Should delegate to monitor
        assertTrue(stable);
        verify(mockMonitor).isViewStable();
    }

    @Test
    void testConstructor_NullMonitor() {
        // When/Then: Null monitor should throw
        assertThrows(NullPointerException.class,
            () -> new ViewStabilityGate(null, 5000));
    }

    @Test
    void testAwaitStability_MultipleCallsAllowed() throws Exception {
        // Given: A stable monitor
        var mockMonitor = mock(FirefliesViewMonitor.class);
        when(mockMonitor.isViewStable()).thenReturn(true);

        var gate = new ViewStabilityGate(mockMonitor, 5000);

        // When: Multiple calls to awaitStability
        var future1 = gate.awaitStability();
        var future2 = gate.awaitStability();

        // Then: Both should complete successfully
        assertDoesNotThrow(() -> future1.get(100, TimeUnit.MILLISECONDS));
        assertDoesNotThrow(() -> future2.get(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testClockInjection_WithTestClock() throws Exception {
        // Given: A monitor that becomes stable immediately
        var mockMonitor = mock(FirefliesViewMonitor.class);
        when(mockMonitor.isViewStable()).thenReturn(true);

        var gate = new ViewStabilityGate(mockMonitor, 5000);  // 5s timeout

        // And: TestClock for deterministic time control
        var testClock = new TestClock(1000L);  // Start at t=1000ms
        gate.setClock(testClock);

        // When: Await stability
        var future = gate.awaitStability();

        // Then: Should complete successfully using the injected clock
        assertDoesNotThrow(() -> future.get(100, TimeUnit.MILLISECONDS),
            "Gate should complete successfully with TestClock injection");

        // Verify monitor was queried
        verify(mockMonitor, atLeastOnce()).isViewStable();
    }
}
