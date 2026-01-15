/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Validates cross-process entity migrations in a distributed simulation.
 * <p>
 * Orchestrates migrations between bubbles and processes, tracking results
 * and validating entity retention.
 * <p>
 * Phase 6B5.4: Cross-Process Migration Validation
 *
 * @author hal.hildebrand
 */
public class CrossProcessMigrationValidator {

    private static final Logger log = LoggerFactory.getLogger(CrossProcessMigrationValidator.class);

    private final TestProcessCluster cluster;
    private final DistributedEntityFactory entityFactory;
    private final MigrationPathSelector pathSelector;
    private final ExecutorService executor;
    private final AtomicLong totalMigrations = new AtomicLong(0);
    private final AtomicLong successfulMigrations = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private volatile Clock clock = Clock.system();
    private volatile boolean running = true;

    /**
     * Creates a new migration validator.
     *
     * @param cluster       the test process cluster
     * @param entityFactory the entity factory
     */
    public CrossProcessMigrationValidator(TestProcessCluster cluster, DistributedEntityFactory entityFactory) {
        this.cluster = cluster;
        this.entityFactory = entityFactory;
        this.pathSelector = new MigrationPathSelector(cluster.getTopology());
        this.executor = Executors.newFixedThreadPool(4, r -> {
            var t = new Thread(r, "migration-validator");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Sets the clock to use for timing (for testing clock skew).
     *
     * @param clock the clock implementation
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Migrates an entity from source to destination bubble.
     *
     * @param entityId     entity UUID
     * @param sourceBubble source bubble UUID
     * @param destBubble   destination bubble UUID
     * @return migration result summary
     */
    public MigrationResultSummary migrateEntity(UUID entityId, UUID sourceBubble, UUID destBubble) {
        totalMigrations.incrementAndGet();
        var startTime = clock.currentTimeMillis();

        // Validate path
        var validPaths = pathSelector.getValidDestinations(sourceBubble);
        if (!validPaths.contains(destBubble)) {
            return new MigrationResultSummary(entityId, false, "INVALID_PATH", 0);
        }

        // Check entity is actually in source
        var currentBubble = entityFactory.getBubbleForEntity(entityId);
        if (!sourceBubble.equals(currentBubble)) {
            return new MigrationResultSummary(entityId, false, "ENTITY_NOT_IN_SOURCE", 0);
        }

        try {
            // Perform migration (simplified for validation)
            // In full implementation, this would use CrossProcessMigration
            var accountant = cluster.getEntityAccountant();
            var migrationSucceeded = accountant.moveBetweenBubbles(entityId, sourceBubble, destBubble);

            if (!migrationSucceeded) {
                // Entity was not in source bubble - likely already migrated by concurrent operation
                cluster.getMetrics().recordMigrationFailure();
                return new MigrationResultSummary(entityId, false, "CONCURRENT_MIGRATION_CONFLICT", 0);
            }

            // Update factory tracking
            updateEntityLocation(entityId, destBubble);

            var latency = clock.currentTimeMillis() - startTime;
            totalLatencyMs.addAndGet(latency);
            successfulMigrations.incrementAndGet();

            // Update cluster metrics
            cluster.getMetrics().recordMigrationSuccess(latency);

            return new MigrationResultSummary(entityId, true, null, latency);
        } catch (Exception e) {
            cluster.getMetrics().recordMigrationFailure();
            return new MigrationResultSummary(entityId, false, e.getMessage(), 0);
        }
    }

    /**
     * Migrates an entity asynchronously.
     *
     * @param entityId     entity UUID
     * @param sourceBubble source bubble UUID
     * @param destBubble   destination bubble UUID
     * @param callback     callback for result
     */
    public void migrateEntityAsync(UUID entityId, UUID sourceBubble, UUID destBubble,
                                   Consumer<MigrationResultSummary> callback) {
        executor.submit(() -> {
            var result = migrateEntity(entityId, sourceBubble, destBubble);
            callback.accept(result);
        });
    }

    /**
     * Migrates an entity to a random neighbor bubble.
     *
     * @param entityId entity UUID
     * @param callback callback for result
     */
    public void migrateToRandomNeighborAsync(UUID entityId, Consumer<MigrationResultSummary> callback) {
        executor.submit(() -> {
            var sourceBubble = entityFactory.getBubbleForEntity(entityId);
            if (sourceBubble == null) {
                callback.accept(new MigrationResultSummary(entityId, false, "ENTITY_NOT_FOUND", 0));
                return;
            }

            var neighbors = cluster.getTopology().getNeighbors(sourceBubble);
            if (neighbors.isEmpty()) {
                callback.accept(new MigrationResultSummary(entityId, false, "NO_NEIGHBORS", 0));
                return;
            }

            var dest = neighbors.iterator().next();
            var result = migrateEntity(entityId, sourceBubble, dest);
            callback.accept(result);
        });
    }

    /**
     * Migrates a batch of entities to random neighbors.
     *
     * @param count number of migrations to perform
     * @return list of migration results
     */
    public List<MigrationResultSummary> migrateBatch(int count) {
        var results = new ArrayList<MigrationResultSummary>();
        var entities = new ArrayList<>(entityFactory.getAllEntityIds());
        var random = new Random();

        for (int i = 0; i < count; i++) {
            var entityId = entities.get(random.nextInt(entities.size()));
            var sourceBubble = entityFactory.getBubbleForEntity(entityId);

            if (sourceBubble != null) {
                var neighbors = new ArrayList<>(cluster.getTopology().getNeighbors(sourceBubble));
                if (!neighbors.isEmpty()) {
                    var destBubble = neighbors.get(random.nextInt(neighbors.size()));
                    results.add(migrateEntity(entityId, sourceBubble, destBubble));
                }
            }
        }

        return results;
    }

    /**
     * Measures migration throughput over a time period.
     *
     * @param durationMs   duration in milliseconds
     * @param targetCount  target number of migrations
     * @return throughput metrics
     */
    public ThroughputMetrics measureThroughput(long durationMs, int targetCount) throws InterruptedException {
        var startTime = clock.currentTimeMillis();
        var completed = new AtomicInteger(0);
        var latch = new CountDownLatch(targetCount);
        var entities = new ArrayList<>(entityFactory.getAllEntityIds());
        var random = new Random();

        // Submit migrations
        for (int i = 0; i < targetCount; i++) {
            var entityId = entities.get(random.nextInt(entities.size()));
            migrateToRandomNeighborAsync(entityId, result -> {
                completed.incrementAndGet();
                latch.countDown();
            });
        }

        // Wait for completion or timeout
        latch.await(durationMs, TimeUnit.MILLISECONDS);

        var elapsed = clock.currentTimeMillis() - startTime;
        var actualTPS = completed.get() * 1000.0 / elapsed;

        return new ThroughputMetrics(targetCount, completed.get(), elapsed, actualTPS);
    }

    /**
     * Gets migration metrics.
     *
     * @return metrics snapshot
     */
    public MigrationMetricsSummary getMetrics() {
        var total = totalMigrations.get();
        var successful = successfulMigrations.get();
        var avgLatency = successful > 0 ? (double) totalLatencyMs.get() / successful : 0;
        var successRate = total > 0 ? (successful * 100.0 / total) : 100.0;

        return new MigrationMetricsSummary(total, successful, successRate, avgLatency);
    }

    /**
     * Checks if the validator is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Shuts down the validator.
     */
    public void shutdown() {
        running = false;
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateEntityLocation(UUID entityId, UUID newBubble) {
        // Use reflection to update entityFactory's internal map
        // (In production, this would be handled by proper encapsulation)
        try {
            var field = DistributedEntityFactory.class.getDeclaredField("entityToBubble");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (Map<UUID, UUID>) field.get(entityFactory);
            map.put(entityId, newBubble);
        } catch (Exception e) {
            log.warn("Failed to update entity location: {}", e.getMessage());
        }
    }
}

/**
 * Summary of a migration result.
 */
record MigrationResultSummary(UUID entityId, boolean success, String error, long latencyMs) {
}

/**
 * Throughput measurement metrics.
 */
record ThroughputMetrics(int targetCount, int completedCount, long elapsedMs, double actualTPS) {
}

/**
 * Summary of migration metrics.
 */
record MigrationMetricsSummary(long totalMigrations, long successfulMigrations,
                                double successRate, double averageLatencyMs) {
}
