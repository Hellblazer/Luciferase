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

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.traversal.ESVTTraversal;
import com.hellblazer.luciferase.esvt.traversal.ESVTRay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Standardized performance benchmarking for ESVT traversal operations.
 *
 * <p>Provides benchmarks for:
 * <ul>
 *   <li>Single ray traversal latency</li>
 *   <li>Batch ray throughput</li>
 *   <li>Memory bandwidth utilization</li>
 *   <li>Parallel scalability</li>
 *   <li>Cache efficiency analysis</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTPerformanceBenchmark {
    private static final Logger log = LoggerFactory.getLogger(ESVTPerformanceBenchmark.class);

    // Performance targets
    public static final double TARGET_RAYS_PER_SECOND = 1_000_000.0;
    public static final double TARGET_LATENCY_NS = 1000.0; // 1 microsecond per ray
    public static final double MIN_THROUGHPUT_RATIO = 0.5; // 50% of theoretical max

    /**
     * Benchmark configuration.
     */
    public record BenchmarkConfig(
        int warmupIterations,
        int measurementIterations,
        int raysPerIteration,
        int parallelThreads,
        long randomSeed,
        boolean measureMemory,
        boolean measureLatency
    ) {
        public static BenchmarkConfig defaultConfig() {
            return new BenchmarkConfig(100, 1000, 10000, 1, 42L, true, true);
        }

        public static BenchmarkConfig quickConfig() {
            return new BenchmarkConfig(10, 100, 1000, 1, 42L, false, true);
        }

        public static BenchmarkConfig thoroughConfig() {
            return new BenchmarkConfig(500, 5000, 50000,
                Runtime.getRuntime().availableProcessors(), 42L, true, true);
        }

        public BenchmarkConfig withParallelism(int threads) {
            return new BenchmarkConfig(warmupIterations, measurementIterations,
                raysPerIteration, threads, randomSeed, measureMemory, measureLatency);
        }
    }

    /**
     * Complete benchmark result.
     */
    public record BenchmarkResult(
        double raysPerSecond,
        double avgLatencyNs,
        double minLatencyNs,
        double maxLatencyNs,
        double p50LatencyNs,
        double p95LatencyNs,
        double p99LatencyNs,
        double stdDevLatencyNs,
        double hitRate,
        long totalRays,
        long totalTimeNs,
        MemoryMetrics memoryMetrics,
        ScalabilityMetrics scalabilityMetrics,
        boolean meetsTargets
    ) {
        public String toSummary() {
            return String.format(
                "Rays/sec: %.2f M, Avg latency: %.2f ns, Hit rate: %.2f%%, Meets targets: %b",
                raysPerSecond / 1_000_000, avgLatencyNs, hitRate * 100, meetsTargets);
        }
    }

    /**
     * Memory utilization metrics.
     */
    public record MemoryMetrics(
        long heapUsedBefore,
        long heapUsedAfter,
        long heapDelta,
        double bytesPerRay,
        double gcCount,
        double gcTimeMs
    ) {}

    /**
     * Parallel scalability metrics.
     */
    public record ScalabilityMetrics(
        int threadCount,
        double singleThreadRps,
        double multiThreadRps,
        double scalingEfficiency,
        double speedup
    ) {
        public static ScalabilityMetrics none() {
            return new ScalabilityMetrics(1, 0, 0, 1.0, 1.0);
        }
    }

    /**
     * Latency distribution.
     */
    public record LatencyDistribution(
        double[] percentiles, // [0, 50, 90, 95, 99, 100]
        long[] histogram,
        int histogramBuckets
    ) {}

    private final ESVTTraversal traversal;
    private final BenchmarkConfig config;

    public ESVTPerformanceBenchmark() {
        this(BenchmarkConfig.defaultConfig());
    }

    public ESVTPerformanceBenchmark(BenchmarkConfig config) {
        this.config = config;
        this.traversal = new ESVTTraversal();
    }

    /**
     * Run complete benchmark suite.
     */
    public BenchmarkResult runBenchmark(ESVTData data) {
        if (data == null || data.nodeCount() == 0) {
            return emptyResult();
        }

        // Generate rays once
        var rays = generateRays(config.raysPerIteration, config.randomSeed);

        // Warmup
        log.debug("Running {} warmup iterations", config.warmupIterations);
        for (int i = 0; i < config.warmupIterations; i++) {
            runSingleIteration(data, rays);
        }

        // Memory snapshot before
        MemoryMetrics memMetrics = null;
        long heapBefore = 0;
        int gcCountBefore = 0;
        long gcTimeBefore = 0;

        if (config.measureMemory) {
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            gcCountBefore = getGCCount();
            gcTimeBefore = getGCTime();
        }

        // Measurement
        log.debug("Running {} measurement iterations", config.measurementIterations);
        var latencies = new ArrayList<Long>(config.measurementIterations);
        long totalTimeNs = 0;
        long totalHits = 0;
        long totalRays = 0;

        for (int i = 0; i < config.measurementIterations; i++) {
            long startNs = System.nanoTime();
            int hits = runSingleIteration(data, rays);
            long endNs = System.nanoTime();

            long iterationTime = endNs - startNs;
            latencies.add(iterationTime);
            totalTimeNs += iterationTime;
            totalHits += hits;
            totalRays += rays.size();
        }

        // Memory snapshot after
        if (config.measureMemory) {
            long heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            int gcCountAfter = getGCCount();
            long gcTimeAfter = getGCTime();

            memMetrics = new MemoryMetrics(
                heapBefore, heapAfter, heapAfter - heapBefore,
                (double)(heapAfter - heapBefore) / totalRays,
                gcCountAfter - gcCountBefore,
                (gcTimeAfter - gcTimeBefore) / 1_000_000.0
            );
        } else {
            memMetrics = new MemoryMetrics(0, 0, 0, 0, 0, 0);
        }

        // Calculate statistics
        double raysPerSecond = totalRays * 1_000_000_000.0 / totalTimeNs;
        double avgLatencyNs = (double) totalTimeNs / config.measurementIterations;
        double perRayLatencyNs = (double) totalTimeNs / totalRays;
        double hitRate = (double) totalHits / totalRays;

        // Latency percentiles
        Collections.sort(latencies);
        double minLatency = latencies.isEmpty() ? 0 : latencies.get(0);
        double maxLatency = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
        double p50 = percentile(latencies, 50);
        double p95 = percentile(latencies, 95);
        double p99 = percentile(latencies, 99);
        double stdDev = calculateStdDev(latencies);

        // Scalability metrics
        ScalabilityMetrics scalability = ScalabilityMetrics.none();
        if (config.parallelThreads > 1) {
            scalability = measureScalability(data, rays);
        }

        // Check targets
        boolean meetsTargets = raysPerSecond >= TARGET_RAYS_PER_SECOND * MIN_THROUGHPUT_RATIO &&
                              perRayLatencyNs <= TARGET_LATENCY_NS * 2;

        return new BenchmarkResult(
            raysPerSecond, avgLatencyNs, minLatency, maxLatency,
            p50, p95, p99, stdDev, hitRate, totalRays, totalTimeNs,
            memMetrics, scalability, meetsTargets
        );
    }

    /**
     * Run quick throughput test.
     */
    public double measureThroughput(ESVTData data, int numRays) {
        var rays = generateRays(numRays, config.randomSeed);

        // Quick warmup
        for (int i = 0; i < 10; i++) {
            runSingleIteration(data, rays);
        }

        // Measure
        long startNs = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            runSingleIteration(data, rays);
        }
        long elapsedNs = System.nanoTime() - startNs;

        long totalRays = 100L * numRays;
        return totalRays * 1_000_000_000.0 / elapsedNs;
    }

    /**
     * Run latency profiling with detailed histogram.
     */
    public LatencyDistribution profileLatency(ESVTData data, int numSamples) {
        var rays = generateRays(1, config.randomSeed); // Single ray for latency
        var latencies = new long[numSamples];

        // Warmup
        for (int i = 0; i < 1000; i++) {
            runSingleIteration(data, rays);
        }

        // Measure individual ray latencies
        for (int i = 0; i < numSamples; i++) {
            var ray = rays.get(0);
            long startNs = System.nanoTime();
            traversal.castRay(ray, data.nodes(), data.contours(), data.farPointers(), 0);
            latencies[i] = System.nanoTime() - startNs;
        }

        // Sort for percentiles
        Arrays.sort(latencies);

        double[] percentiles = new double[6];
        percentiles[0] = latencies[0];
        percentiles[1] = latencies[(int)(numSamples * 0.50)];
        percentiles[2] = latencies[(int)(numSamples * 0.90)];
        percentiles[3] = latencies[(int)(numSamples * 0.95)];
        percentiles[4] = latencies[(int)(numSamples * 0.99)];
        percentiles[5] = latencies[numSamples - 1];

        // Build histogram (logarithmic buckets)
        int numBuckets = 20;
        long[] histogram = new long[numBuckets];
        long maxLatency = latencies[numSamples - 1];
        double logMax = Math.log10(maxLatency + 1);

        for (long latency : latencies) {
            int bucket = (int) (Math.log10(latency + 1) * numBuckets / logMax);
            bucket = Math.min(bucket, numBuckets - 1);
            histogram[bucket]++;
        }

        return new LatencyDistribution(percentiles, histogram, numBuckets);
    }

    /**
     * Stress test with sustained load.
     */
    public record StressTestResult(
        long durationMs,
        long totalRays,
        double avgRps,
        double minRps,
        double maxRps,
        int errors,
        boolean passed
    ) {}

    public StressTestResult runStressTest(ESVTData data, long durationMs) {
        var rays = generateRays(config.raysPerIteration, config.randomSeed);
        var rpsHistory = new ArrayList<Double>();

        long startTime = System.currentTimeMillis();
        long totalRays = 0;
        int errors = 0;

        while (System.currentTimeMillis() - startTime < durationMs) {
            long iterStart = System.nanoTime();

            try {
                runSingleIteration(data, rays);
                totalRays += rays.size();
            } catch (Exception e) {
                errors++;
                log.warn("Stress test error: {}", e.getMessage());
            }

            long iterTime = System.nanoTime() - iterStart;
            double iterRps = rays.size() * 1_000_000_000.0 / iterTime;
            rpsHistory.add(iterRps);
        }

        long actualDuration = System.currentTimeMillis() - startTime;
        double avgRps = rpsHistory.stream().mapToDouble(d -> d).average().orElse(0);
        double minRps = rpsHistory.stream().mapToDouble(d -> d).min().orElse(0);
        double maxRps = rpsHistory.stream().mapToDouble(d -> d).max().orElse(0);

        boolean passed = errors == 0 && avgRps >= TARGET_RAYS_PER_SECOND * MIN_THROUGHPUT_RATIO;

        return new StressTestResult(actualDuration, totalRays, avgRps, minRps, maxRps, errors, passed);
    }

    // ========== Helper Methods ==========

    private List<ESVTRay> generateRays(int count, long seed) {
        var rays = new ArrayList<ESVTRay>(count);
        var random = new Random(seed);

        for (int i = 0; i < count; i++) {
            float ox = random.nextFloat() * 2 - 1.5f;
            float oy = random.nextFloat() * 2 - 1.5f;
            float oz = random.nextFloat() * 2 - 1.5f;

            float dx = 0.5f - ox + (random.nextFloat() - 0.5f) * 0.5f;
            float dy = 0.5f - oy + (random.nextFloat() - 0.5f) * 0.5f;
            float dz = 0.5f - oz + (random.nextFloat() - 0.5f) * 0.5f;

            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > 0) {
                dx /= len;
                dy /= len;
                dz /= len;
            }

            rays.add(new ESVTRay(new Point3f(ox, oy, oz), new Vector3f(dx, dy, dz)));
        }

        return rays;
    }

    private int runSingleIteration(ESVTData data, List<ESVTRay> rays) {
        int hits = 0;
        for (var ray : rays) {
            var result = traversal.castRay(ray, data.nodes(),
                data.contours(), data.farPointers(), 0);
            if (result.hit) {
                hits++;
            }
        }
        return hits;
    }

    private ScalabilityMetrics measureScalability(ESVTData data, List<ESVTRay> rays) {
        // Single-threaded baseline
        long startSingle = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            runSingleIteration(data, rays);
        }
        long singleTime = System.nanoTime() - startSingle;
        double singleRps = 100L * rays.size() * 1_000_000_000.0 / singleTime;

        // Multi-threaded
        var executor = Executors.newFixedThreadPool(config.parallelThreads);
        try {
            long startMulti = System.nanoTime();
            var futures = new ArrayList<Future<?>>();

            for (int t = 0; t < config.parallelThreads; t++) {
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < 100 / config.parallelThreads; i++) {
                        runSingleIteration(data, rays);
                    }
                }));
            }

            for (var future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    log.warn("Thread exception: {}", e.getMessage());
                }
            }

            long multiTime = System.nanoTime() - startMulti;
            double multiRps = 100L * rays.size() * 1_000_000_000.0 / multiTime;

            double speedup = multiRps / singleRps;
            double efficiency = speedup / config.parallelThreads;

            return new ScalabilityMetrics(config.parallelThreads, singleRps, multiRps, efficiency, speedup);
        } finally {
            executor.shutdownNow();
        }
    }

    private double percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) (sorted.size() * p / 100.0);
        return sorted.get(Math.min(idx, sorted.size() - 1));
    }

    private double calculateStdDev(List<Long> values) {
        if (values.size() < 2) return 0;
        double mean = values.stream().mapToLong(l -> l).average().orElse(0);
        double variance = values.stream()
            .mapToDouble(l -> (l - mean) * (l - mean))
            .sum() / (values.size() - 1);
        return Math.sqrt(variance);
    }

    private int getGCCount() {
        int count = 0;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            count += gc.getCollectionCount();
        }
        return count;
    }

    private long getGCTime() {
        long time = 0;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            time += gc.getCollectionTime();
        }
        return time;
    }

    private BenchmarkResult emptyResult() {
        return new BenchmarkResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            new MemoryMetrics(0, 0, 0, 0, 0, 0),
            ScalabilityMetrics.none(), false);
    }
}
