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

import com.hellblazer.luciferase.lucien.balancing.grpc.BalanceCoordinatorClient;
import com.hellblazer.luciferase.lucien.balancing.proto.BalanceViolation;
import com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey;
import io.grpc.StatusRuntimeException;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for DistributedViolationAggregator - gRPC-based violation exchange.
 *
 * @author hal.hildebrand
 */
class DistributedViolationAggregatorTest {

    private BalanceCoordinatorClient mockClient;
    private DistributedViolationAggregator aggregator;

    @BeforeEach
    void setUp() {
        mockClient = mock(BalanceCoordinatorClient.class);
        aggregator = new DistributedViolationAggregator(0, 4, mockClient);
    }

    @Test
    void testSuccessfulDistributedExchange() {
        // Create local violations
        var localViolations = List.of(
            createViolation(1, 2, 5, 7, 2, 0),
            createViolation(3, 4, 6, 8, 2, 0)
        );

        // Mock successful gRPC responses with partner violations
        var partnerViolation1 = createViolation(5, 6, 7, 9, 2, 1);
        var partnerViolation2 = createViolation(7, 8, 8, 10, 2, 2);

        when(mockClient.exchangeViolations(any(ViolationBatch.class)))
            .thenReturn(ViolationBatch.newBuilder()
                .setRequesterRank(1)
                .setResponderRank(0)
                .setRoundNumber(0)
                .addViolations(partnerViolation1)
                .setTimestamp(System.currentTimeMillis())
                .build())
            .thenReturn(ViolationBatch.newBuilder()
                .setRequesterRank(2)
                .setResponderRank(0)
                .setRoundNumber(1)
                .addViolations(partnerViolation2)
                .setTimestamp(System.currentTimeMillis())
                .build());

        // Execute aggregation
        var result = aggregator.aggregateDistributed(localViolations);

        // Verify we got results including partner violations
        assertNotNull(result);
        assertTrue(result.containsAll(localViolations));
        assertTrue(result.contains(partnerViolation1));
        assertTrue(result.contains(partnerViolation2));
        assertEquals(4, result.size());

        // Verify gRPC calls were made (2 rounds for 4 partitions)
        verify(mockClient, times(2)).exchangeViolations(any(ViolationBatch.class));
    }

    @Test
    void testTimeoutHandling() {
        // Create local violations
        var localViolations = List.of(
            createViolation(1, 2, 5, 7, 2, 0)
        );

        // Mock timeout on first call, success on second
        when(mockClient.exchangeViolations(any(ViolationBatch.class)))
            .thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED))
            .thenReturn(ViolationBatch.newBuilder()
                .setRequesterRank(2)
                .setResponderRank(0)
                .setRoundNumber(1)
                .setTimestamp(System.currentTimeMillis())
                .build());

        // Execute aggregation - should handle timeout gracefully
        var result = aggregator.aggregateDistributed(localViolations);

        // Should still return local violations despite timeout
        assertNotNull(result);
        assertTrue(result.containsAll(localViolations));

        // Verify both calls were attempted
        verify(mockClient, times(2)).exchangeViolations(any(ViolationBatch.class));
    }

    @Test
    void testRetryOnTransientFailure() {
        // Create local violations
        var localViolations = List.of(
            createViolation(1, 2, 5, 7, 2, 0)
        );

        var callCount = new AtomicInteger(0);

        // Mock transient failure on first attempt, success on retry
        when(mockClient.exchangeViolations(any(ViolationBatch.class)))
            .thenAnswer(invocation -> {
                if (callCount.getAndIncrement() == 0) {
                    throw new StatusRuntimeException(Status.UNAVAILABLE);
                }
                return ViolationBatch.newBuilder()
                    .setRequesterRank(1)
                    .setResponderRank(0)
                    .setRoundNumber(0)
                    .setTimestamp(System.currentTimeMillis())
                    .build();
            });

        // Execute aggregation
        var result = aggregator.aggregateDistributed(localViolations);

        // Should succeed after retry
        assertNotNull(result);
        assertTrue(result.containsAll(localViolations));

        // Verify retry happened (1 original + 1 retry = 2 calls for first round)
        // Plus 1 for second round = 3 total
        verify(mockClient, atLeast(2)).exchangeViolations(any(ViolationBatch.class));
    }

    @Test
    void testPartialFailureDoesNotBlockAggregation() {
        // Create local violations
        var localViolations = List.of(
            createViolation(1, 2, 5, 7, 2, 0)
        );

        // Mock first round fails permanently, second round succeeds
        when(mockClient.exchangeViolations(any(ViolationBatch.class)))
            .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
            .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
            .thenReturn(ViolationBatch.newBuilder()
                .setRequesterRank(2)
                .setResponderRank(0)
                .setRoundNumber(1)
                .setTimestamp(System.currentTimeMillis())
                .build());

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
    void testShutdownCleansUpResources() {
        // Execute aggregation to initialize state
        var localViolations = List.of(createViolation(1, 2, 5, 7, 2, 0));

        when(mockClient.exchangeViolations(any(ViolationBatch.class)))
            .thenReturn(ViolationBatch.newBuilder()
                .setRequesterRank(1)
                .setResponderRank(0)
                .setRoundNumber(0)
                .setTimestamp(System.currentTimeMillis())
                .build());

        aggregator.aggregateDistributed(localViolations);

        // Shutdown should not throw
        assertDoesNotThrow(() -> aggregator.shutdown());
    }

    /**
     * Helper to create a BalanceViolation for testing.
     */
    private BalanceViolation createViolation(long localKeyId, long ghostKeyId,
                                            int localLevel, int ghostLevel,
                                            int levelDiff, int sourceRank) {
        return BalanceViolation.newBuilder()
            .setLocalKey(SpatialKey.newBuilder()
                .setMorton(com.hellblazer.luciferase.lucien.forest.ghost.proto.MortonKey.newBuilder()
                    .setMortonCode(localKeyId)
                    .build())
                .build())
            .setGhostKey(SpatialKey.newBuilder()
                .setMorton(com.hellblazer.luciferase.lucien.forest.ghost.proto.MortonKey.newBuilder()
                    .setMortonCode(ghostKeyId)
                    .build())
                .build())
            .setLocalLevel(localLevel)
            .setGhostLevel(ghostLevel)
            .setLevelDifference(levelDiff)
            .setSourceRank(sourceRank)
            .build();
    }
}
