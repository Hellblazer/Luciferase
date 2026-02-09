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

import java.util.List;
import java.util.Objects;

/**
 * Lifecycle adapter for RealTimeController.
 * <p>
 * Wraps RealTimeController to provide LifecycleComponent interface without modifying
 * the original class. Uses composition pattern for clean separation.
 * <p>
 * Dependency Layer: 0 (no dependencies)
 * <p>
 * Extends {@link AbstractLifecycleAdapter} for common state management logic.
 *
 * @author hal.hildebrand
 */
public class RealTimeControllerAdapter extends AbstractLifecycleAdapter {

    private final RealTimeController controller;

    /**
     * Create an adapter for RealTimeController.
     *
     * @param controller The RealTimeController instance to wrap
     */
    public RealTimeControllerAdapter(RealTimeController controller) {
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
    }

    @Override
    protected String getComponentName() {
        return "RealTimeController";
    }

    @Override
    protected void doStart() {
        controller.start();
    }

    @Override
    protected void doStop() {
        controller.stop();
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
