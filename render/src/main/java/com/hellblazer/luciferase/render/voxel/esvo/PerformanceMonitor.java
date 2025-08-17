package com.hellblazer.luciferase.render.voxel.esvo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance monitoring for ESVO pipeline.
 * Tracks phase timings, metrics, and generates reports.
 */
public class PerformanceMonitor {
    
    private final Map<String, PhaseStatistics> phaseStats;
    private final Map<String, AtomicLong> metrics;
    private final Map<String, Long> phaseStartTimes;
    
    // Frame tracking
    private long frameCount;
    private long lastFrameTime;
    private double averageFPS;
    private double minFPS;
    private double maxFPS;
    
    public PerformanceMonitor() {
        this.phaseStats = new ConcurrentHashMap<>();
        this.metrics = new ConcurrentHashMap<>();
        this.phaseStartTimes = new ConcurrentHashMap<>();
        this.frameCount = 0;
        this.lastFrameTime = System.nanoTime();
        this.averageFPS = 0;
        this.minFPS = Double.MAX_VALUE;
        this.maxFPS = 0;
    }
    
    /**
     * Start timing a phase
     */
    public void startPhase(String phaseName) {
        phaseStartTimes.put(phaseName, System.nanoTime());
    }
    
    /**
     * End timing a phase
     */
    public void endPhase(String phaseName) {
        Long startTime = phaseStartTimes.remove(phaseName);
        if (startTime != null) {
            long duration = System.nanoTime() - startTime;
            
            PhaseStatistics stats = phaseStats.computeIfAbsent(phaseName, 
                k -> new PhaseStatistics(phaseName));
            stats.recordSample(duration);
        }
    }
    
    /**
     * Record a metric value
     */
    public void recordMetric(String metricName, long value) {
        metrics.computeIfAbsent(metricName, k -> new AtomicLong(0))
               .addAndGet(value);
    }
    
    /**
     * Record a metric value
     */
    public void recordMetric(String metricName, double value) {
        // Store as fixed-point (multiply by 1000)
        recordMetric(metricName, (long)(value * 1000));
    }
    
    /**
     * Mark frame completion
     */
    public void markFrame() {
        long currentTime = System.nanoTime();
        long frameDuration = currentTime - lastFrameTime;
        
        if (frameDuration > 0) {
            double fps = 1_000_000_000.0 / frameDuration;
            
            // Update FPS statistics
            frameCount++;
            averageFPS = ((averageFPS * (frameCount - 1)) + fps) / frameCount;
            minFPS = Math.min(minFPS, fps);
            maxFPS = Math.max(maxFPS, fps);
        }
        
        lastFrameTime = currentTime;
    }
    
    /**
     * Generate performance report
     */
    public PerformanceReport generateReport() {
        Map<String, PhaseReport> phaseReports = new HashMap<>();
        
        for (Map.Entry<String, PhaseStatistics> entry : phaseStats.entrySet()) {
            PhaseStatistics stats = entry.getValue();
            phaseReports.put(entry.getKey(), stats.generateReport());
        }
        
        Map<String, Long> metricValues = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : metrics.entrySet()) {
            metricValues.put(entry.getKey(), entry.getValue().get());
        }
        
        return new PerformanceReport(
            phaseReports,
            metricValues,
            frameCount,
            averageFPS,
            minFPS,
            maxFPS
        );
    }
    
    /**
     * Reset all statistics
     */
    public void reset() {
        phaseStats.clear();
        metrics.clear();
        phaseStartTimes.clear();
        frameCount = 0;
        lastFrameTime = System.nanoTime();
        averageFPS = 0;
        minFPS = Double.MAX_VALUE;
        maxFPS = 0;
    }
    
    /**
     * Phase statistics tracking
     */
    private static class PhaseStatistics {
        private final String name;
        private long sampleCount;
        private long totalTime;
        private long minTime;
        private long maxTime;
        private double averageTime;
        
        PhaseStatistics(String name) {
            this.name = name;
            this.sampleCount = 0;
            this.totalTime = 0;
            this.minTime = Long.MAX_VALUE;
            this.maxTime = 0;
            this.averageTime = 0;
        }
        
        synchronized void recordSample(long duration) {
            sampleCount++;
            totalTime += duration;
            minTime = Math.min(minTime, duration);
            maxTime = Math.max(maxTime, duration);
            averageTime = (double) totalTime / sampleCount;
        }
        
        PhaseReport generateReport() {
            return new PhaseReport(
                name,
                sampleCount,
                nanosToMillis(totalTime),
                nanosToMillis(minTime),
                nanosToMillis(maxTime),
                nanosToMillis((long) averageTime)
            );
        }
        
        private double nanosToMillis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }
    
    /**
     * Phase performance report
     */
    public static class PhaseReport {
        public final String name;
        public final long sampleCount;
        public final double totalTimeMs;
        public final double minTimeMs;
        public final double maxTimeMs;
        public final double averageTimeMs;
        
        public PhaseReport(String name, long sampleCount, 
                          double totalTimeMs, double minTimeMs,
                          double maxTimeMs, double averageTimeMs) {
            this.name = name;
            this.sampleCount = sampleCount;
            this.totalTimeMs = totalTimeMs;
            this.minTimeMs = minTimeMs;
            this.maxTimeMs = maxTimeMs;
            this.averageTimeMs = averageTimeMs;
        }
        
        @Override
        public String toString() {
            return String.format("%s: avg=%.2fms, min=%.2fms, max=%.2fms, samples=%d",
                name, averageTimeMs, minTimeMs, maxTimeMs, sampleCount);
        }
    }
    
    /**
     * Complete performance report
     */
    public static class PerformanceReport {
        public final Map<String, PhaseReport> phases;
        public final Map<String, Long> metrics;
        public final long frameCount;
        public final double averageFPS;
        public final double minFPS;
        public final double maxFPS;
        
        public PerformanceReport(Map<String, PhaseReport> phases,
                                Map<String, Long> metrics,
                                long frameCount,
                                double averageFPS,
                                double minFPS,
                                double maxFPS) {
            this.phases = phases;
            this.metrics = metrics;
            this.frameCount = frameCount;
            this.averageFPS = averageFPS;
            this.minFPS = minFPS;
            this.maxFPS = maxFPS;
        }
        
        public String generateSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== ESVO Performance Report ===\n");
            sb.append(String.format("Frames: %d, FPS: avg=%.1f, min=%.1f, max=%.1f\n",
                frameCount, averageFPS, minFPS, maxFPS));
            
            sb.append("\nPhase Timings:\n");
            for (PhaseReport phase : phases.values()) {
                sb.append("  ").append(phase).append("\n");
            }
            
            sb.append("\nMetrics:\n");
            for (Map.Entry<String, Long> entry : metrics.entrySet()) {
                String name = entry.getKey();
                long value = entry.getValue();
                
                // Handle fixed-point values
                if (name.contains("efficiency") || name.contains("ratio")) {
                    sb.append(String.format("  %s: %.3f\n", name, value / 1000.0));
                } else {
                    sb.append(String.format("  %s: %d\n", name, value));
                }
            }
            
            return sb.toString();
        }
    }
}