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
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for LifecycleCoordinator integration with Fireflies virtual synchrony.
 * Verifies that shutdown waits for view stability before closing components.
 *
 * @author hal.hildebrand
 */
class LifecycleCoordinatorFirefliesTest {
    private static final Logger log = LoggerFactory.getLogger(LifecycleCoordinatorFirefliesTest.class);

    @Test
    void testShutdownWithViewStability() {
        // Given: Coordinator with stable view
        var mockMonitor = mock(FirefliesViewMonitor.class);
        when(mockMonitor.isViewStable()).thenReturn(true);

        var gate = new ViewStabilityGate(mockMonitor, 5000);
        var coordinator = new LifecycleCoordinator(gate);

        // Register mock components
        var startOrder = new ArrayList<String>();
        var stopOrder = new ArrayList<String>();
        var componentA = new MockComponent("A", startOrder, stopOrder);
        coordinator.register(componentA);

        // When: Start and stop
        coordinator.start();
        coordinator.stop(5000);

        // Then: View stability was checked
        verify(mockMonitor, atLeastOnce()).isViewStable();

        // And component stopped
        assertEquals(LifecycleState.STOPPED, componentA.getState());
        assertEquals(List.of("A"), startOrder);
        assertEquals(List.of("A"), stopOrder);

        log.info("testShutdownWithViewStability: passed");
    }

    @Test
    void testShutdownWithViewStabilityTimeout() {
        // Given: View that never stabilizes
        var mockMonitor = mock(FirefliesViewMonitor.class);
        when(mockMonitor.isViewStable()).thenReturn(false);

        var gate = new ViewStabilityGate(mockMonitor, 100);  // Fast timeout
        var coordinator = new LifecycleCoordinator(gate);

        var startOrder = new ArrayList<String>();
        var stopOrder = new ArrayList<String>();
        var componentA = new MockComponent("A", startOrder, stopOrder);
        coordinator.register(componentA);

        coordinator.start();

        // When: Stop with view stability timeout
        // Then: Should complete despite timeout (graceful degradation)
        assertDoesNotThrow(() -> coordinator.stop(5000));

        // And component should still stop
        assertEquals(LifecycleState.STOPPED, componentA.getState());
        assertEquals(List.of("A"), stopOrder);

        log.info("testShutdownWithViewStabilityTimeout: passed - graceful degradation works");
    }

    @Test
    void testShutdownWithoutViewStabilityGate() {
        // Given: Coordinator without gate (backward compatibility)
        var coordinator = new LifecycleCoordinator();  // No gate

        var startOrder = new ArrayList<String>();
        var stopOrder = new ArrayList<String>();
        var componentA = new MockComponent("A", startOrder, stopOrder);
        coordinator.register(componentA);

        // When: Start and stop without gate
        coordinator.start();
        coordinator.stop(5000);

        // Then: Should work normally without view stability check
        assertEquals(LifecycleState.STOPPED, componentA.getState());
        assertEquals(List.of("A"), stopOrder);

        log.info("testShutdownWithoutViewStabilityGate: backward compatibility verified");
    }

    @Test
    void testShutdownMultipleComponentsWithViewStability() {
        // Given: Coordinator with stable view and multiple components
        var mockMonitor = mock(FirefliesViewMonitor.class);
        when(mockMonitor.isViewStable()).thenReturn(true);

        var gate = new ViewStabilityGate(mockMonitor, 5000);
        var coordinator = new LifecycleCoordinator(gate);

        var startOrder = new ArrayList<String>();
        var stopOrder = new ArrayList<String>();

        // Create dependency chain: C → B → A
        var componentA = new MockComponent("A", startOrder, stopOrder);
        var componentB = new MockComponent("B", List.of("A"), startOrder, stopOrder);
        var componentC = new MockComponent("C", List.of("B"), startOrder, stopOrder);

        coordinator.register(componentA);
        coordinator.register(componentB);
        coordinator.register(componentC);

        // When: Start and stop
        coordinator.start();
        coordinator.stop(5000);

        // Then: View stability checked once
        verify(mockMonitor, atLeastOnce()).isViewStable();

        // And all components stopped
        assertEquals(LifecycleState.STOPPED, componentA.getState());
        assertEquals(LifecycleState.STOPPED, componentB.getState());
        assertEquals(LifecycleState.STOPPED, componentC.getState());

        // And stop order is reverse of start order
        assertEquals(List.of("A", "B", "C"), startOrder);
        assertEquals(List.of("C", "B", "A"), stopOrder);

        log.info("testShutdownMultipleComponentsWithViewStability: dependency order preserved");
    }

    @Test
    void testShutdownWithBecomingStableView() throws Exception {
        // Given: View that becomes stable after 2 polls
        var mockMonitor = mock(FirefliesViewMonitor.class);
        when(mockMonitor.isViewStable())
            .thenReturn(false)  // First poll
            .thenReturn(false)  // Second poll
            .thenReturn(true);  // Third poll - stable

        var gate = new ViewStabilityGate(mockMonitor, 5000);
        var coordinator = new LifecycleCoordinator(gate);

        var startOrder = new ArrayList<String>();
        var stopOrder = new ArrayList<String>();
        var componentA = new MockComponent("A", startOrder, stopOrder);
        coordinator.register(componentA);

        // When: Start and stop
        coordinator.start();
        coordinator.stop(5000);

        // Then: View stability was polled multiple times
        verify(mockMonitor, atLeast(3)).isViewStable();

        // And component stopped after view became stable
        assertEquals(LifecycleState.STOPPED, componentA.getState());

        log.info("testShutdownWithBecomingStableView: waited for stability convergence");
    }
}
