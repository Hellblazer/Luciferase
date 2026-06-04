/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Luciferase-0frcy.74: VolumeAnimator's per-frame sleep duration must clamp to
 * non-negative. When frame work plus event overhead exceeds the frame budget,
 * the raw {@code frameRateNs - duration - eventOverhead} is negative; passing a
 * negative duration to {@code Kronos.sleep} has unspecified (potentially
 * stalling) behavior. The fix mirrors RealTimeController.tickLoop()'s
 * {@code if (sleepNs > 0)} guard.
 *
 * @author hal.hildebrand
 */
class VolumeAnimatorFrameSleepTest {

    @Test
    void frameSleepClampsNegativeToZero() {
        long frameRateNs = 16_000_000L; // ~60fps budget

        // Work + overhead exceeds budget -> raw value negative -> must clamp to 0.
        assertEquals(0L, VolumeAnimator.frameSleepNs(frameRateNs, 20_000_000L, 5_000_000L),
            "Overrun must clamp to zero, never a negative sleep");

        // Within budget: remaining time preserved.
        assertEquals(6_000_000L, VolumeAnimator.frameSleepNs(frameRateNs, 8_000_000L, 2_000_000L),
            "Within budget, remaining time is returned");

        // Exactly-over boundary clamps to zero.
        assertEquals(0L, VolumeAnimator.frameSleepNs(frameRateNs, frameRateNs, 1L),
            "Exactly-over boundary clamps to zero");
    }
}
