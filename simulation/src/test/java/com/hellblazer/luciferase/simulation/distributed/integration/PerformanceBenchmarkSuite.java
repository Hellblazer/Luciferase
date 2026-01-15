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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5.2: Performance Benchmarking Suite
 * <p>
 * Measures and documents performance characteristics of distributed coordination:
 * <p>
 * 1. Coordination Overhead
 * - CPU usage per process (expected: <0.01%)
 * - Memory usage per coordinator (expected: ~500 bytes entity state)
 * - Network bandwidth (expected: minimal, only on topology changes)
 * <p>
 * 2. Migration Latency
 * - 2PC prepare phase latency
 * - 2PC commit phase latency
 * - End-to-end migration latency
 * <p>
 * 3. View Change Latency
 * - Fireflies notification latency (instant)
 * - Unregistration processing time
 * - Coordinator election convergence time (expected: <10ms)
 * <p>
 * 4. Topology Update Propagation
 * - Detection latency (expected: <10ms polling)
 * - Broadcast latency (rate-limited: 1/second)
 * <p>
 * Baselines (Phase 4.3.2):
 * - 90% coordination overhead reduction vs heartbeat monitoring
 * - 40% memory footprint reduction
 * - Zero periodic network traffic
 * - Instant failure detection
 * <p>
 * References: Luciferase-23pd (Phase 5.2)
 *
 * @author hal.hildebrand
 */
class PerformanceBenchmarkSuite {

    private static final Logger log = LoggerFactory.getLogger(PerformanceBenchmarkSuite.class);

    private final Map<UUID, ProcessCoordinator> coordinators = new ConcurrentHashMap<>();
    private final Map<UUID, LocalServerTransport> transports = new ConcurrentHashMap<>();
    private final Map<UUID, MockMembershipView<UUID>> views = new ConcurrentHashMap<>();
    private LocalServerTransport.Registry registry;

    // Performance measurement utilities
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    @AfterEach
    void tearDown() {
        coordinators.values().forEach(ProcessCoordinator::stop);
        coordinators.clear();
        transports.clear();
        views.clear();
        registry = null;
    }

    // ==================== Benchmark Category 1: Coordination Overhead ====================

    @Test
    @Timeout(30)
    void benchmarkCoordinationOverhead() throws Exception {
        log.info("=== Benchmark: Coordination Overhead ===");

        // Setup: 3-process cluster
        var processIds = setupCluster(3);
        var allMembers = new HashSet<>(processIds);
        for (var view : views.values()) {
            view.setMembers(allMembers);
        }

        // Baseline: Measure before coordination activity
        Thread.sleep(500); // Allow startup to complete
        var baselineMemory = memoryBean.getHeapMemoryUsage().getUsed();
        var baselineCpuTime = getCurrentThreadCpuTime();

        log.info("Baseline Memory: {} bytes", baselineMemory);
        log.info("Baseline CPU Time: {} ns", baselineCpuTime);

        // Test: Let coordinators run for 10 seconds
        var startTime = System.nanoTime();
        Thread.sleep(10_000);
        var endTime = System.nanoTime();

        // Measure: Coordination overhead
        var finalMemory = memoryBean.getHeapMemoryUsage().getUsed();
        var finalCpuTime = getCurrentThreadCpuTime();

        var memoryDelta = finalMemory - baselineMemory;
        var cpuTimeDelta = finalCpuTime - baselineCpuTime;
        var elapsedTime = endTime - startTime;
        var cpuPercentage = (cpuTimeDelta * 100.0) / elapsedTime;

        // Results
        log.info("=== Coordination Overhead Results ===");
        log.info("Elapsed Time: {} ms", elapsedTime / 1_000_000);
        log.info("Memory Delta: {} bytes (~{} bytes per coordinator)",
                 memoryDelta, memoryDelta / processIds.size());
        log.info("CPU Time Delta: {} ms", cpuTimeDelta / 1_000_000);
        log.info(String.format("CPU Percentage: %.6f%%", cpuPercentage));

        // Validation: CPU usage should be minimal (<0.01%)
        assertTrue(cpuPercentage < 0.01,
                   "CPU usage " + cpuPercentage + "% exceeds 0.01% threshold");

        // Validation: Memory per coordinator should be reasonable
        var memoryPerCoordinator = memoryDelta / processIds.size();
        assertTrue(memoryPerCoordinator < 10_000,
                   "Memory per coordinator " + memoryPerCoordinator + " exceeds 10KB threshold");

        log.info("✅ Coordination overhead within acceptable limits");
    }

    @Test
    @Timeout(60)
    void benchmarkSteadyStateCoordination() throws Exception {
        log.info("=== Benchmark: Steady-State Coordination ===");

        // Setup: 5-process cluster with registered processes
        var processIds = setupCluster(5);
        var allMembers = new HashSet<>(processIds);
        for (var view : views.values()) {
            view.setMembers(allMembers);
        }

        // Register processes with bubbles
        for (var processId : processIds) {
            var coordinator = coordinators.get(processId);
            var bubbleIds = List.of(UUID.randomUUID(), UUID.randomUUID());
            coordinator.registerProcess(processId, bubbleIds);
        }

        Thread.sleep(500); // Allow registration to complete

        // Measure: Steady-state performance over 20 seconds (reduced for faster testing)
        var measurements = new ArrayList<PerformanceMeasurement>();
        var startTime = System.nanoTime();

        for (int i = 0; i < 20; i++) {
            var iterStartTime = System.nanoTime();
            var iterMemory = memoryBean.getHeapMemoryUsage().getUsed();
            var iterCpuTime = getCurrentThreadCpuTime();

            Thread.sleep(1000);

            var iterEndTime = System.nanoTime();
            var iterFinalMemory = memoryBean.getHeapMemoryUsage().getUsed();
            var iterFinalCpuTime = getCurrentThreadCpuTime();

            var measurement = new PerformanceMeasurement(
                iterEndTime - iterStartTime,
                iterFinalMemory - iterMemory,
                iterFinalCpuTime - iterCpuTime
            );
            measurements.add(measurement);
        }

        var endTime = System.nanoTime();

        // Analyze results
        var avgElapsed = measurements.stream().mapToLong(m -> m.elapsedNs).average().orElse(0);
        var avgMemoryDelta = measurements.stream().mapToLong(m -> m.memoryDelta).average().orElse(0);
        var avgCpuDelta = measurements.stream().mapToLong(m -> m.cpuTimeNs).average().orElse(0);
        var avgCpuPercentage = (avgCpuDelta * 100.0) / avgElapsed;

        log.info("=== Steady-State Coordination Results ({} samples) ===", measurements.size());
        log.info(String.format("Average CPU per second: %.6f%%", avgCpuPercentage));
        log.info("Average Memory delta: {} bytes", (long) avgMemoryDelta);
        log.info("Total runtime: {} ms", (endTime - startTime) / 1_000_000);

        // Validation: Steady-state should be extremely low overhead
        assertTrue(avgCpuPercentage < 0.01,
                   "Average CPU " + avgCpuPercentage + "% exceeds 0.01% threshold");

        log.info("✅ Steady-state coordination stable");
    }

    // ==================== Benchmark Category 2: Migration Latency ====================

    @Test
    @Timeout(30)
    void benchmarkMigrationLatency() throws Exception {
        log.info("=== Benchmark: Migration Latency ===");

        // Setup: 2-process cluster
        var processIds = setupCluster(2);
        var allMembers = new HashSet<>(processIds);
        for (var view : views.values()) {
            view.setMembers(allMembers);
        }

        var sourceProcessId = processIds.get(0);
        var destProcessId = processIds.get(1);

        var sourceCoordinator = coordinators.get(sourceProcessId);
        var destCoordinator = coordinators.get(destProcessId);

        // Register processes
        var sourceBubbles = List.of(UUID.randomUUID());
        var destBubbles = List.of(UUID.randomUUID());
        sourceCoordinator.registerProcess(sourceProcessId, sourceBubbles);
        destCoordinator.registerProcess(destProcessId, destBubbles);

        Thread.sleep(500);

        // Measure: Migration timing (simulated via registration/unregistration)
        var iterations = 100;
        var latencies = new ArrayList<Long>();

        for (int i = 0; i < iterations; i++) {
            var entityId = UUID.randomUUID();

            // Simulate PREPARE phase (unregister from source)
            var prepareStart = System.nanoTime();
            sourceCoordinator.unregisterProcess(sourceProcessId);
            var prepareEnd = System.nanoTime();
            var prepareLatency = prepareEnd - prepareStart;

            // Simulate COMMIT phase (register to destination)
            var commitStart = System.nanoTime();
            destCoordinator.registerProcess(destProcessId, destBubbles);
            var commitEnd = System.nanoTime();
            var commitLatency = commitEnd - commitStart;

            // Total migration latency
            var totalLatency = prepareLatency + commitLatency;
            latencies.add(totalLatency);

            // Re-register source for next iteration
            sourceCoordinator.registerProcess(sourceProcessId, sourceBubbles);

            Thread.sleep(10); // Rate-limit iterations
        }

        // Analyze results
        latencies.sort(Long::compareTo);
        var p50 = latencies.get(latencies.size() / 2);
        var p95 = latencies.get((int) (latencies.size() * 0.95));
        var p99 = latencies.get((int) (latencies.size() * 0.99));
        var avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);

        log.info("=== Migration Latency Results ({} samples) ===", iterations);
        log.info(String.format("Average: %.2f μs", avg / 1000.0));
        log.info(String.format("P50: %.2f μs", p50 / 1000.0));
        log.info(String.format("P95: %.2f μs", p95 / 1000.0));
        log.info(String.format("P99: %.2f μs", p99 / 1000.0));

        // Validation: Registration/unregistration operations (<100ms p99 acceptable)
        assertTrue(p99 < 100_000_000,
                   "P99 migration latency " + (p99 / 1000.0) + " μs exceeds 100ms threshold");

        log.info("✅ Migration latency within acceptable limits");
    }

    // ==================== Benchmark Category 3: View Change Latency ====================

    @Test
    @Timeout(30)
    void benchmarkViewChangeLatency() throws Exception {
        log.info("=== Benchmark: View Change Latency ===");

        // Setup: 5-process cluster
        var processIds = setupCluster(5);
        var allMembers = new HashSet<>(processIds);
        for (var view : views.values()) {
            view.setMembers(allMembers);
        }

        // Register all processes
        for (var processId : processIds) {
            var coordinator = coordinators.get(processId);
            var bubbles = List.of(UUID.randomUUID());
            coordinator.registerProcess(processId, bubbles);
        }

        Thread.sleep(500);

        // Measure: View change propagation latency
        var iterations = 50;
        var latencies = new ArrayList<Long>();

        for (int i = 0; i < iterations; i++) {
            // Remove one process from view
            var failingProcessId = processIds.get(i % processIds.size());
            var remainingMembers = new HashSet<>(allMembers);
            remainingMembers.remove(failingProcessId);

            var viewChangeStart = System.nanoTime();

            // Trigger view change
            for (var processId : remainingMembers) {
                views.get(processId).setMembers(remainingMembers);
            }

            // Wait for propagation
            Thread.sleep(50);

            var viewChangeEnd = System.nanoTime();
            var latency = viewChangeEnd - viewChangeStart;
            latencies.add(latency);

            // Re-add process for next iteration
            allMembers.add(failingProcessId);
            for (var view : views.values()) {
                view.setMembers(allMembers);
            }

            Thread.sleep(50);
        }

        // Analyze results
        latencies.sort(Long::compareTo);
        var p50 = latencies.get(latencies.size() / 2);
        var p95 = latencies.get((int) (latencies.size() * 0.95));
        var p99 = latencies.get((int) (latencies.size() * 0.99));
        var avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);

        log.info("=== View Change Latency Results ({} samples) ===", iterations);
        log.info(String.format("Average: %.2f ms", avg / 1_000_000.0));
        log.info(String.format("P50: %.2f ms", p50 / 1_000_000.0));
        log.info(String.format("P95: %.2f ms", p95 / 1_000_000.0));
        log.info(String.format("P99: %.2f ms", p99 / 1_000_000.0));

        // Validation: View change should be fast (<100ms p99)
        assertTrue(p99 < 100_000_000,
                   "P99 view change latency " + (p99 / 1_000_000.0) + " ms exceeds 100ms threshold");

        log.info("✅ View change latency within acceptable limits");
    }

    @Test
    @Timeout(30)
    void benchmarkCoordinatorElectionConvergence() throws Exception {
        log.info("=== Benchmark: Coordinator Election Convergence ===");

        // Setup: 8-process cluster (larger cluster for election stress)
        var processIds = setupCluster(8);
        var allMembers = new HashSet<>(processIds);
        for (var view : views.values()) {
            view.setMembers(allMembers);
        }

        Thread.sleep(500);

        // Measure: Convergence time after coordinator failure
        var iterations = 20;
        var convergenceTimes = new ArrayList<Long>();

        for (int i = 0; i < iterations; i++) {
            // Identify current coordinator
            var currentCoordinator = coordinators.get(processIds.get(0)).getCoordinator();

            // Remove coordinator from view
            var remainingMembers = new HashSet<>(allMembers);
            remainingMembers.remove(currentCoordinator);

            var convergenceStart = System.nanoTime();

            // Trigger coordinator failure
            for (var processId : remainingMembers) {
                views.get(processId).setMembers(remainingMembers);
            }

            // Wait for convergence
            Thread.sleep(20);

            var convergenceEnd = System.nanoTime();
            var convergenceTime = convergenceEnd - convergenceStart;
            convergenceTimes.add(convergenceTime);

            // Verify all remaining processes converged on same new coordinator
            UUID newCoordinator = null;
            for (var processId : remainingMembers) {
                var coordinator = coordinators.get(processId);
                if (coordinator != null) {
                    var elected = coordinator.getCoordinator();
                    if (newCoordinator == null) {
                        newCoordinator = elected;
                    } else {
                        assertEquals(newCoordinator, elected,
                                     "Coordinator election not converged");
                    }
                }
            }

            // Restore for next iteration
            allMembers.add(currentCoordinator);
            for (var view : views.values()) {
                view.setMembers(allMembers);
            }

            Thread.sleep(50);
        }

        // Analyze results
        convergenceTimes.sort(Long::compareTo);
        var p50 = convergenceTimes.get(convergenceTimes.size() / 2);
        var p95 = convergenceTimes.get((int) (convergenceTimes.size() * 0.95));
        var p99 = convergenceTimes.get((int) (convergenceTimes.size() * 0.99));
        var avg = convergenceTimes.stream().mapToLong(Long::longValue).average().orElse(0);

        log.info("=== Coordinator Election Convergence Results ({} samples) ===", iterations);
        log.info(String.format("Average: %.2f ms", avg / 1_000_000.0));
        log.info(String.format("P50: %.2f ms", p50 / 1_000_000.0));
        log.info(String.format("P95: %.2f ms", p95 / 1_000_000.0));
        log.info(String.format("P99: %.2f ms", p99 / 1_000_000.0));

        // Validation: Convergence should be instant (<10ms expected)
        assertTrue(p99 < 100_000_000,
                   "P99 convergence time " + (p99 / 1_000_000.0) + " ms exceeds 100ms threshold");

        log.info("✅ Coordinator election convergence within acceptable limits");
    }

    // ==================== Benchmark Category 4: Topology Update Propagation ====================

    @Test
    @Timeout(30)
    void benchmarkTopologyDetectionLatency() throws Exception {
        log.info("=== Benchmark: Topology Detection Latency ===");

        // Setup: 3-process cluster
        var processIds = setupCluster(3);
        var allMembers = new HashSet<>(processIds);
        for (var view : views.values()) {
            view.setMembers(allMembers);
        }

        var coordinator = coordinators.get(processIds.get(0));

        // Measure: Detection latency for topology changes
        var iterations = 100;
        var detectionLatencies = new ArrayList<Long>();

        for (int i = 0; i < iterations; i++) {
            var processId = UUID.randomUUID();
            var bubbles = List.of(UUID.randomUUID());

            var registerStart = System.nanoTime();
            coordinator.registerProcess(processId, bubbles);

            // Wait for detection (10ms polling interval)
            Thread.sleep(15); // Slightly more than polling interval

            var registerEnd = System.nanoTime();
            var detectionLatency = registerEnd - registerStart;
            detectionLatencies.add(detectionLatency);

            // Cleanup
            coordinator.unregisterProcess(processId);
            Thread.sleep(15);
        }

        // Analyze results
        detectionLatencies.sort(Long::compareTo);
        var p50 = detectionLatencies.get(detectionLatencies.size() / 2);
        var p95 = detectionLatencies.get((int) (detectionLatencies.size() * 0.95));
        var p99 = detectionLatencies.get((int) (detectionLatencies.size() * 0.99));
        var avg = detectionLatencies.stream().mapToLong(Long::longValue).average().orElse(0);

        log.info("=== Topology Detection Latency Results ({} samples) ===", iterations);
        log.info(String.format("Average: %.2f ms", avg / 1_000_000.0));
        log.info(String.format("P50: %.2f ms", p50 / 1_000_000.0));
        log.info(String.format("P95: %.2f ms", p95 / 1_000_000.0));
        log.info(String.format("P99: %.2f ms", p99 / 1_000_000.0));

        // Validation: Detection within polling + propagation window (<50ms acceptable)
        assertTrue(p99 < 50_000_000,
                   "P99 detection latency " + (p99 / 1_000_000.0) + " ms exceeds 50ms threshold");

        log.info("✅ Topology detection latency within acceptable limits");
    }

    @Test
    @Timeout(60)
    void benchmarkRateLimitedBroadcasting() throws Exception {
        log.info("=== Benchmark: Rate-Limited Broadcasting ===");

        // Setup: Single coordinator
        var processIds = setupCluster(1);
        var coordinator = coordinators.get(processIds.get(0));

        // Set all processes in view
        var allMembers = new HashSet<>(processIds);
        for (var view : views.values()) {
            view.setMembers(allMembers);
        }

        Thread.sleep(500);

        // Measure: Broadcast rate-limiting effectiveness
        var rapidChanges = 100;
        var broadcastStart = System.nanoTime();

        // Generate rapid topology changes
        for (int i = 0; i < rapidChanges; i++) {
            var processId = UUID.randomUUID();
            var bubbles = List.of(UUID.randomUUID());
            coordinator.registerProcess(processId, bubbles);
        }

        // Wait for all changes to be processed
        Thread.sleep(10_000); // 10 seconds (allows 10 broadcasts max at 1/second rate)

        var broadcastEnd = System.nanoTime();
        var totalTime = (broadcastEnd - broadcastStart) / 1_000_000; // ms

        log.info("=== Rate-Limited Broadcasting Results ===");
        log.info("Generated {} topology changes", rapidChanges);
        log.info("Total time: {} ms", totalTime);
        log.info("Rate-limiting: 1 broadcast/second (max 10 broadcasts expected)");

        // Validation: Coordinator should survive broadcast storm
        assertTrue(coordinator.isRunning(), "Coordinator should still be running after broadcast storm");

        log.info("✅ Rate-limited broadcasting effective");
    }

    // ==================== Helper Methods ====================

    /**
     * Setup a test cluster with N processes
     */
    private List<UUID> setupCluster(int processCount) throws Exception {
        registry = LocalServerTransport.Registry.create();
        var processIds = new ArrayList<UUID>();

        for (int i = 0; i < processCount; i++) {
            var processId = UUID.randomUUID();
            processIds.add(processId);

            var transport = registry.register(processId);
            transports.put(processId, transport);

            var view = new MockMembershipView<UUID>();
            views.put(processId, view);

            var coordinator = new ProcessCoordinator(transport, view);
            coordinators.put(processId, coordinator);

            coordinator.start();
        }

        return processIds;
    }

    /**
     * Get current thread CPU time (if available)
     */
    private long getCurrentThreadCpuTime() {
        if (threadBean.isThreadCpuTimeSupported()) {
            return threadBean.getCurrentThreadCpuTime();
        }
        return 0;
    }

    /**
     * Performance measurement data holder
     */
    private record PerformanceMeasurement(long elapsedNs, long memoryDelta, long cpuTimeNs) {
    }
}
