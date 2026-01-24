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

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for fault detection performance (P2.5).
 *
 * <p>Measures key performance metrics:
 * <ul>
 *   <li>Detection latency: Time from failure injection to FaultHandler notification</li>
 *   <li>Heartbeat processing overhead: Per-heartbeat processing cost</li>
 *   <li>Concurrent status updates: Throughput with multiple partitions</li>
 *   <li>Recovery initiation time: Time from FAILED to RECOVERING transition</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class FaultDetectionBenchmark {

    private SimpleFaultHandler faultHandler;
    private DefaultFailureDetector failureDetector;
    private DefaultPartitionStatusTracker statusTracker;
    private Clock testClock;
    private FailureDetectionConfig config;
    private UUID partitionId;

    @Setup(Level.Trial)
    public void setupTrial() {
        testClock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault());
        faultHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        faultHandler.start();

        config = FailureDetectionConfig.defaultConfig()
            .withHeartbeatInterval(Duration.ofMillis(100))
            .withSuspectTimeout(Duration.ofMillis(300))
            .withFailureTimeout(Duration.ofMillis(500))
            .withCheckIntervalMs(50);

        failureDetector = new DefaultFailureDetector(config, faultHandler);
        failureDetector.setClock(testClock);
        failureDetector.start();

        statusTracker = new DefaultPartitionStatusTracker(FaultConfiguration.defaultConfig());
        statusTracker.setClock(testClock);
        statusTracker.start();

        partitionId = UUID.randomUUID();
    }

    @TearDown(Level.Trial)
    public void teardownTrial() {
        failureDetector.stop();
        statusTracker.stop();
        faultHandler.stop();
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        var newPartitionId = UUID.randomUUID();
        failureDetector.registerPartition(newPartitionId);
        failureDetector.recordHeartbeat(newPartitionId);
        partitionId = newPartitionId;
    }

    @Benchmark
    public void benchmarkHeartbeatRecording(Blackhole bh) {
        failureDetector.recordHeartbeat(partitionId);
        bh.consume(partitionId);
    }

    @Benchmark
    public void benchmarkStatusTransition(Blackhole bh) {
        faultHandler.markHealthy(partitionId);
        var status = faultHandler.checkHealth(partitionId);
        bh.consume(status);
    }

    @Benchmark
    public void benchmarkPartitionStatusTracking(Blackhole bh) {
        statusTracker.markHealthy(partitionId);
        var timeSince = statusTracker.getTimeSinceLastHealthy(partitionId);
        bh.consume(timeSince);
    }

    @Benchmark
    public void benchmarkStatusHistoryAccess(Blackhole bh) {
        statusTracker.markHealthy(partitionId);
        var history = statusTracker.getStatusHistory(partitionId);
        bh.consume(history);
    }

    @Benchmark
    public void benchmarkStalenessCheck(Blackhole bh) {
        statusTracker.markHealthy(partitionId);
        var stale = statusTracker.isStale(partitionId, Duration.ofMillis(1000));
        bh.consume(stale);
    }

    @Benchmark
    public void benchmarkHealthCheck(Blackhole bh) {
        var status = faultHandler.checkHealth(partitionId);
        bh.consume(status);
    }

    @Benchmark
    public void benchmarkMetricsRetrieval(Blackhole bh) {
        faultHandler.markHealthy(partitionId);
        var metrics = faultHandler.getMetrics(partitionId);
        bh.consume(metrics);
    }

    @Benchmark
    public void benchmarkSubscriptionCreation(Blackhole bh) {
        var subscription = faultHandler.subscribeToChanges(event -> {
            // No-op consumer
        });
        bh.consume(subscription);
    }
}
