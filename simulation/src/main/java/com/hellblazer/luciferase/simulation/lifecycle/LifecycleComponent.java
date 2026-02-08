/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Contract for components that participate in coordinated lifecycle management.
 * <p>
 * Components follow the lifecycle state machine:
 * <pre>
 * CREATED → STARTING → RUNNING → STOPPING → STOPPED
 *              ↓ (error) ↓
 *            FAILED
 * </pre>
 * <p>
 * All lifecycle operations (start/stop) are asynchronous and return CompletableFuture.
 * The LifecycleCoordinator uses dependency information to determine startup/shutdown ordering.
 * <p>
 * Implementations must be thread-safe for concurrent state queries.
 *
 * @author hal.hildebrand
 */
public interface LifecycleComponent {

    /**
     * Start this component asynchronously.
     * <p>
     * Valid initial states: CREATED, STOPPED
     * <p>
     * Transition sequence: CREATED/STOPPED → STARTING → RUNNING
     * <p>
     * If startup fails, component should transition to FAILED state
     * and the returned future should complete exceptionally.
     *
     * @return CompletableFuture that completes when startup finishes
     * @throws LifecycleException if called from invalid state (e.g., already RUNNING)
     */
    CompletableFuture<Void> start();

    /**
     * Stop this component asynchronously.
     * <p>
     * Valid initial state: RUNNING
     * <p>
     * Transition sequence: RUNNING → STOPPING → STOPPED
     * <p>
     * If shutdown fails, component should transition to FAILED state
     * and the returned future should complete exceptionally.
     *
     * @return CompletableFuture that completes when shutdown finishes
     * @throws LifecycleException if called from invalid state (e.g., already STOPPED)
     */
    CompletableFuture<Void> stop();

    /**
     * Get the current lifecycle state of this component.
     * <p>
     * Must be thread-safe for concurrent access.
     *
     * @return current state, never null
     */
    LifecycleState getState();

    /**
     * Get the unique name of this component.
     * <p>
     * Used by LifecycleCoordinator for dependency resolution and logging.
     * Must remain constant for the lifetime of the component.
     *
     * @return component name, never null
     */
    String name();

    /**
     * Get the names of components this component depends on.
     * <p>
     * The LifecycleCoordinator uses this information to perform topological
     * sort (Kahn's algorithm) and ensure components start in dependency order.
     * <p>
     * During startup: dependencies start before this component
     * During shutdown: dependencies stop after this component
     * <p>
     * Implementation should return a defensive copy to prevent external modification.
     *
     * @return list of component names this component depends on, never null (may be empty)
     */
    List<String> dependencies();
}
