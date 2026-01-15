/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.config;

import java.util.concurrent.atomic.LongAdder;

/**
 * Metrics for simulation observability.
 * <p>
 * Thread-safe counters for monitoring simulation performance.
 *
 * @author hal.hildebrand
 */
public class SimulationMetrics {

    private final LongAdder totalTicks = new LongAdder();
    private final LongAdder totalFrameTimeNs = new LongAdder();
    private final LongAdder totalEntitiesProcessed = new LongAdder();
    private final LongAdder maxFrameTimeNs = new LongAdder();

    private volatile long lastFrameTimeNs = 0;
    private volatile int lastEntityCount = 0;

    /**
     * Record a completed tick.
     *
     * @param frameTimeNs Time taken for the tick in nanoseconds
     * @param entityCount Number of entities processed
     */
    public void recordTick(long frameTimeNs, int entityCount) {
        totalTicks.increment();
        totalFrameTimeNs.add(frameTimeNs);
        totalEntitiesProcessed.add(entityCount);
        lastFrameTimeNs = frameTimeNs;
        lastEntityCount = entityCount;

        // Track max frame time
        if (frameTimeNs > maxFrameTimeNs.sum()) {
            maxFrameTimeNs.reset();
            maxFrameTimeNs.add(frameTimeNs);
        }
    }

    /**
     * Get total number of ticks executed.
     */
    public long getTotalTicks() {
        return totalTicks.sum();
    }

    /**
     * Get average frame time in milliseconds.
     */
    public double getAverageFrameTimeMs() {
        long ticks = totalTicks.sum();
        if (ticks == 0) return 0;
        return totalFrameTimeNs.sum() / (double) ticks / 1_000_000.0;
    }

    /**
     * Get maximum frame time in milliseconds.
     */
    public double getMaxFrameTimeMs() {
        return maxFrameTimeNs.sum() / 1_000_000.0;
    }

    /**
     * Get last frame time in milliseconds.
     */
    public double getLastFrameTimeMs() {
        return lastFrameTimeNs / 1_000_000.0;
    }

    /**
     * Get last entity count.
     */
    public int getLastEntityCount() {
        return lastEntityCount;
    }

    /**
     * Get total entities processed across all ticks.
     */
    public long getTotalEntitiesProcessed() {
        return totalEntitiesProcessed.sum();
    }

    /**
     * Get average entities per tick.
     */
    public double getAverageEntitiesPerTick() {
        long ticks = totalTicks.sum();
        if (ticks == 0) return 0;
        return totalEntitiesProcessed.sum() / (double) ticks;
    }

    /**
     * Get current ticks per second based on average frame time.
     */
    public double getTicksPerSecond() {
        double avgMs = getAverageFrameTimeMs();
        if (avgMs <= 0) return 0;
        return 1000.0 / avgMs;
    }

    /**
     * Reset all metrics.
     */
    public void reset() {
        totalTicks.reset();
        totalFrameTimeNs.reset();
        totalEntitiesProcessed.reset();
        maxFrameTimeNs.reset();
        lastFrameTimeNs = 0;
        lastEntityCount = 0;
    }

    @Override
    public String toString() {
        return String.format(
            "SimulationMetrics{ticks=%d, avgFrame=%.2fms, maxFrame=%.2fms, avgEntities=%.1f, tps=%.1f}",
            getTotalTicks(), getAverageFrameTimeMs(), getMaxFrameTimeMs(),
            getAverageEntitiesPerTick(), getTicksPerSecond()
        );
    }
}
