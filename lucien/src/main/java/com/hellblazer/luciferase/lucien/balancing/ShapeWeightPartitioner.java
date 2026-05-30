/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weighted space-filling-curve cumulative-offset partitioner (RDR-010 pi1.6; Knapp 2026 Algorithm 5.1,
 * the hybrid-forest specialization of the Burstedde+Holke weighted SFC partition).
 *
 * <p>Given an SFC-ordered sequence of indivisible units (forest trees) each carrying a weight
 * {@code w[i]}, the partition assigns each unit to the rank whose {@code [r·W/P, (r+1)·W/P)} weight band
 * its cumulative start offset falls into ({@code W = Σ w}, {@code P} = partition count). The result is a
 * contiguous (non-decreasing) rank assignment in which every rank receives ≈ {@code W/P} total weight —
 * the best a contiguous partition of indivisible units can do.
 *
 * <p><b>Shape-correctness.</b> Weights come from {@link ShapeWeightProvider#elementCount(int)}, so a
 * pyramid tree ({@code N = 2·8^ℓ − 6^ℓ}) is weighted by its true element load rather than the 1:8
 * ({@code 8^ℓ}) count a shape-blind partitioner would assume — see
 * {@link #weightsAtLevel(List, int)} and {@link #partitionForest(Forest, int, int)}.
 *
 * <p><b>Forest key-type note.</b> A {@link Forest} is parameterized by a single {@link SpatialKey} type,
 * so a single forest instance is homogeneous in key (all Octree, all Tetree, or all PyramidIndex). A
 * genuinely heterogeneous hex+pyramid partition is computed by feeding mixed per-shape weights to
 * {@link #assign(long[], int)} / {@link #weightsAtLevel(List, int)} directly.
 *
 * @author Hal Hildebrand
 */
public final class ShapeWeightPartitioner {

    private ShapeWeightPartitioner() {
    }

    /**
     * Assign each weighted unit (in SFC order) to one of {@code partitionCount} ranks by cumulative
     * weight, so every rank receives ≈ {@code W/P} total weight.
     *
     * @param weights        per-unit weights in SFC order (non-negative)
     * @param partitionCount number of ranks {@code P} (≥ 1)
     * @return rank per unit (non-decreasing, each in {@code [0, P-1]}); empty if {@code weights} is empty
     * @throws IllegalArgumentException if {@code partitionCount < 1} or any weight is negative
     */
    public static int[] assign(long[] weights, int partitionCount) {
        if (partitionCount < 1) {
            throw new IllegalArgumentException("partitionCount must be >= 1, got " + partitionCount);
        }
        int n = weights.length;
        int[] result = new int[n];
        if (n == 0) {
            return result;
        }

        long total = 0;
        for (long w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("weights must be non-negative, got " + w);
            }
            if (total + w < total) {
                throw new ArithmeticException(
                    "weight total overflows a signed long; reduce the level or the number of units");
            }
            total += w;
        }

        // Degenerate: no weight to balance — fall back to a contiguous even split by index.
        if (total == 0) {
            for (int i = 0; i < n; i++) {
                int rank = (int) ((long) i * partitionCount / n);
                result[i] = Math.min(rank, partitionCount - 1);
            }
            return result;
        }

        long prefix = 0;
        for (int i = 0; i < n; i++) {
            // Overflow-safe: prefix·P can exceed Long.MAX_VALUE at high levels (8^20·P). double's
            // 53-bit mantissa keeps the rank error far below one partition for any practical P.
            int rank = (int) ((double) prefix / total * partitionCount);
            if (rank >= partitionCount) {
                rank = partitionCount - 1;
            }
            result[i] = rank;
            prefix += weights[i];
        }
        return result;
    }

    /**
     * Map a list of shapes to their {@code N_shape(level)} weights.
     *
     * @param shapes the per-unit shape weight providers (typically forest tree spatial indices)
     * @param level  the uniform refinement level
     * @return the weight array, aligned with {@code shapes}
     */
    public static long[] weightsAtLevel(List<? extends ShapeWeightProvider> shapes, int level) {
        long[] w = new long[shapes.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = shapes.get(i).elementCount(level);
        }
        return w;
    }

    /**
     * Partition a homogeneous forest's trees across {@code partitionCount} ranks by shape-aware weight.
     *
     * <p>Each tree's weight is {@code tree.getSpatialIndex().elementCount(uniformLevel)} — shape-aware via
     * {@link ShapeWeightProvider}, so even a single-shape forest is balanced by element load rather than
     * tree count.
     *
     * @apiNote A {@link Forest} is parameterized by a single {@link SpatialKey} type, so this method can
     *          only partition a <em>homogeneous</em> (single-shape) forest — where all trees share the
     *          same {@code N_shape} and the shape-aware weighting is effectively uniform. For a genuinely
     *          heterogeneous partition (e.g. hex + pyramid), build the weight array from mixed
     *          {@link ShapeWeightProvider}s and call {@link #weightsAtLevel(List, int)} +
     *          {@link #assign(long[], int)} directly; {@code partitionForest} cannot express that case.
     * @return an insertion-ordered map of {@code treeId → rank}
     */
    public static <Key extends SpatialKey<Key>, ID extends EntityID, Content> Map<String, Integer>
    partitionForest(Forest<Key, ID, Content> forest, int uniformLevel, int partitionCount) {
        var trees = forest.getAllTrees();
        long[] weights = new long[trees.size()];
        for (int i = 0; i < trees.size(); i++) {
            weights[i] = trees.get(i).getSpatialIndex().elementCount(uniformLevel);
        }
        int[] ranks = assign(weights, partitionCount);
        var assignment = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < trees.size(); i++) {
            assignment.put(trees.get(i).getTreeId(), ranks[i]);
        }
        return assignment;
    }

    /**
     * Bootstrap-facing inverse of {@link #assign(long[], int)} for a distributed partition: given the
     * SFC-ordered trees, their per-shape weights, and the partition count, return which trees each rank
     * owns (RDR-010 §4c, bead Luciferase-uzyd). This is the shape-weighted tree→rank assignment a
     * distributed partition bootstrap consumes — heterogeneous (hex/tet/pyramid) weights are supported
     * because the caller supplies the weight array directly (unlike {@link #partitionForest}, which a
     * single-key {@link Forest} constrains to one shape).
     *
     * @param treeIds        SFC-ordered tree identifiers
     * @param weights        per-tree shape weights ({@code N_shape(level)}), aligned with {@code treeIds}
     * @param partitionCount number of ranks
     * @return rank → list of tree ids owned by that rank (every rank {@code 0..P-1} present, possibly empty)
     * @throws IllegalArgumentException if {@code treeIds.size() != weights.length}
     */
    public static Map<Integer, List<String>> assignTreesToRanks(List<String> treeIds, long[] weights,
                                                                int partitionCount) {
        if (treeIds.size() != weights.length) {
            throw new IllegalArgumentException(
                "treeIds (" + treeIds.size() + ") and weights (" + weights.length + ") must align");
        }
        int[] ranks = assign(weights, partitionCount);
        var byRank = new LinkedHashMap<Integer, List<String>>();
        for (int r = 0; r < partitionCount; r++) {
            byRank.put(r, new ArrayList<>());
        }
        for (int i = 0; i < treeIds.size(); i++) {
            byRank.get(ranks[i]).add(treeIds.get(i));
        }
        return byRank;
    }
}
