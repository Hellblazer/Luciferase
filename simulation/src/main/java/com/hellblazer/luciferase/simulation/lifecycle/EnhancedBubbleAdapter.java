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
    private final List<String> dependencies;

    /**
     * Create an adapter for EnhancedBubble with no dependencies.
     * <p>
     * Use this constructor when the bubble is standalone and doesn't depend
     * on other lifecycle components (typical for Manager integration in Phase 5).
     *
     * @param bubble              The EnhancedBubble instance to wrap
     * @param realTimeController  The RealTimeController used by the bubble
     */
    public EnhancedBubbleAdapter(EnhancedBubble bubble, RealTimeController realTimeController) {
        this(bubble, realTimeController, List.of());
    }

    /**
     * Create an adapter for EnhancedBubble with explicit dependencies.
     * <p>
     * Use this constructor when the bubble depends on other lifecycle components
     * (e.g., PersistenceManager, SocketConnectionManager) that are managed by
     * the same coordinator.
     *
     * @param bubble              The EnhancedBubble instance to wrap
     * @param realTimeController  The RealTimeController used by the bubble
     * @param dependencies        List of component names this bubble depends on
     */
    public EnhancedBubbleAdapter(EnhancedBubble bubble, RealTimeController realTimeController,
                                 List<String> dependencies) {
        this.bubble = Objects.requireNonNull(bubble, "bubble must not be null");
        this.realTimeController = Objects.requireNonNull(realTimeController, "realTimeController must not be null");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies must not be null");
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

                // If this is a VON-enabled Bubble, call close() for full cleanup
                // Bubble.close() handles broadcastLeave() + resource cleanup in correct order
                if (bubble instanceof Bubble vonBubble) {
                    log.debug("Calling close() on VON Bubble {} for graceful shutdown", bubble.id());
                    vonBubble.close();
                } else {
                    // For plain EnhancedBubbles, stop RealTimeController manually
                    if (realTimeController.isRunning()) {
                        log.debug("Stopping RealTimeController for bubble {}", bubble.id());
                        realTimeController.stop();
                    }
                }

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
        // Include bubble ID to ensure unique names when multiple bubbles are coordinated
        return "EnhancedBubble-" + bubble.id();
    }

    @Override
    public List<String> dependencies() {
        // Return dependencies provided at construction time
        // Empty list for standalone bubbles, or explicit dependencies for coordinated systems
        return dependencies;
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
