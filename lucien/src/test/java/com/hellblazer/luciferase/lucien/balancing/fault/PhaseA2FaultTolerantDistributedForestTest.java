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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Phase A.2: FaultTolerantDistributedForest TDD tests.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Health state tracking</li>
 *   <li>Fault detection integration</li>
 *   <li>Quorum enforcement</li>
 *   <li>Synchronous pause barrier (CRITICAL 2)</li>
 *   <li>Atomic recovery lock (CRITICAL 1)</li>
 *   <li>Atomic quorum check (CRITICAL 3)</li>
 *   <li>DefaultParallelBalancer integration (CRITICAL 4)</li>
 * </ul>
 */
class PhaseA2FaultTolerantDistributedForestTest {

    private Forest<MortonKey, UUIDEntityID, byte[]> mockForest;
    private DistributedGhostManager<MortonKey, UUIDEntityID, byte[]> mockGhostManager;
    private ParallelBalancer.PartitionRegistry mockRegistry;
    private GhostLayer<MortonKey, UUIDEntityID, byte[]> mockGhostLayer;

    private SimpleFaultHandler faultHandler;
    private RecoveryCoordinatorLock recoveryLock;
    private InFlightOperationTracker tracker;
    private FaultTolerantDistributedForest<MortonKey, UUIDEntityID, byte[]> ftForest;

    @BeforeEach
    void setUp() {
        // Initialize mocks manually
        mockForest = org.mockito.Mockito.mock(Forest.class);
        mockGhostManager = org.mockito.Mockito.mock(DistributedGhostManager.class);
        mockRegistry = org.mockito.Mockito.mock(ParallelBalancer.PartitionRegistry.class);
        mockGhostLayer = org.mockito.Mockito.mock(GhostLayer.class);

        faultHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        recoveryLock = new RecoveryCoordinatorLock(3);
        tracker = new InFlightOperationTracker();

        org.mockito.Mockito.when(mockGhostManager.getGhostLayer()).thenReturn(mockGhostLayer);
    }

    @AfterEach
    void tearDown() {
        if (ftForest != null) {
            ftForest.stop();
        }
    }

    // === Test 6: Pause/Resume Coordination (CRITICAL 2: Synchronous Barrier) ===

    /**
     * Test 6: Verify pause barrier waits for in-flight operations to complete.
     *
     * <p>This test validates CRITICAL 2 fix: InFlightOperationTracker provides
     * synchronous pause that blocks until all in-flight operations complete.
     */
    @Test
    void testPauseBarrierWaitsForInFlightOperations() throws Exception {
        // Given: Tracker with a slow operation in progress
        var operationStarted = new CountDownLatch(1);
        var operationCanFinish = new CountDownLatch(1);

        // Start a slow operation in background
        var operationFuture = CompletableFuture.runAsync(() -> {
            try (var token = tracker.beginOperation()) {
                operationStarted.countDown();
                assertTrue(operationCanFinish.await(5, TimeUnit.SECONDS), "Operation timed out waiting for signal");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Operation interrupted");
            }
        });

        // Wait for operation to start
        assertTrue(operationStarted.await(1, TimeUnit.SECONDS), "Operation failed to start");
        assertEquals(1, tracker.getActiveCount(), "Should have 1 active operation");

        // When: Pause is called (in background, will block)
        var pauseComplete = new AtomicBoolean(false);
        var pauseFuture = CompletableFuture.runAsync(() -> {
            try {
                tracker.pauseAndWait(5, TimeUnit.SECONDS);
                pauseComplete.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Pause interrupted");
            }
        });

        // Verify pause is blocking
        Thread.sleep(100);
        assertFalse(pauseComplete.get(), "Pause should be blocked waiting for operation");
        assertTrue(tracker.isPaused(), "Tracker should be paused");

        // When: Operation completes
        operationCanFinish.countDown();
        operationFuture.get(1, TimeUnit.SECONDS);

        // Then: Pause should complete
        pauseFuture.get(1, TimeUnit.SECONDS);
        assertTrue(pauseComplete.get(), "Pause should complete after operation finishes");
        assertTrue(tracker.isPaused());
        assertEquals(0, tracker.getActiveCount());

        // Cleanup
        tracker.resume();
    }

    /**
     * Test 6b: Verify new operations are rejected when paused.
     */
    @Test
    void testBeginOperationRejectedWhenPaused() throws Exception {
        // Given: Paused tracker
        tracker.pauseAndWait(1, TimeUnit.SECONDS);
        assertTrue(tracker.isPaused());

        // When: Try to begin operation
        // Then: Should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> tracker.beginOperation());
    }

    /**
     * Test 6c: Verify tryBeginOperation returns empty when paused.
     */
    @Test
    void testTryBeginOperationReturnsEmptyWhenPaused() throws Exception {
        // Given: Paused tracker
        tracker.pauseAndWait(1, TimeUnit.SECONDS);
        assertTrue(tracker.isPaused());

        // When: Try to begin operation
        var result = tracker.tryBeginOperation();

        // Then: Should return empty
        assertTrue(result.isEmpty(), "tryBeginOperation should return empty when paused");

        // Cleanup
        tracker.resume();
    }

    /**
     * Test 6d: Verify resume allows new operations.
     */
    @Test
    void testResumeAllowsNewOperations() throws Exception {
        // Given: Paused tracker
        tracker.pauseAndWait(1, TimeUnit.SECONDS);
        assertTrue(tracker.isPaused());

        // When: Resume
        tracker.resume();

        // Then: Should allow new operations
        assertFalse(tracker.isPaused());
        try (var token = tracker.beginOperation()) {
            assertEquals(1, tracker.getActiveCount());
        }
        assertEquals(0, tracker.getActiveCount());
    }

    /**
     * Test 6e: Verify pause with no in-flight operations returns immediately.
     */
    @Test
    void testPauseWithNoInFlightOperationsReturnsImmediately() throws Exception {
        // Given: No active operations
        assertEquals(0, tracker.getActiveCount());

        // When: Pause
        var startTime = System.currentTimeMillis();
        boolean result = tracker.pauseAndWait(5, TimeUnit.SECONDS);
        var duration = System.currentTimeMillis() - startTime;

        // Then: Should return immediately (< 100ms)
        assertTrue(result, "Pause should succeed");
        assertTrue(duration < 100, "Pause should return immediately, took " + duration + "ms");
        assertTrue(tracker.isPaused());

        // Cleanup
        tracker.resume();
    }

    // === Placeholder Tests (will implement after InFlightOperationTracker works) ===

    /**
     * Test 1: Verify health state tracking.
     *
     * <p>Validates that partition states are correctly initialized and tracked.
     */
    @Test
    void testHealthStateTracking() {
        // Given: Forest with 3 partitions
        var partitions = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var topology = createTestTopology(3, partitions.toArray(UUID[]::new));

        // Create test forest
        var testForest = createTestDistributedForest();
        ftForest = new FaultTolerantDistributedForest<>(
            testForest,
            faultHandler,
            recoveryLock,
            new DefaultParallelBalancer<>(BalanceConfiguration.defaultConfig()),
            mockGhostManager,
            topology,
            partitions.get(0),
            FaultConfiguration.defaultConfig(),
            tracker
        );

        // When: Start forest
        ftForest.start();

        // Then: All partitions should be in HEALTHY state
        var stats = ftForest.getStats();
        assertEquals(3, stats.totalPartitions(), "Should have 3 total partitions");
        assertEquals(3, stats.healthyPartitions(), "Should have 3 healthy partitions");
        assertEquals(0, stats.suspectedPartitions(), "Should have 0 suspected partitions");
        assertEquals(0, stats.failedPartitions(), "Should have 0 failed partitions");

        // Verify individual partition status
        for (var partitionId : partitions) {
            assertEquals(PartitionStatus.HEALTHY, ftForest.getPartitionStatus(partitionId),
                "Partition " + partitionId + " should be HEALTHY");
        }
    }

    @Disabled("Test 2: Will implement after InFlightOperationTracker")
    @Test
    void testBarrierTimeoutDetection() {
        fail("Not yet implemented");
    }

    @Disabled("Test 3: Will implement after InFlightOperationTracker")
    @Test
    void testGhostSyncFailureDetection() {
        fail("Not yet implemented");
    }

    /**
     * Test 4: Verify quorum calculation.
     *
     * <p>Validates that quorum is correctly calculated as majority (> 50%).
     */
    @Test
    void testQuorumCalculation() {
        // Test cases: (total, active, expectedQuorum)
        var testCases = List.of(
            new Object[]{3, 2, true},   // 2/3 = 66% > 50%
            new Object[]{3, 1, false},  // 1/3 = 33% <= 50%
            new Object[]{5, 3, true},   // 3/5 = 60% > 50%
            new Object[]{5, 2, false},  // 2/5 = 40% <= 50%
            new Object[]{4, 3, true},   // 3/4 = 75% > 50%
            new Object[]{4, 2, false}   // 2/4 = 50% not > 50%
        );

        for (var testCase : testCases) {
            int total = (int) testCase[0];
            int active = (int) testCase[1];
            boolean expectedQuorum = (boolean) testCase[2];

            boolean actualQuorum = recoveryLock.hasQuorum(active, total);

            assertEquals(expectedQuorum, actualQuorum,
                String.format("Quorum check failed for %d/%d partitions", active, total));
        }
    }

    /**
     * Test 5: Verify recovery is blocked when quorum is lost.
     *
     * <p>Validates CRITICAL 3 fix: quorum check is atomic with recovery trigger.
     */
    @Test
    void testRecoveryBlockedWhenQuorumLost() throws Exception {
        // Given: 5 partitions (quorum requires 3 healthy)
        var partitions = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) {
            partitions.add(UUID.randomUUID());
        }
        var topology = createTestTopology(5, partitions.toArray(UUID[]::new));

        var testForest = createTestDistributedForest();
        ftForest = new FaultTolerantDistributedForest<>(
            testForest,
            faultHandler,
            recoveryLock,
            new DefaultParallelBalancer<>(BalanceConfiguration.defaultConfig()),
            mockGhostManager,
            topology,
            partitions.get(0),
            FaultConfiguration.defaultConfig(),
            tracker
        );

        ftForest.start();

        // Track recovery attempts
        var recoveriesTriggered = new AtomicInteger(0);
        ftForest.setRecoveryCallback(id -> recoveriesTriggered.incrementAndGet());

        // Initial state: all 5 healthy, has quorum
        assertTrue(ftForest.hasQuorum(), "Should have quorum with 5/5 healthy");

        // When: Fail 3 partitions (leaving only 2 healthy - quorum lost)
        for (int i = 0; i < 3; i++) {
            faultHandler.reportBarrierTimeout(partitions.get(i));
            faultHandler.reportBarrierTimeout(partitions.get(i));  // SUSPECTED -> FAILED
        }

        // Allow events to propagate
        Thread.sleep(200);

        // Then: Quorum should be lost
        assertFalse(ftForest.hasQuorum(), "Should have lost quorum with 2/5 healthy");

        // Recovery should have been blocked for third failure due to quorum loss
        // First two failures had quorum, third did not
        var stats = ftForest.getStats();
        assertTrue(stats.failedPartitions() >= 3, "Should have at least 3 failed partitions");

        // Verify that at least one recovery was blocked
        assertTrue(recoveriesTriggered.get() < 3,
            "Third recovery should have been blocked due to quorum loss");
    }

    /**
     * Test 7: Verify atomic recovery lock acquisition for same partition.
     *
     * <p>This test validates CRITICAL 1 fix: RecoveryCoordinatorLock.acquireRecoveryLock()
     * is synchronized to prevent race conditions where two threads could acquire lock
     * for the same partition simultaneously.
     */
    @Test
    void testConcurrentRecoveryLockAcquisition() throws Exception {
        // Given: Lock with max 2 concurrent recoveries
        var lock = new RecoveryCoordinatorLock(2);
        var partitionId = UUID.randomUUID();

        // Create topology with 5 total, 4 active (has quorum)
        var topology = createTestTopology(5, partitionId);

        // When: 10 threads try to acquire lock for SAME partition simultaneously
        var executor = Executors.newFixedThreadPool(10);
        var startLatch = new CountDownLatch(1);
        var successCount = new AtomicInteger(0);

        var futures = new ArrayList<Future<Boolean>>();
        for (int i = 0; i < 10; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();  // Synchronized start
                    if (lock.acquireRecoveryLock(partitionId, topology, 100, TimeUnit.MILLISECONDS)) {
                        successCount.incrementAndGet();
                        return true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }));
        }

        startLatch.countDown();  // Release all threads

        // Wait for all to complete
        for (var future : futures) {
            future.get(2, TimeUnit.SECONDS);
        }

        // Then: EXACTLY one thread should have succeeded (not 2, not 0)
        assertEquals(1, successCount.get(),
            "Only one thread should acquire lock for same partition");
        assertEquals(1, lock.activeRecoveryCount(),
            "Active recovery count should be 1");

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    // === Test Recovery Strategy ===

    /**
     * Test implementation of PartitionRecovery for testing recovery wiring.
     */
    private static class TestRecoveryStrategy implements PartitionRecovery {
        private final String name;
        private final FaultConfiguration config;
        private final boolean shouldSucceed;
        private final long delayMs;
        private final AtomicInteger attemptCount;

        TestRecoveryStrategy(String name, FaultConfiguration config, boolean shouldSucceed, long delayMs) {
            this.name = name;
            this.config = config;
            this.shouldSucceed = shouldSucceed;
            this.delayMs = delayMs;
            this.attemptCount = new AtomicInteger(0);
        }

        static TestRecoveryStrategy success(String name) {
            return new TestRecoveryStrategy(name, FaultConfiguration.defaultConfig(), true, 0);
        }

        static TestRecoveryStrategy failure(String name) {
            return new TestRecoveryStrategy(name, FaultConfiguration.defaultConfig(), false, 0);
        }

        static TestRecoveryStrategy delayed(String name, long delayMs) {
            return new TestRecoveryStrategy(name, FaultConfiguration.defaultConfig(), true, delayMs);
        }

        @Override
        public CompletableFuture<RecoveryResult> recover(UUID partitionId, FaultHandler handler) {
            var startTime = System.currentTimeMillis();
            var attempt = attemptCount.incrementAndGet();

            return CompletableFuture.supplyAsync(() -> {
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return RecoveryResult.failure(
                            partitionId,
                            System.currentTimeMillis() - startTime,
                            name,
                            attempt,
                            "Recovery interrupted",
                            e
                        );
                    }
                }

                var duration = System.currentTimeMillis() - startTime;

                if (shouldSucceed) {
                    return RecoveryResult.success(partitionId, duration, name, attempt);
                } else {
                    return RecoveryResult.failure(
                        partitionId,
                        duration,
                        name,
                        attempt,
                        "Test recovery configured to fail",
                        null
                    );
                }
            });
        }

        @Override
        public boolean canRecover(UUID partitionId, FaultHandler handler) {
            var status = handler.checkHealth(partitionId);
            return status == PartitionStatus.FAILED || status == PartitionStatus.SUSPECTED;
        }

        @Override
        public String getStrategyName() {
            return name;
        }

        @Override
        public FaultConfiguration getConfiguration() {
            return config;
        }

        @Override
        public CompletableFuture<Boolean> initiateRecovery(UUID failedPartitionId) {
            // Support legacy method by calling recover() and converting result
            return recover(failedPartitionId, null)
                .thenApply(RecoveryResult::success)
                .exceptionally(ex -> false);
        }

        int getAttemptCount() {
            return attemptCount.get();
        }
    }

    // === PHASE 1: Recovery Wiring Tests ===

    /**
     * Test: Successful recovery restores partition to HEALTHY.
     */
    @Test
    void testRecoverySuccessRestoresHealthy() throws Exception {
        // Given: Partition with registered recovery strategy
        var partitionId = UUID.randomUUID();
        var recovery = TestRecoveryStrategy.success("test-recovery");
        faultHandler.registerRecovery(partitionId, recovery);

        // Register partition
        faultHandler.markHealthy(partitionId);
        assertEquals(PartitionStatus.HEALTHY, faultHandler.checkHealth(partitionId));

        // When: Partition fails
        faultHandler.reportBarrierTimeout(partitionId);  // HEALTHY -> SUSPECTED
        faultHandler.reportBarrierTimeout(partitionId);  // SUSPECTED -> FAILED
        assertEquals(PartitionStatus.FAILED, faultHandler.checkHealth(partitionId));

        // Initiate recovery
        var recoveryFuture = faultHandler.initiateRecovery(partitionId);
        var success = recoveryFuture.get(2, TimeUnit.SECONDS);

        // Then: Recovery should succeed
        assertTrue(success, "Recovery should succeed");

        // Verify recovery was called
        assertEquals(1, recovery.getAttemptCount(), "Recovery should have been attempted once");

        // Notify recovery complete
        faultHandler.notifyRecoveryComplete(partitionId, true);

        // Verify partition is HEALTHY
        assertEquals(PartitionStatus.HEALTHY, faultHandler.checkHealth(partitionId));

        // Verify metrics
        var metrics = faultHandler.getMetrics(partitionId);
        assertNotNull(metrics);
        assertEquals(1, metrics.recoveryAttempts(), "Should have 1 recovery attempt");
        assertEquals(1, metrics.successfulRecoveries(), "Should have 1 successful recovery");
        assertEquals(0, metrics.failedRecoveries(), "Should have 0 failed recoveries");
    }

    /**
     * Test: Failed recovery leaves partition in FAILED state.
     */
    @Test
    void testRecoveryFailureLeavesPartitionFailed() throws Exception {
        // Given: Partition with recovery strategy that fails
        var partitionId = UUID.randomUUID();
        var recovery = TestRecoveryStrategy.failure("test-recovery-fail");
        faultHandler.registerRecovery(partitionId, recovery);

        // Register partition
        faultHandler.markHealthy(partitionId);
        assertEquals(PartitionStatus.HEALTHY, faultHandler.checkHealth(partitionId));

        // When: Partition fails
        faultHandler.reportBarrierTimeout(partitionId);  // HEALTHY -> SUSPECTED
        faultHandler.reportBarrierTimeout(partitionId);  // SUSPECTED -> FAILED
        assertEquals(PartitionStatus.FAILED, faultHandler.checkHealth(partitionId));

        // Initiate recovery
        var recoveryFuture = faultHandler.initiateRecovery(partitionId);
        var success = recoveryFuture.get(2, TimeUnit.SECONDS);

        // Then: Recovery should fail
        assertFalse(success, "Recovery should fail");

        // Verify recovery was attempted
        assertEquals(1, recovery.getAttemptCount(), "Recovery should have been attempted once");

        // Notify recovery failed
        faultHandler.notifyRecoveryComplete(partitionId, false);

        // Verify partition remains FAILED
        assertEquals(PartitionStatus.FAILED, faultHandler.checkHealth(partitionId));

        // Verify metrics
        var metrics = faultHandler.getMetrics(partitionId);
        assertNotNull(metrics);
        assertEquals(1, metrics.recoveryAttempts(), "Should have 1 recovery attempt");
        assertEquals(0, metrics.successfulRecoveries(), "Should have 0 successful recoveries");
        assertEquals(1, metrics.failedRecoveries(), "Should have 1 failed recovery");
    }

    /**
     * Test: Recovery timeout handled gracefully without crashes.
     */
    @Test
    void testRecoveryTimeoutHandledGracefully() throws Exception {
        // Given: Partition with slow recovery strategy
        var partitionId = UUID.randomUUID();
        var recovery = TestRecoveryStrategy.delayed("test-recovery-slow", 5000); // 5 second delay
        faultHandler.registerRecovery(partitionId, recovery);

        // Register partition
        faultHandler.markHealthy(partitionId);

        // When: Partition fails
        faultHandler.reportBarrierTimeout(partitionId);  // HEALTHY -> SUSPECTED
        faultHandler.reportBarrierTimeout(partitionId);  // SUSPECTED -> FAILED
        assertEquals(PartitionStatus.FAILED, faultHandler.checkHealth(partitionId));

        // Initiate recovery (will timeout in background)
        var recoveryFuture = faultHandler.initiateRecovery(partitionId);

        // Then: Should handle timeout gracefully
        // Don't wait for completion, just verify no crash
        Thread.sleep(100); // Brief wait to ensure recovery started

        // Verify recovery was initiated
        assertTrue(recovery.getAttemptCount() > 0, "Recovery should have been initiated");

        // Verify partition state is consistent (still FAILED while recovery in progress)
        var currentStatus = faultHandler.checkHealth(partitionId);
        assertNotNull(currentStatus, "Partition status should not be null");
        assertEquals(PartitionStatus.FAILED, currentStatus, "Partition should remain FAILED during recovery");

        // Verify metrics are consistent
        var metrics = faultHandler.getMetrics(partitionId);
        assertNotNull(metrics);
        assertEquals(1, metrics.recoveryAttempts(), "Should have 1 recovery attempt");
    }

    // === Helper Methods ===

    /**
     * Create test topology with specified partition count and active partitions.
     */
    private PartitionTopology createTestTopology(int totalPartitions, UUID... activePartitionIds) {
        var topology = new InMemoryPartitionTopology();

        for (int i = 0; i < totalPartitions; i++) {
            UUID partitionId = i < activePartitionIds.length ? activePartitionIds[i] : UUID.randomUUID();

            // Register the partition
            topology.register(partitionId, i);
        }

        return topology;
    }

    /**
     * Create minimal test distributed forest with mocks.
     */
    private ParallelBalancer.DistributedForest<MortonKey, UUIDEntityID, byte[]> createTestDistributedForest() {
        // Note: getCurrentPartitionId() may return UUID or Integer depending on PartitionRegistry
        // For now, just set up getPartitionCount() which is definitely needed
        org.mockito.Mockito.when(mockRegistry.getPartitionCount()).thenReturn(3);

        return new ParallelBalancer.DistributedForest<MortonKey, UUIDEntityID, byte[]>() {
            @Override
            public Forest<MortonKey, UUIDEntityID, byte[]> getLocalForest() {
                return mockForest;
            }

            @Override
            public DistributedGhostManager<MortonKey, UUIDEntityID, byte[]> getGhostManager() {
                return mockGhostManager;
            }

            @Override
            public ParallelBalancer.PartitionRegistry getPartitionRegistry() {
                return mockRegistry;
            }
        };
    }

    @Disabled("Failover coordinator not yet implemented")
    @Test
    void testFailoverToBackupCoordinator() {
        fail("Test 8 disabled per audit recommendation");
    }

    /**
     * Test 9: Verify stats and metrics collection.
     *
     * <p>Validates HIGH 5 fix: getStats() computes real partition counts from live state.
     */
    @Test
    void testStatsAndMetricsCollection() throws Exception {
        // Given: Forest with 3 partitions
        var partitions = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var topology = createTestTopology(3, partitions.toArray(UUID[]::new));

        var testForest = createTestDistributedForest();
        ftForest = new FaultTolerantDistributedForest<>(
            testForest,
            faultHandler,
            recoveryLock,
            new DefaultParallelBalancer<>(BalanceConfiguration.defaultConfig()),
            mockGhostManager,
            topology,
            partitions.get(0),
            FaultConfiguration.defaultConfig(),
            tracker
        );

        ftForest.start();

        // When: Check initial stats
        var initialStats = ftForest.getStats();

        // Then: Stats should show accurate counts
        assertEquals(3, initialStats.totalPartitions(), "Should have 3 total partitions");
        assertEquals(3, initialStats.healthyPartitions(), "Should have 3 healthy partitions");
        assertEquals(0, initialStats.suspectedPartitions(), "Should have 0 suspected");
        assertEquals(0, initialStats.failedPartitions(), "Should have 0 failed");

        // When: Fail one partition
        faultHandler.reportBarrierTimeout(partitions.get(0));  // HEALTHY -> SUSPECTED
        Thread.sleep(50);

        var afterSuspectedStats = ftForest.getStats();

        // Then: Stats should reflect the change
        assertEquals(3, afterSuspectedStats.totalPartitions());
        assertEquals(2, afterSuspectedStats.healthyPartitions(), "Should have 2 healthy");
        assertEquals(1, afterSuspectedStats.suspectedPartitions(), "Should have 1 suspected");
        assertEquals(0, afterSuspectedStats.failedPartitions());

        // When: Confirm failure
        faultHandler.reportBarrierTimeout(partitions.get(0));  // SUSPECTED -> FAILED
        Thread.sleep(50);

        var afterFailedStats = ftForest.getStats();

        // Then: Stats should show failed partition
        assertEquals(3, afterFailedStats.totalPartitions());
        assertEquals(2, afterFailedStats.healthyPartitions());
        assertEquals(0, afterFailedStats.suspectedPartitions());
        assertEquals(1, afterFailedStats.failedPartitions(), "Should have 1 failed");

        // Verify counter metrics
        assertTrue(afterFailedStats.totalFailuresDetected() > 0,
            "Should have detected at least one failure");
    }

    /**
     * Test 10: Verify integration with DefaultParallelBalancer.
     *
     * <p>Validates CRITICAL 4 fix: DefaultParallelBalancer integrates with
     * InFlightOperationTracker to respect pause/resume operations.
     */
    @Test
    void testIntegrationWithDefaultParallelBalancer() throws Exception {
        // Given: Shared tracker and real balancer
        var sharedTracker = new InFlightOperationTracker();
        var balancer = new DefaultParallelBalancer<MortonKey, UUIDEntityID, byte[]>(
            BalanceConfiguration.defaultConfig(),
            sharedTracker
        );

        // Create test distributed forest
        var testForest = createTestDistributedForest();

        // When: Start a balance operation in background
        var balanceStarted = new CountDownLatch(1);
        var balanceFuture = CompletableFuture.runAsync(() -> {
            balanceStarted.countDown();
            balancer.balance(testForest);
        });

        // Wait for balance to start
        assertTrue(balanceStarted.await(1, TimeUnit.SECONDS), "Balance should start");
        Thread.sleep(50);  // Let balance get into operation

        // Then: Verify balancer is not paused initially
        assertFalse(balancer.isPaused(), "Balancer should not be paused initially");

        // When: Trigger pause (mimics recovery mode)
        sharedTracker.pauseAndWait(2, TimeUnit.SECONDS);

        // Then: Verify balancer shows paused
        assertTrue(balancer.isPaused(), "Balancer should be paused");

        // Wait for balance to complete
        balanceFuture.get(5, TimeUnit.SECONDS);

        // When: Try to start new balance while paused
        var result = balancer.balance(testForest);

        // Then: Balance should be skipped
        assertTrue(result.successful(), "Skipped balance should report success");
        assertEquals(0, result.refinementsApplied(),
            "Skipped balance should have 0 refinements");

        // When: Resume operations
        sharedTracker.resume();

        // Then: Balancer should not be paused
        assertFalse(balancer.isPaused(), "Balancer should not be paused after resume");

        // Verify balance can run again
        var result2 = balancer.balance(testForest);
        assertTrue(result2.successful(), "Balance should succeed after resume");
    }
}
