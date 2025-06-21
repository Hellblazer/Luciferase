/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.performance;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Performance metrics collection and reporting
 *
 * @author hal.hildebrand
 */
public class PerformanceMetrics {
    private final String operation;
    private final long entityCount;
    private final long elapsedNanos;
    private final long memoryUsedBytes;
    private final Map<String, Object> additionalMetrics;
    
    public PerformanceMetrics(String operation, long entityCount, long elapsedNanos, 
                            long memoryUsedBytes, Map<String, Object> additionalMetrics) {
        this.operation = operation;
        this.entityCount = entityCount;
        this.elapsedNanos = elapsedNanos;
        this.memoryUsedBytes = memoryUsedBytes;
        this.additionalMetrics = additionalMetrics;
    }
    
    // Getters
    public String getOperation() { return operation; }
    public long getEntityCount() { return entityCount; }
    public long getElapsedNanos() { return elapsedNanos; }
    public long getMemoryUsedBytes() { return memoryUsedBytes; }
    public Map<String, Object> getAdditionalMetrics() { return additionalMetrics; }
    
    // Calculated metrics
    public double getElapsedMillis() {
        return elapsedNanos / 1_000_000.0;
    }
    
    public double getElapsedSeconds() {
        return elapsedNanos / 1_000_000_000.0;
    }
    
    public double getOperationsPerSecond() {
        return entityCount > 0 ? entityCount / getElapsedSeconds() : 0;
    }
    
    public double getNanosPerOperation() {
        return entityCount > 0 ? (double) elapsedNanos / entityCount : 0;
    }
    
    public double getMemoryPerEntity() {
        return entityCount > 0 ? (double) memoryUsedBytes / entityCount : 0;
    }
    
    public double getMemoryUsedMB() {
        return memoryUsedBytes / (1024.0 * 1024.0);
    }
    
    /**
     * Calculate speedup factor compared to baseline
     */
    public double getSpeedup(PerformanceMetrics baseline) {
        return baseline.getElapsedNanos() / (double) this.elapsedNanos;
    }
    
    /**
     * Export metrics to CSV format
     */
    public static void exportToCSV(List<PerformanceMetrics> metrics, Path outputPath) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath))) {
            // Write header
            writer.println("Operation,EntityCount,ElapsedMs,OpsPerSec,NanosPerOp,MemoryUsedMB,MemoryPerEntity,Implementation");
            
            // Write data
            for (PerformanceMetrics metric : metrics) {
                writer.printf("%s,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%s%n",
                    metric.getOperation(),
                    metric.getEntityCount(),
                    metric.getElapsedMillis(),
                    metric.getOperationsPerSecond(),
                    metric.getNanosPerOperation(),
                    metric.getMemoryUsedMB(),
                    metric.getMemoryPerEntity(),
                    metric.getAdditionalMetrics().getOrDefault("implementation", "Unknown")
                );
            }
        }
    }
    
    /**
     * Calculate average metrics from multiple runs
     */
    public static PerformanceMetrics average(List<PerformanceMetrics> metrics) {
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("Cannot average empty metrics list");
        }
        
        PerformanceMetrics first = metrics.get(0);
        long totalNanos = 0;
        long totalMemory = 0;
        
        for (PerformanceMetrics m : metrics) {
            totalNanos += m.elapsedNanos;
            totalMemory += m.memoryUsedBytes;
        }
        
        return new PerformanceMetrics(
            first.operation,
            first.entityCount,
            totalNanos / metrics.size(),
            totalMemory / metrics.size(),
            first.additionalMetrics
        );
    }
    
    @Override
    public String toString() {
        return String.format("PerformanceMetrics[operation=%s, entities=%d, elapsed=%.2fms, ops/sec=%.2f, memory=%.2fMB]",
            operation, entityCount, getElapsedMillis(), getOperationsPerSecond(), getMemoryUsedMB());
    }
}