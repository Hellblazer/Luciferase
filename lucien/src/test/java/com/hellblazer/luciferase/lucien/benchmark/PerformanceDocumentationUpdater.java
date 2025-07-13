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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Updates performance documentation files with current metrics.
 * Used by Maven performance profiles to maintain documentation consistency.
 *
 * @author hal.hildebrand
 */
public class PerformanceDocumentationUpdater {

    private static final String UPDATE_MODE = System.getProperty("update.mode", "all");
    private static final boolean BACKUP_ENABLED = Boolean.parseBoolean(System.getProperty("backup.enabled", "true"));
    
    private final Path performanceDataDir;
    private final Path docsDir;
    private final Map<String, PerformanceData> latestMetrics;

    public PerformanceDocumentationUpdater(String performanceDataDir, String docsDir) {
        this.performanceDataDir = Paths.get(performanceDataDir);
        this.docsDir = Paths.get(docsDir);
        this.latestMetrics = new HashMap<>();
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: PerformanceDocumentationUpdater <performance-data-dir> <docs-dir>");
            System.exit(1);
        }

        try {
            var updater = new PerformanceDocumentationUpdater(args[0], args[1]);
            updater.loadLatestMetrics();
            updater.updateDocumentation();
            System.out.println("Performance documentation update completed successfully");
        } catch (Exception e) {
            System.err.println("Performance documentation update failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void loadLatestMetrics() throws IOException {
        System.out.println("Loading performance metrics from: " + performanceDataDir);
        
        // Find the most recent CSV file
        var csvFile = findLatestCSVFile();
        if (csvFile == null) {
            System.err.println("No CSV performance data found in: " + performanceDataDir);
            return;
        }
        
        System.out.println("Loading metrics from: " + csvFile);
        loadMetricsFromCSV(csvFile);
    }

    public void updateDocumentation() throws IOException {
        if (latestMetrics.isEmpty()) {
            System.out.println("No metrics loaded, skipping documentation update");
            return;
        }

        System.out.println("Updating documentation files...");
        
        if (UPDATE_MODE.equals("all") || UPDATE_MODE.contains("master")) {
            updatePerformanceMetricsMaster();
        }
        
        if (UPDATE_MODE.equals("all") || UPDATE_MODE.contains("comparison")) {
            updateSpatialIndexComparison();
        }
        
        if (UPDATE_MODE.equals("all") || UPDATE_MODE.contains("index")) {
            updatePerformanceIndex();
        }
        
        if (UPDATE_MODE.equals("all") || UPDATE_MODE.contains("readme")) {
            updateReadme();
        }
    }

    private Path findLatestCSVFile() throws IOException {
        if (!Files.exists(performanceDataDir)) {
            return null;
        }
        
        try (var stream = Files.walk(performanceDataDir)) {
            return stream
                .filter(path -> path.getFileName().toString().startsWith("performance_metrics_") &&
                              path.getFileName().toString().endsWith(".csv"))
                .max(Comparator.comparing(path -> path.getFileName().toString()))
                .orElse(null);
        }
    }

    private void loadMetricsFromCSV(Path csvFile) throws IOException {
        try (var reader = Files.newBufferedReader(csvFile)) {
            String line;
            boolean isHeader = true;
            
            while ((line = reader.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }
                
                var parts = line.split(",");
                if (parts.length >= 5) {
                    var operation = parts[0];
                    var entityCount = Integer.parseInt(parts[1]);
                    var implementation = parts[2];
                    var value = Double.parseDouble(parts[3]);
                    var unit = parts[4];
                    
                    var key = operation + "_" + entityCount + "_" + implementation;
                    latestMetrics.put(key, new PerformanceData(operation, entityCount, implementation, value, unit));
                }
            }
        }
        
        System.out.println("Loaded " + latestMetrics.size() + " performance metrics");
    }

    private void updatePerformanceMetricsMaster() throws IOException {
        var masterFile = docsDir.resolve("PERFORMANCE_METRICS_MASTER.md");
        if (!Files.exists(masterFile)) {
            System.err.println("PERFORMANCE_METRICS_MASTER.md not found at: " + masterFile);
            return;
        }
        
        if (BACKUP_ENABLED) {
            createBackup(masterFile);
        }
        
        System.out.println("Updating PERFORMANCE_METRICS_MASTER.md...");
        
        var content = Files.readString(masterFile);
        
        // Update the "Last Updated" date
        content = updateLastUpdatedDate(content);
        
        // Update performance tables
        content = updateInsertionTable(content);
        content = updateKNNTable(content);
        content = updateRangeTable(content);
        content = updateMemoryTable(content);
        content = updateUpdateTable(content);
        content = updateRemovalTable(content);
        
        Files.writeString(masterFile, content);
        System.out.println("Updated PERFORMANCE_METRICS_MASTER.md");
    }

    private void updateSpatialIndexComparison() throws IOException {
        var comparisonFile = docsDir.resolve("SPATIAL_INDEX_PERFORMANCE_COMPARISON.md");
        if (!Files.exists(comparisonFile)) {
            System.out.println("SPATIAL_INDEX_PERFORMANCE_COMPARISON.md not found, skipping");
            return;
        }
        
        if (BACKUP_ENABLED) {
            createBackup(comparisonFile);
        }
        
        System.out.println("Updating SPATIAL_INDEX_PERFORMANCE_COMPARISON.md...");
        
        var content = Files.readString(comparisonFile);
        content = updateLastUpdatedDate(content);
        
        // Update cross-reference tables with current data
        // This would contain specific logic for the comparison document format
        
        Files.writeString(comparisonFile, content);
        System.out.println("Updated SPATIAL_INDEX_PERFORMANCE_COMPARISON.md");
    }

    private void updatePerformanceIndex() throws IOException {
        var indexFile = docsDir.resolve("PERFORMANCE_INDEX.md");
        if (!Files.exists(indexFile)) {
            System.out.println("PERFORMANCE_INDEX.md not found, skipping");
            return;
        }
        
        if (BACKUP_ENABLED) {
            createBackup(indexFile);
        }
        
        System.out.println("Updating PERFORMANCE_INDEX.md...");
        
        var content = Files.readString(indexFile);
        content = updateLastUpdatedDate(content);
        
        Files.writeString(indexFile, content);
        System.out.println("Updated PERFORMANCE_INDEX.md");
    }

    private void updateReadme() throws IOException {
        var readmeFile = Path.of("README.md");
        if (!Files.exists(readmeFile)) {
            System.out.println("README.md not found, skipping");
            return;
        }
        
        if (BACKUP_ENABLED) {
            createBackup(readmeFile);
        }
        
        System.out.println("Updating README.md...");
        
        var content = Files.readString(readmeFile);
        content = updateReadmePerformanceTable(content);
        content = updateReadmeRecommendations(content);
        content = updateLastUpdatedDate(content);
        
        Files.writeString(readmeFile, content);
        System.out.println("Updated README.md");
    }

    private void createBackup(Path file) throws IOException {
        var parent = file.getParent();
        var backupDir = parent != null ? parent.resolve("backup") : Path.of("backup");
        Files.createDirectories(backupDir);
        
        var timestamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        var backupFile = backupDir.resolve(file.getFileName() + ".backup." + timestamp);
        
        Files.copy(file, backupFile, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Created backup: " + backupFile);
    }

    private String updateLastUpdatedDate(String content) {
        var today = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
        return content.replaceFirst(
            "\\*\\*Last Updated\\*\\*: [^\\n]+",
            "**Last Updated**: " + today
        );
    }

    private String updateInsertionTable(String content) {
        return updatePerformanceTable(content, "insertion", "Insertion Performance");
    }

    private String updateKNNTable(String content) {
        return updatePerformanceTable(content, "knn", "k-Nearest Neighbor \\(k-NN\\) Search Performance");
    }

    private String updateRangeTable(String content) {
        return updatePerformanceTable(content, "range", "Range Query Performance");
    }

    private String updateMemoryTable(String content) {
        return updatePerformanceTable(content, "memory", "Memory Usage");
    }

    private String updateUpdateTable(String content) {
        return updatePerformanceTable(content, "update", "Update Performance");
    }

    private String updateRemovalTable(String content) {
        return updatePerformanceTable(content, "removal", "Removal Performance");
    }

    private String updatePerformanceTable(String content, String operation, String sectionTitle) {
        // Find the section and table
        var sectionPattern = Pattern.compile(
            "### " + sectionTitle + ".*?\\n\\n(\\|.*?\\n)(\\|.*?\\n)((?:\\|.*?\\n)*)",
            Pattern.DOTALL
        );
        
        var matcher = sectionPattern.matcher(content);
        if (!matcher.find()) {
            System.out.println("Could not find " + sectionTitle + " table to update");
            return content;
        }
        
        var tableStart = matcher.start(1);
        var tableEnd = matcher.end(3);
        
        // Generate new table rows
        var newRows = generateTableRows(operation);
        if (newRows.isEmpty()) {
            System.out.println("No data found for " + operation + ", keeping existing table");
            return content;
        }
        
        // Replace the table data rows (keep header and separator)
        var newContent = content.substring(0, matcher.end(2)) + newRows + content.substring(tableEnd);
        
        System.out.println("Updated " + sectionTitle + " table");
        return newContent;
    }

    private String generateTableRows(String operation) {
        var entityCounts = latestMetrics.values().stream()
            .filter(m -> m.operation.equals(operation))
            .mapToInt(m -> m.entityCount)
            .distinct()
            .sorted()
            .boxed()
            .toList();
        
        if (entityCounts.isEmpty()) {
            return "";
        }
        
        var rows = new StringBuilder();
        
        for (var count : entityCounts) {
            var octree = getMetric(operation, count, "Octree");
            var tetree = getMetric(operation, count, "Tetree");
            var prism = getMetric(operation, count, "Prism");
            
            var tetreeRatio = (octree != null && tetree != null) ? 
                (tetree.value < octree.value ? 
                    String.format("%.1fx faster", octree.value / tetree.value) :
                    String.format("%.1fx slower", tetree.value / octree.value)) : "-";
            
            // Handle different units for different operations
            var unit = octree != null ? octree.unit : (tetree != null ? tetree.unit : "");
            
            // All tables in PERFORMANCE_METRICS_MASTER.md use 7-column format
            // | Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
            var prismVsOctree = (octree != null && prism != null) ? 
                (prism.value < octree.value ? 
                    String.format("%.0fx faster", octree.value / prism.value) :
                    String.format("%.0fx slower", prism.value / octree.value)) : "-";
            var prismVsTetree = (tetree != null && prism != null) ? 
                (prism.value < tetree.value ? 
                    String.format("%.0fx faster", tetree.value / prism.value) :
                    String.format("%.0fx slower", prism.value / tetree.value)) : "-";
            
            rows.append(String.format("| %,d | %s | %s | %s | %s | %s | %s |\n",
                count,
                octree != null ? formatValue(octree.value, unit) : "-",
                tetree != null ? formatValue(tetree.value, unit) : "-",
                tetreeRatio,
                prism != null ? formatValue(prism.value, unit) : "-",
                prismVsOctree,
                prismVsTetree
            ));
        }
        
        return rows.toString();
    }

    private String formatValue(double value, String unit) {
        return String.format("%.3f %s", value, unit);
    }

    private PerformanceData getMetric(String operation, int entityCount, String implementation) {
        var key = operation + "_" + entityCount + "_" + implementation;
        return latestMetrics.get(key);
    }

    private String updateReadmePerformanceTable(String content) {
        // Update the performance table in README.md
        var tablePattern = Pattern.compile(
            "\\| Operation\\s+\\| Octree\\s+\\| Tetree\\s+\\| Prism\\s+\\| Best Choice\\s+\\|.*?" +
            "\\|---[^\\n]*\\n" +
            "((?:\\|[^\\n]*\\n)*)",
            Pattern.DOTALL
        );
        
        var matcher = tablePattern.matcher(content);
        if (!matcher.find()) {
            System.out.println("Could not find README performance table to update");
            return content;
        }
        
        // Get current metrics for 1K entities (most representative)
        var octreeInsert = getMetric("insertion", 1000, "Octree");
        var tetreeInsert = getMetric("insertion", 1000, "Tetree");
        var prismInsert = getMetric("insertion", 1000, "Prism");
        
        var octreeKnn = getMetric("knn", 1000, "Octree"); 
        var tetreeKnn = getMetric("knn", 1000, "Tetree");
        var prismKnn = getMetric("knn", 1000, "Prism");
        
        var octreeRange = getMetric("range", 1000, "Octree");
        var tetreeRange = getMetric("range", 1000, "Tetree"); 
        var prismRange = getMetric("range", 1000, "Prism");
        
        var octreeMemory = getMetric("memory", 1000, "Octree");
        var tetreeMemory = getMetric("memory", 1000, "Tetree");
        var prismMemory = getMetric("memory", 1000, "Prism");
        
        // Generate new table with current data
        var newTable = new StringBuilder();
        newTable.append("| Operation         | Octree     | Tetree     | Prism      | Best Choice      |\n");
        newTable.append("|-------------------|------------|------------|------------|------------------|\n");
        
        // Insertion row
        var insertBest = determineBestPerformer(octreeInsert, tetreeInsert, prismInsert, false);
        newTable.append(String.format("| Insert (1K)       | %s | %s | %s | **%s**       |\n",
            formatTableValue(octreeInsert), formatTableValue(tetreeInsert), formatTableValue(prismInsert), insertBest));
        
        // k-NN row  
        var knnBest = determineBestPerformer(octreeKnn, tetreeKnn, prismKnn, false);
        newTable.append(String.format("| k-NN (1K)         | %s | %s | %s | **%s**       |\n",
            formatTableValue(octreeKnn), formatTableValue(tetreeKnn), formatTableValue(prismKnn), knnBest));
        
        // Range query row
        var rangeBest = determineBestPerformer(octreeRange, tetreeRange, prismRange, false);
        newTable.append(String.format("| Range Query (1K)  | %s | %s | %s | **%s**       |\n",
            formatTableValue(octreeRange), formatTableValue(tetreeRange), formatTableValue(prismRange), rangeBest));
        
        // Memory row (lower is better)
        var memoryBest = determineBestPerformer(octreeMemory, tetreeMemory, prismMemory, true);
        newTable.append(String.format("| Memory (2K)       | %s | %s | %s | **%s**       |\n",
            formatTableValue(octreeMemory), formatTableValue(tetreeMemory), formatTableValue(prismMemory), memoryBest));
        
        return content.substring(0, matcher.start(1)) + newTable.toString() + content.substring(matcher.end(1));
    }
    
    private String updateReadmeRecommendations(String content) {
        // Update the "Use Octree When" vs "Use Tetree When" recommendations based on current performance
        var octreeInsert = getMetric("insertion", 1000, "Octree");
        var tetreeInsert = getMetric("insertion", 1000, "Tetree");
        
        var tetreeFaster = tetreeInsert != null && octreeInsert != null && tetreeInsert.value < octreeInsert.value;
        
        if (tetreeFaster) {
            // Update recommendations to reflect Tetree's superior insertion performance
            content = content.replaceAll(
                "### Use Octree When \\(General Recommendation\\):",
                "### Use Tetree When (Recommended for Most Use Cases):");
            
            content = content.replaceAll(
                "- \\*\\*Best overall performance\\*\\* \\(fastest insertion, k-NN, and range queries\\)",
                "- **Fastest insertion performance** (2-6x faster than Octree after July 2025 optimizations)");
                
            content = content.replaceAll(
                "### Use Tetree When:",
                "### Use Octree When:");
        }
        
        return content;
    }
    
    private String determineBestPerformer(PerformanceData octree, PerformanceData tetree, PerformanceData prism, boolean lowerIsBetter) {
        var best = "Octree";
        var bestValue = Double.MAX_VALUE;
        if (!lowerIsBetter) bestValue = 0;
        
        if (octree != null) {
            if (lowerIsBetter ? octree.value < bestValue : octree.value > bestValue) {
                bestValue = octree.value;
                best = "Octree";
            }
        }
        
        if (tetree != null) {
            if (lowerIsBetter ? tetree.value < bestValue : tetree.value > bestValue) {
                bestValue = tetree.value;
                best = "Tetree";
            }
        }
        
        if (prism != null) {
            if (lowerIsBetter ? prism.value < bestValue : prism.value > bestValue) {
                bestValue = prism.value;
                best = "Prism";
            }
        }
        
        return best;
    }
    
    private String formatTableValue(PerformanceData data) {
        if (data == null) return "-         ";
        
        if ("ms".equals(data.unit)) {
            return String.format("%-10s", String.format("%.2fms", data.value));
        } else if ("MB".equals(data.unit)) {
            return String.format("%-10s", String.format("%.0fKB", data.value * 1024));
        } else {
            return String.format("%-10s", String.format("%.0f%s", data.value, data.unit));
        }
    }

    private static class PerformanceData {
        final String operation;
        final int entityCount;
        final String implementation;
        final double value;
        final String unit;

        PerformanceData(String operation, int entityCount, String implementation, double value, String unit) {
            this.operation = operation;
            this.entityCount = entityCount;
            this.implementation = implementation;
            this.value = value;
            this.unit = unit;
        }
    }
}