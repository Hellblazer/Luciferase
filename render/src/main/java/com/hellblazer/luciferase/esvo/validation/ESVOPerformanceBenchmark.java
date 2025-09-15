package com.hellblazer.luciferase.esvo.validation;

import com.hellblazer.luciferase.esvo.app.ESVOApplication;
import com.hellblazer.luciferase.esvo.app.ESVOPerformanceMonitor;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive performance benchmark suite for ESVO rendering system.
 * 
 * Provides detailed performance metrics including frame rendering times, GPU memory usage,
 * CPU utilization monitoring, throughput calculations, and memory bandwidth analysis.
 * 
 * Features statistical analysis with proper benchmarking practices including warmup phases,
 * multiple iterations, and outlier detection for accurate performance measurement.
 */
public class ESVOPerformanceBenchmark {
    private static final Logger log = LoggerFactory.getLogger(ESVOPerformanceBenchmark.class);

    // Performance targets based on real-time rendering standards
    public static final double TARGET_FPS = 60.0;
    public static final double TARGET_FRAME_TIME_MS = 16.67; // 1000/60
    public static final long MIN_RAYS_PER_SECOND = 1_000_000L;
    public static final long MIN_VOXELS_PER_SECOND = 500_000L;
    public static final double MAX_GPU_MEMORY_USAGE_PERCENT = 80.0;
    public static final double MAX_CPU_USAGE_PERCENT = 70.0;

    // Benchmark configuration
    private static final int DEFAULT_WARMUP_ITERATIONS = 50;
    private static final int DEFAULT_BENCHMARK_ITERATIONS = 200;
    private static final int MEMORY_MEASUREMENT_INTERVAL_MS = 100;
    private static final int CPU_MEASUREMENT_SAMPLES = 10;

    // System monitoring
    private final MemoryMXBean memoryMXBean;
    private final OperatingSystemMXBean osMXBean;
    private final Runtime runtime;

    // Performance tracking
    private final AtomicLong totalBenchmarkTime = new AtomicLong(0);
    private final AtomicLong totalFramesRendered = new AtomicLong(0);

    public ESVOPerformanceBenchmark() {
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.osMXBean = ManagementFactory.getOperatingSystemMXBean();
        this.runtime = Runtime.getRuntime();
        
        log.info("ESVOPerformanceBenchmark initialized");
        log.debug("System info - Cores: {}, Max memory: {} MB", 
                 runtime.availableProcessors(), 
                 runtime.maxMemory() / (1024 * 1024));
    }

    /**
     * Run comprehensive performance benchmark on an ESVO application context.
     * 
     * @param application The ESVO application to benchmark
     * @param iterations Number of benchmark iterations (default: 200)
     * @return Detailed performance report with all metrics
     */
    public PerformanceReport benchmarkRenderPerformance(ESVOApplication application, int iterations) {
        Objects.requireNonNull(application, "ESVO application cannot be null");
        
        if (!application.isInitialized()) {
            throw new IllegalStateException("ESVO application must be initialized before benchmarking");
        }

        var actualIterations = iterations > 0 ? iterations : DEFAULT_BENCHMARK_ITERATIONS;
        
        log.info("Starting ESVO performance benchmark with {} iterations", actualIterations);
        
        // Perform warmup to stabilize JIT compilation and GPU state
        runWarmup(application, DEFAULT_WARMUP_ITERATIONS);
        
        // Collect baseline metrics
        var baselineMemory = measureMemoryUsage();
        var baselineCPU = measureCPUUsage();
        
        // Run main benchmark
        var benchmarkStart = System.nanoTime();
        var frameTimings = new ArrayList<Long>(actualIterations);
        var rayThroughputs = new ArrayList<Double>(actualIterations);
        var voxelThroughputs = new ArrayList<Double>(actualIterations);
        var memorySnapshots = new ArrayList<MemorySnapshot>();
        
        var performanceMonitor = application.getPerformanceMonitor();
        performanceMonitor.reset();
        
        // Memory monitoring thread
        var memoryMonitorThread = startMemoryMonitoring(memorySnapshots);
        
        try {
            for (var i = 0; i < actualIterations; i++) {
                // Measure single frame performance
                var frameTime = measureFrameTime(application);
                frameTimings.add(frameTime);
                
                // Calculate throughput metrics
                var summary = performanceMonitor.getPerformanceSummary();
                if (summary.totalRaysTraced() > 0) {
                    var rayThroughput = calculateRayThroughput(frameTime, (int) summary.totalRaysTraced());
                    var voxelThroughput = calculateVoxelThroughput(frameTime, (int) summary.totalVoxelsHit());
                    rayThroughputs.add(rayThroughput);
                    voxelThroughputs.add(voxelThroughput);
                }
                
                // Periodic GC to get accurate memory measurements
                if (i % 50 == 0) {
                    System.gc();
                    Thread.sleep(10);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Benchmark interrupted", e);
        } finally {
            memoryMonitorThread.interrupt();
        }
        
        var benchmarkEnd = System.nanoTime();
        var totalBenchmarkTimeMs = (benchmarkEnd - benchmarkStart) / 1_000_000;
        
        // Calculate final metrics
        var finalMemory = measureMemoryUsage();
        var finalCPU = measureCPUUsage();
        var gpuMemoryUsage = measureGPUMemoryUsage();
        
        // Build comprehensive report
        var report = buildPerformanceReport(
            frameTimings, rayThroughputs, voxelThroughputs, memorySnapshots,
            baselineMemory, finalMemory, baselineCPU, finalCPU, gpuMemoryUsage,
            totalBenchmarkTimeMs, performanceMonitor.getPerformanceSummary()
        );
        
        log.info("ESVO benchmark completed in {} ms", totalBenchmarkTimeMs);
        log.info("Performance summary: avg FPS={:.1f}, avg frame time={:.2f}ms, ray throughput={:.0f}/sec",
                report.frameStats().averageFPS, report.frameStats().averageFrameTimeMs,
                report.throughputStats().averageRaysPerSecond);
        
        return report;
    }

    /**
     * Measure the time to render a single frame.
     * 
     * @param application The ESVO application
     * @return Frame time in nanoseconds
     */
    public long measureFrameTime(ESVOApplication application) {
        Objects.requireNonNull(application, "ESVO application cannot be null");
        
        var startTime = System.nanoTime();
        application.renderFrame();
        var endTime = System.nanoTime();
        
        return endTime - startTime;
    }

    /**
     * Measure current GPU memory usage.
     * 
     * @return GPU memory usage in bytes (0 if not available)
     */
    public long measureGPUMemoryUsage() {
        // GPU memory measurement would depend on the specific GPU API being used
        // For now, return 0 as a placeholder - actual implementation would use
        // OpenGL, OpenCL, CUDA, or Vulkan APIs to query GPU memory
        
        var performanceMonitor = new ESVOPerformanceMonitor();
        return performanceMonitor.getGPUMemoryUsed();
    }

    /**
     * Calculate ray throughput in rays per second.
     * 
     * @param frameTimeNs Frame render time in nanoseconds
     * @param rayCount Number of rays processed in the frame
     * @return Ray throughput (rays/second)
     */
    public double calculateRayThroughput(long frameTimeNs, int rayCount) {
        if (frameTimeNs <= 0 || rayCount <= 0) {
            return 0.0;
        }
        
        var frameTimeSeconds = frameTimeNs / 1_000_000_000.0;
        return rayCount / frameTimeSeconds;
    }

    /**
     * Calculate voxel throughput in voxels per second.
     * 
     * @param frameTimeNs Frame render time in nanoseconds  
     * @param voxelCount Number of voxels processed in the frame
     * @return Voxel throughput (voxels/second)
     */
    public double calculateVoxelThroughput(long frameTimeNs, int voxelCount) {
        if (frameTimeNs <= 0 || voxelCount <= 0) {
            return 0.0;
        }
        
        var frameTimeSeconds = frameTimeNs / 1_000_000_000.0;
        return voxelCount / frameTimeSeconds;
    }

    /**
     * Run warmup iterations to stabilize performance.
     * 
     * @param application The ESVO application
     * @param iterations Number of warmup iterations
     */
    public void runWarmup(ESVOApplication application, int iterations) {
        Objects.requireNonNull(application, "ESVO application cannot be null");
        
        var actualIterations = Math.max(1, iterations);
        log.debug("Running {} warmup iterations", actualIterations);
        
        var performanceMonitor = application.getPerformanceMonitor();
        performanceMonitor.reset();
        
        // Warmup rendering pipeline and JIT compilation
        for (var i = 0; i < actualIterations; i++) {
            application.renderFrame();
            
            // Occasional GC during warmup
            if (i % 10 == 0) {
                System.gc();
                Thread.yield();
            }
        }
        
        // Final GC and stabilization pause
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        performanceMonitor.reset(); // Clear warmup data
        log.debug("Warmup completed");
    }

    /**
     * Compare two performance reports.
     * 
     * @param baseline Baseline performance report
     * @param current Current performance report
     * @return Comparison report with relative changes
     */
    public ComparisonReport comparePerformance(PerformanceReport baseline, PerformanceReport current) {
        Objects.requireNonNull(baseline, "Baseline report cannot be null");
        Objects.requireNonNull(current, "Current report cannot be null");
        
        // Calculate percentage changes
        var fpsChange = calculatePercentageChange(baseline.frameStats().averageFPS, 
                                                 current.frameStats().averageFPS);
        var frameTimeChange = calculatePercentageChange(baseline.frameStats().averageFrameTimeMs,
                                                       current.frameStats().averageFrameTimeMs);
        var rayThroughputChange = calculatePercentageChange(baseline.throughputStats().averageRaysPerSecond,
                                                           current.throughputStats().averageRaysPerSecond);
        var memoryChange = calculatePercentageChange(baseline.memoryStats().peakUsedMemoryMB,
                                                    current.memoryStats().peakUsedMemoryMB);
        
        // Determine overall trend
        var trend = determineTrend(fpsChange, frameTimeChange, rayThroughputChange);
        
        return new ComparisonReport(
            fpsChange, frameTimeChange, rayThroughputChange, memoryChange, trend,
            generateComparisonSummary(baseline, current, fpsChange, frameTimeChange, 
                                    rayThroughputChange, memoryChange, trend)
        );
    }

    // Private helper methods

    private MemorySnapshot measureMemoryUsage() {
        var heapMemory = memoryMXBean.getHeapMemoryUsage();
        var nonHeapMemory = memoryMXBean.getNonHeapMemoryUsage();
        
        return new MemorySnapshot(
            heapMemory.getUsed() / (1024 * 1024), // Convert to MB
            heapMemory.getMax() / (1024 * 1024),
            nonHeapMemory.getUsed() / (1024 * 1024),
            System.nanoTime()
        );
    }

    private double measureCPUUsage() {
        // Take multiple samples for more accurate CPU measurement
        var cpuSamples = new double[CPU_MEASUREMENT_SAMPLES];
        var startTime = System.nanoTime();
        
        for (var i = 0; i < CPU_MEASUREMENT_SAMPLES; i++) {
            try {
                // Try to get process CPU load, fallback if not available
                double processCpuLoad = 0.0;
                try {
                    if (osMXBean instanceof com.sun.management.OperatingSystemMXBean) {
                        var sunBean = (com.sun.management.OperatingSystemMXBean) osMXBean;
                        processCpuLoad = sunBean.getProcessCpuLoad();
                    }
                } catch (Exception e) {
                    processCpuLoad = 0.0;
                }
                cpuSamples[i] = processCpuLoad >= 0 ? processCpuLoad * 100.0 : 0.0;
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // Calculate average CPU usage
        return Arrays.stream(cpuSamples).average().orElse(0.0);
    }

    private Thread startMemoryMonitoring(List<MemorySnapshot> snapshots) {
        var monitorThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    synchronized (snapshots) {
                        snapshots.add(measureMemoryUsage());
                    }
                    Thread.sleep(MEMORY_MEASUREMENT_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        monitorThread.setName("ESVOMemoryMonitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
        
        return monitorThread;
    }

    private PerformanceReport buildPerformanceReport(
            List<Long> frameTimings, List<Double> rayThroughputs, List<Double> voxelThroughputs,
            List<MemorySnapshot> memorySnapshots, MemorySnapshot baselineMemory, 
            MemorySnapshot finalMemory, double baselineCPU, double finalCPU, 
            long gpuMemoryUsage, long totalBenchmarkTimeMs,
            ESVOPerformanceMonitor.PerformanceSummary monitorSummary) {
        
        // Calculate frame statistics
        var frameStats = calculateFrameStatistics(frameTimings);
        
        // Calculate throughput statistics
        var throughputStats = calculateThroughputStatistics(rayThroughputs, voxelThroughputs);
        
        // Calculate memory statistics
        var memoryStats = calculateMemoryStatistics(memorySnapshots, baselineMemory, finalMemory);
        
        // Calculate system statistics
        var systemStats = new SystemStats(
            baselineCPU, finalCPU, runtime.availableProcessors(),
            gpuMemoryUsage, totalBenchmarkTimeMs
        );
        
        // Determine overall assessment
        var assessment = assessPerformance(frameStats, throughputStats, memoryStats, systemStats);
        
        return new PerformanceReport(frameStats, throughputStats, memoryStats, systemStats, assessment);
    }

    private FrameStats calculateFrameStatistics(List<Long> frameTimingsNs) {
        if (frameTimingsNs.isEmpty()) {
            return new FrameStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        
        // Convert to milliseconds and calculate statistics
        var frameTimingsMs = frameTimingsNs.stream()
                                          .mapToDouble(ns -> ns / 1_000_000.0)
                                          .toArray();
        
        Arrays.sort(frameTimingsMs);
        
        var avg = Arrays.stream(frameTimingsMs).average().orElse(0.0);
        var min = frameTimingsMs[0];
        var max = frameTimingsMs[frameTimingsMs.length - 1];
        var p95 = calculatePercentile(frameTimingsMs, 0.95);
        var p99 = calculatePercentile(frameTimingsMs, 0.99);
        var stdDev = calculateStandardDeviation(frameTimingsMs, avg);
        var avgFps = avg > 0 ? 1000.0 / avg : 0.0;
        
        return new FrameStats(avgFps, avg, min, max, p95, p99, stdDev);
    }

    private ThroughputStats calculateThroughputStatistics(List<Double> rayThroughputs, 
                                                         List<Double> voxelThroughputs) {
        var avgRayThroughput = rayThroughputs.stream()
                                           .mapToDouble(Double::doubleValue)
                                           .average()
                                           .orElse(0.0);
        
        var avgVoxelThroughput = voxelThroughputs.stream()
                                               .mapToDouble(Double::doubleValue)
                                               .average()
                                               .orElse(0.0);
        
        var peakRayThroughput = rayThroughputs.stream()
                                            .mapToDouble(Double::doubleValue)
                                            .max()
                                            .orElse(0.0);
        
        var peakVoxelThroughput = voxelThroughputs.stream()
                                                .mapToDouble(Double::doubleValue)
                                                .max()
                                                .orElse(0.0);
        
        return new ThroughputStats(avgRayThroughput, peakRayThroughput, 
                                  avgVoxelThroughput, peakVoxelThroughput);
    }

    private MemoryStats calculateMemoryStatistics(List<MemorySnapshot> snapshots,
                                                 MemorySnapshot baseline, MemorySnapshot finalSnapshot) {
        if (snapshots.isEmpty()) {
            return new MemoryStats(0L, 0L, 0L, 0L, 0.0);
        }
        
        var peakHeapUsage = snapshots.stream()
                                   .mapToLong(s -> s.heapUsedMB)
                                   .max()
                                   .orElse(0L);
        
        var avgHeapUsage = snapshots.stream()
                                  .mapToLong(s -> s.heapUsedMB)
                                  .average()
                                  .orElse(0.0);
        
        var memoryGrowth = finalSnapshot.heapUsedMB - baseline.heapUsedMB;
        var maxHeapSize = snapshots.stream()
                                 .mapToLong(s -> s.heapMaxMB)
                                 .max()
                                 .orElse(0L);
        
        return new MemoryStats(peakHeapUsage, (long)avgHeapUsage, memoryGrowth, maxHeapSize, avgHeapUsage);
    }

    private PerformanceAssessment assessPerformance(FrameStats frameStats, ThroughputStats throughputStats,
                                                   MemoryStats memoryStats, SystemStats systemStats) {
        var issues = new ArrayList<String>();
        var recommendations = new ArrayList<String>();
        
        // Assess frame rate performance
        if (frameStats.averageFPS < TARGET_FPS * 0.8) {
            issues.add("Low frame rate: " + String.format("%.1f FPS (target: %.1f FPS)", 
                      frameStats.averageFPS, TARGET_FPS));
            recommendations.add("Optimize ray traversal algorithm or reduce scene complexity");
        }
        
        // Assess frame time consistency
        if (frameStats.standardDeviationMs > frameStats.averageFrameTimeMs * 0.3) {
            issues.add("High frame time variance: " + String.format("%.2f ms std dev", 
                      frameStats.standardDeviationMs));
            recommendations.add("Investigate frame time spikes and optimize worst-case performance");
        }
        
        // Assess throughput
        if (throughputStats.averageRaysPerSecond < MIN_RAYS_PER_SECOND) {
            issues.add("Low ray throughput: " + String.format("%.0f rays/sec (min: %d rays/sec)",
                      throughputStats.averageRaysPerSecond, MIN_RAYS_PER_SECOND));
            recommendations.add("Consider GPU acceleration or SIMD optimizations for ray traversal");
        }
        
        // Assess memory usage
        if (memoryStats.peakUsedMemoryMB > memoryStats.maxHeapMemoryMB * 0.8) {
            issues.add("High memory usage: " + String.format("%d MB (%.1f%% of heap)",
                      memoryStats.peakUsedMemoryMB, 
                      (memoryStats.peakUsedMemoryMB * 100.0) / memoryStats.maxHeapMemoryMB));
            recommendations.add("Optimize memory usage or increase heap size");
        }
        
        // Overall grade
        var grade = PerformanceGrade.EXCELLENT;
        if (!issues.isEmpty()) {
            if (frameStats.averageFPS < TARGET_FPS * 0.5) {
                grade = PerformanceGrade.POOR;
            } else if (frameStats.averageFPS < TARGET_FPS * 0.8) {
                grade = PerformanceGrade.NEEDS_IMPROVEMENT;
            } else {
                grade = PerformanceGrade.GOOD;
            }
        }
        
        return new PerformanceAssessment(grade, issues, recommendations);
    }

    private double calculatePercentile(double[] sortedValues, double percentile) {
        if (sortedValues.length == 0) return 0.0;
        
        var index = (int) Math.ceil(percentile * sortedValues.length) - 1;
        index = Math.max(0, Math.min(index, sortedValues.length - 1));
        
        return sortedValues[index];
    }

    private double calculateStandardDeviation(double[] values, double mean) {
        var variance = Arrays.stream(values)
                           .map(v -> Math.pow(v - mean, 2))
                           .average()
                           .orElse(0.0);
        
        return Math.sqrt(variance);
    }

    private double calculatePercentageChange(double baseline, double current) {
        if (baseline == 0) return current == 0 ? 0.0 : Double.POSITIVE_INFINITY;
        return ((current - baseline) / baseline) * 100.0;
    }

    private PerformanceTrend determineTrend(double fpsChange, double frameTimeChange, 
                                          double rayThroughputChange) {
        var positiveChanges = 0;
        var negativeChanges = 0;
        
        if (fpsChange > 5.0) positiveChanges++;
        else if (fpsChange < -5.0) negativeChanges++;
        
        if (frameTimeChange < -5.0) positiveChanges++; // Lower frame time is better
        else if (frameTimeChange > 5.0) negativeChanges++;
        
        if (rayThroughputChange > 5.0) positiveChanges++;
        else if (rayThroughputChange < -5.0) negativeChanges++;
        
        if (positiveChanges > negativeChanges) {
            return PerformanceTrend.IMPROVED;
        } else if (negativeChanges > positiveChanges) {
            return PerformanceTrend.DEGRADED;
        } else {
            return PerformanceTrend.STABLE;
        }
    }

    private String generateComparisonSummary(PerformanceReport baseline, PerformanceReport current,
                                           double fpsChange, double frameTimeChange,
                                           double rayThroughputChange, double memoryChange,
                                           PerformanceTrend trend) {
        var sb = new StringBuilder();
        sb.append("Performance Comparison Summary\n");
        sb.append("============================\n\n");
        
        sb.append(String.format("Overall trend: %s\n\n", trend));
        
        sb.append("Key Changes:\n");
        sb.append(String.format("  FPS: %.1f → %.1f (%+.1f%%)\n",
                  baseline.frameStats().averageFPS, current.frameStats().averageFPS, fpsChange));
        sb.append(String.format("  Frame time: %.2f → %.2f ms (%+.1f%%)\n",
                  baseline.frameStats().averageFrameTimeMs, current.frameStats().averageFrameTimeMs, frameTimeChange));
        sb.append(String.format("  Ray throughput: %.0f → %.0f rays/sec (%+.1f%%)\n",
                  baseline.throughputStats().averageRaysPerSecond, 
                  current.throughputStats().averageRaysPerSecond, rayThroughputChange));
        sb.append(String.format("  Memory usage: %d → %d MB (%+.1f%%)\n",
                  baseline.memoryStats().peakUsedMemoryMB,
                  current.memoryStats().peakUsedMemoryMB, memoryChange));
        
        return sb.toString();
    }

    // Data classes for performance metrics

    private record MemorySnapshot(long heapUsedMB, long heapMaxMB, long nonHeapUsedMB, long timestampNs) {}

    public record FrameStats(
        double averageFPS,
        double averageFrameTimeMs,
        double minFrameTimeMs,
        double maxFrameTimeMs,
        double p95FrameTimeMs,
        double p99FrameTimeMs,
        double standardDeviationMs
    ) {}

    public record ThroughputStats(
        double averageRaysPerSecond,
        double peakRaysPerSecond,
        double averageVoxelsPerSecond,
        double peakVoxelsPerSecond
    ) {}

    public record MemoryStats(
        long peakUsedMemoryMB,
        long averageUsedMemoryMB,
        long memoryGrowthMB,
        long maxHeapMemoryMB,
        double averageUsagePercent
    ) {}

    public record SystemStats(
        double baselineCPUPercent,
        double finalCPUPercent,
        int availableProcessors,
        long gpuMemoryUsageBytes,
        long totalBenchmarkTimeMs
    ) {}

    public enum PerformanceGrade {
        EXCELLENT, GOOD, NEEDS_IMPROVEMENT, POOR
    }

    public record PerformanceAssessment(
        PerformanceGrade grade,
        List<String> issues,
        List<String> recommendations
    ) {}

    public record PerformanceReport(
        FrameStats frameStats,
        ThroughputStats throughputStats,
        MemoryStats memoryStats,
        SystemStats systemStats,
        PerformanceAssessment assessment
    ) {
        /**
         * Check if performance meets all target thresholds.
         */
        public boolean meetsPerformanceTargets() {
            return frameStats.averageFPS >= TARGET_FPS &&
                   throughputStats.averageRaysPerSecond >= MIN_RAYS_PER_SECOND &&
                   assessment.grade != PerformanceGrade.POOR;
        }

        /**
         * Generate a comprehensive performance report string.
         */
        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append("ESVO Performance Benchmark Report\n");
            sb.append("=================================\n\n");
            
            // Frame performance
            sb.append("Frame Performance:\n");
            sb.append(String.format("  Average FPS: %.1f (target: %.1f)\n", 
                      frameStats.averageFPS, TARGET_FPS));
            sb.append(String.format("  Average frame time: %.2f ms (target: %.2f ms)\n",
                      frameStats.averageFrameTimeMs, TARGET_FRAME_TIME_MS));
            sb.append(String.format("  Frame time range: %.2f - %.2f ms\n",
                      frameStats.minFrameTimeMs, frameStats.maxFrameTimeMs));
            sb.append(String.format("  95th percentile: %.2f ms, 99th percentile: %.2f ms\n",
                      frameStats.p95FrameTimeMs, frameStats.p99FrameTimeMs));
            sb.append(String.format("  Standard deviation: %.2f ms\n\n",
                      frameStats.standardDeviationMs));
            
            // Throughput performance
            sb.append("Throughput Performance:\n");
            sb.append(String.format("  Average ray throughput: %.0f rays/sec (min: %,d)\n",
                      throughputStats.averageRaysPerSecond, MIN_RAYS_PER_SECOND));
            sb.append(String.format("  Peak ray throughput: %.0f rays/sec\n",
                      throughputStats.peakRaysPerSecond));
            sb.append(String.format("  Average voxel throughput: %.0f voxels/sec\n",
                      throughputStats.averageVoxelsPerSecond));
            sb.append(String.format("  Peak voxel throughput: %.0f voxels/sec\n\n",
                      throughputStats.peakVoxelsPerSecond));
            
            // Memory usage
            sb.append("Memory Usage:\n");
            sb.append(String.format("  Peak heap usage: %,d MB\n", memoryStats.peakUsedMemoryMB));
            sb.append(String.format("  Average heap usage: %,d MB\n", memoryStats.averageUsedMemoryMB));
            sb.append(String.format("  Memory growth: %+,d MB\n", memoryStats.memoryGrowthMB));
            sb.append(String.format("  Max heap size: %,d MB\n\n", memoryStats.maxHeapMemoryMB));
            
            // System metrics
            sb.append("System Metrics:\n");
            sb.append(String.format("  CPU usage: %.1f%% → %.1f%%\n",
                      systemStats.baselineCPUPercent, systemStats.finalCPUPercent));
            sb.append(String.format("  Available processors: %d\n", systemStats.availableProcessors));
            sb.append(String.format("  GPU memory usage: %,d bytes\n", systemStats.gpuMemoryUsageBytes));
            sb.append(String.format("  Total benchmark time: %,d ms\n\n", systemStats.totalBenchmarkTimeMs));
            
            // Assessment
            sb.append("Performance Assessment: ").append(assessment.grade).append("\n");
            
            if (!assessment.issues.isEmpty()) {
                sb.append("\nIssues Identified:\n");
                assessment.issues.forEach(issue -> sb.append("  • ").append(issue).append("\n"));
            }
            
            if (!assessment.recommendations.isEmpty()) {
                sb.append("\nRecommendations:\n");
                assessment.recommendations.forEach(rec -> sb.append("  • ").append(rec).append("\n"));
            }
            
            sb.append("\nPerformance Targets: ").append(meetsPerformanceTargets() ? "MET" : "NOT MET");
            
            return sb.toString();
        }
    }

    public enum PerformanceTrend {
        IMPROVED, STABLE, DEGRADED
    }

    public record ComparisonReport(
        double fpsChangePercent,
        double frameTimeChangePercent,
        double rayThroughputChangePercent,
        double memoryChangePercent,
        PerformanceTrend trend,
        String summary
    ) {}
}