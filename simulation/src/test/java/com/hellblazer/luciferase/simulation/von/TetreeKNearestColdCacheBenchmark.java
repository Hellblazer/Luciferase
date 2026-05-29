/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.entity.UUIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import javax.vecmath.Point3d;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.results.format.ResultFormatType;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark measuring cold-cache {@code Tetree.kNearestNeighbors} cost —
 * the Phase 1 trigger experiment for RDR-003 (see §Revision History
 * 2026-05-23 "Phase 0 Step 3 outcome", and {@code simulation/doc/baselines/README.md}
 * Confound 2).
 * <p>
 * <b>Why this benchmark exists.</b> The cache-hit findKNearest numbers in
 * {@code SpatialNeighborIndexBaselineBenchmark} (0.5 μs across the matrix) are
 * dominated by the k-NN cache at level 15 (~64 cache buckets in a 200³ world,
 * 128 cycled query centres → ~100% hit rate after warmup). The dual-store
 * dispatcher choice that closed Phase 0 assumed cold-cache cost was the
 * unmeasured risk that could re-open Phase 1. This benchmark measures it
 * directly.
 * <p>
 * <b>How cache misses are forced.</b> The k-NN cache key is
 * {@code (spatial-key-at-level-15, k, maxDistance)}. The two non-spatial
 * components are intentionally varied per invocation: {@code k} stays at 10
 * (the operational value) and {@code maxDistance} is incremented by a tiny
 * finite epsilon each call. The result set is unchanged (epsilon is
 * astronomically larger than the world diagonal) but each cache key is unique,
 * so every call misses, computes, stores, and (after ~10K misses)
 * LRU-evicts. The steady-state cost is therefore the
 * miss-plus-compute-plus-store path.
 * <p>
 * <b>What this benchmark does NOT measure.</b> Cold-cache cost for
 * {@link SpatialNeighborIndex#findKNearest} (the consumer-facing API): that
 * call routes through {@code Tetree.kNearestNeighbors} directly, plus a
 * second flat-map lookup per result (≤ k × 100 ns). Adding ~1 μs to the raw
 * Tetree numbers gives the SpatialNeighborIndex equivalent. The deciding
 * question for Phase 1 is the raw Tetree cost — that's what's measured here.
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
public class TetreeKNearestColdCacheBenchmark {

    /** World-space bound matching {@code WorldBounds.DEFAULT = (0, 200)}. */
    private static final float WORLD_EXTENT = 200.0f;

    /** k for the findKNearest benchmark — VoN operational value. */
    private static final int K_NEAREST = 10;

    /**
     * Cycled query-centre count. Set high enough to exercise multiple
     * level-15 cache buckets, but the cache-miss forcing comes from
     * varied {@code maxDistance} (see class JavaDoc), not from spatial
     * variation.
     */
    private static final int QUERY_CENTER_COUNT = 4096;

    /**
     * Finite base maxDistance: large enough that no candidate is pruned (the
     * world diagonal is ~346 units), small enough that float precision can
     * distinguish per-call increments.
     */
    private static final float BASE_MAX_DISTANCE = 1_000_000f;

    /**
     * Per-call increment to {@code maxDistance}. {@code 0.001f} at base
     * {@code 1e6} sits well above float ulps (~0.06) for this magnitude, so
     * every call produces a distinct float value (and therefore a distinct
     * cache key).
     */
    private static final float MAX_DISTANCE_STRIDE = 0.001f;

    @Param({"1000", "10000", "100000"})
    public int entityCount;

    /** Operational level per RDR-003 §Phase 0 Step 0 (cell-edge 8 units). */
    @Param({"18"})
    public byte spatialLevel;

    private Tetree<UUIDEntityID, Node> tetree;
    private List<Point3f>              queryCenters;
    private long                       callCounter = 0;

    @Setup(Level.Trial)
    public void setup() {
        // Seeds are deterministic and independent of spatialLevel for
        // apples-to-apples comparison with SpatialNeighborIndexBaselineBenchmark.
        var positionRng = new Random(42L ^ ((long) entityCount * 31L));
        tetree = new Tetree<>(new UUIDGenerator());
        for (int i = 0; i < entityCount; i++) {
            var p = new Point3f(positionRng.nextFloat() * WORLD_EXTENT,
                                positionRng.nextFloat() * WORLD_EXTENT,
                                positionRng.nextFloat() * WORLD_EXTENT);
            tetree.insert(new UUIDEntityID(UUID.randomUUID()), p, spatialLevel,
                          new StubNode(UUID.randomUUID(), new Point3d(p.x, p.y, p.z)));
        }

        var centerRng = new Random(0xC11C7E7L);
        queryCenters = new ArrayList<>(QUERY_CENTER_COUNT);
        for (int i = 0; i < QUERY_CENTER_COUNT; i++) {
            queryCenters.add(new Point3f(centerRng.nextFloat() * WORLD_EXTENT,
                                          centerRng.nextFloat() * WORLD_EXTENT,
                                          centerRng.nextFloat() * WORLD_EXTENT));
        }
        callCounter = 0;
    }

    @Benchmark
    public List<UUIDEntityID> findKNearestColdCache() {
        var center = queryCenters.get((int) (callCounter & (QUERY_CENTER_COUNT - 1)));
        // Force cache miss by perturbing maxDistance per invocation. Result
        // set is identical to maxDistance = +Infinity because the world
        // diagonal is ~346 units (well under BASE_MAX_DISTANCE).
        float maxDistance = BASE_MAX_DISTANCE + (callCounter++ % 100_000L) * MAX_DISTANCE_STRIDE;
        return tetree.kNearestNeighbors(center, K_NEAREST, maxDistance);
    }

    @Test
    @Disabled("JMH benchmark — run manually via main() or -Djunit.jupiter.conditions.deactivate=*")
    public void runBenchmark() throws RunnerException {
        main(new String[]{});
    }

    public static void main(String[] args) throws RunnerException {
        var resultPath = System.getProperty(
            "jmh.result",
            System.getProperty("user.dir") + "/simulation/target/jmh-tetree-knn-cold-cache.json");
        var builder = new OptionsBuilder()
            .include(TetreeKNearestColdCacheBenchmark.class.getSimpleName())
            .forks(0)
            .resultFormat(ResultFormatType.JSON)
            .result(resultPath);
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "-wi" -> builder.warmupIterations(Integer.parseInt(args[++i]));
                case "-i"  -> builder.measurementIterations(Integer.parseInt(args[++i]));
                case "-f"  -> builder.forks(Integer.parseInt(args[++i]));
                case "-p"  -> {
                    var kv = args[++i].split("=", 2);
                    if (kv.length == 2) builder.param(kv[0], kv[1].split(","));
                }
                default    -> { /* ignored */ }
            }
        }
        new Runner(builder.build()).run();
    }

    /**
     * Lightweight stub Node returning only {@code id()} and {@code position()}.
     * Mirrors {@code SpatialNeighborIndexBaselineBenchmark.StubNode} for
     * cross-benchmark consistency.
     */
    private record StubNode(UUID id, Point3d position) implements Node {
        @Override
        public BubbleBounds bounds() {
            throw new UnsupportedOperationException("bounds() not exercised by k-NN benchmark");
        }
        @Override public Set<UUID> neighbors()                  { return Set.of(); }
        @Override public void notifyMove(Node neighbor)         { }
        @Override public void notifyLeave(Node neighbor)        { }
        @Override public void notifyJoin(Node neighbor)         { }
        @Override public void addNeighbor(UUID neighborId)      { }
        @Override public void removeNeighbor(UUID neighborId)   { }
    }
}
