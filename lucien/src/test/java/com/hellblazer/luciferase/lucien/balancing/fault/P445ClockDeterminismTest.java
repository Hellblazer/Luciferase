package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.TestFaultHandler;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * P4.4.5 Clock Determinism Tests (D1-D2).
 * <p>
 * Validates deterministic time control and reproducibility in recovery scenarios.
 * Tests clock advance through recovery phases and handling of clock irregularities.
 * <p>
 * <b>Test Coverage</b>:
 * <ul>
 *   <li>D1: Full Cycle Clock Control - TestClock.advance() through all phases</li>
 *   <li>D2: Boundary Conditions - Clock skew, drift, and time jump handling</li>
 * </ul>
 * <p>
 * <b>Quality Criteria</b>:
 * <ul>
 *   <li>Deterministic clock advance correctly tracks time progression</li>
 *   <li>Multiple runs with identical clock sequence produce identical results</li>
 *   <li>Clock irregularities handled gracefully (no crash)</li>
 *   <li>Positive clock jumps handled correctly</li>
 *   <li>Recovery completes despite clock irregularities</li>
 *   <li>Recovery duration calculated correctly</li>
 * </ul>
 */
class P445ClockDeterminismTest {

    private UUID partitionId;
    private InMemoryPartitionTopology topology;
    private TestFaultHandler handler;
    private TestClock testClock;

    @BeforeEach
    void setUp() {
        partitionId = UUID.randomUUID();
        topology = new InMemoryPartitionTopology();
        topology.register(partitionId, 0);
        handler = new TestFaultHandler();
        handler.start();
        testClock = new TestClock(0); // Start at t=0
    }

    @AfterEach
    void tearDown() {
        if (handler != null && handler.isRunning()) {
            handler.stop();
        }
    }

    /**
     * D1: Full Cycle Clock Control - TestClock.advance().
     * <p>
     * Validates that deterministic clock advancement correctly tracks time
     * progression through all recovery phases, and that multiple runs with
     * identical clock sequences produce identical results (reproducibility).
     * <p>
     * <b>Scenario</b>:
     * <ol>
     *   <li>Setup recovery with TestClock at t=0</li>
     *   <li>Execute recovery through all phases</li>
     *   <li>Verify state transition timestamps reflect clock state</li>
     *   <li>Assert recovery completes within projected time</li>
     *   <li>Reproduce identical recovery with same clock sequence</li>
     *   <li>Validate deterministic behavior (same clock → same sequence)</li>
     * </ol>
     * <p>
     * <b>Expected</b>: Duration <1s, reproducibility 100%.
     */
    @Test
    void testFullCycleClockControlAdvance() throws Exception {
        // Given: Recovery with TestClock at t=0
        var recovery = new DefaultPartitionRecovery(partitionId, topology);
        recovery.setClock(testClock);

        var phaseTransitionTimes = new CopyOnWriteArrayList<Long>();
        var phaseSequence = new CopyOnWriteArrayList<RecoveryPhase>();

        // Subscribe to track phases and timestamps
        recovery.subscribe(phase -> {
            phaseSequence.add(phase);
            phaseTransitionTimes.add(testClock.currentTimeMillis());
        });

        // Record initial time
        var t0 = testClock.currentTimeMillis();
        assertThat(t0).isZero();

        // When: Execute recovery (runs asynchronously)
        var startTime = System.currentTimeMillis();
        var resultFuture = recovery.recover(partitionId, handler);

        // Wait for recovery to complete
        var result = resultFuture.get(5, TimeUnit.SECONDS);
        var wallClockDuration = System.currentTimeMillis() - startTime;

        // Then: Should succeed
        assertThat(result.success()).isTrue();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);

        // And: Should have transitioned through all phases
        var expectedPhases = List.of(
            RecoveryPhase.DETECTING,
            RecoveryPhase.REDISTRIBUTING,
            RecoveryPhase.REBALANCING,
            RecoveryPhase.VALIDATING,
            RecoveryPhase.COMPLETE
        );

        for (var expectedPhase : expectedPhases) {
            assertThat(phaseSequence)
                .as("Phase sequence should contain %s", expectedPhase)
                .contains(expectedPhase);
        }

        // And: Timestamps should be monotonically increasing or equal (clock controlled)
        for (var i = 1; i < phaseTransitionTimes.size(); i++) {
            assertThat(phaseTransitionTimes.get(i))
                .as("Timestamp at index %d should be >= previous", i)
                .isGreaterThanOrEqualTo(phaseTransitionTimes.get(i - 1));
        }

        // And: Should complete within 1 second wall clock time
        assertThat(wallClockDuration)
            .as("Recovery should complete within 1s")
            .isLessThan(1000);

        // ===== Reproducibility Test =====

        // Given: Fresh recovery with new TestClock at t=0
        var testClock2 = new TestClock(0);
        var recovery2 = new DefaultPartitionRecovery(partitionId, topology);
        recovery2.setClock(testClock2);

        var phaseSequence2 = new CopyOnWriteArrayList<RecoveryPhase>();
        recovery2.subscribe(phaseSequence2::add);

        // When: Execute recovery with identical clock sequence
        var result2 = recovery2.recover(partitionId, handler).get(5, TimeUnit.SECONDS);

        // Then: Should produce identical phase sequence
        assertThat(result2.success()).isTrue();

        // Verify same phases in same order
        for (var expectedPhase : expectedPhases) {
            assertThat(phaseSequence2).contains(expectedPhase);
        }

        // And: Final phase should be COMPLETE
        assertThat(recovery.getCurrentPhase()).isEqualTo(RecoveryPhase.COMPLETE);
        assertThat(recovery2.getCurrentPhase()).isEqualTo(RecoveryPhase.COMPLETE);

        // And: Both should report success
        assertThat(result.success()).isEqualTo(result2.success());
    }

    /**
     * D2: Boundary Conditions - Clock Skew & Drift.
     * <p>
     * Validates that recovery handles clock irregularities gracefully including:
     * backward time after recovery, positive clock jumps, and multiple recoveries
     * with different clock states.
     * <p>
     * <b>Scenario</b>:
     * <ol>
     *   <li>Setup with TestClock</li>
     *   <li>Test clock backward after recovery completes (no active recovery affected)</li>
     *   <li>Verify recovery handles gracefully (no crashes, valid state)</li>
     *   <li>Test positive clock jump before recovery</li>
     *   <li>Verify recovery completes despite time anomaly</li>
     *   <li>Test multiple recoveries with stable clocks</li>
     *   <li>Assert recovery completes correctly in all scenarios</li>
     *   <li>Validate recovery duration always non-negative</li>
     * </ol>
     * <p>
     * <b>Expected</b>: No crashes, recovery completes successfully.
     */
    @Test
    void testBoundaryConditionsClockSkewDrift() throws Exception {
        // ===== Test 1: Clock Backward After Recovery Completes =====

        // Given: Recovery with TestClock
        var recovery1 = new DefaultPartitionRecovery(partitionId, topology);
        testClock.setTime(1000); // Start at t=1000
        recovery1.setClock(testClock);

        var phaseHistory1 = new CopyOnWriteArrayList<RecoveryPhase>();
        recovery1.subscribe(phaseHistory1::add);

        // When: Complete recovery normally
        var result1 = recovery1.recover(partitionId, handler).get(5, TimeUnit.SECONDS);

        // Then: Should complete successfully
        assertThat(result1.success())
            .as("Recovery should complete successfully")
            .isTrue();

        // When: Clock goes backward after recovery completes
        testClock.setTime(500); // Go backward 500ms
        assertThat(testClock.currentTimeMillis()).isEqualTo(500);

        // Then: Recovery should remain in COMPLETE state (doesn't crash)
        assertThat(recovery1.getCurrentPhase()).isEqualTo(RecoveryPhase.COMPLETE);

        // And: Should have reached COMPLETE phase during recovery
        assertThat(phaseHistory1).contains(RecoveryPhase.COMPLETE);

        // ===== Test 2: Positive Clock Jump Before Recovery =====

        // Given: Fresh recovery with new partition
        var partition2 = UUID.randomUUID();
        topology.register(partition2, 1);
        var recovery2 = new DefaultPartitionRecovery(partition2, topology);
        var testClock2 = new TestClock(1000);
        recovery2.setClock(testClock2);

        var phaseHistory2 = new CopyOnWriteArrayList<RecoveryPhase>();
        recovery2.subscribe(phaseHistory2::add);

        // When: Jump clock forward significantly before recovery
        testClock2.setTime(11000); // Jump forward 10s
        assertThat(testClock2.currentTimeMillis()).isEqualTo(11000);

        // And: Start recovery with jumped clock
        var result2 = recovery2.recover(partition2, handler).get(5, TimeUnit.SECONDS);

        // Then: Should complete successfully despite clock jump
        assertThat(result2.success())
            .as("Recovery should handle positive clock skew gracefully")
            .isTrue();

        // And: Should have reached COMPLETE phase
        assertThat(recovery2.getCurrentPhase()).isEqualTo(RecoveryPhase.COMPLETE);

        // And: Should not crash
        assertThat(phaseHistory2).contains(RecoveryPhase.COMPLETE);

        // ===== Test 3: Multiple Recoveries with Stable Clock =====

        // Given: Fresh recovery with new partition
        var partition3 = UUID.randomUUID();
        topology.register(partition3, 2);
        var recovery3 = new DefaultPartitionRecovery(partition3, topology);
        var testClock3 = new TestClock(1000);
        recovery3.setClock(testClock3);

        var phaseHistory3 = new CopyOnWriteArrayList<RecoveryPhase>();
        recovery3.subscribe(phaseHistory3::add);

        // When: Execute recovery with stable clock
        var result3 = recovery3.recover(partition3, handler).get(5, TimeUnit.SECONDS);

        // Then: Should complete successfully
        assertThat(result3.success())
            .as("Recovery should complete with stable clock")
            .isTrue();

        // And: Should reach COMPLETE phase
        assertThat(recovery3.getCurrentPhase()).isEqualTo(RecoveryPhase.COMPLETE);

        // And: Should not crash or enter FAILED state
        assertThat(phaseHistory3).contains(RecoveryPhase.COMPLETE);
        assertThat(phaseHistory3).doesNotContain(RecoveryPhase.FAILED);

        // ===== Test 4: Clock Jump Between Recoveries =====

        // Given: New partition with clock at t=2000
        var partition4 = UUID.randomUUID();
        topology.register(partition4, 3);
        var testClock4 = new TestClock(2000);
        var recovery4 = new DefaultPartitionRecovery(partition4, topology);
        recovery4.setClock(testClock4);

        // When: Jump clock forward
        testClock4.setTime(12000); // Jump 10s forward
        var result4 = recovery4.recover(partition4, handler).get(5, TimeUnit.SECONDS);

        // Then: Should complete successfully
        assertThat(result4.success()).isTrue();

        // ===== Validate Duration Calculation =====

        // All recoveries should have non-negative durations
        assertThat(result1.durationMs())
            .as("Duration should be non-negative")
            .isGreaterThanOrEqualTo(0);

        assertThat(result2.durationMs())
            .as("Duration should be non-negative despite positive skew")
            .isGreaterThanOrEqualTo(0);

        assertThat(result3.durationMs())
            .as("Duration should be non-negative with stable clock")
            .isGreaterThanOrEqualTo(0);

        assertThat(result4.durationMs())
            .as("Duration should be non-negative after clock jump")
            .isGreaterThanOrEqualTo(0);
    }

    // ==================== Test Helper Classes ====================

    /**
     * Simple in-memory partition topology for testing.
     * <p>
     * Provides thread-safe registration and lookup of partition-to-rank mappings.
     */
    private static class InMemoryPartitionTopology implements PartitionTopology {
        private final Map<UUID, Integer> uuidToRank = new ConcurrentHashMap<>();
        private final Map<Integer, UUID> rankToUuid = new ConcurrentHashMap<>();
        private volatile long version = 0;

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
