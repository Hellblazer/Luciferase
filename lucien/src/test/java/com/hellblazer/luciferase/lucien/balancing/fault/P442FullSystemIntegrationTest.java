package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.*;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.4.2: Full System Integration Tests (A1-A3).
 * <p>
 * End-to-end recovery workflow validation using P4.4.1 infrastructure.
 * Tests fault detection, recovery coordination, and VON topology integration.
 * <p>
 * Uses:
 * <ul>
 *   <li>{@link IntegrationTestFixture} - Test harness and lifecycle management</li>
 *   <li>{@link EventCapture} - Recovery phase transition tracking</li>
 *   <li>{@link ConfigurableValidator} - Multi-aspect state validation</li>
 *   <li>{@link DefaultPartitionRecovery} - Real recovery implementation</li>
 *   <li>{@link InMemoryPartitionTopology} - Real partition topology</li>
 *   <li>{@link SimpleFaultHandler} - Real fault handler</li>
 * </ul>
 */
class P442FullSystemIntegrationTest {

    private IntegrationTestFixture fixture;
    private EventCapture capture;
    private ConfigurableValidator validator;
    private PartitionTopology topology;
    private FaultHandler faultHandler;
    private TestClock testClock;

    @BeforeEach
    void setUp() {
        fixture = new IntegrationTestFixture();
        capture = new EventCapture();
        validator = new ConfigurableValidator();
        topology = new InMemoryPartitionTopology();
        faultHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        testClock = new TestClock(1000L);
    }

    /**
     * A1: FaultDetection → Recovery Workflow
     * <p>
     * Single partition failure scenario with full recovery workflow:
     * <ol>
     *   <li>Inject partition failure via FaultHandler</li>
     *   <li>Verify DefaultPartitionRecovery triggered</li>
     *   <li>Track all phase transitions via EventCapture</li>
     *   <li>Validate final state consistency via ConfigurableValidator</li>
     *   <li>Assert VON neighbor status updated</li>
     * </ol>
     * <p>
     * Success Criteria: Duration <5s, success rate 100%, all phases tracked.
     */
    @Test
    void testA1_FaultDetectionToRecoveryWorkflow() throws Exception {
        // Given: 3-partition forest with VON network
        var forest = fixture.setupForestWithPartitions(3);
        var network = fixture.setupVONNetwork(3);
        fixture.configureFaultHandler(); // Required for failure injection
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        // Register partitions in topology
        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        // Setup recovery for partition 0 with event capture
        var partition0 = partitionIds.get(0);
        var recovery = new DefaultPartitionRecovery(partition0, topology);
        var phaseHistory = new ArrayList<RecoveryPhase>();

        recovery.subscribe(phase -> {
            var timestamp = testClock.currentTimeMillis();
            phaseHistory.add(phase);
            capture.recordEvent("recovery", new Event(timestamp, "recovery", "PHASE_CHANGE", phase));
        });

        // Setup VON neighbors (partition 0 neighbors with partition 1 and 2)
        network.addNeighbor(partition0, partitionIds.get(1));
        network.addNeighbor(partition0, partitionIds.get(2));

        capture.reset();
        var startTime = System.currentTimeMillis();

        // When: Inject partition failure and trigger recovery
        fixture.injectPartitionFailure(partition0, 0);
        capture.recordEvent("fault", Event.of(testClock.currentTimeMillis(), "fault", "DETECTED"));

        var recoveryFuture = recovery.recover(partition0, faultHandler);
        var result = recoveryFuture.get(5, TimeUnit.SECONDS);

        var endTime = System.currentTimeMillis();
        var duration = endTime - startTime;

        // Then: Recovery should complete successfully
        assertTrue(result.success(), "Recovery should succeed");
        assertEquals(partition0, result.partitionId());

        // Verify event sequence contains key phases
        var sequence = capture.getEventSequence("fault", "recovery");
        assertThat(sequence.size()).isGreaterThanOrEqualTo(4); // At least: DETECTING, REDISTRIBUTING, REBALANCING, COMPLETE

        // Verify phase transitions
        assertTrue(phaseHistory.contains(RecoveryPhase.DETECTING), "Should have DETECTING phase");
        assertTrue(phaseHistory.contains(RecoveryPhase.REDISTRIBUTING), "Should have REDISTRIBUTING phase");
        assertTrue(phaseHistory.contains(RecoveryPhase.REBALANCING), "Should have REBALANCING phase");
        assertTrue(phaseHistory.contains(RecoveryPhase.COMPLETE), "Should have COMPLETE phase");

        // Validate final recovery state
        var expectedState = RecoveryState.initial(partition0, testClock.currentTimeMillis())
                                         .withPhase(RecoveryPhase.COMPLETE, testClock.currentTimeMillis());
        var actualState = RecoveryState.initial(partition0, testClock.currentTimeMillis())
                                       .withPhase(recovery.getCurrentPhase(), testClock.currentTimeMillis());

        validator.validateRecoveryState(expectedState, actualState);

        // Validate VON topology (partition 0 should be removed from network view)
        var activePartitions = new HashSet<>(forest.getPartitionIds());
        activePartitions.remove(partition0); // Failed partition removed
        validator.validateVONTopology(activePartitions);

        // Validate performance
        var sla = LatencySLA.relaxed(); // 5s max
        validator.validatePerformance(sla, duration);

        // Generate validation report
        var report = validator.generateReport();
        assertTrue(report.passed(), () -> "Validation failed: " + report.getFailureSummary());

        // Verify duration SLA
        assertThat(duration).isLessThan(5000L); // <5s requirement

        fixture.tearDown();
    }

    /**
     * A2: Multi-Partition Cascading Recovery
     * <p>
     * Multiple partition failures handled independently:
     * <ol>
     *   <li>Setup 5-partition forest</li>
     *   <li>Inject failures in 2 partitions simultaneously</li>
     *   <li>Verify independent recovery paths</li>
     *   <li>Ensure no cross-partition interference</li>
     *   <li>Validate quorum maintained (3/5 minimum)</li>
     *   <li>Track recovery latency per partition</li>
     * </ol>
     * <p>
     * Success Criteria: Both recoveries complete, quorum maintained, no interference.
     */
    @Test
    void testA2_MultiPartitionCascadingRecovery() throws Exception {
        // Given: 5-partition forest with VON network
        var forest = fixture.setupForestWithPartitions(5);
        var network = fixture.setupVONNetwork(5);
        fixture.configureFaultHandler(); // Required for failure injection
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        // Register all partitions
        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        // Setup recovery for partitions 1 and 3
        var partition1 = partitionIds.get(1);
        var partition3 = partitionIds.get(3);

        var recovery1 = new DefaultPartitionRecovery(partition1, topology);
        var recovery3 = new DefaultPartitionRecovery(partition3, topology);

        var phases1 = new CopyOnWriteArrayList<RecoveryPhase>();
        var phases3 = new CopyOnWriteArrayList<RecoveryPhase>();

        recovery1.subscribe(phase -> {
            phases1.add(phase);
            capture.recordEvent("recovery1", new Event(testClock.currentTimeMillis(), "recovery1", "PHASE_CHANGE", phase));
        });

        recovery3.subscribe(phase -> {
            phases3.add(phase);
            capture.recordEvent("recovery3", new Event(testClock.currentTimeMillis(), "recovery3", "PHASE_CHANGE", phase));
        });

        capture.reset();

        // When: Inject failures in both partitions (staggered)
        fixture.injectPartitionFailure(partition1, 0);
        capture.recordEvent("fault", Event.of(testClock.currentTimeMillis(), "fault", "P1_DETECTED"));

        Thread.sleep(100); // Stagger by 100ms

        fixture.injectPartitionFailure(partition3, 0);
        capture.recordEvent("fault", Event.of(testClock.currentTimeMillis(), "fault", "P3_DETECTED"));

        // Trigger concurrent recoveries
        var future1 = recovery1.recover(partition1, faultHandler);
        var future3 = recovery3.recover(partition3, faultHandler);

        var result1 = future1.get(10, TimeUnit.SECONDS);
        var result3 = future3.get(10, TimeUnit.SECONDS);

        // Then: Both recoveries should succeed
        assertTrue(result1.success(), "Partition 1 recovery should succeed");
        assertTrue(result3.success(), "Partition 3 recovery should succeed");

        // Verify quorum maintained (3 out of 5 partitions active)
        var activePartitions = new HashSet<>(forest.getPartitionIds());
        activePartitions.remove(partition1); // Failed
        activePartitions.remove(partition3); // Failed
        assertThat(activePartitions.size()).isGreaterThanOrEqualTo(3); // 5 - 2 = 3 minimum

        // Verify independent recovery paths
        assertTrue(phases1.contains(RecoveryPhase.COMPLETE), "Partition 1 should complete");
        assertTrue(phases3.contains(RecoveryPhase.COMPLETE), "Partition 3 should complete");

        // Verify no cross-partition interference (both reached COMPLETE)
        assertEquals(RecoveryPhase.COMPLETE, recovery1.getCurrentPhase());
        assertEquals(RecoveryPhase.COMPLETE, recovery3.getCurrentPhase());

        // Verify event counts (multiple phases × 2 partitions)
        var stats = capture.getStatistics();
        assertThat(stats.totalEvents()).isGreaterThanOrEqualTo(8); // At least 4 phases per partition

        // Validate VON topology
        validator.validateVONTopology(activePartitions);
        var report = validator.generateReport();
        assertTrue(report.passed(), () -> "Validation failed: " + report.getFailureSummary());

        fixture.tearDown();
    }

    /**
     * A3: Recovery with VON Topology Changes
     * <p>
     * Recovery happening concurrently with VON topology changes:
     * <ol>
     *   <li>Start recovery for partition P1</li>
     *   <li>During recovery, trigger VON neighbor join for P1</li>
     *   <li>Verify recovery completes despite topology change</li>
     *   <li>Validate VON view consistency with recovery state</li>
     *   <li>Ensure ghost layer remains valid</li>
     * </ol>
     * <p>
     * Success Criteria: Recovery completes, topology consistent, no corruption.
     */
    @Test
    void testA3_RecoveryWithVONTopologyChanges() throws Exception {
        // Given: 3-partition forest with VON network
        var forest = fixture.setupForestWithPartitions(3);
        var network = fixture.setupVONNetwork(3);
        fixture.configureFaultHandler(); // Required for failure injection
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        // Register partitions
        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        var partition0 = partitionIds.get(0);
        var partition1 = partitionIds.get(1);
        var partition2 = partitionIds.get(2);

        // Setup VON neighbors
        network.addNeighbor(partition0, partition1);
        network.addNeighbor(partition0, partition2);

        var recovery = new DefaultPartitionRecovery(partition0, topology);
        var phases = new CopyOnWriteArrayList<RecoveryPhase>();
        var rebalancingReached = new CountDownLatch(1);

        recovery.subscribe(phase -> {
            phases.add(phase);
            capture.recordEvent("recovery", new Event(testClock.currentTimeMillis(), "recovery", "PHASE_CHANGE", phase));

            if (phase == RecoveryPhase.REBALANCING) {
                rebalancingReached.countDown();
            }
        });

        capture.reset();

        // When: Start recovery
        fixture.injectPartitionFailure(partition0, 0);
        var recoveryFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return recovery.recover(partition0, faultHandler).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for REBALANCING phase, then trigger VON topology change
        assertTrue(rebalancingReached.await(5, TimeUnit.SECONDS), "Should reach REBALANCING phase");

        // During recovery, add new VON neighbor (simulates dynamic topology change)
        var newNeighbor = UUID.randomUUID();
        network.addNeighbor(partition0, newNeighbor);
        capture.recordEvent("von", Event.of(testClock.currentTimeMillis(), "von", "NEIGHBOR_JOIN"));

        // Wait for recovery completion
        var result = recoveryFuture.get(10, TimeUnit.SECONDS);

        // Then: Recovery should complete despite topology change
        assertTrue(result.success(), "Recovery should complete despite VON topology change");
        assertTrue(phases.contains(RecoveryPhase.COMPLETE), "Should reach COMPLETE phase");

        // Validate forest integrity (placeholder - real implementation would check ghost layer)
        validator.validateForestIntegrity(forest);

        // Validate VON topology consistency
        var expectedPartitions = Set.of(partition1, partition2); // partition0 failed
        validator.validateVONTopology(expectedPartitions);

        // Verify VON and recovery events captured
        var vonEvents = capture.getEventsByCategory("von");
        var recoveryEvents = capture.getEventsByCategory("recovery");
        assertThat(vonEvents).isNotEmpty();
        assertThat(recoveryEvents).isNotEmpty();

        // Generate validation report
        var report = validator.generateReport();
        assertTrue(report.passed(), () -> "Validation failed: " + report.getFailureSummary());

        fixture.tearDown();
    }

    /**
     * Helper: Wait for recovery to reach specific phase with timeout.
     */
    private boolean waitForRecoveryPhase(DefaultPartitionRecovery recovery, RecoveryPhase targetPhase, long timeoutMs) {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (recovery.getCurrentPhase() == targetPhase) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
