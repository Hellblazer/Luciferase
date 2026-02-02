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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VendorTestReport D4 implementation.
 *
 * @author hal.hildebrand
 */
class VendorTestReportTest {

    @TempDir
    Path tempDir;

    @Test
    void testTestSummaryTotal() {
        var summary = new VendorTestReport.TestSummary(10, 2, 3, 5000);

        assertEquals(15, summary.total());
    }

    @Test
    void testTestSummaryPassRate() {
        var summary = new VendorTestReport.TestSummary(8, 2, 5, 5000);

        // 8 passed out of 10 executed (skipped don't count)
        assertEquals(80.0, summary.passRate(), 0.01);
    }

    @Test
    void testTestSummaryPassRateAllPassed() {
        var summary = new VendorTestReport.TestSummary(10, 0, 2, 3000);

        assertEquals(100.0, summary.passRate(), 0.01);
        assertTrue(summary.allPassed());
    }

    @Test
    void testTestSummaryPassRateNoneExecuted() {
        var summary = new VendorTestReport.TestSummary(0, 0, 5, 1000);

        // No tests executed should return 100%
        assertEquals(100.0, summary.passRate(), 0.01);
    }

    @Test
    void testPerformanceComparisonCreate() {
        var baseline = new PerformanceBaseline.KernelMetrics(1_000_000.0, 16.67, 18.5);
        var measured = new PerformanceBaseline.KernelMetrics(1_040_000.0, 15.0, 16.5);  // 4% change

        var comparison = VendorTestReport.PerformanceComparison.create(
            "dag_ray_traversal", baseline, measured, 0.05
        );

        assertEquals("dag_ray_traversal", comparison.kernelName());
        assertEquals(1_000_000.0, comparison.baselineRaysPerSec());
        assertEquals(1_040_000.0, comparison.measuredRaysPerSec());
        assertEquals(4.0, comparison.deltaPercent(), 0.01);
        assertTrue(comparison.withinTolerance());  // 4% is within 5% tolerance
    }

    @Test
    void testPerformanceComparisonFormatDelta() {
        var positive = new VendorTestReport.PerformanceComparison(
            "kernel", 1_000_000.0, 1_100_000.0, 10.0, true
        );
        var negative = new VendorTestReport.PerformanceComparison(
            "kernel", 1_000_000.0, 900_000.0, -10.0, true
        );

        assertEquals("+10.0%", positive.formatDelta());
        assertEquals("-10.0%", negative.formatDelta());
    }

    @Test
    void testPerformanceComparisonStatusEmoji() {
        var passing = new VendorTestReport.PerformanceComparison(
            "kernel", 1_000_000.0, 1_050_000.0, 5.0, true
        );
        var warning = new VendorTestReport.PerformanceComparison(
            "kernel", 1_000_000.0, 950_000.0, -5.0, true
        );
        var failing = new VendorTestReport.PerformanceComparison(
            "kernel", 1_000_000.0, 800_000.0, -20.0, false
        );

        assertEquals("\u2705", passing.statusEmoji());  // Checkmark
        assertEquals("\u26A0\uFE0F", warning.statusEmoji());  // Warning
        assertEquals("\u274C", failing.statusEmoji());  // X
    }

    @Test
    void testBuilderCreatesValidReport() {
        var timestamp = LocalDateTime.of(2026, 1, 15, 10, 30);

        var report = VendorTestReport.builder()
            .runDate(timestamp)
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .driverVersion("535.154.05")
            .openCLVersion("OpenCL 3.0")
            .summary(10, 0, 2, 5000)
            .addPerformance(new VendorTestReport.PerformanceComparison(
                "dag_ray_traversal", 1_000_000.0, 1_050_000.0, 5.0, true
            ))
            .addIssue("Minor driver warning")
            .addWorkaround("Disabled kernel caching")
            .build();

        assertEquals(timestamp, report.runDate());
        assertEquals(GPUVendor.NVIDIA, report.vendor());
        assertEquals("RTX 4090", report.deviceName());
        assertEquals("535.154.05", report.driverVersion());
        assertEquals("OpenCL 3.0", report.openCLVersion());
        assertEquals(10, report.summary().passed());
        assertEquals(1, report.performance().size());
        assertEquals(1, report.issues().size());
        assertEquals(1, report.appliedWorkarounds().size());
    }

    @Test
    void testIsSuccess() {
        // All tests pass, all performance within tolerance
        var successReport = VendorTestReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .summary(10, 0, 0, 5000)
            .addPerformance(new VendorTestReport.PerformanceComparison(
                "kernel", 1_000_000.0, 1_040_000.0, 4.0, true
            ))
            .build();

        assertTrue(successReport.isSuccess());

        // Tests fail
        var failedTestReport = VendorTestReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .summary(8, 2, 0, 5000)
            .build();

        assertFalse(failedTestReport.isSuccess());

        // Performance outside tolerance
        var failedPerfReport = VendorTestReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .summary(10, 0, 0, 5000)
            .addPerformance(new VendorTestReport.PerformanceComparison(
                "kernel", 1_000_000.0, 800_000.0, -20.0, false
            ))
            .build();

        assertFalse(failedPerfReport.isSuccess());
    }

    @Test
    void testAllPerformanceWithinTolerance() {
        // No performance data
        var noPerf = VendorTestReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .summary(10, 0, 0, 5000)
            .build();

        assertTrue(noPerf.allPerformanceWithinTolerance());

        // All within tolerance
        var allGood = VendorTestReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .summary(10, 0, 0, 5000)
            .addPerformance(new VendorTestReport.PerformanceComparison(
                "kernel1", 1_000_000.0, 1_040_000.0, 4.0, true
            ))
            .addPerformance(new VendorTestReport.PerformanceComparison(
                "kernel2", 500_000.0, 520_000.0, 4.0, true
            ))
            .build();

        assertTrue(allGood.allPerformanceWithinTolerance());

        // One outside tolerance
        var oneBad = VendorTestReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .summary(10, 0, 0, 5000)
            .addPerformance(new VendorTestReport.PerformanceComparison(
                "kernel1", 1_000_000.0, 1_040_000.0, 4.0, true
            ))
            .addPerformance(new VendorTestReport.PerformanceComparison(
                "kernel2", 500_000.0, 400_000.0, -20.0, false
            ))
            .build();

        assertFalse(oneBad.allPerformanceWithinTolerance());
    }

    @Test
    void testSaveAsJson() throws IOException {
        var report = createTestReport();
        var path = tempDir.resolve("test_report.json");

        report.saveAsJson(path);

        assertTrue(Files.exists(path));
        var content = Files.readString(path);
        assertTrue(content.contains("\"vendor\" : \"NVIDIA\""));
        assertTrue(content.contains("\"deviceName\" : \"RTX 4090\""));
    }

    @Test
    void testToJson() throws IOException {
        var report = createTestReport();
        var json = report.toJson();

        assertTrue(json.contains("\"vendor\" : \"NVIDIA\""));
        assertTrue(json.contains("\"deviceName\" : \"RTX 4090\""));
        assertTrue(json.contains("\"passed\" : 10"));
    }

    @Test
    void testToMarkdown() {
        var report = VendorTestReport.builder()
            .runDate(LocalDateTime.of(2026, 1, 15, 10, 30))
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .driverVersion("535.154.05")
            .openCLVersion("OpenCL 3.0")
            .summary(10, 0, 2, 5000)
            .addPerformance(new VendorTestReport.PerformanceComparison(
                "dag_ray_traversal", 1_000_000.0, 1_050_000.0, 5.0, true
            ))
            .addIssue("Minor driver warning")
            .addWorkaround("Disabled kernel caching")
            .build();

        var markdown = report.toMarkdown();

        // Header
        assertTrue(markdown.contains("# Vendor Test Report: NVIDIA"));
        assertTrue(markdown.contains("**Device**: RTX 4090"));
        assertTrue(markdown.contains("**Date**: 2026-01-15"));
        assertTrue(markdown.contains("**Driver**: 535.154.05"));
        assertTrue(markdown.contains("**OpenCL**: OpenCL 3.0"));

        // Test Summary table
        assertTrue(markdown.contains("## Test Summary"));
        assertTrue(markdown.contains("| Total Tests | 12 |"));
        assertTrue(markdown.contains("| Passed | 10 |"));
        assertTrue(markdown.contains("| Pass Rate | 100.0% |"));
        assertTrue(markdown.contains("PASSED"));

        // Performance table
        assertTrue(markdown.contains("## Performance Comparison"));
        assertTrue(markdown.contains("| dag_ray_traversal |"));
        assertTrue(markdown.contains("+5.0%"));

        // Issues
        assertTrue(markdown.contains("## Issues"));
        assertTrue(markdown.contains("- Minor driver warning"));

        // Workarounds
        assertTrue(markdown.contains("## Applied Workarounds"));
        assertTrue(markdown.contains("- Disabled kernel caching"));

        // Footer
        assertTrue(markdown.contains("Generated by VendorTestReport"));
    }

    @Test
    void testToMarkdownNoPerformanceData() {
        var report = VendorTestReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .summary(10, 0, 0, 5000)
            .build();

        var markdown = report.toMarkdown();

        // Should not have performance section
        assertFalse(markdown.contains("## Performance Comparison"));
    }

    @Test
    void testToMarkdownNoIssuesOrWorkarounds() {
        var report = VendorTestReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .summary(10, 0, 0, 5000)
            .build();

        var markdown = report.toMarkdown();

        // Should not have issues or workarounds sections
        assertFalse(markdown.contains("## Issues"));
        assertFalse(markdown.contains("## Applied Workarounds"));
    }

    @Test
    void testToMarkdownFailedTests() {
        var report = VendorTestReport.builder()
            .vendor(GPUVendor.AMD)
            .deviceName("RX 6900 XT")
            .summary(8, 2, 0, 5000)
            .build();

        var markdown = report.toMarkdown();

        assertTrue(markdown.contains("| Failed | 2 |"));
        assertTrue(markdown.contains("FAILED"));
    }

    private VendorTestReport createTestReport() {
        return VendorTestReport.builder()
            .runDate(LocalDateTime.of(2026, 1, 15, 10, 30))
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .driverVersion("535.154.05")
            .openCLVersion("OpenCL 3.0")
            .summary(10, 0, 2, 5000)
            .build();
    }
}
