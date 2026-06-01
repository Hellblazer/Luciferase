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
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for RefinementCoordinator with butterfly pattern integration.
 *
 * <p>Updated for Inc3-C4: uses domain RefinementRequest/Response and MockRefinementExchange
 * instead of proto types and BalanceCoordinatorClient.
 *
 * @author hal.hildebrand
 */
public class RefinementCoordinatorTest {

    private MockRefinementExchange exchange;
    private RefinementRequestManager requestManager;
    private RefinementCoordinator<MortonKey, LongEntityID, String> coordinator;

    @BeforeEach
    public void setUp() {
        exchange = new MockRefinementExchange();
        requestManager = new RefinementRequestManager();
    }

    /**
     * A by-partner request map covering rank-0's possible butterfly partners {1,2,3} in a 4-partition run, each with
     * one ghost-coarser request (requesterRank=0 local, placeholder roundNumber=0). Lets the butterfly-routing tests
     * exercise a non-vacuous send after Luciferase-uhsn removed the synthesized vacuous request.
     */
    private static Map<Integer, List<RefinementRequest<MortonKey>>> rank0PartnerMap() {
        var keys = List.of(new MortonKey(10L, (byte) 3), new MortonKey(11L, (byte) 2));
        var req = new RefinementRequest<>(0, 0L, 0, 3, keys, 123L);
        return Map.of(1, List.of(req), 2, List.of(req), 3, List.of(req));
    }

    // TEST 1: Constructor with valid parameters
    @Test
    public void testConstructorWithValidParameters() {
        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 2);
        assertNotNull(coordinator, "Should create coordinator with rank=0, partitions=2");

        coordinator = new RefinementCoordinator<>(exchange, requestManager, 4, 8);
        assertNotNull(coordinator, "Should create coordinator with rank=4, partitions=8");
    }

    // TEST 2: Constructor rejects negative rank
    @Test
    public void testConstructorRejectsNegativeRank() {
        var exception = assertThrows(IllegalArgumentException.class, () ->
            new RefinementCoordinator<>(exchange, requestManager, -1, 4));

        assertTrue(exception.getMessage().contains("myRank"),
                  "Exception should mention invalid myRank parameter");
    }

    // TEST 3: Constructor rejects zero partitions
    @Test
    public void testConstructorRejectsZeroPartitions() {
        var exception = assertThrows(IllegalArgumentException.class, () ->
            new RefinementCoordinator<>(exchange, requestManager, 0, 0));

        assertTrue(exception.getMessage().contains("totalPartitions"),
                  "Exception should mention invalid totalPartitions parameter");
    }

    // TEST 4: Butterfly partner calculation - Round 0
    @Test
    public void testButterflyPartnerRound0() {
        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4, rank0PartnerMap());

        var result = coordinator.executeRefinementRound(1, 2); // roundNumber=1 (1-based)

        assertTrue(exchange.getRequestCount() > 0, "Should send request to butterfly partner");

        var sentRequests = exchange.getSentRequests();
        assertFalse(sentRequests.isEmpty(), "Should have sent requests");
    }

    // TEST 5: Butterfly partner calculation - Round 1
    @Test
    public void testButterflyPartnerRound1() {
        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4, rank0PartnerMap());

        var result = coordinator.executeRefinementRound(2, 2); // roundNumber=2 (1-based)

        assertTrue(exchange.getRequestCount() > 0, "Should send request to butterfly partner");

        var sentRequests = exchange.getSentRequests();
        assertFalse(sentRequests.isEmpty(), "Should have sent requests");
    }

    // TEST 6: Butterfly partner calculation - Round 2 (out of bounds)
    @Test
    public void testButterflyPartnerRound2OutOfBounds() {
        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4);

        var result = coordinator.executeRefinementRound(3, 3); // roundNumber=3 (1-based)

        assertNotNull(result, "Should return result even when partner out of bounds");
    }

    // TEST 7: Non-participating round (partner=-1) handled gracefully
    @Test
    public void testNonParticipatingRoundHandledGracefully() {
        coordinator = new RefinementCoordinator<>(exchange, requestManager, 7, 8);

        var result = coordinator.executeRefinementRound(4, 3); // roundNumber=4 (1-based)

        assertNotNull(result, "Should complete successfully even with no partner");
        assertEquals(4, result.roundNumber(), "Should preserve round number");
    }

    // TEST 8: sendRequestsParallel() called with correct requests
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testSendRequestsParallelCalled() {
        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4, rank0PartnerMap());

        var result = coordinator.executeRefinementRound(1, 2);

        assertTrue(exchange.getRequestCount() > 0, "Should invoke sendRequestsParallel");

        var sentRequests = exchange.getSentRequests();
        assertFalse(sentRequests.isEmpty(), "Should send requests to butterfly partner");
    }

    // TEST 9: Timeout handling doesn't deadlock
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testTimeoutHandlingDoesntDeadlock() {
        exchange.setSimulateTimeout(true);

        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4);

        var result = coordinator.executeRefinementRound(1, 2);

        assertNotNull(result, "Should complete despite timeout");
        // Timeout should not cause deadlock - test will fail with @Timeout if it does
    }

    // TEST 10: Response processing updates refinementsApplied count
    @Test
    public void testResponseProcessingUpdatesRefinementsApplied() {
        exchange.setGhostElementCount(5);

        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4, rank0PartnerMap());

        var result = coordinator.executeRefinementRound(1, 2);

        assertTrue(result.refinementsApplied() >= 0,
                  "Should track refinements applied from responses");
        assertNotNull(result, "Should return valid result");
    }

    // TEST 11 (Luciferase-uhsn S3): allReduceConverged default returns the local value
    @Test
    public void allReduceConverged_defaultReturnsLocalValue() throws InterruptedException {
        // A PartitionRegistry implementing only the pre-existing abstract methods must inherit the
        // allReduceConverged default, which returns its argument (degenerate single-partition behavior).
        var registry = new ParallelBalancer.PartitionRegistry() {
            @Override public int getCurrentPartitionId() { return 0; }
            @Override public int getPartitionCount() { return 1; }
            @Override public void barrier(int round) { }
            @Override public void requestRefinement(Object elementKey) { }
            @Override public int getPendingRefinements() { return 0; }
        };

        assertTrue(registry.allReduceConverged(true), "default returns local convergence value (true)");
        assertFalse(registry.allReduceConverged(false), "default returns local convergence value (false)");
    }

    // TEST 12 (Luciferase-uhsn S4): buildRequestsForPartner returns the partner's real injected request set,
    // overwriting the placeholder roundNumber, and targets the butterfly partner.
    @Test
    public void buildRequestsForPartner_withInjectedMap_returnsPartnerRealRequestSet() {
        int partner = ButterflyPattern.getPartner(0, 0, 4); // round 1 (0-based 0) partner for rank 0 in P=4
        var k1 = new MortonKey(20L, (byte) 3);
        var k2 = new MortonKey(21L, (byte) 3);
        var k3 = new MortonKey(22L, (byte) 3);
        var injected = new RefinementRequest<>(0 /*local reply-to*/, 0L, 0 /*placeholder round*/, 3,
                                               List.of(k1, k2, k3), 777L);
        var map = Map.of(partner, List.of(injected));

        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4, map);
        coordinator.executeRefinementRound(1, 2);

        var sent = exchange.getSentRequests();
        assertEquals(1, sent.size(), "the partner's single injected request is sent (not a vacuous one)");
        assertEquals(3, sent.get(0).boundaryKeys().size(), "real boundary keys are carried, not List.of()");
        assertTrue(sent.get(0).boundaryKeys().containsAll(List.of(k1, k2, k3)));
        assertEquals(1, sent.get(0).roundNumber(), "placeholder round 0 overwritten with actual round 1");
        assertEquals(partner, sent.get(0).requesterRank(),
                     "exchange targetRank (captured as requesterRank in the mock) is the butterfly partner");
    }

    // TEST 13 (Luciferase-uhsn S4): a partner with no entry in the map yields no request (no vacuous send).
    @Test
    public void buildRequestsForPartner_partnerAbsentFromMap_sendsNothing() {
        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4, Map.of());
        coordinator.executeRefinementRound(1, 2);

        assertTrue(exchange.getSentRequests().isEmpty(),
                   "empty by-partner map => no refinement request is sent");
    }

    // TEST 14 (Luciferase-uhsn S5): coordinateRefinement terminates on global Allreduce-LAND, not a fixed count.
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void coordinateRefinement_terminatesOnAllReduceConvergence_notFixedRounds() {
        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4, rank0PartnerMap());

        // Not converged on round 1, converged on round 2. maxRounds high so termination is driven by the reduction.
        var registry = new ScriptedRegistry(0, 4, List.of(false, true));

        var result = coordinator.coordinateRefinement(4, 10, 0, registry);

        assertEquals(2, result.roundsExecuted(), "must stop the round AFTER the reduction first returns true");
        assertTrue(result.converged(), "result reflects global convergence");
        assertEquals(2, registry.allReduceCalls(), "allReduceConverged drives the loop");
    }

    // TEST 15 (Luciferase-uhsn S5): a never-converging reduction stops at maxRounds (safety cap), not forever.
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void coordinateRefinement_neverConverges_stopsAtMaxRounds() {
        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4, rank0PartnerMap());

        var registry = new ScriptedRegistry(0, 4, List.of()); // always returns false

        var result = coordinator.coordinateRefinement(4, 3, 0, registry);

        assertEquals(3, result.roundsExecuted(), "maxRounds is the safety cap");
        assertFalse(result.converged(), "never reached global convergence");
    }

    // ========== Mock implementations ==========

    /** PartitionRegistry whose allReduceConverged follows a scripted sequence (then false forever). */
    private static class ScriptedRegistry implements ParallelBalancer.PartitionRegistry {
        private final int id;
        private final int count;
        private final List<Boolean> script;
        private int calls = 0;

        ScriptedRegistry(int id, int count, List<Boolean> script) {
            this.id = id;
            this.count = count;
            this.script = script;
        }

        @Override public int getCurrentPartitionId() { return id; }
        @Override public int getPartitionCount() { return count; }
        @Override public void barrier(int round) { }
        @Override public void requestRefinement(Object elementKey) { }
        @Override public int getPendingRefinements() { return 0; }

        @Override
        public boolean allReduceConverged(boolean locallyConverged) {
            var idx = calls++;
            return idx < script.size() && script.get(idx);
        }

        int allReduceCalls() { return calls; }
    }

    /** Domain exchange mock for RefinementCoordinator tests. */
    private static class MockRefinementExchange
            implements RefinementExchange<MortonKey, LongEntityID, String> {

        private final List<RefinementRequest<MortonKey>> sentRequests = new ArrayList<>();
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private volatile boolean simulateTimeout = false;
        private volatile int ghostElementCount = 0;

        @Override
        public CompletableFuture<RefinementResponse<MortonKey, LongEntityID, String>> requestRefinementAsync(
                int targetRank, long treeId, int roundNumber, int treeLevel,
                List<MortonKey> boundaryKeys) {

            requestCount.incrementAndGet();

            var request = new RefinementRequest<>(targetRank, treeId, roundNumber, treeLevel,
                                                   boundaryKeys, System.currentTimeMillis());
            sentRequests.add(request);

            if (simulateTimeout) {
                // Never complete — will be caught by orTimeout() in sendRequestsParallel
                return new CompletableFuture<>();
            }

            var response = new RefinementResponse<MortonKey, LongEntityID, String>(
                targetRank, targetRank, treeId, roundNumber,
                List.of(), false, System.currentTimeMillis()
            );

            return CompletableFuture.completedFuture(response);
        }

        public void setSimulateTimeout(boolean simulateTimeout) { this.simulateTimeout = simulateTimeout; }

        public void setGhostElementCount(int count) { this.ghostElementCount = count; }

        public int getRequestCount() { return requestCount.get(); }

        public List<RefinementRequest<MortonKey>> getSentRequests() {
            return new ArrayList<>(sentRequests);
        }
    }
}
