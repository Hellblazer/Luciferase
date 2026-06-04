/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Abstract base class for lifecycle adapters using template method pattern.
 * <p>
 * Extracts common state management logic from concrete adapters, eliminating
 * ~80 LOC duplication per adapter. Concrete subclasses only need to implement:
 * <ul>
 *   <li>{@link #getComponentName()} - for logging</li>
 *   <li>{@link #doStart()} - delegate to wrapped component</li>
 *   <li>{@link #doStop()} - delegate to wrapped component</li>
 * </ul>
 * <p>
 * State Transitions (common to all adapters):
 * <ul>
 *   <li>start(): CREATED/STOPPED/FAILED → STARTING → RUNNING</li>
 *   <li>stop(): RUNNING → STOPPING → STOPPED</li>
 *   <li>stop(): STARTING → STOPPING → STOPPED (cancellation during startup rollback)</li>
 * </ul>
 * <p>
 * Thread-safe via AtomicReference for state management.
 * <p>
 * Idempotent: start() when RUNNING is no-op, stop() when STOPPED is no-op.
 *
 * @author hal.hildebrand
 * @see RealTimeControllerAdapter
 * @see EnhancedBubbleAdapter
 * @see PersistenceManagerAdapter
 */
public abstract class AbstractLifecycleAdapter implements LifecycleComponent {

    private static final Logger log = LoggerFactory.getLogger(AbstractLifecycleAdapter.class);

    private final AtomicReference<LifecycleState> state = new AtomicReference<>(LifecycleState.CREATED);

    /**
     * Get the component name for logging purposes.
     *
     * @return Component name (e.g., "RealTimeController", "EnhancedBubble")
     */
    protected abstract String getComponentName();

    /**
     * Template method: start the wrapped component.
     * <p>
     * Called after state transitions to STARTING. Should delegate to the
     * wrapped component's start method.
     *
     * @throws Exception if start fails
     */
    protected abstract void doStart() throws Exception;

    /**
     * Template method: stop the wrapped component.
     * <p>
     * Called after state transitions to STOPPING. Should delegate to the
     * wrapped component's stop method.
     *
     * @throws Exception if stop fails
     */
    protected abstract void doStop() throws Exception;

    @Override
    public final CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            var currentState = state.get();

            // Idempotent: already running
            if (currentState == LifecycleState.RUNNING) {
                log.debug("{} already running - idempotent no-op", getComponentName());
                return;
            }

            // Validate transition (allow restart from FAILED for recovery)
            if (currentState != LifecycleState.CREATED
                && currentState != LifecycleState.STOPPED
                && currentState != LifecycleState.FAILED) {
                throw new LifecycleException(
                    "Cannot start " + getComponentName() + " from state: " + currentState);
            }

            try {
                // Transition to STARTING
                if (!state.compareAndSet(currentState, LifecycleState.STARTING)) {
                    throw new LifecycleException("State changed during start transition");
                }

                log.debug("Starting {}", getComponentName());

                // Template method: delegate to subclass
                doStart();

                // Transition to RUNNING — but ONLY if a concurrent stop() (startup-rollback
                // cancellation) has not already moved us out of STARTING. Blindly setting
                // RUNNING here would resurrect a component the coordinator already tore down
                // (Luciferase-0frcy.27). If the CAS fails, the component was cancelled; undo
                // the partial start best-effort and leave it stopped.
                if (state.compareAndSet(LifecycleState.STARTING, LifecycleState.RUNNING)) {
                    log.info("{} started", getComponentName());
                } else {
                    // The CAS failed: a concurrent stop() moved us out of STARTING. stop() reserves
                    // the STARTING->STOPPING transition and OWNS the doStop() cleanup for this
                    // start/stop cycle. We must NOT call doStop() here — doing so would double-invoke
                    // doStop() and double-close resources in subclasses (Luciferase-0frcy M2). Only
                    // run the best-effort teardown if we actually claimed STOPPING ourselves (a state
                    // other than STARTING/STOPPING/STOPPED that nonetheless lost the RUNNING CAS).
                    var observed = state.get();
                    log.info("{} start cancelled before completion (state={})",
                             getComponentName(), observed);
                    if (observed != LifecycleState.STOPPING && observed != LifecycleState.STOPPED
                        && state.compareAndSet(observed, LifecycleState.STOPPING)) {
                        try {
                            doStop();
                        } catch (Exception stopEx) {
                            log.warn("Best-effort doStop() after cancelled start of {} failed: {}",
                                     getComponentName(), stopEx.getMessage());
                        }
                        state.set(LifecycleState.STOPPED);
                    }
                }

            } catch (Exception e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Failed to start " + getComponentName(), e);
            }
        });
    }

    @Override
    public final CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            var currentState = state.get();

            // Idempotent: already stopped
            if (currentState == LifecycleState.STOPPED) {
                log.debug("{} already stopped - idempotent no-op", getComponentName());
                return;
            }

            // Cancellation during startup rollback: a component caught in STARTING (e.g. when
            // a layer fails and the coordinator stops STARTING|RUNNING components) must be torn
            // down rather than rejected. Rejecting would leave it half-initialized in STARTING
            // with its start in flight, possibly transitioning to RUNNING after rollback
            // completed (Luciferase-0frcy.27). Run doStop() best-effort to release any partially
            // acquired resources, then settle in STOPPED.
            if (currentState == LifecycleState.STARTING) {
                if (!state.compareAndSet(currentState, LifecycleState.STOPPING)) {
                    // The in-flight start() advanced the state (most likely to RUNNING or FAILED).
                    // Re-read and fall through to the normal validation below.
                    currentState = state.get();
                    if (currentState == LifecycleState.STOPPED) {
                        return;
                    }
                    if (currentState != LifecycleState.RUNNING) {
                        throw new LifecycleException(
                            "Cannot stop " + getComponentName() + " from state: " + currentState);
                    }
                    state.set(LifecycleState.STOPPING);
                }
                log.debug("Cancelling start of {} (was STARTING)", getComponentName());
                try {
                    doStop();
                } catch (Exception e) {
                    // Best-effort cleanup of a partial start; settle in STOPPED regardless so the
                    // component is not left dangling in STARTING.
                    log.warn("Best-effort doStop() during startup cancellation of {} failed: {}",
                             getComponentName(), e.getMessage());
                }
                state.set(LifecycleState.STOPPED);
                log.info("{} start cancelled and stopped", getComponentName());
                return;
            }

            // Validate transition
            if (currentState != LifecycleState.RUNNING) {
                throw new LifecycleException(
                    "Cannot stop " + getComponentName() + " from state: " + currentState);
            }

            try {
                // Transition to STOPPING
                if (!state.compareAndSet(currentState, LifecycleState.STOPPING)) {
                    throw new LifecycleException("State changed during stop transition");
                }

                log.debug("Stopping {}", getComponentName());

                // Template method: delegate to subclass
                doStop();

                // Transition to STOPPED
                state.set(LifecycleState.STOPPED);
                log.info("{} stopped", getComponentName());

            } catch (Exception e) {
                state.set(LifecycleState.FAILED);
                throw new LifecycleException("Failed to stop " + getComponentName(), e);
            }
        });
    }

    @Override
    public final LifecycleState getState() {
        return state.get();
    }
}
