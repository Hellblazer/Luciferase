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
package com.hellblazer.luciferase.esvo.gpu.report;

import com.hellblazer.luciferase.esvo.gpu.GPUVendor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PerformanceBaseline D4 implementation.
 *
 * @author hal.hildebrand
 */
class PerformanceBaselineTest {

    @TempDir
    Path tempDir;

    @Test
    void testKernelMetricsWithinTolerance() {
        var baseline = new PerformanceBaseline.KernelMetrics(1_000_000.0, 16.67, 18.5);
        var measured = new PerformanceBaseline.KernelMetrics(1_040_000.0, 16.0, 17.8);

        // 4% difference should be within 5% tolerance
        assertTrue(baseline.isWithinTolerance(measured, 0.05));

        // 4% difference should NOT be within 3% tolerance
        assertFalse(baseline.isWithinTolerance(measured, 0.03));
    }

    @Test
    void testKernelMetricsPercentageDifference() {
        var baseline = new PerformanceBaseline.KernelMetrics(1_000_000.0, 16.67, 18.5);
        var faster = new PerformanceBaseline.KernelMetrics(1_100_000.0, 15.0, 16.5);
        var slower = new PerformanceBaseline.KernelMetrics(900_000.0, 18.0, 20.0);

        // 10% faster
        assertEquals(10.0, baseline.percentageDifference(faster), 0.01);

        // 10% slower
        assertEquals(-10.0, baseline.percentageDifference(slower), 0.01);
    }

    @Test
    void testKernelMetricsZeroBaseline() {
        var zeroBaseline = new PerformanceBaseline.KernelMetrics(0.0, 0.0, 0.0);
        var measured = new PerformanceBaseline.KernelMetrics(1_000_000.0, 16.67, 18.5);

        // Zero baseline should always be within tolerance
        assertTrue(zeroBaseline.isWithinTolerance(measured, 0.05));
        assertEquals(0.0, zeroBaseline.percentageDifference(measured));
    }

    @Test
    void testTestParametersDefaults() {
        var defaults = PerformanceBaseline.TestParameters.defaults();

        assertEquals(1920, defaults.frameWidth());
        assertEquals(1080, defaults.frameHeight());
        assertEquals(100, defaults.iterations());
        assertEquals(10, defaults.warmupIterations());
    }

    @Test
    void testBuilderCreatesValidBaseline() {
        var timestamp = LocalDateTime.of(2026, 1, 15, 10, 30);
        var metrics = Map.of(
            "dag_ray_traversal", new PerformanceBaseline.KernelMetrics(1_000_000.0, 16.67, 18.5),
            "depth_buffer_clear", new PerformanceBaseline.KernelMetrics(500_000.0, 2.0, 2.5)
        );

        var baseline = PerformanceBaseline.builder()
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .driverVersion("535.154.05")
            .openCLVersion("OpenCL 3.0")
            .timestamp(timestamp)
            .testParameters(PerformanceBaseline.TestParameters.defaults())
            .metrics(metrics)
            .build();

        assertEquals(GPUVendor.NVIDIA, baseline.vendor());
        assertEquals("RTX 4090", baseline.deviceName());
        assertEquals("535.154.05", baseline.driverVersion());
        assertEquals("OpenCL 3.0", baseline.openCLVersion());
        assertEquals(timestamp, baseline.timestamp());
        assertEquals(2, baseline.metrics().size());
        assertNotNull(baseline.getKernelMetrics("dag_ray_traversal"));
    }

    @Test
    void testGenerateFilename() {
        assertEquals("nvidia_rtx_4090.json",
            PerformanceBaseline.generateFilename(GPUVendor.NVIDIA, "RTX 4090"));

        assertEquals("apple_m4_max.json",
            PerformanceBaseline.generateFilename(GPUVendor.APPLE, "M4 Max"));

        assertEquals("amd_rx_6900_xt.json",
            PerformanceBaseline.generateFilename(GPUVendor.AMD, "RX 6900 XT"));

        assertEquals("intel_arc_a770.json",
            PerformanceBaseline.generateFilename(GPUVendor.INTEL, "Arc A770"));
    }

    @Test
    void testNormalizeDeviceName() {
        assertEquals("rtx_4090", PerformanceBaseline.normalizeDeviceName("RTX 4090"));
        assertEquals("m4_max", PerformanceBaseline.normalizeDeviceName("M4 Max"));
        assertEquals("rx_6900_xt", PerformanceBaseline.normalizeDeviceName("RX 6900 XT"));
        assertEquals("arc_a770", PerformanceBaseline.normalizeDeviceName("Arc A770"));
        assertEquals("unknown", PerformanceBaseline.normalizeDeviceName(null));
        assertEquals("test_device", PerformanceBaseline.normalizeDeviceName("  Test Device  "));
    }

    @Test
    void testSaveAndLoadFromPath() throws IOException {
        var baseline = createTestBaseline();
        var path = tempDir.resolve("test_baseline.json");

        baseline.save(path);
        assertTrue(Files.exists(path));

        var loaded = PerformanceBaseline.load(path);
        assertNotNull(loaded);
        assertEquals(baseline.vendor(), loaded.vendor());
        assertEquals(baseline.deviceName(), loaded.deviceName());
        assertEquals(baseline.metrics().size(), loaded.metrics().size());
    }

    @Test
    void testSaveAndLoadFromStream() throws IOException {
        var baseline = createTestBaseline();

        var outputStream = new ByteArrayOutputStream();
        baseline.save(outputStream);

        var json = outputStream.toString();
        assertTrue(json.contains("NVIDIA"));
        assertTrue(json.contains("RTX 4090"));

        var inputStream = new ByteArrayInputStream(json.getBytes());
        var loaded = PerformanceBaseline.load(inputStream);

        assertEquals(baseline.vendor(), loaded.vendor());
        assertEquals(baseline.deviceName(), loaded.deviceName());
    }

    @Test
    void testLoadNonExistentPath() {
        var path = tempDir.resolve("nonexistent.json");
        var loaded = PerformanceBaseline.load(path);
        assertNull(loaded);
    }

    @Test
    void testToJson() {
        var baseline = createTestBaseline();
        var json = baseline.toJson();

        assertTrue(json.contains("\"vendor\" : \"NVIDIA\""));
        assertTrue(json.contains("\"deviceName\" : \"RTX 4090\""));
        assertTrue(json.contains("\"dag_ray_traversal\""));
    }

    @Test
    void testAllWithinTolerance() {
        var baselineMetrics = Map.of(
            "kernel1", new PerformanceBaseline.KernelMetrics(1_000_000.0, 16.0, 18.0),
            "kernel2", new PerformanceBaseline.KernelMetrics(500_000.0, 2.0, 2.5)
        );

        var baseline = PerformanceBaseline.builder()
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .metrics(baselineMetrics)
            .build();

        // All within tolerance
        var measuredGood = Map.of(
            "kernel1", new PerformanceBaseline.KernelMetrics(1_040_000.0, 15.5, 17.5),
            "kernel2", new PerformanceBaseline.KernelMetrics(520_000.0, 1.9, 2.4)
        );
        assertTrue(baseline.allWithinTolerance(measuredGood, 0.05));

        // One outside tolerance
        var measuredBad = Map.of(
            "kernel1", new PerformanceBaseline.KernelMetrics(1_040_000.0, 15.5, 17.5),
            "kernel2", new PerformanceBaseline.KernelMetrics(400_000.0, 2.5, 3.0)  // 20% slower
        );
        assertFalse(baseline.allWithinTolerance(measuredBad, 0.05));
    }

    @Test
    void testGetKernelMetrics() {
        var baseline = createTestBaseline();

        assertNotNull(baseline.getKernelMetrics("dag_ray_traversal"));
        assertNull(baseline.getKernelMetrics("nonexistent_kernel"));
    }

    @Test
    void testDefaultTolerance() {
        assertEquals(0.05, PerformanceBaseline.DEFAULT_TOLERANCE);
    }

    private PerformanceBaseline createTestBaseline() {
        var metrics = Map.of(
            "dag_ray_traversal", new PerformanceBaseline.KernelMetrics(1_000_000.0, 16.67, 18.5)
        );

        return PerformanceBaseline.builder()
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .driverVersion("535.154.05")
            .openCLVersion("OpenCL 3.0")
            .timestamp(LocalDateTime.now())
            .testParameters(PerformanceBaseline.TestParameters.defaults())
            .metrics(metrics)
            .build();
    }
}
