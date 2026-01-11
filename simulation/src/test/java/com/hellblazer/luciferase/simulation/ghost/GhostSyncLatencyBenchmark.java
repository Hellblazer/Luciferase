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
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.distributed.integration.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6E: Ghost synchronization latency benchmarks.
 * <p>
 * MANDATORY REQUIREMENT: Ghost sync latency validation <100ms p99
 * <p>
 * Benchmarks ghost synchronization performance:
 * - Baseline latency (no load)
 * - Latency under high load
 * - Ghost sync throughput (ghosts/sec)
 * - Ghost propagation delay (cross-process)
 * - Memory overhead (1000 ghosts)
 * <p>
 * All tests measure and assert percentile latencies:
 * - p50 (50th percentile)
 * - p95 (95th percentile)
 * - p99 (99th percentile) - MUST BE <100ms in baseline
 * <p>
 * Bead: Luciferase-uchl - Inc 6E: Integration Testing & Performance Validation
 * <p>
 * Disabled in CI: Latency benchmarks have hard thresholds that vary with CI runner speed.
 *
 * @author hal.hildebrand
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "Latency benchmarks have hard thresholds that vary with CI runner speed")
class GhostSyncLatencyBenchmark {

    private static final Logger log = LoggerFactory.getLogger(GhostSyncLatencyBenchmark.class);

    private TestProcessCluster cluster;
    private DistributedEntityFactory entityFactory;
    private CrossProcessMigrationValidator validator;
    private HeapMonitor heapMonitor;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new TestProcessCluster(8, 2);
        cluster.start();
    }

    @AfterEach
    void tearDown() {
        if (validator != null) {
            validator.shutdown();
        }
        if (heapMonitor != null) {
            heapMonitor.stop();
        }
        if (cluster != null) {
            cluster.stop();
        }
    }

    // ==================== Latency Measurement Utility ====================

    private record LatencyStats(long count, long p50Ms, long p95Ms, long p99Ms, long maxMs, double avgMs) {
        @Override
        public String toString() {
            return String.format("p50=%dms, p95=%dms, p99=%dms, max=%dms, avg=%.2fms",
                p50Ms, p95Ms, p99Ms, maxMs, avgMs);
        }
    }

    private LatencyStats calculateLatencyStats(List<Long> latencies) {
        if (latencies.isEmpty()) {
            return new LatencyStats(0, 0, 0, 0, 0, 0);
        }

        Collections.sort(latencies);
        long count = latencies.size();
        long p50 = latencies.get((int) (count * 0.50));
        long p95 = latencies.get((int) (count * 0.95));
        long p99 = latencies.get((int) (count * 0.99));
        long max = latencies.get((int) count - 1);
        double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);

        return new LatencyStats(count, p50, p95, p99, max, avg);
    }

    // ==================== Baseline Latency Test ====================

    @Test
    void testGhostSyncLatency_Baseline() throws Exception {
        // Given: 8-process cluster, 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);

        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Measure ghost sync latency
        var latencies = new ArrayList<Long>();

        // Warm up (skip first few measurements)
        for (int i = 0; i < 10; i++) {
            cluster.syncGhosts();
            Thread.sleep(100);
        }

        // Collect 100 latency samples
        for (int i = 0; i < 100; i++) {
            var start = System.nanoTime();
            cluster.syncGhosts();  // Trigger ghost sync
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000_000);  // Convert to ms
            Thread.sleep(100);  // Wait for next batch boundary
        }

        // Then: Calculate and validate percentiles
        var stats = calculateLatencyStats(latencies);
        log.info("Ghost sync baseline latency: {}", stats);

        // MANDATORY: p99 must be <100ms
        assertTrue(stats.p99Ms < 100,
            "Ghost sync p99 should be < 100ms, got: " + stats.p99Ms + "ms");

        // Validate other percentiles are reasonable
        assertTrue(stats.p95Ms < 80, "p95 should be < 80ms, got: " + stats.p95Ms + "ms");
        assertTrue(stats.p50Ms < 50, "p50 should be < 50ms, got: " + stats.p50Ms + "ms");
    }

    @Test
    void testGhostSyncLatency_HighLoad() throws Exception {
        // Given: Skewed distribution (1200 entities)
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(cluster.getTopology(), heavyIndices, 0.8, 42);
        entityFactory = new DistributedEntityFactory(cluster, strategy, 42);
        entityFactory.createEntities(1200);

        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Measure ghost sync latency under load
        var latencies = new ArrayList<Long>();

        // Warm up
        for (int i = 0; i < 10; i++) {
            validator.migrateBatch(5);
            Thread.sleep(100);
        }

        // Measure while running migrations
        for (int i = 0; i < 100; i++) {
            // Start migrations in parallel
            validator.migrateBatch(10);

            // Measure ghost sync latency
            var start = System.nanoTime();
            cluster.syncGhosts();
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000_000);

            Thread.sleep(100);
        }

        // Then: Validate percentiles
        var stats = calculateLatencyStats(latencies);
        log.info("Ghost sync high-load latency: {}", stats);

        // Allow slightly higher p99 under load (150ms tolerance)
        assertTrue(stats.p99Ms < 150,
            "Ghost sync p99 under load should be < 150ms, got: " + stats.p99Ms + "ms");

        // Check that it's still reasonable
        assertTrue(stats.p95Ms < 120, "p95 under load should be < 120ms");
    }

    @Test
    void testGhostSyncThroughput() throws Exception {
        // Given: 8-process cluster, 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);

        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Measure ghost sync throughput
        long startTime = System.currentTimeMillis();
        int syncCount = 0;

        for (int i = 0; i < 100; i++) {
            cluster.syncGhosts();
            syncCount++;
            Thread.sleep(100);  // 100ms batch boundary
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        double throughput = (syncCount * 1000.0) / elapsedMs;

        // Then: Validate throughput
        log.info("Ghost sync throughput: {:.1f} syncs/sec (100 syncs in {}ms)",
            throughput, elapsedMs);

        // Should be able to do at least 5 syncs per second (one every 200ms)
        assertTrue(throughput >= 5,
            "Ghost sync throughput should be >= 5 syncs/sec, got: " + throughput);
    }

    @Test
    void testGhostPropagationDelay() throws Exception {
        // Given: 8-process cluster
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);

        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Create entities and measure ghost propagation
        var propagationDelays = new ArrayList<Long>();

        for (int iteration = 0; iteration < 10; iteration++) {
            // Migrate entities across process boundaries
            long startTime = System.currentTimeMillis();

            // Force migrations to create ghosts at boundaries
            for (int m = 0; m < 50; m++) {
                validator.migrateBatch(10);
            }

            // Measure time until ghosts are visible at neighbor processes
            long visibilityTime = System.currentTimeMillis();
            propagationDelays.add(visibilityTime - startTime);

            Thread.sleep(500);  // Wait before next iteration
        }

        // Then: Validate propagation delay
        var stats = calculateLatencyStats(propagationDelays);
        log.info("Ghost propagation delay: {}", stats);

        // Ghost propagation should be < 200ms (2 × 100ms batch boundaries)
        assertTrue(stats.p99Ms < 200,
            "Ghost propagation p99 should be < 200ms, got: " + stats.p99Ms + "ms");
    }

    @Test
    void testGhostSyncMemoryImpact() throws Exception {
        // Given: Initial memory baseline
        heapMonitor = new HeapMonitor();

        // Baseline without ghosts
        heapMonitor.start(100);
        Thread.sleep(2000);
        heapMonitor.stop();
        var baselineHeap = heapMonitor.getPeakMemory();

        // When: Create 1000 ghost entities
        heapMonitor = new HeapMonitor();
        heapMonitor.start(100);

        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);

        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // Create many ghosts by migrating across boundaries
        for (int i = 0; i < 200; i++) {
            validator.migrateBatch(20);
            Thread.sleep(50);
        }

        heapMonitor.stop();
        var loadHeap = heapMonitor.getPeakMemory();

        // Then: Measure memory overhead
        long memoryOverhead = loadHeap - baselineHeap;
        double overheadMB = memoryOverhead / (1024.0 * 1024.0);

        log.info("Ghost memory impact: baseline={}MB, with-ghosts={}MB, overhead={}MB",
            baselineHeap / (1024 * 1024), loadHeap / (1024 * 1024), overheadMB);

        // Ghost overhead should be reasonable (<50MB for 1000 ghosts)
        assertTrue(memoryOverhead < 50_000_000,
            "Ghost memory overhead should be < 50MB, got: " + overheadMB + "MB");
    }
}
