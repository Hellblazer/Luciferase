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
 * Robust performance documentation updater that safely updates performance tables.
 */
public class RobustPerformanceUpdater {
    
    private static final Path METRICS_DOC = Paths.get("doc/PERFORMANCE_METRICS_MASTER.md");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d, yyyy")
        .withZone(ZoneId.systemDefault());
    
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: RobustPerformanceUpdater <results-file>");
            System.exit(1);
        }
        
        Path resultsFile = Paths.get(args[0]);
        Map<String, String> updates = parseResults(resultsFile);
        
        String content = Files.readString(METRICS_DOC);
        content = updateTimestamp(content);
        content = applyUpdates(content, updates);
        
        Files.writeString(METRICS_DOC, content);
        System.out.println("Documentation updated successfully!");
        System.out.println("Applied " + updates.size() + " updates");
    }
    
    private static String updateTimestamp(String content) {
        String newDate = DATE_FORMAT.format(Instant.now());
        return content.replaceFirst(
            "(\\*\\*Last Updated\\*\\*: )[^\\n]+",
            "$1" + newDate
        );
    }
    
    private static String applyUpdates(String content, Map<String, String> updates) {
        // Update each table section separately
        content = updateTable(content, "### Insertion Performance", updates, "insert-prism-");
        content = updateTable(content, "### k-Nearest Neighbor \\(k-NN\\) Search Performance", updates, "knn-prism-");
        content = updateTable(content, "### Range Query Performance", updates, "range-prism-");
        content = updateTable(content, "### Memory Usage", updates, "memory-prism-");
        content = updateTable(content, "### Update Performance", updates, "update-prism-");
        content = updateTable(content, "### Removal Performance", updates, "remove-prism-");
        
        return content;
    }
    
    private static String updateTable(String content, String sectionHeader, Map<String, String> updates, String keyPrefix) {
        // Find the table section
        Pattern sectionPattern = Pattern.compile(
            "(" + sectionHeader + ".*?\\n\\n)(\\|.*?\\n\\|[-:\\s|]+\\n)((?:\\|.*\\n)*?)(?=\\n(?:###|\\*\\*Key|$))",
            Pattern.DOTALL
        );
        
        Matcher matcher = sectionPattern.matcher(content);
        if (matcher.find()) {
            String beforeTable = matcher.group(1);
            String tableHeader = matcher.group(2);
            String tableRows = matcher.group(3);
            
            // Process each row
            String[] rows = tableRows.split("\n");
            StringBuilder newRows = new StringBuilder();
            
            for (String row : rows) {
                if (row.trim().isEmpty()) continue;
                
                // Extract entity count from row
                Pattern rowPattern = Pattern.compile("\\| *(\\d+(?:,\\d+)*) *\\|");
                Matcher rowMatcher = rowPattern.matcher(row);
                
                if (rowMatcher.find()) {
                    String entityCount = rowMatcher.group(1).replace(",", "");
                    String updateKey = keyPrefix + entityCount;
                    String updateValue = updates.get(updateKey);
                    
                    if (updateValue != null && !updateValue.isEmpty()) {
                        // Replace the *pending* in the Prism Time column (5th column)
                        row = row.replaceFirst(
                            "(\\|[^|]+\\|[^|]+\\|[^|]+\\|[^|]+\\| *)\\*pending\\*( *\\|)",
                            "$1" + updateValue + "$2"
                        );
                    }
                }
                
                newRows.append(row).append("\n");
            }
            
            // Replace the table section
            content = content.substring(0, matcher.start()) + 
                     beforeTable + tableHeader + newRows.toString() + 
                     content.substring(matcher.end());
        }
        
        return content;
    }
    
    static Map<String, String> parseResults(Path resultsFile) throws IOException {
        Map<String, String> updates = new HashMap<>();
        
        for (String line : Files.readAllLines(resultsFile)) {
            String[] parts = line.split(",", -1); // -1 to include trailing empty strings
            if (parts.length >= 3 && !line.startsWith("operation")) {
                String operation = parts[0].trim();
                String throughput = parts[1].trim();
                String latency = parts[2].trim();
                
                // Extract entity count from operation name (e.g., "insert-prism-1000" -> "1000")
                Pattern opPattern = Pattern.compile("(\\w+)-prism-(\\d+)");
                Matcher matcher = opPattern.matcher(operation);
                
                if (matcher.matches()) {
                    String opType = matcher.group(1);
                    String entityCount = matcher.group(2);
                    String key = opType + "-prism-" + entityCount;
                    
                    // Convert to appropriate format based on operation type
                    switch (opType) {
                        case "insert":
                        case "update":
                        case "remove":
                            if (!throughput.isEmpty()) {
                                updates.put(key, throughput);
                            }
                            break;
                        case "knn":
                        case "range":
                            if (!latency.isEmpty()) {
                                updates.put(key, latency);
                            }
                            break;
                        case "memory":
                            // Convert bytes to MB
                            if (!throughput.isEmpty()) {
                                try {
                                    double bytes = Double.parseDouble(throughput.replace(" bytes", ""));
                                    updates.put(key, String.format("%.3f MB", bytes / (1024 * 1024)));
                                } catch (NumberFormatException e) {
                                    updates.put(key, throughput);
                                }
                            }
                            break;
                    }
                }
            }
        }
        
        return updates;
    }
}