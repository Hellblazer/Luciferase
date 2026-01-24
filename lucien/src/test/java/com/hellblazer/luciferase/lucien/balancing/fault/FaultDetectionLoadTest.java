/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Load tests for fault detection performance (P2.5).
 *
 * <p>Non-JMH load tests measuring:
 * <ul>
 *   <li>Detection latency under realistic loads</li>
 *   <li>Memory overhead with many partitions</li>
 *   <li>Concurrent partition monitoring throughput</li>
 *   <li>Recovery scaling with partition count</li>
 * </ul>
 */
@Tag("performance")
class FaultDetectionLoadTest {

    /**
     * LT1: Test detection latency under multi-partition load.
     *
     * <p>Measure average time from failure injection to detection
     * with 50 partitions being monitored.
     */
    @Test
    void testDetectionLatency_MultiplePartitions() throws Exception {
        var clock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault());
        var handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        handler.start();

        var config = FailureDetectionConfig.defaultConfig()
            .withHeartbeatInterval(Duration.ofMillis(100))
            .withSuspectTimeout(Duration.ofMillis(300))
            .withFailureTimeout(Duration.ofMillis(500))
            .withCheckIntervalMs(50);

        var detector = new DefaultFailureDetector(config, handler);
        detector.setClock(clock);
        detector.start();

        var partitionCount = 50;
        var partitions = new UUID[partitionCount];
        for (int i = 0; i < partitionCount; i++) {
            partitions[i] = UUID.randomUUID();
            detector.registerPartition(partitions[i]);
            detector.recordHeartbeat(partitions[i]);
        }

        // Measure detection latency
        var latencies = new long[10];
        for (int iteration = 0; iteration < 10; iteration++) {
            var startNanos = System.nanoTime();

            // Advance clock past failure threshold
            var advancedClock = Clock.offset(clock, Duration.ofMillis(600));
            detector.setClock(advancedClock);

            // Trigger health check
            detector.getClass().getDeclaredMethod("checkHealth").invoke(detector);

            var endNanos = System.nanoTime();
            latencies[iteration] = (endNanos - startNanos) / 1000; // Convert to microseconds
        }

        // Calculate average latency
        var avgLatency = Arrays.stream(latencies).average().orElse(0);
        System.out.printf("Average detection latency: %.2f µs%n", avgLatency);

        // Should be reasonably fast
        assertTrue(avgLatency < 10000, "Detection latency should be < 10ms");

        detector.stop();
        handler.stop();
    }

    /**
     * LT2: Test memory overhead with many partitions and transitions.
     *
     * <p>Create 50 partitions with 100 status transitions each.
     * Verify memory usage is bounded.
     */
    @Test
    void testMemoryOverhead_BoundedGrowth() throws Exception {
        var clock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault());
        var tracker = new DefaultPartitionStatusTracker(FaultConfiguration.defaultConfig());
        tracker.setClock(clock);
        tracker.start();

        var partitionCount = 50;
        var transitionsPerPartition = 100;

        // Record many transitions
        for (int p = 0; p < partitionCount; p++) {
            var partitionId = UUID.randomUUID();

            for (int t = 0; t < transitionsPerPartition; t++) {
                var nodeId = UUID.randomUUID();

                // Alternate between healthy and suspected
                if (t % 2 == 0) {
                    tracker.markHealthy(partitionId);
                } else {
                    tracker.reportHeartbeatFailure(partitionId, nodeId);
                }
            }
        }

        // Get memory usage
        var runtime = Runtime.getRuntime();
        var usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);

        System.out.printf("Memory used after %d partitions with %d transitions each: %d MB%n",
                         partitionCount, transitionsPerPartition, usedMemory);

        // Memory should be reasonable (bounded)
        assertTrue(usedMemory < 200, "Memory usage should be < 200 MB");

        tracker.stop();
    }

    /**
     * LT3: Test concurrent partition monitoring throughput.
     *
     * <p>Multiple threads recording heartbeats concurrently.
     * Measure operations per second.
     */
    @Test
    void testConcurrentHeartbeatThroughput() throws Exception {
        var handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        handler.start();

        var config = FailureDetectionConfig.defaultConfig();
        var detector = new DefaultFailureDetector(config, handler);
        detector.start();

        var partitionCount = 20;
        var threadCount = 4;
        var operationsPerThread = 5000;
        var latch = new CountDownLatch(threadCount);
        var operationCount = new AtomicLong(0);

        var partitions = new UUID[partitionCount];
        for (int i = 0; i < partitionCount; i++) {
            partitions[i] = UUID.randomUUID();
            detector.registerPartition(partitions[i]);
        }

        var startTime = System.nanoTime();

        // Spawn threads recording heartbeats
        for (int t = 0; t < threadCount; t++) {
            final var threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        var partitionId = partitions[(threadId + i) % partitionCount];
                        detector.recordHeartbeat(partitionId);
                        operationCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Wait for completion
        var completed = latch.await(10, TimeUnit.SECONDS);
        var endTime = System.nanoTime();

        assertTrue(completed, "All threads should complete");

        var durationSeconds = (endTime - startTime) / 1_000_000_000.0;
        var throughput = operationCount.get() / durationSeconds;

        System.out.printf("Concurrent heartbeat throughput: %.0f ops/sec%n", throughput);

        // Should sustain good throughput
        assertTrue(throughput > 1000, "Throughput should be > 1000 ops/sec");

        detector.stop();
        handler.stop();
    }

    /**
     * LT4: Test detection latency scaling with partition count.
     *
     * <p>Measure how detection latency changes as partition count increases.
     * Should scale linearly or better.
     */
    @Test
    void testLatencyScaling_WithPartitionCount() throws Exception {
        var testSizes = new int[]{10, 50, 100, 200};
        var latencies = new double[testSizes.length];

        for (int sizeIndex = 0; sizeIndex < testSizes.length; sizeIndex++) {
            var clock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault());
            var handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
            handler.start();

            var config = FailureDetectionConfig.defaultConfig()
                .withHeartbeatInterval(Duration.ofMillis(100))
                .withSuspectTimeout(Duration.ofMillis(300))
                .withFailureTimeout(Duration.ofMillis(500))
                .withCheckIntervalMs(50);

            var detector = new DefaultFailureDetector(config, handler);
            detector.setClock(clock);
            detector.start();

            var partitionCount = testSizes[sizeIndex];
            var partitions = new UUID[partitionCount];
            for (int i = 0; i < partitionCount; i++) {
                partitions[i] = UUID.randomUUID();
                detector.registerPartition(partitions[i]);
                detector.recordHeartbeat(partitions[i]);
            }

            // Measure detection latency
            var startNanos = System.nanoTime();

            var advancedClock = Clock.offset(clock, Duration.ofMillis(600));
            detector.setClock(advancedClock);
            detector.getClass().getDeclaredMethod("checkHealth").invoke(detector);

            var endNanos = System.nanoTime();
            latencies[sizeIndex] = (endNanos - startNanos) / 1000.0; // Microseconds

            detector.stop();
            handler.stop();
        }

        // Print scaling results
        System.out.println("Detection latency scaling:");
        for (int i = 0; i < testSizes.length; i++) {
            System.out.printf("  %d partitions: %.2f µs%n", testSizes[i], latencies[i]);
        }

        // Latency should not degrade excessively with partition count
        // Health check is O(n) in partition count, so linear scaling is acceptable
        // Allow up to 25x increase from 10 to 200 partitions (20x partition increase)
        // + some overhead tolerance
        var baselineLatency = latencies[0];
        var maxLatency = latencies[testSizes.length - 1];
        var scalingFactor = maxLatency / baselineLatency;

        System.out.printf("Scaling factor (200 partitions / 10 partitions): %.2fx%n", scalingFactor);
        assertTrue(scalingFactor < 25.0, "Latency should not scale worse than 25x (allows for O(n) overhead)");
    }

    /**
     * LT5: Test recovery initiation time.
     *
     * <p>Measure time from FAILED status to recovery initiation.
     */
    @Test
    void testRecoveryInitiationTime() throws Exception {
        var clock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault());
        var handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        handler.start();

        var partitionId = UUID.randomUUID();
        handler.markHealthy(partitionId);

        // Cause failure
        var nodeId = UUID.randomUUID();
        handler.reportHeartbeatFailure(partitionId, nodeId);
        handler.reportHeartbeatFailure(partitionId, nodeId);

        var startTime = System.nanoTime();

        // Initiate recovery
        var recoveryFuture = handler.initiateRecovery(partitionId);

        var endTime = System.nanoTime();
        var recoveryLatency = (endTime - startTime) / 1000.0; // Microseconds

        System.out.printf("Recovery initiation latency: %.2f µs%n", recoveryLatency);

        // Should be fast
        assertTrue(recoveryLatency < 5000, "Recovery initiation should be < 5ms");

        handler.stop();
    }

    /**
     * LT6: Test status transition throughput.
     *
     * <p>Measure how many status transitions the handler can process per second.
     */
    @Test
    void testStatusTransitionThroughput() throws Exception {
        var handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        handler.start();

        var partitionCount = 10;
        var transitionsPerPartition = 10000;

        var startTime = System.nanoTime();

        for (int p = 0; p < partitionCount; p++) {
            var partitionId = UUID.randomUUID();
            var nodeId = UUID.randomUUID();

            for (int t = 0; t < transitionsPerPartition; t++) {
                if (t % 2 == 0) {
                    handler.markHealthy(partitionId);
                } else {
                    handler.reportHeartbeatFailure(partitionId, nodeId);
                }
            }
        }

        var endTime = System.nanoTime();
        var durationSeconds = (endTime - startTime) / 1_000_000_000.0;
        var throughput = (partitionCount * transitionsPerPartition) / durationSeconds;

        System.out.printf("Status transition throughput: %.0f transitions/sec%n", throughput);

        // Should support high throughput
        assertTrue(throughput > 100000, "Should support > 100K transitions/sec");

        handler.stop();
    }
}
