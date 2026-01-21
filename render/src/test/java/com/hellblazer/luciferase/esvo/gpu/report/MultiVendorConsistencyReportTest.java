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
package com.hellblazer.luciferase.esvo.gpu.report;

import com.hellblazer.luciferase.esvo.gpu.GPUVendor;
import com.hellblazer.luciferase.esvo.gpu.report.MultiVendorConsistencyReport.VendorResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.3: Multi-Vendor Consistency Report Tests
 *
 * Test coverage for consistency calculation, report generation, and metrics.
 *
 * @author hal.hildebrand
 */
@DisplayName("P4.3: Multi-Vendor Consistency Report Tests")
class MultiVendorConsistencyReportTest {

    // ==================== VendorResult Tests ====================

    @Test
    @DisplayName("VendorResult calculates pass percentage correctly")
    void testVendorResultPassPercentage() {
        var result = new VendorResult(
            GPUVendor.NVIDIA,
            "RTX 4090",
            50, // testsRun
            45, // testsPassed
            5,  // testsFailed
            List.of("test1", "test2"),
            List.of("workaround1")
        );

        assertEquals(90.0, result.passPercentage(), 0.01);
    }

    @Test
    @DisplayName("VendorResult handles 100% pass rate")
    void testVendorResult100Percent() {
        var result = new VendorResult(
            GPUVendor.NVIDIA,
            "RTX 4090",
            50,
            50,
            0,
            List.of(),
            List.of()
        );

        assertEquals(100.0, result.passPercentage(), 0.01);
        assertTrue(result.meetsTarget(90.0));
    }

    @Test
    @DisplayName("VendorResult handles zero tests")
    void testVendorResultZeroTests() {
        var result = new VendorResult(
            GPUVendor.NVIDIA,
            "RTX 4090",
            0,
            0,
            0,
            List.of(),
            List.of()
        );

        assertEquals(0.0, result.passPercentage(), 0.01);
        assertFalse(result.meetsTarget(90.0));
    }

    @Test
    @DisplayName("VendorResult meets target check")
    void testVendorResultMeetsTarget() {
        var result90 = new VendorResult(
            GPUVendor.AMD,
            "RX 6900 XT",
            100,
            90,
            10,
            List.of(),
            List.of()
        );

        var result89 = new VendorResult(
            GPUVendor.AMD,
            "RX 6700 XT",
            100,
            89,
            11,
            List.of(),
            List.of()
        );

        assertTrue(result90.meetsTarget(90.0));
        assertFalse(result89.meetsTarget(90.0));
    }

    // ==================== Overall Consistency Tests ====================

    @Test
    @DisplayName("Calculate overall consistency with single vendor")
    void testOverallConsistencySingleVendor() {
        var report = new MultiVendorConsistencyReport();

        var result = MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(50)
            .testsPassed(45)
            .build();

        report.addResult(result);

        assertEquals(90.0, report.getOverallConsistency(), 0.01);
    }

    @Test
    @DisplayName("Calculate overall consistency with multiple vendors")
    void testOverallConsistencyMultipleVendors() {
        var report = new MultiVendorConsistencyReport();

        // NVIDIA: 50/50 (100%)
        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(50)
            .testsPassed(50)
            .build());

        // AMD: 45/50 (90%)
        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.AMD)
            .model("RX 6900 XT")
            .testsRun(50)
            .testsPassed(45)
            .build());

        // Intel: 40/50 (80%)
        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.INTEL)
            .model("Arc A770")
            .testsRun(50)
            .testsPassed(40)
            .build());

        // Apple: 45/50 (90%)
        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.APPLE)
            .model("M2 Max")
            .testsRun(50)
            .testsPassed(45)
            .build());

        // Overall: (50 + 45 + 40 + 45) / 200 = 180/200 = 90%
        assertEquals(90.0, report.getOverallConsistency(), 0.01);
    }

    @Test
    @DisplayName("Meets target threshold check")
    void testMeetsTargetThreshold() {
        var report = new MultiVendorConsistencyReport();

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(100)
            .testsPassed(92)
            .build());

        assertTrue(report.meetsTarget(90.0));
        assertTrue(report.meetsTarget(92.0));
        assertFalse(report.meetsTarget(93.0));
    }

    // ==================== Report Generation Tests ====================

    @Test
    @DisplayName("Generate report with all vendors passing")
    void testGenerateReportAllPassing() {
        var report = new MultiVendorConsistencyReport();

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(50)
            .testsPassed(50)
            .build());

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.AMD)
            .model("RX 6900 XT")
            .testsRun(50)
            .testsPassed(50)
            .build());

        var markdown = report.generateReport();

        assertNotNull(markdown);
        assertTrue(markdown.contains("Multi-Vendor GPU Consistency Report"));
        assertTrue(markdown.contains("100.0%"));
        assertTrue(markdown.contains("✅ TARGET MET"));
        assertTrue(markdown.contains("No failures detected"));
    }

    @Test
    @DisplayName("Generate report with vendor failures")
    void testGenerateReportWithFailures() {
        var report = new MultiVendorConsistencyReport();

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(50)
            .testsPassed(50)
            .build());

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.AMD)
            .model("RX 6900 XT")
            .testsRun(50)
            .testsPassed(48)
            .addFailedTest("testAtomicOperationBoundaries")
            .addWorkaround("AMD_ATOMIC_WORKAROUND")
            .build());

        var markdown = report.generateReport();

        assertNotNull(markdown);
        assertTrue(markdown.contains("AMD"));
        assertTrue(markdown.contains("testAtomicOperationBoundaries"));
        assertTrue(markdown.contains("AMD_ATOMIC_WORKAROUND"));
        assertTrue(markdown.contains("Vendor-Specific Issues"));
    }

    @Test
    @DisplayName("Generate summary line")
    void testGenerateSummary() {
        var report = new MultiVendorConsistencyReport();

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(100)
            .testsPassed(95)
            .build());

        var summary = report.generateSummary();

        assertNotNull(summary);
        assertTrue(summary.contains("95/100"));
        assertTrue(summary.contains("95.0%"));
        assertTrue(summary.contains("✅ TARGET MET"));
    }

    // ==================== Query Methods Tests ====================

    @Test
    @DisplayName("Get vendor-specific result")
    void testGetVendorResult() {
        var report = new MultiVendorConsistencyReport();

        var nvidiaResult = MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(50)
            .testsPassed(50)
            .build();

        report.addResult(nvidiaResult);

        var retrieved = report.getResult(GPUVendor.NVIDIA, "RTX 4090");
        assertNotNull(retrieved);
        assertEquals(GPUVendor.NVIDIA, retrieved.vendor());
        assertEquals("RTX 4090", retrieved.model());
        assertEquals(50, retrieved.testsRun());
    }

    @Test
    @DisplayName("Get all results for vendor")
    void testGetResultsForVendor() {
        var report = new MultiVendorConsistencyReport();

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(50)
            .testsPassed(50)
            .build());

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 3060")
            .testsRun(50)
            .testsPassed(48)
            .build());

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.AMD)
            .model("RX 6900 XT")
            .testsRun(50)
            .testsPassed(45)
            .build());

        var nvidiaResults = report.getResultsForVendor(GPUVendor.NVIDIA);
        assertEquals(2, nvidiaResults.size());

        var amdResults = report.getResultsForVendor(GPUVendor.AMD);
        assertEquals(1, amdResults.size());
    }

    @Test
    @DisplayName("Check if report has failures")
    void testHasFailures() {
        var reportNoFailures = new MultiVendorConsistencyReport();
        reportNoFailures.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(50)
            .testsPassed(50)
            .build());

        assertFalse(reportNoFailures.hasFailures());

        var reportWithFailures = new MultiVendorConsistencyReport();
        reportWithFailures.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.AMD)
            .model("RX 6900 XT")
            .testsRun(50)
            .testsPassed(48)
            .addFailedTest("test1")
            .build());

        assertTrue(reportWithFailures.hasFailures());
    }

    @Test
    @DisplayName("Get all failed tests across vendors")
    void testGetAllFailedTests() {
        var report = new MultiVendorConsistencyReport();

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.AMD)
            .model("RX 6900 XT")
            .testsRun(50)
            .testsPassed(48)
            .addFailedTest("testAtomicOperations")
            .addFailedTest("testSharedMemory")
            .build());

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.INTEL)
            .model("Arc A770")
            .testsRun(50)
            .testsPassed(49)
            .addFailedTest("testRayPrecision")
            .build());

        var allFailed = report.getAllFailedTests();
        assertEquals(3, allFailed.size());
        assertTrue(allFailed.contains("testAtomicOperations"));
        assertTrue(allFailed.contains("testSharedMemory"));
        assertTrue(allFailed.contains("testRayPrecision"));
    }

    @Test
    @DisplayName("Get vendors meeting target")
    void testGetVendorsMeetingTarget() {
        var report = new MultiVendorConsistencyReport();

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(100)
            .testsPassed(95)
            .build());

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.AMD)
            .model("RX 6900 XT")
            .testsRun(100)
            .testsPassed(85)
            .build());

        var meetingTarget = report.getVendorsMeetingTarget(90.0);
        assertEquals(1, meetingTarget.size());
        assertEquals(GPUVendor.NVIDIA, meetingTarget.get(0).vendor());

        var belowTarget = report.getVendorsBelowTarget(90.0);
        assertEquals(1, belowTarget.size());
        assertEquals(GPUVendor.AMD, belowTarget.get(0).vendor());
    }

    // ==================== Builder Tests ====================

    @Test
    @DisplayName("VendorResultBuilder creates valid result")
    void testVendorResultBuilder() {
        var result = MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.AMD)
            .model("RX 6900 XT")
            .testsRun(50)
            .testsPassed(48)
            .addFailedTest("test1")
            .addFailedTest("test2")
            .addWorkaround("AMD_ATOMIC_WORKAROUND")
            .build();

        assertEquals(GPUVendor.AMD, result.vendor());
        assertEquals("RX 6900 XT", result.model());
        assertEquals(50, result.testsRun());
        assertEquals(48, result.testsPassed());
        assertEquals(2, result.testsFailed());
        assertEquals(2, result.failedTests().size());
        assertEquals(1, result.appliedWorkarounds().size());
    }

    @Test
    @DisplayName("VendorResultBuilder calculates testsFailed automatically")
    void testVendorResultBuilderAutoCalculatesFailed() {
        var result = MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.INTEL)
            .model("Arc A770")
            .testsRun(100)
            .testsPassed(97)
            .build();

        assertEquals(3, result.testsFailed());
    }
}
