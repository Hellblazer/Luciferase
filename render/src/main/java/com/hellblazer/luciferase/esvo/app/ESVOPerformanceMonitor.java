package com.hellblazer.luciferase.esvo.app;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance monitoring system for ESVO applications.
 * Tracks frame rates, traversal metrics, and GPU utilization.
 */
public class ESVOPerformanceMonitor {
    
    // Frame timing
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong totalFrameTime = new AtomicLong(0);
    private long lastFrameTime = System.nanoTime();
    private double currentFPS = 0.0;
    
    // Traversal statistics
    private final AtomicLong totalRaysTraced = new AtomicLong(0);
    private final AtomicLong totalNodesVisited = new AtomicLong(0);
    private final AtomicLong totalVoxelsHit = new AtomicLong(0);
    
    // GPU utilization (if available)
    private double gpuUtilization = 0.0;
    private long gpuMemoryUsed = 0;
    private long gpuMemoryTotal = 0;
    
    // Performance windows
    private static final int FPS_WINDOW_SIZE = 60;
    private final double[] recentFrameTimes = new double[FPS_WINDOW_SIZE];
    private int frameTimeIndex = 0;
    private boolean frameTimeBufferFull = false;
    
    // Update tracking
    private long lastUpdateTime = System.nanoTime();
    private static final long UPDATE_INTERVAL_NS = 1_000_000_000; // 1 second
    
    public ESVOPerformanceMonitor() {
        reset();
    }
    
    /**
     * Record start of a new frame
     */
    public void startFrame() {
        var currentTime = System.nanoTime();
        var frameDuration = currentTime - lastFrameTime;
        
        // Update frame statistics
        frameCount.incrementAndGet();
        totalFrameTime.addAndGet(frameDuration);
        
        // Update rolling window
        recentFrameTimes[frameTimeIndex] = frameDuration / 1_000_000.0; // Convert to milliseconds
        frameTimeIndex = (frameTimeIndex + 1) % FPS_WINDOW_SIZE;
        if (frameTimeIndex == 0) {
            frameTimeBufferFull = true;
        }
        
        lastFrameTime = currentTime;
        
        // Update FPS calculation periodically
        if (currentTime - lastUpdateTime > UPDATE_INTERVAL_NS) {
            updateFPSCalculation();
            lastUpdateTime = currentTime;
        }
    }
    
    /**
     * Record ray traversal statistics
     */
    public void recordTraversal(int raysTraced, int nodesVisited, int voxelsHit) {
        totalRaysTraced.addAndGet(raysTraced);
        totalNodesVisited.addAndGet(nodesVisited);
        totalVoxelsHit.addAndGet(voxelsHit);
    }
    
    /**
     * Update GPU utilization metrics (called by GPU integration)
     */
    public void updateGPUMetrics(double utilization, long memoryUsed, long memoryTotal) {
        this.gpuUtilization = Math.max(0.0, Math.min(100.0, utilization));
        this.gpuMemoryUsed = Math.max(0, memoryUsed);
        this.gpuMemoryTotal = Math.max(memoryUsed, memoryTotal);
    }
    
    /**
     * Get current frames per second
     */
    public double getCurrentFPS() {
        return currentFPS;
    }
    
    /**
     * Get average frame time in milliseconds
     */
    public double getAverageFrameTime() {
        if (frameCount.get() == 0) return 0.0;
        return (totalFrameTime.get() / 1_000_000.0) / frameCount.get();
    }
    
    /**
     * Get recent frame time statistics
     */
    public FrameStats getRecentFrameStats() {
        if (!frameTimeBufferFull && frameTimeIndex == 0) {
            return new FrameStats(0.0, 0.0, 0.0, 0.0);
        }
        
        var count = frameTimeBufferFull ? FPS_WINDOW_SIZE : frameTimeIndex;
        var sum = 0.0;
        var min = Double.MAX_VALUE;
        var max = Double.MIN_VALUE;
        
        for (int i = 0; i < count; i++) {
            var frameTime = recentFrameTimes[i];
            sum += frameTime;
            min = Math.min(min, frameTime);
            max = Math.max(max, frameTime);
        }
        
        var avg = sum / count;
        
        // Calculate standard deviation
        var variance = 0.0;
        for (int i = 0; i < count; i++) {
            var diff = recentFrameTimes[i] - avg;
            variance += diff * diff;
        }
        var stdDev = Math.sqrt(variance / count);
        
        return new FrameStats(avg, min, max, stdDev);
    }
    
    /**
     * Get total frame count
     */
    public long getTotalFrameCount() {
        return frameCount.get();
    }
    
    /**
     * Get total rays traced
     */
    public long getTotalRaysTraced() {
        return totalRaysTraced.get();
    }
    
    /**
     * Get total nodes visited during traversal
     */
    public long getTotalNodesVisited() {
        return totalNodesVisited.get();
    }
    
    /**
     * Get total voxels hit
     */
    public long getTotalVoxelsHit() {
        return totalVoxelsHit.get();
    }
    
    /**
     * Get average nodes visited per ray
     */
    public double getAverageNodesPerRay() {
        var rays = totalRaysTraced.get();
        return rays == 0 ? 0.0 : (double) totalNodesVisited.get() / rays;
    }
    
    /**
     * Get voxel hit rate (percentage)
     */
    public double getVoxelHitRate() {
        var rays = totalRaysTraced.get();
        return rays == 0 ? 0.0 : (100.0 * totalVoxelsHit.get()) / rays;
    }
    
    /**
     * Get GPU utilization percentage
     */
    public double getGPUUtilization() {
        return gpuUtilization;
    }
    
    /**
     * Get GPU memory usage in bytes
     */
    public long getGPUMemoryUsed() {
        return gpuMemoryUsed;
    }
    
    /**
     * Get GPU memory total in bytes
     */
    public long getGPUMemoryTotal() {
        return gpuMemoryTotal;
    }
    
    /**
     * Get GPU memory usage percentage
     */
    public double getGPUMemoryUsagePercentage() {
        return gpuMemoryTotal == 0 ? 0.0 : (100.0 * gpuMemoryUsed) / gpuMemoryTotal;
    }
    
    /**
     * Reset all statistics
     */
    public void reset() {
        frameCount.set(0);
        totalFrameTime.set(0);
        totalRaysTraced.set(0);
        totalNodesVisited.set(0);
        totalVoxelsHit.set(0);
        
        lastFrameTime = System.nanoTime();
        lastUpdateTime = lastFrameTime;
        currentFPS = 0.0;
        
        frameTimeIndex = 0;
        frameTimeBufferFull = false;
        
        gpuUtilization = 0.0;
        gpuMemoryUsed = 0;
        gpuMemoryTotal = 0;
    }
    
    /**
     * Get comprehensive performance summary
     */
    public PerformanceSummary getPerformanceSummary() {
        var frameStats = getRecentFrameStats();
        
        return new PerformanceSummary(
            getCurrentFPS(),
            getAverageFrameTime(),
            frameStats,
            getTotalFrameCount(),
            getTotalRaysTraced(),
            getTotalNodesVisited(),
            getTotalVoxelsHit(),
            getAverageNodesPerRay(),
            getVoxelHitRate(),
            getGPUUtilization(),
            getGPUMemoryUsagePercentage()
        );
    }
    
    /**
     * Update FPS calculation based on recent frame times
     */
    private void updateFPSCalculation() {
        var frameStats = getRecentFrameStats();
        if (frameStats.averageMs > 0) {
            currentFPS = 1000.0 / frameStats.averageMs;
        }
    }
    
    /**
     * Frame timing statistics
     */
    public record FrameStats(
        double averageMs,
        double minMs,
        double maxMs,
        double stdDevMs
    ) {}
    
    /**
     * Comprehensive performance summary
     */
    public record PerformanceSummary(
        double currentFPS,
        double averageFrameTimeMs,
        FrameStats recentFrameStats,
        long totalFrameCount,
        long totalRaysTraced,
        long totalNodesVisited,
        long totalVoxelsHit,
        double averageNodesPerRay,
        double voxelHitRatePercentage,
        double gpuUtilizationPercentage,
        double gpuMemoryUsagePercentage
    ) {
        @Override
        public String toString() {
            return String.format(
                "Performance Summary:%n" +
                "  FPS: %.1f (avg frame time: %.2f ms)%n" +
                "  Recent frames: avg=%.2f ms, min=%.2f ms, max=%.2f ms, stddev=%.2f ms%n" +
                "  Total frames: %,d%n" +
                "  Ray traversal: %,d rays, %,d nodes visited, %,d voxels hit%n" +
                "  Efficiency: %.1f nodes/ray, %.1f%% voxel hit rate%n" +
                "  GPU: %.1f%% utilization, %.1f%% memory usage",
                currentFPS, averageFrameTimeMs,
                recentFrameStats.averageMs, recentFrameStats.minMs, recentFrameStats.maxMs, recentFrameStats.stdDevMs,
                totalFrameCount,
                totalRaysTraced, totalNodesVisited, totalVoxelsHit,
                averageNodesPerRay, voxelHitRatePercentage,
                gpuUtilizationPercentage, gpuMemoryUsagePercentage
            );
        }
    }
}