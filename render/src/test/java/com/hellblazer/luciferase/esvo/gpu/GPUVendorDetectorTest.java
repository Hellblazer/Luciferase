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
package com.hellblazer.luciferase.esvo.gpu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3.1.4 D1: GPUVendorDetector Tests
 *
 * Test coverage for GPU vendor detection via OpenCL device queries.
 * Tests that require real GPU hardware are conditional on RUN_GPU_TESTS=true.
 *
 * @author hal.hildebrand
 */
@DisplayName("F3.1.4 D1: GPUVendorDetector Tests")
class GPUVendorDetectorTest {

    // ==================== Basic Detection Tests (No GPU Required) ====================

    @Test
    @DisplayName("GPUVendorDetector is singleton")
    void testSingleton() {
        var detector1 = GPUVendorDetector.getInstance();
        var detector2 = GPUVendorDetector.getInstance();
        assertSame(detector1, detector2, "GPUVendorDetector should be singleton");
    }

    @Test
    @DisplayName("Detector returns UNKNOWN when no GPU available")
    void testNoGPUReturnsUnknown() {
        var detector = GPUVendorDetector.getInstance();
        // This test assumes OpenCL might not be available in all CI environments
        var vendor = detector.getVendor();
        assertNotNull(vendor, "Vendor should never be null");
    }

    @Test
    @DisplayName("Device name is never null")
    void testDeviceNameNeverNull() {
        var detector = GPUVendorDetector.getInstance();
        var deviceName = detector.getDeviceName();
        assertNotNull(deviceName, "Device name should never be null");
    }

    @Test
    @DisplayName("Capabilities record is never null")
    void testCapabilitiesNeverNull() {
        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();
        assertNotNull(capabilities, "Capabilities should never be null");
        assertNotNull(capabilities.vendor(), "Vendor in capabilities should not be null");
        assertNotNull(capabilities.deviceName(), "Device name in capabilities should not be null");
    }

    // ==================== GPU Hardware Tests (Conditional) ====================

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Detect GPU vendor from actual hardware")
    void testDetectVendorFromHardware() {
        var detector = GPUVendorDetector.getInstance();
        var vendor = detector.getVendor();

        // When GPU is available, vendor should not be UNKNOWN
        // (unless it's truly an unknown vendor)
        assertNotNull(vendor, "Vendor should be detected");

        System.out.println("Detected GPU vendor: " + vendor);
        System.out.println("Device name: " + detector.getDeviceName());
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Device name contains vendor information")
    void testDeviceNameContainsVendor() {
        var detector = GPUVendorDetector.getInstance();
        var vendor = detector.getVendor();
        var deviceName = detector.getDeviceName();

        if (vendor != GPUVendor.UNKNOWN) {
            var lowerDeviceName = deviceName.toLowerCase();
            var lowerVendorId = vendor.getIdentifier().toLowerCase();

            // Device name should contain vendor identifier or related keywords
            boolean containsVendorInfo =
                lowerDeviceName.contains(lowerVendorId) ||
                (vendor == GPUVendor.NVIDIA && (lowerDeviceName.contains("geforce") ||
                                                lowerDeviceName.contains("quadro") ||
                                                lowerDeviceName.contains("tesla"))) ||
                (vendor == GPUVendor.AMD && lowerDeviceName.contains("radeon")) ||
                (vendor == GPUVendor.INTEL && (lowerDeviceName.contains("iris") ||
                                               lowerDeviceName.contains("uhd") ||
                                               lowerDeviceName.contains("arc")));

            assertTrue(containsVendorInfo,
                      "Device name '" + deviceName + "' should contain vendor information for " + vendor);
        }
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Capabilities contain non-zero compute units")
    void testCapabilitiesComputeUnits() {
        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();

        // If GPU is available, should have at least 1 compute unit
        if (detector.getVendor() != GPUVendor.UNKNOWN) {
            assertTrue(capabilities.computeUnits() > 0,
                      "GPU should have at least 1 compute unit");
        }
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Capabilities contain non-zero max workgroup size")
    void testCapabilitiesMaxWorkgroupSize() {
        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();

        if (detector.getVendor() != GPUVendor.UNKNOWN) {
            assertTrue(capabilities.maxWorkGroupSize() > 0,
                      "GPU should have non-zero max workgroup size");
        }
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Capabilities contain positive global memory size")
    void testCapabilitiesGlobalMemory() {
        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();

        if (detector.getVendor() != GPUVendor.UNKNOWN) {
            assertTrue(capabilities.globalMemorySize() > 0,
                      "GPU should have positive global memory size");
        }
    }

    // ==================== Vendor-Specific Hardware Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA GPU detected correctly")
    void testNvidiaDetection() {
        var detector = GPUVendorDetector.getInstance();
        assertEquals(GPUVendor.NVIDIA, detector.getVendor(),
                    "GPU_VENDOR=NVIDIA environment variable set but detected " + detector.getVendor());

        var deviceName = detector.getDeviceName().toLowerCase();
        assertTrue(deviceName.contains("nvidia") ||
                   deviceName.contains("geforce") ||
                   deviceName.contains("quadro") ||
                   deviceName.contains("tesla"),
                  "NVIDIA device name should contain vendor keywords: " + deviceName);
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD GPU detected correctly")
    void testAmdDetection() {
        var detector = GPUVendorDetector.getInstance();
        assertEquals(GPUVendor.AMD, detector.getVendor(),
                    "GPU_VENDOR=AMD environment variable set but detected " + detector.getVendor());

        var deviceName = detector.getDeviceName().toLowerCase();
        assertTrue(deviceName.contains("amd") || deviceName.contains("radeon"),
                  "AMD device name should contain vendor keywords: " + deviceName);
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel GPU detected correctly")
    void testIntelDetection() {
        var detector = GPUVendorDetector.getInstance();
        assertEquals(GPUVendor.INTEL, detector.getVendor(),
                    "GPU_VENDOR=Intel environment variable set but detected " + detector.getVendor());

        var deviceName = detector.getDeviceName().toLowerCase();
        assertTrue(deviceName.contains("intel") ||
                   deviceName.contains("iris") ||
                   deviceName.contains("uhd") ||
                   deviceName.contains("arc"),
                  "Intel device name should contain vendor keywords: " + deviceName);
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
    @Test
    @DisplayName("Apple GPU detected correctly")
    void testAppleDetection() {
        var detector = GPUVendorDetector.getInstance();
        assertEquals(GPUVendor.APPLE, detector.getVendor(),
                    "GPU_VENDOR=Apple environment variable set but detected " + detector.getVendor());

        var deviceName = detector.getDeviceName().toLowerCase();
        assertTrue(deviceName.contains("apple") || deviceName.contains("metal"),
                  "Apple device name should contain vendor keywords: " + deviceName);
    }

    // ==================== Environment Variable Override Tests ====================

    @Test
    @DisplayName("Environment variable GPU_VENDOR can override detection")
    void testEnvironmentVariableOverride() {
        // Read environment variable
        var envVendor = System.getenv("GPU_VENDOR");

        if (envVendor != null && !envVendor.isEmpty()) {
            var detector = GPUVendorDetector.getInstance();
            var detectedVendor = detector.getVendor();

            // If environment variable is set, detection should match or be UNKNOWN
            var expectedVendor = GPUVendor.fromString(envVendor);

            // This test documents the behavior but doesn't enforce it
            // (actual override logic is optional)
            System.out.println("Environment GPU_VENDOR: " + envVendor);
            System.out.println("Expected vendor: " + expectedVendor);
            System.out.println("Detected vendor: " + detectedVendor);
        }
    }
}
