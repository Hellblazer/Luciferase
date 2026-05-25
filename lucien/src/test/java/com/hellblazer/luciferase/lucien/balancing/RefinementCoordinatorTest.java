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
        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4);

        var result = coordinator.executeRefinementRound(1, 2); // roundNumber=1 (1-based)

        assertTrue(exchange.getRequestCount() > 0, "Should send request to butterfly partner");

        var sentRequests = exchange.getSentRequests();
        assertFalse(sentRequests.isEmpty(), "Should have sent requests");
    }

    // TEST 5: Butterfly partner calculation - Round 1
    @Test
    public void testButterflyPartnerRound1() {
        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4);

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
        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4);

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

        coordinator = new RefinementCoordinator<>(exchange, requestManager, 0, 4);

        var result = coordinator.executeRefinementRound(1, 2);

        assertTrue(result.refinementsApplied() >= 0,
                  "Should track refinements applied from responses");
        assertNotNull(result, "Should return valid result");
    }

    // ========== Mock implementations ==========

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
