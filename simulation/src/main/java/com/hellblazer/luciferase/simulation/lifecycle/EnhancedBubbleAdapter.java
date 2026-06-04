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

/**
 * Lifecycle adapter for EnhancedBubble.
 * <p>
 * Wraps EnhancedBubble to provide LifecycleComponent interface without modifying
 * the original class. Uses composition pattern for clean separation.
 * <p>
 * Dependency Layer: 2 (depends on RealTimeController, PersistenceManager, SocketConnectionManager)
 * <p>
 * Extends {@link AbstractLifecycleAdapter} for common state management logic.
 *
 * @author hal.hildebrand
 */
public class EnhancedBubbleAdapter extends AbstractLifecycleAdapter {

    private static final Logger log = LoggerFactory.getLogger(EnhancedBubbleAdapter.class);

    private final EnhancedBubble bubble;
    private final RealTimeController realTimeController;
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
    }

    @Override
    protected String getComponentName() {
        return "EnhancedBubble-" + bubble.id();
    }

    @Override
    protected void doStart() {
        // EnhancedBubble components are initialized on construction
        // The bubble is effectively ready to use, but we ensure RealTimeController is started
        // if it's not already running (it should be started by RealTimeControllerAdapter)

        // No-op: bubble ready to use after construction
    }

    @Override
    protected void doStop() {
        // If this is a VON-enabled Bubble, call close() for full cleanup first:
        // Bubble.close() handles broadcastLeave() + VON resource cleanup in correct order.
        if (bubble instanceof Bubble vonBubble) {
            log.debug("Calling close() on VON Bubble {} for graceful shutdown", bubble.id());
            vonBubble.close();
        }
        // For all EnhancedBubbles (including VON), close() releases the ghost coordinator
        // tick-listener and, if this bubble owns its controller (3-arg ctor), stops it.
        // EnhancedBubble.close() is idempotent; calling it after vonBubble.close() is safe.
        bubble.close();
        // The adapter always receives a controller reference (injected or matching the bubble's
        // internal one). When the bubble was created with an externally-supplied controller
        // (ownsController=false) bubble.close() does NOT stop it — the adapter is then
        // the responsible owner and must stop it here.
        // RealTimeController.stop() uses compareAndSet(true→false), so calling it when the
        // bubble already stopped it (ownsController=true path) is a safe no-op.
        realTimeController.stop();
    }

    @Override
    public String name() {
        return getComponentName();
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
