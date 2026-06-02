/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the honest default contract for the simulation recovery strategies (Luciferase-yogvu): without an explicit
 * {@code enableSimulatedRecovery()} call, {@code recover()} performs NO work and reports failure ("not implemented")
 * rather than fabricating success. This is the regression guard that the collapse is real — callers do not silently
 * get do-nothing recovery dressed up as success.
 *
 * @author hal.hildebrand
 */
class PartitionRecoveryNoOpDefaultTest {

    private static void assertNotImplemented(RecoveryResult result) {
        assertNotNull(result);
        assertFalse(result.success(), "default (flag-off) recovery must NOT report success");
        assertTrue(result.statusMessage().toLowerCase().contains("not implemented"),
                   "failure message must state recovery is not implemented; was: " + result.statusMessage());
    }

    @Test
    void defaultPartitionRecoveryIsNoOpByDefault() throws Exception {
        var partitionId = UUID.randomUUID();
        var recovery = new DefaultPartitionRecovery(partitionId, new InMemoryPartitionTopology());
        var handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());

        assertNotImplemented(recovery.recover(partitionId, handler).get());
    }

    @Test
    void barrierRecoveryIsNoOpByDefault() throws Exception {
        var recovery = new BarrierRecoveryImpl();
        var handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());

        assertNotImplemented(recovery.recover(UUID.randomUUID(), handler).get());
    }

    @Test
    void cascadingRecoveryIsNoOpByDefault() throws Exception {
        var recovery = new CascadingRecoveryImpl();
        var handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());

        assertNotImplemented(recovery.recover(UUID.randomUUID(), handler).get());
    }

    @Test
    void enableSimulatedRecoveryOptsIntoTheScaffolding() throws Exception {
        // Sanity: the opt-in flips the contract — the simulation runs and reports success (scaffolding only).
        var partitionId = UUID.randomUUID();
        var recovery = new DefaultPartitionRecovery(partitionId, new InMemoryPartitionTopology());
        recovery.enableSimulatedRecovery();
        var handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());

        var result = recovery.recover(partitionId, handler).get();
        assertTrue(result.success(), "with enableSimulatedRecovery() the simulated pipeline completes");
    }
}
