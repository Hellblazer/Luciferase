/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

/**
 * Common identity / navigation surface for elements of the hybrid pyramid&ndash;tetrahedron
 * space-filling curve (RDR-010, Knapp 2026). Implemented by {@code Tet} (types 0&ndash;5) and
 * {@code Pyramid} (types 6&ndash;7), it lets the cross-shape navigation methods
 * ({@code Tet.parentElement()} / {@code faceNeighborElement()}, {@code Pyramid.parent()} /
 * {@code child()} / {@code faceNeighbor()}) return either shape through a single type without the
 * caller needing an {@code instanceof} ladder for the common accessors.
 *
 * <p>This interface is intentionally <b>non-sealed</b>. A sealed interface {@code permits Tet, Pyramid}
 * cannot compile in the unnamed module ({@code lucien} has no {@code module-info}), where all permitted
 * subclasses must share the sealed type's package &mdash; {@code Tet} (tetree) and {@code Pyramid}
 * (pyramid) do not. The element model is closed in practice (only these two shapes exist), but the
 * closure is documented rather than compiler-enforced. The RDR-010 q3p parallel-method design relies
 * only on the common return type, not on exhaustive pattern matching, so non-sealed is sufficient.
 *
 * <p>The interface exposes identity and navigation only &mdash; deliberately <b>not</b>
 * {@code coordinates()}, whose arity differs by shape ({@code Tet} returns 4 vertices, {@code Pyramid}
 * 5). Geometry consumers branch on {@link #type()} (0&ndash;5 tetrahedron, 6&ndash;7 pyramid).
 *
 * @author hal.hildebrand
 */
public interface HybridElement {

    /** Anchor x coordinate (lower-left-back corner of the surrounding cube). */
    int x();

    /** Anchor y coordinate. */
    int y();

    /** Anchor z coordinate. */
    int z();

    /** Refinement level (0 = root). */
    byte level();

    /** Element type: 0&ndash;5 tetrahedron (S0&ndash;S5), 6&ndash;7 pyramid. */
    byte type();

    /**
     * Smallest level at which an ancestor is a tetrahedron, or the no-tetrahedral-ancestor sentinel
     * ({@code -1}) for pure-Tetree elements and pyramids (Knapp 2026 Algorithm 4.1).
     */
    byte minTetLevel();

    /** Edge length of the surrounding cube at this element's level ({@code 2^(maxLevel - level)}). */
    int length();
}
