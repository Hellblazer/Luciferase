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
package com.hellblazer.luciferase.esvt.app;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.io.ESVTDeserializer;
import com.hellblazer.luciferase.esvt.traversal.ESVTTraversal;
import com.hellblazer.luciferase.esvt.traversal.ESVTRay;
import com.hellblazer.luciferase.esvt.traversal.ESVTResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Benchmark mode for ESVT performance testing.
 *
 * <p>Provides comprehensive performance benchmarking including:
 * <ul>
 *   <li>Warmup phase to stabilize JIT compilation</li>
 *   <li>Primary benchmark with configurable ray count</li>
 *   <li>Stress tests (random, coherent, worst-case patterns)</li>
 *   <li>Performance scoring and analysis</li>
 *   <li>CSV report generation</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTBenchmarkMode {
    private static final Logger log = LoggerFactory.getLogger(ESVTBenchmarkMode.class);

    private final ESVTCommandLine.Config config;
    private final PrintStream out;

    private ESVTData data;
    private ESVTTraversal traversal;
    private final List<BenchmarkResult> results = new ArrayList<>();

    public ESVTBenchmarkMode(ESVTCommandLine.Config config) {
        this(config, System.out);
    }

    public ESVTBenchmarkMode(ESVTCommandLine.Config config, PrintStream out) {
        this.config = config;
        this.out = out;
    }

    /**
     * Run benchmark mode.
     */
    public static int run(ESVTCommandLine.Config config) {
        return new ESVTBenchmarkMode(config).execute();
    }

    /**
     * Execute benchmarks.
     */
    public int execute() {
        try {
            printHeader();

            // Load ESVT file
            phase("Loading ESVT File");
            if (!loadESVTFile()) {
                return 1;
            }

            // Warmup phase
            phase("Warmup Phase");
            runWarmup();

            // Primary benchmark
            phase("Primary Benchmark");
            var primaryResult = runPrimaryBenchmark();
            results.add(primaryResult);
            reportBenchmarkResult("Primary", primaryResult);

            // Stress tests
            if (config.stressTest) {
                phase("Stress Tests");

                var randomResult = runRandomRayBenchmark();
                results.add(randomResult);
                reportBenchmarkResult("Random Rays", randomResult);

                var coherentResult = runCoherentRayBenchmark();
                results.add(coherentResult);
                reportBenchmarkResult("Coherent Rays", coherentResult);

                var worstCaseResult = runWorstCaseBenchmark();
                results.add(worstCaseResult);
                reportBenchmarkResult("Worst Case", worstCaseResult);
            }

            // Performance analysis
            phase("Performance Analysis");
            var score = analyzePerformance();
            reportPerformanceScore(score);

            // Save report
            if (config.reportFile != null) {
                phase("Saving Report");
                saveCSVReport(Path.of(config.reportFile));
            }

            printFooter(true);
            return 0;

        } catch (Exception e) {
            error("Benchmark failed: " + e.getMessage());
            log.error("Benchmark failed", e);
            printFooter(false);
            return 1;
        }
    }

    private boolean loadESVTFile() {
        try {
            var inputPath = Path.of(config.inputFile);
            progress("Loading: " + inputPath);

            var startTime = System.nanoTime();
            var deserializer = new ESVTDeserializer();
            data = deserializer.deserialize(inputPath);
            var loadTimeMs = (System.nanoTime() - startTime) / 1_000_000.0;

            progress("Nodes: " + data.nodeCount());
            progress("Leaves: " + data.leafCount());
            progress("Max depth: " + data.maxDepth());
            progress("Load time: " + String.format("%.2f", loadTimeMs) + " ms");

            traversal = new ESVTTraversal();
            return true;

        } catch (IOException e) {
            error("Failed to load ESVT file: " + e.getMessage());
            return false;
        }
    }

    /**
     * Helper to cast a ray through the loaded data.
     */
    private ESVTResult castRay(ESVTRay ray) {
        return traversal.castRay(ray, data.nodes(), data.contours(), data.farPointers(), 0);
    }

    private void runWarmup() {
        progress("Running " + config.warmupIterations + " warmup iterations...");

        var warmupRays = generateRandomRays(1000);
        var startTime = System.nanoTime();

        for (int i = 0; i < config.warmupIterations; i++) {
            for (var ray : warmupRays) {
                castRay(ray);
            }
        }

        var warmupTimeMs = (System.nanoTime() - startTime) / 1_000_000.0;
        var totalRays = config.warmupIterations * warmupRays.size();
        var raysPerSecond = totalRays / (warmupTimeMs / 1000.0);

        progress("Warmup complete: " + String.format("%.2f", warmupTimeMs) + " ms");
        progress("Warmup throughput: " + formatNumber(raysPerSecond) + " rays/sec");
    }

    private BenchmarkResult runPrimaryBenchmark() {
        var benchmarkResult = new BenchmarkResult("Primary");
        var rays = generateRandomRays(config.numRays);

        progress("Benchmarking with " + config.numRays + " rays x " + config.benchmarkIterations + " iterations");

        var times = new ArrayList<Long>();
        var totalHits = 0;
        var totalMisses = 0;

        for (int iter = 0; iter < config.benchmarkIterations; iter++) {
            var startTime = System.nanoTime();
            int hits = 0;
            int misses = 0;

            for (var ray : rays) {
                var castResult = castRay(ray);
                if (castResult.hit) {
                    hits++;
                } else {
                    misses++;
                }
            }

            var elapsed = System.nanoTime() - startTime;
            times.add(elapsed);
            totalHits += hits;
            totalMisses += misses;
        }

        benchmarkResult.totalRays = (long) config.numRays * config.benchmarkIterations;
        benchmarkResult.totalHits = totalHits;
        benchmarkResult.totalMisses = totalMisses;
        benchmarkResult.computeStatistics(times);

        return benchmarkResult;
    }

    private BenchmarkResult runRandomRayBenchmark() {
        var benchmarkResult = new BenchmarkResult("Random");
        progress("Random ray test...");

        var times = new ArrayList<Long>();
        var totalHits = 0;
        var totalMisses = 0;
        var iterations = Math.min(100, config.benchmarkIterations);

        for (int iter = 0; iter < iterations; iter++) {
            // Generate fresh random rays each iteration
            var rays = generateRandomRays(config.numRays);

            var startTime = System.nanoTime();
            int hits = 0;

            for (var ray : rays) {
                var result = castRay(ray);
                if (result.hit) hits++;
            }

            var elapsed = System.nanoTime() - startTime;
            times.add(elapsed);
            totalHits += hits;
            totalMisses += (config.numRays - hits);
        }

        benchmarkResult.totalRays = (long) config.numRays * iterations;
        benchmarkResult.totalHits = totalHits;
        benchmarkResult.totalMisses = totalMisses;
        benchmarkResult.computeStatistics(times);

        return benchmarkResult;
    }

    private BenchmarkResult runCoherentRayBenchmark() {
        var benchmarkResult = new BenchmarkResult("Coherent");
        progress("Coherent ray test...");

        var times = new ArrayList<Long>();
        var totalHits = 0;
        var totalMisses = 0;
        var iterations = Math.min(100, config.benchmarkIterations);

        // Generate coherent ray bundles (rays from similar origins, similar directions)
        for (int iter = 0; iter < iterations; iter++) {
            var rays = generateCoherentRays(config.numRays);

            var startTime = System.nanoTime();
            int hits = 0;

            for (var ray : rays) {
                var result = castRay(ray);
                if (result.hit) hits++;
            }

            var elapsed = System.nanoTime() - startTime;
            times.add(elapsed);
            totalHits += hits;
            totalMisses += (config.numRays - hits);
        }

        benchmarkResult.totalRays = (long) config.numRays * iterations;
        benchmarkResult.totalHits = totalHits;
        benchmarkResult.totalMisses = totalMisses;
        benchmarkResult.computeStatistics(times);

        return benchmarkResult;
    }

    private BenchmarkResult runWorstCaseBenchmark() {
        var benchmarkResult = new BenchmarkResult("WorstCase");
        progress("Worst case ray test...");

        var times = new ArrayList<Long>();
        var totalHits = 0;
        var totalMisses = 0;
        var iterations = Math.min(50, config.benchmarkIterations);

        // Generate rays that traverse maximum depth (diagonal through volume)
        for (int iter = 0; iter < iterations; iter++) {
            var rays = generateWorstCaseRays(config.numRays);

            var startTime = System.nanoTime();
            int hits = 0;

            for (var ray : rays) {
                var result = castRay(ray);
                if (result.hit) hits++;
            }

            var elapsed = System.nanoTime() - startTime;
            times.add(elapsed);
            totalHits += hits;
            totalMisses += (config.numRays - hits);
        }

        benchmarkResult.totalRays = (long) config.numRays * iterations;
        benchmarkResult.totalHits = totalHits;
        benchmarkResult.totalMisses = totalMisses;
        benchmarkResult.computeStatistics(times);

        return benchmarkResult;
    }

    private void reportBenchmarkResult(String name, BenchmarkResult result) {
        out.println();
        out.println("  " + name + " Results:");
        out.println("  ─────────────────────────────────────");
        out.println("    Total rays:     " + formatNumber(result.totalRays));
        out.println("    Hits/Misses:    " + result.totalHits + " / " + result.totalMisses);
        out.println("    Hit rate:       " + String.format("%.1f%%", result.hitRate * 100));
        out.println();
        out.println("    Rays/second:    " + formatNumber(result.raysPerSecond));
        out.println("    Avg time/ray:   " + String.format("%.2f", result.avgTimePerRayNs) + " ns");
        out.println("    Min time:       " + String.format("%.2f", result.minTimeMs) + " ms");
        out.println("    Max time:       " + String.format("%.2f", result.maxTimeMs) + " ms");
        out.println("    Std dev:        " + String.format("%.2f", result.stdDevMs) + " ms");
    }

    private PerformanceScore analyzePerformance() {
        var score = new PerformanceScore();

        // Use primary benchmark result
        var primary = results.stream()
                .filter(r -> r.name.equals("Primary"))
                .findFirst()
                .orElse(results.get(0));

        // Calculate score based on throughput
        // Baseline: 10M rays/sec = 100 score
        score.throughputScore = (float) Math.min(100, (primary.raysPerSecond / 10_000_000.0) * 100);

        // Latency score based on average time per ray
        // Baseline: 100ns = 100 score
        score.latencyScore = (float) Math.min(100, (100.0 / primary.avgTimePerRayNs) * 100);

        // Consistency score based on standard deviation
        // Lower std dev = higher score
        var cvPercent = primary.avgTimeMs > 0 ? (primary.stdDevMs / primary.avgTimeMs) * 100 : 100;
        score.consistencyScore = (float) Math.max(0, 100 - cvPercent * 10);

        // Hit rate score
        score.hitRateScore = (float) (primary.hitRate * 100);

        // Overall score (weighted average)
        score.overallScore = score.throughputScore * 0.4f +
                            score.latencyScore * 0.3f +
                            score.consistencyScore * 0.2f +
                            score.hitRateScore * 0.1f;

        // Grade
        score.grade = calculateGrade(score.overallScore);

        return score;
    }

    private void reportPerformanceScore(PerformanceScore score) {
        out.println("  Performance Scores:");
        out.println("  ─────────────────────────────────────");
        out.println("    Throughput:     " + String.format("%.1f", score.throughputScore) + " / 100");
        out.println("    Latency:        " + String.format("%.1f", score.latencyScore) + " / 100");
        out.println("    Consistency:    " + String.format("%.1f", score.consistencyScore) + " / 100");
        out.println("    Hit Rate:       " + String.format("%.1f", score.hitRateScore) + " / 100");
        out.println("  ─────────────────────────────────────");
        out.println("    Overall:        " + String.format("%.1f", score.overallScore) + " / 100");
        out.println("    Grade:          " + score.grade);
    }

    private void saveCSVReport(Path path) throws IOException {
        try (var writer = new PrintWriter(Files.newBufferedWriter(path))) {
            // Header
            writer.println("benchmark,total_rays,hits,misses,hit_rate,rays_per_sec,avg_ns,min_ms,max_ms,std_ms");

            // Data rows
            for (var result : results) {
                writer.printf("%s,%d,%d,%d,%.4f,%.0f,%.2f,%.2f,%.2f,%.2f%n",
                        result.name,
                        result.totalRays,
                        result.totalHits,
                        result.totalMisses,
                        result.hitRate,
                        result.raysPerSecond,
                        result.avgTimePerRayNs,
                        result.minTimeMs,
                        result.maxTimeMs,
                        result.stdDevMs);
            }
        }

        progress("Report saved to: " + path);
    }

    // Ray generation helpers

    private List<ESVTRay> generateRandomRays(int count) {
        var rays = new ArrayList<ESVTRay>(count);
        var rng = ThreadLocalRandom.current();

        for (int i = 0; i < count; i++) {
            // Random origin outside the unit cube
            var ox = rng.nextFloat() * 4 - 2;  // [-2, 2]
            var oy = rng.nextFloat() * 4 - 2;
            var oz = rng.nextFloat() * 4 - 2;

            // Random direction toward center
            var dx = 0.5f - ox + (rng.nextFloat() - 0.5f) * 0.5f;
            var dy = 0.5f - oy + (rng.nextFloat() - 0.5f) * 0.5f;
            var dz = 0.5f - oz + (rng.nextFloat() - 0.5f) * 0.5f;

            // Normalize direction
            var len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (len > 0) {
                dx /= len;
                dy /= len;
                dz /= len;
            }

            rays.add(new ESVTRay(
                    new Point3f(ox, oy, oz),
                    new Vector3f(dx, dy, dz)
            ));
        }

        return rays;
    }

    private List<ESVTRay> generateCoherentRays(int count) {
        var rays = new ArrayList<ESVTRay>(count);
        var rng = ThreadLocalRandom.current();

        // Base origin and direction
        var baseOx = rng.nextFloat() * 2 - 1;
        var baseOy = rng.nextFloat() * 2 - 1;
        var baseOz = -2.0f;  // All rays from front

        var baseDx = 0.0f;
        var baseDy = 0.0f;
        var baseDz = 1.0f;

        for (int i = 0; i < count; i++) {
            // Small perturbation for coherent bundle
            var ox = baseOx + (rng.nextFloat() - 0.5f) * 0.1f;
            var oy = baseOy + (rng.nextFloat() - 0.5f) * 0.1f;
            var oz = baseOz;

            var dx = baseDx + (rng.nextFloat() - 0.5f) * 0.05f;
            var dy = baseDy + (rng.nextFloat() - 0.5f) * 0.05f;
            var dz = baseDz;

            // Normalize
            var len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (len > 0) {
                dx /= len;
                dy /= len;
                dz /= len;
            }

            rays.add(new ESVTRay(
                    new Point3f(ox, oy, oz),
                    new Vector3f(dx, dy, dz)
            ));
        }

        return rays;
    }

    private List<ESVTRay> generateWorstCaseRays(int count) {
        var rays = new ArrayList<ESVTRay>(count);
        var rng = ThreadLocalRandom.current();

        for (int i = 0; i < count; i++) {
            // Diagonal rays that traverse entire volume
            var corner = rng.nextInt(8);
            float ox, oy, oz, dx, dy, dz;

            switch (corner) {
                case 0 -> { ox = -0.1f; oy = -0.1f; oz = -0.1f; dx = 1; dy = 1; dz = 1; }
                case 1 -> { ox = 1.1f; oy = -0.1f; oz = -0.1f; dx = -1; dy = 1; dz = 1; }
                case 2 -> { ox = -0.1f; oy = 1.1f; oz = -0.1f; dx = 1; dy = -1; dz = 1; }
                case 3 -> { ox = 1.1f; oy = 1.1f; oz = -0.1f; dx = -1; dy = -1; dz = 1; }
                case 4 -> { ox = -0.1f; oy = -0.1f; oz = 1.1f; dx = 1; dy = 1; dz = -1; }
                case 5 -> { ox = 1.1f; oy = -0.1f; oz = 1.1f; dx = -1; dy = 1; dz = -1; }
                case 6 -> { ox = -0.1f; oy = 1.1f; oz = 1.1f; dx = 1; dy = -1; dz = -1; }
                default -> { ox = 1.1f; oy = 1.1f; oz = 1.1f; dx = -1; dy = -1; dz = -1; }
            }

            // Normalize
            var len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
            dx /= len;
            dy /= len;
            dz /= len;

            rays.add(new ESVTRay(
                    new Point3f(ox, oy, oz),
                    new Vector3f(dx, dy, dz)
            ));
        }

        return rays;
    }

    private String calculateGrade(float score) {
        if (score >= 90) return "A+";
        if (score >= 85) return "A";
        if (score >= 80) return "A-";
        if (score >= 75) return "B+";
        if (score >= 70) return "B";
        if (score >= 65) return "B-";
        if (score >= 60) return "C+";
        if (score >= 55) return "C";
        if (score >= 50) return "C-";
        if (score >= 45) return "D+";
        if (score >= 40) return "D";
        return "F";
    }

    // Helper classes

    private static class BenchmarkResult {
        String name;
        long totalRays;
        long totalHits;
        long totalMisses;
        double hitRate;
        double raysPerSecond;
        double avgTimePerRayNs;
        double avgTimeMs;
        double minTimeMs;
        double maxTimeMs;
        double stdDevMs;

        BenchmarkResult(String name) {
            this.name = name;
        }

        void computeStatistics(List<Long> timesNanos) {
            if (timesNanos.isEmpty()) return;

            long totalTimeNs = 0;
            long minTimeNs = Long.MAX_VALUE;
            long maxTimeNs = 0;

            for (var time : timesNanos) {
                totalTimeNs += time;
                minTimeNs = Math.min(minTimeNs, time);
                maxTimeNs = Math.max(maxTimeNs, time);
            }

            avgTimeMs = totalTimeNs / (timesNanos.size() * 1_000_000.0);
            minTimeMs = minTimeNs / 1_000_000.0;
            maxTimeMs = maxTimeNs / 1_000_000.0;

            // Standard deviation
            double sumSquaredDiff = 0;
            double avgNs = totalTimeNs / (double) timesNanos.size();
            for (var time : timesNanos) {
                double diff = time - avgNs;
                sumSquaredDiff += diff * diff;
            }
            stdDevMs = Math.sqrt(sumSquaredDiff / timesNanos.size()) / 1_000_000.0;

            // Compute rates
            hitRate = totalRays > 0 ? (double) totalHits / totalRays : 0;
            raysPerSecond = totalTimeNs > 0 ? (totalRays * 1_000_000_000.0) / totalTimeNs : 0;
            avgTimePerRayNs = totalRays > 0 ? (double) totalTimeNs / totalRays : 0;
        }
    }

    private static class PerformanceScore {
        float throughputScore;
        float latencyScore;
        float consistencyScore;
        float hitRateScore;
        float overallScore;
        String grade;
    }

    // Formatting helpers

    private void printHeader() {
        if (config.quiet) return;
        out.println();
        out.println("╔══════════════════════════════════════════════════════════════╗");
        out.println("║                  ESVT Benchmark Mode                         ║");
        out.println("╚══════════════════════════════════════════════════════════════╝");
        out.println();
    }

    private void printFooter(boolean success) {
        if (config.quiet) return;
        out.println();
        if (success) {
            out.println("╔══════════════════════════════════════════════════════════════╗");
            out.println("║                  Benchmark Complete                          ║");
            out.println("╚══════════════════════════════════════════════════════════════╝");
        } else {
            out.println("╔══════════════════════════════════════════════════════════════╗");
            out.println("║                   Benchmark Failed                           ║");
            out.println("╚══════════════════════════════════════════════════════════════╝");
        }
        out.println();
    }

    private void phase(String name) {
        if (config.quiet) return;
        out.println();
        out.println("► " + name);
        out.println("─".repeat(62));
    }

    private void progress(String message) {
        if (config.quiet) return;
        out.println("  " + message);
    }

    private void error(String message) {
        out.println("  ✗ ERROR: " + message);
    }

    private static String formatNumber(double n) {
        if (n >= 1_000_000_000) {
            return String.format("%.1f B", n / 1_000_000_000);
        } else if (n >= 1_000_000) {
            return String.format("%.1f M", n / 1_000_000);
        } else if (n >= 1_000) {
            return String.format("%.1f K", n / 1_000);
        } else {
            return String.format("%.0f", n);
        }
    }
}
