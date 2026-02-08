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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle adapter for RealTimeController.
 * <p>
 * Wraps RealTimeController to provide LifecycleComponent interface without modifying
 * the original class. Uses composition pattern for clean separation.
 * <p>
 * Dependency Layer: 0 (no dependencies)
 * <p>
 * State Transitions:
 * <ul>
 *   <li>start(): CREATED/STOPPED → STARTING → RUNNING (calls controller.start())</li>
 *   <li>stop(): RUNNING → STOPPING → STOPPED (calls controller.stop())</li>
 * </ul>
 * <p>
 * Thread-safe via AtomicReference for state management.
 *
 * @author hal.hildebrand
 */
public class RealTimeControllerAdapter implements LifecycleComponent {

    private static final Logger log = LoggerFactory.getLogger(RealTimeControllerAdapter.class);

    private final RealTimeController controller;
    private final AtomicReference<LifecycleState> state;

    /**
     * Create an adapter for RealTimeController.
     *
     * @param controller The RealTimeController instance to wrap
     */
    public RealTimeControllerAdapter(RealTimeController controller) {
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
        this.state = new AtomicReference<>(LifecycleState.CREATED);
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            var currentState = state.get();

            // Idempotent: already running
            if (currentState == LifecycleState.RUNNING) {
                log.debug("RealTimeController already running - idempotent no-op");
                return;
            }

            // Validate transition
            if (currentState != LifecycleState.CREATED && currentState != LifecycleState.STOPPED) {
                throw new LifecycleException(
                    "Cannot start RealTimeController from state: " + currentState);
            }

            try {
                // Transition to STARTING
                if (!state.compareAndSet(currentState, LifecycleState.STARTING)) {
                    throw new LifecycleException("State changed during start transition");
                }

                log.debug("Starting RealTimeController: {}", controller.getName());

                // Delegate to wrapped controller
                controller.start();

                // Transition to RUNNING
                state.set(LifecycleState.RUNNING);
                log.info("RealTimeController started: {}", controller.getName());

            } catch (Exception e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Failed to start RealTimeController", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            var currentState = state.get();

            // Idempotent: already stopped
            if (currentState == LifecycleState.STOPPED) {
                log.debug("RealTimeController already stopped - idempotent no-op");
                return;
            }

            // Validate transition
            if (currentState != LifecycleState.RUNNING) {
                throw new LifecycleException(
                    "Cannot stop RealTimeController from state: " + currentState);
            }

            try {
                // Transition to STOPPING
                if (!state.compareAndSet(currentState, LifecycleState.STOPPING)) {
                    throw new LifecycleException("State changed during stop transition");
                }

                log.debug("Stopping RealTimeController: {}", controller.getName());

                // Delegate to wrapped controller
                controller.stop();

                // Transition to STOPPED
                state.set(LifecycleState.STOPPED);
                log.info("RealTimeController stopped: {}", controller.getName());

            } catch (Exception e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Failed to stop RealTimeController", e);
            }
        });
    }

    @Override
    public LifecycleState getState() {
        return state.get();
    }

    @Override
    public String name() {
        return "RealTimeController";
    }

    @Override
    public List<String> dependencies() {
        return List.of(); // Layer 0 - no dependencies
    }

    /**
     * Get the wrapped RealTimeController instance.
     *
     * @return The underlying controller
     */
    public RealTimeController getController() {
        return controller;
    }
}
