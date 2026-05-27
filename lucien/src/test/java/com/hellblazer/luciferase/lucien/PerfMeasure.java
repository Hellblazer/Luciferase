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
package com.hellblazer.luciferase.lucien;

/**
 * Microbenchmark hygiene helpers for latency-asserting tests.
 *
 * <p>A test that times a <i>single, cold</i> invocation and asserts an absolute latency bound is measuring one-time
 * costs the warm JVM hides — class loading and interpreted execution before the JIT compiles the path. On a fast warm
 * machine the cold call is a few hundred microseconds; under CI or concurrent-benchmark CPU load it can spike an order
 * of magnitude higher and trip the bound, producing a flaky failure that is a measurement artifact, not a regression.
 *
 * <p>The fix (used by {@code Luciferase-a7r}/{@code lgu} and consolidated here for {@code Luciferase-tlb}): {@link
 * #warmup(int, Runnable)} the path with untimed iterations so it is class-loaded and JIT-compiled, then take the
 * {@link #bestNanos(int, Runnable) best (minimum)} of several timed runs — the minimum is the run least perturbed by
 * scheduler/GC/CPU contention, i.e. the closest estimate of true steady-state cost. Keep the original functional
 * assertions; only the timing measurement changes.
 */
public final class PerfMeasure {

    private PerfMeasure() {
    }

    /**
     * Run {@code op} {@code iterations} times without timing, to trigger class loading and JIT compilation of the path
     * before it is measured.
     *
     * @param iterations number of untimed warmup runs (must be {@code >= 0})
     * @param op         the operation to warm
     */
    public static void warmup(int iterations, Runnable op) {
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations must be >= 0, was " + iterations);
        }
        for (int i = 0; i < iterations; i++) {
            op.run();
        }
    }

    /**
     * Execute {@code op} {@code runs} times and return the minimum elapsed wall-clock time in nanoseconds. The minimum
     * is reported because it is the run least perturbed by external CPU contention, giving the most stable estimate of
     * steady-state cost. Warm the path with {@link #warmup(int, Runnable)} first.
     *
     * @param runs number of timed runs (must be {@code >= 1})
     * @param op   the operation to measure
     * @return the smallest {@link System#nanoTime()} delta observed across the runs
     */
    public static long bestNanos(int runs, Runnable op) {
        if (runs < 1) {
            throw new IllegalArgumentException("runs must be >= 1, was " + runs);
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            var start = System.nanoTime();
            op.run();
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }
}
