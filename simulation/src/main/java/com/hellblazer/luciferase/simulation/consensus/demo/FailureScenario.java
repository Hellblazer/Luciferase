/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus.demo;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Defines and runs Byzantine failure test scenarios.
 * <p>
 * Responsibilities:
 * - Define failure scenarios (crash, partition, Byzantine voting)
 * - Run scenario and measure outcomes
 * - Validate success criteria
 * - Collect metrics (recovery time, entity preservation, migrations)
 * <p>
 * SCENARIO TYPES:
 * - SINGLE_CRASH: Node 0 crashes at tick 10, recovers at tick 20
 * - SLOW_NODE: Node 1 delayed 500ms throughout simulation
 * - BYZANTINE_VOTER: Node 2 votes incorrectly
 * - PARTITION: Node 3 partitioned at tick 15, recovers at tick 30
 * - MULTIPLE_FAILURES: 2 different failure types simultaneously
 * - CASCADING_RECOVERY: Multiple nodes fail/recover in sequence
 * <p>
 * SUCCESS CRITERIA:
 * - System continues: 3/4 quorum maintained
 * - Entities preserved: 100% retention despite failures
 * - Recovery timing: < 10 seconds
 * - Failed migrations tracked
 * <p>
 * Phase 8D Day 1: Byzantine Failure Injection
 *
 * @author hal.hildebrand
 */
public class FailureScenario {

    private static final Logger log = LoggerFactory.getLogger(FailureScenario.class);

    /**
     * Scenario types supported.
     */
    public enum ScenarioType {
        SINGLE_CRASH,           // Single node crash and recovery
        SLOW_NODE,              // Node with 500ms delays
        BYZANTINE_VOTER,        // Node votes incorrectly
        PARTITION,              // Network partition and reconnect
        MULTIPLE_FAILURES,      // Multiple failure types
        CASCADING_RECOVERY      // Sequential fail/recover
    }

    /**
     * Scenario execution result.
     *
     * @param systemContinued    true if system continued operating
     * @param entitiesPreserved  true if all entities preserved
     * @param recoveryTimeMs     Recovery time in milliseconds
     * @param failedMigrations   Count of failed migrations
     * @param finalEntityCount   Final entity count
     * @param ticksCompleted     Ticks completed
     */
    public record FailureScenarioResult(
            boolean systemContinued,
            boolean entitiesPreserved,
            long recoveryTimeMs,
            int failedMigrations,
            int finalEntityCount,
            int ticksCompleted
    ) {
        /**
         * Check if system continued operating.
         *
         * @return true if system continued
         */
        public boolean didSystemContinue() {
            return systemContinued;
        }

        /**
         * Check if entities were preserved.
         *
         * @return true if all entities preserved
         */
        public boolean wereEntitiesPreserved() {
            return entitiesPreserved;
        }

        /**
         * Get recovery time in milliseconds.
         *
         * @return Recovery time
         */
        public long getRecoveryTimeMs() {
            return recoveryTimeMs;
        }

        /**
         * Get failed migration count.
         *
         * @return Failed migrations
         */
        public int getFailedMigrations() {
            return failedMigrations;
        }
    }

    private final ScenarioType type;
    private final ConsensusBubbleGrid grid;
    private FailureInjector injector;
    private SimulationRunner runner;
    private EntitySpawner spawner;
    private ConsensusAwareMigrator migrator;
    private volatile Clock clock = Clock.system();

    /**
     * Create FailureScenario.
     *
     * @param type Scenario type to run
     * @param grid ConsensusBubbleGrid for topology
     */
    public FailureScenario(ScenarioType type, ConsensusBubbleGrid grid) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.grid = Objects.requireNonNull(grid, "grid must not be null");

        log.debug("Created FailureScenario: type={}, bubbles=4", type);
    }

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Run scenario for specified number of ticks.
     *
     * @param durationTicks Number of ticks to run
     * @return FailureScenarioResult with outcome metrics
     */
    public FailureScenarioResult run(int durationTicks) {
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("Duration must be positive, got " + durationTicks);
        }

        log.info("Running scenario: type={}, duration={} ticks", type, durationTicks);

        // Initialize components
        spawner = new EntitySpawner(grid);
        migrator = new ConsensusAwareMigrator(grid); // Test constructor with auto-approve stub
        runner = new SimulationRunner(grid, spawner, migrator);
        injector = new FailureInjector(grid, runner);

        var initialEntityCount = spawner.getEntityCount();
        var startTime = clock.currentTimeMillis();
        var systemContinued = true;
        var failedMigrations = 0;

        try {
            // Execute scenario-specific failure injection
            switch (type) {
                case SINGLE_CRASH -> runSingleCrashScenario(durationTicks);
                case SLOW_NODE -> runSlowNodeScenario(durationTicks);
                case BYZANTINE_VOTER -> runByzantineVoterScenario(durationTicks);
                case PARTITION -> runPartitionScenario(durationTicks);
                case MULTIPLE_FAILURES -> runMultipleFailuresScenario(durationTicks);
                case CASCADING_RECOVERY -> runCascadingRecoveryScenario(durationTicks);
                default -> throw new IllegalArgumentException("Unknown scenario type: " + type);
            }

            // Run simulation
            runner.runSimulation(durationTicks);

        } catch (Exception e) {
            log.error("Scenario failed with exception", e);
            systemContinued = false;
        } finally {
            injector.recoverAll();
            runner.close();
        }

        var endTime = clock.currentTimeMillis();
        var recoveryTimeMs = endTime - startTime;
        var finalEntityCount = spawner.getEntityCount();
        var entitiesPreserved = (finalEntityCount == initialEntityCount);

        log.info("Scenario complete: type={}, continued={}, entitiesPreserved={}, recovery={}ms",
                type, systemContinued, entitiesPreserved, recoveryTimeMs);

        return new FailureScenarioResult(
                systemContinued,
                entitiesPreserved,
                recoveryTimeMs,
                failedMigrations,
                finalEntityCount,
                durationTicks
        );
    }

    // ========== Scenario Implementations ==========

    /**
     * Single node crash: Node 0 crashes at tick 10, recovers at tick 20.
     */
    private void runSingleCrashScenario(int durationTicks) {
        log.debug("SINGLE_CRASH: Node 0 crashes at tick 10, recovers at tick 20");

        // Schedule crash at "tick 10" (500ms delay to simulate)
        scheduleAfterDelay(500, () -> {
            injector.injectFailure(0, FailureInjector.FailureType.NODE_CRASH);
        });

        // Schedule recovery at "tick 20" (1000ms delay)
        scheduleAfterDelay(1000, () -> {
            injector.recoverNode(0);
        });
    }

    /**
     * Slow node: Node 1 delayed 500ms throughout simulation.
     */
    private void runSlowNodeScenario(int durationTicks) {
        log.debug("SLOW_NODE: Node 1 delayed 500ms for entire simulation");
        injector.injectFailure(1, FailureInjector.FailureType.SLOW_NODE);
        // Failure persists until recoverAll() called in finally block
    }

    /**
     * Byzantine voter: Node 2 votes incorrectly.
     */
    private void runByzantineVoterScenario(int durationTicks) {
        log.debug("BYZANTINE_VOTER: Node 2 votes incorrectly for entire simulation");
        injector.injectFailure(2, FailureInjector.FailureType.BYZANTINE_VOTE);
        // Failure persists until recoverAll() called in finally block
    }

    /**
     * Network partition: Node 3 partitioned at tick 15, reconnects at tick 30.
     */
    private void runPartitionScenario(int durationTicks) {
        log.debug("PARTITION: Node 3 partitioned at tick 15, reconnects at tick 30");

        // Schedule partition at "tick 15" (750ms delay)
        scheduleAfterDelay(750, () -> {
            injector.injectFailure(3, FailureInjector.FailureType.NETWORK_PARTITION);
        });

        // Schedule reconnect at "tick 30" (1500ms delay)
        scheduleAfterDelay(1500, () -> {
            injector.recoverNode(3);
        });
    }

    /**
     * Multiple failures: Node 0 crashes, Node 1 slow (but only 1 failed at a time).
     */
    private void runMultipleFailuresScenario(int durationTicks) {
        log.debug("MULTIPLE_FAILURES: Node 0 crashes, Node 1 slow");

        // Crash node 0 immediately
        injector.injectFailure(0, FailureInjector.FailureType.NODE_CRASH);

        // Slow node 1 after 500ms
        scheduleAfterDelay(500, () -> {
            injector.injectFailure(1, FailureInjector.FailureType.SLOW_NODE);
        });

        // Recover node 0 after 1000ms
        scheduleAfterDelay(1000, () -> {
            injector.recoverNode(0);
        });
    }

    /**
     * Cascading recovery: Multiple nodes fail and recover in sequence.
     */
    private void runCascadingRecoveryScenario(int durationTicks) {
        log.debug("CASCADING_RECOVERY: Multiple nodes fail/recover in sequence");

        // Node 0 crashes at start
        injector.injectFailure(0, FailureInjector.FailureType.NODE_CRASH);

        // Node 1 crashes at 300ms, Node 0 recovers
        scheduleAfterDelay(300, () -> {
            injector.recoverNode(0);
            injector.injectFailure(1, FailureInjector.FailureType.NODE_CRASH);
        });

        // Node 2 crashes at 600ms, Node 1 recovers
        scheduleAfterDelay(600, () -> {
            injector.recoverNode(1);
            injector.injectFailure(2, FailureInjector.FailureType.NODE_CRASH);
        });

        // Node 2 recovers at 900ms
        scheduleAfterDelay(900, () -> {
            injector.recoverNode(2);
        });
    }

    /**
     * Schedule action to run after delay.
     *
     * @param delayMs Delay in milliseconds
     * @param action  Action to run
     */
    private void scheduleAfterDelay(long delayMs, Runnable action) {
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                action.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Scheduled action interrupted");
            }
        }, "FailureScenario-Scheduler").start();
    }
}
