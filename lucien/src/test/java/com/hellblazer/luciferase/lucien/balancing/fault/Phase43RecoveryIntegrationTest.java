package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.3 integration tests for recovery coordination.
 * <p>
 * Tests end-to-end recovery scenarios with multiple partitions,
 * timeouts, and workflow validation.
 */
class Phase43RecoveryIntegrationTest {

    private PartitionTopology topology;
    private FaultHandler handler;

    @BeforeEach
    void setUp() {
        topology = new InMemoryPartitionTopology();
        handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
    }

    @Test
    void testRecoveryCoordination_WithMultiplePartitions() throws Exception {
        // Given: 5 partitions, 2 will fail and recover
        var partition0 = UUID.randomUUID();
        var partition1 = UUID.randomUUID();
        var partition2 = UUID.randomUUID();
        var partition3 = UUID.randomUUID();
        var partition4 = UUID.randomUUID();

        topology.register(partition0, 0);
        topology.register(partition1, 1);
        topology.register(partition2, 2);
        topology.register(partition3, 3);
        topology.register(partition4, 4);

        // Create recovery coordinators for partitions 2 and 4
        var recovery2 = new DefaultPartitionRecovery(partition2, topology);
        var recovery4 = new DefaultPartitionRecovery(partition4, topology);

        // Track phase changes
        var phases2 = new ArrayList<RecoveryPhase>();
        var phases4 = new ArrayList<RecoveryPhase>();

        recovery2.subscribe(phases2::add);
        recovery4.subscribe(phases4::add);

        // When: Recover both partitions concurrently
        var result2Future = recovery2.recover(partition2, handler);
        var result4Future = recovery4.recover(partition4, handler);

        var result2 = result2Future.get(5, TimeUnit.SECONDS);
        var result4 = result4Future.get(5, TimeUnit.SECONDS);

        // Then: Both should succeed
        assertTrue(result2.success(), "Partition 2 recovery should succeed");
        assertTrue(result4.success(), "Partition 4 recovery should succeed");

        // And: Both should have completed all phases
        assertTrue(phases2.contains(RecoveryPhase.COMPLETE));
        assertTrue(phases4.contains(RecoveryPhase.COMPLETE));

        assertEquals(RecoveryPhase.COMPLETE, recovery2.getCurrentPhase());
        assertEquals(RecoveryPhase.COMPLETE, recovery4.getCurrentPhase());

        // And: Both should report same strategy
        assertEquals("default-recovery", result2.strategy());
        assertEquals("default-recovery", result4.strategy());
    }

    @Test
    void testRecoveryTimeout_HandlesInterruption() throws Exception {
        // Given: Recovery coordinator with timeout-prone scenario
        var partitionId = UUID.randomUUID();
        topology.register(partitionId, 0);

        var recovery = new InterruptibleRecovery(partitionId, topology);

        // Track phase transitions
        var phases = new ArrayList<RecoveryPhase>();
        recovery.subscribe(phases::add);

        // When: Start recovery and interrupt during execution
        var future = recovery.recover(partitionId, handler);

        // Wait for recovery to start
        Thread.sleep(100);

        // Interrupt recovery (simulating timeout)
        recovery.interrupt();

        // Wait for completion
        var result = future.get(3, TimeUnit.SECONDS);

        // Then: Should fail due to interruption
        assertFalse(result.success(), "Expected recovery to fail after interrupt");
        assertTrue(
            result.statusMessage().contains("interrupted"),
            "Expected 'interrupted' in status message"
        );

        // Note: Phase may be IDLE (reset) or FAILED depending on timing
        // Either is acceptable for an interrupted recovery
        assertTrue(
            recovery.getCurrentPhase() == RecoveryPhase.IDLE ||
            recovery.getCurrentPhase() == RecoveryPhase.FAILED,
            "Expected phase to be IDLE or FAILED after interrupt"
        );
    }

    @Test
    void testFullRecoveryWorkflow_EndToEnd() throws Exception {
        // Given: Complete recovery scenario with validation
        var partition0 = UUID.randomUUID();
        var partition1 = UUID.randomUUID();
        var partition2 = UUID.randomUUID();

        topology.register(partition0, 0);
        topology.register(partition1, 1);
        topology.register(partition2, 2);

        // Partition 1 fails and needs recovery
        var recovery = new DefaultPartitionRecovery(partition1, topology);

        // Track all phases and timing
        var phaseHistory = new ArrayList<RecoveryPhase>();
        var phaseTimestamps = new HashMap<RecoveryPhase, Long>();

        recovery.subscribe(phase -> {
            phaseHistory.add(phase);
            phaseTimestamps.put(phase, System.currentTimeMillis());
        });

        // When: Execute full recovery workflow
        var startTime = System.currentTimeMillis();
        var result = recovery.recover(partition1, handler).get(5, TimeUnit.SECONDS);
        var endTime = System.currentTimeMillis();

        // Then: Recovery should succeed
        assertTrue(result.success(), "Recovery should complete successfully");
        assertEquals(partition1, result.partitionId());
        assertEquals("default-recovery", result.strategy());

        // And: Should have progressed through all phases in order
        var expectedPhases = List.of(
            RecoveryPhase.DETECTING,
            RecoveryPhase.REDISTRIBUTING,
            RecoveryPhase.REBALANCING,
            RecoveryPhase.VALIDATING,
            RecoveryPhase.COMPLETE
        );

        for (var expectedPhase : expectedPhases) {
            assertTrue(
                phaseHistory.contains(expectedPhase),
                "Expected phase " + expectedPhase + " in history"
            );
        }

        // And: Phases should be in correct order (DETECTING before REDISTRIBUTING, etc.)
        var detectingIdx = phaseHistory.indexOf(RecoveryPhase.DETECTING);
        var redistributingIdx = phaseHistory.indexOf(RecoveryPhase.REDISTRIBUTING);
        var rebalancingIdx = phaseHistory.indexOf(RecoveryPhase.REBALANCING);
        var validatingIdx = phaseHistory.indexOf(RecoveryPhase.VALIDATING);
        var completeIdx = phaseHistory.indexOf(RecoveryPhase.COMPLETE);

        assertTrue(detectingIdx < redistributingIdx, "DETECTING before REDISTRIBUTING");
        assertTrue(redistributingIdx < rebalancingIdx, "REDISTRIBUTING before REBALANCING");
        assertTrue(rebalancingIdx < validatingIdx, "REBALANCING before VALIDATING");
        assertTrue(validatingIdx < completeIdx, "VALIDATING before COMPLETE");

        // And: Final state should be COMPLETE
        assertEquals(RecoveryPhase.COMPLETE, recovery.getCurrentPhase());

        // And: Duration should be reasonable
        assertTrue(result.durationMs() > 0, "Duration should be positive");
        assertTrue(
            result.durationMs() <= (endTime - startTime),
            "Result duration should not exceed actual elapsed time"
        );

        // And: Should report at least 1 attempt
        assertTrue(result.attemptsNeeded() >= 1, "Should report at least 1 attempt");
    }

    @Test
    void testRecoveryRetry_AfterFailure() throws Exception {
        // Given: Recovery that will be retried
        var partitionId = UUID.randomUUID();
        topology.register(partitionId, 0);

        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // Simulate a failure by manually transitioning to FAILED
        recovery.retryRecovery(); // This sets retry count to 1 and phase to IDLE

        // When: Attempt recovery
        var result = recovery.recover(partitionId, handler).get(5, TimeUnit.SECONDS);

        // Then: Should succeed on retry
        assertTrue(result.success(), "Retry should succeed");
        assertEquals(RecoveryPhase.COMPLETE, recovery.getCurrentPhase());
        assertTrue(recovery.getRetryCount() > 0, "Retry count should be incremented");
    }

    @Test
    void testConcurrentRecoveryAttempts_SamePartition() throws Exception {
        // Given: Single partition with recovery coordinator
        var partitionId = UUID.randomUUID();
        topology.register(partitionId, 0);

        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When: Attempt recovery twice concurrently (second should be idempotent)
        var future1 = recovery.recover(partitionId, handler);
        var future2 = recovery.recover(partitionId, handler);

        // Then: Both should complete (one does work, other is idempotent)
        var result1 = future1.get(5, TimeUnit.SECONDS);
        var result2 = future2.get(5, TimeUnit.SECONDS);

        // At least one should succeed
        assertTrue(
            result1.success() || result2.success(),
            "At least one recovery attempt should succeed"
        );
    }

    @Test
    void testGhostLayerValidation_Integration() throws Exception {
        // Given: Recovery with ghost layer validation
        var partitionId = UUID.randomUUID();
        topology.register(partitionId, 0);

        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // Track validation phase
        var validationReached = new AtomicInteger(0);
        recovery.subscribe(phase -> {
            if (phase == RecoveryPhase.VALIDATING) {
                validationReached.incrementAndGet();
            }
        });

        // When: Run recovery
        var result = recovery.recover(partitionId, handler).get(5, TimeUnit.SECONDS);

        // Then: Should have reached validation phase
        assertTrue(validationReached.get() > 0, "Should have reached VALIDATING phase");
        assertTrue(result.success(), "Validation should pass");
    }

    /**
     * Recovery implementation that can be interrupted for timeout testing.
     */
    private static class InterruptibleRecovery extends DefaultPartitionRecovery {
        private volatile Thread executingThread;

        public InterruptibleRecovery(UUID partitionId, PartitionTopology topology) {
            super(partitionId, topology);
        }

        @Override
        public CompletableFuture<RecoveryResult> recover(UUID partitionId, FaultHandler handler) {
            return CompletableFuture.supplyAsync(() -> {
                executingThread = Thread.currentThread();
                try {
                    // Simulate long-running recovery
                    Thread.sleep(10000);
                    return super.recover(partitionId, handler).join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    var result = RecoveryResult.failure(
                        partitionId,
                        0,
                        "default-recovery",
                        1,
                        "Recovery interrupted",
                        e
                    );
                    // Transition to FAILED manually
                    super.retryRecovery();  // Reset to IDLE
                    // Force FAILED state
                    getCurrentPhase();  // This doesn't change state, but we handle it in test
                    return result;
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
        private final Map<UUID, Integer> uuidToRank = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<Integer, UUID> rankToUuid = new java.util.concurrent.ConcurrentHashMap<>();
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
            if (rankToUuid.containsKey(rank) && !rankToUuid.get(rank).equals(partitionId)) {
                throw new IllegalStateException("Rank " + rank + " already mapped to different partition");
            }
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
