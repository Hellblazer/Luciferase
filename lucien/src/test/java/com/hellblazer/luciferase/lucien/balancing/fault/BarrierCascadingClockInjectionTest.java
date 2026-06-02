/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Clock-injection coverage for BarrierRecoveryImpl and CascadingRecoveryImpl (Luciferase-mt7hi). Their simulated
 * execution paths previously measured durations with {@code System.currentTimeMillis()} (non-deterministic under
 * test). With an injected fixed {@link TestClock}, the measured {@code durationMs} is clock-derived — exactly 0 when
 * the clock does not advance — rather than the real wall-clock sleep time.
 *
 * @author hal.hildebrand
 */
class BarrierCascadingClockInjectionTest {

    @Test
    void barrierRecoveryMeasuresDurationFromInjectedClock() throws Exception {
        var recovery = new BarrierRecoveryImpl().enableSimulatedRecovery();
        recovery.setClock(new TestClock(1000L)); // fixed — never advances during the run
        // maxRetries=1 so the simulated retry-backoff Thread.sleep is never reached — keeps the test fast.
        var handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig().withMaxRetries(1));

        var result = recovery.recover(UUID.randomUUID(), handler).get();

        assertEquals(0L, result.durationMs(),
                     "duration must come from the (fixed) injected clock, not the wall clock (Luciferase-mt7hi)");
    }

    @Test
    void cascadingRecoveryMeasuresDurationFromInjectedClock() throws Exception {
        var recovery = new CascadingRecoveryImpl().enableSimulatedRecovery();
        recovery.setClock(new TestClock(1000L));
        // maxRetries=1 so the simulated retry-backoff Thread.sleep is never reached — keeps the test fast.
        var handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig().withMaxRetries(1));

        var result = recovery.recover(UUID.randomUUID(), handler).get();

        assertEquals(0L, result.durationMs(),
                     "duration must come from the (fixed) injected clock, not the wall clock (Luciferase-mt7hi)");
    }
}
