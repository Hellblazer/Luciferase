/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvt.validation;

import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTPerformanceBenchmark.
 *
 * @author hal.hildebrand
 */
class ESVTPerformanceBenchmarkTest {

    private ESVTPerformanceBenchmark benchmark;
    private Tetree<LongEntityID, String> tetree;
    private ESVTBuilder builder;
    private ESVTData testData;

    @BeforeEach
    void setUp() {
        benchmark = new ESVTPerformanceBenchmark(
            ESVTPerformanceBenchmark.BenchmarkConfig.quickConfig());
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        builder = new ESVTBuilder();

        // Build test data
        var random = new Random(42);
        for (int i = 0; i < 100; i++) {
            float x = random.nextFloat() * 500 + 100;
            float y = random.nextFloat() * 500 + 100;
            float z = random.nextFloat() * 500 + 100;
            tetree.insert(new Point3f(x, y, z), (byte) 8, "Entity" + i);
        }
        testData = builder.build(tetree);
    }

    @Test
    void testBenchmarkNullData() {
        var result = benchmark.runBenchmark(null);
        assertFalse(result.meetsTargets());
        assertEquals(0, result.totalRays());
    }

    @Test
    void testBenchmarkValidData() {
        var result = benchmark.runBenchmark(testData);

        assertTrue(result.totalRays() > 0);
        assertTrue(result.raysPerSecond() > 0);
        assertTrue(result.avgLatencyNs() > 0);
        assertNotNull(result.memoryMetrics());
        assertNotNull(result.scalabilityMetrics());
    }

    @Test
    void testMeasureThroughput() {
        double throughput = benchmark.measureThroughput(testData, 1000);
        assertTrue(throughput > 0, "Throughput should be positive");
    }

    @Test
    void testProfileLatency() {
        var distribution = benchmark.profileLatency(testData, 100);

        assertNotNull(distribution);
        assertEquals(6, distribution.percentiles().length);
        assertTrue(distribution.percentiles()[0] <= distribution.percentiles()[5],
            "Min should be <= max");
    }

    @Test
    void testBenchmarkConfigs() {
        var defaultConfig = ESVTPerformanceBenchmark.BenchmarkConfig.defaultConfig();
        var quickConfig = ESVTPerformanceBenchmark.BenchmarkConfig.quickConfig();
        var thoroughConfig = ESVTPerformanceBenchmark.BenchmarkConfig.thoroughConfig();

        assertTrue(quickConfig.measurementIterations() < defaultConfig.measurementIterations());
        assertTrue(thoroughConfig.measurementIterations() > defaultConfig.measurementIterations());
    }

    @Test
    void testBenchmarkResultSummary() {
        var result = benchmark.runBenchmark(testData);
        var summary = result.toSummary();

        assertNotNull(summary);
        assertTrue(summary.contains("Rays/sec"));
        assertTrue(summary.contains("latency"));
    }

    @Test
    void testLatencyPercentiles() {
        var result = benchmark.runBenchmark(testData);

        assertTrue(result.minLatencyNs() <= result.p50LatencyNs());
        assertTrue(result.p50LatencyNs() <= result.p95LatencyNs());
        assertTrue(result.p95LatencyNs() <= result.p99LatencyNs());
        assertTrue(result.p99LatencyNs() <= result.maxLatencyNs());
    }

    @Test
    void testParallelConfig() {
        var config = ESVTPerformanceBenchmark.BenchmarkConfig.quickConfig()
            .withParallelism(2);

        assertEquals(2, config.parallelThreads());
    }

    @Test
    void testStressTestShort() {
        // Run very short stress test
        var shortBenchmark = new ESVTPerformanceBenchmark(
            new ESVTPerformanceBenchmark.BenchmarkConfig(
                5, 10, 100, 1, 42L, false, true));

        var result = shortBenchmark.runStressTest(testData, 500); // 500ms

        assertTrue(result.durationMs() >= 400); // Allow some tolerance
        assertTrue(result.totalRays() > 0);
        assertEquals(0, result.errors());
    }

    @Test
    void testMemoryMetrics() {
        var withMemory = new ESVTPerformanceBenchmark(
            new ESVTPerformanceBenchmark.BenchmarkConfig(
                10, 50, 100, 1, 42L, true, true));

        var result = withMemory.runBenchmark(testData);
        var mem = result.memoryMetrics();

        assertNotNull(mem);
        // Memory metrics should be non-negative
        assertTrue(mem.heapUsedBefore() >= 0);
        assertTrue(mem.gcCount() >= 0);
    }
}
