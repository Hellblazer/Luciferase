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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mock component with configurable delays for testing timeout handling.
 *
 * @author hal.hildebrand
 */
public class SlowComponent implements LifecycleComponent {
    private final String componentName;
    private final long startDelayMs;
    private final long stopDelayMs;
    private final AtomicReference<LifecycleState> state;

    /**
     * Create a slow component with configurable delays.
     *
     * @param name component name
     * @param startDelayMs milliseconds to delay start() completion
     * @param stopDelayMs milliseconds to delay stop() completion
     */
    public SlowComponent(String name, long startDelayMs, long stopDelayMs) {
        this.componentName = name;
        this.startDelayMs = startDelayMs;
        this.stopDelayMs = stopDelayMs;
        this.state = new AtomicReference<>(LifecycleState.CREATED);
    }

    @Override
    public CompletableFuture<Void> start() {
        var currentState = state.get();
        if (currentState != LifecycleState.CREATED && currentState != LifecycleState.STOPPED) {
            throw new LifecycleException("Cannot start from state: " + currentState);
        }

        state.set(LifecycleState.STARTING);

        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(startDelayMs);
                state.set(LifecycleState.RUNNING);
            } catch (InterruptedException e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Start interrupted", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> stop() {
        var currentState = state.get();
        if (currentState != LifecycleState.RUNNING) {
            throw new LifecycleException("Cannot stop from state: " + currentState);
        }

        state.set(LifecycleState.STOPPING);

        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(stopDelayMs);
                state.set(LifecycleState.STOPPED);
            } catch (InterruptedException e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Stop interrupted", e);
            }
        });
    }

    @Override
    public LifecycleState getState() {
        return state.get();
    }

    @Override
    public String name() {
        return componentName;
    }

    @Override
    public List<String> dependencies() {
        return List.of();
    }
}
