/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states for simulation components.
 * <p>
 * State machine:
 * <pre>
 * CREATED → STARTING → RUNNING → STOPPING → STOPPED
 *              ↓ (error) ↓
 *            FAILED
 * </pre>
 * <p>
 * Valid transitions:
 * <ul>
 *   <li>CREATED → STARTING</li>
 *   <li>STARTING → RUNNING, FAILED</li>
 *   <li>RUNNING → STOPPING, FAILED</li>
 *   <li>STOPPING → STOPPED, FAILED</li>
 *   <li>STOPPED → STARTING (restart), FAILED</li>
 *   <li>FAILED → (none - terminal state)</li>
 * </ul>
 * <p>
 * Thread-safe: Enum instances are immutable.
 *
 * @author hal.hildebrand
 */
public enum LifecycleState {
    /**
     * Initial state after construction.
     * Valid transitions: STARTING
     */
    CREATED,

    /**
     * Asynchronous startup in progress.
     * Valid transitions: RUNNING, FAILED
     */
    STARTING,

    /**
     * Fully operational.
     * Valid transitions: STOPPING, FAILED
     */
    RUNNING,

    /**
     * Asynchronous shutdown in progress.
     * Valid transitions: STOPPED, FAILED
     */
    STOPPING,

    /**
     * Fully stopped, can restart.
     * Valid transitions: STARTING, FAILED
     */
    STOPPED,

    /**
     * Error occurred during transition.
     * Terminal state - no transitions allowed.
     */
    FAILED;

    /**
     * Check if transition to target state is valid.
     *
     * @param target the state to transition to
     * @return true if transition is allowed, false otherwise
     */
    public boolean canTransitionTo(LifecycleState target) {
        // Any state can transition to FAILED on error
        if (target == FAILED && this != FAILED) {
            return true;
        }

        return switch (this) {
            case CREATED -> target == STARTING;
            case STARTING -> target == RUNNING;
            case RUNNING -> target == STOPPING;
            case STOPPING -> target == STOPPED;
            case STOPPED -> target == STARTING;
            case FAILED -> false; // Terminal state - no transitions out
        };
    }

    /**
     * Get all valid transition states from current state.
     *
     * @return immutable set of allowed target states
     */
    public Set<LifecycleState> getAllowedTransitions() {
        return switch (this) {
            case CREATED -> EnumSet.of(STARTING);
            case STARTING -> EnumSet.of(RUNNING, FAILED);
            case RUNNING -> EnumSet.of(STOPPING, FAILED);
            case STOPPING -> EnumSet.of(STOPPED, FAILED);
            case STOPPED -> EnumSet.of(STARTING, FAILED);
            case FAILED -> EnumSet.noneOf(LifecycleState.class);
        };
    }
}
