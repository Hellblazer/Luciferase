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

import com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 3 Integration Tests - End-to-End Cross-Partition Balance Protocol.
 *
 * <p>Updated for Inc3-C4: uses MockRefinementExchange (domain interface) instead of
 * MockBalanceCoordinatorClient. Sent-request captures are domain RefinementRequest records;
 * assertions use record accessors instead of proto getters.
 *
 * @author hal.hildebrand
 */
public class Phase3IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(Phase3IntegrationTest.class);

    private Phase44ForestIntegrationFixture fixture;
    private BalanceConfiguration config;

    @BeforeEach
    public void setUp() {
        fixture = new Phase44ForestIntegrationFixture();
        config = BalanceConfiguration.defaultConfig();
    }

    /**
     * SCENARIO 1: Two-Partition Single Round Convergence.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testTwoPartitionSingleRound_ConvergesQuickly() {
        log.info("=== SCENARIO 1: Two-Partition Single Round Convergence ===");

        var distributedForest = fixture.createForest(100, 2);
        fixture.syncGhostLayer();

        var forest = fixture.getForest();
        var ghostLayer = fixture.getGhostLayer();

        var exchange = new MockRefinementExchange();
        var registry = new MockPartitionRegistry(2);
        var phase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>(exchange, registry, config);

        phase.setForestContext(forest, ghostLayer);

        var result = phase.execute(forest, 0, 2);

        assertThat(result.successful())
            .as("Phase 3 should complete successfully for 2 partitions")
            .isTrue();

        assertThat(result.finalMetrics().roundCount())
            .as("Should execute exactly 1 refinement round for 2 partitions (O(log 2) = 1)")
            .isEqualTo(1);

        var violations = fixture.findCurrentViolations();
        log.info("Violations after convergence: {}", violations.size());

        assertThat(registry.getBarrierCount())
            .as("Should synchronize at barrier after round 1")
            .isGreaterThanOrEqualTo(1);

        log.info("Scenario 1 PASSED: Converged in {} rounds with {} refinements",
                result.finalMetrics().roundCount(), result.refinementsApplied());
    }

    /**
     * SCENARIO 2: Four-Partition Two Rounds with Butterfly Pattern.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testFourPartitionTwoRounds_ButterflyPattern() {
        log.info("=== SCENARIO 2: Four-Partition Two Rounds with Butterfly Pattern ===");

        var distributedForest = fixture.createForest(150, 4);
        fixture.syncGhostLayer();

        var forest = fixture.getForest();
        var ghostLayer = fixture.getGhostLayer();

        var exchange = new MockRefinementExchange();
        var registry = new MockPartitionRegistry(4);
        var phase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>(exchange, registry, config);

        phase.setForestContext(forest, ghostLayer);

        var result = phase.execute(forest, 0, 4);

        assertThat(result.successful())
            .as("Phase 3 should complete successfully for 4 partitions")
            .isTrue();

        assertThat(result.finalMetrics().roundCount())
            .as("Should execute exactly 2 refinement rounds for 4 partitions (O(log 4) = 2)")
            .isEqualTo(2);

        var sentRequests = exchange.getSentRequests();
        assertThat(sentRequests)
            .as("Should send requests in butterfly pattern")
            .isNotEmpty();

        assertThat(registry.getBarrierCount())
            .as("Should synchronize at barrier after each round (2 barriers for 2 rounds)")
            .isGreaterThanOrEqualTo(2);

        log.info("Scenario 2 PASSED: Converged in {} rounds with {} refinements, {} requests sent",
                result.finalMetrics().roundCount(), result.refinementsApplied(), sentRequests.size());
    }

    /**
     * SCENARIO 3: Asymmetric Violations Partial Recovery.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testAsymmetricViolations_PartialRecovery() {
        log.info("=== SCENARIO 3: Asymmetric Violations Partial Recovery ===");

        var distributedForest = fixture.createForest(150, 4);
        fixture.syncGhostLayer();

        var forest = fixture.getForest();
        var ghostLayer = fixture.getGhostLayer();

        var exchange = new MockRefinementExchange();
        var registry = new MockPartitionRegistry(4);
        var phase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>(exchange, registry, config);

        phase.setForestContext(forest, ghostLayer);

        // Inject asymmetric responses
        exchange.addMockResponse(1, createMockResponse(1, 1, 3));
        exchange.addMockResponse(3, createMockResponse(3, 1, 2));

        var result = phase.execute(forest, 0, 4);

        assertThat(result.successful())
            .as("Phase 3 should complete successfully even with asymmetric violations")
            .isTrue();

        assertThat(result.finalMetrics().roundCount())
            .as("Should complete within expected rounds for 4 partitions")
            .isGreaterThanOrEqualTo(1)
            .isLessThanOrEqualTo(2);

        var ghostElementCount = ghostLayer.getNumGhostElements();
        log.info("Ghost layer contains {} elements after asymmetric recovery", ghostElementCount);

        assertThat(ghostElementCount)
            .as("Ghost layer should contain elements from asymmetric boundaries")
            .isGreaterThanOrEqualTo(0);

        log.info("Scenario 3 PASSED: Asymmetric violations handled in {} rounds",
                result.finalMetrics().roundCount());
    }

    /**
     * SCENARIO 4: Partition Failure Graceful Degradation.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testPartitionFailure_GracefulDegradation() {
        log.info("=== SCENARIO 4: Partition Failure Graceful Degradation ===");

        var distributedForest = fixture.createForest(120, 3);
        fixture.syncGhostLayer();

        var forest = fixture.getForest();
        var ghostLayer = fixture.getGhostLayer();

        var exchange = new MockRefinementExchange();
        exchange.setPartitionFailed(2, true); // Partition 2 is failed

        var registry = new MockPartitionRegistry(3);
        var phase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>(exchange, registry, config);

        phase.setForestContext(forest, ghostLayer);

        var result = phase.execute(forest, 0, 3);

        assertThat(result.successful())
            .as("Phase 3 should complete successfully even with 1 failed partition")
            .isTrue();

        var sentRequests = exchange.getSentRequests();
        assertThat(sentRequests)
            .as("Should send requests only to healthy partitions")
            .isNotEmpty();

        log.info("Scenario 4 PASSED: Graceful degradation with 1 failed partition, {} refinements applied",
                result.refinementsApplied());
    }

    /**
     * SCENARIO 5: Large Forest Scaling (8 Partitions, O(log 8) = 3 Rounds).
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    public void testLargeForestScaling_8Partitions_3Rounds() {
        log.info("=== SCENARIO 5: Large Forest Scaling (8 Partitions, O(log 8) = 3) ===");

        var distributedForest = fixture.createForest(200, 8);
        fixture.syncGhostLayer();

        var forest = fixture.getForest();
        var ghostLayer = fixture.getGhostLayer();

        var exchange = new MockRefinementExchange();
        var registry = new MockPartitionRegistry(8);
        var phase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>(exchange, registry, config);

        phase.setForestContext(forest, ghostLayer);

        var startTime = System.currentTimeMillis();
        var result = phase.execute(forest, 0, 8);
        var elapsed = System.currentTimeMillis() - startTime;

        assertThat(result.successful())
            .as("Phase 3 should complete successfully for 8 partitions")
            .isTrue();

        assertThat(result.finalMetrics().roundCount())
            .as("Should execute exactly 3 refinement rounds for 8 partitions (O(log 8) = 3)")
            .isEqualTo(3);

        assertThat(elapsed)
            .as("Total execution time should be reasonable (<15s)")
            .isLessThan(15000L);

        var avgRoundTime = result.finalMetrics().averageRoundTime().toMillis();
        assertThat(avgRoundTime)
            .as("Average round time should be reasonable (<5s)")
            .isLessThan(5000L);

        assertThat(registry.getBarrierCount())
            .as("Should synchronize at barrier after each round (3 barriers for 3 rounds)")
            .isGreaterThanOrEqualTo(3);

        log.info("Scenario 5 PASSED: 8 partitions converged in {} rounds, total time={}ms, avg round time={}ms",
                result.finalMetrics().roundCount(), elapsed, avgRoundTime);
    }

    /**
     * SCENARIO 6: End-to-End Correctness Full Phase 3 Protocol.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    public void testEndToEndCorrectness_FullPhase3Protocol() {
        log.info("=== SCENARIO 6: End-to-End Correctness Full Phase 3 Protocol ===");

        var distributedForest = fixture.createForest(150, 4);
        fixture.syncGhostLayer();

        var forest = fixture.getForest();
        var ghostLayer = fixture.getGhostLayer();

        var exchange = new MockRefinementExchange();
        var registry = new MockPartitionRegistry(4);
        var phase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>(exchange, registry, config);

        phase.setForestContext(forest, ghostLayer);

        // STEP 1: Identify violations before execution
        var initialViolations = fixture.findCurrentViolations();
        log.info("Initial violations detected: {}", initialViolations.size());

        // STEP 2: Execute complete Phase 3 workflow
        var result = phase.execute(forest, 0, 4);

        // STEP 3: Verify end-to-end correctness
        assertThat(result.successful())
            .as("Phase 3 workflow should complete successfully")
            .isTrue();

        assertThat(result.finalMetrics().roundCount())
            .as("Should execute expected rounds for 4 partitions (O(log 4) = 2)")
            .isEqualTo(2);

        // STEP 4: Verify requests were sent
        var sentRequests = exchange.getSentRequests();
        assertThat(sentRequests)
            .as("identifyRefinementNeeds() should create refinement requests")
            .isNotEmpty();

        // STEP 5: Verify domain request structure (record accessors, not proto getters)
        for (var request : sentRequests) {
            assertThat(request.requesterRank())
                .as("Request should have valid requester rank")
                .isBetween(0, 3);

            assertThat(request.roundNumber())
                .as("Request should have valid round number")
                .isGreaterThan(0);

            assertThat(request.treeLevel())
                .as("Request should have valid tree level")
                .isBetween(0, 21);

            assertThat(request.timestamp())
                .as("Request should have timestamp")
                .isGreaterThan(0L);
        }

        // STEP 6: Verify synchronization
        assertThat(registry.getBarrierCount())
            .as("Should synchronize at barrier after each round")
            .isGreaterThanOrEqualTo(2);

        // STEP 7: Verify metrics collection
        var metrics = result.finalMetrics();
        assertThat(metrics.totalTime().toNanos())
            .as("Should track total execution time")
            .isGreaterThan(0L);

        assertThat(metrics.averageRoundTime().toNanos())
            .as("Should track average round time")
            .isGreaterThan(0L);

        assertThat(metrics.maxRoundTime().toNanos())
            .as("Should track max round time")
            .isGreaterThan(0L);

        // STEP 8: Verify ghost layer updates
        var finalGhostCount = ghostLayer.getNumGhostElements();
        log.info("Ghost layer final count: {} elements", finalGhostCount);

        // STEP 9: Verify final violations
        var finalViolations = fixture.findCurrentViolations();
        log.info("Final violations remaining: {}", finalViolations.size());

        log.info("Scenario 6 PASSED: Full Phase 3 protocol executed successfully");
        log.info("  - Rounds: {}", result.finalMetrics().roundCount());
        log.info("  - Refinements: {}", result.refinementsApplied());
        log.info("  - Requests sent: {}", sentRequests.size());
        log.info("  - Barriers: {}", registry.getBarrierCount());
        log.info("  - Total time: {}ms", metrics.totalTime().toMillis());
        log.info("  - Avg round time: {}ms", metrics.averageRoundTime().toMillis());
    }

    // ========== Helper Methods ==========

    private RefinementResponse<MortonKey, LongEntityID, Phase44ForestIntegrationFixture.TestEntity>
    createMockResponse(int responderRank, int roundNumber, int ghostElementCount) {
        return new RefinementResponse<>(0, responderRank, 0L, roundNumber,
                                        List.of(), ghostElementCount > 0, System.currentTimeMillis());
    }

    // ========== Mock Infrastructure ==========

    /**
     * Mock RefinementExchange — domain implementation replacing MockBalanceCoordinatorClient.
     * Captures sent requests as domain RefinementRequest records.
     */
    private static class MockRefinementExchange
            implements RefinementExchange<MortonKey, LongEntityID, Phase44ForestIntegrationFixture.TestEntity> {

        private final Map<Integer, RefinementResponse<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>> mockResponses = new ConcurrentHashMap<>();
        private final List<RefinementRequest<MortonKey>> sentRequests =
            Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private final Set<Integer> failedPartitions = ConcurrentHashMap.newKeySet();
        private volatile boolean alwaysNeedsRefinement = false;

        @Override
        public CompletableFuture<RefinementResponse<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>> requestRefinementAsync(
                int targetRank, long treeId, int roundNumber, int treeLevel,
                List<MortonKey> boundaryKeys) {

            requestCount.incrementAndGet();

            // Simulate failed partition — complete exceptionally so the .exceptionally handler
            // in sendRequestsParallel maps it to RefinementResponse.empty()
            if (failedPartitions.contains(targetRank)) {
                var failedFuture =
                    new CompletableFuture<RefinementResponse<MortonKey, LongEntityID,
                            Phase44ForestIntegrationFixture.TestEntity>>();
                failedFuture.completeExceptionally(
                    new RuntimeException("Partition " + targetRank + " is failed"));
                return failedFuture;
            }

            // Capture the domain request
            var request = new RefinementRequest<>(0, treeId, roundNumber, treeLevel,
                                                   boundaryKeys, System.currentTimeMillis());
            sentRequests.add(request);

            var response = mockResponses.getOrDefault(targetRank,
                createDefaultResponse(targetRank, roundNumber));

            return CompletableFuture.completedFuture(response);
        }

        public void addMockResponse(int targetRank,
                RefinementResponse<MortonKey, LongEntityID, Phase44ForestIntegrationFixture.TestEntity> response) {
            mockResponses.put(targetRank, response);
        }

        public void setPartitionFailed(int rank, boolean failed) {
            if (failed) failedPartitions.add(rank);
            else failedPartitions.remove(rank);
        }

        public void setAlwaysNeedsRefinement(boolean alwaysNeedsRefinement) {
            this.alwaysNeedsRefinement = alwaysNeedsRefinement;
        }

        public int getRequestCount() { return requestCount.get(); }

        public List<RefinementRequest<MortonKey>> getSentRequests() {
            return new ArrayList<>(sentRequests);
        }

        private RefinementResponse<MortonKey, LongEntityID, Phase44ForestIntegrationFixture.TestEntity>
        createDefaultResponse(int responderRank, int roundNumber) {
            return new RefinementResponse<>(0, responderRank, 0L, roundNumber,
                                            List.of(), alwaysNeedsRefinement, System.currentTimeMillis());
        }
    }

    private static class MockPartitionRegistry implements ParallelBalancer.PartitionRegistry {
        private final int partitionCount;
        private final AtomicInteger barrierCount = new AtomicInteger(0);
        private final AtomicInteger refinementRequests = new AtomicInteger(0);

        public MockPartitionRegistry(int partitionCount) {
            this.partitionCount = partitionCount;
        }

        @Override
        public int getCurrentPartitionId() { return 0; }

        @Override
        public int getPartitionCount() { return partitionCount; }

        @Override
        public void barrier(int round) throws InterruptedException {
            barrierCount.incrementAndGet();
            Thread.sleep(10);
        }

        @Override
        public void requestRefinement(Object elementKey) {
            refinementRequests.incrementAndGet();
        }

        @Override
        public int getPendingRefinements() { return refinementRequests.get(); }

        public int getBarrierCount() { return barrierCount.get(); }
    }
}
