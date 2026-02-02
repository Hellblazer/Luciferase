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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hellblazer.luciferase.esvo.gpu.GPUVendor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D4: Vendor Test Report for CI/CD artifact generation.
 * <p>
 * Captures complete test run results including:
 * - Test summary (passed/failed/skipped)
 * - Performance comparisons to baseline
 * - Issues encountered during testing
 * <p>
 * Reports are saved as JSON artifacts for GitHub Actions upload
 * and can be converted to markdown for documentation.
 *
 * @author hal.hildebrand
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VendorTestReport(
    LocalDateTime runDate,
    GPUVendor vendor,
    String deviceName,
    String driverVersion,
    String openCLVersion,
    TestSummary summary,
    Map<String, PerformanceComparison> performance,
    List<String> issues,
    List<String> appliedWorkarounds
) {
    private static final ObjectMapper MAPPER = createMapper();

    /**
     * Test execution summary
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestSummary(
        int passed,
        int failed,
        int skipped,
        long durationMs
    ) {
        public int total() {
            return passed + failed + skipped;
        }

        public double passRate() {
            var executed = passed + failed;
            return executed == 0 ? 100.0 : (passed * 100.0) / executed;
        }

        public boolean allPassed() {
            return failed == 0;
        }
    }

    /**
     * Performance comparison against baseline
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PerformanceComparison(
        String kernelName,
        double baselineRaysPerSec,
        double measuredRaysPerSec,
        double deltaPercent,
        boolean withinTolerance
    ) {
        /**
         * Create comparison from baseline and measured metrics
         */
        public static PerformanceComparison create(
            String kernelName,
            PerformanceBaseline.KernelMetrics baseline,
            PerformanceBaseline.KernelMetrics measured,
            double tolerance
        ) {
            var deltaPercent = baseline.percentageDifference(measured);
            var withinTolerance = baseline.isWithinTolerance(measured, tolerance);

            return new PerformanceComparison(
                kernelName,
                baseline.raysPerSecond(),
                measured.raysPerSecond(),
                deltaPercent,
                withinTolerance
            );
        }

        /**
         * Format delta as string with sign
         */
        public String formatDelta() {
            var sign = deltaPercent >= 0 ? "+" : "";
            return String.format("%s%.1f%%", sign, deltaPercent);
        }

        /**
         * Get status emoji for delta
         */
        public String statusEmoji() {
            if (withinTolerance) {
                return deltaPercent >= 0 ? "✅" : "⚠️";
            }
            return "❌";
        }
    }

    /**
     * Check if this report indicates success
     */
    public boolean isSuccess() {
        return summary.allPassed() && allPerformanceWithinTolerance();
    }

    /**
     * Check if all performance comparisons are within tolerance
     */
    public boolean allPerformanceWithinTolerance() {
        if (performance == null || performance.isEmpty()) return true;
        return performance.values().stream().allMatch(PerformanceComparison::withinTolerance);
    }

    /**
     * Save report as JSON to file
     */
    public void saveAsJson(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), this);
    }

    /**
     * Convert to JSON string
     */
    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }

    /**
     * Generate markdown report
     */
    public String toMarkdown() {
        var sb = new StringBuilder();

        // Header
        sb.append("# Vendor Test Report: ").append(vendor.name()).append("\n\n");
        sb.append("**Device**: ").append(deviceName).append("\n");
        sb.append("**Date**: ").append(formatDate(runDate)).append("\n");
        sb.append("**Driver**: ").append(driverVersion != null ? driverVersion : "N/A").append("\n");
        sb.append("**OpenCL**: ").append(openCLVersion != null ? openCLVersion : "N/A").append("\n\n");

        // Test Summary
        sb.append("## Test Summary\n\n");
        sb.append(String.format("| Metric | Value |\n"));
        sb.append(String.format("|--------|-------|\n"));
        sb.append(String.format("| Total Tests | %d |\n", summary.total()));
        sb.append(String.format("| Passed | %d |\n", summary.passed()));
        sb.append(String.format("| Failed | %d |\n", summary.failed()));
        sb.append(String.format("| Skipped | %d |\n", summary.skipped()));
        sb.append(String.format("| Pass Rate | %.1f%% |\n", summary.passRate()));
        sb.append(String.format("| Duration | %dms |\n", summary.durationMs()));
        sb.append(String.format("| Status | %s |\n\n", summary.allPassed() ? "✅ PASSED" : "❌ FAILED"));

        // Performance Comparisons
        if (performance != null && !performance.isEmpty()) {
            sb.append("## Performance Comparison\n\n");
            sb.append("| Kernel | Baseline | Measured | Delta | Status |\n");
            sb.append("|--------|----------|----------|-------|--------|\n");

            for (var comp : performance.values()) {
                sb.append(String.format("| %s | %.0f | %.0f | %s | %s |\n",
                    comp.kernelName(),
                    comp.baselineRaysPerSec(),
                    comp.measuredRaysPerSec(),
                    comp.formatDelta(),
                    comp.statusEmoji()
                ));
            }
            sb.append("\n");
        }

        // Issues
        if (issues != null && !issues.isEmpty()) {
            sb.append("## Issues\n\n");
            for (var issue : issues) {
                sb.append("- ").append(issue).append("\n");
            }
            sb.append("\n");
        }

        // Workarounds
        if (appliedWorkarounds != null && !appliedWorkarounds.isEmpty()) {
            sb.append("## Applied Workarounds\n\n");
            for (var workaround : appliedWorkarounds) {
                sb.append("- ").append(workaround).append("\n");
            }
            sb.append("\n");
        }

        // Footer
        sb.append("---\n");
        sb.append("*Generated by VendorTestReport - D4 Performance Baseline Collection*\n");

        return sb.toString();
    }

    /**
     * Create a builder for constructing reports
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for VendorTestReport
     */
    public static class Builder {
        private LocalDateTime runDate = LocalDateTime.now();
        private GPUVendor vendor;
        private String deviceName;
        private String driverVersion;
        private String openCLVersion;
        private TestSummary summary;
        private final Map<String, PerformanceComparison> performance = new LinkedHashMap<>();
        private final List<String> issues = new ArrayList<>();
        private final List<String> appliedWorkarounds = new ArrayList<>();

        public Builder runDate(LocalDateTime runDate) {
            this.runDate = runDate;
            return this;
        }

        public Builder vendor(GPUVendor vendor) {
            this.vendor = vendor;
            return this;
        }

        public Builder deviceName(String deviceName) {
            this.deviceName = deviceName;
            return this;
        }

        public Builder driverVersion(String driverVersion) {
            this.driverVersion = driverVersion;
            return this;
        }

        public Builder openCLVersion(String openCLVersion) {
            this.openCLVersion = openCLVersion;
            return this;
        }

        public Builder summary(TestSummary summary) {
            this.summary = summary;
            return this;
        }

        public Builder summary(int passed, int failed, int skipped, long durationMs) {
            this.summary = new TestSummary(passed, failed, skipped, durationMs);
            return this;
        }

        public Builder addPerformance(PerformanceComparison comparison) {
            this.performance.put(comparison.kernelName(), comparison);
            return this;
        }

        public Builder addIssue(String issue) {
            this.issues.add(issue);
            return this;
        }

        public Builder addWorkaround(String workaround) {
            this.appliedWorkarounds.add(workaround);
            return this;
        }

        public VendorTestReport build() {
            return new VendorTestReport(
                runDate,
                vendor,
                deviceName,
                driverVersion,
                openCLVersion,
                summary,
                Map.copyOf(performance),
                List.copyOf(issues),
                List.copyOf(appliedWorkarounds)
            );
        }
    }

    private static String formatDate(LocalDateTime dt) {
        return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static ObjectMapper createMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
