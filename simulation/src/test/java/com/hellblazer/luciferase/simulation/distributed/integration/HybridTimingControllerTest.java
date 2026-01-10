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

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0: Inc7 Go/No-Go Validation Test
 * <p>
 * Validates that RealTimeController (autonomous bubble-local 100Hz ticking) can coexist
 * with BucketScheduler (100ms bucket-based coordination) without exceeding timing tolerances.
 * <p>
 * Success Criteria:
 * <ul>
 *   <li>Clock drift < 50ms max, < 10ms P95</li>
 *   <li>Event dispatch overhead < 5% of CPU time</li>
 *   <li>Entity retention 100% (zero losses)</li>
 * </ul>
 * <p>
 * GO/NO-GO Decision:
 * <ul>
 *   <li>GO: All criteria met - Proceed with Inc7 Phases 7A-7F</li>
 *   <li>NO-GO: Any criterion failed - Escalate to user, recommend aborting Inc7</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@Tag("phase0")
@Tag("inc7-gate")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HybridTimingControllerTest {

    private static final Logger log = LoggerFactory.getLogger(HybridTimingControllerTest.class);

    // Test configuration
    private static final int PROCESS_COUNT = 4;
    private static final int BUBBLES_PER_PROCESS = 2;
    private static final int TOTAL_BUBBLES = PROCESS_COUNT * BUBBLES_PER_PROCESS;  // 8
    private static final int ENTITIES_PER_BUBBLE = 100;
    private static final int TOTAL_ENTITIES = TOTAL_BUBBLES * ENTITIES_PER_BUBBLE;  // 800
    private static final int FULL_BUCKET_COUNT = 1000;
    private static final int QUICK_BUCKET_COUNT = 100;
    private static final long BUCKET_DURATION_MS = 100;

    // Success thresholds
    private static final long MAX_DRIFT_THRESHOLD_MS = 50;
    private static final long P95_DRIFT_THRESHOLD_MS = 10;
    private static final double MAX_OVERHEAD_PERCENT = 5.0;

    private TestProcessCluster cluster;
    private DistributedEntityFactory entityFactory;
    private Map<UUID, HybridBubbleController> bubbleControllers;
    private HybridTimingMetrics metrics;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new TestProcessCluster(PROCESS_COUNT, BUBBLES_PER_PROCESS);
        cluster.start();

        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(TOTAL_ENTITIES);

        bubbleControllers = new HashMap<>();
        metrics = new HybridTimingMetrics();

        log.info("Test setup complete: {} processes, {} bubbles, {} entities",
                PROCESS_COUNT, TOTAL_BUBBLES, TOTAL_ENTITIES);
    }

    @AfterEach
    void tearDown() {
        for (var controller : bubbleControllers.values()) {
            if (controller.isRunning()) {
                controller.stop();
            }
        }
        if (cluster != null && cluster.isRunning()) {
            cluster.stop();
        }
        log.info("Test teardown complete");
    }

    /**
     * Test 1: Full 1000-bucket validation (main go/no-go test)
     * <p>
     * Runs the complete hybrid timing validation:
     * - 1000 buckets (100 seconds)
     * - 8 bubbles with RealTimeController each
     * - Measures clock drift, overhead, and entity retention
     */
    @Test
    @Order(1)
    @Timeout(180)  // 3 minutes max
    void testHybridTimingArchitectureValidation() throws Exception {
        // Start all bubble controllers
        startBubbleControllers();

        log.info("Starting {} bucket validation with {} bubbles", FULL_BUCKET_COUNT, TOTAL_BUBBLES);
        long testStartTime = System.currentTimeMillis();

        // Main loop: 1000 buckets
        for (int bucket = 0; bucket < FULL_BUCKET_COUNT; bucket++) {
            // Let RealTimeControllers run for ~100ms
            Thread.sleep(BUCKET_DURATION_MS);

            // Collect clock drift at bucket boundary
            collectClockDrift(bucket);

            // Collect overhead
            collectOverhead(bucket);

            // Periodic validation (every 100 buckets)
            if (bucket % 100 == 0) {
                var validation = cluster.getEntityAccountant().validate();
                metrics.recordRetentionCheck(bucket, validation.success());

                var currentDrift = metrics.getMaxDrift();
                var currentOverhead = metrics.getAverageOverheadPercent();
                log.info("Bucket {}/{}: drift={}ms, overhead={:.2f}%, retention={}",
                        bucket, FULL_BUCKET_COUNT, currentDrift, currentOverhead,
                        validation.success() ? "OK" : "LOSS");
            }
        }

        // Stop all controllers
        stopBubbleControllers();

        long elapsedMs = System.currentTimeMillis() - testStartTime;
        log.info("Validation completed in {}ms ({} buckets)", elapsedMs, FULL_BUCKET_COUNT);

        // Collect final metrics
        var maxDrift = metrics.getMaxDrift();
        var p95Drift = metrics.getP95Drift();
        var avgOverhead = metrics.getAverageOverheadPercent();
        var retentionOk = metrics.allRetentionChecksPassed();

        // Log results
        log.info("=== Phase 0 Validation Results ===");
        log.info("Clock Drift (Max):  {}ms (threshold: {}ms) {}",
                maxDrift, MAX_DRIFT_THRESHOLD_MS, maxDrift < MAX_DRIFT_THRESHOLD_MS ? "PASS" : "FAIL");
        log.info("Clock Drift (P95):  {}ms (threshold: {}ms) {}",
                p95Drift, P95_DRIFT_THRESHOLD_MS, p95Drift < P95_DRIFT_THRESHOLD_MS ? "PASS" : "FAIL");
        log.info("Event Overhead:     {:.2f}% (threshold: {:.1f}%) {}",
                avgOverhead, MAX_OVERHEAD_PERCENT, avgOverhead < MAX_OVERHEAD_PERCENT ? "PASS" : "FAIL");
        log.info("Entity Retention:   {} {}",
                retentionOk ? "100%" : "LOSS DETECTED", retentionOk ? "PASS" : "FAIL");

        // Generate and save report
        try {
            metrics.writeReportToFile(Path.of(".pm/PHASE_0_VALIDATION_REPORT.md"));
        } catch (Exception e) {
            log.warn("Failed to write report: {}", e.getMessage());
        }

        // Determine verdict
        boolean allPass = maxDrift < MAX_DRIFT_THRESHOLD_MS
                && p95Drift < P95_DRIFT_THRESHOLD_MS
                && avgOverhead < MAX_OVERHEAD_PERCENT
                && retentionOk;

        if (allPass) {
            log.info("GO: All criteria met. Proceed with Inc7 Phases 7A-7F.");
        } else {
            log.error("NO-GO: One or more criteria failed. Escalate to user.");
        }

        // Assertions
        assertTrue(maxDrift < MAX_DRIFT_THRESHOLD_MS,
                "Clock drift must be < " + MAX_DRIFT_THRESHOLD_MS + "ms, got: " + maxDrift + "ms");
        assertTrue(p95Drift < P95_DRIFT_THRESHOLD_MS,
                "Clock drift P95 must be < " + P95_DRIFT_THRESHOLD_MS + "ms, got: " + p95Drift + "ms");
        assertTrue(avgOverhead < MAX_OVERHEAD_PERCENT,
                "Event overhead must be < " + MAX_OVERHEAD_PERCENT + "%, got: " + avgOverhead + "%");
        assertTrue(retentionOk, "Entity retention must be 100%");
    }

    /**
     * Test 2: Clock drift under 50ms (quick validation)
     */
    @Test
    @Order(2)
    @Timeout(30)
    void testClockDriftUnder50ms() throws Exception {
        startBubbleControllers();

        for (int bucket = 0; bucket < QUICK_BUCKET_COUNT; bucket++) {
            Thread.sleep(BUCKET_DURATION_MS);
            collectClockDrift(bucket);
        }

        stopBubbleControllers();

        var maxDrift = metrics.getMaxDrift();
        log.info("Clock drift test: max={}ms (threshold: {}ms)", maxDrift, MAX_DRIFT_THRESHOLD_MS);

        assertTrue(maxDrift < MAX_DRIFT_THRESHOLD_MS,
                "Clock drift < 50ms: got " + maxDrift + "ms");
    }

    /**
     * Test 3: Clock drift P95 under 10ms (quick validation)
     */
    @Test
    @Order(3)
    @Timeout(30)
    void testClockDriftP95Under10ms() throws Exception {
        startBubbleControllers();

        for (int bucket = 0; bucket < QUICK_BUCKET_COUNT; bucket++) {
            Thread.sleep(BUCKET_DURATION_MS);
            collectClockDrift(bucket);
        }

        stopBubbleControllers();

        var p95Drift = metrics.getP95Drift();
        log.info("Clock drift P95 test: p95={}ms (threshold: {}ms)", p95Drift, P95_DRIFT_THRESHOLD_MS);

        assertTrue(p95Drift < P95_DRIFT_THRESHOLD_MS,
                "Clock drift P95 < 10ms: got " + p95Drift + "ms");
    }

    /**
     * Test 4: Event overhead under 5%
     */
    @Test
    @Order(4)
    @Timeout(30)
    void testEventOverheadUnder5Percent() throws Exception {
        startBubbleControllers();

        for (int bucket = 0; bucket < QUICK_BUCKET_COUNT; bucket++) {
            Thread.sleep(BUCKET_DURATION_MS);
            collectOverhead(bucket);
        }

        stopBubbleControllers();

        var avgOverhead = metrics.getAverageOverheadPercent();
        log.info("Event overhead test: avg={:.2f}% (threshold: {:.1f}%)", avgOverhead, MAX_OVERHEAD_PERCENT);

        assertTrue(avgOverhead < MAX_OVERHEAD_PERCENT,
                "Event overhead < 5%: got " + avgOverhead + "%");
    }

    /**
     * Test 5: Entity retention 100%
     */
    @Test
    @Order(5)
    @Timeout(30)
    void testEntityRetention100Percent() throws Exception {
        startBubbleControllers();

        for (int bucket = 0; bucket < QUICK_BUCKET_COUNT; bucket++) {
            Thread.sleep(BUCKET_DURATION_MS);
            var validation = cluster.getEntityAccountant().validate();
            metrics.recordRetentionCheck(bucket, validation.success());
        }

        stopBubbleControllers();

        var allPassed = metrics.allRetentionChecksPassed();
        var failedCount = metrics.getFailedRetentionCount();
        log.info("Entity retention test: passed={}, failed={}", allPassed, failedCount);

        assertTrue(allPassed, "100% entity retention: " + failedCount + " checks failed");
    }

    /**
     * Test 6: Three consecutive validation cycles (stability)
     */
    @Test
    @Order(6)
    @Timeout(120)
    void testThreeConsecutiveValidationCycles() throws Exception {
        for (int cycle = 1; cycle <= 3; cycle++) {
            log.info("=== Validation Cycle {}/3 ===", cycle);

            // Reset state for new cycle
            metrics = new HybridTimingMetrics();
            bubbleControllers.clear();

            // Run abbreviated validation (333 buckets each = ~1000 total across 3 cycles)
            startBubbleControllers();

            for (int bucket = 0; bucket < 333; bucket++) {
                Thread.sleep(BUCKET_DURATION_MS);
                collectClockDrift(bucket);
                collectOverhead(bucket);

                if (bucket % 50 == 0) {
                    var validation = cluster.getEntityAccountant().validate();
                    metrics.recordRetentionCheck(bucket, validation.success());
                }
            }

            stopBubbleControllers();

            // Assert this cycle passed
            var maxDrift = metrics.getMaxDrift();
            var avgOverhead = metrics.getAverageOverheadPercent();
            var retention = metrics.allRetentionChecksPassed();

            log.info("Cycle {} results: drift={}ms, overhead={:.2f}%, retention={}",
                    cycle, maxDrift, avgOverhead, retention ? "OK" : "FAIL");

            assertTrue(maxDrift < MAX_DRIFT_THRESHOLD_MS,
                    "Cycle " + cycle + ": drift < 50ms, got " + maxDrift + "ms");
            assertTrue(avgOverhead < MAX_OVERHEAD_PERCENT,
                    "Cycle " + cycle + ": overhead < 5%, got " + avgOverhead + "%");
            assertTrue(retention,
                    "Cycle " + cycle + ": 100% retention");

            log.info("Cycle {} PASSED", cycle);
        }

        log.info("All 3 validation cycles PASSED - System is stable");
    }

    /**
     * Test 7: Verify all bubbles are ticking at expected rate
     */
    @Test
    @Order(7)
    @Timeout(15)
    void testBubbleTickRates() throws Exception {
        startBubbleControllers();

        // Run for 1 second (10 buckets at 100Hz = 100 ticks expected)
        Thread.sleep(1000);

        stopBubbleControllers();

        // Each bubble should have ~100 ticks (+/- 10 for timing variance)
        for (var entry : bubbleControllers.entrySet()) {
            var bubbleId = entry.getKey();
            var controller = entry.getValue();
            var simTime = controller.getSimulationTime();

            log.info("Bubble {}: simulationTime={} (expected ~100)", bubbleId, simTime);

            assertTrue(simTime >= 80 && simTime <= 120,
                    "Bubble " + bubbleId + " should have ~100 ticks, got " + simTime);
        }
    }

    // ==================== Helper Methods ====================

    private void startBubbleControllers() {
        var bubbleIds = cluster.getTopology().getAllBubbleIds();
        for (var bubbleId : bubbleIds) {
            var name = "bubble-" + bubbleId.toString().substring(0, 8);
            var controller = new HybridBubbleController(bubbleId, name);
            bubbleControllers.put(bubbleId, controller);
            controller.start();
        }
        log.debug("Started {} bubble controllers", bubbleControllers.size());
    }

    private void stopBubbleControllers() {
        for (var controller : bubbleControllers.values()) {
            controller.stop();
        }
        log.debug("Stopped {} bubble controllers", bubbleControllers.size());
    }

    private void collectClockDrift(int bucket) {
        var simulationTimes = new HashMap<UUID, Long>();
        for (var entry : bubbleControllers.entrySet()) {
            simulationTimes.put(entry.getKey(), entry.getValue().getSimulationTime());
        }
        metrics.recordClockDrift(bucket, simulationTimes);
    }

    private void collectOverhead(int bucket) {
        long totalOverheadNs = 0;
        for (var controller : bubbleControllers.values()) {
            totalOverheadNs += controller.getTickOverheadNs();
            controller.resetBucketMetrics();
        }
        // Budget = bucket duration (100ms) * bubble count * ns per ms
        long budgetNs = BUCKET_DURATION_MS * 1_000_000L * TOTAL_BUBBLES;
        metrics.recordTickOverhead(bucket, totalOverheadNs, budgetNs);
    }
}
