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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * B3: Runtime Performance-Driven Tuning Benchmark
 *
 * Benchmarks workgroup configurations by running actual GPU kernel invocations
 * and measuring throughput and latency. Supports:
 * - Single config benchmarking
 * - Multi-config comparison
 * - Timeout protection for hung configs
 * - Result caching for future sessions
 *
 * @author hal.hildebrand
 */
public class TuningBenchmark {

    private static final Logger log = LoggerFactory.getLogger(TuningBenchmark.class);

    /**
     * Benchmark result for a single configuration
     */
    public record BenchmarkResult(
        WorkgroupConfig config,
        double throughputRaysPerMicrosecond,
        double latencyMicroseconds,
        int raysProcessed,
        boolean timedOut,
        String errorMessage
    ) {
        /**
         * Check if benchmark completed successfully
         */
        public boolean isSuccessful() {
            return !timedOut && errorMessage == null && throughputRaysPerMicrosecond > 0;
        }

        /**
         * Create failed result
         */
        public static BenchmarkResult failed(WorkgroupConfig config, String error) {
            return new BenchmarkResult(config, 0.0, 0.0, 0, false, error);
        }

        /**
         * Create timeout result
         */
        public static BenchmarkResult timeout(WorkgroupConfig config) {
            return new BenchmarkResult(config, 0.0, 0.0, 0, true, "Benchmark timed out");
        }

        /**
         * Create successful result
         */
        public static BenchmarkResult success(WorkgroupConfig config, double throughput, double latency, int rays) {
            return new BenchmarkResult(config, throughput, latency, rays, false, null);
        }
    }

    /**
     * Executor function interface for benchmarking
     * Allows injection of mock executor for testing
     */
    @FunctionalInterface
    public interface BenchmarkExecutor {
        /**
         * Execute kernel with given config and return metrics
         *
         * @param config workgroup configuration
         * @param rayCount number of rays to process
         * @return tuple of (throughput rays/µs, latency µs)
         * @throws Exception if execution fails
         */
        double[] execute(WorkgroupConfig config, int rayCount) throws Exception;
    }

    private final BenchmarkExecutor executor;
    private final Duration timeout;
    private final int warmupRuns;
    private final int benchmarkRuns;
    private final int rayCount;
    private final ExecutorService executorService;

    /**
     * Create benchmark with default settings
     *
     * @param executor function to execute kernel benchmark
     */
    public TuningBenchmark(BenchmarkExecutor executor) {
        this(executor, Duration.ofSeconds(5), 1, 3, 1000);
    }

    /**
     * Create benchmark with custom settings
     *
     * @param executor function to execute kernel benchmark
     * @param timeout max time per config before aborting
     * @param warmupRuns number of warmup iterations (not counted)
     * @param benchmarkRuns number of benchmark iterations to average
     * @param rayCount number of rays per benchmark iteration
     */
    public TuningBenchmark(BenchmarkExecutor executor, Duration timeout,
                          int warmupRuns, int benchmarkRuns, int rayCount) {
        this.executor = executor;
        this.timeout = timeout;
        this.warmupRuns = warmupRuns;
        this.benchmarkRuns = benchmarkRuns;
        this.rayCount = rayCount;
        this.executorService = Executors.newSingleThreadExecutor();
    }

    /**
     * Benchmark a single configuration
     *
     * @param config workgroup configuration to benchmark
     * @return benchmark result
     */
    public BenchmarkResult benchmarkConfig(WorkgroupConfig config) {
        try {
            var future = executorService.submit(() -> runBenchmark(config));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Benchmark timed out for config: {} threads, depth {}",
                    config.workgroupSize(), config.maxTraversalDepth());
            return BenchmarkResult.timeout(config);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BenchmarkResult.failed(config, "Benchmark interrupted");
        } catch (ExecutionException e) {
            log.warn("Benchmark failed for config {}: {}", config.workgroupSize(), e.getMessage());
            return BenchmarkResult.failed(config, e.getCause().getMessage());
        }
    }

    /**
     * Benchmark multiple configurations and return ranked results
     *
     * @param configs list of configurations to benchmark
     * @return list of results sorted by throughput (highest first)
     */
    public List<BenchmarkResult> benchmarkConfigs(List<WorkgroupConfig> configs) {
        var results = new ArrayList<BenchmarkResult>();

        for (var config : configs) {
            var result = benchmarkConfig(config);
            results.add(result);

            if (result.isSuccessful()) {
                log.info("Benchmark: {} threads, depth {} -> {} rays/µs",
                        config.workgroupSize(), config.maxTraversalDepth(),
                        String.format("%.2f", result.throughputRaysPerMicrosecond()));
            }
        }

        // Sort by throughput descending (best first)
        results.sort((a, b) -> Double.compare(b.throughputRaysPerMicrosecond(), a.throughputRaysPerMicrosecond()));

        return results;
    }

    /**
     * Select optimal configuration by benchmarking all candidates
     *
     * @param candidates list of candidate configurations
     * @return best performing configuration, or first candidate if all fail
     */
    public WorkgroupConfig selectOptimalConfig(List<WorkgroupConfig> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No candidates to benchmark");
        }

        var results = benchmarkConfigs(candidates);

        // Find first successful result (already sorted by throughput)
        for (var result : results) {
            if (result.isSuccessful()) {
                log.info("Selected optimal config: {} threads, depth {} ({} rays/µs)",
                        result.config().workgroupSize(),
                        result.config().maxTraversalDepth(),
                        String.format("%.2f", result.throughputRaysPerMicrosecond()));
                return result.config();
            }
        }

        // All benchmarks failed, return first candidate as fallback
        log.warn("All benchmarks failed, using first candidate as fallback");
        return candidates.get(0);
    }

    /**
     * Run benchmark iterations for a config (called within timeout-protected thread)
     */
    private BenchmarkResult runBenchmark(WorkgroupConfig config) {
        try {
            // Warmup runs (not counted)
            for (int i = 0; i < warmupRuns; i++) {
                executor.execute(config, rayCount);
            }

            // Benchmark runs (averaged)
            double totalThroughput = 0.0;
            double totalLatency = 0.0;
            int totalRays = 0;

            for (int i = 0; i < benchmarkRuns; i++) {
                var metrics = executor.execute(config, rayCount);
                totalThroughput += metrics[0];
                totalLatency += metrics[1];
                totalRays += rayCount;
            }

            double avgThroughput = totalThroughput / benchmarkRuns;
            double avgLatency = totalLatency / benchmarkRuns;

            return BenchmarkResult.success(config, avgThroughput, avgLatency, totalRays);

        } catch (Exception e) {
            return BenchmarkResult.failed(config, e.getMessage());
        }
    }

    /**
     * Shutdown the executor service
     */
    public void shutdown() {
        executorService.shutdownNow();
    }

    /**
     * Create a mock executor for testing that simulates throughput based on config
     *
     * @param throughputMultiplier multiplier applied to workgroupSize/16 to simulate throughput
     * @return mock benchmark executor
     */
    public static BenchmarkExecutor mockExecutor(double throughputMultiplier) {
        return (config, rayCount) -> {
            // Simulate: larger workgroups = higher throughput (up to a point)
            double baseThroughput = config.workgroupSize() / 16.0 * throughputMultiplier;
            // Simulate: deeper stacks = slightly lower throughput (more memory pressure)
            double depthPenalty = 1.0 - (config.maxTraversalDepth() - 16) * 0.02;
            double throughput = baseThroughput * Math.max(0.5, depthPenalty);

            // Simulate latency (inversely proportional to throughput)
            double latency = rayCount / throughput / 1000.0; // µs

            // Add some noise to simulate real GPU variation
            double noise = 0.95 + Math.random() * 0.1; // ±5%

            return new double[]{throughput * noise, latency / noise};
        };
    }

    /**
     * Create a mock executor that throws on specific configs (for timeout testing)
     *
     * @param slowConfigs configs that will take too long (for timeout testing)
     * @return mock benchmark executor
     */
    public static BenchmarkExecutor slowExecutor(Set<Integer> slowWorkgroupSizes) {
        return (config, rayCount) -> {
            if (slowWorkgroupSizes.contains(config.workgroupSize())) {
                // Simulate hung kernel
                Thread.sleep(10000);
            }
            return new double[]{1.0, 1000.0}; // Default result if not slow
        };
    }
}
