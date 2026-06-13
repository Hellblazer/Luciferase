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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mock component with configurable delays for testing timeout handling.
 * <p>
 * Async start/stop work runs on a per-instance daemon executor, NOT {@link java.util.concurrent.ForkJoinPool}'s
 * common pool. This is load-bearing for the shutdown-budget tests (Luciferase-43bat / h8gm8): a budget-exhausted
 * stop is abandoned by the coordinator and keeps running in the background. If those abandoned multi-second sleeps
 * ran on the shared common pool, they would starve every later {@code CompletableFuture.runAsync}-based test in the
 * same JVM on a low-core CI runner (e.g. {@code MockComponent} stops never getting a thread → spurious CREATED/
 * RUNNING states). Isolating to a daemon executor confines the leak; {@link #close()} interrupts it promptly.
 *
 * @author hal.hildebrand
 */
public class SlowComponent implements LifecycleComponent, AutoCloseable {
    private final String componentName;
    private final long startDelayMs;
    private final long stopDelayMs;
    private final List<String> componentDependencies;
    private final AtomicReference<LifecycleState> state;
    private final ExecutorService executor;

    /**
     * Create a slow component with configurable delays and no dependencies.
     *
     * @param name component name
     * @param startDelayMs milliseconds to delay start() completion
     * @param stopDelayMs milliseconds to delay stop() completion
     */
    public SlowComponent(String name, long startDelayMs, long stopDelayMs) {
        this(name, startDelayMs, stopDelayMs, List.of());
    }

    /**
     * Create a slow component with configurable delays and dependencies (to place it in a higher
     * shutdown layer for budget/straggler tests).
     *
     * @param name component name
     * @param startDelayMs milliseconds to delay start() completion
     * @param stopDelayMs milliseconds to delay stop() completion
     * @param dependencies dependency component names
     */
    public SlowComponent(String name, long startDelayMs, long stopDelayMs, List<String> dependencies) {
        this.componentName = name;
        this.startDelayMs = startDelayMs;
        this.stopDelayMs = stopDelayMs;
        this.componentDependencies = List.copyOf(dependencies);
        this.state = new AtomicReference<>(LifecycleState.CREATED);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "SlowComponent-" + name);
            t.setDaemon(true);  // abandoned stragglers must never block JVM exit
            return t;
        });
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
                state.compareAndSet(LifecycleState.STARTING, LifecycleState.RUNNING);
            } catch (InterruptedException e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Start interrupted", e);
            }
        }, executor);
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
        }, executor);
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
        return componentDependencies;
    }

    /**
     * Shut down the per-instance executor, interrupting any in-flight (e.g. coordinator-abandoned) start/stop sleep.
     * Tests that deliberately abandon a slow stop should call this in a finally block for prompt thread cleanup;
     * the daemon thread makes it safe to omit, but closing frees the thread immediately.
     */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
