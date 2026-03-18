package com.hellblazer.luciferase.lucien.balancing.fault.test;

import com.hellblazer.luciferase.lucien.balancing.fault.FaultHandler;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionStatus;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Predefined test scenarios for fault tolerance testing.
 * <p>
 * Provides reusable test scenarios that exercise common failure patterns,
 * recovery sequences, and edge cases. Each scenario follows the TestScenario
 * interface contract: setup, execute, verify, cleanup.
 * <p>
 * Example usage:
 * <pre>{@code
 * var handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
 * var simulator = new PartitionSimulator(partitionId);
 *
 * var scenario = TestFaultScenarios.singleNodeFailureWithRecovery();
 * scenario.setup(handler, simulator);
 * scenario.execute();
 * scenario.verify(handler);
 * scenario.cleanup();
 * }</pre>
 */
public class TestFaultScenarios {

    /**
     * Test scenario interface.
     */
    public interface TestScenario {
        /**
         * Setup scenario prerequisites.
         *
         * @param handler fault handler
         * @param simulator partition simulator
         * @throws Exception if setup fails
         */
        void setup(FaultHandler handler, PartitionSimulator simulator) throws Exception;

        /**
         * Execute scenario steps.
         *
         * @throws Exception if execution fails
         */
        void execute() throws Exception;

        /**
         * Verify scenario postconditions.
         *
         * @param handler fault handler for assertions
         * @throws AssertionError if verification fails
         */
        void verify(FaultHandler handler) throws AssertionError;

        /**
         * Cleanup scenario resources.
         */
        void cleanup();
    }

    /**
     * Scenario: Single node failure followed by successful recovery.
     * <p>
     * Steps:
     * <ol>
     *   <li>Partition starts HEALTHY</li>
     *   <li>Partition fails (transitions to FAILED)</li>
     *   <li>Recovery initiated</li>
     *   <li>Recovery completes successfully (back to HEALTHY)</li>
     * </ol>
     *
     * @return test scenario
     */
    public static TestScenario singleNodeFailureWithRecovery() {
        return new TestScenario() {
            private FaultHandler handler;
            private PartitionSimulator simulator;
            private UUID partitionId;

            @Override
            public void setup(FaultHandler handler, PartitionSimulator simulator) {
                this.handler = handler;
                this.simulator = simulator;
                this.partitionId = simulator.getPartitionId();

                // Start with healthy partition
                handler.start();
                simulator.simulateHealthy(100);
            }

            @Override
            public void execute() throws Exception {
                // Simulate failure
                simulator.simulateFailure();
                TimeUnit.MILLISECONDS.sleep(100);

                // Verify failure detected
                if (handler.checkHealth(partitionId) != PartitionStatus.FAILED) {
                    throw new IllegalStateException("Expected FAILED status");
                }

                // Simulate recovery
                simulator.simulateRecoveryInProgress(200);
                TimeUnit.MILLISECONDS.sleep(300);
            }

            @Override
            public void verify(FaultHandler handler) {
                var status = handler.checkHealth(partitionId);
                if (status != PartitionStatus.HEALTHY) {
                    throw new AssertionError("Expected HEALTHY after recovery, got: " + status);
                }

                var history = simulator.getStatusHistory();
                if (history.size() < 3) {
                    throw new AssertionError("Expected at least 3 transitions, got: " + history.size());
                }
            }

            @Override
            public void cleanup() {
                if (simulator != null) {
                    simulator.cleanup();
                }
                if (handler != null) {
                    handler.stop();
                }
            }
        };
    }

    /**
     * Scenario: Cascading failure (domino effect).
     * <p>
     * Steps:
     * <ol>
     *   <li>Primary partition fails</li>
     *   <li>Failure cascades to dependent partitions</li>
     *   <li>Multiple partitions in FAILED state</li>
     * </ol>
     *
     * @param primaryId primary partition that triggers cascade
     * @param dependentIds dependent partitions that fail subsequently
     * @param cascadeDelayMs delay between cascade steps
     * @return test scenario
     */
    public static TestScenario cascadingFailure(
        UUID primaryId,
        List<UUID> dependentIds,
        int cascadeDelayMs
    ) {
        return new TestScenario() {
            private FaultHandler handler;
            private PartitionSimulator primarySimulator;
            private List<PartitionSimulator> dependentSimulators;

            @Override
            public void setup(FaultHandler handler, PartitionSimulator primarySimulator) {
                this.handler = handler;
                this.primarySimulator = primarySimulator;
                this.dependentSimulators = dependentIds.stream()
                    .map(PartitionSimulator::new)
                    .toList();

                handler.start();
                primarySimulator.simulateHealthy(100);
                dependentSimulators.forEach(sim -> sim.simulateHealthy(100));
            }

            @Override
            public void execute() throws Exception {
                // Trigger cascading failure
                primarySimulator.simulateCascadingFailure(
                    dependentIds,
                    cascadeDelayMs,
                    dependentSimulators
                );

                // Wait for cascade to complete
                TimeUnit.MILLISECONDS.sleep(cascadeDelayMs * (dependentIds.size() + 1) + 100);
            }

            @Override
            public void verify(FaultHandler handler) {
                // Primary should be FAILED
                var primaryStatus = handler.checkHealth(primaryId);
                if (primaryStatus != PartitionStatus.FAILED) {
                    throw new AssertionError("Primary partition not FAILED: " + primaryStatus);
                }

                // All dependents should be FAILED
                for (var id : dependentIds) {
                    var status = handler.checkHealth(id);
                    if (status != PartitionStatus.FAILED) {
                        throw new AssertionError("Dependent " + id + " not FAILED: " + status);
                    }
                }
            }

            @Override
            public void cleanup() {
                if (primarySimulator != null) {
                    primarySimulator.cleanup();
                }
                if (dependentSimulators != null) {
                    dependentSimulators.forEach(PartitionSimulator::cleanup);
                }
                if (handler != null) {
                    handler.stop();
                }
            }
        };
    }

    /**
     * Scenario: Barrier timeout with retry.
     * <p>
     * Steps:
     * <ol>
     *   <li>Partition times out on barrier</li>
     *   <li>Status transitions to SUSPECTED</li>
     *   <li>Retry succeeds, returns to HEALTHY</li>
     * </ol>
     *
     * @return test scenario
     */
    public static TestScenario barrierTimeoutWithRetry() {
        return new TestScenario() {
            private FaultHandler handler;
            private PartitionSimulator simulator;
            private UUID partitionId;

            @Override
            public void setup(FaultHandler handler, PartitionSimulator simulator) {
                this.handler = handler;
                this.simulator = simulator;
                this.partitionId = simulator.getPartitionId();

                handler.start();
                simulator.simulateHealthy(100);
            }

            @Override
            public void execute() throws Exception {
                // Simulate barrier timeout
                simulator.simulateBarrierTimeout();
                TimeUnit.MILLISECONDS.sleep(50);

                // Report to handler
                handler.reportBarrierTimeout(partitionId);
                TimeUnit.MILLISECONDS.sleep(50);

                // Retry succeeds
                simulator.simulateHealthy(100);
                handler.markHealthy(partitionId);
                TimeUnit.MILLISECONDS.sleep(50);
            }

            @Override
            public void verify(FaultHandler handler) {
                var status = handler.checkHealth(partitionId);
                if (status != PartitionStatus.HEALTHY) {
                    throw new AssertionError("Expected HEALTHY after retry, got: " + status);
                }

                var history = simulator.getStatusHistory();
                if (history.size() < 2) {
                    throw new AssertionError("Expected at least 2 transitions");
                }
            }

            @Override
            public void cleanup() {
                if (simulator != null) {
                    simulator.cleanup();
                }
                if (handler != null) {
                    handler.stop();
                }
            }
        };
    }

    /**
     * Scenario: Slow node detection (gradient degradation).
     * <p>
     * Steps:
     * <ol>
     *   <li>Partition starts healthy</li>
     *   <li>Gradually slows down (HEALTHY → SUSPECTED)</li>
     *   <li>Either recovers or fails completely</li>
     * </ol>
     *
     * @param slowDownDelayMs delay before transition to SUSPECTED
     * @param recovers true if partition recovers, false if it fails
     * @return test scenario
     */
    public static TestScenario slowNodeDetection(int slowDownDelayMs, boolean recovers) {
        return new TestScenario() {
            private FaultHandler handler;
            private PartitionSimulator simulator;
            private UUID partitionId;

            @Override
            public void setup(FaultHandler handler, PartitionSimulator simulator) {
                this.handler = handler;
                this.simulator = simulator;
                this.partitionId = simulator.getPartitionId();

                handler.start();
                simulator.simulateHealthy(100);
            }

            @Override
            public void execute() throws Exception {
                // Simulate gradual slowdown
                simulator.simulateSlowDown(slowDownDelayMs);
                TimeUnit.MILLISECONDS.sleep(slowDownDelayMs + 100);

                if (recovers) {
                    // Partition recovers
                    simulator.simulateHealthy(100);
                    handler.markHealthy(partitionId);
                } else {
                    // Partition fails completely
                    simulator.simulateFailure();
                }

                TimeUnit.MILLISECONDS.sleep(100);
            }

            @Override
            public void verify(FaultHandler handler) {
                var status = handler.checkHealth(partitionId);
                var expectedStatus = recovers ? PartitionStatus.HEALTHY : PartitionStatus.FAILED;

                if (status != expectedStatus) {
                    throw new AssertionError("Expected " + expectedStatus + ", got: " + status);
                }
            }

            @Override
            public void cleanup() {
                if (simulator != null) {
                    simulator.cleanup();
                }
                if (handler != null) {
                    handler.stop();
                }
            }
        };
    }

    /**
     * Scenario: Network partition (split-brain simulation).
     * <p>
     * Simulates asymmetric communication failure where partition can send
     * but not receive (or vice versa).
     *
     * @return test scenario
     */
    public static TestScenario networkPartition() {
        return new TestScenario() {
            private FaultHandler handler;
            private PartitionSimulator simulator;
            private UUID partitionId;

            @Override
            public void setup(FaultHandler handler, PartitionSimulator simulator) {
                this.handler = handler;
                this.simulator = simulator;
                this.partitionId = simulator.getPartitionId();

                handler.start();
                simulator.simulateHealthy(100);
            }

            @Override
            public void execute() throws Exception {
                // Simulate network partition (sync failures)
                handler.reportSyncFailure(partitionId);
                TimeUnit.MILLISECONDS.sleep(50);

                handler.reportSyncFailure(partitionId);
                TimeUnit.MILLISECONDS.sleep(50);

                // Partition still running but isolated
                simulator.simulateSlowDown(100);
                TimeUnit.MILLISECONDS.sleep(150);
            }

            @Override
            public void verify(FaultHandler handler) {
                var status = handler.checkHealth(partitionId);
                if (status != PartitionStatus.SUSPECTED && status != PartitionStatus.FAILED) {
                    throw new AssertionError("Expected SUSPECTED or FAILED, got: " + status);
                }
            }

            @Override
            public void cleanup() {
                if (simulator != null) {
                    simulator.cleanup();
                }
                if (handler != null) {
                    handler.stop();
                }
            }
        };
    }
}
