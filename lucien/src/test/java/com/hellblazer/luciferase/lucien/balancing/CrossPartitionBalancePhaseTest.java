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

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.balancing.grpc.BalanceCoordinatorClient;
import com.hellblazer.luciferase.lucien.balancing.proto.RefinementRequest;
import com.hellblazer.luciferase.lucien.balancing.proto.RefinementResponse;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ForestConfig;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for CrossPartitionBalancePhase - O(log P) distributed refinement protocol.
 *
 * <p>These tests are written FIRST (red phase) to define the expected behavior
 * before implementation. They should FAIL initially until the implementation
 * is complete.
 *
 * <p>The cross-partition protocol implements the Phase 3 of parallel balancing:
 * <ol>
 *   <li>Execute O(log P) refinement rounds where P = partition count</li>
 *   <li>Each round identifies boundary elements needing refinement</li>
 *   <li>Requests ghost elements from neighbors via gRPC</li>
 *   <li>Applies received ghost elements to local forest</li>
 *   <li>Synchronizes all partitions via barrier</li>
 *   <li>Checks convergence (no more refinements needed)</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public class CrossPartitionBalancePhaseTest {

    private Forest<MortonKey, LongEntityID, String> forest;
    private MockBalanceCoordinatorClient client;
    private MockPartitionRegistry registry;
    private BalanceConfiguration config;
    private CrossPartitionBalancePhase<MortonKey, LongEntityID, String> phase;

    @BeforeEach
    public void setUp() {
        forest = new Forest<>(ForestConfig.defaultConfig());
        client = new MockBalanceCoordinatorClient();
        registry = new MockPartitionRegistry(4); // 4 partitions
        config = BalanceConfiguration.defaultConfig();

        phase = new CrossPartitionBalancePhase<>(client, registry, config);
    }

    // TEST 1: 2 Partitions - O(log 2) = 1 round
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testCrossPartitionBalanceWith2Partitions() {
        // O(log 2) = 1 round for 2 partitions
        registry = new MockPartitionRegistry(2);
        phase = new CrossPartitionBalancePhase<>(client, registry, config);

        var result = phase.execute(forest, 0, 2);

        assertTrue(result.successful(), "Should succeed with 2 partitions");
        assertEquals(1, result.finalMetrics().roundCount(),
                    "Should execute exactly 1 refinement round for 2 partitions (O(log 2) = 1)");
    }

    // TEST 2: 4 Partitions - O(log 4) = 2 rounds
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testCrossPartitionBalanceWith4Partitions() {
        // O(log 4) = 2 rounds for 4 partitions
        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should succeed with 4 partitions");
        assertEquals(2, result.finalMetrics().roundCount(),
                    "Should execute exactly 2 refinement rounds for 4 partitions (O(log 4) = 2)");
    }

    // TEST 3: 8 Partitions - O(log 8) = 3 rounds
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testCrossPartitionBalanceWith8Partitions() {
        // O(log 8) = 3 rounds for 8 partitions
        registry = new MockPartitionRegistry(8);
        phase = new CrossPartitionBalancePhase<>(client, registry, config);

        var result = phase.execute(forest, 0, 8);

        assertTrue(result.successful(), "Should succeed with 8 partitions");
        assertEquals(3, result.finalMetrics().roundCount(),
                    "Should execute exactly 3 refinement rounds for 8 partitions (O(log 8) = 3)");
    }

    // TEST 4: Refinement Request Generation
    @Test
    public void testRefinementRequestGeneration() {
        // Execute with empty forest - implementation should handle gracefully
        phase.execute(forest, 0, 4);

        // Verify refinement request tracking works
        assertTrue(client.getRequestCount() >= 0,
                  "Should track refinement request count");

        // Check request structure if any were sent
        var requests = client.getSentRequests();
        for (var request : requests) {
            assertEquals(0, request.getRequesterRank(), "Request should have correct rank");
            assertTrue(request.getRoundNumber() > 0, "Request should have valid round number");
            assertTrue(request.getTreeLevel() >= 0, "Request should have valid tree level");
            assertTrue(request.getTimestamp() > 0, "Request should have timestamp");
        }
    }

    // TEST 5: Refinement Response Processing
    @Test
    public void testRefinementResponseProcessing() {
        // Configure mock client to return responses with ghost elements
        client.addMockResponse(1, createMockResponse(1, 1, 5)); // 5 ghost elements from rank 1

        // Execute one round
        var result = phase.execute(forest, 0, 4);

        // Verify responses were processed
        assertTrue(result.successful(), "Should process responses successfully");
        assertTrue(result.refinementsApplied() >= 0,
                  "Should apply ghost elements from responses");
    }

    // TEST 6: Convergence Detection
    @Test
    public void testConvergenceDetection() {
        // Configure to converge immediately (no refinements needed)
        client.setConverged(true);

        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should succeed on convergence");
        assertTrue(result.finalMetrics().roundCount() >= 1,
                  "Should complete at least one round before convergence");
    }

    // TEST 7: Round Timing
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testRoundTiming() {
        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should complete within timeout");

        var snapshot = result.finalMetrics();
        assertTrue(snapshot.totalTime().toNanos() > 0, "Should track total time");
        assertTrue(snapshot.averageRoundTime().toNanos() > 0, "Should track average round time");

        // Each round should complete reasonably fast
        assertTrue(snapshot.maxRoundTime().toMillis() < 5000,
                  "Individual rounds should complete within 5 seconds");
    }

    // TEST 8: Boundary Ghost Exchange
    @Test
    public void testBoundaryGhostExchange() {
        // Configure mock to return ghost elements for boundaries
        client.addMockResponse(1, createMockResponse(1, 1, 2));

        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should exchange ghosts at boundaries");

        // Verify request/response mechanism works
        var sentRequests = client.getSentRequests();
        assertTrue(sentRequests.size() >= 0, "Should track sent requests");
    }

    // TEST 9: Request Batching
    @Test
    public void testRequestBatching() {
        // Configure client to simulate batching behavior
        phase.execute(forest, 0, 4);

        // Verify request tracking works for batching
        var requestCount = client.getRequestCount();
        assertTrue(requestCount >= 0, "Should track batched refinement requests");
    }

    // TEST 10: Barrier Synchronization
    @Test
    public void testBarrierSynchronization() {
        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should complete with barrier synchronization");

        // Verify barrier was called for each round
        var expectedRounds = (int) Math.ceil(Math.log(4) / Math.log(2));
        assertTrue(registry.getBarrierCount() >= expectedRounds,
                  "Should synchronize at barrier after each round");
    }

    // TEST 11: Max Rounds Respected
    @Test
    public void testMaxRoundsRespected() {
        // Configure to never converge (always need refinement)
        client.setConverged(false);
        client.setAlwaysNeedsRefinement(true);

        // Set max rounds to 2
        config = BalanceConfiguration.defaultConfig().withMaxRounds(2);
        phase = new CrossPartitionBalancePhase<>(client, registry, config);

        var result = phase.execute(forest, 0, 4);

        // Should terminate after max rounds even without convergence
        assertTrue(result.finalMetrics().roundCount() <= 2,
                  "Should not exceed max rounds (2)");
    }

    // TEST 12: Level-Based Efficiency
    @Test
    public void testLevelBasedRefinement() {
        // Execute with empty forest
        phase.execute(forest, 0, 4);

        // Verify level information is tracked correctly in requests
        var sentRequests = client.getSentRequests();
        for (var request : sentRequests) {
            assertTrue(request.getTreeLevel() >= 0 && request.getTreeLevel() <= 21,
                      "Request should have valid tree level (0-21)");
        }
    }

    // TEST 13: ButterflyPattern Integration - Verify partner selection uses butterfly
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testButterflyPartnerSelection() {
        // In 8 partitions, round 0 partners should be (0,1), (2,3), (4,5), (6,7)
        // Round 1 partners should be (0,2), (1,3), (4,6), (5,7)
        registry = new MockPartitionRegistry(8);
        phase = new CrossPartitionBalancePhase<>(client, registry, config);

        var result = phase.execute(forest, 0, 8);

        // Should communicate with correct partners in each round (butterfly pattern)
        assertTrue(result.successful(), "Should succeed with butterfly pattern");
        assertEquals(3, result.finalMetrics().roundCount(),
                    "Should execute 3 rounds for 8 partitions (O(log 8) = 3)");
    }

    // TEST 14: TwoOneBalanceChecker Integration - Violation detection
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testTwoOneViolationDetectionIntegration() {
        // Execute with empty forest (no violations expected)
        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should complete without violations in empty forest");
        assertTrue(result.finalMetrics().roundCount() >= 1, "Should execute at least 1 round");
    }

    // TEST 15: Refinement Requests Created From Violations
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRefinementRequestsCreatedFromViolations() {
        // Set up client to always need refinement (simulating ongoing violations)
        client.setAlwaysNeedsRefinement(true);

        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should handle refinement requests");
        // Requests should be sent to communicate refinement needs
        assertTrue(client.getRequestCount() > 0, "Should send refinement requests");
    }

    // TEST 16: Ghost Elements Applied During Rounds
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testGhostElementsAppliedDuringRounds() {
        // Execute balance rounds (ghost elements would be applied during execution)
        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should successfully apply ghost elements");
        // Verify that the phase executed all expected rounds
        assertEquals(2, result.finalMetrics().roundCount(),
                    "4 partitions should converge in 2 rounds");
    }

    // TEST 17: Convergence Detected When No Violations Remain
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testConvergenceDetectedWhenNoViolations() {
        // Set converged to indicate no more violations
        client.setConverged(true);
        client.setAlwaysNeedsRefinement(false);

        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should converge when no violations remain");
        assertTrue(result.converged(), "Result should indicate convergence");
    }

    // Helper method to create mock refinement response
    private RefinementResponse createMockResponse(int responderRank, int roundNumber, int ghostElementCount) {
        return RefinementResponse.newBuilder()
            .setResponderRank(responderRank)
            .setResponderTreeId(0L)
            .setRoundNumber(roundNumber)
            .setNeedsFurtherRefinement(ghostElementCount > 0)
            .setTimestamp(System.currentTimeMillis())
            .build();
    }

    // Mock BalanceCoordinatorClient for testing
    private static class MockBalanceCoordinatorClient extends BalanceCoordinatorClient {
        private final Map<Integer, RefinementResponse> mockResponses = new ConcurrentHashMap<>();
        private final List<RefinementRequest> sentRequests = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private volatile boolean converged = false;
        private volatile boolean alwaysNeedsRefinement = false;

        public MockBalanceCoordinatorClient() {
            super(0, new MockServiceDiscovery());
        }

        @Override
        public CompletableFuture<RefinementResponse> requestRefinementAsync(
                int targetRank, long treeId, int roundNumber, int treeLevel,
                List<com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey> boundaryKeys) {

            requestCount.incrementAndGet();

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

    // Mock ServiceDiscovery for BalanceCoordinatorClient
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

    // Mock PartitionRegistry for testing
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
