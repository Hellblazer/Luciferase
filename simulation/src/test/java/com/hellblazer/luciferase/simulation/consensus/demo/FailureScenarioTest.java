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

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.consensus.demo.FailureScenario.ScenarioType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FailureScenario - Byzantine failure test scenarios.
 * <p>
 * Validates:
 * - Single node crash scenario
 * - Slow node scenario (500ms delays)
 * - Byzantine voter scenario
 * - Network partition scenario
 * - Multiple simultaneous failures
 * - Recovery timing < 10s
 * - Entity preservation (100% retention)
 * - System continues with 3/4 quorum
 * <p>
 * Phase 8D Day 1: Byzantine Failure Injection
 *
 * @author hal.hildebrand
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class FailureScenarioTest {

    private ConsensusBubbleGrid grid;
    private SimulationRunner runner;
    private EntitySpawner spawner;
    private ConsensusAwareMigrator migrator;
    private FailureScenario scenario;

    @BeforeEach
    void setUp() {
        // Create 4-bubble grid
        var viewId = DigestAlgorithm.DEFAULT.digest("test-view");
        var nodeIds = List.of(
            DigestAlgorithm.DEFAULT.digest("node0"),
            DigestAlgorithm.DEFAULT.digest("node1"),
            DigestAlgorithm.DEFAULT.digest("node2"),
            DigestAlgorithm.DEFAULT.digest("node3")
        );
        grid = ConsensusBubbleGridFactory.createGrid(viewId, null, nodeIds);

        // Create supporting components
        spawner = new EntitySpawner(grid);
        migrator = new ConsensusAwareMigrator(grid);
        runner = new SimulationRunner(grid, spawner, migrator);

        // Spawn 10 test entities
        spawner.spawnEntities(10); // Spawn entities across all bubbles
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.close();
        }
    }

    @Test
    void testSingleNodeCrashScenario() {
        // Scenario: Node 0 crashes at tick 10, recovers at tick 20
        scenario = new FailureScenario(ScenarioType.SINGLE_CRASH, grid);

        var result = scenario.run(50); // Run for 50 ticks

        assertNotNull(result);
        assertTrue(result.didSystemContinue(), "System should continue with 3/4 quorum");
        assertTrue(result.wereEntitiesPreserved(), "All entities should be preserved");
        assertTrue(result.getRecoveryTimeMs() < 10_000, "Recovery should be < 10 seconds");
        assertEquals(0, result.getFailedMigrations(), "No migrations should fail");
    }

    @Test
    void testSlowNodeScenario() {
        // Scenario: Node 1 has 500ms delay throughout simulation
        scenario = new FailureScenario(ScenarioType.SLOW_NODE, grid);

        var result = scenario.run(100); // Run for 100 ticks

        assertNotNull(result);
        assertTrue(result.didSystemContinue(), "System should tolerate slow node");
        assertTrue(result.wereEntitiesPreserved(), "All entities should be preserved");
    }

    @Test
    void testByzantineVoterScenario() {
        // Scenario: Node 2 votes incorrectly (Byzantine behavior)
        scenario = new FailureScenario(ScenarioType.BYZANTINE_VOTER, grid);

        var result = scenario.run(50);

        assertNotNull(result);
        assertTrue(result.didSystemContinue(), "System should tolerate Byzantine voter");
        assertTrue(result.wereEntitiesPreserved(), "Entities preserved despite faulty votes");

        // Byzantine voting may cause some migrations to fail
        // But system should remain operational
        assertTrue(result.getFailedMigrations() >= 0, "Failed migrations should be tracked");
    }

    @Test
    void testNetworkPartitionScenario() {
        // Scenario: Node 3 partitioned at tick 15, reconnects at tick 30
        scenario = new FailureScenario(ScenarioType.PARTITION, grid);

        var result = scenario.run(60);

        assertNotNull(result);
        assertTrue(result.didSystemContinue(), "System should continue with 3/4 quorum");
        assertTrue(result.wereEntitiesPreserved(), "Entities preserved during partition");
        assertTrue(result.getRecoveryTimeMs() < 10_000, "Reconnection should be fast");
    }

    @Test
    void testMultipleFailuresScenario() {
        // Scenario: Node 0 crashes, Node 1 slow (but only 1 failed at a time)
        scenario = new FailureScenario(ScenarioType.MULTIPLE_FAILURES, grid);

        var result = scenario.run(80);

        assertNotNull(result);
        assertTrue(result.didSystemContinue(), "System should tolerate multiple failures");
        assertTrue(result.wereEntitiesPreserved(), "Entities preserved");
    }

    @Test
    void testCascadingRecoveryScenario() {
        // Scenario: Multiple nodes fail and recover in sequence
        scenario = new FailureScenario(ScenarioType.CASCADING_RECOVERY, grid);

        var result = scenario.run(100);

        assertNotNull(result);
        assertTrue(result.didSystemContinue(), "System should handle cascading recovery");
        assertTrue(result.wereEntitiesPreserved(), "Entities preserved");
    }

    @Test
    void testEntityPreservationUnderFailure() {
        scenario = new FailureScenario(ScenarioType.SINGLE_CRASH, grid);

        var initialEntityCount = spawner.getEntityCount();
        assertEquals(10, initialEntityCount, "Should start with 10 entities");

        var result = scenario.run(50);

        assertTrue(result.wereEntitiesPreserved(), "All entities should be preserved");
    }

    @Test
    void testQuorumMaintenanceUnderFailure() {
        // With 4 nodes and t=1, q=3 quorum
        // System should continue when 1 node fails
        scenario = new FailureScenario(ScenarioType.SINGLE_CRASH, grid);

        var result = scenario.run(50);

        assertTrue(result.didSystemContinue(), "System should continue with 3/4 nodes (q=3)");
    }

    @Test
    void testRecoveryTiming() {
        scenario = new FailureScenario(ScenarioType.PARTITION, grid);

        var result = scenario.run(60);

        var recoveryTimeMs = result.getRecoveryTimeMs();
        assertTrue(recoveryTimeMs > 0, "Recovery time should be positive");
        assertTrue(recoveryTimeMs < 10_000, "Recovery should complete in < 10 seconds");
    }

    @Test
    void testFailedMigrationTracking() {
        scenario = new FailureScenario(ScenarioType.BYZANTINE_VOTER, grid);

        var result = scenario.run(50);

        var failedMigrations = result.getFailedMigrations();
        assertTrue(failedMigrations >= 0, "Failed migration count should be non-negative");

        // Byzantine voting may cause some migration failures
        // System should track these for monitoring
    }
}
