package com.hellblazer.luciferase.lucien.balancing.fault.profiling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance profiler for fault tolerance and recovery operations.
 *
 * <p>Provides comprehensive instrumentation for:
 * <ul>
 *   <li>Recovery phase latencies (DETECTING, REDISTRIBUTING, REBALANCING, VALIDATING)</li>
 *   <li>Throughput metrics (messages/sec during recovery)</li>
 *   <li>Resource usage (memory, thread count)</li>
 *   <li>Listener notification overhead</li>
 *   <li>VON topology update cost</li>
 *   <li>Ghost layer validation performance</li>
 * </ul>
 *
 * <p><b>Thread Safety</b>: All operations are thread-safe using concurrent collections.
 *
 * @author hal.hildebrand
 */
public class PerformanceProfiler {

    private static final Logger log = LoggerFactory.getLogger(PerformanceProfiler.class);

    /**
     * Recovery phases tracked by the profiler.
     */
    public enum RecoveryPhase {
        DETECTING,       // Fault detection
        REDISTRIBUTING,  // Entity redistribution
        REBALANCING,     // Tree rebalancing
        VALIDATING,      // Ghost layer validation
        COMPLETE         // Full recovery cycle
    }

    /**
     * Metric types tracked by the profiler.
     */
    public enum MetricType {
        LATENCY,                    // Operation duration
        THROUGHPUT,                 // Operations per second
        MEMORY,                     // Memory usage in bytes
        THREAD_COUNT,               // Active thread count
        LISTENER_NOTIFICATION,      // Listener call overhead
        VON_TOPOLOGY_UPDATE,        // VON neighbor update cost
        GHOST_LAYER_VALIDATION,     // Ghost validation cost
        MESSAGE_RATE                // Messages per second
    }

    // Phase latency tracking
    private final Map<RecoveryPhase, LatencyTracker> phaseLatencies = new ConcurrentHashMap<>();

    // General metric tracking
    private final Map<MetricType, MetricTracker> metrics = new ConcurrentHashMap<>();

    // Per-partition metrics
    private final Map<UUID, PartitionMetrics> partitionMetrics = new ConcurrentHashMap<>();

    // Sampling configuration
    private volatile int samplingRate = 1;  // Sample every operation by default
    private final AtomicLong operationCount = new AtomicLong(0);

    // Resource tracking
    private final Runtime runtime = Runtime.getRuntime();
    private volatile long baselineMemory = 0;

    /**
     * Create a new performance profiler.
     */
    public PerformanceProfiler() {
        // Initialize phase latency trackers
        for (var phase : RecoveryPhase.values()) {
            phaseLatencies.put(phase, new LatencyTracker(phase.name()));
        }

        // Initialize metric trackers
        for (var type : MetricType.values()) {
            metrics.put(type, new MetricTracker(type.name()));
        }

        // Capture baseline memory
        System.gc();
        baselineMemory = getCurrentMemoryUsed();

        log.info("PerformanceProfiler initialized with baseline memory: {} MB",
            baselineMemory / (1024.0 * 1024.0));
    }

    // === Phase Latency Tracking ===

    /**
     * Start timing a recovery phase.
     *
     * @param phase the recovery phase
     * @param partitionId the partition being recovered
     * @return timer token to complete timing
     */
    public PhaseTimer startPhase(RecoveryPhase phase, UUID partitionId) {
        var tracker = phaseLatencies.get(phase);
        var startTime = System.nanoTime();
        var startMemory = shouldSample() ? getCurrentMemoryUsed() : 0;

        return new PhaseTimer(phase, partitionId, tracker, startTime, startMemory);
    }

    /**
     * Record phase completion.
     */
    public void endPhase(PhaseTimer timer) {
        var duration = System.nanoTime() - timer.startTime;
        var memoryDelta = shouldSample() ? (getCurrentMemoryUsed() - timer.startMemory) : 0;

        timer.tracker.record(duration, memoryDelta);

        // Update partition-specific metrics
        var partMetrics = partitionMetrics.computeIfAbsent(
            timer.partitionId,
            id -> new PartitionMetrics(id)
        );
        partMetrics.recordPhase(timer.phase, duration);

        if (log.isDebugEnabled()) {
            log.debug("Phase {} for partition {} took {} ms",
                timer.phase, timer.partitionId, duration / 1_000_000.0);
        }
    }

    // === Throughput Tracking ===

    /**
     * Record a message processed during recovery.
     *
     * @param partitionId the partition processing the message
     */
    public void recordMessage(UUID partitionId) {
        metrics.get(MetricType.MESSAGE_RATE).increment();

        var partMetrics = partitionMetrics.computeIfAbsent(
            partitionId,
            id -> new PartitionMetrics(id)
        );
        partMetrics.messageCount.incrementAndGet();
    }

    /**
     * Record throughput measurement.
     *
     * @param messagesPerSecond the measured throughput
     */
    public void recordThroughput(double messagesPerSecond) {
        metrics.get(MetricType.THROUGHPUT).recordValue((long) messagesPerSecond);
    }

    // === Resource Tracking ===

    /**
     * Take a memory usage snapshot.
     *
     * @return current memory usage in bytes
     */
    public long snapshotMemory() {
        var memoryUsed = getCurrentMemoryUsed();
        var delta = memoryUsed - baselineMemory;
        metrics.get(MetricType.MEMORY).recordValue(delta);
        return memoryUsed;
    }

    /**
     * Take a thread count snapshot.
     *
     * @return current active thread count
     */
    public int snapshotThreadCount() {
        var threadCount = Thread.activeCount();
        metrics.get(MetricType.THREAD_COUNT).recordValue(threadCount);
        return threadCount;
    }

    // === Component Overhead Tracking ===

    /**
     * Time a listener notification.
     *
     * @param runnable the listener callback
     */
    public void profileListenerNotification(Runnable runnable) {
        var start = System.nanoTime();
        try {
            runnable.run();
        } finally {
            var duration = System.nanoTime() - start;
            metrics.get(MetricType.LISTENER_NOTIFICATION).recordValue(duration);
        }
    }

    /**
     * Time a VON topology update.
     *
     * @param runnable the topology update operation
     */
    public void profileVONTopologyUpdate(Runnable runnable) {
        var start = System.nanoTime();
        try {
            runnable.run();
        } finally {
            var duration = System.nanoTime() - start;
            metrics.get(MetricType.VON_TOPOLOGY_UPDATE).recordValue(duration);
        }
    }

    /**
     * Time a ghost layer validation.
     *
     * @param runnable the validation operation
     */
    public void profileGhostLayerValidation(Runnable runnable) {
        var start = System.nanoTime();
        try {
            runnable.run();
        } finally {
            var duration = System.nanoTime() - start;
            metrics.get(MetricType.GHOST_LAYER_VALIDATION).recordValue(duration);
        }
    }

    // === Reporting ===

    /**
     * Generate a comprehensive performance report.
     *
     * @return performance report with all metrics
     */
    public PerformanceReport generateReport() {
        var report = new PerformanceReport();

        // Phase latencies
        for (var entry : phaseLatencies.entrySet()) {
            report.phaseLatencies.put(entry.getKey(), entry.getValue().getStats());
        }

        // General metrics
        for (var entry : metrics.entrySet()) {
            report.metrics.put(entry.getKey(), entry.getValue().getStats());
        }

        // Per-partition metrics
        for (var entry : partitionMetrics.entrySet()) {
            report.partitionMetrics.put(entry.getKey(), entry.getValue().getStats());
        }

        // System metrics
        report.currentMemoryMB = getCurrentMemoryUsed() / (1024.0 * 1024.0);
        report.baselineMemoryMB = baselineMemory / (1024.0 * 1024.0);
        report.activeThreads = Thread.activeCount();

        return report;
    }

    /**
     * Reset all metrics.
     */
    public void reset() {
        phaseLatencies.values().forEach(LatencyTracker::reset);
        metrics.values().forEach(MetricTracker::reset);
        partitionMetrics.clear();
        operationCount.set(0);

        System.gc();
        baselineMemory = getCurrentMemoryUsed();

        log.info("PerformanceProfiler reset");
    }

    /**
     * Set sampling rate for resource measurements.
     *
     * @param rate sample every N operations (1 = every operation)
     */
    public void setSamplingRate(int rate) {
        this.samplingRate = Math.max(1, rate);
    }

    // === Private Helpers ===

    private boolean shouldSample() {
        return (operationCount.incrementAndGet() % samplingRate) == 0;
    }

    private long getCurrentMemoryUsed() {
        MemoryUsage heapUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heapUsage.getUsed();
    }

    // === Inner Classes ===

    /**
     * Timer token for phase timing.
     */
    public static class PhaseTimer {
        final RecoveryPhase phase;
        final UUID partitionId;
        final LatencyTracker tracker;
        final long startTime;
        final long startMemory;

        PhaseTimer(RecoveryPhase phase, UUID partitionId, LatencyTracker tracker,
                   long startTime, long startMemory) {
            this.phase = phase;
            this.partitionId = partitionId;
            this.tracker = tracker;
            this.startTime = startTime;
            this.startMemory = startMemory;
        }
    }

    /**
     * Latency tracker with percentile calculation.
     */
    private static class LatencyTracker {
        private final String name;
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong totalNanos = new AtomicLong(0);
        private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxNanos = new AtomicLong(0);
        private final AtomicLong totalMemoryDelta = new AtomicLong(0);
        private final List<Long> samples = new CopyOnWriteArrayList<>();

        LatencyTracker(String name) {
            this.name = name;
        }

        void record(long durationNanos, long memoryDelta) {
            count.incrementAndGet();
            totalNanos.addAndGet(durationNanos);

            // Update min/max
            long currentMin, currentMax;
            do {
                currentMin = minNanos.get();
            } while (durationNanos < currentMin && !minNanos.compareAndSet(currentMin, durationNanos));

            do {
                currentMax = maxNanos.get();
            } while (durationNanos > currentMax && !maxNanos.compareAndSet(currentMax, durationNanos));

            // Memory delta
            if (memoryDelta != 0) {
                totalMemoryDelta.addAndGet(memoryDelta);
            }

            // Keep samples for percentile calculation (limit to 10,000)
            if (samples.size() < 10000) {
                samples.add(durationNanos);
            }
        }

        LatencyStats getStats() {
            var cnt = count.get();
            if (cnt == 0) {
                return new LatencyStats(name, 0, 0, 0, 0, 0, 0, 0, 0);
            }

            var avgMs = (totalNanos.get() / cnt) / 1_000_000.0;
            var minMs = minNanos.get() / 1_000_000.0;
            var maxMs = maxNanos.get() / 1_000_000.0;

            // Calculate percentiles
            var sortedSamples = new ArrayList<>(samples);
            Collections.sort(sortedSamples);

            double p50 = 0, p95 = 0, p99 = 0;
            if (!sortedSamples.isEmpty()) {
                p50 = sortedSamples.get(sortedSamples.size() / 2) / 1_000_000.0;
                p95 = sortedSamples.get((int) (sortedSamples.size() * 0.95)) / 1_000_000.0;
                p99 = sortedSamples.get((int) (sortedSamples.size() * 0.99)) / 1_000_000.0;
            }

            var avgMemoryMB = (totalMemoryDelta.get() / cnt) / (1024.0 * 1024.0);

            return new LatencyStats(name, cnt, avgMs, minMs, maxMs, p50, p95, p99, avgMemoryMB);
        }

        void reset() {
            count.set(0);
            totalNanos.set(0);
            minNanos.set(Long.MAX_VALUE);
            maxNanos.set(0);
            totalMemoryDelta.set(0);
            samples.clear();
        }
    }

    /**
     * General metric tracker.
     */
    private static class MetricTracker {
        private final String name;
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong sum = new AtomicLong(0);
        private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);

        MetricTracker(String name) {
            this.name = name;
        }

        void increment() {
            count.incrementAndGet();
        }

        void recordValue(long value) {
            count.incrementAndGet();
            sum.addAndGet(value);

            long currentMin, currentMax;
            do {
                currentMin = min.get();
            } while (value < currentMin && !min.compareAndSet(currentMin, value));

            do {
                currentMax = max.get();
            } while (value > currentMax && !max.compareAndSet(currentMax, value));
        }

        MetricStats getStats() {
            var cnt = count.get();
            var avg = cnt > 0 ? sum.get() / (double) cnt : 0;
            var minVal = cnt > 0 ? min.get() : 0;
            var maxVal = cnt > 0 ? max.get() : 0;
            return new MetricStats(name, cnt, avg, minVal, maxVal);
        }

        void reset() {
            count.set(0);
            sum.set(0);
            min.set(Long.MAX_VALUE);
            max.set(Long.MIN_VALUE);
        }
    }

    /**
     * Per-partition metrics.
     */
    private static class PartitionMetrics {
        private final UUID partitionId;
        private final Map<RecoveryPhase, AtomicLong> phaseDurations = new ConcurrentHashMap<>();
        private final AtomicLong messageCount = new AtomicLong(0);

        PartitionMetrics(UUID partitionId) {
            this.partitionId = partitionId;
            for (var phase : RecoveryPhase.values()) {
                phaseDurations.put(phase, new AtomicLong(0));
            }
        }

        void recordPhase(RecoveryPhase phase, long durationNanos) {
            phaseDurations.get(phase).addAndGet(durationNanos);
        }

        PartitionMetricStats getStats() {
            var phaseStats = new EnumMap<RecoveryPhase, Long>(RecoveryPhase.class);
            for (var entry : phaseDurations.entrySet()) {
                phaseStats.put(entry.getKey(), entry.getValue().get() / 1_000_000); // Convert to ms
            }
            return new PartitionMetricStats(partitionId, phaseStats, messageCount.get());
        }
    }

    // === Stats Records ===

    public record LatencyStats(
        String name,
        long count,
        double avgMs,
        double minMs,
        double maxMs,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        double avgMemoryMB
    ) {}

    public record MetricStats(
        String name,
        long count,
        double avg,
        long min,
        long max
    ) {}

    public record PartitionMetricStats(
        UUID partitionId,
        Map<RecoveryPhase, Long> phaseDurationsMs,
        long messageCount
    ) {}

    /**
     * Comprehensive performance report.
     */
    public static class PerformanceReport {
        public Map<RecoveryPhase, LatencyStats> phaseLatencies = new EnumMap<>(RecoveryPhase.class);
        public Map<MetricType, MetricStats> metrics = new EnumMap<>(MetricType.class);
        public Map<UUID, PartitionMetricStats> partitionMetrics = new ConcurrentHashMap<>();
        public double currentMemoryMB;
        public double baselineMemoryMB;
        public int activeThreads;

        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append("\n=== Performance Profile Report ===\n");
            sb.append(String.format("Memory: Current=%.2f MB, Baseline=%.2f MB, Delta=%.2f MB\n",
                currentMemoryMB, baselineMemoryMB, currentMemoryMB - baselineMemoryMB));
            sb.append(String.format("Threads: %d active\n", activeThreads));

            sb.append("\n--- Recovery Phase Latencies ---\n");
            for (var entry : phaseLatencies.entrySet()) {
                var stats = entry.getValue();
                if (stats.count() > 0) {
                    sb.append(String.format("  %s: count=%d, avg=%.2fms, p50=%.2fms, p95=%.2fms, p99=%.2fms\n",
                        entry.getKey(), stats.count(), stats.avgMs(), stats.p50Ms(), stats.p95Ms(), stats.p99Ms()));
                }
            }

            sb.append("\n--- Component Metrics ---\n");
            for (var entry : metrics.entrySet()) {
                var stats = entry.getValue();
                if (stats.count() > 0) {
                    sb.append(String.format("  %s: count=%d, avg=%.2f, min=%d, max=%d\n",
                        entry.getKey(), stats.count(), stats.avg(), stats.min(), stats.max()));
                }
            }

            if (!partitionMetrics.isEmpty()) {
                sb.append(String.format("\n--- Per-Partition Metrics (%d partitions) ---\n", partitionMetrics.size()));
            }

            return sb.toString();
        }
    }
}
