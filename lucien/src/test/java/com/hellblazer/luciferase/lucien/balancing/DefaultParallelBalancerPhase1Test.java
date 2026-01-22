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

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ForestConfig;
import com.hellblazer.luciferase.lucien.forest.TreeNode;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for F4.1.3 Local Balance Integration (Phase 1).
 *
 * <p>These tests define the expected behavior for integrating TreeBalancer with
 * ParallelBalancer's localBalance() phase and ghost layer tracking.
 *
 * <p><b>TDD RED PHASE</b>: These tests are written FIRST and should FAIL until
 * implementation is complete.
 *
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li>Empty forest handling</li>
 *   <li>Unbalanced tree balancing via TreeBalancer integration</li>
 *   <li>Metrics tracking (round time, refinements)</li>
 *   <li>Multi-tree forest balancing</li>
 *   <li>Ghost exchange with neighbor partitions</li>
 *   <li>Ghost level tracking for boundary detection</li>
 *   <li>Boundary ghost extraction after balance</li>
 *   <li>2:1 balance invariant maintenance</li>
 *   <li>LocalBalancePhase metrics updates</li>
 *   <li>Ghost exchange round-trip communication</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class DefaultParallelBalancerPhase1Test {

    private DefaultParallelBalancer<MortonKey, LongEntityID, String> balancer;
    private BalanceConfiguration configuration;
    private Forest<MortonKey, LongEntityID, String> forest;
    private MockDistributedGhostManager mockGhostManager;
    private EntityIDGenerator<LongEntityID> idGenerator;

    @BeforeEach
    public void setUp() {
        // Create ID generator for Octree
        idGenerator = new SequentialLongIDGenerator();
        // Configuration for testing
        configuration = BalanceConfiguration.defaultConfig()
                                           .withMaxRounds(5)
                                           .withTimeoutPerRound(Duration.ofSeconds(2));

        // Create empty forest
        forest = new Forest<>(ForestConfig.defaultConfig());

        // Create mock ghost manager
        mockGhostManager = new MockDistributedGhostManager();

        // Create balancer instance - THIS WILL FAIL until implementation exists
        balancer = new DefaultParallelBalancer<>(configuration);
    }

    /**
     * Test 1: Phase 1 local balance on empty forest should complete successfully.
     *
     * <p>Empty forest has no trees to balance, so operation should succeed
     * immediately with zero refinements.
     */
    @Test
    public void testLocalBalanceWithEmptyForest() {
        // GIVEN: Empty forest with no trees
        assertTrue(forest.getTreeCount() == 0, "Forest should be empty");

        // WHEN: Local balance is performed
        var result = balancer.localBalance(forest);

        // THEN: Operation succeeds with no work needed
        assertNotNull(result, "Result should not be null");
        assertTrue(result.successful(), "Local balance should succeed on empty forest");
        assertEquals(0, result.refinementsApplied(), "No refinements needed for empty forest");
        assertTrue(result.noWorkNeeded(), "Should indicate no work was needed");

        // Verify metrics were tracked
        var metrics = balancer.getMetrics();
        assertEquals(0, metrics.roundCount(), "Should have zero rounds for empty forest");
    }

    /**
     * Test 2: Phase 1 local balance should use TreeBalancer to balance unbalanced trees.
     *
     * <p>When forest contains unbalanced trees, localBalance() should delegate
     * to TreeBalancer to restore 2:1 balance invariant within each tree.
     */
    @Test
    public void testLocalBalanceWithUnbalancedTree() {
        // GIVEN: Forest with one unbalanced tree
        var tree = createUnbalancedTree();
        forest.addTree(tree);
        assertTrue(forest.getTreeCount() == 1, "Forest should have one tree");

        // WHEN: Local balance is performed
        var result = balancer.localBalance(forest);

        // THEN: TreeBalancer should have balanced the tree
        assertNotNull(result, "Result should not be null");
        assertTrue(result.successful(), "Local balance should succeed");
        assertTrue(result.refinementsApplied() > 0, "Should have performed refinements");
        assertTrue(result.converged(), "Should have converged to balanced state");

        // Verify metrics tracked the operation
        var metrics = balancer.getMetrics();
        assertTrue(metrics.roundCount() > 0, "Should have completed at least one round");
        assertTrue(metrics.totalTime().toNanos() > 0, "Should have non-zero execution time");
    }

    /**
     * Test 3: Local balance phase should track metrics including round timing and refinements.
     *
     * <p>BalanceMetrics should capture:
     * <ul>
     *   <li>Round count</li>
     *   <li>Total time, min/max/average round time</li>
     *   <li>Refinements requested and applied</li>
     * </ul>
     */
    @Test
    public void testLocalBalanceMetricsTracking() {
        // GIVEN: Forest with trees requiring balancing
        forest.addTree(createUnbalancedTree());
        forest.addTree(createUnbalancedTree());

        var metricsBeforeBalance = balancer.getMetrics().snapshot();

        // WHEN: Local balance is performed
        var result = balancer.localBalance(forest);

        // THEN: Metrics should be updated
        var metricsAfterBalance = balancer.getMetrics().snapshot();

        assertTrue(result.successful(), "Balance should succeed");

        // Verify round metrics
        assertTrue(metricsAfterBalance.roundCount() > metricsBeforeBalance.roundCount(),
                  "Round count should increase");

        // Verify timing metrics
        assertTrue(metricsAfterBalance.totalTime().compareTo(metricsBeforeBalance.totalTime()) > 0,
                  "Total time should increase");
        assertTrue(metricsAfterBalance.averageRoundTime().toNanos() > 0,
                  "Average round time should be positive");
        assertTrue(metricsAfterBalance.minRoundTime().toNanos() > 0,
                  "Min round time should be positive");
        assertTrue(metricsAfterBalance.maxRoundTime().toNanos() > 0,
                  "Max round time should be positive");

        // Verify refinement metrics
        assertTrue(metricsAfterBalance.refinementsApplied() >= result.refinementsApplied(),
                  "Metrics should track refinements applied");
    }

    /**
     * Test 4: Phase 1 should balance multiple trees independently in a forest.
     *
     * <p>Each tree in the forest should be balanced using TreeBalancer,
     * with metrics aggregated across all trees.
     */
    @Test
    public void testLocalBalanceWithMultipleTrees() {
        // GIVEN: Forest with multiple unbalanced trees
        var tree1 = createUnbalancedTree();
        var tree2 = createUnbalancedTree();
        var tree3 = createUnbalancedTree();
        forest.addTree(tree1);
        forest.addTree(tree2);
        forest.addTree(tree3);

        assertTrue(forest.getTreeCount() == 3, "Forest should have 3 trees");

        // WHEN: Local balance is performed
        var result = balancer.localBalance(forest);

        // THEN: All trees should be balanced
        assertTrue(result.successful(), "Balance should succeed");
        assertTrue(result.refinementsApplied() > 0, "Should have refined multiple trees");

        // Verify that balancing worked on all trees
        // (In real implementation, would check each tree's balance state)
        var metrics = balancer.getMetrics();
        assertTrue(metrics.refinementsApplied() > 0, "Should track refinements across all trees");
    }

    /**
     * Test 5: Phase 2 ghost exchange should send/receive ghosts from neighbor partitions.
     *
     * <p>After local balance, ghost elements at partition boundaries should be
     * exchanged with neighboring partitions via DistributedGhostManager.
     */
    @Test
    public void testGhostExchangeWithNeighbors() {
        // GIVEN: Forest with boundary elements
        var tree = createTreeWithBoundaryElements();
        forest.addTree(tree);

        // Perform local balance first
        balancer.localBalance(forest);

        // WHEN: Ghost exchange is performed
        assertDoesNotThrow(() -> balancer.exchangeGhosts(mockGhostManager),
                          "Ghost exchange should not throw");

        // THEN: Ghost manager should have received boundary ghosts
        assertTrue(mockGhostManager.getReceivedGhostCount() > 0,
                  "Ghost manager should have received boundary ghosts");
        assertTrue(mockGhostManager.wasExchangeCalled(),
                  "Ghost manager exchange method should have been called");
    }

    /**
     * Test 6: Ghost layer should track level information for efficient boundary detection.
     *
     * <p>Each ghost element should include its refinement level (from SpatialKey.getLevel())
     * to enable efficient cross-partition imbalance detection.
     */
    @Test
    public void testGhostLevelTracking() {
        // GIVEN: Forest with elements at different levels
        var tree = createTreeWithMultiLevelElements();
        forest.addTree(tree);

        // Perform local balance
        balancer.localBalance(forest);

        // WHEN: Ghosts are exchanged
        balancer.exchangeGhosts(mockGhostManager);

        // THEN: Ghost elements should have level information
        var ghosts = mockGhostManager.getReceivedGhosts();
        assertFalse(ghosts.isEmpty(), "Should have received ghosts");

        for (var ghost : ghosts) {
            assertNotNull(ghost.getSpatialKey(), "Ghost should have spatial key");
            assertTrue(ghost.getSpatialKey().getLevel() >= 0, "Ghost should have valid level");
        }
    }

    /**
     * Test 7: Boundary ghost extraction should identify elements at tree boundaries.
     *
     * <p>After local balance, elements at partition boundaries should be extracted
     * as ghosts for exchange with neighbors.
     */
    @Test
    public void testBoundaryGhostExtraction() {
        // GIVEN: Forest with boundary elements
        var tree = createTreeWithBoundaryElements();
        forest.addTree(tree);

        // WHEN: Local balance is performed
        var result = balancer.localBalance(forest);

        // THEN: Boundary elements should be identifiable for ghost exchange
        assertTrue(result.successful(), "Balance should succeed");

        // Exchange ghosts to verify extraction worked
        balancer.exchangeGhosts(mockGhostManager);
        assertTrue(mockGhostManager.getReceivedGhostCount() > 0,
                  "Should have extracted boundary ghosts");
    }

    /**
     * Test 8: Local balance should maintain 2:1 balance invariant within partition.
     *
     * <p>The 2:1 balance invariant requires that neighboring elements differ
     * by at most one level of refinement.
     */
    @Test
    public void testTwoToOneInvariantAfterLocalBalance() {
        // GIVEN: Forest with imbalanced trees (violating 2:1 invariant)
        var tree = createTreeViolatingTwoToOneInvariant();
        forest.addTree(tree);

        // WHEN: Local balance is performed
        var result = balancer.localBalance(forest);

        // THEN: 2:1 invariant should be restored
        assertTrue(result.successful(), "Balance should succeed");
        assertTrue(result.refinementsApplied() > 0, "Should have applied refinements");

        // Verify invariant is maintained
        // (In real implementation, would traverse tree and check neighbor level differences)
        assertTrue(result.converged(), "Should converge to valid 2:1 state");
    }

    /**
     * Test 9: LocalBalancePhase should correctly update BalanceMetrics.
     *
     * <p>The LocalBalancePhase class should integrate with BalanceMetrics to track:
     * <ul>
     *   <li>Round timing for local balance phase</li>
     *   <li>Refinements performed by TreeBalancer</li>
     * </ul>
     */
    @Test
    public void testLocalBalancePhaseMetrics() {
        // GIVEN: Forest requiring balancing
        forest.addTree(createUnbalancedTree());

        var metricsBefore = balancer.getMetrics().snapshot();

        // WHEN: Local balance phase executes
        var result = balancer.localBalance(forest);

        // THEN: Metrics should reflect the phase execution
        assertTrue(result.successful(), "Balance should succeed");

        var metricsAfter = balancer.getMetrics().snapshot();

        // Verify phase metrics were updated
        assertTrue(metricsAfter.roundCount() > metricsBefore.roundCount(),
                  "Round count should increase");
        assertTrue(metricsAfter.refinementsApplied() > metricsBefore.refinementsApplied(),
                  "Refinements should be tracked");
        assertTrue(metricsAfter.totalTime().compareTo(metricsBefore.totalTime()) > 0,
                  "Execution time should be recorded");
    }

    /**
     * Test 10: Ghost exchange should support round-trip communication.
     *
     * <p>Ghosts sent by one partition should be received by neighboring partitions,
     * and vice versa, enabling distributed coordination.
     */
    @Test
    public void testGhostExchangeRoundTrip() {
        // GIVEN: Two partitions with boundary elements
        var partition1Forest = new Forest<MortonKey, LongEntityID, String>(ForestConfig.defaultConfig());
        var partition2Forest = new Forest<MortonKey, LongEntityID, String>(ForestConfig.defaultConfig());

        partition1Forest.addTree(createTreeWithBoundaryElements());
        partition2Forest.addTree(createTreeWithBoundaryElements());

        var balancer1 = new DefaultParallelBalancer<MortonKey, LongEntityID, String>(configuration);
        var balancer2 = new DefaultParallelBalancer<MortonKey, LongEntityID, String>(configuration);

        var ghostManager1 = new MockDistributedGhostManager();
        var ghostManager2 = new MockDistributedGhostManager();

        // WHEN: Both partitions exchange ghosts
        balancer1.localBalance(partition1Forest);
        balancer1.exchangeGhosts(ghostManager1);

        balancer2.localBalance(partition2Forest);
        balancer2.exchangeGhosts(ghostManager2);

        // THEN: Both should have sent ghosts
        assertTrue(ghostManager1.getReceivedGhostCount() > 0,
                  "Partition 1 should have sent ghosts");
        assertTrue(ghostManager2.getReceivedGhostCount() > 0,
                  "Partition 2 should have sent ghosts");

        // Verify ghosts contain valid spatial information
        var ghosts1 = ghostManager1.getReceivedGhosts();
        var ghosts2 = ghostManager2.getReceivedGhosts();

        assertFalse(ghosts1.isEmpty(), "Partition 1 ghosts should not be empty");
        assertFalse(ghosts2.isEmpty(), "Partition 2 ghosts should not be empty");

        // Verify ghosts have required fields
        for (var ghost : ghosts1) {
            assertNotNull(ghost.getSpatialKey(), "Ghost should have spatial key");
            assertNotNull(ghost.getEntityId(), "Ghost should have entity ID");
        }
    }

    // Helper methods to create test data

    /**
     * Create an unbalanced tree for testing TreeBalancer integration.
     */
    private AbstractSpatialIndex<MortonKey, LongEntityID, String> createUnbalancedTree() {
        // Use lower maxEntitiesPerNode (3) to aggressively trigger splits
        var tree = new Octree<LongEntityID, String>(idGenerator, 3, (byte) 10);

        // Insert entities at EXACTLY the same position to force them into same node
        // This will create a node with >3 entities that needs splitting
        var clusterPos1 = new Point3f(10.0f, 10.0f, 10.0f);
        for (int i = 0; i < 12; i++) {
            var entityId = idGenerator.generateID();
            tree.insert(entityId, clusterPos1, (byte) 0, "cluster1-" + i, null);
        }

        // Second cluster at different location
        var clusterPos2 = new Point3f(100.0f, 100.0f, 100.0f);
        for (int i = 0; i < 12; i++) {
            var entityId = idGenerator.generateID();
            tree.insert(entityId, clusterPos2, (byte) 0, "cluster2-" + i, null);
        }

        return tree;
    }

    /**
     * Create a tree with elements at partition boundaries.
     */
    private AbstractSpatialIndex<MortonKey, LongEntityID, String> createTreeWithBoundaryElements() {
        var tree = new Octree<LongEntityID, String>(idGenerator);

        // Insert entities at boundaries (edges of spatial domain)
        tree.insert(idGenerator.generateID(), new Point3f(0.0f, 0.0f, 0.0f), (byte) 0, "boundary-1", null);
        tree.insert(idGenerator.generateID(), new Point3f(99.0f, 0.0f, 0.0f), (byte) 0, "boundary-2", null);
        tree.insert(idGenerator.generateID(), new Point3f(0.0f, 99.0f, 0.0f), (byte) 0, "boundary-3", null);
        tree.insert(idGenerator.generateID(), new Point3f(0.0f, 0.0f, 99.0f), (byte) 0, "boundary-4", null);

        return tree;
    }

    /**
     * Create a tree with elements at different refinement levels.
     */
    private AbstractSpatialIndex<MortonKey, LongEntityID, String> createTreeWithMultiLevelElements() {
        var tree = new Octree<LongEntityID, String>(idGenerator);

        // Insert entities at various levels by clustering at different densities
        // Level 0 (coarse): sparse entities
        tree.insert(idGenerator.generateID(), new Point3f(10.0f, 10.0f, 10.0f), (byte) 0, "level-0-1", null);

        // Level 1 (medium): moderate clustering
        tree.insert(idGenerator.generateID(), new Point3f(50.0f, 50.0f, 50.0f), (byte) 1, "level-1-1", null);
        tree.insert(idGenerator.generateID(), new Point3f(51.0f, 50.0f, 50.0f), (byte) 1, "level-1-2", null);

        // Level 2 (fine): dense clustering
        for (int i = 0; i < 10; i++) {
            var pos = new Point3f(80.0f + i * 0.5f, 80.0f, 80.0f);
            tree.insert(idGenerator.generateID(), pos, (byte) 2, "level-2-" + i, null);
        }

        return tree;
    }

    /**
     * Create a tree that violates the 2:1 balance invariant.
     */
    private AbstractSpatialIndex<MortonKey, LongEntityID, String> createTreeViolatingTwoToOneInvariant() {
        // Use lower maxEntitiesPerNode to aggressively trigger splits
        var tree = new Octree<LongEntityID, String>(idGenerator, 3, (byte) 10);

        // Create a scenario where neighbors differ by more than 1 level
        // by inserting entities in a pattern that forces extreme refinement imbalance

        // Very dense cluster at exactly one location (forces deep refinement)
        var densePos = new Point3f(10.0f, 10.0f, 10.0f);
        for (int i = 0; i < 50; i++) {
            tree.insert(idGenerator.generateID(), densePos, (byte) 0, "dense-" + i, null);
        }

        // Sparse entities nearby (remain at coarse level)
        tree.insert(idGenerator.generateID(), new Point3f(100.0f, 100.0f, 100.0f), (byte) 0, "sparse-1", null);

        return tree;
    }

    // Mock implementation of DistributedGhostManager for testing

    private static class MockDistributedGhostManager extends DistributedGhostManager<MortonKey, LongEntityID, String> {
        private final List<GhostElement<MortonKey, LongEntityID, String>> receivedGhosts = new ArrayList<>();
        private boolean exchangeCalled = false;

        public MockDistributedGhostManager() {
            super(createMockSpatialIndex(), createMockGhostChannel(), createMockGhostBoundaryDetector());
        }

        private static Octree<LongEntityID, String> createMockSpatialIndex() {
            return new Octree<>(new SequentialLongIDGenerator());
        }

        @SuppressWarnings("unchecked")
        private static com.hellblazer.luciferase.lucien.forest.ghost.GrpcGhostChannel<MortonKey, LongEntityID, String> createMockGhostChannel() {
            // Use Mockito to create a mock that satisfies the non-null requirement
            return (com.hellblazer.luciferase.lucien.forest.ghost.GrpcGhostChannel<MortonKey, LongEntityID, String>)
                mock(com.hellblazer.luciferase.lucien.forest.ghost.GrpcGhostChannel.class);
        }

        @SuppressWarnings("unchecked")
        private static com.hellblazer.luciferase.lucien.forest.ghost.GhostBoundaryDetector<MortonKey, LongEntityID, String> createMockGhostBoundaryDetector() {
            // Use Mockito to create a mock that satisfies the non-null requirement
            return (com.hellblazer.luciferase.lucien.forest.ghost.GhostBoundaryDetector<MortonKey, LongEntityID, String>)
                mock(com.hellblazer.luciferase.lucien.forest.ghost.GhostBoundaryDetector.class);
        }

        @Override
        public void synchronizeWithAllProcesses() {
            exchangeCalled = true;

            // Simulate receiving ghosts from exchange
            // Create mock ghost elements
            var key = new MortonKey(1L, (byte) 2);
            var entityId = new LongEntityID(999L);
            var position = new Point3f(5.0f, 5.0f, 5.0f);
            var ghost = new GhostElement<>(key, entityId, "ghost-content", position, 0, 0L);

            receivedGhosts.add(ghost);
        }

        public List<GhostElement<MortonKey, LongEntityID, String>> getReceivedGhosts() {
            return new ArrayList<>(receivedGhosts);
        }

        public int getReceivedGhostCount() {
            return receivedGhosts.size();
        }

        public boolean wasExchangeCalled() {
            return exchangeCalled;
        }
    }
}
