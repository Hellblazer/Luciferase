/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Abstract base class for lifecycle adapters using template method pattern.
 * <p>
 * Extracts common state management logic from concrete adapters, eliminating
 * ~80 LOC duplication per adapter. Concrete subclasses only need to implement:
 * <ul>
 *   <li>{@link #getComponentName()} - for logging</li>
 *   <li>{@link #doStart()} - delegate to wrapped component</li>
 *   <li>{@link #doStop()} - delegate to wrapped component</li>
 * </ul>
 * <p>
 * State Transitions (common to all adapters):
 * <ul>
 *   <li>start(): CREATED/STOPPED/FAILED → STARTING → RUNNING</li>
 *   <li>stop(): RUNNING → STOPPING → STOPPED</li>
 * </ul>
 * <p>
 * Thread-safe via AtomicReference for state management.
 * <p>
 * Idempotent: start() when RUNNING is no-op, stop() when STOPPED is no-op.
 *
 * @author hal.hildebrand
 * @see RealTimeControllerAdapter
 * @see EnhancedBubbleAdapter
 * @see PersistenceManagerAdapter
 */
public abstract class AbstractLifecycleAdapter implements LifecycleComponent {

    private static final Logger log = LoggerFactory.getLogger(AbstractLifecycleAdapter.class);

    private final AtomicReference<LifecycleState> state = new AtomicReference<>(LifecycleState.CREATED);

    /**
     * Get the component name for logging purposes.
     *
     * @return Component name (e.g., "RealTimeController", "EnhancedBubble")
     */
    protected abstract String getComponentName();

    /**
     * Template method: start the wrapped component.
     * <p>
     * Called after state transitions to STARTING. Should delegate to the
     * wrapped component's start method.
     *
     * @throws Exception if start fails
     */
    protected abstract void doStart() throws Exception;

    /**
     * Template method: stop the wrapped component.
     * <p>
     * Called after state transitions to STOPPING. Should delegate to the
     * wrapped component's stop method.
     *
     * @throws Exception if stop fails
     */
    protected abstract void doStop() throws Exception;

    @Override
    public final CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            var currentState = state.get();

            // Idempotent: already running
            if (currentState == LifecycleState.RUNNING) {
                log.debug("{} already running - idempotent no-op", getComponentName());
                return;
            }

            // Validate transition (allow restart from FAILED for recovery)
            if (currentState != LifecycleState.CREATED
                && currentState != LifecycleState.STOPPED
                && currentState != LifecycleState.FAILED) {
                throw new LifecycleException(
                    "Cannot start " + getComponentName() + " from state: " + currentState);
            }

            try {
                // Transition to STARTING
                if (!state.compareAndSet(currentState, LifecycleState.STARTING)) {
                    throw new LifecycleException("State changed during start transition");
                }

                log.debug("Starting {}", getComponentName());

                // Template method: delegate to subclass
                doStart();

                // Transition to RUNNING
                state.set(LifecycleState.RUNNING);
                log.info("{} started", getComponentName());

            } catch (Exception e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Failed to start " + getComponentName(), e);
            }
        });
    }

    @Override
    public final CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            var currentState = state.get();

            // Idempotent: already stopped
            if (currentState == LifecycleState.STOPPED) {
                log.debug("{} already stopped - idempotent no-op", getComponentName());
                return;
            }

            // Validate transition
            if (currentState != LifecycleState.RUNNING) {
                throw new LifecycleException(
                    "Cannot stop " + getComponentName() + " from state: " + currentState);
            }

            try {
                // Transition to STOPPING
                if (!state.compareAndSet(currentState, LifecycleState.STOPPING)) {
                    throw new LifecycleException("State changed during stop transition");
                }

                log.debug("Stopping {}", getComponentName());

                // Template method: delegate to subclass
                doStop();

                // Transition to STOPPED
                state.set(LifecycleState.STOPPED);
                log.info("{} stopped", getComponentName());

            } catch (Exception e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Failed to stop " + getComponentName(), e);
            }
        });
    }

    @Override
    public final LifecycleState getState() {
        return state.get();
    }
}
