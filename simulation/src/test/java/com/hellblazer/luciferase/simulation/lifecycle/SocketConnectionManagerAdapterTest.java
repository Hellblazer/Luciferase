/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import com.hellblazer.luciferase.simulation.von.TransportVonMessage;
import com.hellblazer.luciferase.simulation.von.transport.ProcessAddress;
import com.hellblazer.luciferase.simulation.von.transport.SocketConnectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test SocketConnectionManagerAdapter lifecycle management.
 *
 * @author hal.hildebrand
 */
class SocketConnectionManagerAdapterTest {

    private SocketConnectionManager connectionManager;
    private SocketConnectionManagerAdapter adapter;
    private ProcessAddress testAddress;

    @BeforeEach
    void setUp() {
        testAddress = new ProcessAddress(UUID.randomUUID().toString(), "localhost", 0); // Dynamic port
        Consumer<TransportVonMessage> noOpHandler = msg -> {};
        connectionManager = new SocketConnectionManager(testAddress, noOpHandler);
        adapter = new SocketConnectionManagerAdapter(connectionManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (connectionManager.isRunning()) {
            connectionManager.closeAll();
        }
    }

    @Test
    void testInitialState() {
        // Adapter starts in CREATED state
        assertEquals(LifecycleState.CREATED, adapter.getState(), "Initial state should be CREATED");
        assertFalse(connectionManager.isRunning(), "ConnectionManager should not be running initially");
    }

    @Test
    void testStartTransition() throws ExecutionException, InterruptedException, TimeoutException, IOException {
        // Start adapter (listenOn with dynamic port)
        var startFuture = adapter.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Verify state transition
        assertEquals(LifecycleState.RUNNING, adapter.getState(), "State should be RUNNING after start");
        assertTrue(connectionManager.isRunning(), "ConnectionManager should be running after start");
    }

    @Test
    void testStopTransition() throws ExecutionException, InterruptedException, TimeoutException {
        // Start then stop
        adapter.start().get(2, TimeUnit.SECONDS);
        var stopFuture = adapter.stop();
        stopFuture.get(2, TimeUnit.SECONDS);

        // Verify state transition
        assertEquals(LifecycleState.STOPPED, adapter.getState(), "State should be STOPPED after stop");
        assertFalse(connectionManager.isRunning(), "ConnectionManager should not be running after stop");
    }

    @Test
    void testName() {
        assertEquals("SocketConnectionManager", adapter.name(), "Adapter name should be SocketConnectionManager");
    }

    @Test
    void testDependencies() {
        var deps = adapter.dependencies();
        assertNotNull(deps, "Dependencies should not be null");
        assertTrue(deps.isEmpty(), "SocketConnectionManager has no dependencies (Layer 0)");
    }

    @Test
    void testIdempotentStart() throws ExecutionException, InterruptedException, TimeoutException {
        // Start twice
        adapter.start().get(2, TimeUnit.SECONDS);
        adapter.start().get(2, TimeUnit.SECONDS);

        // Should still be RUNNING
        assertEquals(LifecycleState.RUNNING, adapter.getState(), "State should remain RUNNING after second start");
        assertTrue(connectionManager.isRunning(), "ConnectionManager should be running");
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
