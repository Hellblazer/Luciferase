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

import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F4.1.5: Scaling Validation Tests for Distributed Tree Balancing
 *
 * <p>Validates O(log P) complexity for large partition counts (100, 1000).
 *
 * <p>These tests verify:
 * <ul>
 *   <li>Algorithm scales to 100+ partitions</li>
 *   <li>Refinement rounds = ceil(log₂(P)) for large P</li>
 *   <li>Performance remains bounded despite partition count</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ScalingValidationTest {

    private static final Logger log = LoggerFactory.getLogger(ScalingValidationTest.class);

    private SequentialLongIDGenerator idGenerator;
    private MultiPartitionTestOrchestrator orchestrator;

    /**
     * Test scaling to 100 partitions with O(log P) validation.
     *
     * <p>Expected: ceil(log₂(100)) = 7 refinement rounds
     */
    @ParameterizedTest(name = "Scaling test with {0} partitions")
    @ValueSource(ints = {100})
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testScalingTo100Partitions(int partitionCount) {
        log.info("=== SCALING TEST: {} partitions ===", partitionCount);
        int expectedRounds = (int) Math.ceil(Math.log(partitionCount) / Math.log(2));
        log.info("Expected O(log P) rounds: {}", expectedRounds);

        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);
        orchestrator.setupPartitions(5000, MultiPartitionTestOrchestrator.EntityDistributionPattern.UNIFORM);

        var startTime = System.nanoTime();
        var balancingResult = orchestrator.executeBalancing();
        var elapsed = (System.nanoTime() - startTime) / 1_000_000; // Convert to ms

        var validationResult = orchestrator.validateFinalState();

        log.info("Scaling Results [P={}]:", partitionCount);
        log.info("  Refinement rounds: {} (expected: {})", balancingResult.refinementRounds(), expectedRounds);
        log.info("  Execution time: {}ms", elapsed);
        log.info("  Load distribution valid: {}", validationResult.valid());
        log.info("  Details: {}", validationResult.details());

        assertTrue(balancingResult.successful(), "Balancing should succeed");
        assertTrue(validationResult.valid(), "Load should be validly distributed");
        assertTrue(balancingResult.refinementRounds() <= expectedRounds,
                  String.format("Expected at most %d rounds for P=%d, got %d",
                              expectedRounds, partitionCount, balancingResult.refinementRounds()));

        log.info("✓ P={} test PASSED", partitionCount);
    }

    /**
     * Test scaling to 1000 partitions with O(log P) validation.
     *
     * <p>Expected: ceil(log₂(1000)) = 10 refinement rounds
     *
     * <p>This is a stress test for large-scale distributed balancing.
     */
    @ParameterizedTest(name = "Extreme scaling test with {0} partitions")
    @ValueSource(ints = {1000})
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    public void testScalingTo1000Partitions(int partitionCount) {
        log.info("=== EXTREME SCALING TEST: {} partitions ===", partitionCount);
        int expectedRounds = (int) Math.ceil(Math.log(partitionCount) / Math.log(2));
        log.info("Expected O(log P) rounds: {}", expectedRounds);

        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);
        orchestrator.setupPartitions(10000, MultiPartitionTestOrchestrator.EntityDistributionPattern.UNIFORM);

        var startTime = System.nanoTime();
        var balancingResult = orchestrator.executeBalancing();
        var elapsed = (System.nanoTime() - startTime) / 1_000_000; // Convert to ms

        var validationResult = orchestrator.validateFinalState();

        log.info("Extreme Scaling Results [P={}]:", partitionCount);
        log.info("  Refinement rounds: {} (expected: {})", balancingResult.refinementRounds(), expectedRounds);
        log.info("  Execution time: {}ms", elapsed);
        log.info("  Load distribution valid: {}", validationResult.valid());
        log.info("  Details: {}", validationResult.details());

        assertTrue(balancingResult.successful(), "Balancing should succeed at scale");
        assertTrue(validationResult.valid(), "Load should be validly distributed at scale");
        assertTrue(balancingResult.refinementRounds() <= expectedRounds,
                  String.format("Expected at most %d rounds for P=%d, got %d",
                              expectedRounds, partitionCount, balancingResult.refinementRounds()));

        log.info("✓ P={} test PASSED", partitionCount);
    }

    /**
     * Verify O(log P) complexity empirically across multiple scales.
     *
     * <p>This test demonstrates that refinement rounds scale logarithmically:
     * <ul>
     *   <li>P=2: 1 round</li>
     *   <li>P=4: 2 rounds</li>
     *   <li>P=8: 3 rounds</li>
     *   <li>P=100: 7 rounds (ceil(log₂(100)))</li>
     *   <li>P=1000: 10 rounds (ceil(log₂(1000)))</li>
     * </ul>
     */
    @ParameterizedTest(name = "O(log P) verification: {0} partitions")
    @ValueSource(ints = {2, 4, 8, 100, 1000})
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    public void testOLogPComplexity(int partitionCount) {
        log.info("Verifying O(log P) complexity for P={}", partitionCount);

        int expectedRounds = (int) Math.ceil(Math.log(partitionCount) / Math.log(2));
        log.info("  Expected rounds: {}", expectedRounds);

        orchestrator = new MultiPartitionTestOrchestrator(partitionCount);

        // Adjust entity count based on partition count
        int entityCount = Math.max(partitionCount * 2, 100);
        orchestrator.setupPartitions(entityCount, MultiPartitionTestOrchestrator.EntityDistributionPattern.UNIFORM);

        var balancingResult = orchestrator.executeBalancing();

        log.info("  Actual rounds: {}", balancingResult.refinementRounds());
        log.info("  Complexity ratio: {}", (double) balancingResult.refinementRounds() / expectedRounds);

        assertTrue(balancingResult.refinementRounds() <= expectedRounds,
                  String.format("P=%d: Expected ≤%d rounds, got %d",
                              partitionCount, expectedRounds, balancingResult.refinementRounds()));

        // Log the complexity empirically
        double complexity = Math.log(balancingResult.refinementRounds()) / Math.log(partitionCount);
        log.info("  Empirical complexity (rounds/log(P)): {}", String.format("%.2f", complexity));
    }
}
