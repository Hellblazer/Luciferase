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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * P4.3: Multi-Vendor GPU Consistency Report Generator
 *
 * Aggregates test results across multiple GPU vendors and generates
 * consistency metrics for validation against >90% target.
 *
 * Reports include:
 * - Pass/fail counts per vendor
 * - Overall consistency percentage
 * - Vendor-specific failures with workarounds
 * - Baseline comparison (NVIDIA)
 *
 * @author hal.hildebrand
 */
public class MultiVendorConsistencyReport {

    private final Map<String, VendorResult> results = new LinkedHashMap<>();
    private final LocalDateTime timestamp = LocalDateTime.now();

    /**
     * Vendor test result record
     *
     * @param vendor GPU vendor
     * @param model GPU model name
     * @param testsRun Total tests executed
     * @param testsPassed Tests that passed
     * @param testsFailed Tests that failed
     * @param failedTests List of failed test names
     * @param appliedWorkarounds List of workarounds applied
     */
    public record VendorResult(
        GPUVendor vendor,
        String model,
        int testsRun,
        int testsPassed,
        int testsFailed,
        List<String> failedTests,
        List<String> appliedWorkarounds
    ) {
        /**
         * Calculate pass percentage for this vendor
         */
        public double passPercentage() {
            if (testsRun == 0) return 0.0;
            return (testsPassed * 100.0) / testsRun;
        }

        /**
         * Check if this vendor meets target consistency
         */
        public boolean meetsTarget(double threshold) {
            return passPercentage() >= threshold;
        }
    }

    /**
     * Add vendor test result
     */
    public void addResult(VendorResult result) {
        var key = result.vendor().name() + "_" + result.model();
        results.put(key, result);
    }

    /**
     * Get all vendor results
     */
    public Collection<VendorResult> getResults() {
        return Collections.unmodifiableCollection(results.values());
    }

    /**
     * Calculate overall consistency score across all vendors
     *
     * @return Percentage of tests passed (0.0 to 100.0)
     */
    public double getOverallConsistency() {
        var totalTests = results.values().stream()
            .mapToInt(VendorResult::testsRun)
            .sum();

        var totalPassed = results.values().stream()
            .mapToInt(VendorResult::testsPassed)
            .sum();

        if (totalTests == 0) return 0.0;
        return (totalPassed * 100.0) / totalTests;
    }

    /**
     * Check if overall consistency meets target threshold
     *
     * @param threshold Target percentage (e.g., 90.0 for 90%)
     * @return true if consistency >= threshold
     */
    public boolean meetsTarget(double threshold) {
        return getOverallConsistency() >= threshold;
    }

    /**
     * Generate formatted consistency report
     *
     * @return Markdown-formatted report
     */
    public String generateReport() {
        var sb = new StringBuilder();

        // Header
        sb.append("# Multi-Vendor GPU Consistency Report\n\n");
        sb.append("**Generated**: ").append(formatTimestamp()).append("\n\n");
        sb.append("---\n\n");

        // Overall Summary
        var consistency = getOverallConsistency();
        var totalTests = results.values().stream().mapToInt(VendorResult::testsRun).sum();
        var totalPassed = results.values().stream().mapToInt(VendorResult::testsPassed).sum();

        sb.append("## Overall Summary\n\n");
        sb.append(String.format("- **Total Tests**: %d\n", totalTests));
        sb.append(String.format("- **Total Passed**: %d\n", totalPassed));
        sb.append(String.format("- **Total Failed**: %d\n", totalTests - totalPassed));
        sb.append(String.format("- **Consistency**: %.1f%%\n", consistency));
        sb.append(String.format("- **Target**: 90.0%%\n"));
        sb.append(String.format("- **Status**: %s\n\n",
            meetsTarget(90.0) ? "✅ TARGET MET" : "❌ BELOW TARGET"));

        // Vendor Results
        sb.append("## Vendor Results\n\n");
        sb.append("| Vendor | Model | Tests | Passed | Failed | Consistency |\n");
        sb.append("|--------|-------|-------|--------|--------|-------------|\n");

        for (var result : results.values()) {
            var status = result.passPercentage() == 100.0 ? "✅" : "⚠️";
            sb.append(String.format("| %s | %s | %d | %d | %d | %.1f%% %s |\n",
                result.vendor().name(),
                result.model(),
                result.testsRun(),
                result.testsPassed(),
                result.testsFailed(),
                result.passPercentage(),
                status));
        }

        sb.append("\n");

        // Vendor-Specific Failures
        sb.append("## Vendor-Specific Issues\n\n");

        var hasFailures = false;
        for (var result : results.values()) {
            if (!result.failedTests().isEmpty()) {
                hasFailures = true;
                sb.append(String.format("### %s - %s\n\n", result.vendor().name(), result.model()));
                sb.append(String.format("**Failures**: %d/%d tests (%.1f%% failure rate)\n\n",
                    result.testsFailed(),
                    result.testsRun(),
                    (result.testsFailed() * 100.0) / result.testsRun()));

                sb.append("**Failed Tests**:\n");
                for (var test : result.failedTests()) {
                    sb.append(String.format("- `%s`\n", test));
                }
                sb.append("\n");

                if (!result.appliedWorkarounds().isEmpty()) {
                    sb.append("**Applied Workarounds**:\n");
                    for (var workaround : result.appliedWorkarounds()) {
                        sb.append(String.format("- %s\n", workaround));
                    }
                    sb.append("\n");
                }
            }
        }

        if (!hasFailures) {
            sb.append("**No failures detected** - All vendors passed 100% of tests ✅\n\n");
        }

        // Recommendations
        sb.append("## Recommendations\n\n");

        if (consistency >= 95.0) {
            sb.append("✅ **Excellent consistency** - Multi-vendor support is production-ready.\n\n");
        } else if (consistency >= 90.0) {
            sb.append("✅ **Good consistency** - Target met, minor vendor-specific issues documented.\n\n");
        } else if (consistency >= 85.0) {
            sb.append("⚠️ **Below target** - Review vendor-specific failures and improve workarounds.\n\n");
        } else {
            sb.append("❌ **Poor consistency** - Significant vendor issues require investigation.\n\n");
        }

        // Footer
        sb.append("---\n\n");
        sb.append("*Generated by MultiVendorConsistencyReport - P4.3 GPU Performance Validation*\n");

        return sb.toString();
    }

    /**
     * Generate summary line for quick status check
     *
     * @return Single-line summary
     */
    public String generateSummary() {
        var consistency = getOverallConsistency();
        var status = meetsTarget(90.0) ? "✅ TARGET MET" : "❌ BELOW TARGET";
        var totalTests = results.values().stream().mapToInt(VendorResult::testsRun).sum();
        var totalPassed = results.values().stream().mapToInt(VendorResult::testsPassed).sum();

        return String.format("Multi-Vendor Consistency: %d/%d (%.1f%%) %s",
            totalPassed, totalTests, consistency, status);
    }

    /**
     * Get vendor-specific result
     *
     * @param vendor GPU vendor
     * @param model GPU model name
     * @return VendorResult or null if not found
     */
    public VendorResult getResult(GPUVendor vendor, String model) {
        var key = vendor.name() + "_" + model;
        return results.get(key);
    }

    /**
     * Get all results for a specific vendor
     *
     * @param vendor GPU vendor
     * @return List of results for this vendor
     */
    public List<VendorResult> getResultsForVendor(GPUVendor vendor) {
        return results.values().stream()
            .filter(r -> r.vendor() == vendor)
            .toList();
    }

    /**
     * Check if any vendor has failures
     *
     * @return true if at least one vendor has failures
     */
    public boolean hasFailures() {
        return results.values().stream()
            .anyMatch(r -> r.testsFailed() > 0);
    }

    /**
     * Get list of all failed tests across all vendors
     *
     * @return Set of unique failed test names
     */
    public Set<String> getAllFailedTests() {
        var allFailures = new HashSet<String>();
        for (var result : results.values()) {
            allFailures.addAll(result.failedTests());
        }
        return allFailures;
    }

    /**
     * Get vendors that meet the target threshold
     *
     * @param threshold Target percentage (e.g., 90.0)
     * @return List of vendors meeting target
     */
    public List<VendorResult> getVendorsMeetingTarget(double threshold) {
        return results.values().stream()
            .filter(r -> r.meetsTarget(threshold))
            .toList();
    }

    /**
     * Get vendors below the target threshold
     *
     * @param threshold Target percentage (e.g., 90.0)
     * @return List of vendors below target
     */
    public List<VendorResult> getVendorsBelowTarget(double threshold) {
        return results.values().stream()
            .filter(r -> !r.meetsTarget(threshold))
            .toList();
    }

    /**
     * Format timestamp for report header
     */
    private String formatTimestamp() {
        return timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * Builder for creating vendor results
     */
    public static class VendorResultBuilder {
        private GPUVendor vendor;
        private String model;
        private int testsRun;
        private int testsPassed;
        private final List<String> failedTests = new ArrayList<>();
        private final List<String> appliedWorkarounds = new ArrayList<>();

        public VendorResultBuilder vendor(GPUVendor vendor) {
            this.vendor = vendor;
            return this;
        }

        public VendorResultBuilder model(String model) {
            this.model = model;
            return this;
        }

        public VendorResultBuilder testsRun(int testsRun) {
            this.testsRun = testsRun;
            return this;
        }

        public VendorResultBuilder testsPassed(int testsPassed) {
            this.testsPassed = testsPassed;
            return this;
        }

        public VendorResultBuilder addFailedTest(String testName) {
            this.failedTests.add(testName);
            return this;
        }

        public VendorResultBuilder addWorkaround(String workaround) {
            this.appliedWorkarounds.add(workaround);
            return this;
        }

        public VendorResult build() {
            var testsFailed = testsRun - testsPassed;
            return new VendorResult(
                vendor,
                model,
                testsRun,
                testsPassed,
                testsFailed,
                List.copyOf(failedTests),
                List.copyOf(appliedWorkarounds)
            );
        }
    }

    /**
     * Create a new vendor result builder
     */
    public static VendorResultBuilder builder() {
        return new VendorResultBuilder();
    }
}
