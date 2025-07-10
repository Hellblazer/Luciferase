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
package com.hellblazer.luciferase.lucien.report;

import org.junit.jupiter.api.extension.*;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JUnit 5 extension that generates comprehensive test reports for spatial index tests.
 * Tracks performance metrics, test outcomes, and generates HTML reports.
 * 
 * Usage: Add @ExtendWith(SpatialIndexTestReporter.class) to test classes
 * 
 * @author hal.hildebrand
 */
public class SpatialIndexTestReporter implements BeforeAllCallback, AfterAllCallback, 
                                                 BeforeEachCallback, AfterEachCallback,
                                                 TestWatcher {
    
    private static final Path REPORT_DIR = Paths.get("target", "spatial-index-reports");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    
    // Test execution tracking
    private final Map<String, TestResult> testResults = new ConcurrentHashMap<>();
    private final Map<String, Long> testStartTimes = new ConcurrentHashMap<>();
    private final AtomicInteger totalTests = new AtomicInteger();
    private final AtomicInteger passedTests = new AtomicInteger();
    private final AtomicInteger failedTests = new AtomicInteger();
    private final AtomicInteger skippedTests = new AtomicInteger();
    private final AtomicLong totalDuration = new AtomicLong();
    
    // Performance metrics
    private final Map<String, PerformanceMetrics> performanceData = new ConcurrentHashMap<>();
    
    private LocalDateTime suiteStartTime;
    private String suiteName;
    
    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        suiteStartTime = LocalDateTime.now();
        suiteName = context.getDisplayName();
        
        // Create report directory
        Files.createDirectories(REPORT_DIR);
        
        System.out.println("=== Spatial Index Test Suite Started ===");
        System.out.println("Suite: " + suiteName);
        System.out.println("Start Time: " + suiteStartTime.format(TIMESTAMP_FORMAT));
    }
    
    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        var duration = Duration.between(suiteStartTime, LocalDateTime.now());
        
        System.out.println("\n=== Spatial Index Test Suite Completed ===");
        System.out.println("Total Tests: " + totalTests.get());
        System.out.println("Passed: " + passedTests.get());
        System.out.println("Failed: " + failedTests.get());
        System.out.println("Skipped: " + skippedTests.get());
        System.out.println("Duration: " + formatDuration(duration));
        
        generateHtmlReport();
        generateMarkdownReport();
        generateJsonReport();
    }
    
    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        var testName = getTestName(context);
        testStartTimes.put(testName, System.nanoTime());
        totalTests.incrementAndGet();
    }
    
    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        var testName = getTestName(context);
        var startTime = testStartTimes.remove(testName);
        if (startTime != null) {
            var duration = System.nanoTime() - startTime;
            totalDuration.addAndGet(duration);
            
            // Store performance metrics if available
            var metrics = context.getStore(ExtensionContext.Namespace.GLOBAL)
                   .get("performance_metrics", PerformanceMetrics.class);
            if (metrics != null) {
                performanceData.put(testName, metrics);
            }
        }
    }
    
    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        var testName = getTestName(context);
        testResults.put(testName, new TestResult(testName, TestStatus.SKIPPED, 0, reason.orElse("Disabled")));
        skippedTests.incrementAndGet();
    }
    
    @Override
    public void testSuccessful(ExtensionContext context) {
        var testName = getTestName(context);
        var duration = System.nanoTime() - testStartTimes.getOrDefault(testName, System.nanoTime());
        testResults.put(testName, new TestResult(testName, TestStatus.PASSED, duration, null));
        passedTests.incrementAndGet();
    }
    
    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        var testName = getTestName(context);
        var duration = System.nanoTime() - testStartTimes.getOrDefault(testName, System.nanoTime());
        testResults.put(testName, new TestResult(testName, TestStatus.ABORTED, duration, cause.getMessage()));
        skippedTests.incrementAndGet();
    }
    
    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        var testName = getTestName(context);
        var duration = System.nanoTime() - testStartTimes.getOrDefault(testName, System.nanoTime());
        testResults.put(testName, new TestResult(testName, TestStatus.FAILED, duration, cause.getMessage()));
        failedTests.incrementAndGet();
    }
    
    private String getTestName(ExtensionContext context) {
        return context.getTestClass().map(Class::getSimpleName).orElse("Unknown") + 
               "." + 
               context.getTestMethod().map(m -> m.getName()).orElse("unknown");
    }
    
    private void generateHtmlReport() throws IOException {
        var reportPath = REPORT_DIR.resolve("spatial-index-test-report_" + 
                                           suiteStartTime.format(TIMESTAMP_FORMAT) + ".html");
        
        try (var writer = new FileWriter(reportPath.toFile())) {
            writer.write("""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Spatial Index Test Report</title>
                    <style>
                        body { font-family: Arial, sans-serif; margin: 20px; }
                        .summary { background: #f0f0f0; padding: 15px; border-radius: 5px; }
                        .passed { color: green; }
                        .failed { color: red; }
                        .skipped { color: orange; }
                        table { border-collapse: collapse; width: 100%; margin-top: 20px; }
                        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                        th { background-color: #4CAF50; color: white; }
                        tr:nth-child(even) { background-color: #f2f2f2; }
                        .performance { margin-top: 30px; }
                        .chart { width: 100%; height: 400px; margin-top: 20px; }
                    </style>
                    <script src="https://cdn.plot.ly/plotly-latest.min.js"></script>
                </head>
                <body>
                    <h1>Spatial Index Test Report</h1>
                    <p>Generated: %s</p>
                    
                    <div class="summary">
                        <h2>Test Summary</h2>
                        <p>Total Tests: %d</p>
                        <p class="passed">Passed: %d (%.1f%%)</p>
                        <p class="failed">Failed: %d (%.1f%%)</p>
                        <p class="skipped">Skipped: %d (%.1f%%)</p>
                        <p>Total Duration: %s</p>
                    </div>
                    
                    <h2>Test Results</h2>
                    <table>
                        <tr>
                            <th>Test Name</th>
                            <th>Status</th>
                            <th>Duration</th>
                            <th>Details</th>
                        </tr>
                        %s
                    </table>
                    
                    %s
                </body>
                </html>
                """.formatted(
                    LocalDateTime.now().format(TIMESTAMP_FORMAT),
                    totalTests.get(),
                    passedTests.get(), 
                    totalTests.get() > 0 ? (passedTests.get() * 100.0 / totalTests.get()) : 0,
                    failedTests.get(),
                    totalTests.get() > 0 ? (failedTests.get() * 100.0 / totalTests.get()) : 0,
                    skippedTests.get(),
                    totalTests.get() > 0 ? (skippedTests.get() * 100.0 / totalTests.get()) : 0,
                    formatDuration(Duration.ofNanos(totalDuration.get())),
                    generateTestResultRows(),
                    generatePerformanceSection()
                ));
        }
        
        System.out.println("\nHTML report generated: " + reportPath);
    }
    
    private void generateMarkdownReport() throws IOException {
        var reportPath = REPORT_DIR.resolve("spatial-index-test-report_" + 
                                           suiteStartTime.format(TIMESTAMP_FORMAT) + ".md");
        
        try (var writer = new FileWriter(reportPath.toFile())) {
            writer.write("""
                # Spatial Index Test Report
                
                Generated: %s
                
                ## Test Summary
                
                | Metric | Value | Percentage |
                |--------|-------|------------|
                | Total Tests | %d | 100%% |
                | Passed | %d | %.1f%% |
                | Failed | %d | %.1f%% |
                | Skipped | %d | %.1f%% |
                | Duration | %s | - |
                
                ## Test Results
                
                | Test Name | Status | Duration | Details |
                |-----------|--------|----------|---------|
                %s
                
                %s
                """.formatted(
                    LocalDateTime.now().format(TIMESTAMP_FORMAT),
                    totalTests.get(),
                    passedTests.get(), 
                    totalTests.get() > 0 ? (passedTests.get() * 100.0 / totalTests.get()) : 0,
                    failedTests.get(),
                    totalTests.get() > 0 ? (failedTests.get() * 100.0 / totalTests.get()) : 0,
                    skippedTests.get(),
                    totalTests.get() > 0 ? (skippedTests.get() * 100.0 / totalTests.get()) : 0,
                    formatDuration(Duration.ofNanos(totalDuration.get())),
                    generateMarkdownTestRows(),
                    generateMarkdownPerformanceSection()
                ));
        }
        
        System.out.println("Markdown report generated: " + reportPath);
    }
    
    private void generateJsonReport() throws IOException {
        var reportPath = REPORT_DIR.resolve("spatial-index-test-report_" + 
                                           suiteStartTime.format(TIMESTAMP_FORMAT) + ".json");
        
        var report = Map.of(
            "suite", suiteName,
            "timestamp", LocalDateTime.now().toString(),
            "summary", Map.of(
                "total", totalTests.get(),
                "passed", passedTests.get(),
                "failed", failedTests.get(),
                "skipped", skippedTests.get(),
                "duration_ms", totalDuration.get() / 1_000_000
            ),
            "results", testResults,
            "performance", performanceData
        );
        
        // Simple JSON serialization (in production, use Jackson or Gson)
        try (var writer = new FileWriter(reportPath.toFile())) {
            writer.write(toJson(report));
        }
        
        System.out.println("JSON report generated: " + reportPath);
    }
    
    private String generateTestResultRows() {
        var sb = new StringBuilder();
        testResults.values().stream()
            .sorted(Comparator.comparing(TestResult::name))
            .forEach(result -> {
                sb.append("<tr>")
                  .append("<td>").append(result.name()).append("</td>")
                  .append("<td class=\"").append(result.status().name().toLowerCase()).append("\">")
                  .append(result.status()).append("</td>")
                  .append("<td>").append(formatDuration(Duration.ofNanos(result.duration()))).append("</td>")
                  .append("<td>").append(result.details() != null ? result.details() : "-").append("</td>")
                  .append("</tr>\n");
            });
        return sb.toString();
    }
    
    private String generateMarkdownTestRows() {
        var sb = new StringBuilder();
        testResults.values().stream()
            .sorted(Comparator.comparing(TestResult::name))
            .forEach(result -> {
                sb.append("| ").append(result.name())
                  .append(" | ").append(result.status())
                  .append(" | ").append(formatDuration(Duration.ofNanos(result.duration())))
                  .append(" | ").append(result.details() != null ? result.details() : "-")
                  .append(" |\n");
            });
        return sb.toString();
    }
    
    private String generatePerformanceSection() {
        if (performanceData.isEmpty()) {
            return "";
        }
        
        return """
            <div class="performance">
                <h2>Performance Metrics</h2>
                <div id="performanceChart" class="chart"></div>
                <script>
                    // Add performance visualization here
                </script>
            </div>
            """;
    }
    
    private String generateMarkdownPerformanceSection() {
        if (performanceData.isEmpty()) {
            return "";
        }
        
        var sb = new StringBuilder("\n## Performance Metrics\n\n");
        sb.append("| Test | Metric | Value |\n");
        sb.append("|------|--------|-------|\n");
        
        performanceData.forEach((test, metrics) -> {
            sb.append("| ").append(test).append(" | Operations/sec | ")
              .append(String.format("%.2f", metrics.operationsPerSecond())).append(" |\n");
            sb.append("| ").append(test).append(" | Memory MB | ")
              .append(String.format("%.2f", metrics.memoryUsedMB())).append(" |\n");
        });
        
        return sb.toString();
    }
    
    private String formatDuration(Duration duration) {
        if (duration.toHours() > 0) {
            return String.format("%d:%02d:%02d", 
                duration.toHours(), 
                duration.toMinutesPart(), 
                duration.toSecondsPart());
        } else if (duration.toMinutes() > 0) {
            return String.format("%d:%02d", 
                duration.toMinutes(), 
                duration.toSecondsPart());
        } else if (duration.toSeconds() > 0) {
            return String.format("%.3fs", duration.toMillis() / 1000.0);
        } else {
            return String.format("%.3fms", duration.toNanos() / 1_000_000.0);
        }
    }
    
    private String toJson(Object obj) {
        // Simplified JSON serialization - in production use proper JSON library
        if (obj instanceof Map) {
            var map = (Map<?, ?>) obj;
            var entries = map.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + toJson(e.getValue()))
                .toList();
            return "{" + String.join(",", entries) + "}";
        } else if (obj instanceof List) {
            var list = (List<?>) obj;
            var items = list.stream().map(this::toJson).toList();
            return "[" + String.join(",", items) + "]";
        } else if (obj instanceof String) {
            return "\"" + obj + "\"";
        } else {
            return String.valueOf(obj);
        }
    }
    
    // Supporting classes
    
    private enum TestStatus {
        PASSED, FAILED, SKIPPED, ABORTED
    }
    
    private record TestResult(String name, TestStatus status, long duration, String details) {}
    
    public record PerformanceMetrics(double operationsPerSecond, double memoryUsedMB, 
                                    Map<String, Double> customMetrics) {
        public static PerformanceMetrics of(double ops, double memMB) {
            return new PerformanceMetrics(ops, memMB, Map.of());
        }
    }
}