/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link LifecycleComponent} whose start() async body blocks on an external {@link CountDownLatch} gate
 * (instead of a fixed Thread.sleep) before performing the guarded STARTING→RUNNING transition. This makes the
 * interleaving of a mid-STARTING stop()/rollback with a late doStart completion fully deterministic
 * (Luciferase-yy3r4), with no timing race window.
 *
 * <p>{@code enteredStart} fires once the async start body has begun and is about to await the gate, so a test
 * can deterministically know the component is in STARTING before it triggers a stop()/rollback and then
 * releases the gate. The STARTING→RUNNING transition mirrors {@link MockComponent}: it only commits under
 * {@code stateLock} if the state is still STARTING, so a stop() that ran while the gate was held suppresses
 * the late RUNNING transition.
 *
 * @author hal.hildebrand
 */
public class GatedComponent implements LifecycleComponent {
    private final String componentName;
    private final List<String> componentDependencies;
    private final AtomicReference<LifecycleState> state = new AtomicReference<>(LifecycleState.CREATED);
    private final Object stateLock = new Object();

    private final CountDownLatch startGate;
    private final CountDownLatch enteredStart = new CountDownLatch(1);

    public GatedComponent(String name, CountDownLatch startGate) {
        this.componentName = name;
        this.componentDependencies = new ArrayList<>();
        this.startGate = startGate;
    }

    /** Becomes ready once the async start body is running and about to await the gate. */
    public CountDownLatch enteredStart() {
        return enteredStart;
    }

    @Override
    public CompletableFuture<Void> start() {
        var currentState = state.get();
        if (currentState != LifecycleState.CREATED && currentState != LifecycleState.STOPPED) {
            throw new LifecycleException("Cannot start from state: " + currentState);
        }
        state.set(LifecycleState.STARTING);

        return CompletableFuture.runAsync(() -> {
            enteredStart.countDown();
            try {
                startGate.await(); // gate the doStart completion — deterministic, not a sleep race
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Start interrupted", e);
            }
            // Only commit STARTING→RUNNING if a concurrent stop()/rollback did not already move us off STARTING.
            synchronized (stateLock) {
                if (state.get() == LifecycleState.STARTING) {
                    state.set(LifecycleState.RUNNING);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> stop() {
        synchronized (stateLock) {
            var currentState = state.get();
            if (currentState != LifecycleState.STARTING && currentState != LifecycleState.RUNNING) {
                throw new LifecycleException("Cannot stop from state: " + currentState);
            }
            state.set(LifecycleState.STOPPING);
        }
        return CompletableFuture.runAsync(() -> state.set(LifecycleState.STOPPED));
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
