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

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for DistributedViolationAggregator - violation exchange via the {@link ViolationExchange} port.
 *
 * @author hal.hildebrand
 */
class DistributedViolationAggregatorTest {

    private ViolationExchange<MortonKey> mockExchange;
    private DistributedViolationAggregator<MortonKey> aggregator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockExchange = mock(ViolationExchange.class);
        aggregator = new DistributedViolationAggregator<>(0, 4, mockExchange);
    }

    @Test
    void testSuccessfulDistributedExchange() throws Exception {
        // Create local violations
        var localViolations = List.of(
            createViolation(1, 2, 5, 7, 2, 0),
            createViolation(3, 4, 6, 8, 2, 0)
        );

        // Mock successful responses with partner violations
        var partnerViolation1 = createViolation(5, 6, 7, 9, 2, 1);
        var partnerViolation2 = createViolation(7, 8, 8, 10, 2, 2);

        when(mockExchange.exchangeViolations(any()))
            .thenReturn(new ViolationBatch<>(1, 0, 0, List.of(partnerViolation1), System.currentTimeMillis()))
            .thenReturn(new ViolationBatch<>(2, 0, 1, List.of(partnerViolation2), System.currentTimeMillis()));

        // Execute aggregation
        var result = aggregator.aggregateDistributed(localViolations);

        // Verify we got results including partner violations
        assertNotNull(result);
        assertTrue(result.containsAll(localViolations));
        assertTrue(result.contains(partnerViolation1));
        assertTrue(result.contains(partnerViolation2));
        assertEquals(4, result.size());

        // Verify exchanges were made (2 rounds for 4 partitions)
        verify(mockExchange, times(2)).exchangeViolations(any());
    }

    @Test
    void testTimeoutHandling() throws Exception {
        // Create local violations
        var localViolations = List.of(
            createViolation(1, 2, 5, 7, 2, 0)
        );

        // Mock timeout on first call, success on second
        when(mockExchange.exchangeViolations(any()))
            .thenThrow(new BalanceExchangeException("deadline exceeded", false, true))
            .thenReturn(new ViolationBatch<>(2, 0, 1, List.of(), System.currentTimeMillis()));

        // Execute aggregation - should handle timeout gracefully
        var result = aggregator.aggregateDistributed(localViolations);

        // Should still return local violations despite timeout
        assertNotNull(result);
        assertTrue(result.containsAll(localViolations));

        // Verify both calls were attempted (timeout is not retried)
        verify(mockExchange, times(2)).exchangeViolations(any());
    }

    @Test
    void testRetryOnTransientFailure() throws Exception {
        // Create local violations
        var localViolations = List.of(
            createViolation(1, 2, 5, 7, 2, 0)
        );

        var callCount = new AtomicInteger(0);

        // Mock transient failure on first attempt, success on retry
        when(mockExchange.exchangeViolations(any()))
            .thenAnswer(invocation -> {
                if (callCount.getAndIncrement() == 0) {
                    throw new BalanceExchangeException("unavailable", true, false);
                }
                return new ViolationBatch<>(1, 0, 0, List.of(), System.currentTimeMillis());
            });

        // Execute aggregation
        var result = aggregator.aggregateDistributed(localViolations);

        // Should succeed after retry
        assertNotNull(result);
        assertTrue(result.containsAll(localViolations));

        // Verify retry happened (1 original + 1 retry for first round, plus second round)
        verify(mockExchange, atLeast(2)).exchangeViolations(any());
    }

    @Test
    void testPartialFailureDoesNotBlockAggregation() throws Exception {
        // Create local violations
        var localViolations = List.of(
            createViolation(1, 2, 5, 7, 2, 0)
        );

        // Mock first round fails permanently (transient + retry exhausted), second round succeeds
        when(mockExchange.exchangeViolations(any()))
            .thenThrow(new BalanceExchangeException("unavailable", true, false))
            .thenThrow(new BalanceExchangeException("unavailable", true, false))
            .thenReturn(new ViolationBatch<>(2, 0, 1, List.of(), System.currentTimeMillis()));

        // Execute aggregation
        var result = aggregator.aggregateDistributed(localViolations);

        // Should still return local violations
        assertNotNull(result);
        assertTrue(result.containsAll(localViolations));
    }

    @Test
    void testNullLocalViolationsThrowsException() {
        assertThrows(NullPointerException.class, () -> {
            aggregator.aggregateDistributed(null);
        });
    }

    @Test
    void testShutdownCleansUpResources() throws Exception {
        // Execute aggregation to initialize state
        var localViolations = List.of(createViolation(1, 2, 5, 7, 2, 0));

        when(mockExchange.exchangeViolations(any()))
            .thenReturn(new ViolationBatch<>(1, 0, 0, List.of(), System.currentTimeMillis()));

        aggregator.aggregateDistributed(localViolations);

        // Shutdown should not throw
        assertDoesNotThrow(() -> aggregator.shutdown());
    }

    /**
     * Helper to create a domain BalanceViolation for testing (level-0 Morton keys, levelDiff > 1).
     */
    private TwoOneBalanceChecker.BalanceViolation<MortonKey> createViolation(long localKeyId, long ghostKeyId,
                                                                             int localLevel, int ghostLevel,
                                                                             int levelDiff, int sourceRank) {
        return new TwoOneBalanceChecker.BalanceViolation<>(
            new MortonKey(localKeyId, (byte) 0),
            new MortonKey(ghostKeyId, (byte) 0),
            localLevel, ghostLevel, levelDiff, sourceRank);
    }
}
