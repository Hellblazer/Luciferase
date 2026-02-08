/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import com.hellblazer.luciferase.simulation.von.transport.ProcessAddress;
import com.hellblazer.luciferase.simulation.von.transport.SocketConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle adapter for SocketConnectionManager.
 * <p>
 * Wraps SocketConnectionManager to provide LifecycleComponent interface without modifying
 * the original class. Uses composition pattern for clean separation.
 * <p>
 * Dependency Layer: 0 (no dependencies)
 * <p>
 * State Transitions:
 * <ul>
 *   <li>start(): CREATED/STOPPED → STARTING → RUNNING (calls listenOn with dynamic port)</li>
 *   <li>stop(): RUNNING → STOPPING → STOPPED (calls closeAll())</li>
 * </ul>
 * <p>
 * Thread-safe via AtomicReference for state management.
 *
 * @author hal.hildebrand
 */
public class SocketConnectionManagerAdapter implements LifecycleComponent {

    private static final Logger log = LoggerFactory.getLogger(SocketConnectionManagerAdapter.class);

    private final SocketConnectionManager connectionManager;
    private final ProcessAddress bindAddress;
    private final AtomicReference<LifecycleState> state;

    /**
     * Create an adapter for SocketConnectionManager.
     * Uses dynamic port (0) for listening.
     *
     * @param connectionManager The SocketConnectionManager instance to wrap
     */
    public SocketConnectionManagerAdapter(SocketConnectionManager connectionManager) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager must not be null");
        // Extract bind address from connection manager (assumes constructor provided it)
        // For simplicity, create a dynamic port address
        this.bindAddress = new ProcessAddress("socket-mgr", "localhost", 0);
        this.state = new AtomicReference<>(LifecycleState.CREATED);
    }

    /**
     * Create an adapter with custom bind address.
     *
     * @param connectionManager The SocketConnectionManager instance to wrap
     * @param bindAddress       Address to listen on during start()
     */
    public SocketConnectionManagerAdapter(SocketConnectionManager connectionManager, ProcessAddress bindAddress) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager must not be null");
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress must not be null");
        this.state = new AtomicReference<>(LifecycleState.CREATED);
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            var currentState = state.get();

            // Idempotent: already running
            if (currentState == LifecycleState.RUNNING) {
                log.debug("SocketConnectionManager already running - idempotent no-op");
                return;
            }

            // Validate transition (allow restart from FAILED for recovery)
            if (currentState != LifecycleState.CREATED
                && currentState != LifecycleState.STOPPED
                && currentState != LifecycleState.FAILED) {
                throw new LifecycleException(
                    "Cannot start SocketConnectionManager from state: " + currentState);
            }

            try {
                // Transition to STARTING
                if (!state.compareAndSet(currentState, LifecycleState.STARTING)) {
                    throw new LifecycleException("State changed during start transition");
                }

                log.debug("Starting SocketConnectionManager on {}", bindAddress.toUrl());

                // Delegate to wrapped connection manager
                connectionManager.listenOn(bindAddress);

                // Transition to RUNNING
                state.set(LifecycleState.RUNNING);
                log.info("SocketConnectionManager started on {}", bindAddress.toUrl());

            } catch (Exception e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Failed to start SocketConnectionManager", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            var currentState = state.get();

            // Idempotent: already stopped
            if (currentState == LifecycleState.STOPPED) {
                log.debug("SocketConnectionManager already stopped - idempotent no-op");
                return;
            }

            // Validate transition
            if (currentState != LifecycleState.RUNNING) {
                throw new LifecycleException(
                    "Cannot stop SocketConnectionManager from state: " + currentState);
            }

            try {
                // Transition to STOPPING
                if (!state.compareAndSet(currentState, LifecycleState.STOPPING)) {
                    throw new LifecycleException("State changed during stop transition");
                }

                log.debug("Stopping SocketConnectionManager");

                // Delegate to wrapped connection manager
                connectionManager.closeAll();

                // Transition to STOPPED
                state.set(LifecycleState.STOPPED);
                log.info("SocketConnectionManager stopped");

            } catch (Exception e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Failed to stop SocketConnectionManager", e);
            }
        });
    }

    @Override
    public LifecycleState getState() {
        return state.get();
    }

    @Override
    public String name() {
        return "SocketConnectionManager";
    }

    @Override
    public List<String> dependencies() {
        return List.of(); // Layer 0 - no dependencies
    }

    /**
     * Get the wrapped SocketConnectionManager instance.
     *
     * @return The underlying connection manager
     */
    public SocketConnectionManager getConnectionManager() {
        return connectionManager;
    }
}
