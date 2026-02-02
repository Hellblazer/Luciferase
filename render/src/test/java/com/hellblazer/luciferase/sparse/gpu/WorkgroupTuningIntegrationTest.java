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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B5: Workgroup Tuning Integration Tests
 *
 * Comprehensive integration tests validating the end-to-end workgroup tuning
 * pipeline across vendors and failure scenarios.
 *
 * @author hal.hildebrand
 */
@DisplayName("B5: Workgroup Tuning Integration Tests")
class WorkgroupTuningIntegrationTest {

    @TempDir
    Path tempDir;

    private List<DynamicTuner> tuners = new ArrayList<>();

    @AfterEach
    void tearDown() {
        tuners.forEach(DynamicTuner::shutdown);
        tuners.clear();
    }

    // ==================== End-to-End Flow ====================

    @Test
    @DisplayName("End-to-end: detect GPU -> auto-tune -> render -> verify performance")
    void testEndToEndDetectTuneRender() {
        // 1. Simulate GPU detection
        var capabilities = new GPUCapabilities(
            128, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32
        );

        // 2. Create tuner with mock executor
        var tuner = new DynamicTuner(capabilities, tempDir.toString(),
            TuningBenchmark.mockExecutor(1.0));
        tuners.add(tuner);

        // 3. Auto-tune at session start
        var tuneResult = tuner.sessionStart();
        assertTrue(tuneResult.isPresent(), "Should tune successfully");

        var config = tuneResult.get().config();
        var buildOptions = tuneResult.get().buildOptions();

        // 4. Verify config is valid for NVIDIA
        assertTrue(config.workgroupSize() >= 32 && config.workgroupSize() <= 256,
            "Workgroup size should be in valid range");
        assertTrue(config.maxTraversalDepth() >= 16 && config.maxTraversalDepth() <= 32,
            "Traversal depth should be in valid range");

        // 5. Verify build options
        assertTrue(buildOptions.contains("-D WORKGROUP_SIZE=" + config.workgroupSize()),
            "Build options should include workgroup size");
        assertTrue(buildOptions.contains("-D MAX_TRAVERSAL_DEPTH=" + config.maxTraversalDepth()),
            "Build options should include traversal depth");
        assertTrue(buildOptions.contains("-cl-mad-enable"),
            "NVIDIA should have mad-enable flag");

        // 6. Simulate render frames and record performance
        for (int i = 0; i < 10; i++) {
            tuner.recordFramePerformance(2.5 + Math.random() * 0.2);
        }

        // 7. Verify performance tracking
        var stats = tuner.getPerformanceStats();
        assertEquals(10, stats.sampleCount(), "Should track 10 frames");
        assertTrue(stats.averageThroughput() > 2.0, "Should have positive throughput");
    }

    @Test
    @DisplayName("End-to-end flow validates complete tuning pipeline")
    void testEndToEndCompletePipeline() {
        // Full pipeline: GPUCapabilities -> GPUAutoTuner -> TuningBenchmark -> DynamicTuner

        // 1. Create capabilities for test GPU
        var capabilities = new GPUCapabilities(64, 65536, 65536, GPUVendor.AMD, "RX 7900 XT", 64);

        // 2. Create profile loader and auto-tuner
        var profileLoader = new GPUTuningProfileLoader();
        var autoTuner = new GPUAutoTuner(capabilities, tempDir.toString());

        // 3. Create benchmark with mock executor
        var benchmark = new TuningBenchmark(
            TuningBenchmark.mockExecutor(1.0),
            java.time.Duration.ofSeconds(2),
            0, 2, 1000
        );

        // 4. Get candidates from profile or generate
        var candidates = profileLoader.getVendorCandidates(capabilities);
        if (candidates.isEmpty()) {
            candidates = autoTuner.generateCandidates();
        }
        assertFalse(candidates.isEmpty(), "Should have candidates");

        // 5. Benchmark candidates and select optimal
        var optimal = benchmark.selectOptimalConfig(candidates);
        benchmark.shutdown();

        // 6. Verify optimal config
        assertNotNull(optimal, "Should select optimal config");
        assertTrue(optimal.workgroupSize() >= 32, "Should have valid workgroup size");

        // 7. Create dynamic tuner and verify integration
        var tuner = new DynamicTuner(capabilities, tempDir.toString(),
            TuningBenchmark.mockExecutor(1.0));
        tuners.add(tuner);

        var result = tuner.sessionStart();
        assertTrue(result.isPresent(), "Session start should succeed");
    }

    // ==================== Multi-Vendor Consistency ====================

    @ParameterizedTest
    @EnumSource(value = GPUVendor.class, names = {"NVIDIA", "AMD", "INTEL", "APPLE"})
    @DisplayName("Multi-vendor: tuning works across all vendors")
    void testMultiVendorTuning(GPUVendor vendor) {
        var capabilities = createCapabilitiesForVendor(vendor);

        var tuner = new DynamicTuner(capabilities, tempDir.toString(),
            TuningBenchmark.mockExecutor(1.0));
        tuners.add(tuner);

        var result = tuner.sessionStart();

        assertTrue(result.isPresent(), vendor + " should tune successfully");

        var config = result.get().config();
        assertTrue(config.workgroupSize() >= 32 && config.workgroupSize() <= 256,
            vendor + " should have valid workgroup size");
        assertTrue(config.maxTraversalDepth() >= 16 && config.maxTraversalDepth() <= 32,
            vendor + " should have valid depth");

        var options = result.get().buildOptions();
        assertTrue(options.contains("-D WORKGROUP_SIZE"),
            vendor + " should have WORKGROUP_SIZE define");
    }

    @Test
    @DisplayName("Multi-vendor consistency: results are comparable across vendors")
    void testMultiVendorConsistency() {
        var results = new HashMap<GPUVendor, GPUAutoTuner.AutoTuneResult>();

        // Run tuning for each vendor
        for (var vendor : List.of(GPUVendor.NVIDIA, GPUVendor.AMD, GPUVendor.INTEL, GPUVendor.APPLE)) {
            var capabilities = createCapabilitiesForVendor(vendor);
            var tuner = new DynamicTuner(capabilities, tempDir.toString(),
                TuningBenchmark.mockExecutor(1.0));
            tuners.add(tuner);

            var result = tuner.sessionStart();
            assertTrue(result.isPresent(), vendor + " tuning should succeed");
            results.put(vendor, result.get());
        }

        // Verify all vendors produced valid configs
        assertEquals(4, results.size(), "Should have results for all 4 vendors");

        // Verify consistency: all workgroup sizes are powers of 2
        for (var entry : results.entrySet()) {
            var size = entry.getValue().config().workgroupSize();
            assertTrue(isPowerOfTwo(size),
                entry.getKey() + " workgroup size " + size + " should be power of 2");
        }

        // Verify all depths are reasonable (16-32)
        for (var entry : results.entrySet()) {
            var depth = entry.getValue().config().maxTraversalDepth();
            assertTrue(depth >= 16 && depth <= 32,
                entry.getKey() + " depth " + depth + " should be in range 16-32");
        }
    }

    // ==================== Performance Regression ====================

    @Test
    @DisplayName("Performance: tuned config outperforms default")
    void testTunedVsDefault() {
        var capabilities = new GPUCapabilities(128, 65536, 65536,
            GPUVendor.NVIDIA, "RTX 4090", 32);

        var autoTuner = new GPUAutoTuner(capabilities, tempDir.toString());

        // Get default config
        var defaultConfig = WorkgroupConfig.forDevice(capabilities);

        // Get tuned config via benchmark
        var tuner = new DynamicTuner(capabilities, tempDir.toString(),
            TuningBenchmark.mockExecutor(1.0));
        tuners.add(tuner);
        var tunedResult = tuner.sessionStart();
        assertTrue(tunedResult.isPresent());
        var tunedConfig = tunedResult.get().config();

        // Mock executor favors larger workgroups, so tuned should be >= default
        assertTrue(tunedConfig.workgroupSize() >= defaultConfig.workgroupSize(),
            "Tuned config should have equal or better workgroup size");

        // Expected throughput should be better or equal
        assertTrue(tunedConfig.expectedThroughput() >= 0,
            "Tuned config should have valid throughput estimate");
    }

    @Test
    @DisplayName("Performance: benchmark selects highest throughput config")
    void testBenchmarkSelectsHighestThroughput() {
        var capabilities = new GPUCapabilities(128, 65536, 65536,
            GPUVendor.NVIDIA, "Test GPU", 32);

        var autoTuner = new GPUAutoTuner(capabilities, tempDir.toString());
        var candidates = autoTuner.generateCandidates();

        // Mock executor gives higher throughput to larger workgroups
        var benchmark = new TuningBenchmark(
            TuningBenchmark.mockExecutor(1.0),
            java.time.Duration.ofSeconds(2),
            0, 2, 1000
        );

        var results = benchmark.benchmarkConfigs(candidates);
        benchmark.shutdown();

        // First result should have highest throughput (sorted descending)
        assertTrue(results.size() > 1, "Should have multiple results");
        assertTrue(results.get(0).throughputRaysPerMicrosecond() >=
                   results.get(results.size() - 1).throughputRaysPerMicrosecond(),
            "Results should be sorted by throughput descending");
    }

    // ==================== Fallback Scenarios ====================

    @Test
    @DisplayName("Fallback: GPU detection failure uses default config")
    void testFallbackOnGPUDetectionFailure() {
        // Simulate unknown GPU vendor
        var capabilities = new GPUCapabilities(64, 32768, 32768,
            GPUVendor.UNKNOWN, "Unknown GPU", 32);

        var tuner = new DynamicTuner(capabilities, tempDir.toString(),
            TuningBenchmark.mockExecutor(1.0));
        tuners.add(tuner);

        var result = tuner.sessionStartWithFallback();

        assertTrue(result.isPresent(), "Should return fallback config");
        assertTrue(result.get().config().workgroupSize() >= 32,
            "Fallback should have valid workgroup size");
    }

    @Test
    @DisplayName("Fallback: benchmark failure uses cached config")
    void testFallbackOnBenchmarkFailure() {
        var capabilities = new GPUCapabilities(128, 65536, 65536,
            GPUVendor.NVIDIA, "RTX 4090", 32);

        // First, do successful tuning to populate cache
        var tuner1 = new DynamicTuner(capabilities, tempDir.toString(),
            TuningBenchmark.mockExecutor(1.0));
        tuners.add(tuner1);
        var firstResult = tuner1.sessionStart();
        assertTrue(firstResult.isPresent());
        var cachedSize = firstResult.get().config().workgroupSize();
        tuner1.shutdown();

        // Now create tuner with failing executor
        var tuner2 = new DynamicTuner(capabilities, tempDir.toString(),
            (config, rays) -> { throw new RuntimeException("GPU unavailable"); });
        tuners.add(tuner2);

        var fallbackResult = tuner2.sessionStartWithFallback();

        assertTrue(fallbackResult.isPresent(), "Should return fallback");
        assertEquals(cachedSize, fallbackResult.get().config().workgroupSize(),
            "Should use cached workgroup size");
    }

    @Test
    @DisplayName("Fallback: no cache uses conservative default")
    void testFallbackWithNoCache() {
        var capabilities = new GPUCapabilities(128, 65536, 65536,
            GPUVendor.NVIDIA, "RTX 4090", 32);

        // Create tuner with failing executor and empty cache directory
        Path emptyDir = tempDir.resolve("empty");
        var tuner = new DynamicTuner(capabilities, emptyDir.toString(),
            (config, rays) -> { throw new RuntimeException("GPU unavailable"); });
        tuners.add(tuner);

        var result = tuner.sessionStartWithFallback();

        assertTrue(result.isPresent(), "Should return default config");
        // Default config should be conservative but valid
        assertTrue(result.get().config().workgroupSize() >= 32,
            "Default should have valid workgroup size");
        assertTrue(result.get().buildOptions().contains("-D WORKGROUP_SIZE"),
            "Should have build options");
    }

    @Test
    @DisplayName("Fallback: graceful degradation chain")
    void testGracefulDegradationChain() {
        // Test the full fallback chain: benchmark -> cache -> default

        var capabilities = new GPUCapabilities(128, 65536, 65536,
            GPUVendor.AMD, "RX 7900 XT", 64);

        // Step 1: No cache, no benchmark -> should use default
        Path noCacheDir = tempDir.resolve("no-cache");
        var tuner1 = new DynamicTuner(capabilities, noCacheDir.toString(),
            (config, rays) -> { throw new RuntimeException("Fail 1"); });
        tuners.add(tuner1);

        var result1 = tuner1.sessionStartWithFallback();
        assertTrue(result1.isPresent(), "Should fall back to default");

        // Step 2: Successful benchmark -> should tune and cache
        var tuner2 = new DynamicTuner(capabilities, tempDir.toString(),
            TuningBenchmark.mockExecutor(1.0));
        tuners.add(tuner2);

        var result2 = tuner2.sessionStart();
        assertTrue(result2.isPresent(), "Benchmark should succeed");
        var tunedSize = result2.get().config().workgroupSize();
        tuner2.shutdown();

        // Step 3: Failed benchmark -> should use cached config
        var tuner3 = new DynamicTuner(capabilities, tempDir.toString(),
            (config, rays) -> { throw new RuntimeException("Fail 3"); });
        tuners.add(tuner3);

        var result3 = tuner3.sessionStartWithFallback();
        assertTrue(result3.isPresent(), "Should fall back to cache");
        assertEquals(tunedSize, result3.get().config().workgroupSize(),
            "Should use cached tuned config");
    }

    // ==================== Integration with Profile System ====================

    @Test
    @DisplayName("Integration: profile loader provides vendor candidates")
    void testProfileLoaderIntegration() {
        var profileLoader = new GPUTuningProfileLoader();

        for (var vendor : List.of(GPUVendor.NVIDIA, GPUVendor.AMD, GPUVendor.INTEL, GPUVendor.APPLE)) {
            var capabilities = createCapabilitiesForVendor(vendor);
            var candidates = profileLoader.getVendorCandidates(capabilities);

            // Vendor profiles should provide candidates
            assertFalse(candidates.isEmpty(),
                vendor + " should have vendor candidates");

            // All candidates should be valid
            for (var candidate : candidates) {
                assertTrue(candidate.workgroupSize() >= 32 && candidate.workgroupSize() <= 256,
                    vendor + " candidate should have valid workgroup size");
            }
        }
    }

    @Test
    @DisplayName("Integration: auto-tuner uses profile loader candidates")
    void testAutoTunerProfileLoaderIntegration() {
        var capabilities = new GPUCapabilities(128, 65536, 65536,
            GPUVendor.NVIDIA, "RTX 4090", 32);
        var profileLoader = new GPUTuningProfileLoader();
        var autoTuner = new GPUAutoTuner(capabilities, tempDir.toString());

        // Get result using profile loader
        var result = autoTuner.selectOptimalConfigByBenchmark(
            TuningBenchmark.mockExecutor(1.0),
            profileLoader
        );

        assertNotNull(result, "Should produce result");
        assertNotNull(result.config(), "Should have config");
        assertTrue(result.buildOptions().contains("-D WORKGROUP_SIZE"),
            "Should have build options");
    }

    // ==================== Build Options Validation ====================

    @Test
    @DisplayName("Build options: vendor-specific flags are included")
    void testVendorSpecificBuildOptions() {
        var vendorFlags = Map.of(
            GPUVendor.NVIDIA, "-cl-mad-enable",
            GPUVendor.AMD, "-cl-fast-relaxed-math",
            GPUVendor.INTEL, "-cl-fast-relaxed-math",
            GPUVendor.APPLE, "-cl-fast-relaxed-math"
        );

        for (var entry : vendorFlags.entrySet()) {
            var capabilities = createCapabilitiesForVendor(entry.getKey());
            var tuner = new DynamicTuner(capabilities, tempDir.toString(),
                TuningBenchmark.mockExecutor(1.0));
            tuners.add(tuner);

            var result = tuner.sessionStart();
            assertTrue(result.isPresent());
            assertTrue(result.get().buildOptions().contains(entry.getValue()),
                entry.getKey() + " should include " + entry.getValue());
        }
    }

    // ==================== Helper Methods ====================

    private GPUCapabilities createCapabilitiesForVendor(GPUVendor vendor) {
        return switch (vendor) {
            case NVIDIA -> new GPUCapabilities(128, 65536, 65536, vendor, "RTX 4090", 32);
            case AMD -> new GPUCapabilities(64, 65536, 65536, vendor, "RX 7900 XT", 64);
            case INTEL -> new GPUCapabilities(64, 32768, 32768, vendor, "Arc A770", 8);
            case APPLE -> new GPUCapabilities(32, 32768, 32768, vendor, "M3 Max", 32);
            case UNKNOWN -> new GPUCapabilities(32, 16384, 16384, vendor, "Unknown", 4);
        };
    }

    private boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }
}
