package com.hellblazer.luciferase.render.performance;

import com.hellblazer.luciferase.render.memory.GPUMemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.*;

/**
 * Comprehensive performance profiling system for ESVO rendering pipeline.
 */
public class RenderingProfiler {
    private static final Logger log = LoggerFactory.getLogger(RenderingProfiler.class);
    
    private static final int MAX_FRAME_HISTORY = 1000;
    private static final int MAX_OPERATION_HISTORY = 5000;
    private static final long BASELINE_CALCULATION_INTERVAL = 10000; // 10 seconds
    
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final GPUMemoryManager memoryManager;
    
    // Frame timing data
    private final CircularBuffer<FrameData> frameHistory;
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong totalFrameTime = new AtomicLong(0);
    
    // Operation timing data
    private final ConcurrentHashMap<String, OperationStats> operationStats;
    private final CircularBuffer<OperationData> operationHistory;
    
    // Performance baselines and thresholds
    private volatile PerformanceBaseline currentBaseline;
    private volatile long lastBaselineUpdate = 0;
    
    // Real-time monitoring
    private final AtomicLong warningCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private volatile boolean profilingEnabled = true;
    
    public RenderingProfiler(GPUMemoryManager memoryManager) {
        this.memoryManager = memoryManager;
        this.frameHistory = new CircularBuffer<>(MAX_FRAME_HISTORY);
        this.operationHistory = new CircularBuffer<>(MAX_OPERATION_HISTORY);
        this.operationStats = new ConcurrentHashMap<>();
        this.currentBaseline = new PerformanceBaseline();
        
        log.info("Rendering Profiler initialized");
    }
    
    /**
     * Start profiling a frame rendering operation.
     */
    public FrameProfiler startFrame(long frameNumber) {
        if (!profilingEnabled) {
            return new NoOpFrameProfiler();
        }
        
        return new FrameProfilerImpl(frameNumber, System.nanoTime());
    }
    
    /**
     * Start profiling a specific GPU operation.
     */
    public OperationProfiler startOperation(String operationName) {
        if (!profilingEnabled) {
            return new NoOpOperationProfiler();
        }
        
        return new OperationProfilerImpl(operationName, System.nanoTime());
    }
    
    /**
     * Get current performance statistics.
     */
    public PerformanceStats getPerformanceStats() {
        lock.readLock().lock();
        try {
            var memStats = memoryManager.getMemoryStats();
            var frameStats = calculateFrameStats();
            var opStats = calculateOperationStats();
            
            return new PerformanceStats(
                frameStats,
                opStats,
                memStats,
                warningCount.get(),
                errorCount.get(),
                System.currentTimeMillis()
            );
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Generate comprehensive performance report.
     */
    public PerformanceReport generateReport() {
        lock.readLock().lock();
        try {
            var stats = getPerformanceStats();
            var bottlenecks = identifyBottlenecks();
            var trends = calculateTrends();
            var recommendations = generateRecommendations(bottlenecks);
            
            return new PerformanceReport(
                stats,
                bottlenecks,
                trends, 
                recommendations,
                currentBaseline
            );
        } finally {
            lock.readLock().unlock();
        }
    }
    
    // Interface definitions
    
    public interface FrameProfiler {
        void startPhase(String phaseName);
        void endFrame();
    }
    
    public interface OperationProfiler {
        void endOperation();
    }
    
    // Implementation classes
    
    private class FrameProfilerImpl implements FrameProfiler {
        private final long frameNumber;
        private final long startTime;
        private final Map<String, Long> phaseTimings = new HashMap<>();
        private long currentPhaseStart = 0;
        private String currentPhase = null;
        
        FrameProfilerImpl(long frameNumber, long startTime) {
            this.frameNumber = frameNumber;
            this.startTime = startTime;
        }
        
        @Override
        public void startPhase(String phaseName) {
            long now = System.nanoTime();
            
            if (currentPhase != null) {
                phaseTimings.put(currentPhase, now - currentPhaseStart);
            }
            
            currentPhase = phaseName;
            currentPhaseStart = now;
        }
        
        @Override
        public void endFrame() {
            long endTime = System.nanoTime();
            
            if (currentPhase != null) {
                phaseTimings.put(currentPhase, endTime - currentPhaseStart);
            }
            
            long totalTime = endTime - startTime;
            double frameTimeMs = totalTime / 1_000_000.0;
            
            // Create frame data
            FrameData frameData = new FrameData(
                frameNumber,
                frameTimeMs,
                new HashMap<>(phaseTimings),
                System.currentTimeMillis()
            );
            
            // Store frame data
            lock.writeLock().lock();
            try {
                frameHistory.add(frameData);
                frameCount.incrementAndGet();
                totalFrameTime.addAndGet((long) frameTimeMs);
                
                // Check for performance issues
                checkFramePerformance(frameData);
                
            } finally {
                lock.writeLock().unlock();
            }
        }
    }
    
    private class OperationProfilerImpl implements OperationProfiler {
        private final String operationName;
        private final long startTime;
        
        OperationProfilerImpl(String operationName, long startTime) {
            this.operationName = operationName;
            this.startTime = startTime;
        }
        
        @Override
        public void endOperation() {
            long endTime = System.nanoTime();
            double durationMs = (endTime - startTime) / 1_000_000.0;
            
            // Update statistics
            operationStats.compute(operationName, (name, stats) -> {
                if (stats == null) {
                    stats = new OperationStats(name);
                }
                stats.addSample(durationMs);
                return stats;
            });
            
            // Store operation history
            OperationData opData = new OperationData(
                operationName,
                durationMs,
                System.currentTimeMillis()
            );
            
            lock.writeLock().lock();
            try {
                operationHistory.add(opData);
            } finally {
                lock.writeLock().unlock();
            }
        }
    }
    
    // Helper methods
    
    private FrameStats calculateFrameStats() {
        if (frameHistory.isEmpty()) {
            return new FrameStats(0, 0, 0, 0, 0, 0);
        }
        
        var frames = frameHistory.getAll();
        var frameTimes = frames.stream()
            .mapToDouble(f -> f.frameTimeMs)
            .sorted()
            .toArray();
        
        double avgFrameTime = Arrays.stream(frameTimes).average().orElse(0);
        double avgFPS = avgFrameTime > 0 ? 1000.0 / avgFrameTime : 0;
        double minFrameTime = frameTimes.length > 0 ? frameTimes[0] : 0;
        double maxFrameTime = frameTimes.length > 0 ? frameTimes[frameTimes.length - 1] : 0;
        double p95FrameTime = frameTimes.length > 0 ? 
            frameTimes[(int) (frameTimes.length * 0.95)] : 0;
        double p99FrameTime = frameTimes.length > 0 ? 
            frameTimes[(int) (frameTimes.length * 0.99)] : 0;
        
        return new FrameStats(
            avgFrameTime,
            avgFPS,
            minFrameTime,
            maxFrameTime,
            p95FrameTime,
            p99FrameTime
        );
    }
    
    private Map<String, OperationStats> calculateOperationStats() {
        return new HashMap<>(operationStats);
    }
    
    private List<Bottleneck> identifyBottlenecks() {
        var bottlenecks = new ArrayList<Bottleneck>();
        
        // Identify slow operations
        for (var entry : operationStats.entrySet()) {
            var stats = entry.getValue();
            if (stats.averageMs > 10.0) { // Operations taking more than 10ms
                bottlenecks.add(new Bottleneck(
                    BottleneckType.SLOW_OPERATION,
                    entry.getKey(),
                    stats.averageMs,
                    String.format("Operation '%s' averaging %.2fms", entry.getKey(), stats.averageMs)
                ));
            }
        }
        
        // Check memory pressure
        var memStats = memoryManager.getMemoryStats();
        if (memStats.poolHitRate < 0.5) {
            bottlenecks.add(new Bottleneck(
                BottleneckType.MEMORY_PRESSURE,
                "GPU Memory Pool",
                memStats.poolHitRate * 100,
                String.format("Low pool hit rate: %.1f%%", memStats.poolHitRate * 100)
            ));
        }
        
        return bottlenecks;
    }
    
    private List<PerformanceTrend> calculateTrends() {
        return new ArrayList<>(); // Placeholder for trend analysis
    }
    
    private List<String> generateRecommendations(List<Bottleneck> bottlenecks) {
        var recommendations = new ArrayList<String>();
        
        for (var bottleneck : bottlenecks) {
            switch (bottleneck.type) {
                case SLOW_OPERATION:
                    recommendations.add("Consider optimizing operation: " + bottleneck.location);
                    break;
                case MEMORY_PRESSURE:
                    recommendations.add("Increase memory pool sizes or adjust allocation patterns");
                    break;
                case GPU_STALL:
                    recommendations.add("Reduce GPU pipeline stalls by batching operations");
                    break;
            }
        }
        
        return recommendations;
    }
    
    private void checkFramePerformance(FrameData frame) {
        // Check for frame time warnings
        if (frame.frameTimeMs > 33.0) { // Slower than 30 FPS
            warningCount.incrementAndGet();
            log.warn("Slow frame detected: {:.2f}ms (Frame {})", 
                frame.frameTimeMs, frame.frameNumber);
        }
        
        if (frame.frameTimeMs > 100.0) { // Slower than 10 FPS
            errorCount.incrementAndGet();
            log.error("Critical frame performance: {:.2f}ms (Frame {})", 
                frame.frameTimeMs, frame.frameNumber);
        }
    }
    
    // No-op implementations for when profiling is disabled
    
    private static class NoOpFrameProfiler implements FrameProfiler {
        @Override public void startPhase(String phaseName) {}
        @Override public void endFrame() {}
    }
    
    private static class NoOpOperationProfiler implements OperationProfiler {
        @Override public void endOperation() {}
    }
    
    public void setProfilingEnabled(boolean enabled) {
        this.profilingEnabled = enabled;
        log.info("Profiling {}", enabled ? "enabled" : "disabled");
    }
}

// Data classes for profiling data structures
class CircularBuffer<T> {
    private final T[] buffer;
    private final int capacity;
    private int head = 0;
    private int size = 0;
    
    @SuppressWarnings("unchecked")
    public CircularBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = (T[]) new Object[capacity];
    }
    
    public synchronized void add(T item) {
        buffer[head] = item;
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
    }
    
    public synchronized List<T> getAll() {
        List<T> result = new ArrayList<>(size);
        int start = size < capacity ? 0 : head;
        for (int i = 0; i < size; i++) {
            result.add(buffer[(start + i) % capacity]);
        }
        return result;
    }
    
    public synchronized boolean isEmpty() {
        return size == 0;
    }
}

class FrameData {
    final long frameNumber;
    final double frameTimeMs;
    final Map<String, Long> phaseTimings;
    final long timestamp;
    
    FrameData(long frameNumber, double frameTimeMs, Map<String, Long> phaseTimings, long timestamp) {
        this.frameNumber = frameNumber;
        this.frameTimeMs = frameTimeMs;
        this.phaseTimings = phaseTimings;
        this.timestamp = timestamp;
    }
}

class OperationData {
    final String operationName;
    final double durationMs;
    final long timestamp;
    
    OperationData(String operationName, double durationMs, long timestamp) {
        this.operationName = operationName;
        this.durationMs = durationMs;
        this.timestamp = timestamp;
    }
}

class OperationStats {
    final String name;
    double averageMs;
    double minMs = Double.MAX_VALUE;
    double maxMs = Double.MIN_VALUE;
    long sampleCount = 0;
    double totalMs = 0;
    
    OperationStats(String name) {
        this.name = name;
    }
    
    void addSample(double durationMs) {
        sampleCount++;
        totalMs += durationMs;
        averageMs = totalMs / sampleCount;
        minMs = Math.min(minMs, durationMs);
        maxMs = Math.max(maxMs, durationMs);
    }
}

class FrameStats {
    final double averageFrameTimeMs;
    final double averageFPS;
    final double minFrameTimeMs;
    final double maxFrameTimeMs;
    final double frameTimeP95Ms;
    final double frameTimeP99Ms;
    
    FrameStats(double averageFrameTimeMs, double averageFPS, double minFrameTimeMs, 
               double maxFrameTimeMs, double frameTimeP95Ms, double frameTimeP99Ms) {
        this.averageFrameTimeMs = averageFrameTimeMs;
        this.averageFPS = averageFPS;
        this.minFrameTimeMs = minFrameTimeMs;
        this.maxFrameTimeMs = maxFrameTimeMs;
        this.frameTimeP95Ms = frameTimeP95Ms;
        this.frameTimeP99Ms = frameTimeP99Ms;
    }
}

class PerformanceStats {
    final FrameStats frameStats;
    final Map<String, OperationStats> operationStats;
    final GPUMemoryManager.MemoryStats memoryStats;
    final long warningCount;
    final long errorCount;
    final long timestamp;
    
    PerformanceStats(FrameStats frameStats, Map<String, OperationStats> operationStats,
                    GPUMemoryManager.MemoryStats memoryStats, long warningCount, 
                    long errorCount, long timestamp) {
        this.frameStats = frameStats;
        this.operationStats = operationStats;
        this.memoryStats = memoryStats;
        this.warningCount = warningCount;
        this.errorCount = errorCount;
        this.timestamp = timestamp;
    }
}

class PerformanceReport {
    final PerformanceStats stats;
    final List<Bottleneck> bottlenecks;
    final List<PerformanceTrend> trends;
    final List<String> recommendations;
    final PerformanceBaseline baseline;
    
    PerformanceReport(PerformanceStats stats, List<Bottleneck> bottlenecks,
                     List<PerformanceTrend> trends, List<String> recommendations,
                     PerformanceBaseline baseline) {
        this.stats = stats;
        this.bottlenecks = bottlenecks;
        this.trends = trends;
        this.recommendations = recommendations;
        this.baseline = baseline;
    }
}

class PerformanceBaseline {
    final double frameTimeMs;
    final double frameTimeP95Ms;
    final double averageFPS;
    final long memoryBytes;
    final double poolHitRate;
    final long timestamp;
    
    PerformanceBaseline() {
        this(0, 0, 0, 0, 0, 0);
    }
    
    PerformanceBaseline(double frameTimeMs, double frameTimeP95Ms, double averageFPS,
                       long memoryBytes, double poolHitRate, long timestamp) {
        this.frameTimeMs = frameTimeMs;
        this.frameTimeP95Ms = frameTimeP95Ms;
        this.averageFPS = averageFPS;
        this.memoryBytes = memoryBytes;
        this.poolHitRate = poolHitRate;
        this.timestamp = timestamp;
    }
}

class Bottleneck {
    final BottleneckType type;
    final String location;
    final double severity;
    final String description;
    
    Bottleneck(BottleneckType type, String location, double severity, String description) {
        this.type = type;
        this.location = location;
        this.severity = severity;
        this.description = description;
    }
}

enum BottleneckType {
    SLOW_OPERATION,
    MEMORY_PRESSURE,
    GPU_STALL
}

class PerformanceTrend {
    // Placeholder for trend analysis
}