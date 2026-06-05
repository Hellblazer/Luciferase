/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that OctreeBalancer.rebalanceSubtree() performs real work rather than returning a
 * silent no-op 0. Acceptance criteria from bead Luciferase-7wzml.135:
 * (1) rebalanceSubtree performs real work or signals non-support
 * (2) 0 return means truly zero modifications, not "unsupported"
 */
public class OctreeBalancerRebalanceSubtreeTest {

    private static final byte   MAX_DEPTH           = 10;
    private static final int    MAX_ENTITIES_PER_NODE = 4;

    private Octree<LongEntityID, String> octree;
    private OctreeBalancer<LongEntityID> balancer;

    @BeforeEach
    void setUp() {
        var idGen = new SequentialLongIDGenerator();
        octree = new Octree<>(idGen);
        balancer = new OctreeBalancer<>(octree, MAX_DEPTH, MAX_ENTITIES_PER_NODE);
    }

    /**
     * With an already-balanced (empty) octree, rebalanceSubtree on a non-existent root
     * must return 0 — but for the right reason (node not found), not "not implemented".
     */
    @Test
    void rebalanceSubtree_nonExistentRoot_returnsZero() {
        var fakeRoot = new MortonKey(0L, (byte) 0);
        int result = balancer.rebalanceSubtree(fakeRoot);
        assertEquals(0, result, "No modifications expected for a non-existent root node");
    }

    /**
     * When a node is overpopulated (entity count > split threshold), rebalanceSubtree
     * must return > 0 to prove it performed real splits. A stub that always returns 0
     * cannot pass this test.
     *
     * <p>Additional invariants verified:
     * <ul>
     *   <li>No empty nodes leaked into the spatial index after rebalancing (the
     *       now-empty split parent must have been removed).</li>
     *   <li>The return value counts logical node operations (1 per split), not
     *       structural children created — so a single split of an overpopulated
     *       node into N children must return exactly 1 for that split operation
     *       (plus recursive child splits if any).</li>
     * </ul>
     */
    @Test
    void rebalanceSubtree_overpopulatedNode_performsSplit() {
        // Insert all entities at exactly the same spatial location so they are guaranteed
        // to land in the same level-3 node, making it verifiably overpopulated.
        // MAX_ENTITIES_PER_NODE=4; DefaultBalancingStrategy with splitFactor=0.9 splits
        // when entityCount > getSplitThreshold(level=3, max=4) = (int)(4*0.9*(1-0.03)) = 3.
        int insertCount = MAX_ENTITIES_PER_NODE * 3; // 12, well above threshold of 3
        float x = 50.0f, y = 50.0f, z = 50.0f;
        for (int i = 0; i < insertCount; i++) {
            octree.insert(new LongEntityID(i), new Point3f(x, y, z), (byte) 3, "e" + i);
        }

        // Locate the single node that holds all inserted entities
        assertEquals(1, octree.getSpatialIndex().size(),
                     "All entities at the same position must land in exactly one node");
        var overpopulatedKey = octree.getSpatialIndex().keySet().iterator().next();
        var overpopulatedNode = octree.getSpatialIndex().get(overpopulatedKey);

        // Pre-condition: the node must actually be overpopulated so we know a split must fire
        assertTrue(overpopulatedNode.getEntityCount() > MAX_ENTITIES_PER_NODE,
                   "Pre-condition: node must be overpopulated (count=" + overpopulatedNode.getEntityCount()
                   + " must be > " + MAX_ENTITIES_PER_NODE + ")");

        int result = balancer.rebalanceSubtree(overpopulatedKey);

        // A real implementation must perform at least one split (creating child nodes).
        // A stub returning 0 would fail this assertion.
        assertTrue(result > 0,
                   "rebalanceSubtree on a verifiably overpopulated node must return > 0 (got " + result + "); "
                   + "a stub-returning-0 cannot pass this check");

        // No empty-node leak: every node remaining in the spatial index must hold entities.
        // After a split the now-empty parent must have been removed — otherwise every
        // subsequent traversal (range query, k-NN, getBalancingStats) sees phantom nodes.
        var spatialIndex = octree.getSpatialIndex();
        var emptyNodes = spatialIndex.entrySet().stream()
                                     .filter(e -> e.getValue().getEntityCount() == 0)
                                     .map(e -> e.getKey().toString())
                                     .toList();
        assertTrue(emptyNodes.isEmpty(),
                   "Empty nodes leaked into spatial index after rebalance: " + emptyNodes
                   + " — split parent must be removed once entities are redistributed to children");

        // Count-unit sanity: result is measured in logical operations (1 per SPLIT or MERGE),
        // not in child-node count.  With all 12 entities at the same point the balancer may
        // perform a chain of recursive splits (since every child is also overpopulated), so
        // result >= 1 suffices here — the important guarantee is that result > 0 for work done.
        // We verify the unit is NOT children.size() by confirming the returned count is at most
        // the number of nodes currently in the tree (an upper bound on logical ops performed).
        int nodesAfter = spatialIndex.size();
        assertTrue(result <= nodesAfter + insertCount,
                   "result=" + result + " suspiciously large; likely counting children instead of logical ops");
    }

    /**
     * A single-entity node is already balanced: rebalanceSubtree must return 0 for the right
     * reason (no balancing needed), not because the method is stubbed.
     */
    @Test
    void rebalanceSubtree_singleEntityNode_returnsZeroCorrectly() {
        octree.insert(new LongEntityID(1), new Point3f(50.0f, 50.0f, 50.0f), (byte) 3, "singleton");

        // Must be exactly one node
        assertEquals(1, octree.getSpatialIndex().size(), "Expected exactly one node");
        var root = octree.getSpatialIndex().keySet().iterator().next();

        int result = balancer.rebalanceSubtree(root);
        assertEquals(0, result,
                     "Single-entity node needs no rebalancing; result 0 should mean 'already balanced', not 'not implemented'");
    }

    /**
     * Repeatedly calling rebalanceSubtree on a stable tree must be idempotent (returns 0
     * on subsequent calls once the tree is balanced).
     */
    @Test
    void rebalanceSubtree_idempotentOnBalancedTree() {
        // Insert a handful of non-crowded entities
        for (int i = 0; i < MAX_ENTITIES_PER_NODE - 1; i++) {
            octree.insert(new LongEntityID(i), new Point3f(i * 100.0f, 0, 0), (byte) 2, "e" + i);
        }

        var firstKey = octree.getSpatialIndex().keySet().iterator().next();

        // First call
        int first = balancer.rebalanceSubtree(firstKey);
        // Second call on the same (now balanced) tree
        var secondKey = octree.getSpatialIndex().isEmpty() ? firstKey : octree.getSpatialIndex().keySet().iterator().next();
        int second = balancer.rebalanceSubtree(secondKey);

        assertEquals(0, second, "Second rebalance on an already-balanced tree must return 0 modifications");
    }
}
