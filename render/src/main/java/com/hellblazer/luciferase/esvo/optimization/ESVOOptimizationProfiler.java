package com.hellblazer.luciferase.esvo.optimization;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * Performance profiler for ESVO optimization analysis.
 * Tracks memory access patterns, kernel execution times, and identifies bottlenecks.
 *
 * <p>A {@link LongSupplier} nano-time source is injected at construction or via
 * {@link #setNanoTime(LongSupplier)} for deterministic testing.  The default
 * delegates to {@code System::nanoTime}.
 */
public class ESVOOptimizationProfiler {

    /** Nano-time source; defaults to System::nanoTime, injectable for tests. */
    private volatile LongSupplier nanoTime = System::nanoTime;

    private final AtomicLong profilingStartTime = new AtomicLong(0);
    private final Map<String, MemoryAccessData> memoryAccesses = new ConcurrentHashMap<>();
    private final Map<String, KernelExecutionData> kernelExecutions = new ConcurrentHashMap<>();
    private volatile boolean isProfiling = false;

    /**
     * Creates a profiler using the default {@code System::nanoTime} source.
     */
    public ESVOOptimizationProfiler() {
    }

    /**
     * Creates a profiler with a custom nano-time source (useful for testing).
     *
     * @param nanoTime supplier of nanosecond timestamps
     */
    public ESVOOptimizationProfiler(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * Replaces the nano-time source.  Intended for test injection only;
     * must be called before {@link #startProfiling()}.
     *
     * @param nanoTime supplier of nanosecond timestamps
     */
    public void setNanoTime(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * Start profiling session
     */
    public void startProfiling() {
        profilingStartTime.set(nanoTime.getAsLong());
        memoryAccesses.clear();
        kernelExecutions.clear();
        isProfiling = true;
    }

    /**
     * Stop profiling and return results
     */
    public ProfileResult stopProfiling() {
        if (!isProfiling) {
            throw new IllegalStateException("Profiling not started");
        }

        long endTime = nanoTime.getAsLong();
        long totalTime = endTime - profilingStartTime.get();
        isProfiling = false;

        return new ProfileResult(
            totalTime / 1_000_000.0, // Convert to milliseconds
            new ArrayList<>(memoryAccesses.values()),
            new ArrayList<>(kernelExecutions.values())
        );
    }

    /**
     * Record memory access pattern
     */
    public void recordMemoryAccess(String operationName, int accessCount, long bytesAccessed) {
        if (!isProfiling) return;

        final long now = nanoTime.getAsLong();
        memoryAccesses.compute(operationName, (key, existing) -> {
            if (existing == null) {
                return new MemoryAccessData(operationName, accessCount, bytesAccessed, now);
            } else {
                return new MemoryAccessData(
                    operationName,
                    existing.accessCount + accessCount,
                    existing.bytesAccessed + bytesAccessed,
                    existing.firstAccessTime
                );
            }
        });
    }

    /**
     * Record kernel execution time
     */
    public void recordKernelExecution(String kernelName, float executionTimeMs) {
        if (!isProfiling) return;

        final long now = nanoTime.getAsLong();
        kernelExecutions.compute(kernelName, (key, existing) -> {
            if (existing == null) {
                return new KernelExecutionData(kernelName, executionTimeMs, 1, now);
            } else {
                return new KernelExecutionData(
                    kernelName,
                    existing.totalExecutionTimeMs + executionTimeMs,
                    existing.executionCount + 1,
                    existing.firstExecutionTime
                );
            }
        });
    }
    
    /**
     * Identify performance bottlenecks from profile data
     */
    public List<PerformanceBottleneck> identifyBottlenecks(ProfileResult profile) {
        List<PerformanceBottleneck> bottlenecks = new ArrayList<>();
        
        // Analyze memory access bottlenecks
        for (MemoryAccessData access : profile.getMemoryAccessProfiles()) {
            double bandwidth = (access.bytesAccessed / 1024.0 / 1024.0) / (profile.getTotalProfilingTime() / 1000.0); // MB/s
            
            if (bandwidth < 1000) { // Less than 1 GB/s is potentially problematic
                bottlenecks.add(new PerformanceBottleneck(
                    BottleneckType.MEMORY_BANDWIDTH,
                    access.operationName,
                    String.format("Low memory bandwidth: %.2f MB/s", bandwidth),
                    bandwidth < 100 ? Severity.HIGH : Severity.MEDIUM
                ));
            }
            
            if (access.accessCount > 1000000) { // Many small accesses
                bottlenecks.add(new PerformanceBottleneck(
                    BottleneckType.MEMORY_ACCESS_PATTERN,
                    access.operationName,
                    String.format("High access count: %d accesses", access.accessCount),
                    Severity.MEDIUM
                ));
            }
        }
        
        // Analyze kernel execution bottlenecks
        for (KernelExecutionData kernel : profile.getKernelProfiles()) {
            double avgExecutionTime = kernel.totalExecutionTimeMs / kernel.executionCount;
            
            if (avgExecutionTime > 100) { // >100ms average execution time
                bottlenecks.add(new PerformanceBottleneck(
                    BottleneckType.KERNEL_EXECUTION,
                    kernel.kernelName,
                    String.format("Long execution time: %.2f ms average", avgExecutionTime),
                    avgExecutionTime > 500 ? Severity.HIGH : Severity.MEDIUM
                ));
            }
        }
        
        return bottlenecks;
    }
    
    /**
     * Memory access profile data
     */
    public static class MemoryAccessData {
        public final String operationName;
        public final int accessCount;
        public final long bytesAccessed;
        public final long firstAccessTime;
        
        public MemoryAccessData(String operationName, int accessCount, long bytesAccessed, long firstAccessTime) {
            this.operationName = operationName;
            this.accessCount = accessCount;
            this.bytesAccessed = bytesAccessed;
            this.firstAccessTime = firstAccessTime;
        }
    }
    
    /**
     * Kernel execution profile data
     */
    public static class KernelExecutionData {
        public final String kernelName;
        public final float totalExecutionTimeMs;
        public final int executionCount;
        public final long firstExecutionTime;
        
        public KernelExecutionData(String kernelName, float totalExecutionTimeMs, int executionCount, long firstExecutionTime) {
            this.kernelName = kernelName;
            this.totalExecutionTimeMs = totalExecutionTimeMs;
            this.executionCount = executionCount;
            this.firstExecutionTime = firstExecutionTime;
        }
    }
    
    /**
     * Complete profiling result
     */
    public static class ProfileResult {
        private final double totalProfilingTime;
        private final List<MemoryAccessData> memoryAccessProfiles;
        private final List<KernelExecutionData> kernelProfiles;
        
        public ProfileResult(double totalProfilingTime, List<MemoryAccessData> memoryAccessProfiles, List<KernelExecutionData> kernelProfiles) {
            this.totalProfilingTime = totalProfilingTime;
            this.memoryAccessProfiles = memoryAccessProfiles;
            this.kernelProfiles = kernelProfiles;
        }
        
        public double getTotalProfilingTime() { return totalProfilingTime; }
        public List<MemoryAccessData> getMemoryAccessProfiles() { return memoryAccessProfiles; }
        public List<KernelExecutionData> getKernelProfiles() { return kernelProfiles; }
    }
    
    /**
     * Performance bottleneck identification
     */
    public static class PerformanceBottleneck {
        private final BottleneckType type;
        private final String location;
        private final String description;
        private final Severity severity;
        
        public PerformanceBottleneck(BottleneckType type, String location, String description, Severity severity) {
            this.type = type;
            this.location = location;
            this.description = description;
            this.severity = severity;
        }
        
        public BottleneckType getType() { return type; }
        public String getLocation() { return location; }
        public String getDescription() { return description; }
        public Severity getSeverity() { return severity; }
    }
    
    public enum BottleneckType {
        MEMORY_BANDWIDTH,
        MEMORY_ACCESS_PATTERN,
        KERNEL_EXECUTION,
        CACHE_EFFICIENCY
    }
    
    public enum Severity {
        LOW, MEDIUM, HIGH
    }
}