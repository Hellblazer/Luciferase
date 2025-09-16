package com.hellblazer.luciferase.resource.benchmark;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Performance benchmarking framework for GPU resource management.
 * Tracks allocations, deallocations, memory usage, and operation latencies.
 */
public class ResourceBenchmark {
    
    private final String name;
    private final Instant startTime;
    private Instant endTime;
    
    // Allocation metrics
    private final LongAdder totalAllocations = new LongAdder();
    private final LongAdder totalDeallocations = new LongAdder();
    private final LongAdder allocationFailures = new LongAdder();
    private final AtomicLong peakMemoryUsage = new AtomicLong(0);
    private final AtomicLong currentMemoryUsage = new AtomicLong(0);
    
    // Operation timing
    private final Map<String, OperationMetrics> operationMetrics = new ConcurrentHashMap<>();
    
    // Memory pool metrics
    private final LongAdder poolHits = new LongAdder();
    private final LongAdder poolMisses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    
    // API-specific metrics
    private final Map<String, APIMetrics> apiMetrics = new ConcurrentHashMap<>();
    
    public ResourceBenchmark(String name) {
        this.name = name;
        this.startTime = Instant.now();
    }
    
    /**
     * Records an allocation event.
     */
    public void recordAllocation(String resourceType, long sizeBytes, long latencyNanos) {
        totalAllocations.increment();
        
        var newUsage = currentMemoryUsage.addAndGet(sizeBytes);
        var currentPeak = peakMemoryUsage.get();
        while (newUsage > currentPeak) {
            if (peakMemoryUsage.compareAndSet(currentPeak, newUsage)) {
                break;
            }
            currentPeak = peakMemoryUsage.get();
        }
        
        getOperationMetrics("allocation." + resourceType).record(latencyNanos, sizeBytes);
    }
    
    /**
     * Records a deallocation event.
     */
    public void recordDeallocation(String resourceType, long sizeBytes, long latencyNanos) {
        totalDeallocations.increment();
        currentMemoryUsage.addAndGet(-sizeBytes);
        getOperationMetrics("deallocation." + resourceType).record(latencyNanos, sizeBytes);
    }
    
    /**
     * Records an allocation failure.
     */
    public void recordAllocationFailure(String resourceType, long requestedSize) {
        allocationFailures.increment();
        getOperationMetrics("failure." + resourceType).recordFailure(requestedSize);
    }
    
    /**
     * Records a pool hit (resource found in pool).
     */
    public void recordPoolHit() {
        poolHits.increment();
    }
    
    /**
     * Records a pool miss (resource not in pool).
     */
    public void recordPoolMiss() {
        poolMisses.increment();
    }
    
    /**
     * Records an eviction event.
     */
    public void recordEviction(String reason, long sizeFreed) {
        evictions.increment();
        getOperationMetrics("eviction." + reason).record(0, sizeFreed);
    }
    
    /**
     * Records an API-specific operation.
     */
    public void recordAPIOperation(String api, String operation, long latencyNanos) {
        apiMetrics.computeIfAbsent(api, k -> new APIMetrics())
                  .recordOperation(operation, latencyNanos);
    }
    
    /**
     * Completes the benchmark and calculates final metrics.
     */
    public BenchmarkResults complete() {
        if (endTime != null) {
            throw new IllegalStateException("Benchmark already completed");
        }
        endTime = Instant.now();
        
        return new BenchmarkResults(this);
    }
    
    private OperationMetrics getOperationMetrics(String operation) {
        return operationMetrics.computeIfAbsent(operation, k -> new OperationMetrics());
    }
    
    /**
     * Metrics for a specific operation type.
     */
    private static class OperationMetrics {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalLatencyNanos = new LongAdder();
        private final LongAdder totalSizeBytes = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final AtomicLong minLatencyNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxLatencyNanos = new AtomicLong(0);
        
        void record(long latencyNanos, long sizeBytes) {
            count.increment();
            totalLatencyNanos.add(latencyNanos);
            totalSizeBytes.add(sizeBytes);
            
            // Update min/max
            var currentMin = minLatencyNanos.get();
            while (latencyNanos < currentMin) {
                if (minLatencyNanos.compareAndSet(currentMin, latencyNanos)) {
                    break;
                }
                currentMin = minLatencyNanos.get();
            }
            
            var currentMax = maxLatencyNanos.get();
            while (latencyNanos > currentMax) {
                if (maxLatencyNanos.compareAndSet(currentMax, latencyNanos)) {
                    break;
                }
                currentMax = maxLatencyNanos.get();
            }
        }
        
        void recordFailure(long sizeBytes) {
            failures.increment();
            totalSizeBytes.add(sizeBytes);
        }
        
        long getCount() { return count.sum(); }
        long getTotalLatencyNanos() { return totalLatencyNanos.sum(); }
        long getTotalSizeBytes() { return totalSizeBytes.sum(); }
        long getFailures() { return failures.sum(); }
        long getMinLatencyNanos() { 
            var min = minLatencyNanos.get();
            return min == Long.MAX_VALUE ? 0 : min;
        }
        long getMaxLatencyNanos() { return maxLatencyNanos.get(); }
        
        double getAverageLatencyMicros() {
            var cnt = count.sum();
            return cnt > 0 ? totalLatencyNanos.sum() / (double) cnt / 1000.0 : 0;
        }
    }
    
    /**
     * API-specific metrics.
     */
    private static class APIMetrics {
        private final Map<String, OperationMetrics> operations = new ConcurrentHashMap<>();
        
        void recordOperation(String operation, long latencyNanos) {
            operations.computeIfAbsent(operation, k -> new OperationMetrics())
                     .record(latencyNanos, 0);
        }
        
        Map<String, OperationMetrics> getOperations() {
            return operations;
        }
    }
    
    /**
     * Immutable benchmark results.
     */
    public static class BenchmarkResults {
        private final String name;
        private final Duration duration;
        private final long totalAllocations;
        private final long totalDeallocations;
        private final long allocationFailures;
        private final long peakMemoryUsage;
        private final long poolHits;
        private final long poolMisses;
        private final long evictions;
        private final Map<String, OperationSummary> operationSummaries;
        private final Map<String, Map<String, OperationSummary>> apiSummaries;
        
        BenchmarkResults(ResourceBenchmark benchmark) {
            this.name = benchmark.name;
            this.duration = Duration.between(benchmark.startTime, benchmark.endTime);
            this.totalAllocations = benchmark.totalAllocations.sum();
            this.totalDeallocations = benchmark.totalDeallocations.sum();
            this.allocationFailures = benchmark.allocationFailures.sum();
            this.peakMemoryUsage = benchmark.peakMemoryUsage.get();
            this.poolHits = benchmark.poolHits.sum();
            this.poolMisses = benchmark.poolMisses.sum();
            this.evictions = benchmark.evictions.sum();
            
            // Convert operation metrics to summaries
            this.operationSummaries = new ConcurrentHashMap<>();
            benchmark.operationMetrics.forEach((op, metrics) -> 
                operationSummaries.put(op, new OperationSummary(metrics)));
            
            // Convert API metrics to summaries
            this.apiSummaries = new ConcurrentHashMap<>();
            benchmark.apiMetrics.forEach((api, apiMetrics) -> {
                var apiOps = new ConcurrentHashMap<String, OperationSummary>();
                apiMetrics.getOperations().forEach((op, metrics) ->
                    apiOps.put(op, new OperationSummary(metrics)));
                apiSummaries.put(api, apiOps);
            });
        }
        
        // Getters
        public String getName() { return name; }
        public Duration getDuration() { return duration; }
        public long getTotalAllocations() { return totalAllocations; }
        public long getTotalDeallocations() { return totalDeallocations; }
        public long getAllocationFailures() { return allocationFailures; }
        public long getPeakMemoryUsageMB() { return peakMemoryUsage / (1024 * 1024); }
        public double getAllocationsPerSecond() {
            return totalAllocations / (double) duration.getSeconds();
        }
        public double getPoolHitRate() {
            var total = poolHits + poolMisses;
            return total > 0 ? poolHits / (double) total : 0;
        }
        public long getEvictions() { return evictions; }
        public Map<String, OperationSummary> getOperationSummaries() { return operationSummaries; }
        public Map<String, Map<String, OperationSummary>> getApiSummaries() { return apiSummaries; }
        
        /**
         * Generates a formatted report of the benchmark results.
         */
        public String generateReport() {
            var sb = new StringBuilder();
            sb.append("\n=== Resource Benchmark Results: ").append(name).append(" ===\n");
            sb.append(String.format("Duration: %.2f seconds\n", duration.toMillis() / 1000.0));
            sb.append(String.format("Total Allocations: %,d (%.1f/sec)\n", 
                totalAllocations, getAllocationsPerSecond()));
            sb.append(String.format("Total Deallocations: %,d\n", totalDeallocations));
            sb.append(String.format("Allocation Failures: %,d\n", allocationFailures));
            sb.append(String.format("Peak Memory Usage: %,d MB\n", getPeakMemoryUsageMB()));
            sb.append(String.format("Pool Hit Rate: %.1f%%\n", getPoolHitRate() * 100));
            sb.append(String.format("Evictions: %,d\n", evictions));
            
            if (!operationSummaries.isEmpty()) {
                sb.append("\nOperation Summaries:\n");
                operationSummaries.forEach((op, summary) -> {
                    sb.append(String.format("  %s: count=%,d, avg=%.1f µs, min=%.1f µs, max=%.1f µs\n",
                        op, summary.count, summary.averageLatencyMicros,
                        summary.minLatencyMicros, summary.maxLatencyMicros));
                });
            }
            
            if (!apiSummaries.isEmpty()) {
                sb.append("\nAPI Summaries:\n");
                apiSummaries.forEach((api, ops) -> {
                    sb.append("  ").append(api).append(":\n");
                    ops.forEach((op, summary) -> {
                        sb.append(String.format("    %s: count=%,d, avg=%.1f µs\n",
                            op, summary.count, summary.averageLatencyMicros));
                    });
                });
            }
            
            return sb.toString();
        }
    }
    
    /**
     * Summary of an operation's performance metrics.
     */
    public static class OperationSummary {
        public final long count;
        public final long failures;
        public final double averageLatencyMicros;
        public final double minLatencyMicros;
        public final double maxLatencyMicros;
        public final long totalSizeMB;
        
        OperationSummary(OperationMetrics metrics) {
            this.count = metrics.getCount();
            this.failures = metrics.getFailures();
            this.averageLatencyMicros = metrics.getAverageLatencyMicros();
            this.minLatencyMicros = metrics.getMinLatencyNanos() / 1000.0;
            this.maxLatencyMicros = metrics.getMaxLatencyNanos() / 1000.0;
            this.totalSizeMB = metrics.getTotalSizeBytes() / (1024 * 1024);
        }
    }
}