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

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * B4: Dynamic Tuning Engine
 *
 * Monitors GPU performance and adjusts workgroup configuration during runtime.
 * Features:
 * - Session-start auto-tuning with cached fallback
 * - Performance monitoring (track throughput over time)
 * - Threshold-based re-tuning when performance degrades
 * - Background tuning (doesn't block rendering)
 *
 * @author hal.hildebrand
 */
public class DynamicTuner {

    /**
     * Performance statistics snapshot
     */
    public record PerformanceStats(
        int sampleCount,
        double averageThroughput,
        double minThroughput,
        double maxThroughput,
        double recentThroughput
    ) {}

    private static final Logger log = LoggerFactory.getLogger(DynamicTuner.class);

    private static final int DEFAULT_WINDOW_SIZE = 100;
    private static final double DEFAULT_RETUNE_THRESHOLD = 0.20; // 20% drop
    private static final int MIN_SAMPLES_FOR_BASELINE = 10;

    private final GPUCapabilities capabilities;
    private final GPUAutoTuner autoTuner;
    private final TuningBenchmark.BenchmarkExecutor executor;
    private final ExecutorService backgroundExecutor;

    // Configuration
    private volatile int windowSize = DEFAULT_WINDOW_SIZE;
    private volatile double retuneThreshold = DEFAULT_RETUNE_THRESHOLD;

    // Current state
    private volatile WorkgroupConfig currentConfig;
    private volatile String currentBuildOptions;
    private final AtomicBoolean retuneTriggered = new AtomicBoolean(false);

    // Performance tracking - circular buffer
    private final double[] performanceSamples;
    private volatile int sampleIndex = 0;
    private volatile int sampleCount = 0;
    private volatile double baselineThroughput = 0.0;

    // Callbacks
    private volatile Consumer<GPUAutoTuner.AutoTuneResult> retuneCallback;

    /**
     * Create a dynamic tuner for a GPU device
     *
     * @param capabilities GPU hardware capabilities
     * @param cacheDirectory directory for caching tuning results
     * @param executor benchmark executor for tuning
     */
    public DynamicTuner(GPUCapabilities capabilities, String cacheDirectory,
                        TuningBenchmark.BenchmarkExecutor executor) {
        this.capabilities = capabilities;
        this.autoTuner = new GPUAutoTuner(capabilities, cacheDirectory);
        this.executor = executor;
        this.backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "DynamicTuner-Background");
            t.setDaemon(true);
            return t;
        });
        this.performanceSamples = new double[DEFAULT_WINDOW_SIZE * 2]; // Allow for resize
    }

    /**
     * Perform auto-tuning at session start
     *
     * @return tuning result, or empty if tuning failed
     */
    public Optional<GPUAutoTuner.AutoTuneResult> sessionStart() {
        log.info("Starting session auto-tune for {}", capabilities.model());

        try {
            var result = autoTuner.selectOptimalConfigByBenchmark(executor);
            currentConfig = result.config();
            currentBuildOptions = result.buildOptions();

            log.info("Session tuned: {} threads, depth {}, options: {}",
                    currentConfig.workgroupSize(),
                    currentConfig.maxTraversalDepth(),
                    currentBuildOptions);

            return Optional.of(result);
        } catch (Exception e) {
            log.error("Session auto-tune failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Perform auto-tuning with fallback to cached config
     *
     * @return tuning result (from benchmark, cache, or default)
     */
    public Optional<GPUAutoTuner.AutoTuneResult> sessionStartWithFallback() {
        // Load cache FIRST before attempting benchmark (benchmark may overwrite cache)
        var cachedConfig = autoTuner.loadFromCache();

        // Test if executor is working before full benchmark
        if (isExecutorWorking()) {
            // Executor works, do full benchmark
            var benchmarkResult = sessionStart();
            if (benchmarkResult.isPresent()) {
                return benchmarkResult;
            }
        }

        // Executor failed or benchmark failed, use cached config
        if (cachedConfig.isPresent()) {
            log.info("Using cached config as fallback: {} threads",
                    cachedConfig.get().workgroupSize());
            currentConfig = cachedConfig.get();
            currentBuildOptions = autoTuner.generateBuildOptions(currentConfig);
            return Optional.of(new GPUAutoTuner.AutoTuneResult(currentConfig, currentBuildOptions));
        }

        // Use default config
        log.warn("No cache available, using default config");
        currentConfig = WorkgroupConfig.forDevice(capabilities);
        currentBuildOptions = autoTuner.getDefaultBuildOptions();
        return Optional.of(new GPUAutoTuner.AutoTuneResult(currentConfig, currentBuildOptions));
    }

    /**
     * Test if the benchmark executor is working
     */
    private boolean isExecutorWorking() {
        try {
            // Quick test with minimal config
            var testConfig = new WorkgroupConfig(32, 16, 0.5f, 1.0f, "test");
            executor.execute(testConfig, 10);
            return true;
        } catch (Exception e) {
            log.debug("Executor test failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Record frame performance for monitoring.
     *
     * <p><b>Thread Safety</b>: This method is NOT thread-safe. It should only
     * be called from the render thread. Concurrent calls may corrupt the
     * performance sample buffer.
     *
     * @param throughputRaysPerMicrosecond measured throughput
     */
    public void recordFramePerformance(double throughputRaysPerMicrosecond) {
        // Circular buffer insert
        int idx = sampleIndex;
        performanceSamples[idx] = throughputRaysPerMicrosecond;
        sampleIndex = (idx + 1) % windowSize;
        if (sampleCount < windowSize) {
            sampleCount++;
        }

        // Update baseline if we have enough samples
        if (sampleCount >= MIN_SAMPLES_FOR_BASELINE && baselineThroughput == 0.0) {
            baselineThroughput = calculateAverage();
            log.debug("Baseline throughput established: {} rays/µs", String.format("%.2f", baselineThroughput));
        }

        // Check for performance degradation
        if (baselineThroughput > 0.0 && sampleCount >= MIN_SAMPLES_FOR_BASELINE) {
            double current = calculateRecentAverage();
            double drop = (baselineThroughput - current) / baselineThroughput;

            if (drop >= retuneThreshold && retuneTriggered.compareAndSet(false, true)) {
                log.warn("Performance drop detected: {}% (threshold: {}%)",
                        String.format("%.1f", drop * 100), String.format("%.1f", retuneThreshold * 100));

                // Trigger re-tune callback if set
                if (retuneCallback != null) {
                    backgroundRetune(retuneCallback);
                }
            }
        }
    }

    /**
     * Get performance statistics
     *
     * @return current performance stats
     */
    public PerformanceStats getPerformanceStats() {
        if (sampleCount == 0) {
            return new PerformanceStats(0, 0.0, 0.0, 0.0, 0.0);
        }

        double sum = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < sampleCount; i++) {
            double v = performanceSamples[i];
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }

        double avg = sum / sampleCount;
        double recent = calculateRecentAverage();

        return new PerformanceStats(sampleCount, avg, min, max, recent);
    }

    /**
     * Start background re-tuning
     *
     * @param callback called when tuning completes
     */
    public void backgroundRetune(Consumer<GPUAutoTuner.AutoTuneResult> callback) {
        backgroundExecutor.submit(() -> {
            try {
                log.info("Starting background re-tune");
                var result = autoTuner.selectOptimalConfigByBenchmark(executor);

                // Update current config
                currentConfig = result.config();
                currentBuildOptions = result.buildOptions();

                // Reset baseline for new config
                baselineThroughput = 0.0;
                retuneTriggered.set(false);

                log.info("Background re-tune complete: {} threads, depth {}",
                        currentConfig.workgroupSize(), currentConfig.maxTraversalDepth());

                if (callback != null) {
                    callback.accept(result);
                }
            } catch (Exception e) {
                log.error("Background re-tune failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Check if re-tuning has been triggered
     */
    public boolean isRetuneTriggered() {
        return retuneTriggered.get();
    }

    /**
     * Set callback for automatic re-tuning
     */
    public void setRetuneCallback(Consumer<GPUAutoTuner.AutoTuneResult> callback) {
        this.retuneCallback = callback;
    }

    /**
     * Set re-tune threshold (percentage drop)
     */
    public void setRetuneThreshold(double threshold) {
        this.retuneThreshold = threshold;
    }

    /**
     * Set sliding window size for performance tracking
     */
    public void setWindowSize(int size) {
        this.windowSize = Math.min(size, performanceSamples.length);
    }

    /**
     * Get current workgroup configuration
     */
    public WorkgroupConfig getCurrentConfig() {
        return currentConfig;
    }

    /**
     * Get current build options
     */
    public String getCurrentBuildOptions() {
        return currentBuildOptions;
    }

    /**
     * Shutdown the tuner
     */
    public void shutdown() {
        backgroundExecutor.shutdownNow();
    }

    private double calculateAverage() {
        if (sampleCount == 0) return 0.0;

        double sum = 0.0;
        for (int i = 0; i < sampleCount; i++) {
            sum += performanceSamples[i];
        }
        return sum / sampleCount;
    }

    private double calculateRecentAverage() {
        if (sampleCount == 0) return 0.0;

        // Average of last 10 samples or all if fewer
        int recentCount = Math.min(10, sampleCount);
        double sum = 0.0;

        for (int i = 0; i < recentCount; i++) {
            int idx = (sampleIndex - 1 - i + windowSize) % windowSize;
            if (idx < 0) idx += windowSize;
            sum += performanceSamples[idx];
        }

        return sum / recentCount;
    }
}
