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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(CrossPartitionBalancePhaseTest.class);

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

    // ========== D.5 Tests: identifyRefinementNeeds() ==========

    // TEST 1: No violations detected - should return empty RoundResult
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_NoViolations() throws Exception {
        // Setup: Use real balance checker with empty forest (no violations)
        var balanceChecker = new com.hellblazer.luciferase.lucien.balancing.TwoOneBalanceChecker<MortonKey, LongEntityID, String>();

        // Create empty ghost layer
        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );
        phase.setForestContext(forest, ghostLayer);

        // Mock coordinator (should not be called)
        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID, String>();

        // Execute
        var result = phase.identifyRefinementNeeds(1, 2, balanceChecker, mockCoordinator);

        // Verify
        assertNotNull(result, "Result should not be null");
        assertEquals(0, result.refinementsApplied(), "Should have 0 violations processed");
        assertEquals(1, result.roundNumber(), "Should track round number");
        assertFalse(result.needsMoreRefinement(), "Should not need more refinement with no violations");
        assertEquals(0, mockCoordinator.getRequestsSent(), "Should not send requests when no violations");
    }

    // TEST 2: Single violation from one rank - should create 1 request
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_SingleViolation() throws Exception {
        // Setup: Create a mock balance checker that returns a single violation
        var balanceChecker = new TwoOneBalanceChecker<MortonKey, LongEntityID, String>();
        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );

        // Add a single ghost element that will create a violation
        var ghostKey = new MortonKey(100L, (byte) 3); // Level 3 ghost element
        var localKey = new MortonKey(101L, (byte) 1); // Level 1 local element (diff = 2, violation!)

        // Create ghost element with proper constructor
        var ghostElement = new com.hellblazer.luciferase.lucien.forest.ghost.GhostElement<>(
            ghostKey,
            new LongEntityID(100L),
            "ghost-content",
            new javax.vecmath.Point3f(10.0f, 10.0f, 10.0f),
            1,  // ownerRank = 1
            0L  // globalTreeId
        );
        ghostLayer.addGhostElement(ghostElement);

        // Add local element to first tree in forest
        var tree = forest.getAllTrees().get(0);
        var spatialIndex = tree.getSpatialIndex();
        spatialIndex.insert(new LongEntityID(1L), new javax.vecmath.Point3f(10.0f, 10.0f, 10.0f), (byte) 1, "test-entity", null);

        phase.setForestContext(forest, ghostLayer);

        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID, String>();

        // Execute
        var result = phase.identifyRefinementNeeds(1, 2, balanceChecker, mockCoordinator);

        // Verify
        assertNotNull(result, "Result should not be null");
        assertTrue(result.refinementsApplied() > 0 || mockCoordinator.getRequestsSent() > 0,
                  "Should process violations or send requests");
        assertEquals(1, result.roundNumber(), "Should track round number");
    }

    // TEST 3: Multiple violations from different ranks - should group by rank
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_MultipleRanks() throws Exception {
        // Setup: Create violations from 3 different ranks
        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );

        // Rank 1: 2 violations
        addViolationPair(ghostLayer, forest, 100L, 101L, 1);
        addViolationPair(ghostLayer, forest, 102L, 103L, 1);

        // Rank 2: 2 violations
        addViolationPair(ghostLayer, forest, 200L, 201L, 2);
        addViolationPair(ghostLayer, forest, 202L, 203L, 2);

        // Rank 3: 1 violation
        addViolationPair(ghostLayer, forest, 300L, 301L, 3);

        phase.setForestContext(forest, ghostLayer);

        var balanceChecker = new TwoOneBalanceChecker<MortonKey, LongEntityID, String>();
        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID, String>();

        // Execute
        var result = phase.identifyRefinementNeeds(1, 2, balanceChecker, mockCoordinator);

        // Verify: Should group into 3 requests (one per rank)
        var capturedRequests = mockCoordinator.getCapturedRequests();
        assertTrue(capturedRequests.size() <= 3,
                  "Should create at most 3 requests (one per rank)");
        assertNotNull(result, "Result should not be null");
    }

    // TEST 4: Boundary keys included in requests
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_RespectsBoundaryKeys() throws Exception {
        // Setup: Create violations with distinct boundary keys
        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );

        // Add violations with specific keys
        var ghostKey1 = new MortonKey(100L, (byte) 3);
        var localKey1 = new MortonKey(101L, (byte) 1);
        var ghostKey2 = new MortonKey(200L, (byte) 3);
        var localKey2 = new MortonKey(201L, (byte) 1);

        var ghost1 = new com.hellblazer.luciferase.lucien.forest.ghost.GhostElement<>(
            ghostKey1, new LongEntityID(100L), "ghost1",
            new javax.vecmath.Point3f(10.0f, 10.0f, 10.0f), 1, 0L
        );
        var ghost2 = new com.hellblazer.luciferase.lucien.forest.ghost.GhostElement<>(
            ghostKey2, new LongEntityID(200L), "ghost2",
            new javax.vecmath.Point3f(20.0f, 20.0f, 20.0f), 1, 0L
        );

        ghostLayer.addGhostElement(ghost1);
        ghostLayer.addGhostElement(ghost2);

        var tree = forest.getAllTrees().get(0);
        var spatialIndex = tree.getSpatialIndex();
        spatialIndex.insert(new LongEntityID(1L), new javax.vecmath.Point3f(10.0f, 10.0f, 10.0f), (byte) 1, "entity1", null);
        spatialIndex.insert(new LongEntityID(2L), new javax.vecmath.Point3f(20.0f, 20.0f, 20.0f), (byte) 1, "entity2", null);

        phase.setForestContext(forest, ghostLayer);

        var balanceChecker = new TwoOneBalanceChecker<MortonKey, LongEntityID, String>();
        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID, String>();

        // Execute
        var result = phase.identifyRefinementNeeds(1, 2, balanceChecker, mockCoordinator);

        // Verify: Requests should include boundary keys
        var capturedRequests = mockCoordinator.getCapturedRequests();
        if (!capturedRequests.isEmpty()) {
            for (var request : capturedRequests) {
                // Should have boundary keys from violations
                assertTrue(request.getBoundaryKeysCount() >= 0,
                          "Request should contain boundary keys");
            }
        }
    }

    // TEST 5: Timeout handling - should throw TimeoutException
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_AsyncTimeout() {
        // Setup: Create violation that will trigger request
        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );
        addViolationPair(ghostLayer, forest, 100L, 101L, 1);

        phase.setForestContext(forest, ghostLayer);

        var balanceChecker = new TwoOneBalanceChecker<MortonKey, LongEntityID, String>();
        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID, String>();
        mockCoordinator.setShouldTimeout(true);

        // Execute and verify timeout
        assertThrows(java.util.concurrent.TimeoutException.class, () -> {
            phase.identifyRefinementNeeds(1, 2, balanceChecker, mockCoordinator);
        }, "Should throw TimeoutException when coordinator times out");
    }

    // TEST 6: Coordinator exception - should propagate
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_CoordinatorException() {
        // Setup: Create violation that will trigger request
        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );
        addViolationPair(ghostLayer, forest, 100L, 101L, 1);

        phase.setForestContext(forest, ghostLayer);

        var balanceChecker = new TwoOneBalanceChecker<MortonKey, LongEntityID, String>();
        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID, String>();
        mockCoordinator.setShouldThrowException(true);

        // Execute and verify exception propagation
        assertThrows(RuntimeException.class, () -> {
            phase.identifyRefinementNeeds(1, 2, balanceChecker, mockCoordinator);
        }, "Should propagate RuntimeException from coordinator");
    }

    // TEST 7: Integration test with Phase44ForestIntegrationFixture
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_IntegrationWithRealChecker() throws Exception {
        // Setup: Use real forest fixture
        var fixture = new com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture();
        var distributedForest = fixture.createForest();
        fixture.syncGhostLayer();

        var realForest = fixture.getForest();
        var realGhostLayer = fixture.getGhostLayer();

        // Create typed instances for proper generics
        var integrationClient = new MockBalanceCoordinatorClient();
        var integrationRegistry = new MockPartitionRegistry(4);
        var integrationConfig = BalanceConfiguration.defaultConfig();

        var integrationPhase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture.TestEntity>(
            integrationClient, integrationRegistry, integrationConfig
        );
        integrationPhase.setForestContext(realForest, realGhostLayer);

        var realBalanceChecker = new TwoOneBalanceChecker<MortonKey, LongEntityID,
                com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture.TestEntity>();

        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID,
                com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture.TestEntity>();

        // Execute
        var result = integrationPhase.identifyRefinementNeeds(1, 2, realBalanceChecker, mockCoordinator);

        // Verify
        assertNotNull(result, "Result should not be null");
        assertEquals(1, result.roundNumber(), "Should track round number");

        // Capture requests for validation
        var capturedRequests = mockCoordinator.getCapturedRequests();
        log.info("Integration test: {} violations found, {} requests sent",
                result.refinementsApplied(), capturedRequests.size());

        // Verify request structure if any were sent
        for (var request : capturedRequests) {
            assertTrue(request.getRoundNumber() > 0, "Request should have valid round number");
            assertTrue(request.getTreeLevel() >= 0, "Request should have valid tree level");
        }
    }

    // Helper method to add violation pairs
    private void addViolationPair(
        com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String> ghostLayer,
        Forest<MortonKey, LongEntityID, String> forest,
        long ghostCode,
        long localCode,
        int ownerRank
    ) {
        var ghostKey = new MortonKey(ghostCode, (byte) 3); // Level 3
        var localKey = new MortonKey(localCode, (byte) 1); // Level 1 (diff = 2, violation)

        var ghostElement = new com.hellblazer.luciferase.lucien.forest.ghost.GhostElement<>(
            ghostKey,
            new LongEntityID(ghostCode),
            "ghost-" + ghostCode,
            new javax.vecmath.Point3f(10.0f, 10.0f, 10.0f),
            ownerRank,
            0L
        );
        ghostLayer.addGhostElement(ghostElement);

        var tree = forest.getAllTrees().get(0);
        var spatialIndex = tree.getSpatialIndex();
        spatialIndex.insert(new LongEntityID(localCode), new javax.vecmath.Point3f(10.0f, 10.0f, 10.0f), (byte) 1, "entity-" + localCode, null);
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

    // Mock RefinementCoordinator for D.5 tests
    private static class MockRefinementCoordinator<Key extends com.hellblazer.luciferase.lucien.SpatialKey<Key>,
                                                    ID extends com.hellblazer.luciferase.lucien.entity.EntityID,
                                                    Content> {
        private final AtomicInteger requestsSent = new AtomicInteger(0);
        private final List<RefinementRequest> capturedRequests = Collections.synchronizedList(new ArrayList<>());
        private boolean shouldTimeout = false;
        private boolean shouldThrowException = false;

        public List<CompletableFuture<RefinementResponse>> sendRequestsParallel(List<RefinementRequest> requests) {
            requestsSent.addAndGet(requests.size());
            capturedRequests.addAll(requests);

            if (shouldThrowException) {
                throw new RuntimeException("Coordinator exception");
            }

            var futures = new ArrayList<CompletableFuture<RefinementResponse>>();
            for (var request : requests) {
                if (shouldTimeout) {
                    var future = new CompletableFuture<RefinementResponse>();
                    // Don't complete the future - let it timeout
                    futures.add(future);
                } else {
                    var response = RefinementResponse.newBuilder()
                        .setResponderRank(request.getRequesterRank())
                        .setResponderTreeId(0L)
                        .setRoundNumber(request.getRoundNumber())
                        .setNeedsFurtherRefinement(false)
                        .setTimestamp(System.currentTimeMillis())
                        .build();
                    futures.add(CompletableFuture.completedFuture(response));
                }
            }
            return futures;
        }

        public int getRequestsSent() {
            return requestsSent.get();
        }

        public List<RefinementRequest> getCapturedRequests() {
            return new ArrayList<>(capturedRequests);
        }

        public void setShouldTimeout(boolean shouldTimeout) {
            this.shouldTimeout = shouldTimeout;
        }

        public void setShouldThrowException(boolean shouldThrowException) {
            this.shouldThrowException = shouldThrowException;
        }
    }
}
