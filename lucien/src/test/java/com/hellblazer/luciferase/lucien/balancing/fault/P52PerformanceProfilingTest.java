package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.BalanceConfiguration;
import com.hellblazer.luciferase.lucien.balancing.DefaultParallelBalancer;
import com.hellblazer.luciferase.lucien.balancing.ParallelBalancer;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Phase P5.2: Performance Profiling & Optimization Tests.
 *
 * <p>Comprehensive performance profiling suite testing:
 * <ul>
 *   <li>Recovery phase latency profiling</li>
 *   <li>Component overhead measurement (listener, VON, ghost)</li>
 *   <li>Resource usage tracking (memory, threads)</li>
 *   <li>Throughput measurement during recovery</li>
 *   <li>Bottleneck identification</li>
 *   <li>Baseline establishment and regression tracking</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class P52PerformanceProfilingTest {

    private static final Logger log = LoggerFactory.getLogger(P52PerformanceProfilingTest.class);

    private Forest<MortonKey, UUIDEntityID, byte[]> mockForest;
    private DistributedGhostManager<MortonKey, UUIDEntityID, byte[]> mockGhostManager;
    private ParallelBalancer.PartitionRegistry mockRegistry;
    private GhostLayer<MortonKey, UUIDEntityID, byte[]> mockGhostLayer;

    private SimpleFaultHandler faultHandler;
    private RecoveryCoordinatorLock recoveryLock;
    private InFlightOperationTracker tracker;
    private PerformanceProfiler profiler;

    @BeforeEach
    void setUp() {
        // Initialize mocks
        mockForest = mock(Forest.class);
        mockGhostManager = mock(DistributedGhostManager.class);
        mockRegistry = mock(ParallelBalancer.PartitionRegistry.class);
        mockGhostLayer = mock(GhostLayer.class);

        when(mockGhostManager.getGhostLayer()).thenReturn(mockGhostLayer);
        when(mockRegistry.getPartitionCount()).thenReturn(3);

        // Initialize real components
        faultHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        recoveryLock = new RecoveryCoordinatorLock(3);
        tracker = new InFlightOperationTracker();
        profiler = new PerformanceProfiler();

        log.info("Test setup complete");
    }

    @AfterEach
    void tearDown() {
        if (profiler != null) {
            profiler.reset();
        }
        log.info("Test teardown complete");
    }

    // === Test 1: Recovery Latency Profiling ===

    /**
     * Test 1: Profile recovery phase latencies.
     *
     * <p>Validates that PerformanceProfiler accurately measures each recovery phase:
     * DETECTING, REDISTRIBUTING, REBALANCING, VALIDATING, COMPLETE.
     */
    @Test
    void testRecoveryLatencyProfile() throws Exception {
        log.info("=== Test 1: Recovery Latency Profile ===");

        var partitionId = UUID.randomUUID();

        // Simulate recovery phases with known delays
        var detectTimer = profiler.startPhase(PerformanceProfiler.RecoveryPhase.DETECTING, partitionId);
        Thread.sleep(50); // Simulate 50ms detection
        profiler.endPhase(detectTimer);

        var redistributeTimer = profiler.startPhase(
            PerformanceProfiler.RecoveryPhase.REDISTRIBUTING, partitionId);
        Thread.sleep(100); // Simulate 100ms redistribution
        profiler.endPhase(redistributeTimer);

        var rebalanceTimer = profiler.startPhase(
            PerformanceProfiler.RecoveryPhase.REBALANCING, partitionId);
        Thread.sleep(75); // Simulate 75ms rebalancing
        profiler.endPhase(rebalanceTimer);

        var validateTimer = profiler.startPhase(
            PerformanceProfiler.RecoveryPhase.VALIDATING, partitionId);
        Thread.sleep(30); // Simulate 30ms validation
        profiler.endPhase(validateTimer);

        var completeTimer = profiler.startPhase(
            PerformanceProfiler.RecoveryPhase.COMPLETE, partitionId);
        Thread.sleep(10); // Simulate 10ms completion
        profiler.endPhase(completeTimer);

        // Generate report
        var report = profiler.generateReport();

        // Validate phase measurements
        var detectStats = report.phaseLatencies.get(PerformanceProfiler.RecoveryPhase.DETECTING);
        assertNotNull(detectStats, "DETECTING phase should be measured");
        assertEquals(1, detectStats.count(), "Should have 1 detection measurement");
        assertTrue(detectStats.avgMs() >= 45 && detectStats.avgMs() <= 100,
            "Detection should take ~50ms, got " + detectStats.avgMs());

        var redistributeStats = report.phaseLatencies.get(PerformanceProfiler.RecoveryPhase.REDISTRIBUTING);
        assertNotNull(redistributeStats, "REDISTRIBUTING phase should be measured");
        assertTrue(redistributeStats.avgMs() >= 95 && redistributeStats.avgMs() <= 150,
            "Redistribution should take ~100ms, got " + redistributeStats.avgMs());

        var completeStats = report.phaseLatencies.get(PerformanceProfiler.RecoveryPhase.COMPLETE);
        assertNotNull(completeStats, "COMPLETE phase should be measured");
        assertTrue(completeStats.avgMs() >= 5 && completeStats.avgMs() <= 50,
            "Completion should take ~10ms, got " + completeStats.avgMs());

        // Validate partition-specific metrics
        var partitionMetrics = report.partitionMetrics.get(partitionId);
        assertNotNull(partitionMetrics, "Partition metrics should be tracked");

        log.info("Recovery latency profile: {}", report);
        log.info("✅ Test 1 passed");
    }

    // === Test 2: Listener Notification Latency ===

    /**
     * Test 2: Profile listener notification overhead.
     *
     * <p>Measures p99 listener callback latency to ensure it stays <10μs.
     */
    @Test
    void testListenerNotificationLatency() {
        log.info("=== Test 2: Listener Notification Latency ===");

        // Simulate fast listeners (1-5μs)
        for (int i = 0; i < 95; i++) {
            profiler.profileListenerNotification(() -> {
                // Simulate fast listener (busy wait for ~1-2μs)
                var end = System.nanoTime() + 2000;
                while (System.nanoTime() < end) {
                    // Busy wait
                }
            });
        }

        // Simulate slow listeners (10-20μs) - should show up in p99
        for (int i = 0; i < 5; i++) {
            profiler.profileListenerNotification(() -> {
                var end = System.nanoTime() + 15000;
                while (System.nanoTime() < end) {
                    // Busy wait
                }
            });
        }

        var report = profiler.generateReport();
        var listenerStats = report.metrics.get(PerformanceProfiler.MetricType.LISTENER_NOTIFICATION);

        assertNotNull(listenerStats, "Listener notification metrics should be captured");
        assertEquals(100, listenerStats.count(), "Should have 100 listener measurements");

        var avgMicros = listenerStats.avg() / 1000.0;
        var maxMicros = listenerStats.max() / 1000.0;

        log.info("Listener notification: avg={}μs, max={}μs", avgMicros, maxMicros);

        assertTrue(avgMicros < 10.0, "Average listener latency should be <10μs, got " + avgMicros);
        assertTrue(maxMicros < 30.0, "Max listener latency should be <30μs, got " + maxMicros);

        log.info("✅ Test 2 passed");
    }

    // === Test 3: VON Topology Update Latency ===

    /**
     * Test 3: Profile VON topology update cost.
     *
     * <p>Validates that VON neighbor updates complete in <100ms.
     */
    @Test
    void testVONTopologyUpdateLatency() {
        log.info("=== Test 3: VON Topology Update Latency ===");

        // Simulate VON topology updates (typical: 20-50ms)
        for (int i = 0; i < 10; i++) {
            profiler.profileVONTopologyUpdate(() -> {
                try {
                    // Simulate topology computation
                    Thread.sleep(20 + (int) (Math.random() * 30));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        var report = profiler.generateReport();
        var vonStats = report.metrics.get(PerformanceProfiler.MetricType.VON_TOPOLOGY_UPDATE);

        assertNotNull(vonStats, "VON topology metrics should be captured");
        assertEquals(10, vonStats.count(), "Should have 10 VON update measurements");

        var avgMs = vonStats.avg() / 1_000_000.0;
        var maxMs = vonStats.max() / 1_000_000.0;

        log.info("VON topology update: avg={}ms, max={}ms", avgMs, maxMs);

        assertTrue(avgMs < 100.0, "Average VON update should be <100ms, got " + avgMs);
        assertTrue(maxMs < 150.0, "Max VON update should be <150ms, got " + maxMs);

        log.info("✅ Test 3 passed");
    }

    // === Test 4: Message Throughput During Recovery ===

    /**
     * Test 4: Profile message throughput during recovery.
     *
     * <p>Establishes baseline for messages/sec during recovery operations.
     */
    @Test
    void testMessageThroughputDuringRecovery() throws Exception {
        log.info("=== Test 4: Message Throughput During Recovery ===");

        var partitionId = UUID.randomUUID();

        // Simulate recovery with message processing
        var startTime = System.currentTimeMillis();
        var messageCount = 0;

        for (int i = 0; i < 5; i++) {
            // Simulate batch of messages
            for (int j = 0; j < 200; j++) {
                profiler.recordMessage(partitionId);
                messageCount++;
            }
            Thread.sleep(10); // Simulate processing delay
        }

        var duration = (System.currentTimeMillis() - startTime) / 1000.0; // seconds
        var throughput = messageCount / duration;

        profiler.recordThroughput(throughput);

        var report = profiler.generateReport();
        var throughputStats = report.metrics.get(PerformanceProfiler.MetricType.THROUGHPUT);

        assertNotNull(throughputStats, "Throughput metrics should be captured");
        assertTrue(throughputStats.avg() > 500,
            "Throughput should be >500 msgs/sec, got " + throughputStats.avg());

        var partMetrics = report.partitionMetrics.get(partitionId);
        assertNotNull(partMetrics, "Partition message count should be tracked");
        assertEquals(messageCount, partMetrics.messageCount(),
            "Message count should match recorded count");

        log.info("Message throughput: {} msgs/sec", throughputStats.avg());
        log.info("✅ Test 4 passed");
    }

    // === Test 5: Ghost Layer Validation Throughput ===

    /**
     * Test 5: Profile ghost layer validation performance.
     *
     * <p>Ensures ghost validation completes in <50ms baseline.
     */
    @Test
    void testGhostLayerValidationThroughput() {
        log.info("=== Test 5: Ghost Layer Validation Throughput ===");

        // Simulate ghost validations (typical: 10-30ms)
        for (int i = 0; i < 20; i++) {
            profiler.profileGhostLayerValidation(() -> {
                try {
                    Thread.sleep(10 + (int) (Math.random() * 20));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        var report = profiler.generateReport();
        var ghostStats = report.metrics.get(PerformanceProfiler.MetricType.GHOST_LAYER_VALIDATION);

        assertNotNull(ghostStats, "Ghost validation metrics should be captured");
        assertEquals(20, ghostStats.count(), "Should have 20 validation measurements");

        var avgMs = ghostStats.avg() / 1_000_000.0;

        log.info("Ghost validation: avg={}ms", avgMs);

        assertTrue(avgMs < 50.0,
            "Average ghost validation should be <50ms, got " + avgMs);

        log.info("✅ Test 5 passed");
    }

    // === Test 6: Memory Usage Profile ===

    /**
     * Test 6: Profile memory usage during recovery.
     *
     * <p>Tracks memory delta and ensures it stays <100MB per partition.
     */
    @Test
    void testMemoryUsageProfile() throws Exception {
        log.info("=== Test 6: Memory Usage Profile ===");

        var baselineMemory = profiler.snapshotMemory();
        log.info("Baseline memory: {} MB", baselineMemory / (1024.0 * 1024.0));

        // Simulate workload that allocates memory
        var partitionId = UUID.randomUUID();
        var allocations = new ArrayList<byte[]>();

        var timer = profiler.startPhase(PerformanceProfiler.RecoveryPhase.REDISTRIBUTING, partitionId);

        // Allocate ~20MB
        for (int i = 0; i < 20; i++) {
            allocations.add(new byte[1024 * 1024]); // 1MB each
        }

        profiler.endPhase(timer);

        var afterMemory = profiler.snapshotMemory();
        var memoryDelta = (afterMemory - baselineMemory) / (1024.0 * 1024.0);

        log.info("Memory after workload: {} MB (delta: {} MB)",
            afterMemory / (1024.0 * 1024.0), memoryDelta);

        var report = profiler.generateReport();

        // Verify memory metrics are tracked
        var memoryStats = report.metrics.get(PerformanceProfiler.MetricType.MEMORY);
        assertNotNull(memoryStats, "Memory metrics should be captured");

        // Memory usage should be reasonable (< 100MB per partition for this test)
        assertTrue(memoryDelta < 100.0,
            "Memory delta should be <100MB, got " + memoryDelta);

        // Cleanup
        allocations.clear();

        log.info("✅ Test 6 passed");
    }

    // === Test 7: Thread Usage Profile ===

    /**
     * Test 7: Profile thread count during recovery.
     *
     * <p>Ensures thread count stays reasonable during concurrent operations.
     */
    @Test
    void testThreadUsageProfile() throws Exception {
        log.info("=== Test 7: Thread Usage Profile ===");

        var baselineThreads = profiler.snapshotThreadCount();
        log.info("Baseline threads: {}", baselineThreads);

        // Spawn some worker threads
        var executor = Executors.newFixedThreadPool(10);
        var futures = new ArrayList<Future<?>>();

        for (int i = 0; i < 10; i++) {
            futures.add(executor.submit(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // Measure thread count
        var peakThreads = profiler.snapshotThreadCount();
        log.info("Peak threads: {}", peakThreads);

        // Wait for completion
        for (var future : futures) {
            future.get();
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        var finalThreads = profiler.snapshotThreadCount();
        log.info("Final threads: {}", finalThreads);

        var report = profiler.generateReport();
        var threadStats = report.metrics.get(PerformanceProfiler.MetricType.THREAD_COUNT);

        assertNotNull(threadStats, "Thread count metrics should be captured");
        assertTrue(threadStats.max() > baselineThreads,
            "Peak threads should exceed baseline");

        log.info("✅ Test 7 passed");
    }

    // === Test 8: Bottleneck Identification ===

    /**
     * Test 8: Identify performance bottlenecks.
     *
     * <p>Uses OptimizationAnalyzer to identify slowest components and estimate ROI.
     */
    @Test
    void testIdentifyBottlenecks() throws Exception {
        log.info("=== Test 8: Identify Bottlenecks ===");

        var partitionId = UUID.randomUUID();

        // Simulate recovery with intentional bottleneck
        var detectTimer = profiler.startPhase(PerformanceProfiler.RecoveryPhase.DETECTING, partitionId);
        Thread.sleep(50);
        profiler.endPhase(detectTimer);

        // Simulate slow redistribution phase (bottleneck)
        var redistributeTimer = profiler.startPhase(
            PerformanceProfiler.RecoveryPhase.REDISTRIBUTING, partitionId);
        Thread.sleep(1200); // >1s = slow
        profiler.endPhase(redistributeTimer);

        // Simulate expensive listener
        for (int i = 0; i < 10; i++) {
            profiler.profileListenerNotification(() -> {
                var end = System.nanoTime() + 150000; // 150μs
                while (System.nanoTime() < end) {
                    // Busy wait
                }
            });
        }

        var report = profiler.generateReport();
        var analyzer = new OptimizationAnalyzer();
        var analysis = analyzer.analyze(report);

        // Verify bottlenecks were identified
        assertFalse(analysis.bottlenecks.isEmpty(),
            "Should identify at least one bottleneck");

        var slowPhaseBottleneck = analysis.bottlenecks.stream()
            .filter(b -> b.type == OptimizationAnalyzer.BottleneckType.SLOW_PHASE)
            .findFirst();

        assertTrue(slowPhaseBottleneck.isPresent(),
            "Should identify slow REDISTRIBUTING phase");

        var expensiveListenerBottleneck = analysis.bottlenecks.stream()
            .filter(b -> b.type == OptimizationAnalyzer.BottleneckType.EXPENSIVE_LISTENER)
            .findFirst();

        assertTrue(expensiveListenerBottleneck.isPresent(),
            "Should identify expensive listener");

        // Verify recommendations
        assertFalse(analysis.recommendations.isEmpty(),
            "Should generate recommendations");

        log.info("Bottlenecks identified: {}", analysis.bottlenecks.size());
        log.info("Analysis: {}", analysis);
        log.info("✅ Test 8 passed");
    }

    // === Test 9: ROI Analysis ===

    /**
     * Test 9: Estimate optimization ROI.
     *
     * <p>Validates that ROI estimates prioritize high-impact, low-effort optimizations.
     */
    @Test
    void testROIAnalysis() throws Exception {
        log.info("=== Test 9: ROI Analysis ===");

        var partitionId = UUID.randomUUID();

        // Create various bottlenecks with different ROI profiles
        // 1. Slow phase (medium effort, high impact)
        var redistributeTimer = profiler.startPhase(
            PerformanceProfiler.RecoveryPhase.REDISTRIBUTING, partitionId);
        Thread.sleep(1500);
        profiler.endPhase(redistributeTimer);

        // 2. Expensive listener (low effort, high impact) - should be high priority
        for (int i = 0; i < 20; i++) {
            profiler.profileListenerNotification(() -> {
                var end = System.nanoTime() + 200000; // 200μs
                while (System.nanoTime() < end) {
                    // Busy wait
                }
            });
        }

        // 3. Expensive ghost validation (medium effort, high impact)
        for (int i = 0; i < 10; i++) {
            profiler.profileGhostLayerValidation(() -> {
                try {
                    Thread.sleep(60); // >50ms threshold
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        var report = profiler.generateReport();
        var analyzer = new OptimizationAnalyzer();
        var analysis = analyzer.analyze(report);

        // Verify high-priority items exist
        var highPriorityCount = analysis.bottlenecks.stream()
            .filter(b -> b.estimatedROI.priority() == OptimizationAnalyzer.Priority.HIGH)
            .count();

        assertTrue(highPriorityCount > 0,
            "Should have high-priority optimization opportunities");

        // Verify listener optimization is high priority (easy win)
        var listenerBottleneck = analysis.bottlenecks.stream()
            .filter(b -> b.type == OptimizationAnalyzer.BottleneckType.EXPENSIVE_LISTENER)
            .findFirst();

        assertTrue(listenerBottleneck.isPresent(), "Should identify listener bottleneck");
        assertEquals(OptimizationAnalyzer.Priority.HIGH,
            listenerBottleneck.get().estimatedROI.priority(),
            "Listener optimization should be high priority");
        assertEquals(OptimizationAnalyzer.EstimatedEffort.LOW,
            listenerBottleneck.get().estimatedROI.effort(),
            "Listener optimization should be low effort");

        log.info("High-priority optimizations: {}", highPriorityCount);
        log.info("✅ Test 9 passed");
    }

    // === Test 10: Baseline Comparison ===

    /**
     * Test 10: Compare performance to baseline.
     *
     * <p>Validates regression detection by comparing current performance to established baseline.
     */
    @Test
    void testBaselineComparison() throws Exception {
        log.info("=== Test 10: Baseline Comparison ===");

        // Create reference baseline (good performance)
        var referenceBaseline = new PerformanceBaseline();
        referenceBaseline.record(
            PerformanceBaseline.BaselineMetric.SINGLE_PARTITION_RECOVERY_MS, 3000.0);
        referenceBaseline.record(
            PerformanceBaseline.BaselineMetric.LISTENER_NOTIFICATION_P99_US, 8.0);
        referenceBaseline.record(
            PerformanceBaseline.BaselineMetric.GHOST_LAYER_VALIDATION_MS, 30.0);

        // Create current baseline (with regression in recovery time)
        var currentBaseline = new PerformanceBaseline();
        currentBaseline.record(
            PerformanceBaseline.BaselineMetric.SINGLE_PARTITION_RECOVERY_MS, 4500.0); // 50% slower
        currentBaseline.record(
            PerformanceBaseline.BaselineMetric.LISTENER_NOTIFICATION_P99_US, 7.0); // Improved
        currentBaseline.record(
            PerformanceBaseline.BaselineMetric.GHOST_LAYER_VALIDATION_MS, 32.0); // Stable

        // Compare
        var regression = new PerformanceRegression();
        var report = regression.compare(currentBaseline, referenceBaseline);

        // Verify regression detection
        assertTrue(report.hasRegressions(),
            "Should detect regression in recovery time");

        var recoveryComparison = report.comparisons.get(
            PerformanceBaseline.BaselineMetric.SINGLE_PARTITION_RECOVERY_MS);
        assertNotNull(recoveryComparison, "Should have recovery comparison");
        assertEquals(PerformanceRegression.ComparisonStatus.REGRESSION,
            recoveryComparison.status(),
            "Recovery time should be marked as regression");
        assertTrue(recoveryComparison.percentChange() > 10.0,
            "Regression should be >10%");

        // Verify improvement detection
        assertFalse(report.improvements.isEmpty(),
            "Should detect listener improvement");

        var listenerComparison = report.comparisons.get(
            PerformanceBaseline.BaselineMetric.LISTENER_NOTIFICATION_P99_US);
        assertEquals(PerformanceRegression.ComparisonStatus.IMPROVEMENT,
            listenerComparison.status(),
            "Listener should be marked as improvement");

        // Analyze regression
        var analysis = regression.analyze(report);
        assertFalse(analysis.recommendations.isEmpty(),
            "Should generate recommendations");

        log.info("Regression report: {}", report);
        log.info("Analysis: {}", analysis);
        log.info("✅ Test 10 passed");
    }

    // === Test 11: Baseline Export/Import ===

    /**
     * Test 11: Export and import baseline metrics.
     *
     * <p>Validates JSON serialization for baseline persistence.
     */
    @Test
    void testBaselineExportImport() throws IOException {
        log.info("=== Test 11: Baseline Export/Import ===");

        // Create baseline
        var baseline = new PerformanceBaseline();
        baseline.setTestEnvironment("JUnit Test Environment");
        baseline.setGitCommit("test-commit-hash");
        baseline.record(PerformanceBaseline.BaselineMetric.SINGLE_PARTITION_RECOVERY_MS, 3500.0);
        baseline.record(PerformanceBaseline.BaselineMetric.LISTENER_NOTIFICATION_P99_US, 9.0);

        // Export to temp file
        var tempFile = File.createTempFile("performance-baseline-", ".json");
        tempFile.deleteOnExit();

        baseline.exportToJson(tempFile);
        assertTrue(tempFile.exists(), "Export file should be created");
        assertTrue(tempFile.length() > 0, "Export file should not be empty");

        // Import
        var imported = PerformanceBaseline.importFromJson(tempFile);

        // Verify
        assertNotNull(imported, "Should import baseline");
        assertEquals(3500.0, imported.getActualValue(
            PerformanceBaseline.BaselineMetric.SINGLE_PARTITION_RECOVERY_MS),
            "Should preserve recovery metric");
        assertEquals(9.0, imported.getActualValue(
            PerformanceBaseline.BaselineMetric.LISTENER_NOTIFICATION_P99_US),
            "Should preserve listener metric");

        log.info("Baseline exported and imported successfully");
        log.info("✅ Test 11 passed");
    }
}
