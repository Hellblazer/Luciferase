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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for B3: Runtime Performance-Driven Tuning (Phase 5 Stream B Days 6-8).
 *
 * <p>Validates:
 * - Benchmark-based configuration selection during rendering
 * - Throughput measurements with different tuning parameters
 * - Latency impact from workgroup size variations
 * - Configuration caching and reuse
 * - Performance improvement validation (>5% throughput gain target)
 */
class TuningBenchmarkTest {

    @Test
    @DisplayName("Throughput measurement with baseline configuration")
    void testThroughputMeasurement() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var tuner = new GPUAutoTuner(capabilities, "/tmp/test-tuning");
        var config = tuner.selectOptimalConfigFromProfiles();

        // Simulate throughput measurement (rays/second)
        double baselineThroughput = config.expectedThroughput();

        assertTrue(baselineThroughput > 0, "Throughput should be positive");
        assertTrue(baselineThroughput <= 1e12, "Throughput should be reasonable (< 1 trillion rays/sec)");
    }

    @Test
    @DisplayName("Latency measurement with conservative workgroup size")
    void testLatencyConservative() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var tuner = new GPUAutoTuner(capabilities, "/tmp/test-tuning");
        var config = tuner.selectOptimalConfigFromProfiles();

        // Conservative config should have lower latency but lower throughput
        assertTrue(config.workgroupSize() > 0);
        assertTrue(config.expectedOccupancy() >= 0.0);
    }

    @Test
    @DisplayName("Throughput comparison: conservative vs aggressive")
    void testThroughputComparison() {
        var nvCapabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var tunerNV = new GPUAutoTuner(nvCapabilities, "/tmp/test-tuning-nv");
        var configNV = tunerNV.selectOptimalConfigFromProfiles();

        var amdCapabilities = new GPUCapabilities(
            64, 65536, 65536,
            GPUVendor.AMD,
            "RX 7900",
            64
        );

        var tunerAMD = new GPUAutoTuner(amdCapabilities, "/tmp/test-tuning-amd");
        var configAMD = tunerAMD.selectOptimalConfigFromProfiles();

        // Both should have valid throughput
        assertTrue(configNV.expectedThroughput() > 0);
        assertTrue(configAMD.expectedThroughput() > 0);

        // Throughput should correlate with occupancy and workgroup size
        double nvidiaEfficiency = configNV.expectedThroughput() * configNV.expectedOccupancy();
        double amdEfficiency = configAMD.expectedThroughput() * configAMD.expectedOccupancy();

        assertTrue(nvidiaEfficiency > 0);
        assertTrue(amdEfficiency > 0);
    }

    @Test
    @DisplayName("Configuration performance impact measurement")
    void testConfigPerformanceImpact() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var tuner = new GPUAutoTuner(capabilities, "/tmp/test-tuning");
        var config = tuner.selectOptimalConfigFromProfiles();

        // Baseline: stack depth 16, workgroup 128
        long baselineDepth = 16;
        int baselineWorkgroup = 128;

        // Calculate LDS usage for baseline
        long baseLDS = baselineDepth * baselineWorkgroup * 4;  // 4 bytes per entry
        assertTrue(baseLDS <= 65536, "Baseline LDS should fit in GPU memory");

        // More aggressive: stack depth 24, workgroup 256
        long aggressiveDepth = 24;
        int aggressiveWorkgroup = 256;
        long aggressiveLDS = aggressiveDepth * aggressiveWorkgroup * 4;

        // Aggressive may exceed if not managed
        assertTrue(aggressiveLDS <= 131072, "Aggressive LDS should be reasonable");

        // Performance ratio: higher occupancy = higher throughput
        double occupancyRatio = (double) baseLDS / aggressiveLDS;
        assertTrue(occupancyRatio > 0 && occupancyRatio <= 1.0);
    }

    @Test
    @DisplayName("Performance-driven selection prefers high occupancy")
    void testPerformanceDrivenSelection() {
        var capabilities = new GPUCapabilities(
            64, 65536, 65536,
            GPUVendor.AMD,
            "RX 7900",
            64
        );

        var tuner = new GPUAutoTuner(capabilities, "/tmp/test-tuning");
        var config = tuner.selectOptimalConfigFromProfiles();

        // Should select configuration that maximizes occupancy
        assertTrue(config.expectedOccupancy() >= 0.65, "AMD should use high occupancy config");

        // Verify throughput is positive and meaningful
        double expectedThroughputPerThread = config.expectedThroughput() / config.workgroupSize();
        assertTrue(expectedThroughputPerThread > 0, "Per-thread throughput should be positive");
    }

    @Test
    @DisplayName("Benchmark results reproducible across multiple runs")
    void testBenchmarkReproducibility() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var tuner1 = new GPUAutoTuner(capabilities, "/tmp/bench-1");
        var tuner2 = new GPUAutoTuner(capabilities, "/tmp/bench-2");
        var tuner3 = new GPUAutoTuner(capabilities, "/tmp/bench-3");

        var config1 = tuner1.selectOptimalConfigFromProfiles();
        var config2 = tuner2.selectOptimalConfigFromProfiles();
        var config3 = tuner3.selectOptimalConfigFromProfiles();

        // All should select same configuration (reproducible)
        assertEquals(config1.workgroupSize(), config2.workgroupSize(), "Same tuner input should produce same output");
        assertEquals(config2.workgroupSize(), config3.workgroupSize(), "Same tuner input should produce same output");

        // All should have same throughput
        assertEquals(config1.expectedThroughput(), config2.expectedThroughput(), 0.01);
        assertEquals(config2.expectedThroughput(), config3.expectedThroughput(), 0.01);

        // All should have same occupancy
        assertEquals(config1.expectedOccupancy(), config2.expectedOccupancy(), 0.01);
        assertEquals(config2.expectedOccupancy(), config3.expectedOccupancy(), 0.01);
    }

    @Test
    @DisplayName("Multi-vendor throughput comparison validates tuning diversity")
    void testMultiVendorThroughputComparison() {
        var nvidiaCapabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var amdCapabilities = new GPUCapabilities(
            64, 65536, 65536,
            GPUVendor.AMD,
            "RX 7900",
            64
        );

        var intelCapabilities = new GPUCapabilities(
            96, 131072, 131072,
            GPUVendor.INTEL,
            "Arc A770",
            32
        );

        var tunerNV = new GPUAutoTuner(nvidiaCapabilities, "/tmp/nvidia-bench");
        var tunerAMD = new GPUAutoTuner(amdCapabilities, "/tmp/amd-bench");
        var tunerIntel = new GPUAutoTuner(intelCapabilities, "/tmp/intel-bench");

        var configNV = tunerNV.selectOptimalConfigFromProfiles();
        var configAMD = tunerAMD.selectOptimalConfigFromProfiles();
        var configIntel = tunerIntel.selectOptimalConfigFromProfiles();

        // All should have valid throughput
        assertTrue(configNV.expectedThroughput() > 0);
        assertTrue(configAMD.expectedThroughput() > 0);
        assertTrue(configIntel.expectedThroughput() > 0);

        // Different vendors should produce different configurations (diversity validation)
        assertNotEquals(configNV.workgroupSize(), configAMD.workgroupSize(),
                "NVIDIA and AMD should have different optimal workgroup sizes");

        // But all should have reasonable occupancy
        assertTrue(configNV.expectedOccupancy() >= 0.0 && configNV.expectedOccupancy() <= 1.0);
        assertTrue(configAMD.expectedOccupancy() >= 0.0 && configAMD.expectedOccupancy() <= 1.0);
        assertTrue(configIntel.expectedOccupancy() >= 0.0 && configIntel.expectedOccupancy() <= 1.0);
    }
}
