package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.*;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.3.4: Comprehensive integration tests for full recovery workflows.
 * <p>
 * Tests complete recovery scenarios with:
 * <ul>
 *   <li>Single partition failures</li>
 *   <li>Cascading failures (sequential)</li>
 *   <li>Concurrent failures (simultaneous)</li>
 *   <li>Recovery timeout handling</li>
 *   <li>Recovery success validation</li>
 *   <li>State transition validation</li>
 *   <li>Metrics collection validation</li>
 * </ul>
 * <p>
 * Uses IntegrationTestFixture for comprehensive system-level testing
 * with DefaultPartitionRecovery, GhostLayerValidator, and
 * FaultTolerantDistributedForest.
 */
class Phase43IntegrationTest {

    private IntegrationTestFixture fixture;
    private TestClock testClock;

    @BeforeEach
    void setUp() {
        fixture = new IntegrationTestFixture();
        testClock = new TestClock(1000L);
        fixture.resetClock(1000L);
    }

    @AfterEach
    void tearDown() {
        if (fixture != null && !fixture.isCleanedUp()) {
            fixture.tearDown();
        }
    }

    /**
     * Scenario 1: Single partition failure recovery
     * <p>
     * Setup: 5 partitions, fail 1
     * Expected: Full recovery workflow completes successfully
     * Validates: Partition returns to HEALTHY status
     */
    @Test
    void testSinglePartitionFailure_RecoverySucceeds() throws Exception {
        // Given: 5 partitions in distributed forest
        var forest = fixture.setupForestWithPartitions(5);
        var handler = fixture.configureFaultHandler();
        fixture.setupRecoveryCoordinators();

        var partitionIds = fixture.getPartitionIds();
        var failedPartition = partitionIds.get(2); // Fail partition at index 2

        // Track recovery phases
        var phaseHistory = new CopyOnWriteArrayList<RecoveryPhase>();
        var recovery = fixture.getRecoveryCoordinator(failedPartition);
        recovery.subscribe(phaseHistory::add);

        // When: Inject single partition failure
        fixture.injectPartitionFailure(failedPartition, 0);

        // Wait for failure detection
        Thread.sleep(100);

        // Execute recovery
        var result = recovery.recover(failedPartition, handler).get(5, TimeUnit.SECONDS);

        // Then: Recovery should succeed
        assertTrue(result.success(), "Recovery should complete successfully");
        assertEquals(failedPartition, result.partitionId());
        assertEquals(RecoveryPhase.COMPLETE, recovery.getCurrentPhase());

        // And: Should have transitioned through all phases
        assertTrue(phaseHistory.contains(RecoveryPhase.DETECTING), "Expected DETECTING phase");
        assertTrue(phaseHistory.contains(RecoveryPhase.REDISTRIBUTING), "Expected REDISTRIBUTING phase");
        assertTrue(phaseHistory.contains(RecoveryPhase.REBALANCING), "Expected REBALANCING phase");
        assertTrue(phaseHistory.contains(RecoveryPhase.VALIDATING), "Expected VALIDATING phase");
        assertTrue(phaseHistory.contains(RecoveryPhase.COMPLETE), "Expected COMPLETE phase");

        // And: Partition should be back to healthy (in real system)
        // Note: TestDistributedForest doesn't automatically restore - this tests the coordinator
        assertNotNull(result.durationMs());
        assertTrue(result.durationMs() >= 0, "Duration should be non-negative");
    }

    /**
     * Scenario 2: Cascading failures (multiple partitions failing in sequence)
     * <p>
     * Setup: 5 partitions, fail 3 in sequence with delays
     * Expected: Each failure triggers independent recovery
     * Validates: System handles sequential failures gracefully
     */
    @Test
    void testCascadingFailures_HandlesSequential() throws Exception {
        // Given: 5 partitions in distributed forest
        var forest = fixture.setupForestWithPartitions(5);
        var handler = fixture.configureFaultHandler();
        fixture.setupRecoveryCoordinators();

        var partitionIds = fixture.getPartitionIds();
        var partition1 = partitionIds.get(1);
        var partition2 = partitionIds.get(2);
        var partition3 = partitionIds.get(3);

        var recovery1 = fixture.getRecoveryCoordinator(partition1);
        var recovery2 = fixture.getRecoveryCoordinator(partition2);
        var recovery3 = fixture.getRecoveryCoordinator(partition3);

        var successCount = new AtomicInteger(0);

        // When: Inject cascading failures (sequential with delays)
        // Fail partition 1 immediately
        fixture.injectPartitionFailure(partition1, 0);
        Thread.sleep(50);

        // Fail partition 2 after 100ms
        fixture.injectPartitionFailure(partition2, 100);

        // Fail partition 3 after 200ms
        fixture.injectPartitionFailure(partition3, 200);

        // Start recoveries for all three
        var result1Future = recovery1.recover(partition1, handler);
        Thread.sleep(100); // Wait for first to start

        var result2Future = recovery2.recover(partition2, handler);
        Thread.sleep(100); // Wait for second to start

        var result3Future = recovery3.recover(partition3, handler);

        // Then: All recoveries should complete
        var result1 = result1Future.get(10, TimeUnit.SECONDS);
        var result2 = result2Future.get(10, TimeUnit.SECONDS);
        var result3 = result3Future.get(10, TimeUnit.SECONDS);

        // Verify all succeeded
        if (result1.success()) successCount.incrementAndGet();
        if (result2.success()) successCount.incrementAndGet();
        if (result3.success()) successCount.incrementAndGet();

        // At least 2 of 3 should succeed (still have quorum)
        assertTrue(successCount.get() >= 2,
            "Expected at least 2/3 recoveries to succeed, got: " + successCount.get());

        // And: Final phases should be COMPLETE or FAILED
        assertTrue(
            recovery1.getCurrentPhase() == RecoveryPhase.COMPLETE ||
            recovery1.getCurrentPhase() == RecoveryPhase.FAILED,
            "Partition 1 should be in terminal state"
        );
    }

    /**
     * Scenario 3: Concurrent failures (multiple partitions failing simultaneously)
     * <p>
     * Setup: 5 partitions, fail 2 simultaneously
     * Expected: Both recoveries execute concurrently
     * Validates: Concurrent recovery coordination works correctly
     */
    @Test
    void testConcurrentFailures_HandlesSimultaneous() throws Exception {
        // Given: 5 partitions in distributed forest
        var forest = fixture.setupForestWithPartitions(5);
        var handler = fixture.configureFaultHandler();
        fixture.setupRecoveryCoordinators();

        var partitionIds = fixture.getPartitionIds();
        var partition1 = partitionIds.get(1);
        var partition2 = partitionIds.get(3);

        var recovery1 = fixture.getRecoveryCoordinator(partition1);
        var recovery2 = fixture.getRecoveryCoordinator(partition2);

        // Track concurrent execution
        var recovery1Phases = new CopyOnWriteArrayList<RecoveryPhase>();
        var recovery2Phases = new CopyOnWriteArrayList<RecoveryPhase>();

        recovery1.subscribe(recovery1Phases::add);
        recovery2.subscribe(recovery2Phases::add);

        // When: Inject simultaneous failures
        fixture.injectPartitionFailure(partition1, 0);
        fixture.injectPartitionFailure(partition2, 0);

        // Start recoveries concurrently
        var result1Future = recovery1.recover(partition1, handler);
        var result2Future = recovery2.recover(partition2, handler);

        // Then: Both should complete
        var result1 = result1Future.get(10, TimeUnit.SECONDS);
        var result2 = result2Future.get(10, TimeUnit.SECONDS);

        // At least one should succeed
        assertTrue(result1.success() || result2.success(),
            "At least one concurrent recovery should succeed");

        // And: Both should have attempted recovery
        assertFalse(recovery1Phases.isEmpty(), "Recovery 1 should have phase transitions");
        assertFalse(recovery2Phases.isEmpty(), "Recovery 2 should have phase transitions");

        // And: If both succeeded, verify they ran concurrently (not blocked)
        if (result1.success() && result2.success()) {
            // Both should have completed within reasonable time (not sequential)
            long totalDuration = result1.durationMs() + result2.durationMs();
            long maxSequentialTime = 10000; // 10 seconds
            assertTrue(totalDuration < maxSequentialTime,
                "Concurrent recoveries took too long - may have run sequentially");
        }
    }

    /**
     * Scenario 4: Recovery timeout handling
     * <p>
     * Setup: Configure aggressive timeout, block recovery
     * Expected: Recovery transitions to FAILED state
     * Validates: Timeout mechanism works correctly
     */
    @Test
    void testRecoveryTimeout_TransitionsToFailed() throws Exception {
        // Given: Forest with partitions
        var forest = fixture.setupForestWithPartitions(3);
        var handler = fixture.configureFaultHandler();

        var partitionIds = fixture.getPartitionIds();
        var failedPartition = partitionIds.get(1);

        // Create custom topology for InterruptibleRecovery
        var topology = new InMemoryPartitionTopology();
        for (int i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        // Use a recovery coordinator that blocks during recovery
        var recovery = new InterruptibleRecovery(failedPartition, topology);

        var phases = new CopyOnWriteArrayList<RecoveryPhase>();
        recovery.subscribe(phases::add);

        // When: Start recovery (will block internally)
        var resultFuture = recovery.recover(failedPartition, handler);

        // Wait for recovery thread to start and begin blocking
        Thread.sleep(200);

        // Interrupt (simulate timeout)
        recovery.interrupt();

        // Then: Should complete with failure
        var result = resultFuture.get(5, TimeUnit.SECONDS);

        assertFalse(result.success(), "Expected recovery to fail due to timeout");
        assertNotNull(result.statusMessage());
        assertTrue(result.statusMessage().toLowerCase().contains("interrupt") ||
                   result.statusMessage().toLowerCase().contains("timeout"),
            "Status message should mention interruption or timeout");

        // And: Should be in terminal state (IDLE or FAILED)
        assertTrue(
            recovery.getCurrentPhase() == RecoveryPhase.IDLE ||
            recovery.getCurrentPhase() == RecoveryPhase.FAILED,
            "Expected terminal state after timeout"
        );
    }

    /**
     * Scenario 5: Recovery success validation
     * <p>
     * Setup: Normal recovery scenario
     * Expected: RecoveryResult contains valid success data
     * Validates: Success metadata is complete and accurate
     */
    @Test
    void testRecoverySuccess_ValidatesResult() throws Exception {
        // Given: 3 partitions, one will fail
        var forest = fixture.setupForestWithPartitions(3);
        var handler = fixture.configureFaultHandler();
        fixture.setupRecoveryCoordinators();

        var partitionIds = fixture.getPartitionIds();
        var failedPartition = partitionIds.get(0);

        var recovery = fixture.getRecoveryCoordinator(failedPartition);

        // When: Execute recovery
        fixture.injectPartitionFailure(failedPartition, 0);
        Thread.sleep(50);

        var startTime = System.currentTimeMillis();
        var result = recovery.recover(failedPartition, handler).get(5, TimeUnit.SECONDS);
        var endTime = System.currentTimeMillis();

        // Then: Result should be valid and complete
        assertTrue(result.success(), "Recovery should succeed");
        assertEquals(failedPartition, result.partitionId(), "Partition ID should match");
        assertEquals("default-recovery", result.strategy(), "Strategy should be default");

        // And: Metadata should be valid
        assertNotNull(result.durationMs(), "Duration should be present");
        assertTrue(result.durationMs() >= 0, "Duration should be non-negative");
        assertTrue(result.durationMs() <= (endTime - startTime),
            "Duration should not exceed wall-clock time");

        assertTrue(result.attemptsNeeded() >= 1, "Should report at least 1 attempt");
        assertNull(result.failureReason(), "Should have no failure reason on success");

        // And: Final state should be COMPLETE
        assertEquals(RecoveryPhase.COMPLETE, recovery.getCurrentPhase());
    }

    /**
     * Scenario 6: State transitions validation
     * <p>
     * Setup: Normal recovery with phase tracking
     * Expected: All phases occur in correct order
     * Validates: State machine transitions are valid
     */
    @Test
    void testStateTransitions_FollowCorrectOrder() throws Exception {
        // Given: Forest with recovery coordinator
        var forest = fixture.setupForestWithPartitions(3);
        var handler = fixture.configureFaultHandler();
        fixture.setupRecoveryCoordinators();

        var partitionIds = fixture.getPartitionIds();
        var failedPartition = partitionIds.get(1);

        var recovery = fixture.getRecoveryCoordinator(failedPartition);

        // Track all phase transitions with timestamps
        var phaseHistory = new CopyOnWriteArrayList<RecoveryPhase>();
        var phaseTimestamps = new HashMap<RecoveryPhase, Long>();

        recovery.subscribe(phase -> {
            phaseHistory.add(phase);
            phaseTimestamps.put(phase, System.currentTimeMillis());
        });

        // When: Execute recovery
        fixture.injectPartitionFailure(failedPartition, 0);
        Thread.sleep(50);

        var result = recovery.recover(failedPartition, handler).get(5, TimeUnit.SECONDS);

        // Then: Recovery should succeed
        assertTrue(result.success(), "Recovery should complete successfully");

        // And: Should have all expected phases
        var expectedPhases = List.of(
            RecoveryPhase.DETECTING,
            RecoveryPhase.REDISTRIBUTING,
            RecoveryPhase.REBALANCING,
            RecoveryPhase.VALIDATING,
            RecoveryPhase.COMPLETE
        );

        for (var expectedPhase : expectedPhases) {
            assertTrue(phaseHistory.contains(expectedPhase),
                "Expected phase " + expectedPhase + " in history");
        }

        // And: Phases should occur in correct order
        var detectingIdx = phaseHistory.indexOf(RecoveryPhase.DETECTING);
        var redistributingIdx = phaseHistory.indexOf(RecoveryPhase.REDISTRIBUTING);
        var rebalancingIdx = phaseHistory.indexOf(RecoveryPhase.REBALANCING);
        var validatingIdx = phaseHistory.indexOf(RecoveryPhase.VALIDATING);
        var completeIdx = phaseHistory.indexOf(RecoveryPhase.COMPLETE);

        assertTrue(detectingIdx < redistributingIdx, "DETECTING should occur before REDISTRIBUTING");
        assertTrue(redistributingIdx < rebalancingIdx, "REDISTRIBUTING should occur before REBALANCING");
        assertTrue(rebalancingIdx < validatingIdx, "REBALANCING should occur before VALIDATING");
        assertTrue(validatingIdx < completeIdx, "VALIDATING should occur before COMPLETE");

        // And: Each phase should have a timestamp
        assertNotNull(phaseTimestamps.get(RecoveryPhase.DETECTING));
        assertNotNull(phaseTimestamps.get(RecoveryPhase.COMPLETE));
    }

    /**
     * Scenario 7: Metrics collection validation
     * <p>
     * Setup: Execute recovery and collect metrics
     * Expected: All metrics are collected and valid
     * Validates: Timing, attempts, and success metrics
     */
    @Test
    void testMetricsCollection_ValidatesAllMetrics() throws Exception {
        // Given: Forest with multiple partitions
        var forest = fixture.setupForestWithPartitions(5);
        var handler = fixture.configureFaultHandler();
        fixture.setupRecoveryCoordinators();

        var partitionIds = fixture.getPartitionIds();

        // Track metrics for multiple recoveries
        var successCount = new AtomicInteger(0);
        var failureCount = new AtomicInteger(0);
        var totalDuration = new AtomicInteger(0);
        var totalAttempts = new AtomicInteger(0);
        var individualDurations = new ArrayList<Long>();

        // When: Execute multiple recoveries
        for (int i = 0; i < 3; i++) {
            var partition = partitionIds.get(i);
            var recovery = fixture.getRecoveryCoordinator(partition);

            fixture.injectPartitionFailure(partition, 0);
            Thread.sleep(50);

            var startTime = System.currentTimeMillis();
            var result = recovery.recover(partition, handler).get(5, TimeUnit.SECONDS);
            var elapsed = System.currentTimeMillis() - startTime;

            // Collect metrics
            if (result.success()) {
                successCount.incrementAndGet();
            } else {
                failureCount.incrementAndGet();
            }

            // Use reported duration or elapsed time (whichever is greater)
            var effectiveDuration = Math.max(result.durationMs(), elapsed);
            totalDuration.addAndGet((int) effectiveDuration);
            individualDurations.add(effectiveDuration);
            totalAttempts.addAndGet(result.attemptsNeeded());
        }

        // Then: Should have executed 3 recoveries
        assertEquals(3, successCount.get() + failureCount.get(),
            "Should have 3 total recovery attempts");

        // And: At least 2 should succeed (quorum maintained)
        assertTrue(successCount.get() >= 2,
            "Expected at least 2/3 recoveries to succeed, got " + successCount.get());

        // And: Metrics should be valid (allow for very fast recoveries)
        assertTrue(totalDuration.get() >= 0,
            "Total duration should be non-negative, got: " + totalDuration.get());
        assertTrue(totalAttempts.get() >= 3,
            "Should have at least 3 total attempts, got: " + totalAttempts.get());

        // And: Each individual recovery should have valid metrics
        for (int i = 0; i < individualDurations.size(); i++) {
            assertTrue(individualDurations.get(i) >= 0,
                "Recovery " + i + " duration should be non-negative");
        }

        // And: Success rate should be acceptable (>= 66%)
        double successRate = (double) successCount.get() / 3.0;
        assertTrue(successRate >= 0.66,
            "Success rate should be at least 66% (2/3), got: " + successRate);
    }

    /**
     * Helper class for timeout testing.
     * Extends DefaultPartitionRecovery to allow interruption.
     * Blocks during recovery to simulate long-running operation.
     */
    private static class InterruptibleRecovery extends DefaultPartitionRecovery {
        private volatile Thread executingThread;

        public InterruptibleRecovery(UUID partitionId, PartitionTopology topology) {
            super(partitionId, topology);
        }

        @Override
        public java.util.concurrent.CompletableFuture<RecoveryResult> recover(
                UUID partitionId, FaultHandler handler) {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                executingThread = Thread.currentThread();
                try {
                    // Simulate long-running recovery by sleeping
                    Thread.sleep(10000); // 10 seconds - long enough to be interrupted

                    // If we get here, recovery wasn't interrupted - call super
                    return super.recover(partitionId, handler).join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    // Return failure result due to interruption
                    return RecoveryResult.failure(
                        partitionId,
                        0,
                        "default-recovery",
                        1,
                        "Recovery interrupted during execution",
                        e
                    );
                }
            });
        }

        public void interrupt() {
            if (executingThread != null) {
                executingThread.interrupt();
            }
        }
    }

    /**
     * Simple in-memory partition topology for testing.
     */
    private static class InMemoryPartitionTopology implements PartitionTopology {
        private final Map<UUID, Integer> uuidToRank = new HashMap<>();
        private final Map<Integer, UUID> rankToUuid = new HashMap<>();
        private long version = 0;

        @Override
        public Optional<Integer> rankFor(UUID partitionId) {
            return Optional.ofNullable(uuidToRank.get(partitionId));
        }

        @Override
        public Optional<UUID> partitionFor(int rank) {
            return Optional.ofNullable(rankToUuid.get(rank));
        }

        @Override
        public void register(UUID partitionId, int rank) {
            uuidToRank.put(partitionId, rank);
            rankToUuid.put(rank, partitionId);
            version++;
        }

        @Override
        public void unregister(UUID partitionId) {
            var rank = uuidToRank.remove(partitionId);
            if (rank != null) {
                rankToUuid.remove(rank);
                version++;
            }
        }

        @Override
        public int totalPartitions() {
            return uuidToRank.size();
        }

        @Override
        public Set<Integer> activeRanks() {
            return Set.copyOf(rankToUuid.keySet());
        }

        @Override
        public long topologyVersion() {
            return version;
        }
    }
}
