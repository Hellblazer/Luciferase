/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for LifecycleState enum and state transitions.
 * Validates the lifecycle state machine follows the contract:
 * <pre>
 * CREATED → STARTING → RUNNING → STOPPING → STOPPED
 *              ↓ (error) ↓
 *            FAILED
 * </pre>
 *
 * @author hal.hildebrand
 */
class LifecycleStateTest {

    @Test
    void testValidTransitions() {
        // CREATED → STARTING → RUNNING
        assertTrue(LifecycleState.CREATED.canTransitionTo(LifecycleState.STARTING),
                "CREATED should transition to STARTING");
        assertTrue(LifecycleState.STARTING.canTransitionTo(LifecycleState.RUNNING),
                "STARTING should transition to RUNNING");

        // RUNNING → STOPPING → STOPPED
        assertTrue(LifecycleState.RUNNING.canTransitionTo(LifecycleState.STOPPING),
                "RUNNING should transition to STOPPING");
        assertTrue(LifecycleState.STOPPING.canTransitionTo(LifecycleState.STOPPED),
                "STOPPING should transition to STOPPED");

        // STOPPED → STARTING (restart path)
        assertTrue(LifecycleState.STOPPED.canTransitionTo(LifecycleState.STARTING),
                "STOPPED should allow restart via STARTING");
    }

    @Test
    void testInvalidTransitions() {
        // Can't skip states in normal flow
        assertFalse(LifecycleState.CREATED.canTransitionTo(LifecycleState.RUNNING),
                "CREATED should not skip directly to RUNNING");
        assertFalse(LifecycleState.STARTING.canTransitionTo(LifecycleState.STOPPED),
                "STARTING should not skip directly to STOPPED");
        assertFalse(LifecycleState.RUNNING.canTransitionTo(LifecycleState.STOPPED),
                "RUNNING should not skip directly to STOPPED");

        // Can't go backwards (except restart from STOPPED)
        assertFalse(LifecycleState.RUNNING.canTransitionTo(LifecycleState.STARTING),
                "RUNNING should not transition back to STARTING");
        assertFalse(LifecycleState.STOPPING.canTransitionTo(LifecycleState.RUNNING),
                "STOPPING should not transition back to RUNNING");
        assertFalse(LifecycleState.STOPPED.canTransitionTo(LifecycleState.STOPPING),
                "STOPPED should not transition back to STOPPING");

        // Can't restart from non-STOPPED states
        assertFalse(LifecycleState.CREATED.canTransitionTo(LifecycleState.CREATED),
                "CREATED should not transition to itself");
        assertFalse(LifecycleState.RUNNING.canTransitionTo(LifecycleState.CREATED),
                "RUNNING should not transition back to CREATED");
    }

    @Test
    void testFailedTransition() {
        // Any state can transition to FAILED on error
        assertTrue(LifecycleState.CREATED.canTransitionTo(LifecycleState.FAILED),
                "CREATED should be able to transition to FAILED");
        assertTrue(LifecycleState.STARTING.canTransitionTo(LifecycleState.FAILED),
                "STARTING should be able to transition to FAILED");
        assertTrue(LifecycleState.RUNNING.canTransitionTo(LifecycleState.FAILED),
                "RUNNING should be able to transition to FAILED");
        assertTrue(LifecycleState.STOPPING.canTransitionTo(LifecycleState.FAILED),
                "STOPPING should be able to transition to FAILED");
        assertTrue(LifecycleState.STOPPED.canTransitionTo(LifecycleState.FAILED),
                "STOPPED should be able to transition to FAILED");

        // FAILED is terminal - no transitions out
        assertFalse(LifecycleState.FAILED.canTransitionTo(LifecycleState.CREATED),
                "FAILED should not allow transition to CREATED");
        assertFalse(LifecycleState.FAILED.canTransitionTo(LifecycleState.STARTING),
                "FAILED should not allow transition to STARTING");
        assertFalse(LifecycleState.FAILED.canTransitionTo(LifecycleState.RUNNING),
                "FAILED should not allow transition to RUNNING");
        assertFalse(LifecycleState.FAILED.canTransitionTo(LifecycleState.STOPPING),
                "FAILED should not allow transition to STOPPING");
        assertFalse(LifecycleState.FAILED.canTransitionTo(LifecycleState.STOPPED),
                "FAILED should not allow transition to STOPPED");
        assertFalse(LifecycleState.FAILED.canTransitionTo(LifecycleState.FAILED),
                "FAILED should not transition to itself");
    }

    @Test
    void testInitialState() {
        // CREATED is the expected initial state for new components
        // This test documents the contract that components start in CREATED
        var expectedInitialState = LifecycleState.CREATED;
        assertNotNull(expectedInitialState, "CREATED state should exist");
        assertTrue(expectedInitialState.canTransitionTo(LifecycleState.STARTING),
                "Initial state should allow transition to STARTING");
    }

    @Test
    void testStateEnumValues() {
        // Verify all expected states exist
        var states = LifecycleState.values();
        assertEquals(6, states.length, "Should have exactly 6 lifecycle states");

        // Verify state names
        assertNotNull(LifecycleState.valueOf("CREATED"));
        assertNotNull(LifecycleState.valueOf("STARTING"));
        assertNotNull(LifecycleState.valueOf("RUNNING"));
        assertNotNull(LifecycleState.valueOf("STOPPING"));
        assertNotNull(LifecycleState.valueOf("STOPPED"));
        assertNotNull(LifecycleState.valueOf("FAILED"));
    }
}
