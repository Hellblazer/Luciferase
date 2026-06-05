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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultBalancingStrategy} focusing on the shouldMerge / getMaxEntitiesForLevel
 * defect: the hardcoded 100 capacity was replaced with the configured maxEntitiesPerNode.
 *
 * @author hal.hildebrand
 */
public class DefaultBalancingStrategyTest {

    // Default params: mergeFactor=0.25, splitFactor=0.9
    // getMaxEntitiesForLevel(level) = maxEntitiesPerNode * max(0.7, 1 - level*0.01)
    // shouldMerge passes when totalEntities <= getMaxEntitiesForLevel(parentLevel) * splitFactor

    /**
     * Root node (level 0) must never merge.
     */
    @Test
    public void shouldMerge_rootLevel_neverMerges() {
        var strategy = new DefaultBalancingStrategy<LongEntityID>(0.25, 0.9, 0.3, 60000, 50);
        assertFalse(strategy.shouldMerge(1, (byte) 0, new int[] { 1, 1, 1 }),
                    "Root node must never be merged regardless of entity counts");
    }

    /**
     * With maxEntitiesPerNode=50, parentLevel=4:
     *   parentMaxEntities = 50 * max(0.7, 1-0.04) = 50 * 0.96 = 48
     *   threshold = 48 * 0.9 = 43.2  → 43
     *   total = 2+2+2+2 = 8 → well below 43 → should merge
     */
    @Test
    public void shouldMerge_lowCapacityTree_mergesWhenTotalFits() {
        var strategy = new DefaultBalancingStrategy<LongEntityID>(0.25, 0.9, 0.3, 60000, 50);
        // nodeLevel=5, parentLevel=4
        assertTrue(strategy.shouldMerge(2, (byte) 5, new int[] { 2, 2, 2 }),
                   "Should merge: combined 8 entities fit within parent capacity of low-capacity tree");
    }

    /**
     * With maxEntitiesPerNode=50, parentLevel=4 (threshold ~43):
     *   total = 15+15+15+15 = 60 → exceeds 43 → must NOT merge
     * With the old hardcoded 100 this would have produced threshold = 100*0.7*0.9 ≈ 63
     * and would have allowed a spurious merge (60 <= 63). Fixed behaviour is correct refusal.
     */
    @Test
    public void shouldMerge_lowCapacityTree_doesNotMergeWhenTotalExceedsCapacity() {
        var strategy = new DefaultBalancingStrategy<LongEntityID>(0.25, 0.9, 0.3, 60000, 50);
        // nodeLevel=5, parentLevel=4 → parentMaxEntities≈48, threshold≈43
        // total = 15+15+15+15 = 60 > 43 → must not merge
        assertFalse(strategy.shouldMerge(15, (byte) 5, new int[] { 15, 15, 15 }),
                    "Should NOT merge: combined 60 entities exceeds parent capacity for a 50-entity tree");
    }

    /**
     * Boundary: total exactly equals the threshold → merge fires (<=, inclusive boundary).
     * maxEntitiesPerNode=100, nodeLevel=1, parentLevel=0:
     *   parentMaxEntities = 100 * max(0.7, 1-0) = 100
     *   threshold = 100 * 0.9 = 90
     *   total = 30+30+30+0 = 90 → exactly at boundary → should merge
     */
    @Test
    public void shouldMerge_atExactBoundary_merges() {
        var strategy = new DefaultBalancingStrategy<LongEntityID>(0.25, 0.9, 0.3, 60000, 100);
        assertTrue(strategy.shouldMerge(30, (byte) 1, new int[] { 30, 30 }),
                   "Should merge: total 90 is exactly at the 100-capacity threshold");
    }

    /**
     * Boundary+1: total one above threshold → must NOT merge.
     * Same config as above, total = 91 → should not merge.
     */
    @Test
    public void shouldMerge_oneAboveBoundary_doesNotMerge() {
        var strategy = new DefaultBalancingStrategy<LongEntityID>(0.25, 0.9, 0.3, 60000, 100);
        // 31+30+30=91 > 90
        assertFalse(strategy.shouldMerge(31, (byte) 1, new int[] { 30, 30 }),
                    "Should NOT merge: total 91 is one above the 90-entity threshold");
    }

    /**
     * Split/merge thresholds are consistent: getSplitThreshold uses maxEntitiesPerNode,
     * and getMaxEntitiesForLevel (used by shouldMerge) is derived from the same value,
     * so merge threshold < split threshold for the same node level.
     * (This was impossible to verify when getMaxEntitiesForLevel returned a hardcoded 100
     * regardless of the configured maxEntitiesPerNode.)
     */
    @Test
    public void splitAndMergeThresholds_areConsistentWithCapacity() {
        int capacity = 200;
        var strategy = new DefaultBalancingStrategy<LongEntityID>(0.25, 0.9, 0.3, 60000, capacity);
        byte level = 5;

        int splitThreshold = strategy.getSplitThreshold(level, capacity);
        int mergeThreshold = strategy.getMergeThreshold(level, capacity);

        // Both must scale with capacity
        assertTrue(splitThreshold > capacity / 2,
                   "Split threshold should be well above half capacity for capacity=" + capacity);
        assertTrue(mergeThreshold < splitThreshold,
                   "Merge threshold must be less than split threshold");
        assertTrue(mergeThreshold < capacity,
                   "Merge threshold must be less than configured capacity");
    }

    /**
     * The 4-arg constructor (no maxEntitiesPerNode) must still work and must not silently
     * diverge from shouldSplit behaviour (regression guard for existing call sites).
     */
    @Test
    public void fourArgConstructor_usesDefaultCapacity_splitMergeConsistent() {
        var strategy = new DefaultBalancingStrategy<LongEntityID>(0.25, 0.9, 0.3, 60000);
        byte level = (byte) 5;

        // With 100 default: splitThreshold at level 5 = 100 * 0.9 * max(0.7, 0.95) = 85
        // total=14 (5+3+4+2) should merge
        assertTrue(strategy.shouldMerge(5, level, new int[] { 3, 4, 2 }),
                   "4-arg ctor: should merge low-count siblings under default capacity");

        // total=135 across siblings should not merge
        assertFalse(strategy.shouldMerge(50, level, new int[] { 40, 45 }),
                    "4-arg ctor: should not merge when combined would overflow parent");
    }

    /**
     * Capacity-propagation: an Octree constructed with maxEntitiesPerNode=50 must wire that capacity
     * into its DefaultBalancingStrategy, not the hardcoded 100.
     *
     * Verification method: retrieve the strategy via getBalancingStrategy(), cast to DefaultBalancingStrategy,
     * and assert getConfiguredMaxEntitiesPerNode() == 50 (structural check), then confirm shouldMerge
     * produces the correct result for a total that would produce the WRONG answer under the old hardcoded 100.
     *
     * With capacity=50, nodeLevel=1, parentLevel=0:
     *   parentMaxEntities = 50 * max(0.7, 1.0) = 50
     *   threshold = 50 * 0.9 = 45
     *   total = 60 → 60 > 45 → must NOT merge (correct for 50-entity tree)
     *
     * Under the old bug (hardcoded 100):
     *   threshold = 100 * 0.9 = 90 → 60 <= 90 → would wrongly trigger merge
     */
    @Test
    @SuppressWarnings("unchecked")
    public void octree_capacityPropagatedToBalancingStrategy() {
        int treeCapacity = 50;
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator(), treeCapacity, (byte) 10);

        var strategy = octree.getBalancingStrategy();
        assertInstanceOf(DefaultBalancingStrategy.class, strategy,
                         "Default strategy must be a DefaultBalancingStrategy");

        var dbs = (DefaultBalancingStrategy<LongEntityID>) strategy;
        assertEquals(treeCapacity, dbs.getConfiguredMaxEntitiesPerNode(),
                     "DefaultBalancingStrategy must be wired with the tree's actual capacity, not the hardcoded 100");

        // Behavioral check: total=60 must NOT merge under capacity=50 (threshold≈45)
        // but would wrongly merge under the old hardcoded capacity=100 (threshold≈90)
        assertFalse(dbs.shouldMerge(20, (byte) 1, new int[] { 20, 20 }),
                    "shouldMerge must use tree capacity=50 (threshold≈45): total=60 must NOT merge");
    }
}
