/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
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
 * Test EnhancedBubbleAdapter lifecycle management.
 *
 * @author hal.hildebrand
 */
class EnhancedBubbleAdapterTest {

    private EnhancedBubble bubble;
    private RealTimeController controller;
    private EnhancedBubbleAdapter adapter;

    @BeforeEach
    void setUp() {
        var bubbleId = UUID.randomUUID();
        controller = new RealTimeController(bubbleId, "test-bubble", 100);
        bubble = new EnhancedBubble(bubbleId, (byte) 5, 16, controller);
        adapter = new EnhancedBubbleAdapter(bubble, controller);
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
    }

    @Test
    void testStartTransition() throws ExecutionException, InterruptedException, TimeoutException {
        // Start adapter
        var startFuture = adapter.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Verify state transition
        assertEquals(LifecycleState.RUNNING, adapter.getState(), "State should be RUNNING after start");
    }

    @Test
    void testStopTransition() throws ExecutionException, InterruptedException, TimeoutException {
        // Start then stop
        adapter.start().get(2, TimeUnit.SECONDS);
        var stopFuture = adapter.stop();
        stopFuture.get(2, TimeUnit.SECONDS);

        // Verify state transition
        assertEquals(LifecycleState.STOPPED, adapter.getState(), "State should be STOPPED after stop");
        assertFalse(controller.isRunning(), "RealTimeController should be stopped");
    }

    @Test
    void testName() {
        assertEquals("EnhancedBubble", adapter.name(), "Adapter name should be EnhancedBubble");
    }

    @Test
    void testDependencies() {
        var deps = adapter.dependencies();
        assertNotNull(deps, "Dependencies should not be null");
        assertEquals(3, deps.size(), "EnhancedBubble depends on 3 components (Layer 2)");
        assertTrue(deps.contains("PersistenceManager"), "Should depend on PersistenceManager");
        assertTrue(deps.contains("RealTimeController"), "Should depend on RealTimeController");
        assertTrue(deps.contains("SocketConnectionManager"), "Should depend on SocketConnectionManager");
    }

    @Test
    void testIdempotentStart() throws ExecutionException, InterruptedException, TimeoutException {
        // Start twice
        adapter.start().get(2, TimeUnit.SECONDS);
        adapter.start().get(2, TimeUnit.SECONDS);

        // Should still be RUNNING
        assertEquals(LifecycleState.RUNNING, adapter.getState(), "State should remain RUNNING after second start");
    }

    @Test
    void testInvalidTransition() {
        // Try to stop before starting
        var ex = assertThrows(ExecutionException.class, () -> adapter.stop().get(2, TimeUnit.SECONDS),
                    "Should throw ExecutionException wrapping LifecycleException");
        assertTrue(ex.getCause() instanceof LifecycleException,
                  "Cause should be LifecycleException, got: " + ex.getCause());
    }

    @Test
    void testStopCleansUpController() throws ExecutionException, InterruptedException, TimeoutException {
        // Note: EnhancedBubbleAdapter doesn't start the controller itself
        // The controller should be started by RealTimeControllerAdapter in dependency order
        // This test verifies that EnhancedBubbleAdapter.stop() stops the controller if it's running

        // Manually start controller to simulate coordinator startup
        controller.start();
        Thread.sleep(50); // Give controller time to start
        assertTrue(controller.isRunning(), "Controller should be running after manual start");

        // Start adapter (controller already running from RealTimeControllerAdapter in real usage)
        adapter.start().get(2, TimeUnit.SECONDS);

        // Stop adapter and verify controller is stopped
        adapter.stop().get(2, TimeUnit.SECONDS);
        assertFalse(controller.isRunning(), "Controller should be stopped after adapter stop");
    }
}
