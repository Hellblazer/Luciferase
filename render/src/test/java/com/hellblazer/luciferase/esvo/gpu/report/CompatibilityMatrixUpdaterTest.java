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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.hellblazer.luciferase.esvo.gpu.report.CompatibilityMatrixUpdater.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CompatibilityMatrixUpdater D4 implementation.
 *
 * @author hal.hildebrand
 */
class CompatibilityMatrixUpdaterTest {

    @TempDir
    Path tempDir;

    @Test
    void testStatusConstants() {
        assertEquals("\u2705", STATUS_PASSING);
        assertEquals("\u26A0\uFE0F", STATUS_PARTIAL);
        assertEquals("\u274C", STATUS_FAILING);
        assertEquals("\u26AA", STATUS_NOT_TESTED);
    }

    @Test
    void testVendorTestStatusFromReportAllPassed() {
        var report = VendorTestReport.builder()
            .runDate(LocalDateTime.of(2026, 1, 15, 10, 30))
            .vendor(GPUVendor.NVIDIA)
            .deviceName("RTX 4090")
            .summary(10, 0, 0, 5000)
            .addPerformance(new VendorTestReport.PerformanceComparison(
                "kernel", 1_000_000.0, 1_040_000.0, 4.0, true
            ))
            .build();

        var status = VendorTestStatus.fromReport(report);

        assertEquals(GPUVendor.NVIDIA, status.vendor());
        assertEquals("RTX 4090", status.devicePattern());
        assertEquals(STATUS_PASSING, status.status());
        assertEquals("All tests pass", status.notes());
        assertEquals(LocalDate.of(2026, 1, 15), status.testDate());
    }

    @Test
    void testVendorTestStatusFromReportPartialPass() {
        var report = VendorTestReport.builder()
            .runDate(LocalDateTime.of(2026, 1, 15, 10, 30))
            .vendor(GPUVendor.AMD)
            .deviceName("RX 6900 XT")
            .summary(9, 1, 0, 5000)  // 90% pass rate
            .build();

        var status = VendorTestStatus.fromReport(report);

        assertEquals(STATUS_PARTIAL, status.status());
        assertTrue(status.notes().contains("90%"));
    }

    @Test
    void testVendorTestStatusFromReportFailing() {
        var report = VendorTestReport.builder()
            .runDate(LocalDateTime.of(2026, 1, 15, 10, 30))
            .vendor(GPUVendor.INTEL)
            .deviceName("Arc A770")
            .summary(5, 5, 0, 5000)  // 50% pass rate
            .build();

        var status = VendorTestStatus.fromReport(report);

        assertEquals(STATUS_FAILING, status.status());
        assertTrue(status.notes().contains("5 failures"));
    }

    @Test
    void testGenerateTable() {
        var results = List.of(
            new VendorTestStatus(
                GPUVendor.NVIDIA, "RTX 4090", STATUS_PASSING, "All tests pass", LocalDate.of(2026, 1, 15)
            ),
            new VendorTestStatus(
                GPUVendor.AMD, "RX 6900 XT", STATUS_PARTIAL, "90% pass rate", LocalDate.of(2026, 1, 14)
            ),
            new VendorTestStatus(
                GPUVendor.INTEL, "Arc A770", STATUS_FAILING, "5 failures", LocalDate.of(2026, 1, 13)
            )
        );

        var table = CompatibilityMatrixUpdater.generateTable(results);

        // Header
        assertTrue(table.contains("| Vendor | Device | Status | Notes | Test Date |"));
        assertTrue(table.contains("|--------|--------|--------|-------|----------|"));

        // Data rows
        assertTrue(table.contains("| NVIDIA | RTX 4090 |"));
        assertTrue(table.contains("| AMD | RX 6900 XT |"));
        assertTrue(table.contains("| INTEL | Arc A770 |"));

        // Status symbols
        assertTrue(table.contains(STATUS_PASSING));
        assertTrue(table.contains(STATUS_PARTIAL));
        assertTrue(table.contains(STATUS_FAILING));

        // Dates
        assertTrue(table.contains("2026-01-15"));
        assertTrue(table.contains("2026-01-14"));
        assertTrue(table.contains("2026-01-13"));
    }

    @Test
    void testUpdateVendorStatus() throws IOException {
        var matrixContent = """
            # GPU Compatibility Matrix

            **Last Updated**: 2026-01-01

            | Vendor | Device | Status | Notes | Test Date |
            |--------|--------|--------|-------|----------|
            | NVIDIA | RTX 4090 | ⚪ Not Tested | - | - |
            | AMD | RX 6900 XT | ⚪ Not Tested | - | - |
            """;

        var matrixPath = tempDir.resolve("test_matrix.md");
        Files.writeString(matrixPath, matrixContent);

        var updater = new CompatibilityMatrixUpdater(matrixPath);
        var status = new VendorTestStatus(
            GPUVendor.NVIDIA, "RTX 4090", STATUS_PASSING, "All tests pass", LocalDate.of(2026, 1, 15)
        );

        var updated = updater.updateVendorStatus(matrixContent, status);

        // NVIDIA row should be updated
        assertTrue(updated.contains(STATUS_PASSING));
        assertTrue(updated.contains("All tests pass"));
        assertTrue(updated.contains("2026-01-15"));

        // AMD row should remain unchanged
        assertTrue(updated.contains("| AMD | RX 6900 XT | ⚪ Not Tested |"));
    }

    @Test
    void testUpdateLastUpdated() throws IOException {
        var matrixContent = """
            # GPU Compatibility Matrix

            **Last Updated**: 2025-06-01

            Some content here.
            """;

        var matrixPath = tempDir.resolve("test_matrix.md");
        Files.writeString(matrixPath, matrixContent);

        var updater = new CompatibilityMatrixUpdater(matrixPath);
        var updated = updater.updateLastUpdated(matrixContent);

        // Should contain today's date
        var today = LocalDate.now().toString();
        assertTrue(updated.contains("**Last Updated**: " + today));
        assertFalse(updated.contains("2025-06-01"));
    }

    @Test
    void testUpdateMatrixMultipleResults() throws IOException {
        var matrixContent = """
            # GPU Compatibility Matrix

            **Last Updated**: 2025-06-01

            | Vendor | Device | Status | Notes | Test Date |
            |--------|--------|--------|-------|----------|
            | NVIDIA | RTX 4090 | ⚪ Not Tested | - | - |
            | AMD | RX 6900 XT | ⚪ Not Tested | - | - |
            | INTEL | Arc A770 | ⚪ Not Tested | - | - |
            """;

        var matrixPath = tempDir.resolve("test_matrix.md");
        Files.writeString(matrixPath, matrixContent);

        var updater = new CompatibilityMatrixUpdater(matrixPath);
        var results = List.of(
            new VendorTestStatus(
                GPUVendor.NVIDIA, "RTX 4090", STATUS_PASSING, "All tests pass", LocalDate.of(2026, 1, 15)
            ),
            new VendorTestStatus(
                GPUVendor.AMD, "RX 6900 XT", STATUS_PARTIAL, "90% pass rate", LocalDate.of(2026, 1, 15)
            )
        );

        updater.updateMatrix(results);

        var updated = Files.readString(matrixPath);

        // Both NVIDIA and AMD should be updated
        assertTrue(updated.contains(STATUS_PASSING));
        assertTrue(updated.contains(STATUS_PARTIAL));

        // Intel should remain unchanged
        assertTrue(updated.contains("INTEL"));
        assertTrue(updated.contains("⚪"));

        // Last updated should be today
        var today = LocalDate.now().toString();
        assertTrue(updated.contains("**Last Updated**: " + today));
    }

    @Test
    void testUpdateMatrixNonExistentFile() throws IOException {
        var nonExistentPath = tempDir.resolve("nonexistent.md");
        var updater = new CompatibilityMatrixUpdater(nonExistentPath);

        // Should not throw, just log warning
        assertDoesNotThrow(() -> updater.updateMatrix(List.of()));
    }

    @Test
    void testAddDeviceToKnownDevices() throws IOException {
        var matrixContent = """
            # GPU Compatibility Matrix

            ## Known Tested Devices

            | Vendor | Device Name | Compute Units | Global Memory | OpenCL Version | Test Date | Status |
            |--------|-------------|---------------|---------------|----------------|-----------|--------|
            | NVIDIA | RTX 3080 | 68 | 10GB | OpenCL 3.0 | 2025-12-01 | ✅ |

            ## Other Section
            """;

        var matrixPath = tempDir.resolve("test_matrix.md");
        Files.writeString(matrixPath, matrixContent);

        var updater = new CompatibilityMatrixUpdater(matrixPath);
        var updated = updater.addDeviceToKnownDevices(
            matrixContent,
            GPUVendor.NVIDIA,
            "RTX 4090",
            128,
            "24GB",
            "OpenCL 3.0",
            STATUS_PASSING
        );

        assertTrue(updated.contains("| NVIDIA | RTX 4090 | 128 | 24GB | OpenCL 3.0 |"));
        assertTrue(updated.contains(STATUS_PASSING));
    }

    @Test
    void testAddDeviceToKnownDevices_AlreadyExists() throws IOException {
        var matrixContent = """
            # GPU Compatibility Matrix

            ## Known Tested Devices

            | Vendor | Device Name | Compute Units | Global Memory | OpenCL Version | Test Date | Status |
            |--------|-------------|---------------|---------------|----------------|-----------|--------|
            | NVIDIA | RTX 4090 | 128 | 24GB | OpenCL 3.0 | 2025-12-01 | ✅ |

            ## Other Section
            """;

        var matrixPath = tempDir.resolve("test_matrix.md");
        Files.writeString(matrixPath, matrixContent);

        var updater = new CompatibilityMatrixUpdater(matrixPath);
        var updated = updater.addDeviceToKnownDevices(
            matrixContent,
            GPUVendor.NVIDIA,
            "RTX 4090",  // Already exists
            128,
            "24GB",
            "OpenCL 3.0",
            STATUS_PASSING
        );

        // Should not add duplicate - count occurrences
        var count = updated.split("RTX 4090").length - 1;
        assertEquals(1, count, "Should not add duplicate device");
    }

    @Test
    void testUpdateVendorStatusWithExistingStatusSymbol() throws IOException {
        var matrixContent = """
            | Vendor | Device | Status | Notes | Test Date |
            |--------|--------|--------|-------|----------|
            | NVIDIA | RTX 4090 | ⚠️ Partial | 80% pass | 2025-12-01 |
            """;

        var matrixPath = tempDir.resolve("test_matrix.md");
        Files.writeString(matrixPath, matrixContent);

        var updater = new CompatibilityMatrixUpdater(matrixPath);
        var status = new VendorTestStatus(
            GPUVendor.NVIDIA, "RTX 4090", STATUS_PASSING, "All tests pass", LocalDate.of(2026, 1, 15)
        );

        var updated = updater.updateVendorStatus(matrixContent, status);

        // Should update from partial to passing
        assertTrue(updated.contains(STATUS_PASSING));
        assertTrue(updated.contains("All tests pass"));
        assertTrue(updated.contains("2026-01-15"));
        // Note: Old notes may remain in separate column depending on table structure
        assertFalse(updated.contains("⚠️"));  // Old status symbol should be replaced
    }

    @Test
    void testVendorTestStatusFromReportWithPerfFailure() {
        var report = VendorTestReport.builder()
            .runDate(LocalDateTime.of(2026, 1, 15, 10, 30))
            .vendor(GPUVendor.APPLE)
            .deviceName("M4 Max")
            .summary(10, 0, 0, 5000)  // All tests pass
            .addPerformance(new VendorTestReport.PerformanceComparison(
                "kernel", 1_000_000.0, 800_000.0, -20.0, false  // Performance outside tolerance
            ))
            .build();

        var status = VendorTestStatus.fromReport(report);

        // Should be partial because performance is outside tolerance
        assertEquals(STATUS_PARTIAL, status.status());
    }
}
