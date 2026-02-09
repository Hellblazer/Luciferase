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
 * Lifecycle component interface for coordinator management.
 *
 * <p>Components implementing this interface can be registered with a
 * {@link LifecycleCoordinator} for dependency-ordered startup and shutdown.
 *
 * <p>Components follow the lifecycle state machine:
 * <pre>
 * CREATED → STARTING → RUNNING → STOPPING → STOPPED
 *              ↓ (error) ↓
 *            FAILED
 * </pre>
 *
 * <h2>Memory Visibility Requirements</h2>
 *
 * <p>Implementations MUST ensure proper cross-thread visibility of state changes.
 * Use volatile fields or {@link java.util.concurrent.atomic} classes for state
 * storage. State transitions in {@link #start()} and {@link #stop()} MUST be
 * visible to concurrent {@link #getState()} calls without additional synchronization.
 *
 * <p><b>Correct Implementation</b> (using AtomicReference):
 * <pre>
 * public class MyComponent implements LifecycleComponent {
 *     private final AtomicReference&lt;LifecycleState&gt; state =
 *         new AtomicReference&lt;&gt;(LifecycleState.CREATED);
 *
 *     public CompletableFuture&lt;Void&gt; start() {
 *         state.set(LifecycleState.RUNNING);  // Atomic write
 *         return CompletableFuture.completedFuture(null);
 *     }
 *
 *     public LifecycleState getState() {
 *         return state.get();  // Atomic read with memory barrier
 *     }
 * }
 * </pre>
 *
 * <p><b>Incorrect Implementation</b> (plain field - DO NOT USE):
 * <pre>
 * private LifecycleState state;  // NOT volatile - WRONG!
 *
 * public CompletableFuture&lt;Void&gt; start() {
 *     state = RUNNING;  // No memory barrier - invisible to other threads
 *     return CompletableFuture.completedFuture(null);
 * }
 *
 * public LifecycleState getState() {
 *     return state;  // Stale reads possible
 * }
 * </pre>
 *
 * <p>Plain (non-volatile) fields can cause the coordinator to read stale state,
 * leading to incorrect lifecycle decisions such as failing to detect component
 * failures or attempting to unregister running components.
 *
 * @author hal.hildebrand
 * @see LifecycleCoordinator
 * @see LifecycleState
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
     *
     * <p><b>Thread-Safety Constraint</b>: This method MUST NOT call back into
     * the {@link LifecycleCoordinator} from which it was invoked. The coordinator
     * may hold internal locks (ConcurrentHashMap segment locks) while querying
     * component state. Callback attempts create lock cycles and cause deadlock.
     *
     * <p><b>Safe Pattern</b>:
     * <pre>
     * private final AtomicReference&lt;LifecycleState&gt; state =
     *     new AtomicReference&lt;&gt;(LifecycleState.CREATED);
     *
     * public LifecycleState getState() {
     *     return state.get();  // Simple read, no coordinator calls
     * }
     * </pre>
     *
     * @return current lifecycle state (never null)
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
