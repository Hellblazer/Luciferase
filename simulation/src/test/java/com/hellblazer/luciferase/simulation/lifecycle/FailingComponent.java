/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mock component that fails during start or stop for testing error handling.
 *
 * @author hal.hildebrand
 */
public class FailingComponent implements LifecycleComponent {
    private final String componentName;
    private final List<String> componentDependencies;
    private final boolean failOnStart;
    private final boolean failOnStop;
    private final AtomicReference<LifecycleState> state;

    /**
     * Create a component that fails on start or stop.
     *
     * @param name component name
     * @param dependencies list of dependency names
     * @param failOnStart true to fail during start()
     * @param failOnStop true to fail during stop()
     */
    public FailingComponent(String name, List<String> dependencies, boolean failOnStart, boolean failOnStop) {
        this.componentName = name;
        this.componentDependencies = new ArrayList<>(dependencies);
        this.failOnStart = failOnStart;
        this.failOnStop = failOnStop;
        this.state = new AtomicReference<>(LifecycleState.CREATED);
    }

    @Override
    public CompletableFuture<Void> start() {
        var currentState = state.get();
        if (currentState != LifecycleState.CREATED && currentState != LifecycleState.STOPPED) {
            throw new LifecycleException("Cannot start from state: " + currentState);
        }

        state.set(LifecycleState.STARTING);

        if (failOnStart) {
            state.set(LifecycleState.FAILED);
            return CompletableFuture.failedFuture(new LifecycleException("Simulated start failure: " + componentName));
        }

        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(10);
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

        if (failOnStop) {
            state.set(LifecycleState.FAILED);
            return CompletableFuture.failedFuture(new LifecycleException("Simulated stop failure: " + componentName));
        }

        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(10);
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
        return new ArrayList<>(componentDependencies);
    }
}
