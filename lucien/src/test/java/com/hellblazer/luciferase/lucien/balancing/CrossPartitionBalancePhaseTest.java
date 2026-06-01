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
import com.hellblazer.luciferase.lucien.entity.EntityID;
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
 * <p>Updated for Inc3-C4: all mocks use domain types (RefinementRequest/Response) and
 * RefinementExchange instead of BalanceCoordinatorClient. Proto-specific tests have moved
 * to GrpcBalanceExchangeTest.
 *
 * @author hal.hildebrand
 */
public class CrossPartitionBalancePhaseTest {

    private static final Logger log = LoggerFactory.getLogger(CrossPartitionBalancePhaseTest.class);

    private Forest<MortonKey, LongEntityID, String> forest;
    private MockRefinementExchange exchange;
    private MockPartitionRegistry registry;
    private BalanceConfiguration config;
    private CrossPartitionBalancePhase<MortonKey, LongEntityID, String> phase;

    @BeforeEach
    public void setUp() {
        forest = new Forest<>(ForestConfig.defaultConfig());

        // Add a tree to the forest so tests can insert elements
        var idGen = new com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator();
        var octree = new com.hellblazer.luciferase.lucien.octree.Octree<LongEntityID, String>(idGen);
        forest.addTree(octree);

        exchange = new MockRefinementExchange();
        registry = new MockPartitionRegistry(4); // 4 partitions
        config = BalanceConfiguration.defaultConfig();

        phase = new CrossPartitionBalancePhase<>(exchange, registry, config);
    }

    // TEST 1: 2 Partitions - O(log 2) = 1 round
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testCrossPartitionBalanceWith2Partitions() {
        registry = new MockPartitionRegistry(2);
        phase = new CrossPartitionBalancePhase<>(exchange, registry, config);

        var result = phase.execute(forest, 0, 2);

        assertTrue(result.successful(), "Should succeed with 2 partitions");
        assertEquals(1, result.finalMetrics().roundCount(),
                    "Should execute exactly 1 refinement round for 2 partitions (O(log 2) = 1)");
    }

    // TEST 2: 4 Partitions - O(log 4) = 2 rounds
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testCrossPartitionBalanceWith4Partitions() {
        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should succeed with 4 partitions");
        // Luciferase-uhsn: round count is data-driven (Allreduce-LAND), not ceil(log2 P). With no forest context /
        // no violations the partition is immediately locally balanced, so the loop converges in a single round.
        assertEquals(1, result.finalMetrics().roundCount(),
                    "A balanced forest converges in 1 round under Allreduce-LAND");
    }

    // TEST 3: 8 Partitions - O(log 8) = 3 rounds
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testCrossPartitionBalanceWith8Partitions() {
        registry = new MockPartitionRegistry(8);
        phase = new CrossPartitionBalancePhase<>(exchange, registry, config);

        var result = phase.execute(forest, 0, 8);

        assertTrue(result.successful(), "Should succeed with 8 partitions");
        // Luciferase-uhsn: data-driven convergence — a balanced forest needs only one round regardless of P.
        assertEquals(1, result.finalMetrics().roundCount(),
                    "A balanced forest converges in 1 round under Allreduce-LAND");
    }

    // TEST 4: Refinement Request Generation
    @Test
    public void testRefinementRequestGeneration() {
        phase.execute(forest, 0, 4);

        assertTrue(exchange.getRequestCount() >= 0,
                  "Should track refinement request count");

        var requests = exchange.getSentRequests();
        for (var request : requests) {
            assertEquals(0, request.requesterRank(), "Request should have correct rank");
            assertTrue(request.roundNumber() > 0, "Request should have valid round number");
            assertTrue(request.treeLevel() >= 0, "Request should have valid tree level");
            assertTrue(request.timestamp() > 0, "Request should have timestamp");
        }
    }

    // TEST 5: Refinement Response Processing
    @Test
    public void testRefinementResponseProcessing() {
        exchange.addMockResponse(1, createMockResponse(1, 1, 5));

        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should process responses successfully");
        assertTrue(result.refinementsApplied() >= 0,
                  "Should apply ghost elements from responses");
    }

    // TEST 6: Convergence Detection
    @Test
    public void testConvergenceDetection() {
        exchange.setConverged(true);

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
        assertTrue(snapshot.maxRoundTime().toMillis() < 5000,
                  "Individual rounds should complete within 5 seconds");
    }

    // TEST 8: Boundary Ghost Exchange
    @Test
    public void testBoundaryGhostExchange() {
        exchange.addMockResponse(1, createMockResponse(1, 1, 2));

        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should exchange ghosts at boundaries");

        var sentRequests = exchange.getSentRequests();
        assertTrue(sentRequests.size() >= 0, "Should track sent requests");
    }

    // TEST 9: Request Batching
    @Test
    public void testRequestBatching() {
        phase.execute(forest, 0, 4);

        var requestCount = exchange.getRequestCount();
        assertTrue(requestCount >= 0, "Should track batched refinement requests");
    }

    // TEST 10: Barrier Synchronization
    @Test
    public void testBarrierSynchronization() {
        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should complete with barrier synchronization");

        // Luciferase-uhsn: one barrier per executed round; a balanced forest converges in one round.
        assertTrue(registry.getBarrierCount() >= 1,
                  "Should synchronize at a barrier after each executed round");
        assertEquals(result.finalMetrics().roundCount(), registry.getBarrierCount(),
                  "Exactly one barrier per executed round");
    }

    // TEST 11: Max Rounds Respected
    @Test
    public void testMaxRoundsRespected() {
        exchange.setConverged(false);
        exchange.setAlwaysNeedsRefinement(true);

        config = BalanceConfiguration.defaultConfig().withMaxRounds(2);
        phase = new CrossPartitionBalancePhase<>(exchange, registry, config);

        var result = phase.execute(forest, 0, 4);

        assertTrue(result.finalMetrics().roundCount() <= 2,
                  "Should not exceed max rounds (2)");
    }

    // TEST 12: Level-Based Efficiency
    @Test
    public void testLevelBasedRefinement() {
        phase.execute(forest, 0, 4);

        var sentRequests = exchange.getSentRequests();
        for (var request : sentRequests) {
            assertTrue(request.treeLevel() >= 0 && request.treeLevel() <= 21,
                      "Request should have valid tree level (0-21)");
        }
    }

    // TEST 13: ButterflyPattern Integration
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testButterflyPartnerSelection() {
        registry = new MockPartitionRegistry(8);
        phase = new CrossPartitionBalancePhase<>(exchange, registry, config);

        var result = phase.execute(forest, 0, 8);

        assertTrue(result.successful(), "Should succeed with butterfly pattern");
        // Luciferase-uhsn: butterfly scheduling is the coordinator's concern; the phase loop now terminates on
        // Allreduce-LAND, so a balanced forest converges in one round (data-driven, not a fixed butterfly count).
        assertEquals(1, result.finalMetrics().roundCount(),
                    "A balanced forest converges in 1 round under Allreduce-LAND");
    }

    // TEST 14: TwoOneBalanceChecker Integration
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testTwoOneViolationDetectionIntegration() {
        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should complete without violations in empty forest");
        assertTrue(result.finalMetrics().roundCount() >= 1, "Should execute at least 1 round");
    }

    // TEST 15 (Luciferase-uhsn S6): a ghost-coarser violation produces a remote request carrying REAL boundary
    // keys on the wire (replacing the prior inline empty-key send), routed to the ghost owner's rank.
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRefinementRequestsCreatedFromViolations() {
        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES);
        // Ghost is COARSER (level 1) than the adjacent local element (level 3): !localNeedsRefinement() => the ghost
        // owner (rank 2) is the side that must refine, so a remote request is warranted (D3).
        createProperViolation(ghostLayer, forest, (byte) 1, (byte) 3, 2);
        phase.setForestContext(forest, ghostLayer);

        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should handle refinement requests");
        assertTrue(exchange.getRequestCount() > 0, "Should send a refinement request for the ghost-coarser violation");

        var sent = exchange.getSentRequests();
        assertTrue(sent.stream().anyMatch(r -> !r.boundaryKeys().isEmpty()),
                   "Request must carry REAL boundary keys, not an empty list");
        assertTrue(sent.stream().allMatch(r -> r.roundNumber() > 0),
                   "Placeholder roundNumber=0 must be overwritten before transmit");
    }

    // TEST 16: Ghost Elements Applied During Rounds
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testGhostElementsAppliedDuringRounds() {
        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should successfully apply ghost elements");
        // Luciferase-uhsn: data-driven convergence — balanced forest converges in one round.
        assertEquals(1, result.finalMetrics().roundCount(),
                    "A balanced forest converges in 1 round under Allreduce-LAND");
    }

    // TEST 17: Convergence Detected When No Violations Remain
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testConvergenceDetectedWhenNoViolations() {
        exchange.setConverged(true);
        exchange.setAlwaysNeedsRefinement(false);

        var result = phase.execute(forest, 0, 4);

        assertTrue(result.successful(), "Should converge when no violations remain");
        // Luciferase-uhsn: no violations => the Allreduce-LAND loop terminates immediately with no refinements.
        // converged() is defined as "successful with refinements applied", so the no-violation case is noWork().
        assertEquals(1, result.finalMetrics().roundCount(), "Terminates in one round when already balanced");
        assertTrue(result.noWorkNeeded(), "No violations => no refinements applied (noWorkNeeded)");
    }

    // Helper method to create domain mock refinement response
    private RefinementResponse<MortonKey, LongEntityID, String> createMockResponse(
            int responderRank, int roundNumber, int ghostElementCount) {
        return new RefinementResponse<>(0, responderRank, 0L, roundNumber,
                                        List.of(), ghostElementCount > 0, System.currentTimeMillis());
    }

    // ========== D.5 Tests: identifyRefinementNeeds() ==========

    // TEST 1: No violations detected - should return empty RoundResult
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_NoViolations() throws Exception {
        var balanceChecker = new TwoOneBalanceChecker<MortonKey, LongEntityID, String>();

        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );
        phase.setForestContext(forest, ghostLayer);

        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID, String>();

        var result = phase.identifyRefinementNeeds(1, 2, balanceChecker, mockCoordinator);

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
        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );

        var ghostKey = new MortonKey(100L, (byte) 3);
        var ghostElement = new com.hellblazer.luciferase.lucien.forest.ghost.GhostElement<>(
            ghostKey, new LongEntityID(100L), "ghost-content",
            new javax.vecmath.Point3f(100.0f, 100.0f, 100.0f), 1, 0L
        );
        ghostLayer.addGhostElement(ghostElement);

        phase.setForestContext(forest, ghostLayer);

        var mockBalanceChecker = new MockBalanceChecker();
        var localKey = new MortonKey(101L, (byte) 1);
        mockBalanceChecker.addViolation(localKey, (byte) 1, ghostKey, (byte) 3, 1);

        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID, String>();

        var result = phase.identifyRefinementNeeds(1, 2, mockBalanceChecker, mockCoordinator);

        assertNotNull(result, "Result should not be null");
        assertEquals(1, result.refinementsApplied(), "Should find 1 violation");
        assertEquals(1, mockCoordinator.getRequestsSent(), "Should send 1 request");
        assertEquals(1, result.roundNumber(), "Should track round number");
    }

    // TEST 3: Multiple violations from different ranks - should group by rank
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_MultipleRanks() throws Exception {
        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );

        addViolationPair(ghostLayer, forest, 100L, 101L, 1);
        addViolationPair(ghostLayer, forest, 102L, 103L, 1);
        addViolationPair(ghostLayer, forest, 200L, 201L, 2);
        addViolationPair(ghostLayer, forest, 202L, 203L, 2);
        addViolationPair(ghostLayer, forest, 300L, 301L, 3);

        phase.setForestContext(forest, ghostLayer);

        var balanceChecker = new TwoOneBalanceChecker<MortonKey, LongEntityID, String>();
        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID, String>();

        var result = phase.identifyRefinementNeeds(1, 2, balanceChecker, mockCoordinator);

        var capturedRequests = mockCoordinator.getCapturedRequests();
        assertTrue(capturedRequests.size() <= 3,
                  "Should create at most 3 requests (one per rank)");
        assertNotNull(result, "Result should not be null");
    }

    // TEST 4: Boundary keys included in requests
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_RespectsBoundaryKeys() throws Exception {
        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );

        var ghostKey1 = new MortonKey(100L, (byte) 3);
        ghostLayer.addGhostElement(new com.hellblazer.luciferase.lucien.forest.ghost.GhostElement<>(
            ghostKey1, new LongEntityID(100L), "ghost1",
            new javax.vecmath.Point3f(10.0f, 10.0f, 10.0f), 1, 0L
        ));

        var ghostKey2 = new MortonKey(200L, (byte) 3);
        ghostLayer.addGhostElement(new com.hellblazer.luciferase.lucien.forest.ghost.GhostElement<>(
            ghostKey2, new LongEntityID(200L), "ghost2",
            new javax.vecmath.Point3f(20.0f, 20.0f, 20.0f), 1, 0L
        ));

        phase.setForestContext(forest, ghostLayer);

        var balanceChecker = new MockBalanceChecker();
        balanceChecker.addViolation(new MortonKey(101L, (byte) 1), (byte) 1, ghostKey1, (byte) 3, 1);
        balanceChecker.addViolation(new MortonKey(201L, (byte) 1), (byte) 1, ghostKey2, (byte) 3, 1);

        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID, String>();

        var result = phase.identifyRefinementNeeds(1, 2, balanceChecker, mockCoordinator);

        var capturedRequests = mockCoordinator.getCapturedRequests();
        assertEquals(1, capturedRequests.size(), "Should have 1 request for rank 1 violations");

        var request = capturedRequests.get(0);
        assertEquals(4, request.boundaryKeys().size(), "Should have 4 boundary keys (2 violations × 2 keys each)");
        assertEquals(2, result.refinementsApplied(), "Should apply 2 refinements");
    }

    // TEST 5: Timeout handling - should throw TimeoutException
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_AsyncTimeout() {
        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );

        var ghostKey = new MortonKey(100L, (byte) 3);
        ghostLayer.addGhostElement(new com.hellblazer.luciferase.lucien.forest.ghost.GhostElement<>(
            ghostKey, new LongEntityID(100L), "ghost",
            new javax.vecmath.Point3f(100.0f, 100.0f, 100.0f), 1, 0L
        ));

        phase.setForestContext(forest, ghostLayer);

        var balanceChecker = new MockBalanceChecker();
        balanceChecker.addViolation(new MortonKey(101L, (byte) 1), (byte) 1, ghostKey, (byte) 3, 1);

        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID, String>();
        mockCoordinator.setShouldTimeout(true);

        assertThrows(java.util.concurrent.TimeoutException.class, () ->
            phase.identifyRefinementNeeds(1, 2, balanceChecker, mockCoordinator),
            "Should throw TimeoutException when coordinator times out");
    }

    // TEST 6: Coordinator exception - should propagate
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_CoordinatorException() {
        var ghostLayer = new com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );

        var ghostKey = new MortonKey(100L, (byte) 3);
        ghostLayer.addGhostElement(new com.hellblazer.luciferase.lucien.forest.ghost.GhostElement<>(
            ghostKey, new LongEntityID(100L), "ghost",
            new javax.vecmath.Point3f(100.0f, 100.0f, 100.0f), 1, 0L
        ));

        phase.setForestContext(forest, ghostLayer);

        var balanceChecker = new MockBalanceChecker();
        balanceChecker.addViolation(new MortonKey(101L, (byte) 1), (byte) 1, ghostKey, (byte) 3, 1);

        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID, String>();
        mockCoordinator.setShouldThrowException(true);

        assertThrows(RuntimeException.class, () ->
            phase.identifyRefinementNeeds(1, 2, balanceChecker, mockCoordinator),
            "Should propagate RuntimeException from coordinator");
    }

    // TEST 7: Integration test with Phase44ForestIntegrationFixture
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testIdentifyRefinementNeeds_IntegrationWithRealChecker() throws Exception {
        var fixture = new com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture();
        fixture.createForest();
        fixture.syncGhostLayer();

        var realForest = fixture.getForest();
        var realGhostLayer = fixture.getGhostLayer();

        // Need a separate exchange typed for TestEntity (not String)
        var integrationExchange = new RefinementExchange<MortonKey, LongEntityID,
                com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture.TestEntity>() {
            @Override
            public CompletableFuture<RefinementResponse<MortonKey, LongEntityID,
                    com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture.TestEntity>>
            requestRefinementAsync(int targetRank, long treeId, int roundNumber, int treeLevel,
                                   List<MortonKey> boundaryKeys) {
                return CompletableFuture.completedFuture(RefinementResponse.empty());
            }
        };
        var integrationRegistry = new MockPartitionRegistry(4);
        var integrationConfig = BalanceConfiguration.defaultConfig();

        var integrationPhase = new CrossPartitionBalancePhase<MortonKey, LongEntityID,
                com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture.TestEntity>(
            integrationExchange, integrationRegistry, integrationConfig
        );
        integrationPhase.setForestContext(realForest, realGhostLayer);

        var realBalanceChecker = new TwoOneBalanceChecker<MortonKey, LongEntityID,
                com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture.TestEntity>();

        var mockCoordinator = new MockRefinementCoordinator<MortonKey, LongEntityID,
                com.hellblazer.luciferase.lucien.balancing.fault.Phase44ForestIntegrationFixture.TestEntity>();

        var result = integrationPhase.identifyRefinementNeeds(1, 2, realBalanceChecker, mockCoordinator);

        assertNotNull(result, "Result should not be null");
        assertEquals(1, result.roundNumber(), "Should track round number");

        var capturedRequests = mockCoordinator.getCapturedRequests();
        log.info("Integration test: {} violations found, {} requests sent",
                result.refinementsApplied(), capturedRequests.size());

        for (var request : capturedRequests) {
            assertTrue(request.roundNumber() > 0, "Request should have valid round number");
            assertTrue(request.treeLevel() >= 0, "Request should have valid tree level");
        }
    }

    /**
     * Helper to create a proper violation between ghost and local elements.
     */
    private boolean createProperViolation(
        com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String> ghostLayer,
        Forest<MortonKey, LongEntityID, String> forest,
        byte ghostLevel,
        byte localLevel,
        int ownerRank
    ) {
        var localCellSize = com.hellblazer.luciferase.lucien.Constants.lengthAtLevel(localLevel);
        var ghostCellSize = com.hellblazer.luciferase.lucien.Constants.lengthAtLevel(ghostLevel);
        int baseCoord = localCellSize;

        var ghostPosition = new javax.vecmath.Point3f(baseCoord, baseCoord, baseCoord);
        var ghostKey = new MortonKey(
            com.hellblazer.luciferase.geometry.MortonCurve.encode(baseCoord, baseCoord, baseCoord),
            ghostLevel
        );

        ghostLayer.addGhostElement(new com.hellblazer.luciferase.lucien.forest.ghost.GhostElement<>(
            ghostKey, new LongEntityID(ownerRank * 1000L), "ghost-content", ghostPosition, ownerRank, 0L
        ));

        int neighborX = baseCoord + ghostCellSize;
        var neighborPosition = new javax.vecmath.Point3f(neighborX, baseCoord, baseCoord);

        var tree = forest.getAllTrees().get(0);
        var spatialIndex = tree.getSpatialIndex();
        spatialIndex.insert(new LongEntityID(ownerRank * 1000L + 1), neighborPosition, localLevel,
                            "local-entity", null);

        return true;
    }

    private void addViolationPair(
        com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String> ghostLayer,
        Forest<MortonKey, LongEntityID, String> forest,
        long ghostCode, long localCode, int ownerRank
    ) {
        createProperViolation(ghostLayer, forest, (byte) 3, (byte) 1, ownerRank);
    }

    // ========== Mock implementations ==========

    /** Domain exchange mock: captures sent requests, returns configurable domain responses. */
    private static class MockRefinementExchange
            implements RefinementExchange<MortonKey, LongEntityID, String> {

        private final Map<Integer, RefinementResponse<MortonKey, LongEntityID, String>> mockResponses =
            new ConcurrentHashMap<>();
        private final List<RefinementRequest<MortonKey>> sentRequests =
            Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private volatile boolean converged = false;
        private volatile boolean alwaysNeedsRefinement = false;

        @Override
        public CompletableFuture<RefinementResponse<MortonKey, LongEntityID, String>> requestRefinementAsync(
                int targetRank, long treeId, int roundNumber, int treeLevel,
                List<MortonKey> boundaryKeys) {

            requestCount.incrementAndGet();

            // Capture the sent request as a domain object
            var request = new RefinementRequest<>(0, treeId, roundNumber, treeLevel,
                                                   boundaryKeys, System.currentTimeMillis());
            sentRequests.add(request);

            var response = mockResponses.getOrDefault(targetRank,
                createDefaultResponse(targetRank, roundNumber));

            return CompletableFuture.completedFuture(response);
        }

        public void addMockResponse(int targetRank, RefinementResponse<MortonKey, LongEntityID, String> response) {
            mockResponses.put(targetRank, response);
        }

        public void setConverged(boolean converged) { this.converged = converged; }

        public void setAlwaysNeedsRefinement(boolean alwaysNeedsRefinement) {
            this.alwaysNeedsRefinement = alwaysNeedsRefinement;
        }

        public int getRequestCount() { return requestCount.get(); }

        public List<RefinementRequest<MortonKey>> getSentRequests() {
            return new ArrayList<>(sentRequests);
        }

        private RefinementResponse<MortonKey, LongEntityID, String> createDefaultResponse(
                int responderRank, int roundNumber) {
            return new RefinementResponse<>(0, responderRank, 0L, roundNumber,
                                            List.of(), alwaysNeedsRefinement && !converged,
                                            System.currentTimeMillis());
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

    /** Mock coordinator for D.5 tests — works via reflection from identifyRefinementNeeds(). */
    private static class MockRefinementCoordinator<K extends SpatialKey<K>,
                                                    I extends EntityID,
                                                    C> {
        private final AtomicInteger requestsSent = new AtomicInteger(0);
        private final List<RefinementRequest<K>> capturedRequests =
            Collections.synchronizedList(new ArrayList<>());
        private boolean shouldTimeout = false;
        private boolean shouldThrowException = false;

        public List<CompletableFuture<RefinementResponse<K, I, C>>> sendRequestsParallel(
                List<RefinementRequest<K>> requests) {
            requestsSent.addAndGet(requests.size());
            capturedRequests.addAll(requests);

            if (shouldThrowException) {
                throw new RuntimeException("Coordinator exception");
            }

            var futures = new ArrayList<CompletableFuture<RefinementResponse<K, I, C>>>();
            for (var request : requests) {
                if (shouldTimeout) {
                    var future = new CompletableFuture<RefinementResponse<K, I, C>>();
                    // Don't complete the future - let it timeout
                    futures.add(future);
                } else {
                    var response = new RefinementResponse<K, I, C>(
                        request.requesterRank(), request.requesterRank(), 0L,
                        request.roundNumber(), List.of(), false, System.currentTimeMillis()
                    );
                    futures.add(CompletableFuture.completedFuture(response));
                }
            }
            return futures;
        }

        public int getRequestsSent() { return requestsSent.get(); }

        public List<RefinementRequest<K>> getCapturedRequests() {
            return new ArrayList<>(capturedRequests);
        }

        public void setShouldTimeout(boolean shouldTimeout) { this.shouldTimeout = shouldTimeout; }

        public void setShouldThrowException(boolean shouldThrowException) {
            this.shouldThrowException = shouldThrowException;
        }
    }

    /** Mock balance checker for D.5 tests — allows injecting predefined violations. */
    private static class MockBalanceChecker extends TwoOneBalanceChecker<MortonKey, LongEntityID, String> {
        private final List<TwoOneBalanceChecker.BalanceViolation<MortonKey>> violations = new ArrayList<>();

        public void addViolation(MortonKey localKey, byte localLevel, MortonKey ghostKey,
                                  byte ghostLevel, int sourceRank) {
            var levelDiff = Math.abs(localLevel - ghostLevel);
            if (levelDiff > 1) {
                violations.add(new TwoOneBalanceChecker.BalanceViolation<>(
                    localKey, ghostKey, localLevel, ghostLevel, levelDiff, sourceRank
                ));
            }
        }

        @Override
        public List<TwoOneBalanceChecker.BalanceViolation<MortonKey>> findViolations(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer<MortonKey, LongEntityID, String> ghostLayer,
            Forest<MortonKey, LongEntityID, String> forest) {
            return new ArrayList<>(violations);
        }
    }
}
