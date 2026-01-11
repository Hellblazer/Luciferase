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
import com.hellblazer.luciferase.simulation.consensus.demo.FailureInjector.FailureType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FailureInjector - Byzantine failure injection for simulation.
 * <p>
 * Validates:
 * - Node crash injection
 * - Slow node injection (500ms delays)
 * - Network partition injection
 * - Byzantine voting injection
 * - Failure recovery
 * - Multiple simultaneous failures
 * - Failure timeline tracking
 * <p>
 * Phase 8D Day 1: Byzantine Failure Injection
 *
 * @author hal.hildebrand
 */
class FailureInjectorTest {

    private ConsensusBubbleGrid grid;
    private SimulationRunner runner;
    private EntitySpawner spawner;
    private ConsensusAwareMigrator migrator;
    private FailureInjector injector;

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

        // Create injector
        injector = new FailureInjector(grid, runner);
    }

    @AfterEach
    void tearDown() {
        if (injector != null) {
            injector.recoverAll();
        }
        if (runner != null) {
            runner.close();
        }
    }

    @Test
    void testInjectNodeCrash() {
        // Inject crash on bubble 0
        injector.injectFailure(0, FailureType.NODE_CRASH);

        assertTrue(injector.isNodeFailing(0), "Node 0 should be failing");
        assertEquals(FailureType.NODE_CRASH, injector.getFailureType(0));

        // Other nodes should be healthy
        assertFalse(injector.isNodeFailing(1));
        assertFalse(injector.isNodeFailing(2));
        assertFalse(injector.isNodeFailing(3));
    }

    @Test
    void testInjectSlowNode() {
        // Inject 500ms delay on bubble 1
        injector.injectFailure(1, FailureType.SLOW_NODE);

        assertTrue(injector.isNodeFailing(1), "Node 1 should be failing");
        assertEquals(FailureType.SLOW_NODE, injector.getFailureType(1));
    }

    @Test
    void testInjectNetworkPartition() {
        // Partition bubble 2
        injector.injectFailure(2, FailureType.NETWORK_PARTITION);

        assertTrue(injector.isNodeFailing(2), "Node 2 should be partitioned");
        assertEquals(FailureType.NETWORK_PARTITION, injector.getFailureType(2));
    }

    @Test
    void testInjectByzantineVoting() {
        // Byzantine voting on bubble 3
        injector.injectFailure(3, FailureType.BYZANTINE_VOTE);

        assertTrue(injector.isNodeFailing(3), "Node 3 should have Byzantine voting");
        assertEquals(FailureType.BYZANTINE_VOTE, injector.getFailureType(3));
    }

    @Test
    void testRecoverNode() {
        // Inject crash on bubble 0
        injector.injectFailure(0, FailureType.NODE_CRASH);
        assertTrue(injector.isNodeFailing(0));

        // Recover node 0
        injector.recoverNode(0);
        assertFalse(injector.isNodeFailing(0), "Node 0 should be recovered");
        assertNull(injector.getFailureType(0), "Node 0 should have no failure type");
    }

    @Test
    void testMultipleFailures() {
        // Inject different failures on multiple nodes
        injector.injectFailure(0, FailureType.NODE_CRASH);
        injector.injectFailure(1, FailureType.SLOW_NODE);

        assertTrue(injector.isNodeFailing(0));
        assertTrue(injector.isNodeFailing(1));
        assertEquals(FailureType.NODE_CRASH, injector.getFailureType(0));
        assertEquals(FailureType.SLOW_NODE, injector.getFailureType(1));

        // Healthy nodes
        assertFalse(injector.isNodeFailing(2));
        assertFalse(injector.isNodeFailing(3));
    }

    @Test
    void testFailureTimeline() {
        // Inject failures and check timeline
        injector.injectFailure(0, FailureType.NODE_CRASH);
        injector.injectFailure(1, FailureType.BYZANTINE_VOTE);

        var timeline = injector.getFailureTimeline();
        assertNotNull(timeline);
        assertEquals(2, timeline.size(), "Should have 2 failure events");

        // Verify events have timestamps
        for (var event : timeline) {
            assertTrue(event.timestampMs() > 0, "Event should have timestamp");
            assertTrue(event.bubbleIndex() >= 0 && event.bubbleIndex() <= 3);
            assertNotNull(event.type());
        }
    }

    @Test
    void testRecoverAll() {
        // Inject multiple failures
        injector.injectFailure(0, FailureType.NODE_CRASH);
        injector.injectFailure(1, FailureType.SLOW_NODE);
        injector.injectFailure(2, FailureType.NETWORK_PARTITION);

        assertTrue(injector.isNodeFailing(0));
        assertTrue(injector.isNodeFailing(1));
        assertTrue(injector.isNodeFailing(2));

        // Recover all
        injector.recoverAll();

        assertFalse(injector.isNodeFailing(0));
        assertFalse(injector.isNodeFailing(1));
        assertFalse(injector.isNodeFailing(2));
        assertFalse(injector.isNodeFailing(3));
    }

    @Test
    void testFailureWithDuration() {
        // Inject failure with 1000ms duration
        injector.injectFailure(0, FailureType.NODE_CRASH, 1000);

        assertTrue(injector.isNodeFailing(0));
        var duration = injector.getFailureDuration(0);
        assertTrue(duration > 0, "Failure should have positive duration");
    }

    @Test
    void testInvalidBubbleIndex() {
        assertThrows(IllegalArgumentException.class, () -> {
            injector.injectFailure(-1, FailureType.NODE_CRASH);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            injector.injectFailure(4, FailureType.NODE_CRASH);
        });
    }
}
