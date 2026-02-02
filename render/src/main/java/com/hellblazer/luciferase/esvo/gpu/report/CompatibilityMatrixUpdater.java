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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * D4: Updates GPU_COMPATIBILITY_MATRIX.md with test results.
 * <p>
 * Parses markdown tables and updates vendor rows based on test outcomes.
 * Preserves document structure while updating status symbols and dates.
 * <p>
 * Status symbols:
 * - ✅ Tested & Passing
 * - ⚠️ Partial Support (works with limitations)
 * - ❌ Not Working (tests fail)
 * - ⚪ Not Tested
 *
 * @author hal.hildebrand
 */
public class CompatibilityMatrixUpdater {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityMatrixUpdater.class);

    /** Status symbol for passing tests */
    public static final String STATUS_PASSING = "✅";
    /** Status symbol for partial support */
    public static final String STATUS_PARTIAL = "⚠️";
    /** Status symbol for failing tests */
    public static final String STATUS_FAILING = "❌";
    /** Status symbol for not tested */
    public static final String STATUS_NOT_TESTED = "⚪";

    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile(
        "^\\|\\s*([^|]+)\\s*\\|(.*)$"
    );

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Test result for updating matrix
     */
    public record VendorTestStatus(
        GPUVendor vendor,
        String devicePattern,
        String status,
        String notes,
        LocalDate testDate
    ) {
        /**
         * Create from VendorTestReport
         */
        public static VendorTestStatus fromReport(VendorTestReport report) {
            String status;
            String notes;

            if (report.summary().allPassed() && report.allPerformanceWithinTolerance()) {
                status = STATUS_PASSING;
                notes = "All tests pass";
            } else if (report.summary().passRate() >= 90.0) {
                status = STATUS_PARTIAL;
                notes = String.format("%.0f%% pass rate", report.summary().passRate());
            } else {
                status = STATUS_FAILING;
                notes = String.format("%d failures", report.summary().failed());
            }

            return new VendorTestStatus(
                report.vendor(),
                report.deviceName(),
                status,
                notes,
                report.runDate().toLocalDate()
            );
        }
    }

    private final Path matrixPath;

    /**
     * Create updater for the specified matrix file
     *
     * @param matrixPath Path to GPU_COMPATIBILITY_MATRIX.md
     */
    public CompatibilityMatrixUpdater(Path matrixPath) {
        this.matrixPath = matrixPath;
    }

    /**
     * Update matrix with multiple vendor test results
     *
     * @param results List of test results
     * @throws IOException if file operations fail
     */
    public void updateMatrix(List<VendorTestStatus> results) throws IOException {
        if (!Files.exists(matrixPath)) {
            log.warn("Matrix file not found: {}", matrixPath);
            return;
        }

        var content = Files.readString(matrixPath);

        for (var result : results) {
            content = updateVendorStatus(content, result);
        }

        // Update last updated date
        content = updateLastUpdated(content);

        Files.writeString(matrixPath, content);
        log.info("Updated compatibility matrix: {}", matrixPath);
    }

    /**
     * Update a single vendor's status in the matrix
     *
     * @param content Current markdown content
     * @param status Vendor test status to apply
     * @return Updated markdown content
     */
    public String updateVendorStatus(String content, VendorTestStatus status) {
        var lines = content.split("\n");
        var updated = new ArrayList<String>();
        var vendorName = status.vendor().name();
        var devicePattern = status.devicePattern().toLowerCase();

        for (var line : lines) {
            if (isTableRow(line) && lineMatchesVendorDevice(line, vendorName, devicePattern)) {
                line = updateTableRowStatus(line, status);
            }
            updated.add(line);
        }

        return String.join("\n", updated);
    }

    /**
     * Update the "Last Updated" header in the matrix
     */
    public String updateLastUpdated(String content) {
        var today = LocalDate.now().format(DATE_FORMATTER);
        return content.replaceFirst(
            "\\*\\*Last Updated\\*\\*:\\s*\\d{4}-\\d{2}-\\d{2}",
            "**Last Updated**: " + today
        );
    }

    /**
     * Check if line is a markdown table row
     */
    private boolean isTableRow(String line) {
        return line.trim().startsWith("|") && line.trim().endsWith("|");
    }

    /**
     * Check if table row matches vendor and device pattern
     */
    private boolean lineMatchesVendorDevice(String line, String vendorName, String devicePattern) {
        var lower = line.toLowerCase();
        return lower.contains(vendorName.toLowerCase()) ||
               (devicePattern != null && lower.contains(devicePattern));
    }

    /**
     * Update status columns in a table row
     */
    private String updateTableRowStatus(String line, VendorTestStatus status) {
        var parts = line.split("\\|");
        if (parts.length < 4) return line;

        // Find and update status column (usually column 2 for simple tables)
        // Look for existing status symbols or "Not Tested" patterns
        for (int i = 1; i < parts.length; i++) {
            var cell = parts[i].trim();

            // Check if this cell contains a status symbol or "Not Tested"
            if (cell.contains(STATUS_NOT_TESTED) || cell.contains(STATUS_PASSING) ||
                cell.contains(STATUS_PARTIAL) || cell.contains(STATUS_FAILING) ||
                cell.equalsIgnoreCase("Not Tested")) {

                // Update status
                parts[i] = " " + status.status() + " " + status.notes() + " ";
                break;
            }
        }

        // Try to update date column (last column often)
        for (int i = parts.length - 1; i >= 1; i--) {
            var cell = parts[i].trim();
            if (cell.matches("\\d{4}-\\d{2}-\\d{2}") || cell.equals("-") || cell.isEmpty()) {
                parts[i] = " " + status.testDate().format(DATE_FORMATTER) + " ";
                break;
            }
        }

        return String.join("|", parts);
    }

    /**
     * Generate a new compatibility table section for a list of results
     *
     * @param results Test results to include
     * @return Markdown table string
     */
    public static String generateTable(List<VendorTestStatus> results) {
        var sb = new StringBuilder();

        sb.append("| Vendor | Device | Status | Notes | Test Date |\n");
        sb.append("|--------|--------|--------|-------|----------|\n");

        for (var result : results) {
            sb.append(String.format("| %s | %s | %s | %s | %s |\n",
                result.vendor().name(),
                result.devicePattern(),
                result.status(),
                result.notes(),
                result.testDate().format(DATE_FORMATTER)
            ));
        }

        return sb.toString();
    }

    /**
     * Add a new device row to the "Known Tested Devices" table
     *
     * @param content Current markdown content
     * @param vendor GPU vendor
     * @param deviceName Device name
     * @param computeUnits Number of compute units
     * @param globalMemory Memory in GB
     * @param openCLVersion OpenCL version
     * @param status Test status
     * @return Updated content with new row
     */
    public String addDeviceToKnownDevices(
        String content,
        GPUVendor vendor,
        String deviceName,
        int computeUnits,
        String globalMemory,
        String openCLVersion,
        String status
    ) {
        var today = LocalDate.now().format(DATE_FORMATTER);

        // Find "Known Tested Devices" table and add row before the closing pattern
        var newRow = String.format("| %s | %s | %d | %s | %s | %s | %s |",
            vendor.name(), deviceName, computeUnits, globalMemory, openCLVersion, today, status);

        // Look for the table and add row at the end
        var tablePattern = Pattern.compile(
            "(\\| Vendor \\| Device Name \\|[^\\n]*\\n\\|[-|]+\\n(?:\\|[^\\n]+\\n)*)"
        );

        var matcher = tablePattern.matcher(content);
        if (matcher.find()) {
            var existingTable = matcher.group(1);
            // Check if device already exists
            if (!existingTable.toLowerCase().contains(deviceName.toLowerCase())) {
                var updatedTable = existingTable + newRow + "\n";
                content = content.replace(existingTable, updatedTable);
            }
        }

        return content;
    }
}
