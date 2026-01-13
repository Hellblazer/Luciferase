/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus.demo;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects runtime metrics during demo execution.
 * <p>
 * Thread-safe metrics collection for:
 * - Migration throughput and latencies
 * - Entity spawning and retention
 * - Failure injection and recovery timing
 * - Entity balance across bubbles
 * <p>
 * Phase 8E Day 1: Demo Runner and Validation
 *
 * @author hal.hildebrand
 */
public class DemoMetricsCollector {

    // Migration metrics
    private final AtomicInteger totalMigrations = new AtomicInteger(0);
    private final AtomicInteger successfulMigrations = new AtomicInteger(0);
    private final AtomicInteger failedMigrations = new AtomicInteger(0);

    // Migration latencies (synchronized for percentile calculation)
    private final List<Long> migrationLatencies = Collections.synchronizedList(new ArrayList<>());

    // Entity metrics
    private final AtomicInteger entitiesSpawned = new AtomicInteger(0);
    private final Set<UUID> retainedEntities = ConcurrentHashMap.newKeySet();

    // Entity balance tracking: bubbleIndex -> entity count
    private final ConcurrentHashMap<Integer, AtomicInteger> bubbleEntityCounts = new ConcurrentHashMap<>();

    // Timing metrics
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final AtomicLong endTimeMs = new AtomicLong(0);
    private final AtomicLong failureInjectionTimeMs = new AtomicLong(0);
    private final AtomicLong failureDetectedTimeMs = new AtomicLong(0);
    private final AtomicLong recoveryCompleteTimeMs = new AtomicLong(0);
    private volatile Clock clock = Clock.system();

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Start metrics collection.
     */
    public void startCollection() {
        startTimeMs.set(clock.currentTimeMillis());
    }

    /**
     * End metrics collection.
     */
    public void endCollection() {
        endTimeMs.set(clock.currentTimeMillis());
    }

    /**
     * Record a migration attempt.
     *
     * @param entityId     Entity being migrated
     * @param approvalTime Time taken for consensus approval (milliseconds)
     */
    public void recordMigration(UUID entityId, long approvalTime) {
        totalMigrations.incrementAndGet();
        migrationLatencies.add(approvalTime);
    }

    /**
     * Record migration approval result.
     *
     * @param approved true if migration approved, false if rejected
     */
    public void recordMigrationApproval(boolean approved) {
        if (approved) {
            successfulMigrations.incrementAndGet();
        } else {
            failedMigrations.incrementAndGet();
        }
    }

    /**
     * Record entity spawned in bubble.
     *
     * @param bubbleIndex Bubble index where entity spawned
     * @param entityId    Entity ID
     */
    public void recordEntitySpawned(int bubbleIndex, UUID entityId) {
        entitiesSpawned.incrementAndGet();
        bubbleEntityCounts.computeIfAbsent(bubbleIndex, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * Record entity retained at end of simulation.
     *
     * @param entityId Entity ID
     */
    public void recordEntityRetained(UUID entityId) {
        retainedEntities.add(entityId);
    }

    /**
     * Record failure injection time.
     */
    public void recordFailureInjection() {
        failureInjectionTimeMs.set(clock.currentTimeMillis());
    }

    /**
     * Record failure detection time.
     */
    public void recordFailureDetected() {
        failureDetectedTimeMs.set(clock.currentTimeMillis());
    }

    /**
     * Record recovery completion time.
     */
    public void recordRecoveryComplete() {
        recoveryCompleteTimeMs.set(clock.currentTimeMillis());
    }

    // Accessors for raw metrics

    public int totalMigrations() {
        return totalMigrations.get();
    }

    public int successfulMigrations() {
        return successfulMigrations.get();
    }

    public int failedMigrations() {
        return failedMigrations.get();
    }

    public int entitiesSpawned() {
        return entitiesSpawned.get();
    }

    public int entitiesRetained() {
        return retainedEntities.size();
    }

    public long startTimeMs() {
        return startTimeMs.get();
    }

    public long endTimeMs() {
        return endTimeMs.get();
    }

    public long failureInjectionTimeMs() {
        return failureInjectionTimeMs.get();
    }

    public long failureDetectedTimeMs() {
        return failureDetectedTimeMs.get();
    }

    public long recoveryCompleteTimeMs() {
        return recoveryCompleteTimeMs.get();
    }

    // Calculated metrics

    /**
     * Get migration throughput (migrations per second).
     *
     * @return Throughput in migrations/sec
     */
    public double getThroughput() {
        var runtimeSeconds = getTotalRuntimeMs() / 1000.0;
        if (runtimeSeconds <= 0) {
            return 0.0;
        }
        return successfulMigrations.get() / runtimeSeconds;
    }

    /**
     * Get 50th percentile (median) migration latency.
     *
     * @return p50 latency in milliseconds
     */
    public long getLatencyP50() {
        return calculatePercentile(50);
    }

    /**
     * Get 95th percentile migration latency.
     *
     * @return p95 latency in milliseconds
     */
    public long getLatencyP95() {
        return calculatePercentile(95);
    }

    /**
     * Get 99th percentile migration latency.
     *
     * @return p99 latency in milliseconds
     */
    public long getLatencyP99() {
        return calculatePercentile(99);
    }

    /**
     * Get entity retention rate (0.0 to 1.0).
     *
     * @return Retention rate
     */
    public double getRetentionRate() {
        if (entitiesSpawned.get() == 0) {
            return 0.0;
        }
        return (double) retainedEntities.size() / entitiesSpawned.get();
    }

    /**
     * Get entity balance deviation from ideal distribution.
     * <p>
     * Returns the maximum deviation percentage from ideal balance.
     * For 100 entities across 4 bubbles, ideal is 25 per bubble.
     *
     * @return Maximum deviation percentage (0.0 to 1.0)
     */
    public double getEntityBalance() {
        if (bubbleEntityCounts.isEmpty()) {
            return 0.0;
        }

        var totalEntities = entitiesSpawned.get();
        var bubbleCount = bubbleEntityCounts.size();
        var idealPerBubble = totalEntities / (double) bubbleCount;

        // Calculate max deviation
        var maxDeviation = 0.0;
        for (var count : bubbleEntityCounts.values()) {
            var deviation = Math.abs(count.get() - idealPerBubble) / idealPerBubble;
            maxDeviation = Math.max(maxDeviation, deviation);
        }

        return maxDeviation;
    }

    /**
     * Get recovery time after failure injection.
     *
     * @return Recovery time in milliseconds, or 0 if not recorded
     */
    public long getRecoveryTimeMs() {
        var injectionTime = failureInjectionTimeMs.get();
        var recoveryTime = recoveryCompleteTimeMs.get();

        if (injectionTime == 0 || recoveryTime == 0) {
            return 0;
        }

        return recoveryTime - injectionTime;
    }

    /**
     * Get total runtime.
     *
     * @return Runtime in milliseconds
     */
    public long getTotalRuntimeMs() {
        return endTimeMs.get() - startTimeMs.get();
    }

    /**
     * Calculate percentile from latency list.
     *
     * @param percentile Percentile to calculate (0-100)
     * @return Percentile value in milliseconds
     */
    private long calculatePercentile(int percentile) {
        if (migrationLatencies.isEmpty()) {
            return 0;
        }

        // Create sorted copy
        var sorted = new ArrayList<>(migrationLatencies);
        Collections.sort(sorted);

        // Calculate index
        var index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index);
    }
}
