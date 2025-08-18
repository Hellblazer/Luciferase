package com.dyada.performance;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Performance profiler for DyAda operations with minimal overhead
 */
public final class PerformanceProfiler {
    
    private static final PerformanceProfiler INSTANCE = new PerformanceProfiler();
    private final ConcurrentHashMap<String, OperationStats> stats = new ConcurrentHashMap<>();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean enabled = false;
    
    private PerformanceProfiler() {}
    
    public static PerformanceProfiler getInstance() {
        return INSTANCE;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public ProfiledOperation startOperation(String operationName) {
        if (!enabled) {
            return new ProfiledOperation(null, null, 0); // NOOP operation
        }
        return new ProfiledOperation(operationName, Instant.now(), getMemoryUsage());
    }
    
    private long getMemoryUsage() {
        MemoryUsage usage = memoryBean.getHeapMemoryUsage();
        return usage.getUsed();
    }
    
    private void recordOperation(String operationName, Duration duration, long memoryDelta) {
        if (!enabled) return;
        
        stats.computeIfAbsent(operationName, k -> new OperationStats())
             .recordExecution(duration, memoryDelta);
    }
    
    public Map<String, OperationStats> getStats() {
        lock.readLock().lock();
        try {
            return Map.copyOf(stats);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public void clearStats() {
        lock.writeLock().lock();
        try {
            stats.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public static final class OperationStats {
        private final AtomicLong executionCount = new AtomicLong(0);
        private final AtomicLong totalDurationNanos = new AtomicLong(0);
        private final AtomicLong totalMemoryDelta = new AtomicLong(0);
        private volatile long minDurationNanos = Long.MAX_VALUE;
        private volatile long maxDurationNanos = Long.MIN_VALUE;
        
        void recordExecution(Duration duration, long memoryDelta) {
            long durationNanos = duration.toNanos();
            
            executionCount.incrementAndGet();
            totalDurationNanos.addAndGet(durationNanos);
            totalMemoryDelta.addAndGet(memoryDelta);
            
            // Update min/max atomically
            updateMin(durationNanos);
            updateMax(durationNanos);
        }
        
        private void updateMin(long durationNanos) {
            long currentMin = minDurationNanos;
            while (durationNanos < currentMin) {
                if (compareAndSetMin(currentMin, durationNanos)) {
                    break;
                }
                currentMin = minDurationNanos;
            }
        }
        
        private void updateMax(long durationNanos) {
            long currentMax = maxDurationNanos;
            while (durationNanos > currentMax) {
                if (compareAndSetMax(currentMax, durationNanos)) {
                    break;
                }
                currentMax = maxDurationNanos;
            }
        }
        
        private boolean compareAndSetMin(long expect, long update) {
            // Simple volatile write since exact precision isn't critical for min/max
            if (minDurationNanos == expect) {
                minDurationNanos = update;
                return true;
            }
            return false;
        }
        
        private boolean compareAndSetMax(long expect, long update) {
            if (maxDurationNanos == expect) {
                maxDurationNanos = update;
                return true;
            }
            return false;
        }
        
        public long getExecutionCount() {
            return executionCount.get();
        }
        
        public Duration getAverageDuration() {
            long count = executionCount.get();
            if (count == 0) return Duration.ZERO;
            return Duration.ofNanos(totalDurationNanos.get() / count);
        }
        
        public Duration getMinDuration() {
            return minDurationNanos == Long.MAX_VALUE ? 
                Duration.ZERO : Duration.ofNanos(minDurationNanos);
        }
        
        public Duration getMaxDuration() {
            return maxDurationNanos == Long.MIN_VALUE ? 
                Duration.ZERO : Duration.ofNanos(maxDurationNanos);
        }
        
        public long getAverageMemoryDelta() {
            long count = executionCount.get();
            if (count == 0) return 0;
            return totalMemoryDelta.get() / count;
        }
        
        public Duration getTotalDuration() {
            return Duration.ofNanos(totalDurationNanos.get());
        }
    }
    
    public final class ProfiledOperation implements AutoCloseable {
        private final String operationName;
        private final Instant startTime;
        private final long startMemory;
        private boolean closed = false;
        
        ProfiledOperation(String operationName, Instant startTime, long startMemory) {
            this.operationName = operationName;
            this.startTime = startTime;
            this.startMemory = startMemory;
            this.closed = (operationName == null); // NOOP operations are pre-closed
        }
        
        @Override
        public void close() {
            if (closed || !enabled) return;
            closed = true;
            
            var endTime = Instant.now();
            var endMemory = getMemoryUsage();
            var duration = Duration.between(startTime, endTime);
            var memoryDelta = endMemory - startMemory;
            
            recordOperation(operationName, duration, memoryDelta);
        }
    }
}