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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle adapter for PersistenceManager.
 * <p>
 * Wraps PersistenceManager to provide LifecycleComponent interface without modifying
 * the original class. Uses composition pattern for clean separation.
 * <p>
 * Dependency Layer: 1 (depends on SocketConnectionManager)
 * <p>
 * State Transitions:
 * <ul>
 *   <li>start(): CREATED/STOPPED → STARTING → RUNNING (no-op, manager starts on construction)</li>
 *   <li>stop(): RUNNING → STOPPING → STOPPED (calls close())</li>
 * </ul>
 * <p>
 * Note: PersistenceManager auto-starts background tasks (batch flush, checkpoints) on construction.
 * The start() method is effectively a no-op but maintains lifecycle state consistency.
 * <p>
 * Thread-safe via AtomicReference for state management.
 *
 * @author hal.hildebrand
 */
public class PersistenceManagerAdapter implements LifecycleComponent {

    private static final Logger log = LoggerFactory.getLogger(PersistenceManagerAdapter.class);

    private final PersistenceManager persistenceManager;
    private final AtomicReference<LifecycleState> state;

    /**
     * Create an adapter for PersistenceManager.
     *
     * @param persistenceManager The PersistenceManager instance to wrap
     */
    public PersistenceManagerAdapter(PersistenceManager persistenceManager) {
        this.persistenceManager = Objects.requireNonNull(persistenceManager, "persistenceManager must not be null");
        this.state = new AtomicReference<>(LifecycleState.CREATED);
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            var currentState = state.get();

            // Idempotent: already running
            if (currentState == LifecycleState.RUNNING) {
                log.debug("PersistenceManager already running - idempotent no-op");
                return;
            }

            // Validate transition
            if (currentState != LifecycleState.CREATED && currentState != LifecycleState.STOPPED) {
                throw new LifecycleException(
                    "Cannot start PersistenceManager from state: " + currentState);
            }

            try {
                // Transition to STARTING
                if (!state.compareAndSet(currentState, LifecycleState.STARTING)) {
                    throw new LifecycleException("State changed during start transition");
                }

                log.debug("Starting PersistenceManager (background tasks already running)");

                // PersistenceManager starts background tasks on construction, so no explicit start needed
                // This method exists to maintain lifecycle state consistency

                // Transition to RUNNING
                state.set(LifecycleState.RUNNING);
                log.info("PersistenceManager started");

            } catch (Exception e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Failed to start PersistenceManager", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            var currentState = state.get();

            // Idempotent: already stopped
            if (currentState == LifecycleState.STOPPED) {
                log.debug("PersistenceManager already stopped - idempotent no-op");
                return;
            }

            // Validate transition
            if (currentState != LifecycleState.RUNNING) {
                throw new LifecycleException(
                    "Cannot stop PersistenceManager from state: " + currentState);
            }

            try {
                // Transition to STOPPING
                if (!state.compareAndSet(currentState, LifecycleState.STOPPING)) {
                    throw new LifecycleException("State changed during stop transition");
                }

                log.debug("Stopping PersistenceManager");

                // Delegate to wrapped persistence manager (closes executor and WAL)
                persistenceManager.close();

                // Transition to STOPPED
                state.set(LifecycleState.STOPPED);
                log.info("PersistenceManager stopped");

            } catch (Exception e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Failed to stop PersistenceManager", e);
            }
        });
    }

    @Override
    public LifecycleState getState() {
        return state.get();
    }

    @Override
    public String name() {
        return "PersistenceManager";
    }

    @Override
    public List<String> dependencies() {
        // Layer 1: Depends on SocketConnectionManager (for distributed log replication)
        return List.of("SocketConnectionManager");
    }

    /**
     * Get the wrapped PersistenceManager instance.
     *
     * @return The underlying persistence manager
     */
    public PersistenceManager getPersistenceManager() {
        return persistenceManager;
    }
}
