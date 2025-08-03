package com.hellblazer.luciferase.lucien.performance;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Extracts performance data from test output files and converts to CSV format.
 * This bridges the gap between test output and documentation updates.
 */
public class TestResultExtractor {
    
    // Patterns to match performance output
    private static final Pattern INSERTION_PATTERN = Pattern.compile(
        "Insertion\\s+\\|\\s+([\\d.]+)\\s+μs/op\\s+\\|\\s+([\\d.]+)\\s+μs/op\\s+\\|\\s+([\\d.]+)\\s+μs/op"
    );
    
    private static final Pattern KNN_PATTERN = Pattern.compile(
        "k-NN Query.*?\\|\\s+([\\d.]+)\\s+μs/op\\s+\\|\\s+([\\d.]+)\\s+μs/op\\s+\\|\\s+([\\d.]+)\\s+μs/op"
    );
    
    private static final Pattern RANGE_PATTERN = Pattern.compile(
        "Range Query.*?\\|\\s+([\\d.]+)\\s+μs/op\\s+\\|\\s+([\\d.]+)\\s+μs/op\\s+\\|\\s+([\\d.]+)\\s+μs/op"
    );
    
    private static final Pattern MEMORY_PATTERN = Pattern.compile(
        "Memory per entity\\s+\\|\\s+([\\d.]+)\\s+bytes\\s+\\|\\s+([\\d.]+)\\s+bytes\\s+\\|\\s+([\\d.]+)\\s+bytes"
    );
    
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: TestResultExtractor <surefire-reports-dir> <output-csv>");
            System.exit(1);
        }
        
        Path reportsDir = Paths.get(args[0]);
        Path outputCsv = Paths.get(args[1]);
        
        List<PerformanceRecord> records = extractResults(reportsDir);
        writeCsv(records, outputCsv);
        
        System.out.println("Extracted " + records.size() + " performance records to " + outputCsv);
    }
    
    private static List<PerformanceRecord> extractResults(Path reportsDir) throws IOException {
        List<PerformanceRecord> records = new ArrayList<>();
        
        // Find test output files
        try (Stream<Path> files = Files.walk(reportsDir)) {
            files.filter(p -> p.toString().endsWith(".txt") || p.toString().endsWith("-output.txt"))
                 .forEach(file -> {
                     try {
                         records.addAll(extractFromFile(file));
                     } catch (IOException e) {
                         System.err.println("Error reading file: " + file + " - " + e.getMessage());
                     }
                 });
        }
        
        return records;
    }
    
    private static List<PerformanceRecord> extractFromFile(Path file) throws IOException {
        List<PerformanceRecord> records = new ArrayList<>();
        String content = Files.readString(file);
        
        // Look for entity count sections
        Pattern entityCountPattern = Pattern.compile("=== Testing with (\\d+) entities ===");
        Matcher entityMatcher = entityCountPattern.matcher(content);
        
        int currentIndex = 0;
        while (entityMatcher.find(currentIndex)) {
            String entityCount = entityMatcher.group(1);
            int sectionStart = entityMatcher.end();
            
            // Find the next entity count section or end of file
            int sectionEnd = content.length();
            if (entityMatcher.find(sectionStart)) {
                sectionEnd = entityMatcher.start();
                // Reset for next iteration
                currentIndex = entityMatcher.start();
            } else {
                currentIndex = content.length();
            }
            
            String sectionContent = content.substring(sectionStart, sectionEnd);
            
            // Extract insertion performance for this entity count
            Matcher insertMatcher = INSERTION_PATTERN.matcher(sectionContent);
            if (insertMatcher.find()) {
                records.add(new PerformanceRecord("insert-octree-" + entityCount, insertMatcher.group(1) + " μs/op", ""));
                records.add(new PerformanceRecord("insert-tetree-" + entityCount, insertMatcher.group(2) + " μs/op", ""));
                records.add(new PerformanceRecord("insert-prism-" + entityCount, insertMatcher.group(3) + " μs/op", ""));
            }
            
            // Extract k-NN performance
            Matcher knnMatcher = KNN_PATTERN.matcher(sectionContent);
            if (knnMatcher.find()) {
                records.add(new PerformanceRecord("knn-octree-" + entityCount, "", knnMatcher.group(1) + " μs/op"));
                records.add(new PerformanceRecord("knn-tetree-" + entityCount, "", knnMatcher.group(2) + " μs/op"));
                records.add(new PerformanceRecord("knn-prism-" + entityCount, "", knnMatcher.group(3) + " μs/op"));
            }
            
            // Extract range query performance
            Matcher rangeMatcher = RANGE_PATTERN.matcher(sectionContent);
            if (rangeMatcher.find()) {
                records.add(new PerformanceRecord("range-octree-" + entityCount, "", rangeMatcher.group(1) + " μs/op"));
                records.add(new PerformanceRecord("range-tetree-" + entityCount, "", rangeMatcher.group(2) + " μs/op"));
                records.add(new PerformanceRecord("range-prism-" + entityCount, "", rangeMatcher.group(3) + " μs/op"));
            }
            
            // Extract memory usage
            Matcher memoryMatcher = MEMORY_PATTERN.matcher(sectionContent);
            if (memoryMatcher.find()) {
                records.add(new PerformanceRecord("memory-octree-" + entityCount, memoryMatcher.group(1) + " bytes", ""));
                records.add(new PerformanceRecord("memory-tetree-" + entityCount, memoryMatcher.group(2) + " bytes", ""));
                records.add(new PerformanceRecord("memory-prism-" + entityCount, memoryMatcher.group(3) + " bytes", ""));
            }
        }
        
        // If no entity count sections found, try extracting without entity counts
        if (records.isEmpty()) {
            // Extract insertion performance
            Matcher insertMatcher = INSERTION_PATTERN.matcher(content);
            if (insertMatcher.find()) {
                records.add(new PerformanceRecord("insert-octree", insertMatcher.group(1) + " μs/op", ""));
                records.add(new PerformanceRecord("insert-tetree", insertMatcher.group(2) + " μs/op", ""));
                records.add(new PerformanceRecord("insert-prism", insertMatcher.group(3) + " μs/op", ""));
            }
            
            // Extract k-NN performance
            Matcher knnMatcher = KNN_PATTERN.matcher(content);
            if (knnMatcher.find()) {
                records.add(new PerformanceRecord("knn-octree", "", knnMatcher.group(1) + " μs/op"));
                records.add(new PerformanceRecord("knn-tetree", "", knnMatcher.group(2) + " μs/op"));
                records.add(new PerformanceRecord("knn-prism", "", knnMatcher.group(3) + " μs/op"));
            }
            
            // Extract range query performance
            Matcher rangeMatcher = RANGE_PATTERN.matcher(content);
            if (rangeMatcher.find()) {
                records.add(new PerformanceRecord("range-octree", "", rangeMatcher.group(1) + " μs/op"));
                records.add(new PerformanceRecord("range-tetree", "", rangeMatcher.group(2) + " μs/op"));
                records.add(new PerformanceRecord("range-prism", "", rangeMatcher.group(3) + " μs/op"));
            }
            
            // Extract memory usage
            Matcher memoryMatcher = MEMORY_PATTERN.matcher(content);
            if (memoryMatcher.find()) {
                records.add(new PerformanceRecord("memory-octree", memoryMatcher.group(1) + " bytes", ""));
                records.add(new PerformanceRecord("memory-tetree", memoryMatcher.group(2) + " bytes", ""));
                records.add(new PerformanceRecord("memory-prism", memoryMatcher.group(3) + " bytes", ""));
            }
        }
        
        return records;
    }
    
    private static void writeCsv(List<PerformanceRecord> records, Path outputPath) throws IOException {
        // Ensure parent directory exists
        Files.createDirectories(outputPath.getParent());
        
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            // Write CSV header
            writer.write("operation,throughput,latency\n");
            
            // Write records
            for (PerformanceRecord record : records) {
                writer.write(String.format("%s,%s,%s\n", 
                    record.operation, 
                    record.throughput.isEmpty() ? "N/A" : record.throughput,
                    record.latency.isEmpty() ? "N/A" : record.latency
                ));
            }
        }
    }
    
    static class PerformanceRecord {
        final String operation;
        final String throughput;
        final String latency;
        
        PerformanceRecord(String operation, String throughput, String latency) {
            this.operation = operation;
            this.throughput = throughput;
            this.latency = latency;
        }
    }
}