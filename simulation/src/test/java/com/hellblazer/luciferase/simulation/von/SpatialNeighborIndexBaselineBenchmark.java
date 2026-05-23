/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.results.format.ResultFormatType;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * JMH baseline benchmark for {@link SpatialNeighborIndex} — RDR-003 Phase 0 Steps 1 and 1b
 * (beads {@code Luciferase-d8a} and {@code Luciferase-jp7}).
 * <p>
 * Measures the current linear-scan implementation's query latency at
 * {@code N ∈ {1K, 10K, 100K}} entities × representative AoI radii × the two spatial
 * levels of interest (10 = original / degenerate, 18 = corrected post-Step-0).
 * <p>
 * <b>Empirical observation justifying the single-harness design:</b>
 * {@link SpatialNeighborIndex#findWithinRadius} and {@link SpatialNeighborIndex#findKNearest}
 * iterate a flat {@code ConcurrentHashMap} computing Euclidean distance between
 * {@code Point3D} positions — the spatial level never enters the query path. So
 * the {@code spatialLevel} {@code @Param} produces no expected difference in query
 * timings; identical numbers across the two levels are the empirical confirmation
 * that, for the current linear-scan implementation, the level fix in Step 0
 * contributes zero to query speedup. The Step 3 differential analysis
 * (Tetree-backed / linear-scan-at-corrected-level) is what isolates the
 * data-structure contribution.
 * <p>
 * <b>Lightweight stub Node:</b> the benchmark uses a {@link StubNode} record rather
 * than constructing real {@link Bubble} instances. Each production {@link Bubble}
 * owns a {@code Transport} and a per-instance {@code ScheduledThreadPool} — at
 * {@code N = 100K} that is 100K daemon threads which is not viable for setup. The
 * stub returns only the fields the index actually reads ({@code id()},
 * {@code position()}); the unused {@link com.hellblazer.luciferase.simulation.von.Node}
 * methods throw or return empty.
 * <p>
 * <b>How to run:</b>
 * <pre>
 *   mvn test -pl simulation \
 *     -Dtest=SpatialNeighborIndexBaselineBenchmark#runBenchmark \
 *     -Dsurefire.rerunFailingTestsCount=0
 * </pre>
 * Results are emitted as JSON to {@code simulation/target/jmh-spatial-neighbor-index-baseline.json}
 * and should be copied to {@code simulation/doc/baselines/} for archival.
 *
 * @author hal.hildebrand
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(0)
@State(Scope.Benchmark)
@Tag("benchmark")
public class SpatialNeighborIndexBaselineBenchmark {

    /** World-space bound matching {@code WorldBounds.DEFAULT = (0, 200)}. */
    private static final float WORLD_EXTENT = 200.0f;

    /** Number of distinct query centers cycled through to defeat single-point caches. */
    private static final int QUERY_CENTER_COUNT = 128;

    /** k for the findKNearest benchmark. */
    private static final int K_NEAREST = 10;

    /** Boundary buffer matching the existing VoN test convention. */
    private static final float BOUNDARY_BUFFER = 10.0f;

    @Param({"1000", "10000", "100000"})
    public int entityCount;

    /**
     * d8a = 10 (original / degenerate single-cell), jp7 = 18 (corrected per Step 0).
     * The current linear-scan index does not consume this value; identical timings
     * across levels are the empirical confirmation.
     */
    @Param({"10", "18"})
    public byte spatialLevel;

    @Param({"10", "20", "50", "100"})
    public float queryRadius;

    private SpatialNeighborIndex index;
    private List<Point3D> queryCenters;
    private int queryCursor;

    @Setup(Level.Trial)
    public void setup() {
        // Seeds DO NOT depend on spatialLevel: for an apples-to-apples comparison
        // across levels, level=10 and level=18 trials must run against the SAME
        // point cloud and the SAME query centers. The empirical question is
        // "does the spatial level affect query timing?" — which we cannot answer
        // if level=10 and level=18 see different random distributions.
        var positionRng = new Random(42L ^ ((long) entityCount * 31L) ^ Float.floatToIntBits(queryRadius));
        index = new SpatialNeighborIndex(queryRadius, BOUNDARY_BUFFER);

        for (int i = 0; i < entityCount; i++) {
            var position = new Point3D(
                positionRng.nextFloat() * WORLD_EXTENT,
                positionRng.nextFloat() * WORLD_EXTENT,
                positionRng.nextFloat() * WORLD_EXTENT
            );
            index.insert(new StubNode(UUID.randomUUID(), position));
        }

        var centerRng = new Random(0xC11C7E7L);
        queryCenters = new ArrayList<>(QUERY_CENTER_COUNT);
        for (int i = 0; i < QUERY_CENTER_COUNT; i++) {
            queryCenters.add(new Point3D(
                centerRng.nextFloat() * WORLD_EXTENT,
                centerRng.nextFloat() * WORLD_EXTENT,
                centerRng.nextFloat() * WORLD_EXTENT
            ));
        }
        queryCursor = 0;
    }

    @Benchmark
    public List<Node> findWithinRadius() {
        var center = queryCenters.get((queryCursor++) & (QUERY_CENTER_COUNT - 1));
        return index.findWithinRadius(center, queryRadius);
    }

    @Benchmark
    public List<Node> findKNearest() {
        var center = queryCenters.get((queryCursor++) & (QUERY_CENTER_COUNT - 1));
        return index.findKNearest(center, K_NEAREST);
    }

    /**
     * JUnit-driven entry point. {@code @Disabled} to keep CI / regular test runs fast;
     * opt-in via {@code -Dtest=SpatialNeighborIndexBaselineBenchmark#runBenchmark}.
     */
    @Test
    @Disabled("JMH benchmark — run manually via main() or -Djunit.jupiter.conditions.deactivate=*")
    public void runBenchmark() throws RunnerException {
        main(new String[]{});
    }

    /**
     * Direct JMH entry point — bypasses JUnit so the {@code @Disabled} doesn't apply.
     * Invoke via {@code mvn -pl simulation test-compile exec:java -Dexec.mainClass=...
     * -Dexec.classpathScope=test}. Accepts JMH-style flags through {@code args}
     * (e.g. quick smoke with {@code -wi 1 -i 1 -w 200ms -r 200ms}).
     */
    public static void main(String[] args) throws RunnerException {
        // Allow override via -Djmh.result=...; default to a path under the simulation
        // module's target directory regardless of cwd (mvn exec:java's cwd is repo root).
        var resultPath = System.getProperty(
            "jmh.result",
            System.getProperty("user.dir") + "/simulation/target/jmh-spatial-neighbor-index-baseline.json");
        var builder = new OptionsBuilder()
            .include(SpatialNeighborIndexBaselineBenchmark.class.getSimpleName())
            // forks(0) runs in-process — required because mvn exec:java does not
            // populate java.class.path for a forked JVM, so JMH's default fork(1)
            // fails with ClassNotFoundException on ForkedMain. In-process is
            // acceptable here: we compare relative timings of operations on the
            // same JVM, not absolute throughput against external baselines.
            .forks(0)
            .resultFormat(ResultFormatType.JSON)
            .result(resultPath);
        // Parse a small subset of JMH-style overrides for smoke-test convenience.
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "-wi" -> builder.warmupIterations(Integer.parseInt(args[++i]));
                case "-i"  -> builder.measurementIterations(Integer.parseInt(args[++i]));
                case "-f"  -> builder.forks(Integer.parseInt(args[++i]));
                case "-p"  -> {
                    var kv = args[++i].split("=", 2);
                    if (kv.length == 2) builder.param(kv[0], kv[1].split(","));
                }
                default    -> { /* ignored — keep defaults */ }
            }
        }
        new Runner(builder.build()).run();
    }

    /**
     * Lightweight stub Node returning only {@code id()} and {@code position()}.
     * Unused methods throw or return empty to surface accidental misuse.
     */
    private record StubNode(UUID id, Point3D position) implements Node {

        @Override
        public BubbleBounds bounds() {
            throw new UnsupportedOperationException(
                "bounds() not exercised by query benchmarks; stub Node by design");
        }

        @Override
        public Set<UUID> neighbors() {
            return Set.of();
        }

        @Override public void notifyMove(Node neighbor) { }
        @Override public void notifyLeave(Node neighbor) { }
        @Override public void notifyJoin(Node neighbor) { }
        @Override public void addNeighbor(UUID neighborId) { }
        @Override public void removeNeighbor(UUID neighborId) { }
    }
}
