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
package com.hellblazer.luciferase.portal.collision;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Performance profiler for collision detection system.
 * Tracks timing statistics, collision pair frequencies, and bottlenecks.
 *
 * @author hal.hildebrand
 */
public class CollisionProfiler {
    
    // Timing statistics
    private final ConcurrentHashMap<String, TimingStats> timingStats = new ConcurrentHashMap<>();
    
    // Collision pair frequency tracking
    private final ConcurrentHashMap<String, LongAdder> collisionPairCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> collisionHitCounts = new ConcurrentHashMap<>();
    
    // Frame-based statistics
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong totalFrameTime = new AtomicLong(0);
    
    // Hot path detection
    private final ConcurrentHashMap<String, HotPathStats> hotPaths = new ConcurrentHashMap<>();
    
    private static final CollisionProfiler INSTANCE = new CollisionProfiler();
    
    public static CollisionProfiler getInstance() {
        return INSTANCE;
    }
    
    /**
     * Start timing an operation.
     *
     * @param operation the operation name
     * @return timing context for stopping the timer
     */
    public TimingContext startTiming(String operation) {
        return new TimingContext(operation, System.nanoTime());
    }
    
    /**
     * Record timing for an operation.
     */
    public void recordTiming(String operation, long durationNanos) {
        timingStats.computeIfAbsent(operation, k -> new TimingStats()).addSample(durationNanos);
        
        // Track hot paths
        if (durationNanos > 1_000_000) { // > 1ms
            hotPaths.computeIfAbsent(operation, k -> new HotPathStats()).recordSlowExecution(durationNanos);
        }
    }
    
    /**
     * Record a collision pair test.
     *
     * @param shapeTypeA first shape type
     * @param shapeTypeB second shape type
     * @param collision true if collision occurred
     */
    public void recordCollisionPair(String shapeTypeA, String shapeTypeB, boolean collision) {
        var pairKey = createPairKey(shapeTypeA, shapeTypeB);
        collisionPairCounts.computeIfAbsent(pairKey, k -> new LongAdder()).increment();
        
        if (collision) {
            collisionHitCounts.computeIfAbsent(pairKey, k -> new LongAdder()).increment();
        }
    }
    
    /**
     * Start a new frame timing.
     */
    public TimingContext startFrame() {
        return startTiming("frame");
    }
    
    /**
     * End frame timing and update frame statistics.
     */
    public void endFrame(TimingContext frameContext) {
        frameContext.stop();
        frameCount.incrementAndGet();
    }
    
    /**
     * Get timing statistics for an operation.
     */
    public TimingStats getTimingStats(String operation) {
        return timingStats.get(operation);
    }
    
    /**
     * Get all timing statistics.
     */
    public Map<String, TimingStats> getAllTimingStats() {
        return Map.copyOf(timingStats);
    }
    
    /**
     * Get collision pair statistics.
     */
    public CollisionPairStats getCollisionPairStats() {
        var pairs = new ArrayList<PairInfo>();
        
        for (var entry : collisionPairCounts.entrySet()) {
            var pairKey = entry.getKey();
            var totalTests = entry.getValue().sum();
            var hits = collisionHitCounts.getOrDefault(pairKey, new LongAdder()).sum();
            var hitRate = totalTests > 0 ? (double) hits / totalTests : 0.0;
            
            pairs.add(new PairInfo(pairKey, totalTests, hits, hitRate));
        }
        
        return new CollisionPairStats(pairs);
    }
    
    /**
     * Get performance summary.
     */
    public PerformanceSummary getPerformanceSummary() {
        var totalFrames = frameCount.get();
        var frameStats = timingStats.get("frame");
        var avgFrameTime = frameStats != null ? frameStats.getAverageNanos() : 0;
        var fps = avgFrameTime > 0 ? 1_000_000_000.0 / avgFrameTime : 0;
        
        // Find top bottlenecks
        var bottlenecks = timingStats.entrySet().stream()
            .filter(e -> !e.getKey().equals("frame"))
            .sorted((a, b) -> Long.compare(b.getValue().getTotalNanos(), a.getValue().getTotalNanos()))
            .limit(5)
            .map(e -> new Bottleneck(e.getKey(), e.getValue().getTotalNanos(), e.getValue().getAverageNanos()))
            .collect(Collectors.toList());
        
        return new PerformanceSummary(totalFrames, fps, avgFrameTime, bottlenecks);
    }
    
    /**
     * Get hot path analysis.
     */
    public List<HotPathInfo> getHotPaths() {
        return hotPaths.entrySet().stream()
            .map(e -> new HotPathInfo(e.getKey(), e.getValue()))
            .sorted((a, b) -> Long.compare(b.stats.slowExecutions, a.stats.slowExecutions))
            .collect(Collectors.toList());
    }
    
    /**
     * Reset all profiling data.
     */
    public void reset() {
        timingStats.clear();
        collisionPairCounts.clear();
        collisionHitCounts.clear();
        hotPaths.clear();
        frameCount.set(0);
        totalFrameTime.set(0);
    }
    
    /**
     * Generate a detailed profiling report.
     */
    public String generateReport() {
        var report = new StringBuilder();
        
        // Performance summary
        var summary = getPerformanceSummary();
        report.append("=== Collision System Performance Report ===\n");
        report.append(String.format("Total Frames: %d\n", summary.totalFrames));
        report.append(String.format("Average FPS: %.2f\n", summary.fps));
        report.append(String.format("Average Frame Time: %.3f ms\n", summary.avgFrameTimeNanos / 1_000_000.0));
        report.append("\n");
        
        // Top bottlenecks
        report.append("=== Top Performance Bottlenecks ===\n");
        for (var bottleneck : summary.bottlenecks) {
            report.append(String.format("%-20s: %8.3f ms total, %8.3f ms avg\n", 
                bottleneck.operation, 
                bottleneck.totalNanos / 1_000_000.0,
                bottleneck.avgNanos / 1_000_000.0));
        }
        report.append("\n");
        
        // Timing details
        report.append("=== Detailed Timing Statistics ===\n");
        report.append(String.format("%-20s %8s %8s %8s %8s %8s\n", 
            "Operation", "Count", "Total(ms)", "Avg(ms)", "Min(ms)", "Max(ms)"));
        report.append("-".repeat(80)).append("\n");
        
        for (var entry : timingStats.entrySet()) {
            var stats = entry.getValue();
            report.append(String.format("%-20s %8d %8.3f %8.3f %8.3f %8.3f\n",
                entry.getKey(),
                stats.getSampleCount(),
                stats.getTotalNanos() / 1_000_000.0,
                stats.getAverageNanos() / 1_000_000.0,
                stats.getMinNanos() / 1_000_000.0,
                stats.getMaxNanos() / 1_000_000.0));
        }
        report.append("\n");
        
        // Collision pair statistics
        var pairStats = getCollisionPairStats();
        report.append("=== Collision Pair Statistics ===\n");
        report.append(String.format("%-30s %8s %8s %8s\n", "Shape Pair", "Tests", "Hits", "Hit Rate"));
        report.append("-".repeat(60)).append("\n");
        
        for (var pair : pairStats.pairs) {
            report.append(String.format("%-30s %8d %8d %8.2f%%\n",
                pair.pairKey,
                pair.totalTests,
                pair.hits,
                pair.hitRate * 100));
        }
        report.append("\n");
        
        // Hot paths
        var hotPaths = getHotPaths();
        if (!hotPaths.isEmpty()) {
            report.append("=== Hot Paths (>1ms executions) ===\n");
            for (var hotPath : hotPaths) {
                report.append(String.format("%-20s: %d slow executions, max: %.3f ms\n",
                    hotPath.operation,
                    hotPath.stats.slowExecutions,
                    hotPath.stats.maxSlowDuration / 1_000_000.0));
            }
        }
        
        return report.toString();
    }
    
    /**
     * Create a standardized key for shape pairs.
     */
    private String createPairKey(String shapeA, String shapeB) {
        // Ensure consistent ordering
        return shapeA.compareTo(shapeB) <= 0 ? shapeA + " vs " + shapeB : shapeB + " vs " + shapeA;
    }
    
    /**
     * Timing context for measuring operation duration.
     */
    public class TimingContext {
        private final String operation;
        private final long startTime;
        
        TimingContext(String operation, long startTime) {
            this.operation = operation;
            this.startTime = startTime;
        }
        
        /**
         * Stop timing and record the result.
         */
        public void stop() {
            var duration = System.nanoTime() - startTime;
            recordTiming(operation, duration);
        }
    }
    
    /**
     * Statistics for a timed operation.
     */
    public static class TimingStats {
        private final AtomicLong sampleCount = new AtomicLong(0);
        private final AtomicLong totalNanos = new AtomicLong(0);
        private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxNanos = new AtomicLong(Long.MIN_VALUE);
        
        void addSample(long durationNanos) {
            sampleCount.incrementAndGet();
            totalNanos.addAndGet(durationNanos);
            
            // Update min
            long currentMin;
            do {
                currentMin = minNanos.get();
                if (durationNanos >= currentMin) break;
            } while (!minNanos.compareAndSet(currentMin, durationNanos));
            
            // Update max
            long currentMax;
            do {
                currentMax = maxNanos.get();
                if (durationNanos <= currentMax) break;
            } while (!maxNanos.compareAndSet(currentMax, durationNanos));
        }
        
        public long getSampleCount() { return sampleCount.get(); }
        public long getTotalNanos() { return totalNanos.get(); }
        public long getMinNanos() { return minNanos.get(); }
        public long getMaxNanos() { return maxNanos.get(); }
        public double getAverageNanos() { 
            long count = sampleCount.get();
            return count > 0 ? (double) totalNanos.get() / count : 0.0;
        }
    }
    
    /**
     * Hot path tracking for slow operations.
     */
    public static class HotPathStats {
        private volatile long slowExecutions = 0;
        private volatile long maxSlowDuration = 0;
        
        void recordSlowExecution(long durationNanos) {
            slowExecutions++;
            if (durationNanos > maxSlowDuration) {
                maxSlowDuration = durationNanos;
            }
        }
    }
    
    // Data classes for reporting
    public record PairInfo(String pairKey, long totalTests, long hits, double hitRate) {}
    
    public record CollisionPairStats(List<PairInfo> pairs) {}
    
    public record Bottleneck(String operation, long totalNanos, double avgNanos) {}
    
    public record PerformanceSummary(
        long totalFrames, 
        double fps, 
        double avgFrameTimeNanos, 
        List<Bottleneck> bottlenecks
    ) {}
    
    public record HotPathInfo(String operation, HotPathStats stats) {}
}