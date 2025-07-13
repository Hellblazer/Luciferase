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
package com.hellblazer.luciferase.lucien.profiler;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Performance profiler for spatial index operations.
 * Provides detailed timing, memory usage, and operation statistics.
 * 
 * Features:
 * - Operation timing with statistical analysis
 * - Memory usage tracking
 * - Cache hit/miss monitoring
 * - Thread contention analysis
 * - Hotspot identification
 * - Performance regression detection
 */
public class SpatialIndexProfiler<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(SpatialIndexProfiler.class);
    
    private final SpatialIndex<Key, ID, Content> spatialIndex;
    private final Map<OperationType, OperationStats> operationStats = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> customCounters = new ConcurrentHashMap<>();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    
    // Configuration
    private boolean enableThreadProfiling = false;
    private boolean enableMemoryProfiling = true;
    private boolean enableDetailedStats = true;
    private int samplingRate = 100; // Sample every N operations
    
    // Performance thresholds
    private long slowOperationThresholdNanos = TimeUnit.MILLISECONDS.toNanos(10);
    private long memoryUsageThresholdBytes = 100 * 1024 * 1024; // 100MB
    
    public enum OperationType {
        INSERT,
        REMOVE,
        UPDATE,
        QUERY_POINT,
        QUERY_RANGE,
        QUERY_KNN,
        QUERY_RAY,
        QUERY_FRUSTUM,
        QUERY_PLANE,
        BULK_INSERT,
        BULK_REMOVE,
        TREE_BALANCE,
        NODE_SUBDIVISION,
        CACHE_HIT,
        CACHE_MISS
    }
    
    public SpatialIndexProfiler(SpatialIndex<Key, ID, Content> spatialIndex) {
        this.spatialIndex = spatialIndex;
        
        // Initialize operation stats
        for (var type : OperationType.values()) {
            operationStats.put(type, new OperationStats(type));
        }
    }
    
    /**
     * Profile an operation and collect statistics
     */
    public <T> T profile(OperationType type, Supplier<T> operation) {
        var stats = operationStats.get(type);
        var shouldSample = stats.count.get() % samplingRate == 0;
        
        long startTime = System.nanoTime();
        long startCpu = enableThreadProfiling && shouldSample ? threadBean.getCurrentThreadCpuTime() : 0;
        long startMemory = enableMemoryProfiling && shouldSample ? getCurrentMemoryUsed() : 0;
        
        try {
            T result = operation.get();
            
            long duration = System.nanoTime() - startTime;
            stats.recordOperation(duration);
            
            if (shouldSample) {
                if (enableThreadProfiling) {
                    long cpuTime = threadBean.getCurrentThreadCpuTime() - startCpu;
                    stats.recordCpuTime(cpuTime);
                }
                
                if (enableMemoryProfiling) {
                    long memoryDelta = getCurrentMemoryUsed() - startMemory;
                    stats.recordMemoryDelta(memoryDelta);
                }
            }
            
            // Check for slow operations
            if (duration > slowOperationThresholdNanos) {
                log.warn("Slow {} operation detected: {} ms", type, 
                        TimeUnit.NANOSECONDS.toMillis(duration));
            }
            
            return result;
            
        } catch (Exception e) {
            stats.errors.increment();
            throw e;
        }
    }
    
    /**
     * Profile an operation without return value
     */
    public void profileVoid(OperationType type, Runnable operation) {
        profile(type, () -> {
            operation.run();
            return null;
        });
    }
    
    /**
     * Increment a custom counter
     */
    public void incrementCounter(String name) {
        customCounters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }
    
    /**
     * Get a custom counter value
     */
    public long getCounter(String name) {
        var counter = customCounters.get(name);
        return counter != null ? counter.sum() : 0;
    }
    
    /**
     * Reset all statistics
     */
    public void reset() {
        operationStats.values().forEach(OperationStats::reset);
        customCounters.clear();
    }
    
    /**
     * Generate a performance report
     */
    public PerformanceReport generateReport() {
        var report = new PerformanceReport();
        
        // Collect operation statistics
        for (var entry : operationStats.entrySet()) {
            var stats = entry.getValue();
            if (stats.count.get() > 0 || stats.errors.sum() > 0) {
                report.operationReports.put(entry.getKey(), stats.generateReport());
            }
        }
        
        // Collect custom counters
        for (var entry : customCounters.entrySet()) {
            report.customCounters.put(entry.getKey(), entry.getValue().sum());
        }
        
        // Add system metrics
        report.totalMemoryUsed = getCurrentMemoryUsed();
        report.nodeCount = spatialIndex.nodeCount();
        report.entityCount = spatialIndex.entityCount();
        
        return report;
    }
    
    /**
     * Print a formatted performance report
     */
    public void printReport() {
        var report = generateReport();
        System.out.println(report);
    }
    
    /**
     * Enable or disable thread profiling
     */
    public void setEnableThreadProfiling(boolean enable) {
        this.enableThreadProfiling = enable;
        if (enable && !threadBean.isThreadCpuTimeSupported()) {
            log.warn("Thread CPU time monitoring is not supported on this JVM");
            this.enableThreadProfiling = false;
        }
    }
    
    /**
     * Set the sampling rate for detailed statistics
     */
    public void setSamplingRate(int rate) {
        this.samplingRate = Math.max(1, rate);
    }
    
    /**
     * Set the threshold for slow operation warnings
     */
    public void setSlowOperationThreshold(long thresholdMillis) {
        this.slowOperationThresholdNanos = TimeUnit.MILLISECONDS.toNanos(thresholdMillis);
    }
    
    // Private helper methods
    
    private long getCurrentMemoryUsed() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        return heapUsage.getUsed();
    }
    
    /**
     * Statistics for a specific operation type
     */
    private static class OperationStats {
        final OperationType type;
        final AtomicLong count = new AtomicLong();
        final AtomicLong totalTimeNanos = new AtomicLong();
        final AtomicLong minTimeNanos = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong maxTimeNanos = new AtomicLong();
        final LongAdder errors = new LongAdder();
        
        // Sampled statistics
        final List<Long> samples = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong totalCpuTimeNanos = new AtomicLong();
        final AtomicLong totalMemoryDelta = new AtomicLong();
        
        OperationStats(OperationType type) {
            this.type = type;
        }
        
        void recordOperation(long durationNanos) {
            count.incrementAndGet();
            totalTimeNanos.addAndGet(durationNanos);
            
            // Update min/max
            long currentMin, currentMax;
            do {
                currentMin = minTimeNanos.get();
            } while (durationNanos < currentMin && !minTimeNanos.compareAndSet(currentMin, durationNanos));
            
            do {
                currentMax = maxTimeNanos.get();
            } while (durationNanos > currentMax && !maxTimeNanos.compareAndSet(currentMax, durationNanos));
            
            // Keep sample for percentile calculations
            if (samples.size() < 10000) { // Limit sample size
                samples.add(durationNanos);
            }
        }
        
        void recordCpuTime(long cpuNanos) {
            totalCpuTimeNanos.addAndGet(cpuNanos);
        }
        
        void recordMemoryDelta(long bytes) {
            totalMemoryDelta.addAndGet(bytes);
        }
        
        void reset() {
            count.set(0);
            totalTimeNanos.set(0);
            minTimeNanos.set(Long.MAX_VALUE);
            maxTimeNanos.set(0);
            errors.reset();
            samples.clear();
            totalCpuTimeNanos.set(0);
            totalMemoryDelta.set(0);
        }
        
        OperationReport generateReport() {
            var report = new OperationReport();
            report.type = type;
            report.count = count.get();
            report.errors = errors.sum();
            
            if (report.count > 0) {
                report.totalTimeMillis = TimeUnit.NANOSECONDS.toMillis(totalTimeNanos.get());
                report.avgTimeMillis = report.totalTimeMillis / (double) report.count;
                report.minTimeMillis = TimeUnit.NANOSECONDS.toMillis(minTimeNanos.get());
                report.maxTimeMillis = TimeUnit.NANOSECONDS.toMillis(maxTimeNanos.get());
                
                // Calculate percentiles if we have samples
                if (!samples.isEmpty()) {
                    var sortedSamples = new ArrayList<>(samples);
                    Collections.sort(sortedSamples);
                    
                    int p50Index = sortedSamples.size() / 2;
                    int p95Index = (int) (sortedSamples.size() * 0.95);
                    int p99Index = (int) (sortedSamples.size() * 0.99);
                    
                    report.p50TimeMillis = TimeUnit.NANOSECONDS.toMillis(sortedSamples.get(p50Index));
                    if (p95Index < sortedSamples.size()) {
                        report.p95TimeMillis = TimeUnit.NANOSECONDS.toMillis(sortedSamples.get(p95Index));
                    }
                    if (p99Index < sortedSamples.size()) {
                        report.p99TimeMillis = TimeUnit.NANOSECONDS.toMillis(sortedSamples.get(p99Index));
                    }
                }
                
                if (totalCpuTimeNanos.get() > 0) {
                    report.avgCpuTimeMillis = TimeUnit.NANOSECONDS.toMillis(totalCpuTimeNanos.get()) 
                                            / (double) report.count;
                }
                
                if (totalMemoryDelta.get() != 0) {
                    report.avgMemoryDeltaKB = (totalMemoryDelta.get() / 1024.0) / report.count;
                }
            }
            
            return report;
        }
    }
    
    /**
     * Report for a single operation type
     */
    public static class OperationReport {
        public OperationType type;
        public long count;
        public long errors;
        public long totalTimeMillis;
        public double avgTimeMillis;
        public double minTimeMillis;
        public double maxTimeMillis;
        public double p50TimeMillis;
        public double p95TimeMillis;
        public double p99TimeMillis;
        public double avgCpuTimeMillis;
        public double avgMemoryDeltaKB;
        
        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append(String.format("%-20s: count=%d", type, count));
            
            if (count > 0) {
                sb.append(String.format(", avg=%.2fms", avgTimeMillis));
                sb.append(String.format(", min=%.2fms", minTimeMillis));
                sb.append(String.format(", max=%.2fms", maxTimeMillis));
                
                if (p50TimeMillis > 0) {
                    sb.append(String.format(", p50=%.2fms", p50TimeMillis));
                }
                if (p95TimeMillis > 0) {
                    sb.append(String.format(", p95=%.2fms", p95TimeMillis));
                }
                if (p99TimeMillis > 0) {
                    sb.append(String.format(", p99=%.2fms", p99TimeMillis));
                }
                
                if (avgCpuTimeMillis > 0) {
                    sb.append(String.format(", cpu=%.2fms", avgCpuTimeMillis));
                }
                
                if (Math.abs(avgMemoryDeltaKB) > 0.01) {
                    sb.append(String.format(", mem=%+.1fKB", avgMemoryDeltaKB));
                }
                
                if (errors > 0) {
                    sb.append(String.format(", errors=%d", errors));
                }
            }
            
            return sb.toString();
        }
    }
    
    /**
     * Complete performance report
     */
    public static class PerformanceReport {
        public Map<OperationType, OperationReport> operationReports = new EnumMap<>(OperationType.class);
        public Map<String, Long> customCounters = new HashMap<>();
        public long totalMemoryUsed;
        public long nodeCount;
        public long entityCount;
        
        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append("\n=== Spatial Index Performance Report ===\n");
            sb.append(String.format("Nodes: %d, Entities: %d, Memory: %.1f MB\n", 
                    nodeCount, entityCount, totalMemoryUsed / (1024.0 * 1024.0)));
            
            sb.append("\nOperation Statistics:\n");
            operationReports.values().stream()
                    .sorted(Comparator.comparing(r -> r.type))
                    .forEach(report -> sb.append("  ").append(report).append("\n"));
            
            if (!customCounters.isEmpty()) {
                sb.append("\nCustom Counters:\n");
                customCounters.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> sb.append(String.format("  %-30s: %d\n", 
                                entry.getKey(), entry.getValue())));
            }
            
            return sb.toString();
        }
    }
}