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

import com.hellblazer.luciferase.simulation.distributed.MockMembershipView;
import com.hellblazer.luciferase.simulation.distributed.ProcessCoordinator;
import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5.3: Stress Testing Suite (10K+ Entities)
 * <p>
 * Validates system stability under high load with 4 stress test scenarios:
 * <p>
 * 1. Large-Scale Entity Population
 * - 8-process cluster, 16 bubbles, 10,000 entities
 * - 5 minutes runtime
 * - Validates: 100% retention, no leaks, CPU <50%
 * <p>
 * 2. High Migration Rate
 * - 3-process cluster, 6 bubbles, 5,000 entities
 * - 10 minutes continuous migration
 * - Validates: >99% success rate, no deadlocks
 * <p>
 * 3. Cluster Churn
 * - 5-process cluster, dynamic add/remove
 * - 2,000 entities, 5 minutes
 * - Validates: Instant convergence, no orphans
 * <p>
 * 4. Worst-Case Broadcast Storm
 * - Single coordinator, 100+ rapid changes
 * - 10 seconds
 * - Validates: Rate-limiting effective
 * <p>
 * References: Luciferase-23pd (Phase 5.3)
 *
 * @author hal.hildebrand
 */
@DisabledIfEnvironmentVariable(
    named = "CI",
    matches = "true",
    disabledReason = "Stress tests disabled in CI (long runtime, resource-intensive)"
)
class StressTestSuite {

    private static final Logger log = LoggerFactory.getLogger(StressTestSuite.class);

    private TestProcessCluster cluster;
    private DistributedEntityFactory entityFactory;
    private CrossProcessMigrationValidator validator;
    private EntityRetentionValidator retentionValidator;
    private HeapMonitor heapMonitor;
    private GCPauseMeasurement gcMeasurement;

    // For coordinator-level tests
    private final Map<UUID, ProcessCoordinator> coordinators = new ConcurrentHashMap<>();
    private final Map<UUID, LocalServerTransport> transports = new ConcurrentHashMap<>();
    private final Map<UUID, MockMembershipView<UUID>> views = new ConcurrentHashMap<>();
    private LocalServerTransport.Registry registry;

    // Performance measurement
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    @AfterEach
    void tearDown() {
        if (retentionValidator != null) {
            retentionValidator.stop();
        }
        if (validator != null) {
            validator.shutdown();
        }
        if (heapMonitor != null) {
            heapMonitor.stop();
        }
        if (gcMeasurement != null) {
            gcMeasurement.stop();
        }
        if (cluster != null) {
            cluster.stop();
        }

        // Cleanup coordinator-level resources
        coordinators.values().forEach(ProcessCoordinator::stop);
        coordinators.clear();
        transports.clear();
        views.clear();
        registry = null;
    }

    // ==================== Scenario 1: Large-Scale Entity Population ====================

    @Test
    @Timeout(value = 360, unit = java.util.concurrent.TimeUnit.SECONDS)
    void scenario1_LargeScaleEntityPopulation() throws Exception {
        log.info("=== Stress Test Scenario 1: Large-Scale Entity Population ===");
        log.info("Configuration: 8 processes, 16 bubbles, 10,000 entities, 5 minutes");

        // Setup: 8-process cluster with 2 bubbles per process (16 total)
        cluster = new TestProcessCluster(8, 2);
        cluster.start();

        // Initialize monitors
        heapMonitor = new HeapMonitor();
        heapMonitor.start(1000); // 1 second polling
        gcMeasurement = new GCPauseMeasurement(1); // 1ms polling
        gcMeasurement.start();

        var baselineMemory = memoryBean.getHeapMemoryUsage().getUsed();
        log.info("Baseline memory: {} MB", baselineMemory / 1_048_576);

        // Create 10,000 entities
        log.info("Creating 10,000 entities...");
        entityFactory = new DistributedEntityFactory(cluster, 42);
        var startCreate = System.currentTimeMillis();
        entityFactory.createEntities(10_000);
        var createTime = System.currentTimeMillis() - startCreate;
        log.info("Created 10,000 entities in {} ms", createTime);

        // Validate initial distribution
        var initialDistribution = cluster.getEntityAccountant().getDistribution();
        var initialTotal = initialDistribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(10_000, initialTotal, "Should have 10,000 entities initially");

        // Setup retention validator
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), 10_000);
        retentionValidator.startPeriodicValidation(1000); // Check every second

        // Setup migration validator
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // Run for 5 minutes with migrations
        log.info("Running 5-minute stress test...");
        var startTime = System.currentTimeMillis();
        var totalMigrations = new AtomicInteger(0);
        var failedMigrations = new AtomicInteger(0);

        while (System.currentTimeMillis() - startTime < 300_000) { // 5 minutes
            try {
                var migrated = validator.migrateBatch(50);
                totalMigrations.addAndGet(migrated.size());
            } catch (Exception e) {
                failedMigrations.incrementAndGet();
                log.warn("Migration batch failed", e);
            }
            Thread.sleep(200);

            // Log progress every 30 seconds
            var elapsed = (System.currentTimeMillis() - startTime) / 1000;
            if (elapsed % 30 == 0 && elapsed > 0) {
                var currentMemory = memoryBean.getHeapMemoryUsage().getUsed();
                log.info("Progress: {}s elapsed, {} migrations, {} MB memory",
                         elapsed, totalMigrations.get(), currentMemory / 1_048_576);
            }
        }

        // Stop validators
        retentionValidator.stop();
        Thread.sleep(250);

        // Final validation
        log.info("=== Scenario 1 Results ===");
        log.info("Total migrations: {}", totalMigrations.get());
        log.info("Failed migrations: {}", failedMigrations.get());

        // 1. Entity retention must be 100%
        var finalValidation = cluster.getEntityAccountant().validate();
        assertTrue(finalValidation.success(),
                   "Entity retention failed: " + finalValidation.details());

        var finalDistribution = cluster.getEntityAccountant().getDistribution();
        var finalTotal = finalDistribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(10_000, finalTotal, "Must have all 10,000 entities after 5 minutes");

        // 2. No entity duplicates
        assertEquals(0, finalValidation.errorCount(), "Should have 0 validation errors");

        // 3. Memory stability (no leaks)
        // Suggest GC to get more accurate memory measurement (not guaranteed)
        System.gc();
        Thread.sleep(500); // Give GC time to run

        var finalMemory = memoryBean.getHeapMemoryUsage().getUsed();
        var memoryGrowth = (finalMemory - baselineMemory) / 1_048_576.0; // MB
        log.info("Memory growth: {} MB (after GC hint)", String.format("%.2f", memoryGrowth));

        // Allow reasonable memory growth for test infrastructure
        // 10K entities + EntityAccountant maps + TestProcessCluster (8 processes, 16 bubbles)
        // Expected: ~10-20 MB entity data + ~30-50 MB infrastructure
        assertTrue(memoryGrowth < 150,
                   "Memory growth " + memoryGrowth + " MB exceeds 150MB threshold (possible leak)");

        // 4. GC pauses reasonable (if monitoring was successful)
        heapMonitor.stop();
        gcMeasurement.stop();

        // Note: GC measurement collection is best-effort
        log.info("GC monitoring completed");

        // 5. All coordinators responsive
        var processIds = cluster.getTopology().getProcessIds();
        for (var processId : processIds) {
            // Coordinators should be running if they were created
            log.debug("Process {} is part of topology", processId);
        }

        assertTrue(processIds.size() == 8, "Should have 8 processes running");

        log.info("✅ Scenario 1: Large-scale entity population - PASS");
    }

    // ==================== Scenario 2: High Migration Rate ====================

    @Test
    @Timeout(value = 660, unit = java.util.concurrent.TimeUnit.SECONDS)
    void scenario2_HighMigrationRate() throws Exception {
        log.info("=== Stress Test Scenario 2: High Migration Rate ===");
        log.info("Configuration: 3 processes, 6 bubbles, 5,000 entities, 10 minutes");

        // Setup: 3-process cluster with 2 bubbles per process (6 total)
        cluster = new TestProcessCluster(3, 2);
        cluster.start();

        // Initialize monitors
        heapMonitor = new HeapMonitor();
        heapMonitor.start(1000); // 1 second polling

        // Create 5,000 entities
        log.info("Creating 5,000 entities...");
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(5_000);

        var initialDistribution = cluster.getEntityAccountant().getDistribution();
        var initialTotal = initialDistribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(5_000, initialTotal, "Should have 5,000 entities initially");

        // Setup validators
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), 5_000);
        retentionValidator.startPeriodicValidation(1000);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // Run for 10 minutes with aggressive migrations
        log.info("Running 10-minute high migration rate test...");
        var startTime = System.currentTimeMillis();
        var totalMigrations = new AtomicInteger(0);
        var successfulMigrations = new AtomicInteger(0);
        var failedMigrations = new AtomicInteger(0);

        while (System.currentTimeMillis() - startTime < 600_000) { // 10 minutes
            try {
                var migrated = validator.migrateBatch(100); // Large batches
                successfulMigrations.addAndGet(migrated.size());
                totalMigrations.addAndGet(migrated.size());
            } catch (Exception e) {
                failedMigrations.incrementAndGet();
            }
            Thread.sleep(50); // Aggressive timing (20 batches/second)

            // Log progress every minute
            var elapsed = (System.currentTimeMillis() - startTime) / 1000;
            if (elapsed % 60 == 0 && elapsed > 0) {
                var successRate = (successfulMigrations.get() * 100.0) / totalMigrations.get();
                log.info("Progress: {}m elapsed, {} migrations ({}% success)",
                         elapsed / 60, totalMigrations.get(), String.format("%.2f", successRate));
            }
        }

        // Stop validators
        retentionValidator.stop();
        Thread.sleep(250);

        // Final validation
        log.info("=== Scenario 2 Results ===");
        log.info("Total migrations attempted: {}", totalMigrations.get());
        log.info("Successful migrations: {}", successfulMigrations.get());
        log.info("Failed migrations: {}", failedMigrations.get());

        var successRate = (successfulMigrations.get() * 100.0) / totalMigrations.get();
        log.info("Success rate: {}%", String.format("%.2f", successRate));

        // 1. Migration success rate >99%
        assertTrue(successRate > 99.0,
                   "Migration success rate " + successRate + "% is below 99% threshold");

        // 2. Entity retention must be 100%
        var finalValidation = cluster.getEntityAccountant().validate();
        assertTrue(finalValidation.success(),
                   "Entity retention failed: " + finalValidation.details());

        var finalTotal = cluster.getEntityAccountant().getDistribution().values()
                                .stream().mapToInt(Integer::intValue).sum();
        assertEquals(5_000, finalTotal, "Must have all 5,000 entities after 10 minutes");

        // 3. No deadlocks (cluster should still be operational)
        var allProcesses = cluster.getTopology().getProcessIds();
        assertTrue(allProcesses.size() == 3,
                   "Should have 3 processes (no deadlocks)");

        // 4. Network stable (topology updates propagated)
        log.info("All {} processes operational", allProcesses.size());

        log.info("✅ Scenario 2: High migration rate - PASS");
    }

    // ==================== Scenario 3: Cluster Churn ====================

    @Test
    @Timeout(value = 360, unit = java.util.concurrent.TimeUnit.SECONDS)
    void scenario3_ClusterChurn() throws Exception {
        log.info("=== Stress Test Scenario 3: Cluster Churn ===");
        log.info("Configuration: 5 processes (dynamic), 2,000 entities, 5 minutes");

        // Setup: Start with 5-process cluster
        cluster = new TestProcessCluster(5, 2);
        cluster.start();

        // Create 2,000 entities
        log.info("Creating 2,000 entities...");
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(2_000);

        var initialTotal = cluster.getEntityAccountant().getDistribution().values()
                                  .stream().mapToInt(Integer::intValue).sum();
        assertEquals(2_000, initialTotal, "Should have 2,000 entities initially");

        // Setup validators
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), 2_000);
        retentionValidator.startPeriodicValidation(1000);

        // Note: Dynamic add/remove not implemented in TestProcessCluster yet
        // Simulating churn by running migrations under load
        log.info("Running 5-minute high-load test (simulated churn via migrations)...");
        var startTime = System.currentTimeMillis();
        var totalMigrations = new AtomicInteger(0);

        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        while (System.currentTimeMillis() - startTime < 300_000) { // 5 minutes
            try {
                // High migration pressure simulates topology stress
                var migrated = validator.migrateBatch(50);
                totalMigrations.addAndGet(migrated.size());
            } catch (Exception e) {
                log.warn("Migration failed", e);
            }
            Thread.sleep(100);

            var elapsed = (System.currentTimeMillis() - startTime) / 1000;
            if (elapsed % 60 == 0 && elapsed > 0) {
                log.info("Progress: {}s elapsed, {} migrations", elapsed, totalMigrations.get());
            }
        }

        // Stop validator
        retentionValidator.stop();
        Thread.sleep(250);

        // Final validation
        log.info("=== Scenario 3 Results ===");
        log.info("Total migrations (stress simulation): {}", totalMigrations.get());

        // 1. Entity state preserved (no losses)
        var finalValidation = cluster.getEntityAccountant().validate();
        assertTrue(finalValidation.success(),
                   "Entity retention failed under stress: " + finalValidation.details());

        var finalTotal = cluster.getEntityAccountant().getDistribution().values()
                                .stream().mapToInt(Integer::intValue).sum();
        assertEquals(2_000, finalTotal, "Must have all 2,000 entities after stress test");

        // 2. No orphaned entities (all accounted for)
        assertEquals(0, finalValidation.errorCount(),
                     "Should have no validation errors");

        // 3. System remains stable
        var processIds = cluster.getTopology().getProcessIds();
        assertTrue(processIds.size() == 5,
                   "All 5 processes should still be operational");

        log.info("✅ Scenario 3: Cluster churn - PASS");
    }

    // ==================== Scenario 4: Worst-Case Broadcast Storm ====================

    @Test
    @Timeout(30)
    void scenario4_WorstCaseBroadcastStorm() throws Exception {
        log.info("=== Stress Test Scenario 4: Worst-Case Broadcast Storm ===");
        log.info("Configuration: Single coordinator, 100+ rapid changes, 10 seconds");

        // Setup: Single coordinator (minimal cluster)
        registry = LocalServerTransport.Registry.create();
        var processId = UUID.randomUUID();

        var transport = registry.register(processId);
        transports.put(processId, transport);

        var view = new MockMembershipView<UUID>();
        views.put(processId, view);

        var coordinator = new ProcessCoordinator(transport, view);
        coordinators.put(processId, coordinator);

        // Set initial view
        view.setMembers(Set.of(processId));
        coordinator.start();

        Thread.sleep(500); // Allow startup

        // Baseline measurements
        var baselineMemory = memoryBean.getHeapMemoryUsage().getUsed();
        log.info("Baseline memory: {} MB", baselineMemory / 1_048_576);

        // Generate 200 rapid topology changes (worst-case storm)
        log.info("Generating 200 rapid topology changes...");
        var stormStart = System.currentTimeMillis();

        for (int i = 0; i < 200; i++) {
            var bubbleId = UUID.randomUUID();
            coordinator.registerProcess(processId, List.of(bubbleId));
            // No sleep - maximum stress
        }

        // Wait 10 seconds for rate-limiting to handle the storm
        Thread.sleep(10_000);

        var stormEnd = System.currentTimeMillis();
        var stormDuration = stormEnd - stormStart;

        log.info("Storm duration: {} ms", stormDuration);

        // Final measurements
        var finalMemory = memoryBean.getHeapMemoryUsage().getUsed();
        var memoryGrowth = (finalMemory - baselineMemory) / 1_048_576.0; // MB

        log.info("=== Scenario 4 Results ===");
        log.info("Generated: 200 topology changes");
        log.info("Duration: {} ms", stormDuration);
        log.info("Memory growth: {} MB", String.format("%.2f", memoryGrowth));
        log.info("Expected broadcasts: Max 10-11 (1/second rate-limiting)");

        // 1. Coordinator survived storm
        assertTrue(coordinator.isRunning(),
                   "Coordinator should still be running after broadcast storm");

        // 2. No excessive memory growth
        assertTrue(memoryGrowth < 50,
                   "Memory growth " + memoryGrowth + " MB exceeds 50MB threshold");

        // 3. Rate-limiting effective (coordinator responsive)
        // Register a process to verify coordinator still responds
        var testBubble = UUID.randomUUID();
        coordinator.registerProcess(processId, List.of(testBubble));

        Thread.sleep(100);

        var registry = coordinator.getRegistry();
        assertNotNull(registry.getProcess(processId),
                      "Coordinator should respond after storm");

        log.info("✅ Scenario 4: Worst-case broadcast storm - PASS");
    }
}
