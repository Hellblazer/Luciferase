/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test RealTimeControllerAdapter lifecycle management.
 *
 * @author hal.hildebrand
 */
class RealTimeControllerAdapterTest {

    private RealTimeController controller;
    private RealTimeControllerAdapter adapter;

    @BeforeEach
    void setUp() {
        var bubbleId = UUID.randomUUID();
        controller = new RealTimeController(bubbleId, "test-controller", 100);
        adapter = new RealTimeControllerAdapter(controller);
    }

    @AfterEach
    void tearDown() {
        if (controller.isRunning()) {
            controller.stop();
        }
    }

    @Test
    void testInitialState() {
        // Adapter starts in CREATED state
        assertEquals(LifecycleState.CREATED, adapter.getState(), "Initial state should be CREATED");
        assertFalse(controller.isRunning(), "Controller should not be running initially");
    }

    @Test
    void testStartTransition() throws ExecutionException, InterruptedException, TimeoutException {
        // Start adapter
        var startFuture = adapter.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Verify state transition
        assertEquals(LifecycleState.RUNNING, adapter.getState(), "State should be RUNNING after start");
        assertTrue(controller.isRunning(), "Controller should be running after start");
    }

    @Test
    void testStopTransition() throws ExecutionException, InterruptedException, TimeoutException {
        // Start then stop
        adapter.start().get(2, TimeUnit.SECONDS);
        var stopFuture = adapter.stop();
        stopFuture.get(2, TimeUnit.SECONDS);

        // Verify state transition
        assertEquals(LifecycleState.STOPPED, adapter.getState(), "State should be STOPPED after stop");
        assertFalse(controller.isRunning(), "Controller should not be running after stop");
    }

    @Test
    void testName() {
        assertEquals("RealTimeController", adapter.name(), "Adapter name should be RealTimeController");
    }

    @Test
    void testDependencies() {
        var deps = adapter.dependencies();
        assertNotNull(deps, "Dependencies should not be null");
        assertTrue(deps.isEmpty(), "RealTimeController has no dependencies (Layer 0)");
    }

    @Test
    void testIdempotentStart() throws ExecutionException, InterruptedException, TimeoutException {
        // Start twice
        adapter.start().get(2, TimeUnit.SECONDS);
        adapter.start().get(2, TimeUnit.SECONDS);

        // Should still be RUNNING
        assertEquals(LifecycleState.RUNNING, adapter.getState(), "State should remain RUNNING after second start");
        assertTrue(controller.isRunning(), "Controller should be running");
    }

    @Test
    void testStartFromStoppedState() throws ExecutionException, InterruptedException, TimeoutException {
        // Start -> Stop -> Start (restart)
        adapter.start().get(2, TimeUnit.SECONDS);
        adapter.stop().get(2, TimeUnit.SECONDS);
        adapter.start().get(2, TimeUnit.SECONDS);

        // Should be RUNNING again
        assertEquals(LifecycleState.RUNNING, adapter.getState(), "State should be RUNNING after restart");
        assertTrue(controller.isRunning(), "Controller should be running after restart");
    }

    @Test
    void testInvalidTransition() {
        // Try to stop before starting
        var ex = assertThrows(ExecutionException.class, () -> adapter.stop().get(2, TimeUnit.SECONDS),
                    "Should throw ExecutionException wrapping LifecycleException");
        assertTrue(ex.getCause() instanceof LifecycleException,
                  "Cause should be LifecycleException, got: " + ex.getCause());
    }
}
