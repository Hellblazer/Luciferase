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
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GC Pause Integration Benchmarks for 8-Process Distributed Simulation.
 * <p>
 * Critical constraint: All GC pauses must be <40ms p99 to maintain real-time simulation performance.
 * <p>
 * Tests 3 configurations:
 * <ul>
 *   <li>4 processes, 800 entities (baseline from Phase 6B5)</li>
 *   <li>8 processes, 800 entities (uniform: 50 per bubble)</li>
 *   <li>8 processes, 1600 entities (skewed: avg 100 per bubble)</li>
 * </ul>
 * <p>
 * Phase 6B6: 8-Process Scaling & GC Benchmarking
 * Bead: Luciferase-1czq
 * <p>
 * Disabled in CI: GC pause benchmarks depend on GC behavior which varies in CI environments.
 *
 * @author hal.hildebrand
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "GC pause benchmarks depend on GC behavior which varies in CI environments")
class GCPauseIntegrationBenchmark {

    private static final Logger log = LoggerFactory.getLogger(GCPauseIntegrationBenchmark.class);

    // GC pause target: <40ms p99 (1 frame at 25fps)
    private static final long GC_PAUSE_P99_TARGET_MS = 40;
    private static final long GC_PAUSE_P95_TARGET_MS = 30;
    private static final int BENCHMARK_DURATION_MS = 30000; // 30 seconds

    private TestProcessCluster cluster;
    private DistributedEntityFactory entityFactory;
    private CrossProcessMigrationValidator migrationValidator;
    private GCPauseMeasurement gcMeasurement;
    private HeapMonitor heapMonitor;

    @BeforeEach
    void setUp() {
        // Cleanup before test
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterEach
    void tearDown() {
        if (migrationValidator != null) {
            migrationValidator.shutdown();
        }
        if (gcMeasurement != null) {
            gcMeasurement.stop();
        }
        if (heapMonitor != null) {
            heapMonitor.stop();
        }
        if (cluster != null) {
            cluster.stop();
        }
    }

    /**
     * Benchmark 1: GC Pauses - 4 Process Baseline (Phase 6B5)
     * <p>
     * Establishes GC pause baseline with 4 processes and 800 entities.
     */
    @Test
    void benchmarkGCPauses4Process800Entities() throws Exception {
        // Given: 4-process cluster (Phase 6B5 baseline)
        cluster = new TestProcessCluster(4, 2);
        cluster.start();
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);
        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Run simulation with GC measurement
        gcMeasurement = new GCPauseMeasurement();
        gcMeasurement.start();

        var startTime = System.currentTimeMillis();
        var totalMigrations = 0;
        while (System.currentTimeMillis() - startTime < BENCHMARK_DURATION_MS) {
            totalMigrations += migrationValidator.migrateBatch(15).size();
            Thread.sleep(100);
        }

        gcMeasurement.stop();
        var stats = gcMeasurement.getStats();

        // Then: Validate GC pause constraints
        log.info("4-Process Baseline GC Stats: p50={}ms, p95={}ms, p99={}ms, max={}ms, count={}, freq={} Hz",
                stats.p50Ms(), stats.p95Ms(), stats.p99Ms(), stats.maxMs(),
                stats.pauseCount(), String.format("%.2f", stats.pauseFrequency()));

        assertTrue(stats.p99Ms() < GC_PAUSE_P99_TARGET_MS,
                String.format("4-process p99 GC pause should be <%dms, got: %dms",
                        GC_PAUSE_P99_TARGET_MS, stats.p99Ms()));

        assertTrue(stats.p95Ms() < GC_PAUSE_P95_TARGET_MS,
                String.format("4-process p95 GC pause should be <%dms, got: %dms",
                        GC_PAUSE_P95_TARGET_MS, stats.p95Ms()));

        log.info("4-Process baseline: {} migrations, {} entities, GC p99={}ms PASS",
                totalMigrations, 800, stats.p99Ms());
    }

    /**
     * Benchmark 2: GC Pauses - 8 Process Uniform Distribution
     * <p>
     * Tests GC pause performance with 8 processes and uniform entity distribution (50 per bubble).
     */
    @Test
    void benchmarkGCPauses8ProcessUniform() throws Exception {
        // Given: 8-process cluster with 800 entities (50 per bubble)
        cluster = new TestProcessCluster(8, 2);
        cluster.start();
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);
        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Run simulation with GC measurement
        gcMeasurement = new GCPauseMeasurement();
        gcMeasurement.start();

        var startTime = System.currentTimeMillis();
        var totalMigrations = 0;
        while (System.currentTimeMillis() - startTime < BENCHMARK_DURATION_MS) {
            totalMigrations += migrationValidator.migrateBatch(20).size();
            Thread.sleep(100);
        }

        gcMeasurement.stop();
        var stats = gcMeasurement.getStats();

        // Then: Validate GC pause constraints at 8-process scale
        log.info("8-Process Uniform GC Stats: p50={}ms, p95={}ms, p99={}ms, max={}ms, count={}, freq={} Hz",
                stats.p50Ms(), stats.p95Ms(), stats.p99Ms(), stats.maxMs(),
                stats.pauseCount(), String.format("%.2f", stats.pauseFrequency()));

        assertTrue(stats.p99Ms() < GC_PAUSE_P99_TARGET_MS,
                String.format("8-process uniform p99 GC pause should be <%dms, got: %dms",
                        GC_PAUSE_P99_TARGET_MS, stats.p99Ms()));

        log.info("8-Process uniform: {} migrations, {} entities, GC p99={}ms PASS",
                totalMigrations, 800, stats.p99Ms());
    }

    /**
     * Benchmark 3: GC Pauses - 8 Process Skewed Distribution
     * <p>
     * Tests GC pause performance with 8 processes and skewed entity distribution (1600 entities).
     */
    @Test
    void benchmarkGCPauses8ProcessSkewed() throws Exception {
        // Given: 8-process cluster with skewed distribution (4 heavy @ 80%, 12 light @ 20%)
        cluster = new TestProcessCluster(8, 2);
        cluster.start();

        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(cluster.getTopology(), heavyIndices, 0.8, 42);
        entityFactory = new DistributedEntityFactory(cluster, strategy, 42);
        entityFactory.createEntities(1600);
        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Run simulation with GC measurement
        gcMeasurement = new GCPauseMeasurement();
        gcMeasurement.start();

        var startTime = System.currentTimeMillis();
        var totalMigrations = 0;
        while (System.currentTimeMillis() - startTime < BENCHMARK_DURATION_MS) {
            totalMigrations += migrationValidator.migrateBatch(25).size();
            Thread.sleep(100);
        }

        gcMeasurement.stop();
        var stats = gcMeasurement.getStats();

        // Then: Validate GC pause constraints with skewed load
        log.info("8-Process Skewed GC Stats: p50={}ms, p95={}ms, p99={}ms, max={}ms, count={}, freq={} Hz",
                stats.p50Ms(), stats.p95Ms(), stats.p99Ms(), stats.maxMs(),
                stats.pauseCount(), String.format("%.2f", stats.pauseFrequency()));

        assertTrue(stats.p99Ms() < GC_PAUSE_P99_TARGET_MS,
                String.format("8-process skewed p99 GC pause should be <%dms, got: %dms",
                        GC_PAUSE_P99_TARGET_MS, stats.p99Ms()));

        log.info("8-Process skewed: {} migrations, {} entities, GC p99={}ms PASS",
                totalMigrations, 1600, stats.p99Ms());
    }

    /**
     * Benchmark 4: Heap Profiling - 8 Process Skewed
     * <p>
     * Profiles heap usage during 8-process skewed simulation.
     */
    @Test
    void heapProfiling8ProcessSkewed() throws Exception {
        // Given: 8-process cluster with skewed distribution
        cluster = new TestProcessCluster(8, 2);
        cluster.start();

        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(cluster.getTopology(), heavyIndices, 0.8, 42);
        entityFactory = new DistributedEntityFactory(cluster, strategy, 42);
        entityFactory.createEntities(1600);
        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Run simulation with heap monitoring
        heapMonitor = new HeapMonitor();
        heapMonitor.start(100); // Sample every 100ms

        var startTime = System.currentTimeMillis();
        var totalMigrations = 0;
        while (System.currentTimeMillis() - startTime < 30000) { // 30 seconds
            totalMigrations += migrationValidator.migrateBatch(25).size();
            Thread.sleep(100);
        }

        heapMonitor.stop();

        // Then: Analyze heap profile
        var growthRateBytesPerSec = heapMonitor.getGrowthRate();
        var growthRateMBPerSec = growthRateBytesPerSec / (1024.0 * 1024.0);
        var peakMemoryMB = heapMonitor.getPeakMemory() / (1024.0 * 1024.0);
        var currentMemoryMB = heapMonitor.getCurrentMemory() / (1024.0 * 1024.0);

        log.info("Heap Profile: current={:.1f}MB, peak={:.1f}MB, growthRate={:.2f} MB/s",
                currentMemoryMB, peakMemoryMB, growthRateMBPerSec);

        // Heap should not grow uncontrollably (<10MB growth over 30 seconds = ~0.33 MB/s)
        assertTrue(growthRateMBPerSec < 0.5,
                String.format("Heap growth rate should be <0.5 MB/s, got: %.2f MB/s", growthRateMBPerSec));

        log.info("Heap profiling: {} migrations, heap growth {:.2f} MB/s PASS",
                totalMigrations, growthRateMBPerSec);
    }

    /**
     * Benchmark 5: GC Pause Distribution Analysis
     * <p>
     * Analyzes GC pause distribution characteristics across process scales.
     */
    @Test
    void gcPauseDistributionAnalysis() throws Exception {
        // Test 3 configurations
        var results = new java.util.ArrayList<String>();

        // Config 1: 4 processes
        cluster = new TestProcessCluster(4, 2);
        cluster.start();
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);
        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        gcMeasurement = new GCPauseMeasurement();
        gcMeasurement.start();
        runMigrations(10000); // 10 seconds
        gcMeasurement.stop();
        var stats4 = gcMeasurement.getStats();
        results.add(String.format("4-proc: p50=%dms, p95=%dms, p99=%dms", 
                stats4.p50Ms(), stats4.p95Ms(), stats4.p99Ms()));

        tearDown();
        setUp();

        // Config 2: 8 processes uniform
        cluster = new TestProcessCluster(8, 2);
        cluster.start();
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);
        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        gcMeasurement = new GCPauseMeasurement();
        gcMeasurement.start();
        runMigrations(10000);
        gcMeasurement.stop();
        var stats8 = gcMeasurement.getStats();
        results.add(String.format("8-proc uniform: p50=%dms, p95=%dms, p99=%dms",
                stats8.p50Ms(), stats8.p95Ms(), stats8.p99Ms()));

        tearDown();
        setUp();

        // Config 3: 8 processes skewed
        cluster = new TestProcessCluster(8, 2);
        cluster.start();
        var strategy = new SkewedDistributionStrategy(cluster.getTopology(), Set.of(0, 4, 8, 12), 0.8, 42);
        entityFactory = new DistributedEntityFactory(cluster, strategy, 42);
        entityFactory.createEntities(1600);
        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        gcMeasurement = new GCPauseMeasurement();
        gcMeasurement.start();
        runMigrations(10000);
        gcMeasurement.stop();
        var stats16 = gcMeasurement.getStats();
        results.add(String.format("8-proc skewed: p50=%dms, p95=%dms, p99=%dms",
                stats16.p50Ms(), stats16.p95Ms(), stats16.p99Ms()));

        // Then: Log comparison
        log.info("GC Pause Distribution Analysis:");
        for (var result : results) {
            log.info("  {}", result);
        }

        // Validate all meet target
        assertTrue(stats4.p99Ms() < GC_PAUSE_P99_TARGET_MS, "4-proc p99 exceeds target");
        assertTrue(stats8.p99Ms() < GC_PAUSE_P99_TARGET_MS, "8-proc uniform p99 exceeds target");
        assertTrue(stats16.p99Ms() < GC_PAUSE_P99_TARGET_MS, "8-proc skewed p99 exceeds target");
    }

    /**
     * Benchmark 6: Memory Leak Detection - 8 Process
     * <p>
     * Long-running test to detect memory leaks during 8-process simulation.
     */
    @Test
    void memoryLeakDetection8Process() throws Exception {
        // Given: 8-process cluster with skewed distribution
        cluster = new TestProcessCluster(8, 2);
        cluster.start();

        var strategy = new SkewedDistributionStrategy(cluster.getTopology(), Set.of(0, 4, 8, 12), 0.8, 42);
        entityFactory = new DistributedEntityFactory(cluster, strategy, 42);
        entityFactory.createEntities(1600);
        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Run for extended period with heap monitoring
        heapMonitor = new HeapMonitor();
        heapMonitor.start(200); // Sample every 200ms

        var startTime = System.currentTimeMillis();
        var totalMigrations = 0;
        while (System.currentTimeMillis() - startTime < 60000) { // 60 seconds
            totalMigrations += migrationValidator.migrateBatch(30).size();
            Thread.sleep(100);
        }

        heapMonitor.stop();
        var growthRateBytesPerSec = heapMonitor.getGrowthRate();
        var growthRateMBPerSec = growthRateBytesPerSec / (1024.0 * 1024.0);

        // Then: Validate no memory leak
        // Allow up to 0.3 MB/s growth (18MB over 60 seconds)
        log.info("Memory leak detection: {} migrations over 60s, heap growth {:.2f} MB/s",
                totalMigrations, growthRateMBPerSec);

        assertTrue(growthRateMBPerSec < 0.3,
                String.format("Memory leak detected: growth rate %.2f MB/s exceeds 0.3 MB/s threshold",
                        growthRateMBPerSec));

        log.info("Memory leak detection: No leak detected, growth {:.2f} MB/s PASS",
                growthRateMBPerSec);
    }

    private void runMigrations(long durationMs) throws InterruptedException {
        var startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < durationMs) {
            migrationValidator.migrateBatch(20);
            Thread.sleep(100);
        }
    }
}
