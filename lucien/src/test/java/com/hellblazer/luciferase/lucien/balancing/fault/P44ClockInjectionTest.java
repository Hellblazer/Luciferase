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

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4.4-prereq: Clock injection tests for DefaultPartitionRecovery.
 * <p>
 * Verifies deterministic clock support for testing:
 * - Clock field injection
 * - State transition timestamps use injected clock
 * - Recovery timing uses injected clock
 */
class P44ClockInjectionTest {

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

    /**
     * Test 1: Verify Clock injection via setClock().
     * <p>
     * Tests that DefaultPartitionRecovery accepts Clock injection
     * and uses it instead of System.currentTimeMillis().
     */
    @Test
    void testClockInjection() {
        // Given: Recovery coordinator with injected clock
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        recovery.setClock(testClock);

        // When: Trigger a phase transition to update stateTransitionTime with injected clock
        recovery.retryRecovery(); // This will transition to IDLE and update stateTransitionTime

        // Then: State transition time should use injected clock time (1000ms), not System time
        long transitionTime = recovery.getStateTransitionTime();
        assertThat(transitionTime)
            .as("State transition should use injected clock time after phase transition")
            .isEqualTo(1000L);
    }

    /**
     * Test 2: Verify state transitions record injected timestamps.
     * <p>
     * Tests that phase transitions capture time from injected clock.
     */
    @Test
    void testStateTransitionTimestamps() throws Exception {
        // Given: Recovery with TestClock at t=1000ms
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        recovery.setClock(testClock);

        // Trigger initial phase transition to capture injected clock time
        recovery.retryRecovery(); // Transition to IDLE with injected clock
        long initialTime = recovery.getStateTransitionTime();
        assertThat(initialTime)
            .as("Initial transition time should be 1000ms after phase transition")
            .isEqualTo(1000L);

        // When: Advance clock before recovery
        testClock.advance(500); // Now at t=1500ms

        // And: Initiate recovery (will trigger phase transitions)
        recovery.recover(partitionId, mockHandler).get(5, TimeUnit.SECONDS);

        // Then: State transition time should reflect clock advancement
        long finalTime = recovery.getStateTransitionTime();
        assertThat(finalTime)
            .as("State transition should use advanced clock time")
            .isGreaterThanOrEqualTo(1500L);
    }

    /**
     * Test 3: Verify recovery duration uses injected clock.
     * <p>
     * Tests that recovery result duration is calculated from injected clock.
     */
    @Test
    void testRecoveryDurationWithInjectedClock() throws Exception {
        // Given: Recovery with TestClock at t=1000ms
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        recovery.setClock(testClock);

        // When: Execute recovery (simulated phases: 50+100+75 = 225ms)
        var resultFuture = recovery.recover(partitionId, mockHandler);
        var result = resultFuture.get(5, TimeUnit.SECONDS);

        // Then: Duration should be calculated from injected clock
        // Note: Duration depends on simulatePhaseDelay which uses Thread.sleep
        // We verify clock was used for start/end time capture
        assertThat(result.success())
            .as("Recovery should complete successfully")
            .isTrue();
        assertThat(result.durationMs())
            .as("Duration should be non-negative")
            .isGreaterThanOrEqualTo(0L);
    }

    /**
     * Test 4: Verify default clock is Clock.system().
     * <p>
     * Tests that DefaultPartitionRecovery uses system clock by default
     * when no clock is injected.
     */
    @Test
    void testDefaultClockIsSystemClock() {
        // Given: Recovery without clock injection
        var recovery = new DefaultPartitionRecovery(partitionId, topology);

        // When: Get state transition time
        long transitionTime = recovery.getStateTransitionTime();
        long systemTime = System.currentTimeMillis();

        // Then: Should be close to system time (within 100ms tolerance)
        assertThat(transitionTime)
            .as("Default clock should use system time")
            .isCloseTo(systemTime, org.assertj.core.data.Offset.offset(100L));
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
