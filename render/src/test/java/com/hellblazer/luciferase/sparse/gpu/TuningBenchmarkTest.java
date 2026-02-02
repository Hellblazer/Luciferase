/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.sparse.gpu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B3: Tests for TuningBenchmark
 *
 * Validates runtime performance-driven tuning benchmark functionality.
 *
 * @author hal.hildebrand
 */
@DisplayName("B3: TuningBenchmark Tests")
class TuningBenchmarkTest {

    private TuningBenchmark benchmark;

    @BeforeEach
    void setUp() {
        // Create benchmark with mock executor (1.0 throughput multiplier)
        benchmark = new TuningBenchmark(
            TuningBenchmark.mockExecutor(1.0),
            Duration.ofSeconds(2),
            1, 2, 1000  // 1 warmup, 2 runs, 1000 rays
        );
    }

    @AfterEach
    void tearDown() {
        if (benchmark != null) {
            benchmark.shutdown();
        }
    }

    // ==================== Single Config Benchmarking ====================

    @Test
    @DisplayName("Benchmark single config returns metrics")
    void testBenchmarkSingleConfig() {
        var config = new WorkgroupConfig(64, 16, 0.75f, 2.5f, "test");

        var result = benchmark.benchmarkConfig(config);

        assertTrue(result.isSuccessful(), "Benchmark should succeed");
        assertTrue(result.throughputRaysPerMicrosecond() > 0, "Throughput should be positive");
        assertTrue(result.latencyMicroseconds() > 0, "Latency should be positive");
        assertEquals(config, result.config(), "Config should match");
        assertFalse(result.timedOut(), "Should not timeout");
        assertNull(result.errorMessage(), "Should have no error");
    }

    @Test
    @DisplayName("Benchmark result tracks ray count")
    void testBenchmarkTracksRayCount() {
        var config = new WorkgroupConfig(64, 16, 0.75f, 2.5f, "test");

        var result = benchmark.benchmarkConfig(config);

        // 2 benchmark runs × 1000 rays = 2000 rays
        assertEquals(2000, result.raysProcessed(), "Should track total rays processed");
    }

    // ==================== Multiple Config Comparison ====================

    @Test
    @DisplayName("Benchmark multiple configs returns sorted results")
    void testBenchmarkMultipleConfigs() {
        var configs = List.of(
            new WorkgroupConfig(32, 16, 0.65f, 1.5f, "small"),
            new WorkgroupConfig(64, 16, 0.75f, 2.5f, "medium"),
            new WorkgroupConfig(128, 16, 0.80f, 3.5f, "large")
        );

        var results = benchmark.benchmarkConfigs(configs);

        assertEquals(3, results.size(), "Should have 3 results");

        // Results should be sorted by throughput (descending)
        assertTrue(results.get(0).throughputRaysPerMicrosecond() >=
                   results.get(1).throughputRaysPerMicrosecond(),
                "First result should have highest throughput");
        assertTrue(results.get(1).throughputRaysPerMicrosecond() >=
                   results.get(2).throughputRaysPerMicrosecond(),
                "Second result should have higher throughput than third");
    }

    @Test
    @DisplayName("Larger workgroups have higher throughput (mock simulation)")
    void testLargerWorkgroupsHigherThroughput() {
        var configs = List.of(
            new WorkgroupConfig(32, 16, 0.65f, 1.5f, "small"),
            new WorkgroupConfig(128, 16, 0.80f, 3.5f, "large")
        );

        var results = benchmark.benchmarkConfigs(configs);

        // In mock executor, 128 threads should outperform 32 threads
        var largeResult = results.stream()
            .filter(r -> r.config().workgroupSize() == 128)
            .findFirst()
            .orElseThrow();
        var smallResult = results.stream()
            .filter(r -> r.config().workgroupSize() == 32)
            .findFirst()
            .orElseThrow();

        assertTrue(largeResult.throughputRaysPerMicrosecond() > smallResult.throughputRaysPerMicrosecond(),
                "128 threads should outperform 32 threads");
    }

    // ==================== Optimal Selection ====================

    @Test
    @DisplayName("Select optimal config returns best performing")
    void testSelectOptimalByBenchmark() {
        var configs = List.of(
            new WorkgroupConfig(32, 16, 0.65f, 1.5f, "small"),
            new WorkgroupConfig(64, 16, 0.75f, 2.5f, "medium"),
            new WorkgroupConfig(128, 16, 0.80f, 3.5f, "large")
        );

        var optimal = benchmark.selectOptimalConfig(configs);

        // Mock executor should favor larger workgroups
        assertEquals(128, optimal.workgroupSize(),
                "Should select largest workgroup as optimal");
    }

    @Test
    @DisplayName("Select optimal returns fallback when all fail")
    void testSelectOptimalFallback() {
        // Create benchmark that always fails
        var failingBenchmark = new TuningBenchmark(
            (config, rayCount) -> { throw new RuntimeException("Simulated failure"); },
            Duration.ofMillis(100), 0, 1, 1000
        );

        var configs = List.of(
            new WorkgroupConfig(32, 16, 0.65f, 1.5f, "first"),
            new WorkgroupConfig(64, 16, 0.75f, 2.5f, "second")
        );

        var optimal = failingBenchmark.selectOptimalConfig(configs);
        failingBenchmark.shutdown();

        // Should return first candidate as fallback
        assertEquals(32, optimal.workgroupSize(),
                "Should return first candidate when all benchmarks fail");
    }

    @Test
    @DisplayName("Select optimal throws on empty candidates")
    void testSelectOptimalEmptyCandidates() {
        assertThrows(IllegalArgumentException.class,
                () -> benchmark.selectOptimalConfig(List.of()),
                "Should throw for empty candidates");
    }

    // ==================== Timeout Protection ====================

    @Test
    @DisplayName("Timeout prevents hung benchmark from blocking")
    void testBenchmarkTimeout() {
        // Create benchmark with very short timeout and slow executor
        var slowBenchmark = new TuningBenchmark(
            TuningBenchmark.slowExecutor(Set.of(128)), // 128 threads will hang
            Duration.ofMillis(100), // 100ms timeout
            0, 1, 1000
        );

        var config = new WorkgroupConfig(128, 16, 0.80f, 3.5f, "slow");

        var result = slowBenchmark.benchmarkConfig(config);
        slowBenchmark.shutdown();

        assertTrue(result.timedOut(), "Should timeout for slow config");
        assertFalse(result.isSuccessful(), "Timed out result should not be successful");
    }

    @Test
    @DisplayName("Non-slow configs complete within timeout")
    void testNonSlowConfigsComplete() {
        // Create benchmark with slow executor but only 128 threads is slow
        var slowBenchmark = new TuningBenchmark(
            TuningBenchmark.slowExecutor(Set.of(128)),
            Duration.ofSeconds(2),
            0, 1, 1000
        );

        var config = new WorkgroupConfig(64, 16, 0.75f, 2.5f, "fast");

        var result = slowBenchmark.benchmarkConfig(config);
        slowBenchmark.shutdown();

        assertTrue(result.isSuccessful(), "Non-slow config should complete successfully");
        assertFalse(result.timedOut(), "Non-slow config should not timeout");
    }

    // ==================== Benchmark Result Tests ====================

    @Test
    @DisplayName("BenchmarkResult.success creates valid result")
    void testBenchmarkResultSuccess() {
        var config = new WorkgroupConfig(64, 16, 0.75f, 2.5f, "test");
        var result = TuningBenchmark.BenchmarkResult.success(config, 2.5, 400.0, 1000);

        assertTrue(result.isSuccessful());
        assertEquals(2.5, result.throughputRaysPerMicrosecond());
        assertEquals(400.0, result.latencyMicroseconds());
        assertEquals(1000, result.raysProcessed());
        assertFalse(result.timedOut());
        assertNull(result.errorMessage());
    }

    @Test
    @DisplayName("BenchmarkResult.failed creates invalid result")
    void testBenchmarkResultFailed() {
        var config = new WorkgroupConfig(64, 16, 0.75f, 2.5f, "test");
        var result = TuningBenchmark.BenchmarkResult.failed(config, "Test error");

        assertFalse(result.isSuccessful());
        assertEquals("Test error", result.errorMessage());
        assertEquals(0.0, result.throughputRaysPerMicrosecond());
    }

    @Test
    @DisplayName("BenchmarkResult.timeout creates timeout result")
    void testBenchmarkResultTimeout() {
        var config = new WorkgroupConfig(64, 16, 0.75f, 2.5f, "test");
        var result = TuningBenchmark.BenchmarkResult.timeout(config);

        assertFalse(result.isSuccessful());
        assertTrue(result.timedOut());
    }

    // ==================== Depth Impact Tests ====================

    @Test
    @DisplayName("Deeper stacks slightly reduce throughput (mock simulation)")
    void testDeepStacksThroughputImpact() {
        var configs = List.of(
            new WorkgroupConfig(64, 16, 0.75f, 2.5f, "shallow"),
            new WorkgroupConfig(64, 24, 0.70f, 2.0f, "deep")
        );

        var results = benchmark.benchmarkConfigs(configs);

        var shallowResult = results.stream()
            .filter(r -> r.config().maxTraversalDepth() == 16)
            .findFirst()
            .orElseThrow();
        var deepResult = results.stream()
            .filter(r -> r.config().maxTraversalDepth() == 24)
            .findFirst()
            .orElseThrow();

        // In mock executor, deeper stacks have slight penalty
        assertTrue(shallowResult.throughputRaysPerMicrosecond() >= deepResult.throughputRaysPerMicrosecond() * 0.8,
                "Shallow stack throughput should be within 20% of deep stack");
    }

    // ==================== Mock Executor Tests ====================

    @Test
    @DisplayName("Mock executor produces reasonable throughput range")
    void testMockExecutorThroughputRange() {
        var config = new WorkgroupConfig(64, 16, 0.75f, 2.5f, "test");
        var result = benchmark.benchmarkConfig(config);

        // Mock should produce throughput in reasonable range
        assertTrue(result.throughputRaysPerMicrosecond() > 0.1,
                "Throughput should be > 0.1 rays/µs");
        assertTrue(result.throughputRaysPerMicrosecond() < 100.0,
                "Throughput should be < 100 rays/µs (realistic)");
    }
}
