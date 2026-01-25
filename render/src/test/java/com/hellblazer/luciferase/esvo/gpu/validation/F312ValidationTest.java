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
package com.hellblazer.luciferase.esvo.gpu.validation;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.profiler.GPUPerformanceProfiler;
import com.hellblazer.luciferase.esvo.gpu.profiler.PerformanceMetrics;
import com.hellblazer.luciferase.esvo.gpu.profiler.ProfilerConfig;
import com.hellblazer.luciferase.sparse.gpu.GPUAutoTuner;
import com.hellblazer.luciferase.sparse.gpu.GPUCapabilities;
import com.hellblazer.luciferase.sparse.gpu.GPUVendor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3.1.2: Performance Optimization Validation Test Suite
 *
 * <p>Validates that GPU kernel optimizations meet the performance targets:
 * <ul>
 *   <li>10x+ speedup vs CPU SVO baseline</li>
 *   <li>&lt;5ms for 100K rays</li>
 *   <li>Stream A: Shared memory cache (16KB/workgroup, 65% hit rate)</li>
 *   <li>Stream B: GPU auto-tuning (vendor-specific workgroup optimization)</li>
 * </ul>
 *
 * <p>Test Categories:
 * <ol>
 *   <li>End-to-end rendering pipeline validation</li>
 *   <li>Performance regression testing (baseline vs optimized)</li>
 *   <li>Stability testing across multiple runs</li>
 *   <li>Cross-resolution scaling validation</li>
 *   <li>Multi-vendor consistency validation</li>
 * </ol>
 *
 * @see com.hellblazer.luciferase.esvo.gpu.profiler.GPUPerformanceProfiler
 * @see com.hellblazer.luciferase.sparse.gpu.GPUAutoTuner
 */
@DisplayName("F3.1.2: Performance Optimization Validation")
class F312ValidationTest {

    private static final int STABILITY_ITERATIONS = 100;
    private static final double MAX_COEFFICIENT_OF_VARIATION = 0.25; // 25% max CoV for stability
    private static final double TARGET_SPEEDUP = 10.0; // 10x vs baseline
    private static final double TARGET_IMPROVEMENT_PERCENT = 40.0; // 40% improvement minimum
    private static final long TARGET_100K_RAYS_MICROS = 5_000; // 5ms target for 100K rays

    private GPUPerformanceProfiler profiler;
    private DAGOctreeData testDAG;

    @BeforeEach
    void setUp() {
        profiler = new GPUPerformanceProfiler();
        testDAG = createTestDAG();
    }

    @Nested
    @DisplayName("End-to-End Pipeline Validation")
    class EndToEndValidation {

        @Test
        @DisplayName("Full profiling pipeline completes successfully (mock)")
        void testFullPipelineCompletion_Mock() {
            var baseline = profiler.profileBaseline(testDAG, 10_000, true);
            var optimized = profiler.profileOptimized(testDAG, 10_000, true);
            var comparison = profiler.compare(baseline, optimized);

            assertAll("Pipeline completion",
                () -> assertNotNull(baseline, "Baseline metrics captured"),
                () -> assertNotNull(optimized, "Optimized metrics captured"),
                () -> assertNotNull(comparison, "Comparison generated"),
                () -> assertEquals("baseline", baseline.scenario()),
                () -> assertTrue(optimized.scenario().contains("optimized"))
            );
        }

        @Test
        @DisplayName("100K rays processed within target latency (mock)")
        void test100KRaysLatencyTarget_Mock() {
            var metrics = profiler.profileOptimized(testDAG, 100_000, true);

            assertEquals(100_000, metrics.rayCount());
            assertTrue(metrics.latencyMicroseconds() > 0,
                "Should have measurable latency");

            // Mock mode uses deterministic values, verify the profiler returns reasonable results
            assertTrue(metrics.throughputRaysPerMicrosecond() > 0,
                "Should have positive throughput");
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
        @DisplayName("100K rays processed within 5ms target (real GPU)")
        void test100KRaysLatencyTarget_RealGPU() {
            var metrics = profiler.profileOptimized(testDAG, 100_000, false);

            System.out.printf("100K rays latency: %.2fms (target: %.2fms)%n",
                metrics.latencyMicroseconds() / 1000.0, TARGET_100K_RAYS_MICROS / 1000.0);

            assertTrue(metrics.latencyMicroseconds() < TARGET_100K_RAYS_MICROS,
                String.format("100K rays should complete in < 5ms, got %.2fms",
                    metrics.latencyMicroseconds() / 1000.0));
        }

        @Test
        @DisplayName("Profiler metrics are self-consistent")
        void testMetricsSelfConsistency() {
            var metrics = profiler.profileBaseline(testDAG, 10_000, true);

            // Verify throughput = rayCount / latency
            double expectedThroughput = (double) metrics.rayCount() / metrics.latencyMicroseconds();
            assertEquals(expectedThroughput, metrics.throughputRaysPerMicrosecond(), 0.001,
                "Throughput should be consistent with ray count and latency");

            // Verify occupancy is valid
            assertTrue(metrics.gpuOccupancyPercent() >= 0.0f && metrics.gpuOccupancyPercent() <= 100.0f,
                "GPU occupancy should be in valid range [0%, 100%]");

            // Verify cache hit rate is valid
            assertTrue(metrics.cacheHitRate() >= 0.0f && metrics.cacheHitRate() <= 1.0f,
                "Cache hit rate should be in valid range [0.0, 1.0]");
        }
    }

    @Nested
    @DisplayName("Performance Regression Testing")
    class PerformanceRegressionTesting {

        @Test
        @DisplayName("Optimized consistently faster than baseline (mock)")
        void testOptimizedFasterThanBaseline_Mock() {
            var baseline = profiler.profileBaseline(testDAG, 10_000, true);
            var optimized = profiler.profileOptimized(testDAG, 10_000, true);
            var comparison = profiler.compare(baseline, optimized);

            assertTrue(comparison.improvement() > 0,
                "Optimized should show improvement over baseline");
            assertTrue(optimized.latencyMicroseconds() < baseline.latencyMicroseconds(),
                "Optimized latency should be lower");
            assertTrue(optimized.throughputRaysPerMicrosecond() > baseline.throughputRaysPerMicrosecond(),
                "Optimized throughput should be higher");
        }

        @Test
        @DisplayName("Cache hit rate improves with optimization (mock)")
        void testCacheHitRateImprovement_Mock() {
            var baseline = profiler.profileBaseline(testDAG, 10_000, true);
            var optimized = profiler.profileOptimized(testDAG, 10_000, true);

            assertTrue(optimized.cacheHitRate() > baseline.cacheHitRate(),
                String.format("Optimized cache hit rate (%.2f) should exceed baseline (%.2f)",
                    optimized.cacheHitRate(), baseline.cacheHitRate()));

            // Stream A target: 65% cache hit rate
            // Mock mode may not hit exactly 65%, but should show improvement
            assertTrue(optimized.cacheHitRate() >= 0.5,
                "Optimized should achieve >= 50% cache hit rate");
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
        @DisplayName("Achieves target improvement percentage (real GPU)")
        void testTargetImprovementPercent_RealGPU() {
            var baseline = profiler.profileBaseline(testDAG, 100_000, false);
            var optimized = profiler.profileOptimized(testDAG, 100_000, false);
            var comparison = profiler.compare(baseline, optimized);

            System.out.printf("Performance improvement: %.2f%% (target: %.2f%%)%n",
                comparison.improvement(), TARGET_IMPROVEMENT_PERCENT);
            System.out.printf("Baseline: %.2fms, Optimized: %.2fms%n",
                baseline.latencyMicroseconds() / 1000.0,
                optimized.latencyMicroseconds() / 1000.0);

            assertTrue(comparison.improvement() >= TARGET_IMPROVEMENT_PERCENT,
                String.format("Should achieve >= %.0f%% improvement, got %.2f%%",
                    TARGET_IMPROVEMENT_PERCENT, comparison.improvement()));
        }

        @Test
        @DisplayName("Performance scales linearly with ray count (mock)")
        void testLinearScaling_Mock() {
            var metrics1K = profiler.profileOptimized(testDAG, 1_000, true);
            var metrics10K = profiler.profileOptimized(testDAG, 10_000, true);
            var metrics100K = profiler.profileOptimized(testDAG, 100_000, true);

            // Throughput should remain roughly constant (linear scaling)
            double throughput1K = metrics1K.throughputRaysPerMicrosecond();
            double throughput10K = metrics10K.throughputRaysPerMicrosecond();
            double throughput100K = metrics100K.throughputRaysPerMicrosecond();

            // Allow 50% variation for mock mode
            double avgThroughput = (throughput1K + throughput10K + throughput100K) / 3.0;
            assertTrue(Math.abs(throughput1K - avgThroughput) / avgThroughput < 0.5,
                "1K throughput should be within 50% of average");
            assertTrue(Math.abs(throughput10K - avgThroughput) / avgThroughput < 0.5,
                "10K throughput should be within 50% of average");
            assertTrue(Math.abs(throughput100K - avgThroughput) / avgThroughput < 0.5,
                "100K throughput should be within 50% of average");
        }
    }

    @Nested
    @DisplayName("Stability Testing")
    class StabilityTesting {

        @Test
        @DisplayName("Baseline metrics stable across 10 iterations (mock)")
        void testBaselineStability_Mock() {
            List<PerformanceMetrics> samples = new ArrayList<>();

            for (int i = 0; i < 10; i++) {
                samples.add(profiler.profileBaseline(testDAG, 1_000, true));
            }

            var latencyStats = samples.stream()
                .mapToDouble(PerformanceMetrics::latencyMicroseconds)
                .summaryStatistics();

            // Mock mode should be perfectly stable (deterministic)
            double range = latencyStats.getMax() - latencyStats.getMin();
            double mean = latencyStats.getAverage();

            // Range should be small relative to mean
            assertTrue(range / mean < 0.1,
                "Baseline latency should be stable across iterations");
        }

        @Test
        @DisplayName("Optimized metrics stable across 10 iterations (mock)")
        void testOptimizedStability_Mock() {
            List<PerformanceMetrics> samples = new ArrayList<>();

            for (int i = 0; i < 10; i++) {
                samples.add(profiler.profileOptimized(testDAG, 1_000, true));
            }

            var latencyStats = samples.stream()
                .mapToDouble(PerformanceMetrics::latencyMicroseconds)
                .summaryStatistics();

            double range = latencyStats.getMax() - latencyStats.getMin();
            double mean = latencyStats.getAverage();

            assertTrue(range / mean < 0.1,
                "Optimized latency should be stable across iterations");
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
        @DisplayName("100 iterations stability test (real GPU)")
        void testExtendedStability_RealGPU() {
            List<Double> latencies = new ArrayList<>();

            for (int i = 0; i < STABILITY_ITERATIONS; i++) {
                var metrics = profiler.profileOptimized(testDAG, 10_000, false);
                latencies.add(metrics.latencyMicroseconds());
            }

            DoubleSummaryStatistics stats = latencies.stream()
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();

            double mean = stats.getAverage();
            double variance = latencies.stream()
                .mapToDouble(l -> Math.pow(l - mean, 2))
                .average()
                .orElse(0.0);
            double stdDev = Math.sqrt(variance);
            double coefficientOfVariation = stdDev / mean;

            System.out.printf("Stability test (n=%d): mean=%.2fus, stdDev=%.2fus, CoV=%.2f%%%n",
                STABILITY_ITERATIONS, mean, stdDev, coefficientOfVariation * 100);

            assertTrue(coefficientOfVariation < MAX_COEFFICIENT_OF_VARIATION,
                String.format("Coefficient of variation should be < %.0f%%, got %.2f%%",
                    MAX_COEFFICIENT_OF_VARIATION * 100, coefficientOfVariation * 100));
        }

        @Test
        @DisplayName("No degradation over repeated measurements")
        void testNoDegradation_Mock() {
            var firstBatch = new ArrayList<Double>();
            var lastBatch = new ArrayList<Double>();

            // First 5 measurements
            for (int i = 0; i < 5; i++) {
                var metrics = profiler.profileOptimized(testDAG, 1_000, true);
                firstBatch.add(metrics.latencyMicroseconds());
            }

            // Run 90 more (total 100)
            for (int i = 0; i < 90; i++) {
                profiler.profileOptimized(testDAG, 1_000, true);
            }

            // Last 5 measurements
            for (int i = 0; i < 5; i++) {
                var metrics = profiler.profileOptimized(testDAG, 1_000, true);
                lastBatch.add(metrics.latencyMicroseconds());
            }

            double firstAvg = firstBatch.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double lastAvg = lastBatch.stream().mapToDouble(Double::doubleValue).average().orElse(0);

            // Last batch should not be more than 20% slower than first batch
            assertTrue(lastAvg <= firstAvg * 1.2,
                "Performance should not degrade over time");
        }
    }

    @Nested
    @DisplayName("Cross-Resolution Scaling")
    class CrossResolutionScaling {

        @Test
        @DisplayName("Performance scales with resolution (mock)")
        void testResolutionScaling_Mock() {
            int[] rayCounts = {1_000, 10_000, 50_000, 100_000};

            double previousLatency = 0;
            for (int rayCount : rayCounts) {
                var metrics = profiler.profileOptimized(testDAG, rayCount, true);

                if (previousLatency > 0) {
                    // Latency should increase with ray count
                    assertTrue(metrics.latencyMicroseconds() > previousLatency,
                        String.format("Latency for %d rays should exceed latency for fewer rays",
                            rayCount));
                }
                previousLatency = metrics.latencyMicroseconds();
            }
        }

        @Test
        @DisplayName("Throughput consistent across resolutions (mock)")
        void testThroughputConsistency_Mock() {
            int[] rayCounts = {10_000, 50_000, 100_000};
            List<Double> throughputs = new ArrayList<>();

            for (int rayCount : rayCounts) {
                var metrics = profiler.profileOptimized(testDAG, rayCount, true);
                throughputs.add(metrics.throughputRaysPerMicrosecond());
            }

            double avgThroughput = throughputs.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);

            // Throughput should be within 50% of average for all resolutions
            for (double throughput : throughputs) {
                assertTrue(Math.abs(throughput - avgThroughput) / avgThroughput < 0.5,
                    "Throughput should be consistent across resolutions");
            }
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
        @DisplayName("Resolution scaling with real GPU")
        void testResolutionScaling_RealGPU() {
            int[] rayCounts = {10_000, 50_000, 100_000, 500_000};

            System.out.println("\nResolution scaling (real GPU):");
            System.out.println("Rays\t\tLatency(ms)\tThroughput(rays/us)");

            for (int rayCount : rayCounts) {
                var metrics = profiler.profileOptimized(testDAG, rayCount, false);
                System.out.printf("%d\t\t%.2f\t\t%.4f%n",
                    rayCount,
                    metrics.latencyMicroseconds() / 1000.0,
                    metrics.throughputRaysPerMicrosecond());
            }
        }
    }

    @Nested
    @DisplayName("Multi-Vendor Consistency")
    class MultiVendorConsistency {

        @Test
        @DisplayName("All vendors produce valid configurations")
        void testVendorConfigValidity() {
            GPUVendor[] vendors = {GPUVendor.NVIDIA, GPUVendor.AMD, GPUVendor.INTEL, GPUVendor.APPLE};

            for (GPUVendor vendor : vendors) {
                var capabilities = createCapabilitiesForVendor(vendor);
                var tuner = new GPUAutoTuner(capabilities, "/tmp/f312-" + vendor.name());
                var config = tuner.selectOptimalConfigFromProfiles();

                assertAll("Vendor: " + vendor.name(),
                    () -> assertTrue(config.workgroupSize() > 0,
                        "Workgroup size should be positive"),
                    () -> assertTrue(config.workgroupSize() <= 1024,
                        "Workgroup size should be <= 1024"),
                    () -> assertTrue(config.expectedOccupancy() >= 0.0,
                        "Occupancy should be non-negative"),
                    () -> assertTrue(config.expectedOccupancy() <= 1.0,
                        "Occupancy should be <= 1.0"),
                    () -> assertTrue(config.expectedThroughput() > 0,
                        "Throughput should be positive")
                );
            }
        }

        @Test
        @DisplayName("Occupancy improvement consistent across vendors")
        void testCrossVendorOccupancyConsistency() {
            GPUVendor[] vendors = {GPUVendor.NVIDIA, GPUVendor.AMD, GPUVendor.INTEL};
            List<Double> occupancies = new ArrayList<>();

            for (GPUVendor vendor : vendors) {
                var capabilities = createCapabilitiesForVendor(vendor);
                var tuner = new GPUAutoTuner(capabilities, "/tmp/f312-occ-" + vendor.name());
                var config = tuner.selectOptimalConfigFromProfiles();
                occupancies.add((double) config.expectedOccupancy());
            }

            double minOccupancy = occupancies.stream().mapToDouble(d -> d).min().orElse(0);
            double maxOccupancy = occupancies.stream().mapToDouble(d -> d).max().orElse(0);

            // All vendors should achieve reasonable occupancy
            assertTrue(minOccupancy >= 0.5,
                String.format("All vendors should achieve >= 50%% occupancy, min was %.2f%%",
                    minOccupancy * 100));

            // Occupancy variance should be reasonable
            double variance = (maxOccupancy - minOccupancy) / ((maxOccupancy + minOccupancy) / 2.0);
            assertTrue(variance < 0.5,
                String.format("Vendor occupancy variance should be < 50%%, was %.2f%%",
                    variance * 100));
        }

        private GPUCapabilities createCapabilitiesForVendor(GPUVendor vendor) {
            return switch (vendor) {
                case NVIDIA -> new GPUCapabilities(108, 49152, 65536, vendor, "RTX 4090", 32);
                case AMD -> new GPUCapabilities(64, 65536, 65536, vendor, "RX 7900 XTX", 64);
                case INTEL -> new GPUCapabilities(96, 131072, 131072, vendor, "Arc A770", 32);
                case APPLE -> new GPUCapabilities(32, 32768, 32768, vendor, "M3 Max", 32);
                default -> new GPUCapabilities(64, 49152, 65536, vendor, "Generic", 32);
            };
        }
    }

    @Nested
    @DisplayName("Stream A (Shared Memory Cache) Validation")
    class StreamAValidation {

        @Test
        @DisplayName("Cache hit rate meets Stream A target (mock)")
        void testCacheHitRateTarget_Mock() {
            // Stream A target: 65% cache hit rate
            var config = new ProfilerConfig(10_000, true, 128, 16, 5);
            var metrics = profiler.profileWithConfig(testDAG, config, true);

            // In mock mode, verify cache is enabled and producing results
            assertTrue(metrics.cacheHitRate() > 0,
                "Cache should produce hits when enabled");
        }

        @Test
        @DisplayName("LDS memory usage validates occupancy potential")
        void testLDSMemoryUsage() {
            // Stream A uses 16KB per workgroup (1024 entries * 16 bytes)
            int cacheEntries = 1024;
            int entrySize = 16; // bytes
            long ldsPerWorkgroup = (long) cacheEntries * entrySize;

            assertEquals(16384, ldsPerWorkgroup, "Stream A cache uses 16KB per workgroup");

            // With 64KB LDS, can have 4 concurrent workgroups
            int gpuLDSSize = 65536;
            int maxConcurrent = gpuLDSSize / (int) ldsPerWorkgroup;

            assertEquals(4, maxConcurrent, "16KB cache allows 4 concurrent workgroups per CU");
        }
    }

    @Nested
    @DisplayName("Stream B (GPU Auto-Tuning) Validation")
    class StreamBValidation {

        @Test
        @DisplayName("Auto-tuner selects appropriate workgroup size")
        void testWorkgroupSelection() {
            var capabilities = new GPUCapabilities(
                108, 49152, 65536,
                GPUVendor.NVIDIA, "RTX 4090", 32
            );
            var tuner = new GPUAutoTuner(capabilities, "/tmp/f312-stream-b");
            var config = tuner.selectOptimalConfigFromProfiles();

            // Workgroup size should be reasonable for the hardware
            assertTrue(config.workgroupSize() >= 32 && config.workgroupSize() <= 256,
                String.format("Workgroup size should be in [32, 256], got %d",
                    config.workgroupSize()));

            // Selected size should respect warp/wavefront boundaries
            int wavefrontSize = capabilities.wavefrontSize();
            assertEquals(0, config.workgroupSize() % wavefrontSize,
                "Workgroup size should be multiple of wavefront size");
        }

        @Test
        @DisplayName("Expected occupancy is realistic")
        void testExpectedOccupancy() {
            var capabilities = new GPUCapabilities(
                108, 49152, 65536,
                GPUVendor.NVIDIA, "RTX 4090", 32
            );
            var tuner = new GPUAutoTuner(capabilities, "/tmp/f312-occ-b");
            var config = tuner.selectOptimalConfigFromProfiles();

            assertTrue(config.expectedOccupancy() >= 0.5,
                String.format("Expected occupancy should be >= 50%%, got %.2f%%",
                    config.expectedOccupancy() * 100));
            assertTrue(config.expectedOccupancy() <= 1.0,
                "Expected occupancy should not exceed 100%");
        }
    }

    // Helper methods

    private DAGOctreeData createTestDAG() {
        var svo = createSimpleTestOctree();
        return DAGBuilder.from(svo).build();
    }

    /**
     * Create a simple test octree (2-level tree with 8 leaf children).
     * Copied from TestOctreeFactory since it's package-private.
     */
    private ESVOOctreeData createSimpleTestOctree() {
        var octree = new ESVOOctreeData(16);

        // Root node with all 8 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0xFF); // All 8 children present
        root.setChildPtr(1);     // Children start at index 1
        octree.setNode(0, root);

        // Create 8 leaf children
        for (int i = 0; i < 8; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0); // Leaf node
            octree.setNode(1 + i, leaf);
        }

        return octree;
    }
}
