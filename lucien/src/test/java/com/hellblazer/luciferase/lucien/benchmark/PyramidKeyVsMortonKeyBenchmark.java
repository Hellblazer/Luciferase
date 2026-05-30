/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.benchmark;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.pyramid.PyramidIndex;
import com.hellblazer.luciferase.lucien.pyramid.PyramidKey;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;

/**
 * RDR-010 pi1.7 microbenchmark (bead Luciferase-pi1.7) — quantifies the cost of the 128-bit
 * {@link PyramidKey} (two {@code long}s, 16 bytes) relative to the 64-bit {@link MortonKey} (one
 * {@code long}, 8 bytes). Closes RDR-010 Finding #7 / Open-Q (validate the representation before any
 * at-scale hybrid deployment) and the Cost/risk §312 per-element comparison-overhead question.
 *
 * <p>Two questions, four benchmarks:
 * <ul>
 *   <li><b>compareTo overhead</b> ({@code compareTo*}): the per-element branching cost of a 2-long vs a
 *       1-long key comparison — the operation {@code ConcurrentSkipListMap} performs O(log n) times per
 *       insert/lookup.</li>
 *   <li><b>storage + lookup at scale</b> ({@code skipListGet*}): {@code ConcurrentSkipListMap} lookup
 *       throughput, which folds in both the comparison overhead and the larger key footprint / reduced
 *       cache locality of the 128-bit key.</li>
 * </ul>
 *
 * <p>Run manually (not part of the test suite or CI): {@code java ... PyramidKeyVsMortonKeyBenchmark}
 * or via the {@link #main(String[])} JMH runner. Record results in
 * {@code lucien/doc/PERFORMANCE_METRICS_MASTER.md}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class PyramidKeyVsMortonKeyBenchmark {

    @Param({ "10000", "100000" })
    private int keyCount;

    private MortonKey[] mortonKeys;
    private PyramidKey[] pyramidKeys;
    private MortonKey[] mortonProbes;
    private PyramidKey[] pyramidProbes;
    private ConcurrentSkipListMap<MortonKey, Integer> mortonMap;
    private ConcurrentSkipListMap<PyramidKey, Integer> pyramidMap;

    @Setup
    public void setup() {
        var rng = new Random(42);

        // Harvest valid keys of each shape by inserting a randomized point cloud and reading the
        // occupied spatial keys (guarantees genuine, well-formed SFC keys of each type).
        mortonKeys = harvestMorton(Math.max(keyCount, 1), rng).toArray(new MortonKey[0]);
        pyramidKeys = harvestPyramid(Math.max(keyCount, 1), rng).toArray(new PyramidKey[0]);

        // Equal-length probe arrays (cycled) so the two compareTo loops do identical work counts.
        int n = Math.min(mortonKeys.length, pyramidKeys.length);
        mortonKeys = java.util.Arrays.copyOf(mortonKeys, n);
        pyramidKeys = java.util.Arrays.copyOf(pyramidKeys, n);
        mortonProbes = shuffledCopy(mortonKeys, rng);
        pyramidProbes = shuffledCopy(pyramidKeys, rng);

        mortonMap = new ConcurrentSkipListMap<>();
        pyramidMap = new ConcurrentSkipListMap<>();
        for (int i = 0; i < n; i++) {
            mortonMap.put(mortonKeys[i], i);
            pyramidMap.put(pyramidKeys[i], i);
        }
    }

    @Benchmark
    public void compareToMorton(Blackhole bh) {
        for (int i = 1; i < mortonKeys.length; i++) {
            bh.consume(mortonKeys[i].compareTo(mortonKeys[i - 1]));
        }
    }

    @Benchmark
    public void compareToPyramid(Blackhole bh) {
        for (int i = 1; i < pyramidKeys.length; i++) {
            bh.consume(pyramidKeys[i].compareTo(pyramidKeys[i - 1]));
        }
    }

    @Benchmark
    public void skipListGetMorton(Blackhole bh) {
        for (var probe : mortonProbes) {
            bh.consume(mortonMap.get(probe));
        }
    }

    @Benchmark
    public void skipListGetPyramid(Blackhole bh) {
        for (var probe : pyramidProbes) {
            bh.consume(pyramidMap.get(probe));
        }
    }

    private static List<MortonKey> harvestMorton(int target, Random rng) {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        int i = 0;
        while (octree.getSpatialKeys().size() < target && i < target * 4) {
            octree.insert(new Point3f(rng.nextFloat() * 1_000_000f, rng.nextFloat() * 1_000_000f,
                                      rng.nextFloat() * 1_000_000f), (byte) 14, "m" + (i++));
        }
        return new ArrayList<>(octree.getSpatialKeys());
    }

    private static List<PyramidKey> harvestPyramid(int target, Random rng) {
        var pyramid = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        int i = 0;
        while (pyramid.getSpatialKeys().size() < target && i < target * 4) {
            pyramid.insert(new Point3f(rng.nextFloat() * 1_000_000f, rng.nextFloat() * 1_000_000f,
                                       rng.nextFloat() * 1_000_000f), (byte) 14, "p" + (i++));
        }
        return new ArrayList<>(pyramid.getSpatialKeys());
    }

    private static <T> T[] shuffledCopy(T[] src, Random rng) {
        var copy = src.clone();
        for (int i = copy.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            var tmp = copy[i];
            copy[i] = copy[j];
            copy[j] = tmp;
        }
        return copy;
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
