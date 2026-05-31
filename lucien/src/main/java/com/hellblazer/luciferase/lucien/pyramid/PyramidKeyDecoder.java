/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;

/**
 * Shared pyramid-SFC key → {@link Pyramid} decoder (RDR-010, bead Luciferase-3y1).
 *
 * <p>The root-to-key descent was previously duplicated verbatim (~50 lines) in
 * {@link PyramidIndex#pyramidFromKey(PyramidKey)} (private) and
 * {@code PyramidSubdivisionStrategy.pyramidFromKey} (the strategy has no back-reference to the index).
 * Both now delegate here so the descent logic cannot silently diverge if the SFC encoding changes.
 *
 * @author Hal Hildebrand
 */
final class PyramidKeyDecoder {

    private PyramidKeyDecoder() {
    }

    /**
     * Reconstruct the {@link Pyramid} element for {@code key} by descending the pyramid tree from the
     * root, following the coordinate/type bits encoded at each level.
     *
     * <p><b>Tet-child keys:</b> for a <em>level-1</em> tet-child key this returns {@code null} (no pyramid
     * at that key). For a <em>deeper</em> ({@code level > 1}) tet-child key it returns the nearest
     * enclosing parent pyramid (a strictly larger bounding volume) rather than {@code null} — a
     * conservative over-approximation (the pyramid bound encloses the tet; never a false negative). When
     * the <em>actual</em> leaf element is needed for a tet leaf, use
     * {@link PyramidIndex#elementFromKey(PyramidKey)} instead.
     */
    static Pyramid pyramidFromKey(PyramidKey key) {
        byte level = key.getLevel();
        if (level == 0) {
            // Virtual root — return the type-6 root cover pyramid
            return new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        }
        // Step 1: find the type-6 or type-7 root child at level 1
        var type6Root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        var type7Root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7);

        int coordBits1 = key.getCoordBitsAtLevel(1);
        int typeBits1 = key.getTypeAtLevel(1);

        Pyramid current = null;
        outer:
        for (var root : new Pyramid[] { type6Root, type7Root }) {
            int row = root.type() - Pyramid.TYPE_6;
            for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                if (TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i] == coordBits1
                    && TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i] == typeBits1) {
                    var child = root.child(i);
                    if (child instanceof Pyramid pc) {
                        current = pc;
                    }
                    // If it's a tet child at level 1 and level==1, fall through → return null below
                    break outer;
                }
            }
        }

        if (current == null || level == 1) {
            // level-1 element is a tet (or not found) — not a pyramid
            return level == 1 && current != null ? current : null;
        }

        // Descend levels 2..level
        for (int l = 2; l <= level; l++) {
            int cb = key.getCoordBitsAtLevel(l);
            int tb = key.getTypeAtLevel(l);
            Pyramid next = null;
            int row = current.type() - Pyramid.TYPE_6;
            for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                if (TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i] == cb
                    && TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i] == tb) {
                    var child = current.child(i);
                    if (child instanceof Pyramid pc) {
                        next = pc;
                    }
                    break;
                }
            }
            if (next == null) {
                return current; // key ends in a tet at level l; return parent pyramid
            }
            current = next;
        }
        return current;
    }
}
