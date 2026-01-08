/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for tetrahedral entity migration.
 * <p>
 * Tracks:
 * - Total migrations
 * - Migrations per bubble pair (source → destination)
 * - Migration failures
 * - Active cooldowns
 * <p>
 * Unlike 2D grid migration (8 fixed directions), tetrahedral migration
 * tracks arbitrary bubble-to-bubble migrations since topology is variable.
 * <p>
 * Thread-safe via atomic counters and concurrent maps.
 *
 * @author hal.hildebrand
 */
public class TetrahedralMigrationMetrics {

    private final Map<BubblePair, AtomicLong> migrationsPerPair;
    private final AtomicLong totalMigrations;
    private final AtomicLong migrationFailures;
    private final AtomicLong activeCooldowns;

    public TetrahedralMigrationMetrics() {
        this.migrationsPerPair = new ConcurrentHashMap<>();
        this.totalMigrations = new AtomicLong(0);
        this.migrationFailures = new AtomicLong(0);
        this.activeCooldowns = new AtomicLong(0);
    }

    /**
     * Record a successful migration between two bubbles.
     *
     * @param sourceKey      Source bubble key
     * @param destinationKey Destination bubble key
     */
    public void recordSuccessfulMigration(TetreeKey<?> sourceKey, TetreeKey<?> destinationKey) {
        var pair = new BubblePair(sourceKey, destinationKey);
        migrationsPerPair.computeIfAbsent(pair, k -> new AtomicLong(0)).incrementAndGet();
        totalMigrations.incrementAndGet();
    }

    /**
     * Record a migration failure.
     */
    public void recordFailedMigration() {
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
     * Get migration count for a specific bubble pair.
     *
     * @param sourceKey      Source bubble key
     * @param destinationKey Destination bubble key
     * @return Number of migrations from source to destination
     */
    public long getMigrationCount(TetreeKey<?> sourceKey, TetreeKey<?> destinationKey) {
        var pair = new BubblePair(sourceKey, destinationKey);
        var counter = migrationsPerPair.get(pair);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Get total migration count across all bubble pairs.
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
     * Get the number of unique bubble pairs that have had migrations.
     *
     * @return Number of bubble pairs with migrations
     */
    public int getUniquePairCount() {
        return migrationsPerPair.size();
    }

    /**
     * Reset all metrics to zero.
     */
    public void reset() {
        migrationsPerPair.clear();
        totalMigrations.set(0);
        migrationFailures.set(0);
        activeCooldowns.set(0);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder("TetrahedralMigrationMetrics[total=");
        sb.append(totalMigrations.get());
        sb.append(", failures=");
        sb.append(migrationFailures.get());
        sb.append(", cooldowns=");
        sb.append(activeCooldowns.get());
        sb.append(", uniquePairs=");
        sb.append(migrationsPerPair.size());
        sb.append("]");
        return sb.toString();
    }

    /**
     * Represents a source-destination bubble pair.
     * Used as a key for tracking migrations between specific bubbles.
     */
    private record BubblePair(TetreeKey<?> source, TetreeKey<?> destination) {
    }
}
