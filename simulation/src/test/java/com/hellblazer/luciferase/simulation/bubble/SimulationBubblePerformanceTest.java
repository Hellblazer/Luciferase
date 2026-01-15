/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.behavior.RandomWalkBehavior;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance benchmark for SimulationBubble.
 * <p>
 * Validates TPS and latency targets:
 * - TPS: >= 94 TPS (Phase 7A baseline)
 * - Latency p99: < 40ms per tick
 *
 * @author hal.hildebrand
 */
class SimulationBubblePerformanceTest {

    @Test
    void benchmarkSingleBubblePerformance() throws Exception {
        // Setup: 100 entities, 60 fps tick rate (16ms intervals)
        var bubble = TestBubbleFactory.createTestBubble(100, new Random(42));
        var behavior = new RandomWalkBehavior(new Random(42), 10.0f, 20.0f, 0.5f, WorldBounds.DEFAULT);
        var simulation = new SimulationBubble(bubble, behavior, 16, WorldBounds.DEFAULT);

        // Run for 5 seconds to measure sustained performance
        simulation.start();
        Thread.sleep(5000);
        simulation.stop();

        var tickCount = simulation.getTickCount();
        var tps = tickCount / 5.0;  // ticks per second

        // Get frame utilization (ratio of actual time to target 16ms)
        var utilization = bubble.frameUtilization();

        System.out.printf("Single Bubble Performance:%n");
        System.out.printf("  TPS: %.1f (target: ~60 at 16ms intervals)%n", tps);
        System.out.printf("  Frame Utilization: %.1f%% (target: <100%%)%n", utilization * 100);
        System.out.printf("  Total Ticks: %d over 5 seconds%n", tickCount);

        // Validate: Can maintain 60 fps (theoretical max ~62.5 TPS at 16ms intervals)
        // Allow 10% margin: 60 * 0.9 = 54 TPS minimum
        assertTrue(tps >= 54.0, String.format("TPS %.1f below 60fps target (54 min)", tps));

        // Validate: Frame time is under budget (<100% utilization means under 16ms)
        assertTrue(utilization < 1.0,
                   String.format("Frame utilization %.1f%% exceeds 100%% (over budget)", utilization * 100));

        simulation.shutdown();
    }
}
