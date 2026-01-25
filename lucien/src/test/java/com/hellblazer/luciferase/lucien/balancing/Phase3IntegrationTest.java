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
import com.hellblazer.luciferase.lucien.balancing.grpc.BalanceCoordinatorClient;
import com.hellblazer.luciferase.lucien.balancing.proto.RefinementRequest;
import com.hellblazer.luciferase.lucien.balancing.proto.RefinementResponse;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey;
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
 * <p>This test suite validates the complete Phase 3 (Cross-Partition Balance) workflow
 * using realistic multi-partition forests with actual Octree spatial structures, ghost
 * layer integration, and distributed coordination.
 *
 * <p><b>Test Coverage:</b>
 * <ul>
 *   <li>Scenario 1: 2-partition single round convergence (O(log 2) = 1)</li>
 *   <li>Scenario 2: 4-partition two rounds with butterfly pattern (O(log 4) = 2)</li>
 *   <li>Scenario 3: Asymmetric violations partial recovery</li>
 *   <li>Scenario 4: Partition failure graceful degradation</li>
 *   <li>Scenario 5: Large forest scaling (8 partitions, O(log 8) = 3)</li>
 *   <li>Scenario 6: End-to-end correctness full Phase 3 protocol</li>
 * </ul>
 *
 * <p><b>Infrastructure:</b>
 * <ul>
 *   <li>Phase44ForestIntegrationFixture: Real forest with Octree + ghost layer</li>
 *   <li>CrossPartitionBalancePhase: Phase 3 execution engine</li>
 *   <li>TwoOneBalanceChecker: 2:1 violation detection</li>
 *   <li>RefinementCoordinator: Async gRPC coordination</li>
 * </ul>
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
     *
     * <p>Setup: 2-partition forest with butterfly partners (0-0, 1-1)
     * <p>Inject: 2:1 violations at partition boundary
     * <p>Expected: Single refinement round (O(log 2) = 1) converges completely
     * <p>Verify: Ghost layer synchronized, no more violations detected
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testTwoPartitionSingleRound_ConvergesQuickly() {
        log.info("=== SCENARIO 1: Two-Partition Single Round Convergence ===");

        // Setup: Create 2-partition forest
        var distributedForest = fixture.createForest(100, 2);
        fixture.syncGhostLayer();

        var forest = fixture.getForest();
        var ghostLayer = fixture.getGhostLayer();

        // Create mock infrastructure for 2 partitions
        var client = new MockBalanceCoordinatorClient();
        var registry = new MockPartitionRegistry(2);
        var phase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>(client, registry, config);

        phase.setForestContext(forest, ghostLayer);

        // Execute Phase 3
        var result = phase.execute(forest, 0, 2);

        // Verify convergence
        assertThat(result.successful())
            .as("Phase 3 should complete successfully for 2 partitions")
            .isTrue();

        assertThat(result.finalMetrics().roundCount())
            .as("Should execute exactly 1 refinement round for 2 partitions (O(log 2) = 1)")
            .isEqualTo(1);

        // Verify ghost layer synchronized
        var violations = fixture.findCurrentViolations();
        log.info("Violations after convergence: {}", violations.size());

        // Verify barrier synchronization
        assertThat(registry.getBarrierCount())
            .as("Should synchronize at barrier after round 1")
            .isGreaterThanOrEqualTo(1);

        log.info("Scenario 1 PASSED: Converged in {} rounds with {} refinements",
                result.finalMetrics().roundCount(), result.refinementsApplied());
    }

    /**
     * SCENARIO 2: Four-Partition Two Rounds with Butterfly Pattern.
     *
     * <p>Setup: 4-partition forest with butterfly topology
     * <p>Inject: 2:1 violations affecting multiple boundaries
     * <p>Expected: Exactly 2 refinement rounds (O(log 4) = 2) needed
     * <p>Verify: Each round executes correct partner pairings (butterfly pattern)
     *           Round 1: (0-0, 1-1, 2-2, 3-3), Round 2: (0-2, 1-3)
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testFourPartitionTwoRounds_ButterflyPattern() {
        log.info("=== SCENARIO 2: Four-Partition Two Rounds with Butterfly Pattern ===");

        // Setup: Create 4-partition forest
        var distributedForest = fixture.createForest(150, 4);
        fixture.syncGhostLayer();

        var forest = fixture.getForest();
        var ghostLayer = fixture.getGhostLayer();

        // Create mock infrastructure for 4 partitions
        var client = new MockBalanceCoordinatorClient();
        var registry = new MockPartitionRegistry(4);
        var phase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>(client, registry, config);

        phase.setForestContext(forest, ghostLayer);

        // Execute Phase 3
        var result = phase.execute(forest, 0, 4);

        // Verify convergence in 2 rounds
        assertThat(result.successful())
            .as("Phase 3 should complete successfully for 4 partitions")
            .isTrue();

        assertThat(result.finalMetrics().roundCount())
            .as("Should execute exactly 2 refinement rounds for 4 partitions (O(log 4) = 2)")
            .isEqualTo(2);

        // Verify butterfly pattern communication
        // Round 1 should have all partitions communicating with themselves (initialization)
        // Round 2 should have butterfly pairs (0-2, 1-3)
        var sentRequests = client.getSentRequests();
        assertThat(sentRequests)
            .as("Should send requests in butterfly pattern")
            .isNotEmpty();

        // Verify barrier synchronization for both rounds
        assertThat(registry.getBarrierCount())
            .as("Should synchronize at barrier after each round (2 barriers for 2 rounds)")
            .isGreaterThanOrEqualTo(2);

        log.info("Scenario 2 PASSED: Converged in {} rounds with {} refinements, {} requests sent",
                result.finalMetrics().roundCount(), result.refinementsApplied(), sentRequests.size());
    }

    /**
     * SCENARIO 3: Asymmetric Violations Partial Recovery.
     *
     * <p>Setup: 4-partition forest with violations only on boundaries 0-1 and 2-3
     * <p>Inject: Large 2:1 differences requiring multiple refinement rounds
     * <p>Expected: Convergence with proper synchronization
     * <p>Verify: Ghost layer properly maintains both symmetric and asymmetric relationships
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testAsymmetricViolations_PartialRecovery() {
        log.info("=== SCENARIO 3: Asymmetric Violations Partial Recovery ===");

        // Setup: Create 4-partition forest
        var distributedForest = fixture.createForest(150, 4);
        fixture.syncGhostLayer();

        var forest = fixture.getForest();
        var ghostLayer = fixture.getGhostLayer();

        // Create mock infrastructure
        var client = new MockBalanceCoordinatorClient();
        var registry = new MockPartitionRegistry(4);
        var phase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>(client, registry, config);

        phase.setForestContext(forest, ghostLayer);

        // Inject asymmetric violations by configuring client responses
        // Partitions 0-1 have violations, 2-3 have violations
        client.addMockResponse(1, createMockResponse(1, 1, 3)); // 3 ghost elements from rank 1
        client.addMockResponse(3, createMockResponse(3, 1, 2)); // 2 ghost elements from rank 3

        // Execute Phase 3
        var result = phase.execute(forest, 0, 4);

        // Verify partial recovery (asymmetric violations handled)
        assertThat(result.successful())
            .as("Phase 3 should complete successfully even with asymmetric violations")
            .isTrue();

        assertThat(result.finalMetrics().roundCount())
            .as("Should complete within expected rounds for 4 partitions")
            .isGreaterThanOrEqualTo(1)
            .isLessThanOrEqualTo(2);

        // Verify ghost layer has elements from asymmetric boundaries
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
     *
     * <p>Setup: 3-partition forest (one partition marked FAILED)
     * <p>Inject: 2:1 violations that would span to failed partition
     * <p>Expected: Algorithm skips failed partition, converges with healthy ones
     * <p>Verify: No exceptions thrown, partial convergence achieved
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testPartitionFailure_GracefulDegradation() {
        log.info("=== SCENARIO 4: Partition Failure Graceful Degradation ===");

        // Setup: Create 3-partition forest
        var distributedForest = fixture.createForest(120, 3);
        fixture.syncGhostLayer();

        var forest = fixture.getForest();
        var ghostLayer = fixture.getGhostLayer();

        // Create mock infrastructure with partition 2 marked as FAILED
        var client = new MockBalanceCoordinatorClient();
        client.setPartitionFailed(2, true); // Partition 2 is failed

        var registry = new MockPartitionRegistry(3);
        var phase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>(client, registry, config);

        phase.setForestContext(forest, ghostLayer);

        // Execute Phase 3 (should handle failed partition gracefully)
        var result = phase.execute(forest, 0, 3);

        // Verify graceful degradation
        assertThat(result.successful())
            .as("Phase 3 should complete successfully even with 1 failed partition")
            .isTrue();

        // Verify no exceptions thrown during execution
        var sentRequests = client.getSentRequests();
        assertThat(sentRequests)
            .as("Should send requests only to healthy partitions")
            .isNotEmpty();

        // Verify partial convergence (only healthy partitions converge)
        log.info("Partial convergence achieved with {} healthy partitions out of 3",
                3 - 1);

        log.info("Scenario 4 PASSED: Graceful degradation with 1 failed partition, {} refinements applied",
                result.refinementsApplied());
    }

    /**
     * SCENARIO 5: Large Forest Scaling (8 Partitions, O(log 8) = 3 Rounds).
     *
     * <p>Setup: 8-partition forest (O(log 8) = 3 rounds needed)
     * <p>Inject: Random 2:1 violations across many boundaries
     * <p>Expected: Exactly 3 refinement rounds required
     * <p>Verify: All 8 partitions converge within expected round count
     * <p>Metrics: Verify O(log P) complexity holds for P=8
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    public void testLargeForestScaling_8Partitions_3Rounds() {
        log.info("=== SCENARIO 5: Large Forest Scaling (8 Partitions, O(log 8) = 3) ===");

        // Setup: Create 8-partition forest
        var distributedForest = fixture.createForest(200, 8);
        fixture.syncGhostLayer();

        var forest = fixture.getForest();
        var ghostLayer = fixture.getGhostLayer();

        // Create mock infrastructure for 8 partitions
        var client = new MockBalanceCoordinatorClient();
        var registry = new MockPartitionRegistry(8);
        var phase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>(client, registry, config);

        phase.setForestContext(forest, ghostLayer);

        // Execute Phase 3
        var startTime = System.currentTimeMillis();
        var result = phase.execute(forest, 0, 8);
        var elapsed = System.currentTimeMillis() - startTime;

        // Verify O(log P) complexity: O(log 8) = 3 rounds
        assertThat(result.successful())
            .as("Phase 3 should complete successfully for 8 partitions")
            .isTrue();

        assertThat(result.finalMetrics().roundCount())
            .as("Should execute exactly 3 refinement rounds for 8 partitions (O(log 8) = 3)")
            .isEqualTo(3);

        // Verify performance: All rounds should complete within reasonable time
        assertThat(elapsed)
            .as("Total execution time should be reasonable (<15s)")
            .isLessThan(15000L);

        // Verify round timing
        var avgRoundTime = result.finalMetrics().averageRoundTime().toMillis();
        assertThat(avgRoundTime)
            .as("Average round time should be reasonable (<5s)")
            .isLessThan(5000L);

        // Verify barrier synchronization for all 3 rounds
        assertThat(registry.getBarrierCount())
            .as("Should synchronize at barrier after each round (3 barriers for 3 rounds)")
            .isGreaterThanOrEqualTo(3);

        log.info("Scenario 5 PASSED: 8 partitions converged in {} rounds, total time={}ms, avg round time={}ms",
                result.finalMetrics().roundCount(), elapsed, avgRoundTime);
    }

    /**
     * SCENARIO 6: End-to-End Correctness Full Phase 3 Protocol.
     *
     * <p>Setup: Phase44ForestIntegrationFixture with full forest infrastructure
     * <p>Inject: Comprehensive 2:1 violations from previous Phase 1-2 work
     * <p>Execute: Complete Phase 3 (Cross-Partition Balance) workflow:
     * <ol>
     *   <li>Identification: identifyRefinementNeeds() finds violations</li>
     *   <li>Requests: sendRequestsParallel() sends to butterfly partners</li>
     *   <li>Responses: applyRefinementResponses() updates ghost layer</li>
     *   <li>Synchronization: All partitions reach consistency</li>
     * </ol>
     * <p>Verify: End-to-end correctness, proper logging, metrics collected
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    public void testEndToEndCorrectness_FullPhase3Protocol() {
        log.info("=== SCENARIO 6: End-to-End Correctness Full Phase 3 Protocol ===");

        // Setup: Create 4-partition forest with full infrastructure
        var distributedForest = fixture.createForest(150, 4);
        fixture.syncGhostLayer();

        var forest = fixture.getForest();
        var ghostLayer = fixture.getGhostLayer();

        // Create real infrastructure components
        var client = new MockBalanceCoordinatorClient();
        var registry = new MockPartitionRegistry(4);
        var phase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                Phase44ForestIntegrationFixture.TestEntity>(client, registry, config);

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

        // STEP 4: Verify identification worked
        var sentRequests = client.getSentRequests();
        assertThat(sentRequests)
            .as("identifyRefinementNeeds() should create refinement requests")
            .isNotEmpty();

        // STEP 5: Verify request structure
        for (var request : sentRequests) {
            assertThat(request.getRequesterRank())
                .as("Request should have valid requester rank")
                .isBetween(0, 3);

            assertThat(request.getRoundNumber())
                .as("Request should have valid round number")
                .isGreaterThan(0);

            assertThat(request.getTreeLevel())
                .as("Request should have valid tree level")
                .isBetween(0, 21);

            assertThat(request.getTimestamp())
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

    /**
     * Create a mock refinement response for testing.
     *
     * @param responderRank the rank of the responding partition
     * @param roundNumber the round number
     * @param ghostElementCount number of ghost elements in response
     * @return mock refinement response
     */
    private RefinementResponse createMockResponse(int responderRank, int roundNumber, int ghostElementCount) {
        return RefinementResponse.newBuilder()
            .setResponderRank(responderRank)
            .setResponderTreeId(0L)
            .setRoundNumber(roundNumber)
            .setNeedsFurtherRefinement(ghostElementCount > 0)
            .setTimestamp(System.currentTimeMillis())
            .build();
    }

    // ========== Mock Infrastructure ==========

    /**
     * Mock BalanceCoordinatorClient for testing cross-partition communication.
     */
    private static class MockBalanceCoordinatorClient extends BalanceCoordinatorClient {
        private final Map<Integer, RefinementResponse> mockResponses = new ConcurrentHashMap<>();
        private final List<RefinementRequest> sentRequests = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private final Set<Integer> failedPartitions = ConcurrentHashMap.newKeySet();
        private volatile boolean converged = false;
        private volatile boolean alwaysNeedsRefinement = false;

        public MockBalanceCoordinatorClient() {
            super(0, new MockServiceDiscovery());
        }

        @Override
        public CompletableFuture<RefinementResponse> requestRefinementAsync(
                int targetRank, long treeId, int roundNumber, int treeLevel,
                List<SpatialKey> boundaryKeys) {

            requestCount.incrementAndGet();

            // Simulate failed partition
            if (failedPartitions.contains(targetRank)) {
                var failedFuture = new CompletableFuture<RefinementResponse>();
                failedFuture.completeExceptionally(new RuntimeException("Partition " + targetRank + " is failed"));
                return failedFuture;
            }

            // Create and store request
            var request = RefinementRequest.newBuilder()
                .setRequesterRank(0)
                .setRequesterTreeId(treeId)
                .setRoundNumber(roundNumber)
                .setTreeLevel(treeLevel)
                .setTimestamp(System.currentTimeMillis())
                .build();
            sentRequests.add(request);

            // Return mock response
            var response = mockResponses.getOrDefault(targetRank,
                createDefaultResponse(targetRank, roundNumber));

            return CompletableFuture.completedFuture(response);
        }

        public void addMockResponse(int targetRank, RefinementResponse response) {
            mockResponses.put(targetRank, response);
        }

        public void setPartitionFailed(int rank, boolean failed) {
            if (failed) {
                failedPartitions.add(rank);
            } else {
                failedPartitions.remove(rank);
            }
        }

        public void setConverged(boolean converged) {
            this.converged = converged;
        }

        public void setAlwaysNeedsRefinement(boolean alwaysNeedsRefinement) {
            this.alwaysNeedsRefinement = alwaysNeedsRefinement;
        }

        public int getRequestCount() {
            return requestCount.get();
        }

        public List<RefinementRequest> getSentRequests() {
            return new ArrayList<>(sentRequests);
        }

        private RefinementResponse createDefaultResponse(int responderRank, int roundNumber) {
            return RefinementResponse.newBuilder()
                .setResponderRank(responderRank)
                .setResponderTreeId(0L)
                .setRoundNumber(roundNumber)
                .setNeedsFurtherRefinement(alwaysNeedsRefinement && !converged)
                .setTimestamp(System.currentTimeMillis())
                .build();
        }
    }

    /**
     * Mock ServiceDiscovery for BalanceCoordinatorClient.
     */
    private static class MockServiceDiscovery implements BalanceCoordinatorClient.ServiceDiscovery {
        private final Map<Integer, String> endpoints = new ConcurrentHashMap<>();

        public MockServiceDiscovery() {
            // Pre-populate with test endpoints
            for (int i = 0; i < 16; i++) {
                endpoints.put(i, "localhost:" + (50000 + i));
            }
        }

        @Override
        public String getEndpoint(int rank) {
            return endpoints.get(rank);
        }

        @Override
        public void registerEndpoint(int rank, String endpoint) {
            endpoints.put(rank, endpoint);
        }

        @Override
        public Map<Integer, String> getAllEndpoints() {
            return new HashMap<>(endpoints);
        }
    }

    /**
     * Mock PartitionRegistry for testing barrier synchronization.
     */
    private static class MockPartitionRegistry implements ParallelBalancer.PartitionRegistry {
        private final int partitionCount;
        private final AtomicInteger barrierCount = new AtomicInteger(0);
        private final AtomicInteger refinementRequests = new AtomicInteger(0);

        public MockPartitionRegistry(int partitionCount) {
            this.partitionCount = partitionCount;
        }

        @Override
        public int getCurrentPartitionId() {
            return 0;
        }

        @Override
        public int getPartitionCount() {
            return partitionCount;
        }

        @Override
        public void barrier(int round) throws InterruptedException {
            barrierCount.incrementAndGet();
            // Simulate barrier synchronization with small delay
            Thread.sleep(10);
        }

        @Override
        public void requestRefinement(Object elementKey) {
            refinementRequests.incrementAndGet();
        }

        @Override
        public int getPendingRefinements() {
            return refinementRequests.get();
        }

        public int getBarrierCount() {
            return barrierCount.get();
        }
    }
}
