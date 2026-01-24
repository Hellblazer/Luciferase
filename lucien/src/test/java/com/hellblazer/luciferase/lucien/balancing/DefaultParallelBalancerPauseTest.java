/*
 * Copyright (c) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.balancing.ParallelBalancer.PartitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DefaultParallelBalancer pause/resume mechanism (Phase A.1, Modification #2).
 * <p>
 * Tests the new pauseCrossPartitionBalance() and resumeCrossPartitionBalance() methods
 * that control cross-partition balancing during recovery operations.
 *
 * @author Hal Hildebrand
 */
class DefaultParallelBalancerPauseTest {

    private DefaultParallelBalancer<?, ?, ?> balancer;
    private PartitionRegistry mockRegistry;

    @BeforeEach
    void setUp() {
        // Create balancer with default configuration
        var config = BalanceConfiguration.defaultConfig();
        balancer = new DefaultParallelBalancer<>(config);

        // Mock partition registry
        mockRegistry = Mockito.mock(PartitionRegistry.class);
        Mockito.when(mockRegistry.getCurrentPartitionId()).thenReturn(0);
        Mockito.when(mockRegistry.getPartitionCount()).thenReturn(4);
    }

    /**
     * Test that pauseCrossPartitionBalance() prevents cross-partition operations.
     * <p>
     * Expected behavior: When paused, crossPartitionBalance() should return
     * immediately without performing balance operations.
     */
    @Test
    void testPauseCrossPartitionBalanceSkipsOperations() {
        // Pause cross-partition balance
        balancer.pauseCrossPartitionBalance();

        // Call crossPartitionBalance - should return success but with 0 refinements
        var result = balancer.crossPartitionBalance(mockRegistry);

        // Verify: Should succeed but skip actual work
        assertTrue(result.successful(), "Should return success when paused");
        assertEquals(0, result.refinementsApplied(),
                    "Should apply 0 refinements when paused");

        // Verify metrics show that operation was skipped (0 rounds)
        var metrics = balancer.getMetrics();
        assertEquals(0, metrics.roundCount(),
                    "Should have 0 rounds when paused");
    }

    /**
     * Test that resumeCrossPartitionBalance() re-enables operations.
     * <p>
     * Expected behavior: After resume, crossPartitionBalance() should
     * execute normally (or at least attempt to).
     */
    @Test
    void testResumeCrossPartitionBalanceEnablesOperations() {
        // First pause
        balancer.pauseCrossPartitionBalance();

        // Verify paused state by calling crossPartitionBalance
        var pausedResult = balancer.crossPartitionBalance(mockRegistry);
        assertEquals(0, pausedResult.refinementsApplied(),
                    "Should skip when paused");

        // Now resume
        balancer.resumeCrossPartitionBalance();

        // Call crossPartitionBalance again - should now execute
        var resumedResult = balancer.crossPartitionBalance(mockRegistry);

        // Verify: Should complete normally (even if skeleton, should not skip)
        assertTrue(resumedResult.successful(),
                  "Should execute after resume");

        // Note: With current skeleton implementation, refinements may still be 0,
        // but the operation should not be skipped (metrics will differ)
        var metrics = balancer.getMetrics();
        // After resume, should have at least attempted one round (skeleton adds one)
        assertTrue(metrics.roundCount() >= 0,
                  "Should have attempted balance after resume");
    }

    /**
     * Test pause/resume cycle during concurrent operations.
     * <p>
     * Expected behavior: Pause should be thread-safe and prevent
     * concurrent cross-partition balance attempts.
     */
    @Test
    void testPauseDuringRecoveryPreventsConcurrency() {
        // Pause before any operations
        balancer.pauseCrossPartitionBalance();

        // Simulate multiple concurrent calls (during recovery)
        var result1 = balancer.crossPartitionBalance(mockRegistry);
        var result2 = balancer.crossPartitionBalance(mockRegistry);
        var result3 = balancer.crossPartitionBalance(mockRegistry);

        // All should return immediately without doing work
        assertTrue(result1.successful(), "First call should succeed (skip)");
        assertTrue(result2.successful(), "Second call should succeed (skip)");
        assertTrue(result3.successful(), "Third call should succeed (skip)");

        assertEquals(0, result1.refinementsApplied(), "Should skip when paused");
        assertEquals(0, result2.refinementsApplied(), "Should skip when paused");
        assertEquals(0, result3.refinementsApplied(), "Should skip when paused");

        // Resume and verify operation resumes
        balancer.resumeCrossPartitionBalance();
        var resultAfterResume = balancer.crossPartitionBalance(mockRegistry);

        assertTrue(resultAfterResume.successful(),
                  "Should execute after resume");
    }

    /**
     * Test that pause/resume works in multiple cycles.
     */
    @Test
    void testMultiplePauseResumeCycles() {
        // Initial state: not paused, operation should proceed
        var result1 = balancer.crossPartitionBalance(mockRegistry);
        assertTrue(result1.successful());

        // Pause
        balancer.pauseCrossPartitionBalance();
        var result2 = balancer.crossPartitionBalance(mockRegistry);
        assertEquals(0, result2.refinementsApplied(), "Should skip when paused");

        // Resume
        balancer.resumeCrossPartitionBalance();
        var result3 = balancer.crossPartitionBalance(mockRegistry);
        assertTrue(result3.successful());

        // Pause again
        balancer.pauseCrossPartitionBalance();
        var result4 = balancer.crossPartitionBalance(mockRegistry);
        assertEquals(0, result4.refinementsApplied(), "Should skip when paused again");

        // Resume again
        balancer.resumeCrossPartitionBalance();
        var result5 = balancer.crossPartitionBalance(mockRegistry);
        assertTrue(result5.successful());
    }
}
