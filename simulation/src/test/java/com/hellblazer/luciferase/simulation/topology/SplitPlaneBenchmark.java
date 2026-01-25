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
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JMH performance benchmarks for split plane strategy implementations.
 * <p>
 * Measures:
 * - LongestAxisStrategy: AABB computation + centroid calculation overhead
 * - FixedAxisStrategy (X, Y, Z): Simple offset calculation baseline
 * - CyclicAxisStrategy: AtomicInteger counter overhead
 * <p>
 * Performance Targets:
 * - LongestAxisStrategy: < 1ms for 10K entities
 * - FixedAxisStrategy: < 0.1ms for 10K entities
 * - CyclicAxisStrategy: < 0.2ms for 10K entities
 * <p>
 * Baseline Results (Example - will be updated after first run):
 * <pre>
 * Benchmark                                    (entityCount)  Mode  Cnt   Score   Error  Units
 * SplitPlaneBenchmark.benchmarkCyclicStrategy            100  avgt   10   0.XXX ± 0.XXX  us/op
 * SplitPlaneBenchmark.benchmarkCyclicStrategy           1000  avgt   10   X.XXX ± 0.XXX  us/op
 * SplitPlaneBenchmark.benchmarkCyclicStrategy          10000  avgt   10  XX.XXX ± X.XXX  us/op
 * SplitPlaneBenchmark.benchmarkFixedXStrategy            100  avgt   10   0.XXX ± 0.XXX  us/op
 * SplitPlaneBenchmark.benchmarkFixedXStrategy           1000  avgt   10   X.XXX ± 0.XXX  us/op
 * SplitPlaneBenchmark.benchmarkFixedXStrategy          10000  avgt   10  XX.XXX ± X.XXX  us/op
 * SplitPlaneBenchmark.benchmarkLongestAxisStrategy       100  avgt   10   0.XXX ± 0.XXX  us/op
 * SplitPlaneBenchmark.benchmarkLongestAxisStrategy      1000  avgt   10   X.XXX ± 0.XXX  us/op
 * SplitPlaneBenchmark.benchmarkLongestAxisStrategy     10000  avgt   10  XX.XXX ± X.XXX  us/op
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {"-Xms1G", "-Xmx1G"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class SplitPlaneBenchmark {

    /**
     * Number of entities to use for benchmark.
     * Tests three scales: small (100), medium (1000), large (10000).
     */
    @Param({"100", "1000", "10000"})
    private int entityCount;

    // Test data
    private BubbleBounds bounds;
    private List<EnhancedBubble.EntityRecord> entities;

    // Strategy instances
    private SplitPlaneStrategy longestAxisStrategy;
    private SplitPlaneStrategy fixedXStrategy;
    private SplitPlaneStrategy cyclicStrategy;

    /**
     * Setup test data once per iteration.
     * <p>
     * Generates entity distribution and bounds for benchmarking.
     * Uses fixed seed for reproducibility.
     */
    @Setup(Level.Iteration)
    public void setup() {
        // Initialize strategies
        longestAxisStrategy = SplitPlaneStrategies.longestAxis();
        fixedXStrategy = SplitPlaneStrategies.xAxis();
        cyclicStrategy = SplitPlaneStrategies.cyclic();

        // Generate entities with controlled distribution
        var random = new Random(42);  // Fixed seed for reproducibility
        entities = new ArrayList<>(entityCount);

        for (int i = 0; i < entityCount; i++) {
            var pos = new Point3f(
                random.nextFloat() * 1000.0f,  // X: 0-1000
                random.nextFloat() * 1000.0f,  // Y: 0-1000
                random.nextFloat() * 1000.0f   // Z: 0-1000
            );
            entities.add(new EnhancedBubble.EntityRecord(
                UUID.randomUUID().toString(),
                pos,
                null,
                0L
            ));
        }

        // Create bounds from entity positions
        bounds = BubbleBounds.fromEntityPositions(
            entities.stream().map(EnhancedBubble.EntityRecord::position).toList()
        );
    }

    /**
     * Benchmark LongestAxisStrategy.calculate().
     * <p>
     * Measures:
     * - AABB computation from entity positions
     * - Dimension comparison (dx, dy, dz)
     * - Centroid calculation
     * - Plane construction
     * <p>
     * Expected Performance:
     * - 100 entities: < 10 us
     * - 1000 entities: < 100 us
     * - 10000 entities: < 1000 us (1ms)
     *
     * @return calculated split plane (prevents dead code elimination)
     */
    @Benchmark
    public SplitPlane benchmarkLongestAxisStrategy() {
        return longestAxisStrategy.calculate(bounds, entities);
    }

    /**
     * Benchmark fixed X-axis strategy.calculate().
     * <p>
     * Baseline measurement - simplest strategy with:
     * - Centroid calculation only
     * - No AABB computation
     * - No dimension comparison
     * <p>
     * Expected Performance:
     * - 100 entities: < 5 us
     * - 1000 entities: < 50 us
     * - 10000 entities: < 100 us (0.1ms)
     *
     * @return calculated split plane (prevents dead code elimination)
     */
    @Benchmark
    public SplitPlane benchmarkFixedXStrategy() {
        return fixedXStrategy.calculate(bounds, entities);
    }

    /**
     * Benchmark CyclicAxisStrategy.calculate().
     * <p>
     * Measures:
     * - AtomicInteger.getAndIncrement() overhead
     * - Modulo operation
     * - Switch statement dispatch
     * - Centroid calculation
     * <p>
     * Expected Performance:
     * - 100 entities: < 8 us
     * - 1000 entities: < 80 us
     * - 10000 entities: < 200 us (0.2ms)
     *
     * @return calculated split plane (prevents dead code elimination)
     */
    @Benchmark
    public SplitPlane benchmarkCyclicStrategy() {
        return cyclicStrategy.calculate(bounds, entities);
    }

    /**
     * Run benchmarks from command line.
     * <p>
     * Usage:
     * <pre>
     * mvn test -pl simulation -Dtest=SplitPlaneBenchmark
     * </pre>
     * <p>
     * Or with specific parameters:
     * <pre>
     * mvn test -pl simulation -Dtest=SplitPlaneBenchmark \
     *   -Djmh.params="entityCount=10000"
     * </pre>
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(SplitPlaneBenchmark.class.getSimpleName())
            .build();

        new Runner(opt).run();
    }
}
