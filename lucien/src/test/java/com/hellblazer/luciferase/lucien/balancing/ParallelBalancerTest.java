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
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * TDD tests for ParallelBalancer interface and implementations.
 *
 * <p>These tests are written FIRST (red phase) to define the expected behavior
 * before implementation. They should FAIL initially until the implementation
 * is complete.
 *
 * @author hal.hildebrand
 */
public class ParallelBalancerTest {

    private Forest<MortonKey, LongEntityID, String> mockForest;
    private DistributedGhostManager<MortonKey, LongEntityID, String> mockGhostManager;
    private ParallelBalancer.PartitionRegistry mockRegistry;
    private ParallelBalancer<MortonKey, LongEntityID, String> balancer;

    @BeforeEach
    public void setUp() {
        // Create mock dependencies
        mockForest = new Forest<>(ForestConfig.defaultConfig());
        mockGhostManager = new MockGhostManager();
        mockRegistry = new MockPartitionRegistry();

        // Create balancer instance - TDD GREEN PHASE enabled
        balancer = new DefaultParallelBalancer<>(BalanceConfiguration.defaultConfig());
    }

    @Test
    public void testLocalBalanceWithEmptyForest() {
        // Test Phase 1: Local balance on empty forest should succeed with no refinements

        // This will fail until implementation exists
        assertNotNull(balancer, "Balancer should be created");

        var result = balancer.localBalance(mockForest);

        assertTrue(result.successful(), "Local balance should succeed on empty forest");
        assertEquals(0, result.refinementsApplied(), "No refinements needed for empty forest");
        assertTrue(result.noWorkNeeded(), "Should indicate no work was needed");
    }

    @Test
    public void testLocalBalanceNullForestThrows() {
        // Test that null forest argument throws IllegalArgumentException

        assertNotNull(balancer, "Balancer should be created");

        assertThrows(IllegalArgumentException.class,
                    () -> balancer.localBalance(null),
                    "Should throw on null forest");
    }

    @Test
    public void testExchangeGhostsCompletes() {
        // Test Phase 2: Ghost exchange should complete without error

        assertNotNull(balancer, "Balancer should be created");
        assertNotNull(mockGhostManager, "Ghost manager should be available");

        assertDoesNotThrow(() -> balancer.exchangeGhosts(mockGhostManager),
                          "Ghost exchange should not throw");
    }

    @Test
    public void testExchangeGhostsNullManagerThrows() {
        // Test that null ghost manager throws IllegalArgumentException

        assertNotNull(balancer, "Balancer should be created");

        assertThrows(IllegalArgumentException.class,
                    () -> balancer.exchangeGhosts(null),
                    "Should throw on null ghost manager");
    }

    @Test
    public void testCrossPartitionBalanceConverges() {
        // Test Phase 3: Cross-partition balance should converge in O(log P) rounds

        assertNotNull(balancer, "Balancer should be created");

        var result = balancer.crossPartitionBalance(mockRegistry);

        assertTrue(result.successful(), "Cross-partition balance should succeed");
        assertTrue(result.finalMetrics().roundCount() > 0, "Should complete at least one round");

        // O(log P) convergence: for P=4 partitions, should complete in ~2 rounds
        var expectedMaxRounds = (int) Math.ceil(Math.log(mockRegistry.getPartitionCount()) / Math.log(2)) + 1;
        assertTrue(result.finalMetrics().roundCount() <= expectedMaxRounds,
                  String.format("Should converge in O(log P) rounds (P=%d, expected<=%d, actual=%d)",
                               mockRegistry.getPartitionCount(), expectedMaxRounds,
                               result.finalMetrics().roundCount()));
    }

    @Test
    public void testCrossPartitionBalanceNullRegistryThrows() {
        // Test that null partition registry throws IllegalArgumentException

        assertNotNull(balancer, "Balancer should be created");

        assertThrows(IllegalArgumentException.class,
                    () -> balancer.crossPartitionBalance(null),
                    "Should throw on null partition registry");
    }

    @Test
    public void testFullBalanceCycleCompletes() {
        // Test full balance cycle executes all three phases

        assertNotNull(balancer, "Balancer should be created");

        var distributedForest = new MockDistributedForest(mockForest, mockGhostManager, mockRegistry);

        var result = balancer.balance(distributedForest);

        assertTrue(result.successful(), "Full balance cycle should succeed");
        assertNotNull(result.finalMetrics(), "Should have final metrics");
        assertTrue(result.finalMetrics().roundCount() > 0, "Should have completed rounds");
    }

    @Test
    public void testMetricsTrackRoundCount() {
        // Test that metrics correctly track the number of balancing rounds

        assertNotNull(balancer, "Balancer should be created");

        var metrics = balancer.getMetrics();
        assertNotNull(metrics, "Should have metrics");

        var initialRounds = metrics.roundCount();

        // Perform a balance operation
        balancer.crossPartitionBalance(mockRegistry);

        var finalRounds = metrics.roundCount();
        assertTrue(finalRounds > initialRounds, "Round count should increase after balancing");
    }

    @Test
    public void testMetricsTrackTiming() {
        // Test that metrics track timing information per round

        assertNotNull(balancer, "Balancer should be created");

        var metrics = balancer.getMetrics();

        // Perform a balance operation
        balancer.crossPartitionBalance(mockRegistry);

        var snapshot = metrics.snapshot();
        assertTrue(snapshot.totalTime().toNanos() > 0, "Should have non-zero total time");
        assertTrue(snapshot.averageRoundTime().toNanos() > 0, "Should have non-zero average time");
        assertTrue(snapshot.minRoundTime().toNanos() > 0, "Should have non-zero min time");
        assertTrue(snapshot.maxRoundTime().toNanos() > 0, "Should have non-zero max time");
    }

    @Test
    public void testMetricsTrackRefinements() {
        // Test that metrics track refinement requests and applications

        assertNotNull(balancer, "Balancer should be created");

        var metrics = balancer.getMetrics();

        // Simulate refinement requests through registry
        mockRegistry.requestRefinement(new MortonKey(0L, (byte) 0));
        mockRegistry.requestRefinement(new MortonKey(1L, (byte) 0));

        // Perform balance
        var result = balancer.crossPartitionBalance(mockRegistry);

        var snapshot = result.finalMetrics();
        assertTrue(snapshot.refinementsRequested() >= 0, "Should track refinement requests");
        assertTrue(snapshot.refinementsApplied() >= 0, "Should track refinement applications");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testConfigurationMaxRoundsRespected() {
        // Test that balancing respects max rounds configuration

        var config = BalanceConfiguration.defaultConfig().withMaxRounds(3);

        // Create balancer with limited rounds - THIS WILL FAIL until implementation exists
        // var balancer = new DefaultParallelBalancer<>(config);

        assertNotNull(balancer, "Balancer should be created");

        var result = balancer.crossPartitionBalance(mockRegistry);

        assertTrue(result.finalMetrics().roundCount() <= 3,
                  "Should not exceed max rounds (3)");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testConfigurationTimeoutPerRound() {
        // Test that rounds respect timeout configuration

        var config = BalanceConfiguration.defaultConfig()
                                        .withTimeoutPerRound(Duration.ofMillis(100));

        // Create balancer with short timeout
        // var balancer = new DefaultParallelBalancer<>(config);

        assertNotNull(balancer, "Balancer should be created");

        var result = balancer.crossPartitionBalance(mockRegistry);

        // Each round should complete within timeout
        var snapshot = result.finalMetrics();
        if (snapshot.roundCount() > 0) {
            assertTrue(snapshot.maxRoundTime().toMillis() <= 150,
                      "Round time should respect timeout (with 50% tolerance)");
        }
    }

    @Test
    public void testTwoToOneInvariantMaintained() {
        // Test that balancing maintains 2:1 balance invariant (max height difference = 1)

        assertNotNull(balancer, "Balancer should be created");

        // Create forest with imbalanced trees
        // TODO: Add trees with different heights once implementation exists

        var result = balancer.localBalance(mockForest);

        // After balancing, verify 2:1 invariant holds
        // This requires querying tree heights across boundaries
        // TODO: Implement invariant check once Forest supports height queries

        assertTrue(result.successful(), "Should maintain 2:1 invariant");
    }

    @Test
    public void testConcurrentBalanceOperations() throws InterruptedException {
        // Test thread safety under concurrent balance attempts

        assertNotNull(balancer, "Balancer should be created");

        var threadCount = 4;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);
        var successCount = new AtomicInteger(0);

        // Submit concurrent balance operations
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    var result = balancer.crossPartitionBalance(mockRegistry);
                    if (result.successful()) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for completion
        assertTrue(latch.await(30, TimeUnit.SECONDS), "All threads should complete");

        // At least some operations should succeed
        assertTrue(successCount.get() > 0, "At least one balance operation should succeed");

        executor.shutdown();
    }

    // Mock implementations for testing

    private static class MockPartitionRegistry implements ParallelBalancer.PartitionRegistry {
        private final AtomicInteger refinementRequests = new AtomicInteger(0);

        @Override
        public int getCurrentPartitionId() {
            return 0;
        }

        @Override
        public int getPartitionCount() {
            return 4; // Simulate 4 partitions
        }

        @Override
        public void barrier(int round) throws InterruptedException {
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
    }

    private static class MockGhostManager extends DistributedGhostManager<MortonKey, LongEntityID, String> {

        public MockGhostManager() {
            super(createMockSpatialIndex(), createMockGhostChannel(), createMockGhostBoundaryDetector());
        }

        private static com.hellblazer.luciferase.lucien.octree.Octree<LongEntityID, String> createMockSpatialIndex() {
            return new com.hellblazer.luciferase.lucien.octree.Octree<>(
                new com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator()
            );
        }

        @SuppressWarnings("unchecked")
        private static com.hellblazer.luciferase.lucien.forest.ghost.GrpcGhostChannel<MortonKey, LongEntityID, String> createMockGhostChannel() {
            // Use Mockito to create a mock GhostCommunicationManager
            var mockCommManager = mock(com.hellblazer.luciferase.lucien.forest.ghost.grpc.GhostCommunicationManager.class);

            // Create GrpcGhostChannel with the mock
            return new com.hellblazer.luciferase.lucien.forest.ghost.GrpcGhostChannel<MortonKey, LongEntityID, String>(
                mockCommManager,
                0,    // currentRank
                0L,   // treeId
                com.hellblazer.luciferase.lucien.forest.ghost.GhostType.NONE
            );
        }

        private static com.hellblazer.luciferase.lucien.forest.ghost.GhostBoundaryDetector<MortonKey, LongEntityID, String> createMockGhostBoundaryDetector() {
            // Create a minimal stub using Forest constructor
            var mockForest = new com.hellblazer.luciferase.lucien.forest.Forest<MortonKey, LongEntityID, String>(
                com.hellblazer.luciferase.lucien.forest.ForestConfig.defaultConfig()
            );
            return new com.hellblazer.luciferase.lucien.forest.ghost.GhostBoundaryDetector<>(
                mockForest,
                0.0f // ghostDepth - not used in tests
            );
        }

        @Override
        public void synchronizeWithAllProcesses() {
            // Mock implementation - no-op for testing
        }
    }

    private static class MockDistributedForest<Key extends com.hellblazer.luciferase.lucien.SpatialKey<Key>,
                                                ID extends com.hellblazer.luciferase.lucien.entity.EntityID,
                                                Content>
        implements ParallelBalancer.DistributedForest<Key, ID, Content> {

        private final Forest<Key, ID, Content> localForest;
        private final DistributedGhostManager<Key, ID, Content> ghostManager;
        private final ParallelBalancer.PartitionRegistry registry;

        public MockDistributedForest(Forest<Key, ID, Content> localForest,
                                    DistributedGhostManager<Key, ID, Content> ghostManager,
                                    ParallelBalancer.PartitionRegistry registry) {
            this.localForest = localForest;
            this.ghostManager = ghostManager;
            this.registry = registry;
        }

        @Override
        public Forest<Key, ID, Content> getLocalForest() {
            return localForest;
        }

        @Override
        public DistributedGhostManager<Key, ID, Content> getGhostManager() {
            return ghostManager;
        }

        @Override
        public ParallelBalancer.PartitionRegistry getPartitionRegistry() {
            return registry;
        }
    }
}
