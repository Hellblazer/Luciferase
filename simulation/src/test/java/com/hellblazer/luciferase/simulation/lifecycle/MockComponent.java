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
 * Mock implementation of LifecycleComponent for testing.
 * Tracks start/stop order and allows dependency configuration.
 *
 * @author hal.hildebrand
 */
public class MockComponent implements LifecycleComponent {
    private final String componentName;
    private final List<String> componentDependencies;
    private final AtomicReference<LifecycleState> state;
    private final List<String> startOrder;
    private final List<String> stopOrder;

    /**
     * Create a mock component with no dependencies.
     *
     * @param name component name
     * @param startOrder shared list to track start order
     * @param stopOrder shared list to track stop order
     */
    public MockComponent(String name, List<String> startOrder, List<String> stopOrder) {
        this(name, List.of(), startOrder, stopOrder);
    }

    /**
     * Create a mock component with dependencies.
     *
     * @param name component name
     * @param dependencies list of dependency names
     * @param startOrder shared list to track start order
     * @param stopOrder shared list to track stop order
     */
    public MockComponent(String name, List<String> dependencies, List<String> startOrder, List<String> stopOrder) {
        this.componentName = name;
        this.componentDependencies = new ArrayList<>(dependencies);
        this.state = new AtomicReference<>(LifecycleState.CREATED);
        this.startOrder = startOrder;
        this.stopOrder = stopOrder;
    }

    @Override
    public CompletableFuture<Void> start() {
        var currentState = state.get();
        if (currentState != LifecycleState.CREATED && currentState != LifecycleState.STOPPED) {
            throw new LifecycleException("Cannot start from state: " + currentState);
        }

        state.set(LifecycleState.STARTING);
        startOrder.add(componentName);

        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(10); // Simulate startup work
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
        stopOrder.add(componentName);

        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(10); // Simulate shutdown work
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
