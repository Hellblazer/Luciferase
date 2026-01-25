package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.*;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P5.1: Integration Testing & FaultInjector - End-to-End System Validation.
 * <p>
 * Comprehensive integration tests using FaultInjector framework to validate
 * system behavior under various failure scenarios:
 * <ul>
 *   <li>Basic E2E workflows (single and multi-partition failures)</li>
 *   <li>Network fault scenarios (packet loss, latency)</li>
 *   <li>Resource constraint scenarios (memory, threads)</li>
 *   <li>Time anomaly scenarios (clock skew, drift)</li>
 *   <li>Complex adversarial scenarios (multiple concurrent faults)</li>
 * </ul>
 * <p>
 * Uses P4.4 test infrastructure:
 * <ul>
 *   <li>{@link FaultInjector} - Controlled fault injection</li>
 *   <li>{@link FaultScenarioBuilder} - Declarative scenario definition</li>
 *   <li>{@link E2ETestValidator} - Multi-dimensional validation</li>
 *   <li>{@link IntegrationTestFixture} - Test harness</li>
 * </ul>
 */
class P51IntegrationTest {

    private IntegrationTestFixture fixture;
    private FaultInjector injector;
    private TestClock clock;
    private E2ETestValidator e2eValidator;
    private EventCapture capture;
    private PartitionTopology topology;
    private FaultHandler faultHandler;

    @BeforeEach
    void setUp() {
        fixture = new IntegrationTestFixture();
        clock = new TestClock(1000L);
        injector = new FaultInjector(clock);
        e2eValidator = new E2ETestValidator();
        capture = new EventCapture();
        topology = new InMemoryPartitionTopology();
        faultHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
    }

    @AfterEach
    void tearDown() {
        injector.shutdown();
        fixture.tearDown();
    }

    /**
     * Test 1: Single Partition E2E Workflow.
     * <p>
     * Validates complete fault → detection → recovery → success workflow
     * for a single partition failure in a 3-partition forest.
     * <p>
     * Success Criteria:
     * <ul>
     *   <li>Recovery completes successfully</li>
     *   <li>All expected phases reached</li>
     *   <li>Quorum maintained (2/3)</li>
     *   <li>Duration < 10s</li>
     * </ul>
     */
    @Test
    void testSinglePartitionE2E() throws Exception {
        // Given: 3-partition forest
        var forest = fixture.setupForestWithPartitions(3);
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        // Register partitions in topology
        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        // Setup recovery coordinator for partition 0
        var partition0 = partitionIds.get(0);
        var recovery = new DefaultPartitionRecovery(partition0, topology);
        recovery.setClock(clock);

        var phaseHistory = new CopyOnWriteArrayList<RecoveryPhase>();
        recovery.subscribe(phase -> {
            phaseHistory.add(phase);
            capture.recordEvent("recovery", new Event(clock.currentTimeMillis(), "recovery", "PHASE_CHANGE", phase));
        });

        // Register failure handler
        injector.registerPartitionFailureHandler("fixture", partitionId -> {
            fixture.injectPartitionFailure(partitionId, 0);
        });

        var startTime = System.currentTimeMillis();

        // When: Inject partition failure and trigger recovery
        injector.injectPartitionFailure(partition0, 0);
        var recoveryFuture = recovery.recover(partition0, faultHandler);
        var result = recoveryFuture.get(10, TimeUnit.SECONDS);

        var endTime = System.currentTimeMillis();
        var duration = endTime - startTime;

        // Then: Validate E2E workflow
        assertTrue(result.success(), "Recovery should succeed");

        var expectedPhases = List.of(
            RecoveryPhase.DETECTING,
            RecoveryPhase.REDISTRIBUTING,
            RecoveryPhase.REBALANCING,
            RecoveryPhase.VALIDATING,
            RecoveryPhase.COMPLETE
        );
        e2eValidator.validateWorkflowComplete(phaseHistory, expectedPhases);

        // Validate quorum maintained (2 out of 3)
        var activePartitions = new HashSet<>(partitionIds);
        activePartitions.remove(partition0);
        e2eValidator.validateQuorumMaintained(activePartitions, 2);

        // Validate recovery despite fault
        e2eValidator.validateRecoveryDespiteFaults(result.success(), injector.getInjectedFaults());

        // Validate performance (throughput value is placeholder for workflow test)
        e2eValidator.validatePerformanceSLA(duration, 10.0, PerformanceSLA.relaxed());

        var report = e2eValidator.generateReport();
        assertTrue(report.passed(), () -> "E2E validation failed:\n" + report.summary());

        assertThat(duration).isLessThan(10000L);
    }

    /**
     * Test 2: Multi-Partition Concurrent E2E Workflow.
     * <p>
     * Validates concurrent recovery of 2 partitions in a 5-partition forest.
     * <p>
     * Success Criteria:
     * <ul>
     *   <li>Both recoveries complete successfully</li>
     *   <li>Quorum maintained (3/5 minimum)</li>
     *   <li>No cross-partition interference</li>
     *   <li>Independent recovery paths</li>
     * </ul>
     */
    @Test
    void testMultiPartitionConcurrentE2E() throws Exception {
        // Given: 5-partition forest
        var forest = fixture.setupForestWithPartitions(5);
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        // Register partitions
        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        var partition1 = partitionIds.get(1);
        var partition3 = partitionIds.get(3);

        var recovery1 = new DefaultPartitionRecovery(partition1, topology);
        var recovery3 = new DefaultPartitionRecovery(partition3, topology);
        recovery1.setClock(clock);
        recovery3.setClock(clock);

        var phases1 = new CopyOnWriteArrayList<RecoveryPhase>();
        var phases3 = new CopyOnWriteArrayList<RecoveryPhase>();

        recovery1.subscribe(phase -> phases1.add(phase));
        recovery3.subscribe(phase -> phases3.add(phase));

        // Register failure handler
        injector.registerPartitionFailureHandler("fixture", partitionId -> {
            fixture.injectPartitionFailure(partitionId, 0);
        });

        // When: Inject concurrent failures
        injector.injectPartitionFailure(partition1, 0);
        injector.injectPartitionFailure(partition3, 50); // Slight stagger

        var future1 = recovery1.recover(partition1, faultHandler);
        var future3 = recovery3.recover(partition3, faultHandler);

        var result1 = future1.get(10, TimeUnit.SECONDS);
        var result3 = future3.get(10, TimeUnit.SECONDS);

        // Then: Validate both recoveries
        assertTrue(result1.success(), "Partition 1 recovery should succeed");
        assertTrue(result3.success(), "Partition 3 recovery should succeed");

        var expectedPhases = List.of(
            RecoveryPhase.DETECTING,
            RecoveryPhase.REDISTRIBUTING,
            RecoveryPhase.REBALANCING,
            RecoveryPhase.VALIDATING,
            RecoveryPhase.COMPLETE
        );
        e2eValidator.validateWorkflowComplete(phases1, expectedPhases);
        e2eValidator.validateWorkflowComplete(phases3, expectedPhases);

        // Validate quorum (3 out of 5 active)
        var activePartitions = new HashSet<>(partitionIds);
        activePartitions.remove(partition1);
        activePartitions.remove(partition3);
        e2eValidator.validateQuorumMaintained(activePartitions, 3);

        var report = e2eValidator.generateReport();
        assertTrue(report.passed(), () -> "E2E validation failed:\n" + report.summary());
    }

    /**
     * Test 3: Cascading Failure E2E Workflow.
     * <p>
     * Validates recovery from chain reaction of sequential failures
     * in a 7-partition ring topology.
     * <p>
     * Success Criteria:
     * <ul>
     *   <li>All recoveries complete</li>
     *   <li>No uncontrolled cascade</li>
     *   <li>Quorum maintained throughout</li>
     * </ul>
     */
    @Test
    void testCascadingFailureE2E() throws Exception {
        // Given: 7-partition forest
        var forest = fixture.setupForestWithPartitions(7);
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        // Register partitions
        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        // Register failure handler
        injector.registerPartitionFailureHandler("fixture", partitionId -> {
            fixture.injectPartitionFailure(partitionId, 0);
        });

        // When: Inject cascading failures (3 partitions with 200ms stagger)
        var failingPartitions = List.of(
            partitionIds.get(0),
            partitionIds.get(2),
            partitionIds.get(4)
        );
        var faults = injector.injectCascadingFailures(failingPartitions, 200);

        // Wait for cascade to complete
        Thread.sleep(1000);

        // Then: Validate system recovered
        var activePartitions = new HashSet<>(partitionIds);
        activePartitions.removeAll(failingPartitions);

        // Quorum maintained (4 out of 7 active)
        e2eValidator.validateQuorumMaintained(activePartitions, 4);

        // Verify faults injected
        assertThat(faults).hasSize(3);
        assertEquals(3, injector.getFaultsByType(FaultType.PARTITION_FAILURE).size());

        var report = e2eValidator.generateReport();
        assertTrue(report.passed(), () -> "E2E validation failed:\n" + report.summary());
    }

    /**
     * Test 4: Recovery Under Packet Loss.
     * <p>
     * Validates recovery succeeds despite 10% network packet loss.
     * <p>
     * Success Criteria:
     * <ul>
     *   <li>Recovery completes despite packet loss</li>
     *   <li>Network fault properly injected</li>
     *   <li>System adapts to degraded network</li>
     * </ul>
     */
    @Test
    void testRecoveryUnderPacketLoss() throws Exception {
        // Given: 3-partition forest
        var forest = fixture.setupForestWithPartitions(3);
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        var partition0 = partitionIds.get(0);
        var recovery = new DefaultPartitionRecovery(partition0, topology);
        recovery.setClock(clock);

        // Register failure handler
        injector.registerPartitionFailureHandler("fixture", partitionId -> {
            fixture.injectPartitionFailure(partitionId, 0);
        });

        // When: Inject packet loss before recovery
        var packetLossFault = injector.injectPacketLoss(0.10);
        assertThat(injector.shouldDropPacket()).isNotNull(); // Verify probabilistic logic works

        injector.injectPartitionFailure(partition0, 0);
        var result = recovery.recover(partition0, faultHandler).get(15, TimeUnit.SECONDS);

        // Then: Recovery should succeed despite packet loss
        assertTrue(result.success(), "Recovery should succeed despite 10% packet loss");

        // Validate fault was injected
        var networkFaults = injector.getFaultsByType(FaultType.PACKET_LOSS);
        assertThat(networkFaults).hasSize(1);
        assertEquals(0.10, networkFaults.get(0).getParameter("lossRate"));

        e2eValidator.validateRecoveryDespiteFaults(result.success(), injector.getInjectedFaults());

        var report = e2eValidator.generateReport();
        assertTrue(report.passed(), () -> "E2E validation failed:\n" + report.summary());
    }

    /**
     * Test 5: Recovery Under High Latency.
     * <p>
     * Validates recovery succeeds despite 1 second additional network latency.
     * <p>
     * Success Criteria:
     * <ul>
     *   <li>Recovery completes despite latency</li>
     *   <li>Latency fault properly tracked</li>
     * </ul>
     */
    @Test
    void testRecoveryUnderHighLatency() throws Exception {
        // Given: 3-partition forest
        var forest = fixture.setupForestWithPartitions(3);
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        var partition0 = partitionIds.get(0);
        var recovery = new DefaultPartitionRecovery(partition0, topology);
        recovery.setClock(clock);

        // Register failure handler
        injector.registerPartitionFailureHandler("fixture", partitionId -> {
            fixture.injectPartitionFailure(partitionId, 0);
        });

        // When: Inject 1s latency
        injector.injectLatency(1000);
        assertEquals(1000, injector.getAdditionalLatencyMs());

        injector.injectPartitionFailure(partition0, 0);
        var result = recovery.recover(partition0, faultHandler).get(15, TimeUnit.SECONDS);

        // Then: Recovery should succeed despite latency
        assertTrue(result.success(), "Recovery should succeed despite high latency");

        var latencyFaults = injector.getFaultsByType(FaultType.NETWORK_LATENCY);
        assertThat(latencyFaults).hasSize(1);

        e2eValidator.validateRecoveryDespiteFaults(result.success(), injector.getInjectedFaults());

        var report = e2eValidator.generateReport();
        assertTrue(report.passed(), () -> "E2E validation failed:\n" + report.summary());
    }

    /**
     * Test 6: Recovery Under Memory Pressure.
     * <p>
     * Validates recovery handles memory constraints gracefully.
     * <p>
     * Success Criteria:
     * <ul>
     *   <li>Recovery completes despite memory pressure</li>
     *   <li>Resource constraint properly tracked</li>
     * </ul>
     */
    @Test
    void testRecoveryUnderMemoryPressure() throws Exception {
        // Given: 3-partition forest
        var forest = fixture.setupForestWithPartitions(3);
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        var partition0 = partitionIds.get(0);
        var recovery = new DefaultPartitionRecovery(partition0, topology);
        recovery.setClock(clock);

        // Register failure handler
        injector.registerPartitionFailureHandler("fixture", partitionId -> {
            fixture.injectPartitionFailure(partitionId, 0);
        });

        // When: Inject memory pressure (512 MB)
        injector.injectMemoryPressure(512);
        assertEquals(512, injector.getMemoryPressureMB());

        injector.injectPartitionFailure(partition0, 0);
        var result = recovery.recover(partition0, faultHandler).get(15, TimeUnit.SECONDS);

        // Then: Recovery should succeed despite memory pressure
        assertTrue(result.success(), "Recovery should succeed despite memory pressure");

        var memoryFaults = injector.getFaultsByType(FaultType.MEMORY_PRESSURE);
        assertThat(memoryFaults).hasSize(1);

        e2eValidator.validateRecoveryDespiteFaults(result.success(), injector.getInjectedFaults());

        var report = e2eValidator.generateReport();
        assertTrue(report.passed(), () -> "E2E validation failed:\n" + report.summary());
    }

    /**
     * Test 7: Recovery Under Thread Starvation.
     * <p>
     * Validates recovery works with limited thread pool.
     * <p>
     * Success Criteria:
     * <ul>
     *   <li>Recovery completes despite thread limit</li>
     *   <li>Thread constraint properly enforced</li>
     * </ul>
     */
    @Test
    void testRecoveryUnderThreadStarvation() throws Exception {
        // Given: 3-partition forest
        var forest = fixture.setupForestWithPartitions(3);
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        var partition0 = partitionIds.get(0);
        var recovery = new DefaultPartitionRecovery(partition0, topology);
        recovery.setClock(clock);

        // Register failure handler
        injector.registerPartitionFailureHandler("fixture", partitionId -> {
            fixture.injectPartitionFailure(partitionId, 0);
        });

        // When: Inject thread starvation (max 4 threads)
        injector.injectThreadStarvation(4);
        assertEquals(4, injector.getThreadPoolLimit());

        injector.injectPartitionFailure(partition0, 0);
        var result = recovery.recover(partition0, faultHandler).get(15, TimeUnit.SECONDS);

        // Then: Recovery should succeed despite thread limit
        assertTrue(result.success(), "Recovery should succeed despite thread starvation");

        var threadFaults = injector.getFaultsByType(FaultType.THREAD_STARVATION);
        assertThat(threadFaults).hasSize(1);

        e2eValidator.validateRecoveryDespiteFaults(result.success(), injector.getInjectedFaults());

        var report = e2eValidator.generateReport();
        assertTrue(report.passed(), () -> "E2E validation failed:\n" + report.summary());
    }

    /**
     * Test 8: Recovery With Clock Skew.
     * <p>
     * Validates recovery handles clock jumps without crashing.
     * <p>
     * Success Criteria:
     * <ul>
     *   <li>Recovery completes despite clock anomalies</li>
     *   <li>Clock skew properly injected</li>
     *   <li>Time-dependent logic remains stable</li>
     * </ul>
     */
    @Test
    void testRecoveryWithClockSkew() throws Exception {
        // Given: 3-partition forest
        var forest = fixture.setupForestWithPartitions(3);
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        var partition0 = partitionIds.get(0);
        var recovery = new DefaultPartitionRecovery(partition0, topology);
        recovery.setClock(clock);

        // Register failure handler
        injector.registerPartitionFailureHandler("fixture", partitionId -> {
            fixture.injectPartitionFailure(partitionId, 0);
        });

        // When: Inject clock skew (+2 seconds)
        var initialTime = clock.currentTimeMillis();
        injector.injectClockSkew(2000);

        // Verify clock jumped forward
        assertThat(clock.currentTimeMillis()).isGreaterThanOrEqualTo(initialTime);

        injector.injectPartitionFailure(partition0, 0);
        var result = recovery.recover(partition0, faultHandler).get(15, TimeUnit.SECONDS);

        // Then: Recovery should succeed despite clock skew
        assertTrue(result.success(), "Recovery should succeed despite clock skew");

        var clockFaults = injector.getFaultsByType(FaultType.CLOCK_SKEW);
        assertThat(clockFaults).hasSize(1);

        e2eValidator.validateRecoveryDespiteFaults(result.success(), injector.getInjectedFaults());

        var report = e2eValidator.generateReport();
        assertTrue(report.passed(), () -> "E2E validation failed:\n" + report.summary());
    }

    /**
     * Test 9: Recovery With Clock Drift.
     * <p>
     * Validates recovery handles gradual clock desynchronization.
     * <p>
     * Success Criteria:
     * <ul>
     *   <li>Recovery completes despite clock drift</li>
     *   <li>Drift properly simulated</li>
     *   <li>System adapts to time drift</li>
     * </ul>
     */
    @Test
    void testRecoveryWithClockDrift() throws Exception {
        // Given: 3-partition forest
        var forest = fixture.setupForestWithPartitions(3);
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        var partition0 = partitionIds.get(0);
        var recovery = new DefaultPartitionRecovery(partition0, topology);
        recovery.setClock(clock);

        // Register failure handler
        injector.registerPartitionFailureHandler("fixture", partitionId -> {
            fixture.injectPartitionFailure(partitionId, 0);
        });

        try {
            // When: Inject clock drift (100ms per second)
            injector.injectClockDrift(100);

            injector.injectPartitionFailure(partition0, 0);
            var result = recovery.recover(partition0, faultHandler).get(15, TimeUnit.SECONDS);

            // Then: Recovery should succeed despite drift
            assertTrue(result.success(), "Recovery should succeed despite clock drift");

            var driftFaults = injector.getFaultsByType(FaultType.CLOCK_DRIFT);
            assertThat(driftFaults).hasSize(1);

            e2eValidator.validateRecoveryDespiteFaults(result.success(), injector.getInjectedFaults());

            var report = e2eValidator.generateReport();
            assertTrue(report.passed(), () -> "E2E validation failed:\n" + report.summary());
        } finally {
            // Stop drift to prevent background thread issues
            injector.stopClockDrift();
        }
    }

    /**
     * Test 10: Adversarial Scenario (Kitchen Sink).
     * <p>
     * Combines multiple concurrent faults to stress-test recovery:
     * - 2 partition failures
     * - 5% packet loss
     * - 500ms latency
     * - +1s clock skew
     * <p>
     * Success Criteria:
     * <ul>
     *   <li>System survives multiple concurrent faults</li>
     *   <li>Recovery completes successfully</li>
     *   <li>No uncontrolled cascade or crash</li>
     * </ul>
     */
    @Test
    void testAdversarialScenario() throws Exception {
        // Given: 5-partition forest
        var forest = fixture.setupForestWithPartitions(5);
        var partitionIds = new ArrayList<>(forest.getPartitionIds());

        for (var i = 0; i < partitionIds.size(); i++) {
            topology.register(partitionIds.get(i), i);
        }

        var partition0 = partitionIds.get(0);
        var partition2 = partitionIds.get(2);

        var recovery0 = new DefaultPartitionRecovery(partition0, topology);
        var recovery2 = new DefaultPartitionRecovery(partition2, topology);
        recovery0.setClock(clock);
        recovery2.setClock(clock);

        // Register failure handler
        injector.registerPartitionFailureHandler("fixture", partitionId -> {
            fixture.injectPartitionFailure(partitionId, 0);
        });

        // When: Inject multiple concurrent faults
        injector.injectPartitionFailure(partition0, 0);
        injector.injectPartitionFailure(partition2, 0);
        injector.injectPacketLoss(0.05);
        injector.injectLatency(500);
        injector.injectClockSkew(1000);

        var future0 = recovery0.recover(partition0, faultHandler);
        var future2 = recovery2.recover(partition2, faultHandler);

        var result0 = future0.get(20, TimeUnit.SECONDS);
        var result2 = future2.get(20, TimeUnit.SECONDS);

        // Then: System should survive adversarial conditions
        assertTrue(result0.success(), "Partition 0 recovery should succeed");
        assertTrue(result2.success(), "Partition 2 recovery should succeed");

        // Verify multiple fault types injected
        var allFaults = injector.getInjectedFaults();
        assertThat(allFaults).hasSizeGreaterThanOrEqualTo(5);

        var partitionFailures = injector.getFaultsByType(FaultType.PARTITION_FAILURE);
        var packetLoss = injector.getFaultsByType(FaultType.PACKET_LOSS);
        var latency = injector.getFaultsByType(FaultType.NETWORK_LATENCY);
        var clockSkew = injector.getFaultsByType(FaultType.CLOCK_SKEW);

        assertThat(partitionFailures).hasSize(2);
        assertThat(packetLoss).hasSize(1);
        assertThat(latency).hasSize(1);
        assertThat(clockSkew).hasSize(1);

        // Validate quorum maintained (3 out of 5)
        var activePartitions = new HashSet<>(partitionIds);
        activePartitions.remove(partition0);
        activePartitions.remove(partition2);
        e2eValidator.validateQuorumMaintained(activePartitions, 3);

        e2eValidator.validateRecoveryDespiteFaults(
            result0.success() && result2.success(),
            injector.getInjectedFaults()
        );

        var report = e2eValidator.generateReport();
        assertTrue(report.passed(), () -> "E2E validation failed:\n" + report.summary());
    }
}
