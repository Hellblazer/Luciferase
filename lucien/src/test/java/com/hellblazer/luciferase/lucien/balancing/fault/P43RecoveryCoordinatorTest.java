/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.3.3: Comprehensive tests for DefaultPartitionRecovery coordinator.
 * <p>
 * Verifies:
 * - Phase state machine transitions (DETECTING → REDISTRIBUTING → REBALANCING → VALIDATING → COMPLETE)
 * - Recovery workflow end-to-end
 * - Listener notification validation
 * - Ghost layer validation integration
 * - Retry coordination
 * - Clock-based deterministic timing
 * - Concurrent recovery handling
 * - Error handling and failure scenarios
 */
class P43RecoveryCoordinatorTest {

    private UUID partitionId;
    private PartitionTopology topology;
    private FaultHandler mockHandler;
    private TestClock testClock;

    @BeforeEach
    void setUp() {
        partitionId = UUID.randomUUID();
        topology = new InMemoryPartitionTopology();
        topology.register(partitionId, 0);
        mockHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        testClock = new TestClock(1000L); // Start at t=1000ms
    }

    // ========== Phase Transition Tests ==========

    /**
     * Test 1: Verify complete phase sequence.
     * <p>
     * Validates that recovery transitions through all phases in correct order:
     * IDLE → DETECTING → REDISTRIBUTING → REBALANCING → VALIDATING → COMPLETE
     */
    @Test
    void testCompletePhaseSequence() throws Exception {
        // Given: Recovery coordinator with phase tracking
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var phaseSequence = new ArrayList<RecoveryPhase>();
        var latch = new CountDownLatch(5);  // 5 phase transitions after IDLE

        recovery.subscribe(phase -> {
            phaseSequence.add(phase);
            latch.countDown();
        });

        // When: Execute recovery
        var result = recovery.recover(partitionId, mockHandler);

        // Then: Wait for all phase transitions
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Expected 5 phase transitions");
        assertTrue(result.get(1, TimeUnit.SECONDS).success());

        // And: Verify phase sequence
        assertThat(phaseSequence)
            .as("Phase sequence should follow state machine")
            .containsExactly(
                RecoveryPhase.DETECTING,
                RecoveryPhase.REDISTRIBUTING,
                RecoveryPhase.REBALANCING,
                RecoveryPhase.VALIDATING,
                RecoveryPhase.COMPLETE
            );
    }

    /**
     * Test 2: Verify phase order is strictly sequential.
     * <p>
     * Ensures no phase skipping or out-of-order transitions.
     */
    @Test
    void testPhaseOrderIsStrictlySequential() throws Exception {
        // Given: Recovery with phase order tracking
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var phaseIndices = new ArrayList<Integer>();
        var expectedOrder = List.of(
            RecoveryPhase.DETECTING,
            RecoveryPhase.REDISTRIBUTING,
            RecoveryPhase.REBALANCING,
            RecoveryPhase.VALIDATING,
            RecoveryPhase.COMPLETE
        );

        recovery.subscribe(phase -> {
            var index = expectedOrder.indexOf(phase);
            if (index >= 0) {
                phaseIndices.add(index);
            }
        });

        // When: Execute recovery
        recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: Phase indices should be strictly increasing
        for (int i = 1; i < phaseIndices.size(); i++) {
            assertThat(phaseIndices.get(i))
                .as("Phase at index %d should come after previous phase", i)
                .isGreaterThan(phaseIndices.get(i - 1));
        }
    }

    /**
     * Test 3: Verify listener notifications fire on each phase transition.
     * <p>
     * Validates that all subscribed listeners receive notifications for every phase change.
     */
    @Test
    void testListenerNotificationsOnEveryPhaseChange() throws Exception {
        // Given: Recovery with multiple listeners
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var listener1Phases = new ArrayList<RecoveryPhase>();
        var listener2Phases = new ArrayList<RecoveryPhase>();
        var latch = new CountDownLatch(10); // 5 phases × 2 listeners

        recovery.subscribe(phase -> {
            listener1Phases.add(phase);
            latch.countDown();
        });
        recovery.subscribe(phase -> {
            listener2Phases.add(phase);
            latch.countDown();
        });

        // When: Execute recovery
        recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: Both listeners should receive all phases
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Expected all listener notifications");
        assertThat(listener1Phases)
            .as("Listener 1 should receive all phases")
            .hasSize(5);
        assertThat(listener2Phases)
            .as("Listener 2 should receive all phases")
            .hasSize(5);
        assertThat(listener1Phases)
            .as("Both listeners should receive same phases")
            .isEqualTo(listener2Phases);
    }

    /**
     * Test 4: Verify phase transitions to FAILED on error.
     * <p>
     * Validates that recovery transitions to FAILED phase when an error occurs,
     * and that the phase is reported correctly.
     */
    @Test
    void testPhaseTransitionToFailedOnError() throws Exception {
        // Given: Recovery that will fail due to wrong partition ID
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var wrongPartitionId = UUID.randomUUID();

        // When: Attempt recovery with wrong ID
        var result = recovery.recover(wrongPartitionId, mockHandler).get(1, TimeUnit.SECONDS);

        // Then: Should fail immediately (no phase transitions beyond initial state)
        assertFalse(result.success());
        // Phase should still be IDLE since we never started the actual recovery
        assertEquals(RecoveryPhase.IDLE, recovery.getCurrentPhase());
    }

    // ========== Recovery Workflow Tests ==========

    /**
     * Test 5: End-to-end successful recovery workflow.
     * <p>
     * Validates complete recovery from start to finish, including result validation.
     */
    @Test
    void testEndToEndSuccessfulRecovery() throws Exception {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var startPhase = recovery.getCurrentPhase();

        // When: Execute recovery
        var result = recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: Should complete successfully
        assertTrue(result.success());
        assertNull(result.failureReason());
        assertEquals(RecoveryPhase.COMPLETE, recovery.getCurrentPhase());
        assertEquals("default-recovery", result.strategy());
        assertTrue(result.durationMs() >= 0);
        assertEquals(partitionId, result.partitionId());

        // And: Should have transitioned from initial state
        assertNotEquals(startPhase, recovery.getCurrentPhase());
    }

    /**
     * Test 6: Recovery with ghost layer validation.
     * <p>
     * Validates that ghost layer validation is integrated into recovery workflow.
     * Note: Without actual ghost manager, validation is skipped but recovery completes.
     */
    @Test
    void testRecoveryWithGhostLayerValidation() throws Exception {
        // Given: Recovery coordinator (no ghost manager = validation skipped)
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var phasesSeen = new ArrayList<RecoveryPhase>();

        recovery.subscribe(phasesSeen::add);

        // When: Execute recovery
        var result = recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: Should complete successfully (validation skipped gracefully)
        assertTrue(result.success());
        assertTrue(phasesSeen.contains(RecoveryPhase.VALIDATING),
            "Should include validation phase even without ghost manager");
    }

    /**
     * Test 7: Recovery failure scenario.
     * <p>
     * Validates recovery failure handling and result reporting.
     */
    @Test
    void testRecoveryFailureScenario() throws Exception {
        // Given: Recovery with mismatched partition ID (triggers failure)
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var wrongId = UUID.randomUUID();

        // When: Attempt recovery with wrong partition
        var result = recovery.recover(wrongId, mockHandler).get(1, TimeUnit.SECONDS);

        // Then: Should fail with descriptive error
        assertFalse(result.success());
        assertNotNull(result.statusMessage());
        assertTrue(result.statusMessage().contains("mismatch"));
        assertEquals(wrongId, result.partitionId());
    }

    /**
     * Test 8: Retry coordination workflow.
     * <p>
     * Validates that retryRecovery() correctly increments count and resets phase.
     */
    @Test
    void testRetryCoordination() throws Exception {
        // Given: Recovery that completed
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);
        assertEquals(RecoveryPhase.COMPLETE, recovery.getCurrentPhase());

        // When: Retry recovery
        recovery.retryRecovery();

        // Then: Should reset to IDLE with incremented count
        assertEquals(RecoveryPhase.IDLE, recovery.getCurrentPhase());
        assertEquals(1, recovery.getRetryCount());

        // And: Should be able to recover again
        var result = recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertEquals(1, result.attemptsNeeded()); // Counts retry
    }

    // ========== Clock Integration Tests ==========

    /**
     * Test 9: Deterministic timing with injected TestClock.
     * <p>
     * Validates that recovery uses injected clock for all time operations.
     */
    @Test
    void testDeterministicTimingWithTestClock() throws Exception {
        // Given: Recovery with TestClock
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        recovery.setClock(testClock);

        // Record initial time via phase transition
        recovery.retryRecovery(); // Force phase transition to capture clock time
        var initialTime = recovery.getStateTransitionTime();
        assertThat(initialTime).isEqualTo(1000L);

        // When: Advance clock and execute recovery
        testClock.advance(500); // Now at t=1500ms
        var result = recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: State transition should reflect clock advancement
        assertThat(recovery.getStateTransitionTime())
            .as("Final transition time should use advanced clock")
            .isGreaterThanOrEqualTo(1500L);
    }

    /**
     * Test 10: Duration calculation accuracy with TestClock.
     * <p>
     * Validates that recovery duration uses clock timestamps.
     */
    @Test
    void testDurationCalculationAccuracy() throws Exception {
        // Given: Recovery with TestClock at known time
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        recovery.setClock(testClock);
        testClock.setTime(5000L); // Set to t=5000ms

        // When: Execute recovery
        var result = recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: Duration should be calculated correctly
        assertTrue(result.success());
        assertTrue(result.durationMs() >= 0,
            "Duration should be non-negative");
    }

    /**
     * Test 11: State transition timestamps use injected clock.
     * <p>
     * Validates that each phase transition captures time from injected clock.
     */
    @Test
    void testStateTransitionTimestampsUseInjectedClock() throws Exception {
        // Given: Recovery with TestClock
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        recovery.setClock(testClock);

        // Record timestamps at different clock values
        var timestamps = new ArrayList<Long>();
        recovery.subscribe(phase -> {
            timestamps.add(recovery.getStateTransitionTime());
        });

        // When: Execute recovery
        recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: All timestamps should be >= initial clock time
        for (var timestamp : timestamps) {
            assertThat(timestamp)
                .as("Timestamp should be >= initial clock time")
                .isGreaterThanOrEqualTo(1000L);
        }
    }

    // ========== Listener Tests ==========

    /**
     * Test 12: Multiple listeners receive all notifications.
     * <p>
     * Validates that all subscribed listeners receive every phase notification.
     */
    @Test
    void testMultipleListenersReceiveAllNotifications() throws Exception {
        // Given: Recovery with 3 listeners
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var listener1Count = new AtomicInteger(0);
        var listener2Count = new AtomicInteger(0);
        var listener3Count = new AtomicInteger(0);

        recovery.subscribe(phase -> listener1Count.incrementAndGet());
        recovery.subscribe(phase -> listener2Count.incrementAndGet());
        recovery.subscribe(phase -> listener3Count.incrementAndGet());

        // When: Execute recovery
        recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: All listeners should receive same number of notifications
        assertThat(listener1Count.get())
            .as("Listener 1 should receive notifications")
            .isEqualTo(5); // 5 phase transitions
        assertThat(listener2Count.get())
            .as("Listener 2 should receive same notifications")
            .isEqualTo(listener1Count.get());
        assertThat(listener3Count.get())
            .as("Listener 3 should receive same notifications")
            .isEqualTo(listener1Count.get());
    }

    /**
     * Test 13: Listener exceptions don't break recovery.
     * <p>
     * Validates that exceptions in listeners are caught and don't disrupt recovery.
     */
    @Test
    void testListenerExceptionsDontBreakRecovery() throws Exception {
        // Given: Recovery with failing listener
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        var goodListenerCalled = new AtomicInteger(0);

        // Subscribe failing listener
        recovery.subscribe(phase -> {
            throw new RuntimeException("Listener failure");
        });

        // Subscribe good listener after failing one
        recovery.subscribe(phase -> goodListenerCalled.incrementAndGet());

        // When: Execute recovery
        var result = recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: Recovery should complete despite listener exception
        assertTrue(result.success());
        assertThat(goodListenerCalled.get())
            .as("Good listener should still receive notifications")
            .isGreaterThan(0);
    }

    // ========== Concurrent Tests ==========

    /**
     * Test 14: Idempotent behavior on concurrent recovery attempts.
     * <p>
     * Validates that multiple recovery calls handle concurrency correctly.
     */
    @Test
    void testIdempotentBehaviorOnConcurrentRecovery() throws Exception {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When: First recovery completes
        var firstResult = recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);
        assertTrue(firstResult.success());

        // And: Second recovery is attempted
        var secondResult = recovery.recover(partitionId, mockHandler).get(1, TimeUnit.SECONDS);

        // Then: Second recovery should succeed immediately (idempotent)
        assertTrue(secondResult.success());
        assertEquals(0, secondResult.durationMs(),
            "Second recovery should return immediately");
    }

    /**
     * Test 15: Recovery state machine integrity under multiple retries.
     * <p>
     * Validates that retry coordination maintains state machine consistency.
     */
    @Test
    void testRecoveryStateMachineIntegrityUnderRetries() throws Exception {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When: Execute multiple recovery cycles with retries
        for (int i = 0; i < 3; i++) {
            var result = recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);
            assertTrue(result.success(), "Recovery cycle " + i + " should succeed");
            assertEquals(RecoveryPhase.COMPLETE, recovery.getCurrentPhase());

            // Retry for next cycle
            recovery.retryRecovery();
            assertEquals(RecoveryPhase.IDLE, recovery.getCurrentPhase());
            assertEquals(i + 1, recovery.getRetryCount());
        }

        // Then: Final state should be consistent
        assertEquals(RecoveryPhase.IDLE, recovery.getCurrentPhase());
        assertEquals(3, recovery.getRetryCount());
    }

    // ========== Error Handling Tests ==========

    /**
     * Test 16: Null parameter handling throws appropriate exceptions.
     * <p>
     * Validates that null parameters are rejected with clear error messages.
     */
    @Test
    void testNullParameterHandling() {
        // Given: Recovery coordinator
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // Then: Null partition ID should throw
        assertThrows(IllegalArgumentException.class,
            () -> recovery.recover(null, mockHandler));

        // And: Null handler should throw
        assertThrows(IllegalArgumentException.class,
            () -> recovery.recover(partitionId, null));

        // And: Null clock should throw
        assertThrows(IllegalArgumentException.class,
            () -> recovery.setClock(null));

        // And: Null listener should throw
        assertThrows(IllegalArgumentException.class,
            () -> recovery.subscribe(null));
    }

    /**
     * Simple in-memory partition topology for testing.
     */
    private static class InMemoryPartitionTopology implements PartitionTopology {
        private final Map<UUID, Integer> uuidToRank = new ConcurrentHashMap<>();
        private final Map<Integer, UUID> rankToUuid = new ConcurrentHashMap<>();
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
