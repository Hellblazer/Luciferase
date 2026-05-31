/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.pyramid.PyramidIndex;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 §4c (bead Luciferase-uzyd): the distributed layer consuming the shape-weighted partition.
 *
 * <p>This is the live consumer of {@link ShapeWeightPartitioner#assignTreesToRanks} (Knapp Alg 5.1
 * weighted SFC partition) at the distributed-bootstrap seam: given a heterogeneous set of trees
 * (hex / tet / pyramid) and the partition count, it computes which partition <em>rank</em> owns which
 * trees, weighted by each shape's element load ({@code N_pyramid = 2·8^ℓ − 6^ℓ} &gt; {@code N_hex = 8^ℓ}).
 *
 * <p><b>Honesty note:</b> there is no production distributed launcher in the repo that calls this at
 * startup — partition rank is currently supplied externally to {@link DistributedForestImpl}. This test
 * is the proving consumer for the §4c rank-assignment path; wiring it into a production bootstrap is the
 * remaining (registered) work, gated on a distributed-launch arc that does not yet exist.
 */
class ShapeWeightedPartitionBootstrapTest {

    private static final int LEVEL = 2; // N_hex=N_tet=64, N_pyramid=92

    /** A heterogeneous forest's trees, in SFC order, with their backing shape (hex/tet/pyramid). */
    private record TreeShape(String id, ShapeWeightProvider index) {
    }

    private List<TreeShape> heterogeneousTrees() {
        var gen = new SequentialLongIDGenerator();
        // Four pyramids (N=92) then four hex (N=64) in SFC order — a heavy-front layout where the
        // weighted cumulative-offset partition genuinely diverges from a tree-count split.
        return List.of(
            new TreeShape("t0-pyr", new PyramidIndex<LongEntityID, String>(gen)),
            new TreeShape("t1-pyr", new PyramidIndex<LongEntityID, String>(gen)),
            new TreeShape("t2-pyr", new PyramidIndex<LongEntityID, String>(gen)),
            new TreeShape("t3-pyr", new PyramidIndex<LongEntityID, String>(gen)),
            new TreeShape("t4-hex", new Octree<LongEntityID, String>(gen)),
            new TreeShape("t5-hex", new Octree<LongEntityID, String>(gen)),
            new TreeShape("t6-tet", new Tetree<LongEntityID, String>(gen)),
            new TreeShape("t7-hex", new Octree<LongEntityID, String>(gen)));
    }

    @Test
    void weightedRankAssignmentPartitionsAllTreesAcrossRanks() {
        var trees = heterogeneousTrees();
        var ids = trees.stream().map(TreeShape::id).toList();
        long[] weights = ShapeWeightPartitioner.weightsAtLevel(trees.stream().map(TreeShape::index).toList(), LEVEL);
        assertArrayEquals(new long[] { 92, 92, 92, 92, 64, 64, 64, 64 }, weights, "shape weights at level 2");

        int partitions = 3;
        Map<Integer, List<String>> byRank = ShapeWeightPartitioner.assignTreesToRanks(ids, weights, partitions);

        // Every rank present; every tree assigned exactly once (no loss, no duplication).
        assertEquals(partitions, byRank.size());
        var assigned = byRank.values().stream().flatMap(List::stream).sorted().toList();
        assertEquals(ids.stream().sorted().toList(), assigned, "every tree owned by exactly one rank");
    }

    @Test
    void weightedAssignmentBalancesElementLoadBetterThanTreeCountSplit() {
        var trees = heterogeneousTrees();
        var ids = trees.stream().map(TreeShape::id).toList();
        long[] weights = ShapeWeightPartitioner.weightsAtLevel(trees.stream().map(TreeShape::index).toList(), LEVEL);
        var weightOf = new HashMap<String, Long>();
        for (int i = 0; i < ids.size(); i++) {
            weightOf.put(ids.get(i), weights[i]);
        }
        int partitions = 3;

        // §4c weighted plan.
        var weighted = ShapeWeightPartitioner.assignTreesToRanks(ids, weights, partitions);

        // Naive shape-blind plan: contiguous even split by tree COUNT (every tree weight 1).
        long[] ones = new long[ids.size()];
        java.util.Arrays.fill(ones, 1L);
        var naive = ShapeWeightPartitioner.assignTreesToRanks(ids, ones, partitions);

        long weightedSpread = loadSpread(weighted, weightOf);
        long naiveSpread = loadSpread(naive, weightOf);
        // Exact deterministic values for [92,92,92,92,64,64,64,64] over P=3 (guards against a future
        // input that satisfies a loose <= without actually exercising weight-sensitivity):
        //   weighted ranks {0,0,0,1,1,2,2,2} → loads [276,156,192] → spread 120
        //   naive count-split {0,0,0,1,1,1,2,2} → loads [276,220,128] → spread 148
        assertEquals(120L, weightedSpread, "weighted element-load spread");
        assertEquals(148L, naiveSpread, "naive count-split element-load spread");
        assertTrue(weightedSpread < naiveSpread, "shape-weighted assignment balances element load strictly better");
        // And it must genuinely consult the weight (the two plans differ on this pyramid-laden forest).
        assertNotEquals(naive, weighted, "weighted plan must differ from the shape-blind count split");
    }

    @Test
    void mismatchedWeightsRejected() {
        assertThrows(IllegalArgumentException.class,
                     () -> ShapeWeightPartitioner.assignTreesToRanks(List.of("a", "b"), new long[] { 1 }, 2));
    }

    /** Max minus min total element-weight across ranks for a given assignment. */
    private static long loadSpread(Map<Integer, List<String>> byRank, Map<String, Long> weightOf) {
        long max = Long.MIN_VALUE;
        long min = Long.MAX_VALUE;
        for (var trees : byRank.values()) {
            long w = trees.stream().mapToLong(weightOf::get).sum();
            max = Math.max(max, w);
            min = Math.min(min, w);
        }
        return max - min;
    }
}
