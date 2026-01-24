package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.3 tests for DefaultPartitionRecovery.
 * <p>
 * Tests recovery state machine, listener notifications, and coordination.
 */
class Phase43DefaultPartitionRecoveryTest {

    private UUID partitionId;
    private PartitionTopology topology;
    private FaultHandler mockHandler;

    @BeforeEach
    void setUp() {
        partitionId = UUID.randomUUID();
        topology = new InMemoryPartitionTopology();
        topology.register(partitionId, 0);

        // Create simple mock handler
        mockHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
    }

    @Test
    void testInitialPhase_IsIdle() {
        // Given: New recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // Then: Should start in IDLE phase
        assertEquals(RecoveryPhase.IDLE, recovery.getCurrentPhase());
        assertEquals(0, recovery.getRetryCount());
    }

    @Test
    void testSubscribe_ReceivesPhaseUpdates() throws Exception {
        // Given: Recovery with listener
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var phaseUpdates = new ArrayList<RecoveryPhase>();
        var latch = new CountDownLatch(5);  // Expect 5 phase transitions

        recovery.subscribe(phase -> {
            phaseUpdates.add(phase);
            latch.countDown();
        });

        // When: Start recovery
        var resultFuture = recovery.recover(partitionId, mockHandler);

        // Then: Wait for completion
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Expected 5 phase transitions");

        var result = resultFuture.get(1, TimeUnit.SECONDS);
        assertTrue(result.success(), "Expected recovery to succeed");

        // And: Should have received all phase updates
        assertTrue(phaseUpdates.contains(RecoveryPhase.DETECTING));
        assertTrue(phaseUpdates.contains(RecoveryPhase.REDISTRIBUTING));
        assertTrue(phaseUpdates.contains(RecoveryPhase.REBALANCING));
        assertTrue(phaseUpdates.contains(RecoveryPhase.VALIDATING));
        assertTrue(phaseUpdates.contains(RecoveryPhase.COMPLETE));
    }

    @Test
    void testRecover_TransitionsPhases() throws Exception {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When: Initiate recovery
        var result = recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: Should complete successfully
        assertTrue(result.success());
        assertEquals(RecoveryPhase.COMPLETE, recovery.getCurrentPhase());
        assertEquals(partitionId, result.partitionId());
        assertEquals("default-recovery", result.strategy());
    }

    @Test
    void testRecover_CompletesSuccessfully() throws Exception {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When: Execute recovery
        var result = recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: Should have successful result
        assertTrue(result.success());
        assertNull(result.failureReason());
        assertTrue(result.durationMs() >= 0);
        assertTrue(result.attemptsNeeded() >= 1);
        assertEquals("Recovery completed successfully", result.statusMessage());
    }

    @Test
    void testRecover_IdempotentWhenComplete() throws Exception {
        // Given: Completed recovery
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var firstResult = recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);
        assertTrue(firstResult.success());

        // When: Recover again
        var secondResult = recovery.recover(partitionId, mockHandler).get(1, TimeUnit.SECONDS);

        // Then: Should return success immediately (idempotent)
        assertTrue(secondResult.success());
        assertEquals(0, secondResult.durationMs(), "Expected immediate return");
    }

    @Test
    void testRecover_WrongPartitionId_ReturnsFail() throws Exception {
        // Given: Recovery for partition A
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var wrongPartitionId = UUID.randomUUID();

        // When: Try to recover partition B
        var result = recovery.recover(wrongPartitionId, mockHandler).get(1, TimeUnit.SECONDS);

        // Then: Should fail with mismatch error
        assertFalse(result.success());
        assertTrue(result.statusMessage().contains("Partition ID mismatch"));
        assertEquals(wrongPartitionId, result.partitionId());
    }

    @Test
    void testCanRecover_InitialState_ReturnsTrue() {
        // Given: New recovery in IDLE state
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When/Then: Should be recoverable
        assertTrue(recovery.canRecover(partitionId, mockHandler));
    }

    @Test
    void testCanRecover_WrongPartitionId_ReturnsFalse() {
        // Given: Recovery for specific partition
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var wrongId = UUID.randomUUID();

        // When/Then: Should not be recoverable with wrong ID
        assertFalse(recovery.canRecover(wrongId, mockHandler));
    }

    @Test
    void testCanRecover_NullParameters_ReturnsFalse() {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When/Then: Null parameters should return false
        assertFalse(recovery.canRecover(null, mockHandler));
        assertFalse(recovery.canRecover(partitionId, null));
        assertFalse(recovery.canRecover(null, null));
    }

    @Test
    void testSubscribe_NullListener_ThrowsException() {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When/Then: Null listener should throw
        assertThrows(
            IllegalArgumentException.class,
            () -> recovery.subscribe(null),
            "Expected exception for null listener"
        );
    }

    @Test
    void testSubscribe_MultipleListeners_AllNotified() throws Exception {
        // Given: Recovery with multiple listeners
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var listener1Updates = new AtomicInteger(0);
        var listener2Updates = new AtomicInteger(0);
        var listener3Updates = new AtomicInteger(0);

        recovery.subscribe(phase -> listener1Updates.incrementAndGet());
        recovery.subscribe(phase -> listener2Updates.incrementAndGet());
        recovery.subscribe(phase -> listener3Updates.incrementAndGet());

        // When: Run recovery
        recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: All listeners should be notified
        assertTrue(listener1Updates.get() > 0);
        assertTrue(listener2Updates.get() > 0);
        assertTrue(listener3Updates.get() > 0);
        // All should receive same number of updates
        assertEquals(listener1Updates.get(), listener2Updates.get());
        assertEquals(listener2Updates.get(), listener3Updates.get());
    }

    @Test
    void testGetStrategyName_ReturnsDefaultRecovery() {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When/Then: Should return strategy name
        assertEquals("default-recovery", recovery.getStrategyName());
    }

    @Test
    void testGetConfiguration_ReturnsConfiguration() {
        // Given: Recovery with custom config
        var config = FaultConfiguration.defaultConfig().withMaxRetries(5);
        var recovery = new DefaultPartitionRecovery(partitionId, topology, config);

        // When/Then: Should return same configuration
        assertEquals(config, recovery.getConfiguration());
        assertEquals(5, recovery.getConfiguration().maxRecoveryRetries());
    }

    @Test
    void testGetConfiguration_DefaultConfig() {
        // Given: Recovery with default config
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When/Then: Should return default configuration
        var config = recovery.getConfiguration();
        assertNotNull(config);
        assertEquals(FaultConfiguration.defaultConfig().maxRecoveryRetries(), config.maxRecoveryRetries());
    }

    @Test
    void testRetryRecovery_IncrementsCount() {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When: Retry recovery
        recovery.retryRecovery();

        // Then: Retry count should increment and phase should reset to IDLE
        assertEquals(1, recovery.getRetryCount());
        assertEquals(RecoveryPhase.IDLE, recovery.getCurrentPhase());

        // When: Retry again
        recovery.retryRecovery();

        // Then: Retry count should continue incrementing
        assertEquals(2, recovery.getRetryCount());
        assertEquals(RecoveryPhase.IDLE, recovery.getCurrentPhase());
    }

    @Test
    void testConstructor_NullPartitionId_ThrowsException() {
        // When/Then: Null partition ID should throw
        assertThrows(
            NullPointerException.class,
            () -> new DefaultPartitionRecovery(null, topology),
            "Expected exception for null partitionId"
        );
    }

    @Test
    void testConstructor_NullTopology_ThrowsException() {
        // When/Then: Null topology should throw
        assertThrows(
            NullPointerException.class,
            () -> new DefaultPartitionRecovery(partitionId, null),
            "Expected exception for null topology"
        );
    }

    @Test
    void testRecover_NullPartitionId_ThrowsException() {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When/Then: Null partition ID should throw
        assertThrows(
            IllegalArgumentException.class,
            () -> recovery.recover(null, mockHandler),
            "Expected exception for null partitionId"
        );
    }

    @Test
    void testRecover_NullHandler_ThrowsException() {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When/Then: Null handler should throw
        assertThrows(
            IllegalArgumentException.class,
            () -> recovery.recover(partitionId, null),
            "Expected exception for null handler"
        );
    }

    @Test
    void testGetStateTransitionTime_UpdatesOnPhaseChange() throws Exception {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var initialTime = recovery.getStateTransitionTime();

        // When: Start recovery (causes phase transitions)
        Thread.sleep(10);  // Ensure time advances
        recovery.recover(partitionId, mockHandler);
        Thread.sleep(100);  // Let some phases complete

        // Then: State transition time should have updated
        assertTrue(
            recovery.getStateTransitionTime() > initialTime,
            "Expected state transition time to update"
        );
    }

    /**
     * Simple in-memory partition topology for testing.
     */
    private static class InMemoryPartitionTopology implements PartitionTopology {
        private final java.util.Map<UUID, Integer> uuidToRank = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<Integer, UUID> rankToUuid = new java.util.concurrent.ConcurrentHashMap<>();
        private long version = 0;

        @Override
        public java.util.Optional<Integer> rankFor(UUID partitionId) {
            return java.util.Optional.ofNullable(uuidToRank.get(partitionId));
        }

        @Override
        public java.util.Optional<UUID> partitionFor(int rank) {
            return java.util.Optional.ofNullable(rankToUuid.get(rank));
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
        public java.util.Set<Integer> activeRanks() {
            return java.util.Set.copyOf(rankToUuid.keySet());
        }

        @Override
        public long topologyVersion() {
            return version;
        }
    }
}
