/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.animation;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Luciferase-9niuo: AnimationFrame.lastActive was seeded from {@code Clock.system().nanoTime()} by a field
 * initializer that runs during VolumeAnimator construction — BEFORE {@link VolumeAnimator#setClock} can swap
 * the clock off the system source. The first {@code cumulativeDelay} therefore subtracted a wall-clock
 * baseline from an injected-clock reading. {@code setClock} now re-seeds the baseline off the injected clock.
 *
 * <p>The accounting is exercised via the extracted {@code recordFrameTiming()} unit rather than {@code track()}
 * directly: with {@code running=true}, {@code track()} self-reschedules and infinite-recurses under
 * untransformed (non-PrimeMover) bytecode, so it cannot be unit-tested directly.
 */
class VolumeAnimatorClockTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void cumulativeDelayDerivesFromInjectedClock_notWallClockBaseline() {
        var animator = new VolumeAnimator("9niuo-clock-test");
        try {
            var testClock = new TestClock(0L);   // nanoTime() == 0
            animator.setClock(testClock);        // re-seeds frame.lastActive to the injected baseline (0)

            var frame = animator.getFrame();
            testClock.advance(1L);               // +1 ms == +1_000_000 ns on the injected clock

            frame.recordFrameTiming();

            // start - lastActive = 1_000_000 - 0. Pre-fix lastActive held a wall-clock nanoTime captured at
            // construction, so this would be a large (negative) garbage value, not the injected-clock delta.
            assertEquals(1_000_000L, frame.getCumulativeDelay(),
                         "first cumulativeDelay must be the injected-clock delta (1 ms), not a wall-clock baseline");
        } finally {
            try {
                animator.close();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }
}
