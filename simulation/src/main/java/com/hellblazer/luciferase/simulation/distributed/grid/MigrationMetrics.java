/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for multi-directional entity migration.
 * <p>
 * Tracks:
 * - Migrations per direction (8 directions)
 * - Migration failures
 * - Active cooldowns
 * <p>
 * Thread-safe via atomic counters.
 *
 * @author hal.hildebrand
 */
public class MigrationMetrics {

    private final Map<MigrationDirection, AtomicLong> migrationsPerDirection = new EnumMap<>(MigrationDirection.class);
    private final AtomicLong totalMigrations = new AtomicLong(0);
    private final AtomicLong migrationFailures = new AtomicLong(0);
    private final AtomicLong activeCooldowns = new AtomicLong(0);

    public MigrationMetrics() {
        // Initialize counters for all directions
        for (var direction : MigrationDirection.values()) {
            migrationsPerDirection.put(direction, new AtomicLong(0));
        }
    }

    /**
     * Record a successful migration in a specific direction.
     *
     * @param direction Direction of migration
     */
    public void recordMigration(MigrationDirection direction) {
        migrationsPerDirection.get(direction).incrementAndGet();
        totalMigrations.incrementAndGet();
    }

    /**
     * Record a migration failure.
     */
    public void recordFailure() {
        migrationFailures.incrementAndGet();
    }

    /**
     * Update the count of active cooldowns.
     *
     * @param count Current number of entities in cooldown
     */
    public void updateActiveCooldowns(int count) {
        activeCooldowns.set(count);
    }

    /**
     * Get migration count for a specific direction.
     *
     * @param direction Direction to query
     * @return Number of migrations in that direction
     */
    public long getMigrationCount(MigrationDirection direction) {
        return migrationsPerDirection.get(direction).get();
    }

    /**
     * Get total migration count across all directions.
     *
     * @return Total migrations
     */
    public long getTotalMigrations() {
        return totalMigrations.get();
    }

    /**
     * Get migration failure count.
     *
     * @return Number of failed migrations
     */
    public long getFailureCount() {
        return migrationFailures.get();
    }

    /**
     * Get current active cooldown count.
     *
     * @return Number of entities currently in cooldown
     */
    public long getActiveCooldownCount() {
        return activeCooldowns.get();
    }

    /**
     * Reset all metrics to zero.
     */
    public void reset() {
        for (var counter : migrationsPerDirection.values()) {
            counter.set(0);
        }
        totalMigrations.set(0);
        migrationFailures.set(0);
        activeCooldowns.set(0);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder("MigrationMetrics[total=");
        sb.append(totalMigrations.get());
        sb.append(", failures=");
        sb.append(migrationFailures.get());
        sb.append(", cooldowns=");
        sb.append(activeCooldowns.get());
        sb.append(", perDirection={");

        boolean first = true;
        for (var direction : MigrationDirection.values()) {
            long count = migrationsPerDirection.get(direction).get();
            if (count > 0) {
                if (!first) sb.append(", ");
                sb.append(direction.name()).append("=").append(count);
                first = false;
            }
        }
        sb.append("}]");
        return sb.toString();
    }
}
