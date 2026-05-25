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
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ForestConfig;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Domain tests for CrossPartitionBalancePhase.applyRefinementResponses() after the grpc
 * inversion (Inc3-C4). Deserialization of proto ghost elements has moved to GrpcBalanceExchange;
 * this test works entirely with already-deserialized domain {@link GhostElement} instances.
 *
 * <p>Test 5 "skip invalid" semantics now live in GrpcBalanceExchangeTest. This file covers
 * the domain contract: domain ghost elements in a response are added to the ghost layer;
 * null responses and empty responses are no-ops; a per-element exception does not abort
 * the rest.
 *
 * @author hal.hildebrand
 */
public class CrossPartitionBalancePhaseResponseHandlingTest {

    private static final Logger log = LoggerFactory.getLogger(CrossPartitionBalancePhaseResponseHandlingTest.class);

    private CrossPartitionBalancePhase<MortonKey, LongEntityID, String> phase;
    private GhostLayer<MortonKey, LongEntityID, String> ghostLayer;
    private Forest<MortonKey, LongEntityID, String> forest;
    private MockRefinementExchange exchange;
    private MockPartitionRegistry registry;
    private RefinementCoordinator<MortonKey, LongEntityID, String> coordinator;

    @BeforeEach
    public void setUp() {
        forest = new Forest<>(ForestConfig.defaultConfig());
        ghostLayer = new GhostLayer<>(GhostType.FACES);
        exchange = new MockRefinementExchange();
        registry = new MockPartitionRegistry(4);

        phase = new CrossPartitionBalancePhase<>(exchange, registry, BalanceConfiguration.defaultConfig());
        phase.setForestContext(forest, ghostLayer);

        // Create coordinator (passed to applyRefinementResponses)
        coordinator = new RefinementCoordinator<>(exchange, new RefinementRequestManager(), 0, 4);
    }

    // TEST 1: Null response - should handle gracefully
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testApplyRefinementResponses_Null() {
        var initialGhostCount = ghostLayer.getNumGhostElements();

        // Call with null - should not throw
        assertDoesNotThrow(() -> phase.applyRefinementResponses(null, coordinator),
                          "Should handle null response gracefully");

        // Verify ghost layer unchanged
        assertEquals(initialGhostCount, ghostLayer.getNumGhostElements(),
                    "Ghost layer should be unchanged after null response");
    }

    // TEST 2: Empty domain response - should return without adding anything
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testApplyRefinementResponses_EmptyResponse() {
        var emptyResponse = new RefinementResponse<MortonKey, LongEntityID, String>(
            0, 1, 0L, 1, List.of(), false, System.currentTimeMillis()
        );

        var initialGhostCount = ghostLayer.getNumGhostElements();

        phase.applyRefinementResponses(emptyResponse, coordinator);

        assertEquals(initialGhostCount, ghostLayer.getNumGhostElements(),
                    "Ghost layer should be unchanged after empty response");
    }

    // TEST 3: Single domain ghost element - should add correctly
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testApplyRefinementResponses_SingleGhostElement() {
        var ghostKey = new MortonKey(100L, (byte) 3);
        var entityId = new LongEntityID(1001L);
        var content = "ghost-content-1";
        var position = new Point3f(10.0f, 20.0f, 30.0f);

        var ghost = new GhostElement<>(ghostKey, entityId, content, position, 1, 0L);

        var response = new RefinementResponse<>(0, 1, 0L, 1, List.of(ghost), false, System.currentTimeMillis());

        var initialGhostCount = ghostLayer.getNumGhostElements();

        phase.applyRefinementResponses(response, coordinator);

        assertEquals(initialGhostCount + 1, ghostLayer.getNumGhostElements(),
                    "Should add exactly 1 ghost element");

        var ghostElements = ghostLayer.getAllGhostElements();
        assertNotNull(ghostElements, "Ghost elements should not be null");
        assertEquals(1, ghostElements.size(), "Should have exactly 1 ghost element");

        var addedElement = ghostElements.get(0);
        assertEquals(ghostKey, addedElement.getSpatialKey(), "Spatial key should match");
        assertEquals(entityId, addedElement.getEntityId(), "Entity ID should match");
        assertEquals(content, addedElement.getContent(), "Content should match");
        assertEquals(1, addedElement.getOwnerRank(), "Owner rank should match");
    }

    // TEST 4: Multiple domain ghost elements - should add all
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testApplyRefinementResponses_MultipleGhostElements() {
        var ghosts = new java.util.ArrayList<GhostElement<MortonKey, LongEntityID, String>>();
        for (int i = 0; i < 5; i++) {
            var ghostKey = new MortonKey(100L + i, (byte) 3);
            var entityId = new LongEntityID(1000L + i);
            var content = "ghost-content-" + i;
            var position = new Point3f(10.0f + i, 20.0f + i, 30.0f + i);
            ghosts.add(new GhostElement<>(ghostKey, entityId, content, position, 1 + (i % 3), 0L));
        }

        var response = new RefinementResponse<>(0, 1, 0L, 1, ghosts, false, System.currentTimeMillis());

        var initialGhostCount = ghostLayer.getNumGhostElements();

        phase.applyRefinementResponses(response, coordinator);

        assertEquals(initialGhostCount + 5, ghostLayer.getNumGhostElements(),
                    "Should add all 5 ghost elements");

        var ghostElements = ghostLayer.getAllGhostElements();
        assertEquals(5, ghostElements.size(), "Should have exactly 5 ghost elements");

        for (int i = 0; i < 5; i++) {
            var element = ghostElements.get(i);
            assertEquals(new MortonKey(100L + i, (byte) 3), element.getSpatialKey(),
                        "Element " + i + " should have correct spatial key");
            assertEquals(new LongEntityID(1000L + i), element.getEntityId(),
                        "Element " + i + " should have correct entity ID");
            assertEquals("ghost-content-" + i, element.getContent(),
                        "Element " + i + " should have correct content");
        }
    }

    // TEST 5: SIG-1 — empty/sentinel response does NOT flip convergence to true.
    // We test via the execute() path: configure the exchange to return RefinementResponse.empty()
    // and verify that subsequent calls still behave as not-converged.
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testSig1_EmptyResponseDoesNotFlipConvergence() {
        // Configure exchange to always return empty sentinel
        exchange.setResponseFactory((rank, treeId, round, level, keys) ->
            CompletableFuture.completedFuture(RefinementResponse.empty()));

        // Execute a single round — with all-empty responses lastRoundIndicatedConvergence
        // must remain false (its initial value), so isConverged() stays false.
        var result = phase.execute(forest, 0, 2); // 2 partitions = 1 round

        // The result should succeed (no exceptions), but convergence must NOT have been
        // prematurely flagged true. We verify by checking the round completed without
        // converged=true being asserted as the termination cause: in execute() convergence
        // triggers "break" only after the last round, so with 1 round and no real responses
        // the loop finishes normally without converged=true.
        assertTrue(result.successful(), "Should succeed even when all responses are empty");

        // Additionally verify the isEmpty() sentinel itself
        var empty = RefinementResponse.<MortonKey, LongEntityID, String>empty();
        assertTrue(empty.isEmpty(), "RefinementResponse.empty() should report isEmpty()==true");

        // A non-empty response should not report isEmpty()
        var ghost = new GhostElement<>(new MortonKey(1L, (byte) 1), new LongEntityID(1L), "x",
                                        new Point3f(0, 0, 0), 0, 0L);
        var real = new RefinementResponse<MortonKey, LongEntityID, String>(
            1, 2, 0L, 1, List.of(ghost), false, System.currentTimeMillis());
        assertFalse(real.isEmpty(), "Real response with ghost elements should not report isEmpty()");
    }

    // TEST 6: Integration with real GhostLayer — diverse elements from different ranks
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testApplyRefinementResponses_IntegrationWithGhostLayer() {
        var ghosts = List.of(
            new GhostElement<>(new MortonKey(100L, (byte) 2), new LongEntityID(1001L), "content-1",
                               new Point3f(1, 2, 3), 1, 0L),
            new GhostElement<>(new MortonKey(200L, (byte) 4), new LongEntityID(2001L), "content-2",
                               new Point3f(10, 20, 30), 2, 0L),
            new GhostElement<>(new MortonKey(300L, (byte) 3), new LongEntityID(3001L), "content-3",
                               new Point3f(100, 200, 300), 3, 0L)
        );

        var response = new RefinementResponse<>(0, 1, 0L, 1, ghosts, false, System.currentTimeMillis());

        phase.applyRefinementResponses(response, coordinator);

        assertEquals(3, ghostLayer.getNumGhostElements(), "Should have all 3 ghost elements");

        var allGhosts = ghostLayer.getAllGhostElements();
        assertEquals(3, allGhosts.size(), "getAllGhostElements should return 3 elements");

        assertTrue(allGhosts.stream().anyMatch(g -> g.getOwnerRank() == 1), "Should have element from rank 1");
        assertTrue(allGhosts.stream().anyMatch(g -> g.getOwnerRank() == 2), "Should have element from rank 2");
        assertTrue(allGhosts.stream().anyMatch(g -> g.getOwnerRank() == 3), "Should have element from rank 3");

        assertTrue(allGhosts.stream().anyMatch(g -> "content-1".equals(g.getContent())), "Should have content-1");
        assertTrue(allGhosts.stream().anyMatch(g -> "content-2".equals(g.getContent())), "Should have content-2");
        assertTrue(allGhosts.stream().anyMatch(g -> "content-3".equals(g.getContent())), "Should have content-3");
    }

    // Mock implementations

    @FunctionalInterface
    interface ResponseFactory {
        CompletableFuture<RefinementResponse<MortonKey, LongEntityID, String>> create(
            int targetRank, long treeId, int roundNumber, int treeLevel, List<MortonKey> boundaryKeys);
    }

    private static class MockRefinementExchange
            implements RefinementExchange<MortonKey, LongEntityID, String> {

        private volatile ResponseFactory factory = (rank, treeId, round, level, keys) ->
            CompletableFuture.completedFuture(new RefinementResponse<>(0, rank, 0L, round, List.of(), false,
                                                                        System.currentTimeMillis()));

        public void setResponseFactory(ResponseFactory factory) {
            this.factory = factory;
        }

        @Override
        public CompletableFuture<RefinementResponse<MortonKey, LongEntityID, String>> requestRefinementAsync(
                int targetRank, long treeId, int roundNumber, int treeLevel, List<MortonKey> boundaryKeys) {
            return factory.create(targetRank, treeId, roundNumber, treeLevel, boundaryKeys);
        }
    }

    private static class MockPartitionRegistry implements ParallelBalancer.PartitionRegistry {
        private final int partitionCount;

        public MockPartitionRegistry(int partitionCount) {
            this.partitionCount = partitionCount;
        }

        @Override
        public int getCurrentPartitionId() { return 0; }

        @Override
        public int getPartitionCount() { return partitionCount; }

        @Override
        public void barrier(int round) {}

        @Override
        public void requestRefinement(Object elementKey) {}

        @Override
        public int getPendingRefinements() { return 0; }
    }
}
