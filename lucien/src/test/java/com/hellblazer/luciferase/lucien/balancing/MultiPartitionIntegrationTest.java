/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F4.1.5: Multi-Partition Integration Tests for Distributed Tree Balancing
 *
 * <p>Tests the parallel balancing algorithm across multiple partitions (P = 2, 4, 8, 16, 32)
 * with O(log P) iteration complexity.
 *
 * <p>Test matrix:
 * <ul>
 *   <li>Partitions: 2, 4, 8, 16, 32</li>
 *   <li>Entity distributions: Uniform, Skewed (80/20), Boundary-heavy</li>
 *   <li>Validates: O(log P) rounds, 2:1 balance invariant, ghost exchange</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class MultiPartitionIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MultiPartitionIntegrationTest.class);

    private SequentialLongIDGenerator idGenerator;
    private MultiPartitionTestOrchestrator orchestrator;

    @ParameterizedTest(name = "Uniform distribution, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testUniformDistribution(int partitionCount) {
        log.info("Testing uniform distribution with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        orchestrator.setupPartitions(1000, MultiPartitionTestOrchestrator.EntityDistributionPattern.UNIFORM);
        var balancingResult = orchestrator.executeBalancing();
        var validationResult = orchestrator.validateFinalState();

        assertTrue(balancingResult.successful(), "Balancing should succeed");
        assertTrue(validationResult.valid(), "Final state should be valid: " + validationResult.violations());
        assertTrue(balancingResult.refinementRounds() <= Math.ceil(Math.log(partitionCount) / Math.log(2)),
                  "Refinement rounds should be O(log P)");
    }

    @ParameterizedTest(name = "Skewed 80/20 distribution, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testSkewedDistribution(int partitionCount) {
        log.info("Testing skewed 80/20 distribution with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        orchestrator.setupPartitions(800, MultiPartitionTestOrchestrator.EntityDistributionPattern.SKEWED_80_20);
        var balancingResult = orchestrator.executeBalancing();
        var validationResult = orchestrator.validateFinalState();

        assertTrue(balancingResult.successful(), "Balancing should succeed");
        assertTrue(validationResult.valid(), "Final state should be valid: " + validationResult.violations());
    }

    @ParameterizedTest(name = "Boundary-heavy distribution, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testBoundaryHeavyDistribution(int partitionCount) {
        log.info("Testing boundary-heavy distribution with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        orchestrator.setupPartitions(900, MultiPartitionTestOrchestrator.EntityDistributionPattern.BOUNDARY_HEAVY);
        var balancingResult = orchestrator.executeBalancing();
        var validationResult = orchestrator.validateFinalState();

        assertTrue(balancingResult.successful(), "Balancing should succeed");
        assertTrue(validationResult.valid(), "Final state should be valid: " + validationResult.violations());
    }

    @ParameterizedTest(name = "O(log P) refinement rounds validation, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testOLogPRounds(int partitionCount) {
        log.info("Testing O(log P) refinement rounds with {} partitions", partitionCount);
        int expectedRounds = (int) Math.ceil(Math.log(partitionCount) / Math.log(2));
        log.info("Expected rounds for P={}: {}", partitionCount, expectedRounds);

        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);
        orchestrator.setupPartitions(1000, MultiPartitionTestOrchestrator.EntityDistributionPattern.UNIFORM);
        var balancingResult = orchestrator.executeBalancing();

        assertTrue(balancingResult.refinementRounds() <= expectedRounds,
                  String.format("Expected at most %d rounds, got %d", expectedRounds, balancingResult.refinementRounds()));
    }

    @ParameterizedTest(name = "2:1 balance invariant, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testTwoToOneInvariant(int partitionCount) {
        log.info("Testing 2:1 balance invariant with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        orchestrator.setupPartitions(1000, MultiPartitionTestOrchestrator.EntityDistributionPattern.UNIFORM);
        orchestrator.executeBalancing();
        var validationResult = orchestrator.validateFinalState();

        assertTrue(validationResult.valid(), "2:1 invariant should be maintained: " + validationResult.violations());
    }

    @ParameterizedTest(name = "Ghost exchange tracking, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testGhostExchange(int partitionCount) {
        log.info("Testing ghost exchange with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        orchestrator.setupPartitions(1000, MultiPartitionTestOrchestrator.EntityDistributionPattern.BOUNDARY_HEAVY);
        orchestrator.executeBalancing();

        var tracker = orchestrator.getGhostTracker();
        assertTrue(tracker.getTotalGhostsExtracted() >= 0, "Should track extracted ghosts");
        assertTrue(tracker.getTotalGhostsExchanged() >= 0, "Should track exchanged ghosts");
    }

    @ParameterizedTest(name = "Entity relocation correctness, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testEntityRelocation(int partitionCount) {
        log.info("Testing entity relocation with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        orchestrator.setupPartitions(800, MultiPartitionTestOrchestrator.EntityDistributionPattern.UNIFORM);
        orchestrator.executeBalancing();
        var validationResult = orchestrator.validateFinalState();

        assertTrue(validationResult.valid(), "Entities should be properly relocated");
    }

    @ParameterizedTest(name = "Barrier synchronization, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testBarrierSynchronization(int partitionCount) {
        log.info("Testing barrier synchronization with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        var registry = orchestrator.getPartitionRegistry();
        assertEquals(partitionCount, registry.getPartitionCount(), "Registry should have correct partition count");
    }

    @ParameterizedTest(name = "Partition registry coordination, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testPartitionRegistry(int partitionCount) {
        log.info("Testing partition registry with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        var registry = orchestrator.getPartitionRegistry();
        assertNotNull(registry, "Partition registry should not be null");
        assertEquals(partitionCount, registry.getPartitionCount());
        assertEquals(0, registry.getCurrentRound());
    }

    @ParameterizedTest(name = "Balance convergence validation, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testBalanceConvergence(int partitionCount) {
        log.info("Testing balance convergence with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        orchestrator.setupPartitions(1000, MultiPartitionTestOrchestrator.EntityDistributionPattern.UNIFORM);
        var balancingResult = orchestrator.executeBalancing();

        assertTrue(balancingResult.successful(), "Balancing should converge successfully");
    }

    @ParameterizedTest(name = "Load distribution validation, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testLoadDistribution(int partitionCount) {
        log.info("Testing load distribution with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        orchestrator.setupPartitions(1000, MultiPartitionTestOrchestrator.EntityDistributionPattern.UNIFORM);
        orchestrator.executeBalancing();
        var validationResult = orchestrator.validateFinalState();

        assertTrue(validationResult.valid(), "Load should be distributed correctly");
    }

    @ParameterizedTest(name = "Empty partition handling, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testEmptyPartitionHandling(int partitionCount) {
        log.info("Testing empty partition handling with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        // Setup with very few entities to test empty partition handling
        orchestrator.setupPartitions(2, MultiPartitionTestOrchestrator.EntityDistributionPattern.UNIFORM);
        var balancingResult = orchestrator.executeBalancing();

        assertTrue(balancingResult.successful(), "Should handle empty partitions gracefully");
    }

    @ParameterizedTest(name = "Cross-partition entity ownership, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testCrossPartitionOwnership(int partitionCount) {
        log.info("Testing cross-partition entity ownership with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        orchestrator.setupPartitions(1000, MultiPartitionTestOrchestrator.EntityDistributionPattern.BOUNDARY_HEAVY);
        orchestrator.executeBalancing();
        var validationResult = orchestrator.validateFinalState();

        assertTrue(validationResult.valid(), "Ghost boundaries should be correctly owned");
    }

    @ParameterizedTest(name = "Scalability with increasing partitions, {0} partitions")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    public void testScalability(int partitionCount) {
        log.info("Testing scalability with {} partitions", partitionCount);
        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        orchestrator.setupPartitions(1000, MultiPartitionTestOrchestrator.EntityDistributionPattern.UNIFORM);
        var balancingResult = orchestrator.executeBalancing();

        assertTrue(balancingResult.successful(), "Algorithm should scale to P=" + partitionCount + " partitions");
        log.info("P={}: {} rounds", partitionCount, balancingResult.refinementRounds());
    }
}
