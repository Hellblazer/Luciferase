/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import com.hellblazer.luciferase.simulation.persistence.PersistenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test PersistenceManagerAdapter lifecycle management.
 *
 * @author hal.hildebrand
 */
class PersistenceManagerAdapterTest {

    @TempDir
    Path tempDir;

    private PersistenceManager persistenceManager;
    private PersistenceManagerAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        var nodeId = UUID.randomUUID();
        persistenceManager = new PersistenceManager(nodeId, tempDir);
        adapter = new PersistenceManagerAdapter(persistenceManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Only close if not already closed by adapter.stop()
        if (persistenceManager != null && adapter.getState() != LifecycleState.STOPPED) {
            persistenceManager.close();
        }
    }

    @Test
    void testInitialState() {
        // Adapter starts in CREATED state
        // Note: PersistenceManager auto-starts background tasks on construction
        assertEquals(LifecycleState.CREATED, adapter.getState(), "Initial state should be CREATED");
    }

    @Test
    void testStartTransition() throws ExecutionException, InterruptedException, TimeoutException {
        // Start adapter (no-op since PersistenceManager starts on construction)
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
    }

    @Test
    void testName() {
        assertEquals("PersistenceManager", adapter.name(), "Adapter name should be PersistenceManager");
    }

    @Test
    void testDependencies() {
        var deps = adapter.dependencies();
        assertNotNull(deps, "Dependencies should not be null");
        assertEquals(1, deps.size(), "PersistenceManager depends on SocketConnectionManager (Layer 1)");
        assertEquals("SocketConnectionManager", deps.get(0), "Should depend on SocketConnectionManager");
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
}
