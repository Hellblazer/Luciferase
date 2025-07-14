package com.hellblazer.luciferase.lucien.performance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple tool to update performance documentation from benchmark results.
 * Can be run as part of CI pipeline to keep docs in sync with actual performance.
 */
public class PerformanceDocumentationUpdater {
    
    private static final Path METRICS_DOC = Paths.get("doc/PERFORMANCE_METRICS_MASTER.md");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d, yyyy")
        .withZone(ZoneId.systemDefault());
    
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: PerformanceDocumentationUpdater <results-file>");
            System.exit(1);
        }
        
        Path resultsFile = Paths.get(args[0]);
        if (!Files.exists(resultsFile)) {
            System.err.println("Results file not found: " + resultsFile);
            System.exit(1);
        }
        
        // Parse results (simplified - in reality would parse JSON/CSV)
        Map<String, PerformanceData> results = parseResults(resultsFile);
        
        // Update documentation
        updateDocumentation(results);
        
        System.out.println("Documentation updated successfully!");
    }
    
    private static void updateDocumentation(Map<String, PerformanceData> results) throws IOException {
        String content = Files.readString(METRICS_DOC);
        
        // Update timestamp
        content = updateTimestamp(content);
        
        // Update performance tables
        content = updateOperationTable(content, results);
        content = updateMemoryTable(content, results);
        content = updateScalabilityTable(content, results);
        
        // Write back
        Files.writeString(METRICS_DOC, content);
    }
    
    private static String updateTimestamp(String content) {
        String newDate = DATE_FORMAT.format(Instant.now());
        return content.replaceAll(
            "\\*\\*Last Updated\\*\\*: .+",
            "**Last Updated**: " + newDate
        );
    }
    
    private static String updateOperationTable(String content, Map<String, PerformanceData> results) {
        // Update multiple tables
        content = updateInsertionTable(content, results);
        content = updateKNNTable(content, results);
        content = updateRangeTable(content, results);
        content = updateMemoryTable(content, results);
        
        return content;
    }
    
    private static String updateInsertionTable(String content, Map<String, PerformanceData> results) {
        return updateSpecificTable(content, "### Insertion Performance", results, "insert-", true);
    }
    
    private static String updateKNNTable(String content, Map<String, PerformanceData> results) {
        return updateSpecificTable(content, "### k-Nearest Neighbor (k-NN) Search Performance", results, "knn-", false);
    }
    
    private static String updateRangeTable(String content, Map<String, PerformanceData> results) {
        return updateSpecificTable(content, "### Range Query Performance", results, "range-", false);
    }
    
    private static String updateSpecificTable(String content, String sectionTitle, 
                                             Map<String, PerformanceData> results, 
                                             String prefix, boolean useThroughput) {
        // Find the specific table section
        String escapedTitle = Pattern.quote(sectionTitle);
        Pattern tablePattern = Pattern.compile(
            "(" + escapedTitle + ".*?\\n\\n)(\\|.*\\|.*\\|.*\\|.*\\|\\n\\|[-:\\s|]+\\n)((?:\\|.*\\n)*?)(?=\\n(?:###|\\*\\*Key|$))",
            Pattern.DOTALL
        );
        
        Matcher matcher = tablePattern.matcher(content);
        if (!matcher.find()) {
            System.err.println("Could not find table section: " + sectionTitle);
            return content;
        }
        
        String header = matcher.group(1);
        String tableHeader = matcher.group(2);
        String tableBody = matcher.group(3);
        
        // Parse existing table rows
        String[] lines = tableBody.split("\n");
        StringBuilder newTableBody = new StringBuilder();
        
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            
            String[] cells = line.split("\\|");
            if (cells.length >= 8) { // Expecting: | Count | Octree | Tetree | vs | Prism | vs | vs |
                String entityCount = cells[1].trim();
                
                // Find matching data for this entity count
                String octreeValue = updateCellValue(results, prefix + "octree", entityCount, cells[2].trim(), useThroughput);
                String tetreeValue = updateCellValue(results, prefix + "tetree", entityCount, cells[3].trim(), useThroughput);
                String prismValue = updateCellValue(results, prefix + "prism", entityCount, cells[5].trim(), useThroughput);
                
                // Rebuild the line with updated values
                newTableBody.append("| ").append(entityCount).append(" | ");
                newTableBody.append(octreeValue).append(" | ");
                newTableBody.append(tetreeValue).append(" | ");
                newTableBody.append(cells[4].trim()).append(" | "); // Keep "vs" column
                newTableBody.append(prismValue).append(" | ");
                newTableBody.append(cells[6].trim()).append(" | "); // Keep comparison columns
                newTableBody.append(cells[7].trim()).append(" |\n");
            }
        }
        
        return content.substring(0, matcher.start()) + 
               header + tableHeader + newTableBody.toString() + 
               content.substring(matcher.end());
    }
    
    private static String updateCellValue(Map<String, PerformanceData> results, String key, 
                                        String entityCount, String currentValue, boolean useThroughput) {
        PerformanceData data = results.get(key);
        if (data != null) {
            String newValue = useThroughput ? data.throughput : data.latency;
            if (!newValue.isEmpty() && !newValue.equals("N/A")) {
                return newValue.replace(" μs/op", " ms"); // Convert units if needed
            }
        }
        return currentValue; // Keep existing value if no update
    }
    
    private static Map<String, String[]> parseTable(String tableBody) {
        Map<String, String[]> rows = new HashMap<>();
        
        for (String line : tableBody.split("\n")) {
            if (line.trim().isEmpty()) continue;
            
            String[] cells = line.split("\\|");
            if (cells.length >= 5) {
                String operation = cells[1].trim();
                String[] values = new String[]{
                    cells[2].trim(), // Octree
                    cells[3].trim(), // Tetree  
                    cells[4].trim()  // Prism
                };
                rows.put(operation, values);
            }
        }
        
        return rows;
    }
    
    private static void updateRow(Map<String, String[]> rows, String operation, 
                                  String indexType, String value) {
        String[] row = rows.computeIfAbsent(operation, k -> new String[]{"*pending*", "*pending*", "*pending*"});
        
        int index = indexType.equalsIgnoreCase("octree") ? 0 : 
                   indexType.equalsIgnoreCase("tetree") ? 1 : 2;
        
        row[index] = value;
    }
    
    private static String updateMemoryTable(String content, Map<String, PerformanceData> results) {
        // Find the memory usage section
        Pattern tablePattern = Pattern.compile(
            "(### Memory Usage.*?\\n\\n)(\\|.*\\|.*\\|.*\\|.*\\|\\n\\|[-:\\s|]+\\n)((?:\\|.*\\n)*?)(?=\\n(?:###|\\*\\*Key|$))",
            Pattern.DOTALL
        );
        
        Matcher matcher = tablePattern.matcher(content);
        if (!matcher.find()) {
            System.err.println("Could not find memory usage table");
            return content;
        }
        
        String header = matcher.group(1);
        String tableHeader = matcher.group(2);
        String tableBody = matcher.group(3);
        
        // Parse and update memory data
        String[] lines = tableBody.split("\n");
        StringBuilder newTableBody = new StringBuilder();
        
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            
            String[] cells = line.split("\\|");
            if (cells.length >= 8) {
                String entityCount = cells[1].trim();
                
                // Update memory values if we have data
                String octreeValue = updateMemoryValue(results, "memory-octree", cells[2].trim());
                String tetreeValue = updateMemoryValue(results, "memory-tetree", cells[3].trim());
                String prismValue = updateMemoryValue(results, "memory-prism", cells[5].trim());
                
                // Rebuild the line
                newTableBody.append("| ").append(entityCount).append(" | ");
                newTableBody.append(octreeValue).append(" | ");
                newTableBody.append(tetreeValue).append(" | ");
                newTableBody.append(cells[4].trim()).append(" | "); // Keep comparison
                newTableBody.append(prismValue).append(" | ");
                newTableBody.append(cells[6].trim()).append(" | ");
                newTableBody.append(cells[7].trim()).append(" |\n");
            }
        }
        
        return content.substring(0, matcher.start()) + 
               header + tableHeader + newTableBody.toString() + 
               content.substring(matcher.end());
    }
    
    private static String updateMemoryValue(Map<String, PerformanceData> results, String key, String currentValue) {
        PerformanceData data = results.get(key);
        if (data != null && !data.throughput.isEmpty() && !data.throughput.equals("N/A")) {
            // Memory is stored in throughput field for memory entries
            String memoryStr = data.throughput.replace(" bytes", "");
            try {
                double bytes = Double.parseDouble(memoryStr);
                return String.format("%.3f MB", bytes / (1024 * 1024));
            } catch (NumberFormatException e) {
                // If not a simple number, return as-is
                return data.throughput;
            }
        }
        return currentValue;
    }
    
    private static String updateScalabilityTable(String content, Map<String, PerformanceData> results) {
        // Similar logic for scalability table
        return content;
    }
    
    private static Map<String, PerformanceData> parseResults(Path resultsFile) throws IOException {
        // Simplified parser - in reality would handle JSON/CSV
        Map<String, PerformanceData> results = new HashMap<>();
        
        for (String line : Files.readAllLines(resultsFile)) {
            String[] parts = line.split(",");
            if (parts.length >= 3) {
                String key = parts[0].trim();
                String throughput = parts[1].trim();
                String latency = parts[2].trim();
                
                results.put(key, new PerformanceData(throughput, latency));
            }
        }
        
        return results;
    }
    
    static class PerformanceData {
        final String throughput;
        final String latency;
        
        PerformanceData(String throughput, String latency) {
            this.throughput = throughput;
            this.latency = latency;
        }
    }
}