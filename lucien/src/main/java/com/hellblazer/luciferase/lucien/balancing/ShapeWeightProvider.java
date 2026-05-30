/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.balancing;

/**
 * Per-shape element-count weight {@code N_shape(level)} for hybrid-forest partitioning
 * (RDR-010 pi1.6, Knapp 2026 §5 / Eq 5.1).
 *
 * <p>A uniformly refined root of a given element shape produces a shape-specific number of
 * descendants after {@code level} refinement steps:
 * <ul>
 *   <li>hexahedron (Octree) / tetrahedron (Tetree): {@code N(ℓ) = 8^ℓ} (1:8 refinement)</li>
 *   <li>pyramid (PyramidIndex): {@code N(ℓ) = 2·8^ℓ − 6^ℓ} — a pyramid refines into 6 pyramids
 *       + 4 tets, and the {@code −6^ℓ} term corrects the non-uniform pyramid/tet mixing</li>
 * </ul>
 *
 * <p><b>Why this matters.</b> A weighted space-filling-curve partition that assumes every tree
 * refines 1:8 mis-balances a forest containing pyramid trees (a pyramid root holds
 * {@code 2·8^ℓ − 6^ℓ} elements, not {@code 8^ℓ}). Consulting {@code elementCount(level)} per shape
 * lets {@link ShapeWeightPartitioner} assign cumulative-weight-equal SFC ranges (Knapp Algorithm 5.1)
 * across heterogeneous forests.
 *
 * @author Hal Hildebrand
 */
public interface ShapeWeightProvider {

    /**
     * The number of elements a single root of this shape contains after {@code level} uniform
     * refinement steps — the partition weight {@code N_shape(level)}.
     *
     * @param level the uniform refinement level (≥ 0)
     * @return the element count {@code N_shape(level)}
     * @throws IllegalArgumentException if {@code level < 0}, or if the count would overflow a signed
     *                                  {@code long} (i.e. {@code 8^level} exceeds {@link Long#MAX_VALUE};
     *                                  {@code 8^21 = 2^63} overflows, so {@code level} must be ≤ 20)
     */
    long elementCount(int level);

    /** Largest level whose {@code 8^level} (and {@code 2·8^level − 6^level}) fits a signed long. */
    int MAX_WEIGHT_LEVEL = 20;

    /**
     * Validate that {@code level} is in the representable range {@code [0, }{@link #MAX_WEIGHT_LEVEL}{@code ]}.
     *
     * @throws IllegalArgumentException if {@code level < 0} or {@code level > }{@link #MAX_WEIGHT_LEVEL}
     */
    static void requireValidLevel(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("level must be >= 0, got " + level);
        }
        if (level > MAX_WEIGHT_LEVEL) {
            throw new IllegalArgumentException(
                "level " + level + " overflows a signed long (8^" + level + " > Long.MAX_VALUE); max is "
                + MAX_WEIGHT_LEVEL);
        }
    }

    /** {@code 8^level = 2^(3·level)}, computed exactly by bit shift after range validation. */
    static long eightToThe(int level) {
        requireValidLevel(level);
        return 1L << (3 * level);
    }

    /** {@code 6^level}, computed by iterated multiplication after range validation (no overflow for level ≤ 20). */
    static long sixToThe(int level) {
        requireValidLevel(level);
        long r = 1L;
        for (int i = 0; i < level; i++) {
            r *= 6L;
        }
        return r;
    }
}
