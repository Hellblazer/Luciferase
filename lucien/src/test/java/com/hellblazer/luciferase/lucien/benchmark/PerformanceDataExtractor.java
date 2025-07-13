/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.benchmark;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts performance data from surefire test reports and converts to structured formats.
 * Used by Maven performance profiles to automate data collection.
 *
 * @author hal.hildebrand
 */
public class PerformanceDataExtractor {

    private static final Pattern INSERTION_PATTERN = Pattern.compile("(Octree|Tetree|Prism):\\s+([0-9.]+)\\s+ms");
    private static final Pattern KNN_PATTERN = Pattern.compile("k-NN.*?(Octree|Tetree|Prism):\\s+([0-9.]+)\\s+μs");
    private static final Pattern RANGE_PATTERN = Pattern.compile("Range.*?(Octree|Tetree|Prism):\\s+([0-9.]+)\\s+μs");
    private static final Pattern MEMORY_PATTERN = Pattern.compile("(Octree|Tetree|Prism)\\s+Memory:\\s+([0-9.]+)\\s+MB");
    private static final Pattern ENTITY_COUNT_PATTERN = Pattern.compile("=== Testing with ([0-9,]+) entities ===");
    private static final Pattern UPDATE_PATTERN = Pattern.compile("UPDATE.*?(Octree|Tetree|Prism):\\s+([0-9.]+)\\s+ms");
    private static final Pattern REMOVAL_PATTERN = Pattern.compile("REMOVAL.*?(Octree|Tetree|Prism):\\s+([0-9.]+)\\s+ms");
    
    // Ghost layer performance patterns
    private static final Pattern GHOST_CREATION_PATTERN = Pattern.compile("(Octree|Tetree|Prism)\\s+Ghost:\\s+([0-9.]+)\\s+ms");
    private static final Pattern GHOST_MEMORY_PATTERN = Pattern.compile("(Octree|Tetree|Prism)\\s+Ghost\\s+Memory:\\s+([0-9.]+)\\s+MB");
    private static final Pattern GHOST_SERIALIZATION_PATTERN = Pattern.compile("(Octree|Tetree|Prism)\\s+Ghost\\s+Serialization:\\s+([0-9.]+)\\s+μs");

    private final String outputFormat;
    private final Path surefireDir;
    private final Path outputDir;
    private final List<PerformanceMetric> metrics;

    public PerformanceDataExtractor(String surefireDir, String outputDir) {
        this.surefireDir = Paths.get(surefireDir);
        this.outputDir = Paths.get(outputDir);
        this.outputFormat = System.getProperty("output.format", "csv,markdown");
        this.metrics = new ArrayList<>();
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: PerformanceDataExtractor <surefire-reports-dir> <output-dir>");
            System.exit(1);
        }

        try {
            var extractor = new PerformanceDataExtractor(args[0], args[1]);
            extractor.extractData();
            extractor.generateOutputs();
            System.out.println("Performance data extraction completed successfully");
        } catch (Exception e) {
            System.err.println("Performance data extraction failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void extractData() throws IOException {
        System.out.println("Extracting performance data from: " + surefireDir);
        
        // Find OctreeVsTetreeBenchmark output
        var benchmarkFile = findBenchmarkOutput();
        if (benchmarkFile != null) {
            extractFromOctreeVsTetreeBenchmark(benchmarkFile);
        }

        // Find other performance test outputs
        extractFromOtherTests();
        
        System.out.println("Extracted " + metrics.size() + " performance metrics");
    }

    public void generateOutputs() throws IOException {
        Files.createDirectories(outputDir);
        
        if (outputFormat.contains("csv")) {
            generateCSV();
        }
        if (outputFormat.contains("markdown")) {
            generateMarkdown();
        }
    }

    private Path findBenchmarkOutput() throws IOException {
        try (var stream = Files.walk(surefireDir)) {
            // First try to find the comprehensive three-way benchmark
            var prismBenchmark = stream
                .filter(path -> path.getFileName().toString().contains("TEST-") &&
                              path.getFileName().toString().contains("OctreeVsTetreeVsPrismBenchmark.xml"))
                .findFirst();
            
            if (prismBenchmark.isPresent()) {
                return prismBenchmark.get();
            }
            
            // Fall back to the two-way benchmark
            try (var stream2 = Files.walk(surefireDir)) {
                return stream2
                    .filter(path -> path.getFileName().toString().contains("TEST-") &&
                                  path.getFileName().toString().contains("OctreeVsTetreeBenchmark.xml"))
                    .findFirst()
                    .orElse(null);
            }
        }
    }

    private void extractFromOctreeVsTetreeBenchmark(Path file) throws IOException {
        var content = Files.readString(file);
        
        // Extract content from <system-out> CDATA section
        var systemOutStart = content.indexOf("<system-out><![CDATA[");
        var systemOutEnd = content.indexOf("]]></system-out>");
        if (systemOutStart == -1 || systemOutEnd == -1) {
            System.out.println("No system-out section found in " + file);
            return;
        }
        
        var systemOutContent = content.substring(systemOutStart + 21, systemOutEnd);
        var lines = systemOutContent.split("\n");
        
        Integer currentEntityCount = null;
        String currentSection = null;
        
        for (var line : lines) {
            // Extract entity count
            var entityMatcher = ENTITY_COUNT_PATTERN.matcher(line);
            if (entityMatcher.find()) {
                currentEntityCount = Integer.parseInt(entityMatcher.group(1).replace(",", ""));
                continue;
            }
            
            // Detect sections
            if (line.contains("INSERTION PERFORMANCE")) {
                currentSection = "insertion";
            } else if (line.contains("K-NEAREST NEIGHBOR")) {
                currentSection = "knn";
            } else if (line.contains("RANGE QUERY")) {
                currentSection = "range";
            } else if (line.contains("UPDATE PERFORMANCE")) {
                currentSection = "update";
            } else if (line.contains("REMOVAL PERFORMANCE")) {
                currentSection = "removal";
            } else if (line.contains("MEMORY USAGE")) {
                currentSection = "memory";
            } else if (line.contains("GHOST LAYER Performance")) {
                currentSection = "ghost_layer";
            } else if (line.contains("Ghost Creation Performance")) {
                currentSection = "ghost_creation";
            } else if (line.contains("Ghost Memory Usage")) {
                currentSection = "ghost_memory";
            } else if (line.contains("Ghost Serialization Performance")) {
                currentSection = "ghost_serialization";
            }
            
            if (currentEntityCount == null || currentSection == null) continue;
            
            // Extract metrics based on section
            switch (currentSection) {
                case "insertion", "update", "removal" -> {
                    var matcher = INSERTION_PATTERN.matcher(line);
                    if (matcher.find()) {
                        metrics.add(new PerformanceMetric(
                            currentSection, currentEntityCount, 
                            matcher.group(1), Double.parseDouble(matcher.group(2)), "ms"
                        ));
                    }
                }
                case "knn" -> {
                    var matcher = KNN_PATTERN.matcher(line);
                    if (matcher.find()) {
                        metrics.add(new PerformanceMetric(
                            "knn", currentEntityCount,
                            matcher.group(1), Double.parseDouble(matcher.group(2)), "μs"
                        ));
                    }
                }
                case "range" -> {
                    var matcher = RANGE_PATTERN.matcher(line);
                    if (matcher.find()) {
                        metrics.add(new PerformanceMetric(
                            "range", currentEntityCount,
                            matcher.group(1), Double.parseDouble(matcher.group(2)), "μs"
                        ));
                    }
                }
                case "memory" -> {
                    var matcher = MEMORY_PATTERN.matcher(line);
                    if (matcher.find()) {
                        metrics.add(new PerformanceMetric(
                            "memory", currentEntityCount,
                            matcher.group(1), Double.parseDouble(matcher.group(2)), "MB"
                        ));
                    }
                }
                case "ghost_creation" -> {
                    var matcher = GHOST_CREATION_PATTERN.matcher(line);
                    if (matcher.find()) {
                        metrics.add(new PerformanceMetric(
                            "ghost_creation", currentEntityCount,
                            matcher.group(1), Double.parseDouble(matcher.group(2)), "ms"
                        ));
                    }
                }
                case "ghost_memory" -> {
                    var matcher = GHOST_MEMORY_PATTERN.matcher(line);
                    if (matcher.find()) {
                        metrics.add(new PerformanceMetric(
                            "ghost_memory", currentEntityCount,
                            matcher.group(1), Double.parseDouble(matcher.group(2)), "MB"
                        ));
                    }
                }
                case "ghost_serialization" -> {
                    var matcher = GHOST_SERIALIZATION_PATTERN.matcher(line);
                    if (matcher.find()) {
                        metrics.add(new PerformanceMetric(
                            "ghost_serialization", currentEntityCount,
                            matcher.group(1), Double.parseDouble(matcher.group(2)), "μs"
                        ));
                    }
                }
            }
        }
    }

    private void extractFromOtherTests() throws IOException {
        // Extract from Prism comparison tests
        extractFromPrismTests();
        
        // Extract from other specialized benchmarks
        extractFromLockFreeTests();
    }

    private void extractFromPrismTests() throws IOException {
        try (var stream = Files.walk(surefireDir)) {
            stream.filter(path -> path.getFileName().toString().contains("Prism") && 
                               path.getFileName().toString().contains("output.txt"))
                  .forEach(this::extractFromPrismFile);
        }
    }

    private void extractFromPrismFile(Path file) {
        try {
            var content = Files.readString(file);
            // Add Prism-specific extraction logic here
            // This would parse Prism test outputs for additional metrics
        } catch (IOException e) {
            System.err.println("Failed to read Prism file: " + file + " - " + e.getMessage());
        }
    }

    private void extractFromLockFreeTests() throws IOException {
        try (var stream = Files.walk(surefireDir)) {
            stream.filter(path -> path.getFileName().toString().contains("LockFree") && 
                               path.getFileName().toString().contains("output.txt"))
                  .forEach(this::extractFromLockFreeFile);
        }
    }

    private void extractFromLockFreeFile(Path file) {
        try {
            var content = Files.readString(file);
            // Add lock-free specific extraction logic here
        } catch (IOException e) {
            System.err.println("Failed to read LockFree file: " + file + " - " + e.getMessage());
        }
    }

    private void generateCSV() throws IOException {
        var csvFile = outputDir.resolve("performance_metrics_" + 
                                       LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv");
        
        try (var writer = Files.newBufferedWriter(csvFile)) {
            writer.write("operation,entity_count,implementation,value,unit,timestamp\n");
            
            for (var metric : metrics) {
                writer.write(String.format("%s,%d,%s,%.3f,%s,%s\n",
                    metric.operation, metric.entityCount, metric.implementation,
                    metric.value, metric.unit, metric.timestamp));
            }
        }
        
        System.out.println("Generated CSV: " + csvFile);
    }

    private void generateMarkdown() throws IOException {
        var mdFile = outputDir.resolve("performance_tables_" + 
                                      LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".md");
        
        try (var writer = Files.newBufferedWriter(mdFile)) {
            writer.write("# Performance Metrics Report\n\n");
            writer.write("**Generated**: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\n");
            writer.write("**Source**: Surefire test reports\n\n");
            
            generateInsertionTable(writer);
            generateKNNTable(writer);
            generateRangeTable(writer);
            generateMemoryTable(writer);
            generateUpdateTable(writer);
            generateRemovalTable(writer);
            generateGhostCreationTable(writer);
            generateGhostMemoryTable(writer);
            generateGhostSerializationTable(writer);
            
            writer.write("\n## Data Summary\n\n");
            writer.write("- **Total Metrics**: " + metrics.size() + "\n");
            writer.write("- **Operations**: " + metrics.stream().map(m -> m.operation).distinct().count() + "\n");
            writer.write("- **Implementations**: " + metrics.stream().map(m -> m.implementation).distinct().toList() + "\n");
        }
        
        System.out.println("Generated Markdown: " + mdFile);
    }

    private void generateInsertionTable(BufferedWriter writer) throws IOException {
        generateOperationTable(writer, "insertion", "Insertion Performance", "ms");
    }

    private void generateKNNTable(BufferedWriter writer) throws IOException {
        generateOperationTable(writer, "knn", "k-Nearest Neighbor Performance", "μs");
    }

    private void generateRangeTable(BufferedWriter writer) throws IOException {
        generateOperationTable(writer, "range", "Range Query Performance", "μs");
    }

    private void generateMemoryTable(BufferedWriter writer) throws IOException {
        generateOperationTable(writer, "memory", "Memory Usage", "MB");
    }

    private void generateUpdateTable(BufferedWriter writer) throws IOException {
        generateOperationTable(writer, "update", "Update Performance", "ms");
    }

    private void generateRemovalTable(BufferedWriter writer) throws IOException {
        generateOperationTable(writer, "removal", "Removal Performance", "ms");
    }

    private void generateGhostCreationTable(BufferedWriter writer) throws IOException {
        generateOperationTable(writer, "ghost_creation", "Ghost Creation Performance", "ms");
    }

    private void generateGhostMemoryTable(BufferedWriter writer) throws IOException {
        generateOperationTable(writer, "ghost_memory", "Ghost Memory Usage", "MB");
    }

    private void generateGhostSerializationTable(BufferedWriter writer) throws IOException {
        generateOperationTable(writer, "ghost_serialization", "Ghost Serialization Performance", "μs");
    }

    private void generateOperationTable(BufferedWriter writer, String operation, String title, String unit) throws IOException {
        var operationMetrics = metrics.stream()
            .filter(m -> m.operation.equals(operation))
            .toList();
        
        if (operationMetrics.isEmpty()) {
            return;
        }
        
        writer.write("## " + title + "\n\n");
        writer.write("| Entity Count | Octree | Tetree | Prism | Tetree vs Octree | Prism vs Octree |\n");
        writer.write("|-------------|--------|--------|-------|------------------|-----------------|\n");
        
        var entityCounts = operationMetrics.stream()
            .mapToInt(m -> m.entityCount)
            .distinct()
            .sorted()
            .boxed()
            .toList();
        
        for (var count : entityCounts) {
            var octree = findMetric(operationMetrics, count, "Octree");
            var tetree = findMetric(operationMetrics, count, "Tetree");
            var prism = findMetric(operationMetrics, count, "Prism");
            
            var tetreeRatio = (octree != null && tetree != null) ? 
                (tetree.value < octree.value ? 
                    String.format("%.1fx faster", octree.value / tetree.value) :
                    String.format("%.1fx slower", tetree.value / octree.value)) : "N/A";
            var prismRatio = (octree != null && prism != null) ? 
                (prism.value < octree.value ? 
                    String.format("%.1fx faster", octree.value / prism.value) :
                    String.format("%.1fx slower", prism.value / octree.value)) : "N/A";
            
            writer.write(String.format("| %,d | %s | %s | %s | %s | %s |\n",
                count,
                octree != null ? String.format("%.3f %s", octree.value, unit) : "N/A",
                tetree != null ? String.format("%.3f %s", tetree.value, unit) : "N/A",
                prism != null ? String.format("%.3f %s", prism.value, unit) : "N/A",
                tetreeRatio,
                prismRatio
            ));
        }
        
        writer.write("\n");
    }

    private PerformanceMetric findMetric(List<PerformanceMetric> metrics, int entityCount, String implementation) {
        return metrics.stream()
            .filter(m -> m.entityCount == entityCount && m.implementation.equals(implementation))
            .findFirst()
            .orElse(null);
    }

    private static class PerformanceMetric {
        final String operation;
        final int entityCount;
        final String implementation;
        final double value;
        final String unit;
        final String timestamp;

        PerformanceMetric(String operation, int entityCount, String implementation, double value, String unit) {
            this.operation = operation;
            this.entityCount = entityCount;
            this.implementation = implementation;
            this.value = value;
            this.unit = unit;
            this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }
}