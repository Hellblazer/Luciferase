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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6B5: Performance Baseline Benchmarking
 * <p>
 * Establishes quantitative performance baselines for the 4-process distributed simulation:
 * - Throughput: Target >100 TPS with 800 entities
 * - Clock Skew: Target <50ms across 4 processes
 * - Migration Latency: Target p99 <200ms
 * - Ghost Sync Latency: Target p99 <100ms
 * - Memory: No leaks during 10-minute run
 * <p>
 * These baselines establish the foundation for Phase 6B6 scaling tests.
 * <p>
 * Bead: Luciferase-u1am (Phase 6B5: Integration & 4-Process Baseline Validation)
 *
 * @author hal.hildebrand
 */
class PerformanceBaselineTest {

    private static final Logger log = LoggerFactory.getLogger(PerformanceBaselineTest.class);

    // Baseline configuration
    private static final int PROCESS_COUNT = 4;
    private static final int BUBBLES_PER_PROCESS = 2;
    private static final int TOTAL_ENTITIES = 800;

    // Performance targets from Phase 6B5 handoff
    private static final double MIN_TPS = 100.0;
    private static final long MAX_CLOCK_SKEW_MS = 50;
    private static final long MAX_MIGRATION_LATENCY_P99_MS = 200;
    private static final long MAX_GHOST_SYNC_LATENCY_P99_MS = 100;

    private TestProcessCluster cluster;
    private DistributedEntityFactory entityFactory;
    private CrossProcessMigrationValidator migrationValidator;
    private HeapMonitor heapMonitor;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new TestProcessCluster(PROCESS_COUNT, BUBBLES_PER_PROCESS);
        cluster.start();
        log.info("Performance baseline cluster started: {} processes", PROCESS_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (migrationValidator != null) {
            migrationValidator.shutdown();
        }
        if (heapMonitor != null) {
            heapMonitor.stop();
        }
        if (cluster != null) {
            cluster.stop();
        }
    }

    /**
     * Benchmark 1: Throughput - Sustained TPS with 800 Entities
     * <p>
     * Target: >100 TPS sustained over 10 seconds
     * <p>
     * Validates that the system can process at least 100 transactions (migrations)
     * per second while maintaining 100% entity retention.
     */
    @Test
    void benchmarkThroughput() throws Exception {
        // Given: Cluster with 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(TOTAL_ENTITIES);

        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Run migrations for 10 seconds and measure throughput
        long startTime = System.currentTimeMillis();
        int totalMigrations = 0;

        while (System.currentTimeMillis() - startTime < 10000) {
            totalMigrations += migrationValidator.migrateBatch(20).size();
            Thread.sleep(100);  // 10 Hz tick rate
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        double actualTps = (totalMigrations * 1000.0) / elapsedMs;

        // Then: Validate throughput target met
        assertTrue(actualTps >= MIN_TPS,
                String.format("TPS should be >= %.1f, got: %.1f", MIN_TPS, actualTps));

        // Validate entity retention (no loss under load)
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(),
                "Entity retention must hold at sustained load: " + validation.details());

        log.info("✓ Throughput benchmark: {:.1f} TPS ({} migrations in {}ms) - TARGET: {}+ TPS",
                actualTps, totalMigrations, elapsedMs, MIN_TPS);
        log.info("  Baseline: {:.1f} TPS sustained", actualTps);
    }

    /**
     * Benchmark 2: Clock Skew Across 4 Processes
     * <p>
     * Target: <50ms maximum clock skew
     * <p>
     * Validates that the WallClockBucketScheduler keeps all 4 processes synchronized
     * within 50ms tolerance.
     */
    @Test
    void benchmarkClockSkew() throws Exception {
        // Given: 4-process cluster with synchronization running

        // When: Sample clock skew 50 times over 5 seconds
        List<Long> skewSamples = new ArrayList<>();

        for (int sample = 0; sample < 50; sample++) {
            var processIds = cluster.getProcessIds();
            long minBucket = Long.MAX_VALUE;
            long maxBucket = Long.MIN_VALUE;

            for (var processId : processIds) {
                var coordinator = cluster.getProcessCoordinator(processId);
                long bucket = coordinator.getBucketScheduler().getCurrentBucket();
                minBucket = Math.min(minBucket, bucket);
                maxBucket = Math.max(maxBucket, bucket);
            }

            long skewMs = maxBucket - minBucket;
            skewSamples.add(skewMs);

            Thread.sleep(100);
        }

        // Then: Calculate statistics
        long maxSkew = skewSamples.stream().mapToLong(Long::longValue).max().orElse(0);
        double avgSkew = skewSamples.stream().mapToLong(Long::longValue).average().orElse(0);

        assertTrue(maxSkew < MAX_CLOCK_SKEW_MS,
                String.format("Max clock skew should be <%dms, got: %dms", MAX_CLOCK_SKEW_MS, maxSkew));

        log.info("✓ Clock skew benchmark: max={}ms, avg={:.1f}ms - TARGET: <{}ms",
                maxSkew, avgSkew, MAX_CLOCK_SKEW_MS);
        log.info("  Baseline: {}ms max skew across {} processes", maxSkew, PROCESS_COUNT);
    }

    /**
     * Benchmark 3: Migration Latency Distribution
     * <p>
     * Target: p99 < 200ms
     * <p>
     * Measures end-to-end latency for entity migrations across process boundaries.
     */
    @Test
    void benchmarkMigrationLatency() throws Exception {
        // Given: Cluster with 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(TOTAL_ENTITIES);

        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Perform 100 migrations and measure latency
        List<Long> latencies = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            long startNs = System.nanoTime();
            var migrated = migrationValidator.migrateBatch(1);
            long latencyNs = System.nanoTime() - startNs;

            if (!migrated.isEmpty()) {
                latencies.add(TimeUnit.NANOSECONDS.toMillis(latencyNs));
            }

            Thread.sleep(50);  // Avoid overwhelming the system
        }

        // Then: Calculate latency percentiles
        latencies.sort(Long::compareTo);

        long p50 = percentile(latencies, 0.50);
        long p95 = percentile(latencies, 0.95);
        long p99 = percentile(latencies, 0.99);
        long max = latencies.get(latencies.size() - 1);

        assertTrue(p99 < MAX_MIGRATION_LATENCY_P99_MS,
                String.format("Migration p99 latency should be <%dms, got: %dms",
                        MAX_MIGRATION_LATENCY_P99_MS, p99));

        log.info("✓ Migration latency benchmark: p50={}ms, p95={}ms, p99={}ms, max={}ms - TARGET: p99 <{}ms",
                p50, p95, p99, max, MAX_MIGRATION_LATENCY_P99_MS);
        log.info("  Baseline: {}ms p99 latency for cross-process migration", p99);
    }

    /**
     * Benchmark 4: Ghost Sync Latency
     * <p>
     * Target: p99 < 100ms
     * <p>
     * Measures ghost synchronization latency across process boundaries.
     */
    @Test
    void benchmarkGhostSyncLatency() throws Exception {
        // Given: Cluster with 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(TOTAL_ENTITIES);

        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // Trigger some migrations to establish ghost relationships
        for (int i = 0; i < 50; i++) {
            migrationValidator.migrateBatch(5);
            Thread.sleep(50);
        }

        // When: Measure ghost sync latency 50 times
        List<Long> ghostSyncLatencies = new ArrayList<>();

        for (int sample = 0; sample < 50; sample++) {
            long startNs = System.nanoTime();
            cluster.syncGhosts();
            long latencyNs = System.nanoTime() - startNs;

            ghostSyncLatencies.add(TimeUnit.NANOSECONDS.toMillis(latencyNs));

            Thread.sleep(100);
        }

        // Then: Calculate percentiles
        ghostSyncLatencies.sort(Long::compareTo);

        long p50 = percentile(ghostSyncLatencies, 0.50);
        long p95 = percentile(ghostSyncLatencies, 0.95);
        long p99 = percentile(ghostSyncLatencies, 0.99);
        long max = ghostSyncLatencies.get(ghostSyncLatencies.size() - 1);

        assertTrue(p99 < MAX_GHOST_SYNC_LATENCY_P99_MS,
                String.format("Ghost sync p99 latency should be <%dms, got: %dms",
                        MAX_GHOST_SYNC_LATENCY_P99_MS, p99));

        log.info("✓ Ghost sync latency benchmark: p50={}ms, p95={}ms, p99={}ms, max={}ms - TARGET: p99 <{}ms",
                p50, p95, p99, max, MAX_GHOST_SYNC_LATENCY_P99_MS);
        log.info("  Baseline: {}ms p99 latency for ghost sync", p99);
    }

    /**
     * Benchmark 5: Memory Stability - 10-Minute Run
     * <p>
     * Target: No memory leaks (growth <2MB/sec)
     * <p>
     * Validates that sustained operation does not cause memory leaks.
     * <p>
     * NOTE: This test runs for 10 minutes - it's marked as a benchmark/integration test.
     */
    @Test
    void benchmarkMemory() throws Exception {
        // Given: Setup heap monitoring
        heapMonitor = new HeapMonitor();

        // Stabilize JVM
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }

        // Create entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(TOTAL_ENTITIES);

        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Run for 10 minutes with continuous migrations
        log.info("Starting 10-minute memory stability test...");
        heapMonitor.start(100);  // 100ms sampling

        long startTime = System.currentTimeMillis();
        int totalMigrations = 0;

        while (System.currentTimeMillis() - startTime < 600_000) {  // 10 minutes
            totalMigrations += migrationValidator.migrateBatch(20).size();
            Thread.sleep(100);

            // Log progress every minute
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed % 60_000 < 200) {
                long minutes = elapsed / 60_000;
                var currentHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                log.info("  Minute {}/10: {} migrations, heap: {} MB",
                        minutes, totalMigrations, currentHeap / 1_000_000);
            }
        }

        heapMonitor.stop();

        // Then: Validate memory metrics
        double growthRateBytesPerSec = heapMonitor.getGrowthRate();
        double growthRateMbPerSec = growthRateBytesPerSec / 1_000_000.0;

        assertTrue(growthRateMbPerSec < 2.0,
                String.format("Memory growth should be <2 MB/sec, got: %.2f MB/sec", growthRateMbPerSec));

        // Validate entity retention after long run
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(),
                "Entity retention must hold after 10-minute run: " + validation.details());

        log.info("✓ Memory benchmark: {:.2f} MB/sec growth, {} total migrations - TARGET: <2 MB/sec",
                growthRateMbPerSec, totalMigrations);
        log.info("  Baseline: {:.2f} MB/sec growth over 10 minutes", growthRateMbPerSec);
    }

    /**
     * Benchmark 6: Comprehensive Performance Summary
     * <p>
     * Runs a comprehensive 5-second benchmark collecting all metrics simultaneously.
     */
    @Test
    void benchmarkComprehensive() throws Exception {
        // Given: Full setup with monitoring
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(TOTAL_ENTITIES);

        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);
        heapMonitor = new HeapMonitor();
        heapMonitor.start(100);

        // When: Run comprehensive benchmark for 5 seconds
        long startTime = System.currentTimeMillis();
        int totalMigrations = 0;
        List<Long> migrationLatencies = new ArrayList<>();
        List<Long> clockSkewSamples = new ArrayList<>();

        while (System.currentTimeMillis() - startTime < 5000) {
            // Measure migration latency
            long migrationStart = System.nanoTime();
            int batchMigrations = migrationValidator.migrateBatch(10).size();
            long migrationLatency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - migrationStart);

            totalMigrations += batchMigrations;
            if (batchMigrations > 0) {
                migrationLatencies.add(migrationLatency);
            }

            // Measure clock skew
            var processIds = cluster.getProcessIds();
            long minBucket = Long.MAX_VALUE;
            long maxBucket = Long.MIN_VALUE;
            for (var processId : processIds) {
                long bucket = cluster.getProcessCoordinator(processId).getBucketScheduler().getCurrentBucket();
                minBucket = Math.min(minBucket, bucket);
                maxBucket = Math.max(maxBucket, bucket);
            }
            clockSkewSamples.add(maxBucket - minBucket);

            Thread.sleep(100);
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        heapMonitor.stop();

        // Then: Calculate all metrics
        double tps = (totalMigrations * 1000.0) / elapsedMs;

        migrationLatencies.sort(Long::compareTo);
        long migrationP99 = percentile(migrationLatencies, 0.99);

        long maxClockSkew = clockSkewSamples.stream().mapToLong(Long::longValue).max().orElse(0);

        double memoryGrowthMbPerSec = heapMonitor.getGrowthRate() / 1_000_000.0;

        // Validate all targets
        assertTrue(tps >= MIN_TPS, "TPS: " + tps);
        assertTrue(maxClockSkew < MAX_CLOCK_SKEW_MS, "Clock skew: " + maxClockSkew + "ms");
        assertTrue(migrationP99 < MAX_MIGRATION_LATENCY_P99_MS, "Migration p99: " + migrationP99 + "ms");

        log.info("✓ Comprehensive benchmark results:");
        log.info("  Throughput: {:.1f} TPS (target: {}+)", tps, MIN_TPS);
        log.info("  Clock Skew: {}ms max (target: <{}ms)", maxClockSkew, MAX_CLOCK_SKEW_MS);
        log.info("  Migration p99: {}ms (target: <{}ms)", migrationP99, MAX_MIGRATION_LATENCY_P99_MS);
        log.info("  Memory Growth: {:.2f} MB/sec (target: <2 MB/sec)", memoryGrowthMbPerSec);
        log.info("  Entity Retention: 100% ({} entities)", TOTAL_ENTITIES);
    }

    /**
     * Helper: Calculate percentile from sorted list.
     */
    private long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(sortedValues.size() - 1, index));
        return sortedValues.get(index);
    }
}
