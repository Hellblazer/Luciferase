/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.config.SimulationMetrics;
import com.hellblazer.luciferase.simulation.von.VonBubble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics tracking and debug state management for two-bubble simulation.
 * Responsibilities:
 * - Migration metrics (to bubble1, to bubble2, failures)
 * - Entity count tracking
 * - Frame time metrics via SimulationMetrics
 * - Periodic logging
 * - Debug state snapshot generation
 *
 * Thread-safe via AtomicLong counters.
 *
 * @author hal.hildebrand
 */
public class SimulationTickMetrics {

    private static final Logger log = LoggerFactory.getLogger(SimulationTickMetrics.class);

    private final VonBubble bubble1;
    private final VonBubble bubble2;
    private final SimulationMetrics metrics;

    private final AtomicLong migrationsTo1 = new AtomicLong(0);
    private final AtomicLong migrationsTo2 = new AtomicLong(0);
    private final AtomicLong migrationFailures = new AtomicLong(0);

    /**
     * Create metrics tracker for two-bubble simulation.
     */
    public SimulationTickMetrics(VonBubble bubble1, VonBubble bubble2) {
        this.bubble1 = bubble1;
        this.bubble2 = bubble2;
        this.metrics = new SimulationMetrics();
    }

    /**
     * Record a migration to bubble 1.
     */
    public void recordMigrationTo1() {
        migrationsTo1.incrementAndGet();
    }

    /**
     * Record a migration to bubble 2.
     */
    public void recordMigrationTo2() {
        migrationsTo2.incrementAndGet();
    }

    /**
     * Record a migration failure.
     */
    public void recordMigrationFailure() {
        migrationFailures.incrementAndGet();
    }

    /**
     * Record tick metrics (frame time and entity count).
     */
    public void recordTick(long frameTimeNs) {
        int totalEntities = bubble1.entityCount() + bubble2.entityCount();
        metrics.recordTick(frameTimeNs, totalEntities);
    }

    /**
     * Log metrics periodically (every 10 seconds at 60fps = 600 ticks).
     */
    public void logPeriodic(long tickCount, int ghostCount1, int ghostCount2, int cooldownsActive) {
        if (tickCount > 0 && tickCount % 600 == 0) {
            log.debug("Tick {}: bubble1={}, bubble2={}, ghosts1={}, ghosts2={}, " +
                      "migrations(to1={}, to2={}, failures={}), cooldowns={}, {}",
                      tickCount, bubble1.entityCount(), bubble2.entityCount(),
                      ghostCount1, ghostCount2,
                      migrationsTo1.get(), migrationsTo2.get(), migrationFailures.get(),
                      cooldownsActive, metrics);
        }
    }

    /**
     * Get total migrations to bubble 1.
     */
    public long getMigrationsTo1() {
        return migrationsTo1.get();
    }

    /**
     * Get total migrations to bubble 2.
     */
    public long getMigrationsTo2() {
        return migrationsTo2.get();
    }

    /**
     * Get total migration failures.
     */
    public long getMigrationFailures() {
        return migrationFailures.get();
    }

    /**
     * Get simulation metrics.
     */
    public SimulationMetrics getMetrics() {
        return metrics;
    }

    /**
     * Get detailed debug state snapshot.
     */
    public DebugState getDebugState(long tickCount, int ghostCount1, int ghostCount2, int cooldownsActive) {
        return new DebugState(
            tickCount,
            bubble1.entityCount(),
            bubble2.entityCount(),
            ghostCount1,
            ghostCount2,
            migrationsTo1.get(),
            migrationsTo2.get(),
            migrationFailures.get(),
            cooldownsActive,
            metrics
        );
    }

    /**
     * Debug state snapshot for monitoring and debugging.
     */
    public record DebugState(
        long tickCount,
        int bubble1EntityCount,
        int bubble2EntityCount,
        int bubble1GhostCount,
        int bubble2GhostCount,
        long migrationsTo1,
        long migrationsTo2,
        long migrationFailures,
        int cooldownsActive,
        SimulationMetrics metrics
    ) {}

    @Override
    public String toString() {
        return String.format("SimulationTickMetrics[bubble1=%d, bubble2=%d, migrations(to1=%d, to2=%d, failures=%d), %s]",
                             bubble1.entityCount(), bubble2.entityCount(),
                             migrationsTo1.get(), migrationsTo2.get(), migrationFailures.get(),
                             metrics);
    }
}
