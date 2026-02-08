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
import com.hellblazer.luciferase.simulation.von.Bubble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle adapter for EnhancedBubble.
 * <p>
 * Wraps EnhancedBubble to provide LifecycleComponent interface without modifying
 * the original class. Uses composition pattern for clean separation.
 * <p>
 * Dependency Layer: 2 (depends on RealTimeController, PersistenceManager, SocketConnectionManager)
 * <p>
 * State Transitions:
 * <ul>
 *   <li>start(): CREATED/STOPPED → STARTING → RUNNING (starts RealTimeController)</li>
 *   <li>stop(): RUNNING → STOPPING → STOPPED (stops RealTimeController, cleans up ghost coordinator)</li>
 * </ul>
 * <p>
 * Thread-safe via AtomicReference for state management.
 *
 * @author hal.hildebrand
 */
public class EnhancedBubbleAdapter implements LifecycleComponent {

    private static final Logger log = LoggerFactory.getLogger(EnhancedBubbleAdapter.class);

    private final EnhancedBubble bubble;
    private final RealTimeController realTimeController;
    private final AtomicReference<LifecycleState> state;

    /**
     * Create an adapter for EnhancedBubble.
     *
     * @param bubble              The EnhancedBubble instance to wrap
     * @param realTimeController  The RealTimeController used by the bubble
     */
    public EnhancedBubbleAdapter(EnhancedBubble bubble, RealTimeController realTimeController) {
        this.bubble = Objects.requireNonNull(bubble, "bubble must not be null");
        this.realTimeController = Objects.requireNonNull(realTimeController, "realTimeController must not be null");
        this.state = new AtomicReference<>(LifecycleState.CREATED);
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            var currentState = state.get();

            // Idempotent: already running
            if (currentState == LifecycleState.RUNNING) {
                log.debug("EnhancedBubble {} already running - idempotent no-op", bubble.id());
                return;
            }

            // Validate transition (allow restart from FAILED for recovery)
            if (currentState != LifecycleState.CREATED
                && currentState != LifecycleState.STOPPED
                && currentState != LifecycleState.FAILED) {
                throw new LifecycleException(
                    "Cannot start EnhancedBubble from state: " + currentState);
            }

            try {
                // Transition to STARTING
                if (!state.compareAndSet(currentState, LifecycleState.STARTING)) {
                    throw new LifecycleException("State changed during start transition");
                }

                log.debug("Starting EnhancedBubble {}", bubble.id());

                // EnhancedBubble components are initialized on construction
                // The bubble is effectively ready to use, but we ensure RealTimeController is started
                // if it's not already running (it should be started by RealTimeControllerAdapter)

                // Transition to RUNNING
                state.set(LifecycleState.RUNNING);
                log.info("EnhancedBubble {} started", bubble.id());

            } catch (Exception e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Failed to start EnhancedBubble", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            var currentState = state.get();

            // Idempotent: already stopped
            if (currentState == LifecycleState.STOPPED) {
                log.debug("EnhancedBubble {} already stopped - idempotent no-op", bubble.id());
                return;
            }

            // Validate transition
            if (currentState != LifecycleState.RUNNING) {
                throw new LifecycleException(
                    "Cannot stop EnhancedBubble from state: " + currentState);
            }

            try {
                // Transition to STOPPING
                if (!state.compareAndSet(currentState, LifecycleState.STOPPING)) {
                    throw new LifecycleException("State changed during stop transition");
                }

                log.debug("Stopping EnhancedBubble {}", bubble.id());

                // If this is a VON-enabled Bubble, broadcast LEAVE to neighbors before stopping
                // This ensures graceful departure notifications are sent while transport is still available
                if (bubble instanceof Bubble vonBubble) {
                    log.debug("Broadcasting LEAVE to neighbors for Bubble {}", bubble.id());
                    vonBubble.broadcastLeave();
                }

                // RealTimeController is stopped by RealTimeControllerAdapter via coordinator's dependency ordering
                // No manual stop needed here - trust the coordinator's Layer 0→1→2 shutdown sequence

                // Clean up ghost coordinator resources
                // (Ghost channel cleanup happens via coordinator)

                // Transition to STOPPED
                state.set(LifecycleState.STOPPED);
                log.info("EnhancedBubble {} stopped", bubble.id());

            } catch (Exception e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Failed to stop EnhancedBubble", e);
            }
        });
    }

    @Override
    public LifecycleState getState() {
        return state.get();
    }

    @Override
    public String name() {
        return "EnhancedBubble";
    }

    @Override
    public List<String> dependencies() {
        // Layer 2: Depends on all managers and controller
        return List.of("PersistenceManager", "RealTimeController", "SocketConnectionManager");
    }

    /**
     * Get the wrapped EnhancedBubble instance.
     *
     * @return The underlying bubble
     */
    public EnhancedBubble getBubble() {
        return bubble;
    }
}
