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

import com.hellblazer.luciferase.esvo.gpu.*;
import com.hellblazer.luciferase.esvo.gpu.report.MultiVendorConsistencyReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3.1.4: Multi-Vendor Testing Validation Test Suite
 *
 * <p>Validates the multi-vendor GPU testing infrastructure:
 * <ul>
 *   <li>3-tier test execution strategy (mock/local/vendor-specific)</li>
 *   <li>GPUVendor enum completeness and detection</li>
 *   <li>VendorKernelConfig workarounds and compiler flags</li>
 *   <li>MultiVendorConsistencyReport generation</li>
 *   <li>Environment variable gating</li>
 * </ul>
 *
 * @see GPUVendor
 * @see GPUVendorDetector
 * @see VendorKernelConfig
 * @see MultiVendorConsistencyReport
 */
@DisplayName("F3.1.4: Multi-Vendor Testing Validation")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "Requires OpenCL/GPU hardware not available in CI")
class F314MultiVendorValidationTest {

    @Nested
    @DisplayName("GPUVendor Enum")
    class GPUVendorTests {

        @Test
        @DisplayName("All expected vendors defined")
        void testAllVendorsDefined() {
            var vendors = GPUVendor.values();
            assertEquals(5, vendors.length, "Should have 5 vendors");

            assertTrue(containsVendor(vendors, "NVIDIA"));
            assertTrue(containsVendor(vendors, "AMD"));
            assertTrue(containsVendor(vendors, "INTEL"));
            assertTrue(containsVendor(vendors, "APPLE"));
            assertTrue(containsVendor(vendors, "UNKNOWN"));
        }

        @Test
        @DisplayName("Vendor identifiers are lowercase")
        void testVendorIdentifiers() {
            for (var vendor : GPUVendor.values()) {
                var identifier = vendor.getIdentifier();
                assertNotNull(identifier);
                assertEquals(identifier.toLowerCase(), identifier,
                    "Identifier for " + vendor + " should be lowercase");
            }
        }

        @Test
        @DisplayName("Vendor string parsing works")
        void testVendorFromString() {
            assertEquals(GPUVendor.NVIDIA, GPUVendor.fromString("NVIDIA"));
            assertEquals(GPUVendor.NVIDIA, GPUVendor.fromString("nvidia"));
            assertEquals(GPUVendor.NVIDIA, GPUVendor.fromString("NVIDIA Corporation"));

            assertEquals(GPUVendor.AMD, GPUVendor.fromString("AMD"));
            assertEquals(GPUVendor.AMD, GPUVendor.fromString("Advanced Micro Devices"));

            assertEquals(GPUVendor.INTEL, GPUVendor.fromString("Intel"));
            assertEquals(GPUVendor.INTEL, GPUVendor.fromString("Intel(R) Corporation"));

            assertEquals(GPUVendor.APPLE, GPUVendor.fromString("Apple"));

            assertEquals(GPUVendor.UNKNOWN, GPUVendor.fromString("Unknown Vendor XYZ"));
            assertEquals(GPUVendor.UNKNOWN, GPUVendor.fromString(""));
            assertEquals(GPUVendor.UNKNOWN, GPUVendor.fromString(null));
        }

        @Test
        @DisplayName("NVIDIA is baseline (no workarounds needed)")
        void testNvidiaBaseline() {
            assertFalse(GPUVendor.NVIDIA.requiresWorkarounds(),
                "NVIDIA should be baseline (no workarounds)");
        }

        @Test
        @DisplayName("Other vendors require workarounds")
        void testOtherVendorsRequireWorkarounds() {
            assertTrue(GPUVendor.AMD.requiresWorkarounds());
            assertTrue(GPUVendor.INTEL.requiresWorkarounds());
            assertTrue(GPUVendor.APPLE.requiresWorkarounds());
        }

        private boolean containsVendor(GPUVendor[] vendors, String name) {
            for (var v : vendors) {
                if (v.name().equals(name)) return true;
            }
            return false;
        }
    }

    @Nested
    @DisplayName("GPUVendorDetector")
    class GPUVendorDetectorTests {

        @Test
        @DisplayName("Singleton pattern works")
        void testSingletonPattern() {
            var instance1 = GPUVendorDetector.getInstance();
            var instance2 = GPUVendorDetector.getInstance();
            assertSame(instance1, instance2, "Should return same instance");
        }

        @Test
        @DisplayName("Always returns valid vendor (never null)")
        void testNeverReturnsNull() {
            var detector = GPUVendorDetector.getInstance();
            assertNotNull(detector.getVendor(), "Vendor should never be null");
            assertNotNull(detector.getCapabilities(), "Capabilities should never be null");
        }

        @Test
        @DisplayName("Device name is available")
        void testDeviceNameAvailable() {
            var detector = GPUVendorDetector.getInstance();
            assertNotNull(detector.getDeviceName(), "Device name should not be null");
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
        @DisplayName("Detects actual GPU when available")
        void testActualGPUDetection() {
            var detector = GPUVendorDetector.getInstance();
            var capabilities = detector.getCapabilities();

            assertTrue(capabilities.isValid(), "Should detect valid GPU");
            assertNotEquals(GPUVendor.UNKNOWN, detector.getVendor(),
                "Should detect specific vendor");
            assertTrue(capabilities.computeUnits() > 0, "Should have compute units");
            assertTrue(capabilities.globalMemorySize() > 0, "Should have VRAM");
        }
    }

    @Nested
    @DisplayName("GPUCapabilities")
    class GPUCapabilitiesTests {

        @Test
        @DisplayName("None factory creates invalid capabilities")
        void testNoneFactory() {
            var none = GPUCapabilities.none();

            assertFalse(none.isValid());
            assertEquals(GPUVendor.UNKNOWN, none.vendor());
            assertEquals(0, none.computeUnits());
            assertEquals(0, none.globalMemorySize());
        }

        @Test
        @DisplayName("Summary generation works")
        void testSummaryGeneration() {
            var none = GPUCapabilities.none();
            assertNotNull(none.summary());
            assertTrue(none.summary().contains("No GPU"));

            var valid = new GPUCapabilities(
                GPUVendor.NVIDIA,
                "Test GPU",
                "NVIDIA Corporation",
                64,
                1024,
                8L * 1024 * 1024 * 1024,  // 8 GB
                65536,
                2000,
                "OpenCL 3.0"
            );
            assertNotNull(valid.summary());
            assertTrue(valid.summary().contains("NVIDIA"));
            assertTrue(valid.summary().contains("8192 MB"));  // 8 GB in MB
        }
    }

    @Nested
    @DisplayName("VendorKernelConfig")
    class VendorKernelConfigTests {

        @Test
        @DisplayName("All vendors have configurations")
        void testAllVendorsHaveConfigs() {
            for (var vendor : GPUVendor.values()) {
                var config = VendorKernelConfig.forVendor(vendor);
                assertNotNull(config, "Config for " + vendor + " should exist");
            }
        }

        @Test
        @DisplayName("Compiler flags are non-null")
        void testCompilerFlagsNonNull() {
            for (var vendor : GPUVendor.values()) {
                var config = VendorKernelConfig.forVendor(vendor);
                assertNotNull(config.getCompilerFlags(),
                    "Compiler flags for " + vendor + " should not be null");
            }
        }

        @Test
        @DisplayName("NVIDIA uses fast math optimizations")
        void testNvidiaOptimizations() {
            var config = VendorKernelConfig.forVendor(GPUVendor.NVIDIA);
            var flags = config.getCompilerFlags();

            assertTrue(flags.contains("-cl-fast-relaxed-math"),
                "NVIDIA should use fast relaxed math");
            assertTrue(flags.contains("-cl-mad-enable"),
                "NVIDIA should enable MAD instructions");
        }

        @Test
        @DisplayName("AMD has atomic workarounds")
        void testAmdWorkarounds() {
            var config = VendorKernelConfig.forVendor(GPUVendor.AMD);
            var testKernel = "kernel void test() {}";
            var modified = config.applyPreprocessorDefinitions(testKernel);

            assertTrue(modified.contains("AMD_ATOMIC_WORKAROUND") ||
                       modified.contains("USE_RELAXED_ATOMICS"),
                "AMD config should include atomic workarounds");
        }

        @Test
        @DisplayName("Intel has precision workarounds")
        void testIntelWorkarounds() {
            var config = VendorKernelConfig.forVendor(GPUVendor.INTEL);
            var testKernel = "kernel void test() {}";
            var modified = config.applyPreprocessorDefinitions(testKernel);

            assertTrue(modified.contains("INTEL_PRECISION_WORKAROUND") ||
                       modified.contains("RAY_EPSILON"),
                "Intel config should include precision workarounds");
        }

        @Test
        @DisplayName("Apple has macOS workarounds")
        void testAppleWorkarounds() {
            var config = VendorKernelConfig.forVendor(GPUVendor.APPLE);
            var testKernel = "kernel void test() {}";
            var modified = config.applyPreprocessorDefinitions(testKernel);

            assertTrue(modified.contains("APPLE_MACOS_WORKAROUND") ||
                       modified.contains("USE_INTEGER_ABS"),
                "Apple config should include macOS workarounds");
        }

        @Test
        @DisplayName("Detected GPU config works")
        void testDetectedGPUConfig() {
            var config = VendorKernelConfig.forDetectedGPU();
            assertNotNull(config, "Detected GPU config should not be null");
            assertNotNull(config.getCompilerFlags());
        }
    }

    @Nested
    @DisplayName("MultiVendorConsistencyReport")
    class MultiVendorConsistencyReportTests {

        @Test
        @DisplayName("Empty report has 0% consistency")
        void testEmptyReport() {
            var report = new MultiVendorConsistencyReport();
            assertEquals(0.0, report.getOverallConsistency(), 0.001);
            assertFalse(report.meetsTarget(90.0));
        }

        @Test
        @DisplayName("Report with results calculates consistency")
        void testReportWithResults() {
            var report = new MultiVendorConsistencyReport();
            report.addResult(new MultiVendorConsistencyReport.VendorResult(
                GPUVendor.NVIDIA, "RTX 3080", 10, 10, 0, List.of(), List.of()));
            report.addResult(new MultiVendorConsistencyReport.VendorResult(
                GPUVendor.AMD, "RX 6800", 10, 9, 1, List.of("test1"), List.of("atomic")));

            // (10 + 9) / (10 + 10) = 95%
            assertEquals(95.0, report.getOverallConsistency(), 0.001);
            assertTrue(report.meetsTarget(90.0));
        }

        @Test
        @DisplayName("Report generates markdown")
        void testMarkdownGeneration() {
            var report = new MultiVendorConsistencyReport();
            report.addResult(new MultiVendorConsistencyReport.VendorResult(
                GPUVendor.NVIDIA, "Test GPU", 5, 5, 0, List.of(), List.of()));

            var markdown = report.generateReport();
            assertNotNull(markdown);
            assertTrue(markdown.contains("NVIDIA"));
            assertTrue(markdown.contains("Test GPU"));
        }

        @Test
        @DisplayName("Summary generation works")
        void testSummaryGeneration() {
            var report = new MultiVendorConsistencyReport();
            report.addResult(new MultiVendorConsistencyReport.VendorResult(
                GPUVendor.NVIDIA, "Test", 10, 10, 0, List.of(), List.of()));

            var summary = report.generateSummary();
            assertNotNull(summary);
            assertTrue(summary.contains("100.0%") || summary.contains("100%"));
        }
    }

    @Nested
    @DisplayName("3-Tier Test Strategy")
    class ThreeTierStrategyTests {

        @Test
        @DisplayName("Tier 1: Mock tests run without GPU")
        void testTier1MockTests() {
            // This test always runs - validates mock-GPU CI/CD compatibility
            var config = VendorKernelConfig.forVendor(GPUVendor.NVIDIA);
            assertNotNull(config);

            var detector = GPUVendorDetector.getInstance();
            assertNotNull(detector.getVendor());

            // Can create report without actual GPU
            var report = new MultiVendorConsistencyReport();
            assertNotNull(report.generateReport());
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
        @DisplayName("Tier 2: Local GPU tests require RUN_GPU_TESTS=true")
        void testTier2LocalGPU() {
            var detector = GPUVendorDetector.getInstance();
            var capabilities = detector.getCapabilities();

            assertTrue(capabilities.isValid(),
                "RUN_GPU_TESTS=true requires actual GPU");
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
        @DisplayName("Tier 3: NVIDIA-specific tests require GPU_VENDOR=NVIDIA")
        void testTier3NvidiaSpecific() {
            var detector = GPUVendorDetector.getInstance();
            assertEquals(GPUVendor.NVIDIA, detector.getVendor(),
                "GPU_VENDOR=NVIDIA requires NVIDIA GPU");
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
        @DisplayName("Tier 3: AMD-specific tests require GPU_VENDOR=AMD")
        void testTier3AmdSpecific() {
            var detector = GPUVendorDetector.getInstance();
            assertEquals(GPUVendor.AMD, detector.getVendor(),
                "GPU_VENDOR=AMD requires AMD GPU");
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
        @DisplayName("Tier 3: Intel-specific tests require GPU_VENDOR=Intel")
        void testTier3IntelSpecific() {
            var detector = GPUVendorDetector.getInstance();
            assertEquals(GPUVendor.INTEL, detector.getVendor(),
                "GPU_VENDOR=Intel requires Intel GPU");
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
        @DisplayName("Tier 3: Apple-specific tests require GPU_VENDOR=Apple")
        void testTier3AppleSpecific() {
            var detector = GPUVendorDetector.getInstance();
            assertEquals(GPUVendor.APPLE, detector.getVendor(),
                "GPU_VENDOR=Apple requires Apple GPU");
        }
    }

    @Nested
    @DisplayName("CI/CD Compatibility")
    class CICDCompatibilityTests {

        @Test
        @DisplayName("All mock tests pass without GPU hardware")
        void testMockTestsPassWithoutGPU() {
            // Validate that tier 1 tests work in CI without GPU
            // These operations must not fail
            var vendor = GPUVendorDetector.getInstance().getVendor();
            var config = VendorKernelConfig.forVendor(vendor);
            var capabilities = GPUVendorDetector.getInstance().getCapabilities();

            assertNotNull(vendor);
            assertNotNull(config);
            assertNotNull(capabilities);
            assertNotNull(capabilities.summary());
        }

        @Test
        @DisplayName("Graceful degradation when no GPU")
        void testGracefulDegradation() {
            var capabilities = GPUVendorDetector.getInstance().getCapabilities();

            if (!capabilities.isValid()) {
                // No GPU - should still work gracefully
                assertEquals(GPUVendor.UNKNOWN, capabilities.vendor());
                assertEquals("No GPU available", capabilities.summary());
            }
            // If GPU present, capabilities are valid - also OK
        }

        @Test
        @DisplayName("Report generation works without GPU results")
        void testReportWithoutGPUResults() {
            // CI should be able to generate empty reports
            var report = new MultiVendorConsistencyReport();

            assertNotNull(report.generateReport());
            assertNotNull(report.generateSummary());
            assertEquals(0, report.getVendorsMeetingTarget(90.0).size());
        }
    }
}
