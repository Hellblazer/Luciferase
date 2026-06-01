/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-t6su: {@code Tet.faceNeighbor} at level 0.
 *
 * <p><b>The bead's premise for this method was WRONG and is recorded here so it is not re-attempted.</b> The
 * bead claimed the root cube is tiled by six types, so faces 1/2 of a root tet have valid same-cube neighbors
 * of a different type, and the {@code l==0 && typeNew != 0 -> null} guard should be removed. But a single-tree
 * {@link Tetree} models level 0 as a single type-0 root tet — the {@link Tet} constructor enforces
 * "level 0 => type 0" ({@code validateAnchorCoordinates}). A type-changing face at the root would name a
 * level-0 tet of non-zero type, which cannot be constructed; removing the guard makes {@code faceNeighbor}
 * throw instead of returning null. So the guard is correct and is retained. Only the {@link TetreeFamily}
 * {@code l==0} guard part of t6su was a real fix.
 *
 * @author hal.hildebrand
 */
class TetFaceNeighborRootTest {

    @Test
    void typeChangingRootFacesHaveNoSameTreeNeighbor() {
        var root = new Tet(0, 0, 0, (byte) 0, (byte) 0); // the single type-0 root of a single-tree Tetree
        // Faces 1 and 2 keep the coordinates but change type -> a level-0 non-type-0 tet, which cannot exist.
        assertNull(root.faceNeighbor(1), "root face 1 would need a level-0 non-type-0 tet (forbidden) -> null");
        assertNull(root.faceNeighbor(2), "root face 2 would need a level-0 non-type-0 tet (forbidden) -> null");
    }

    @Test
    void coordinateShiftingRootFacesLeaveTheDomainAndAreNull() {
        var root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        assertNull(root.faceNeighbor(0), "face 0 shifts +h beyond MAX_COORD at the root -> no neighbor");
        assertNull(root.faceNeighbor(3), "face 3 shifts -h below 0 at the root -> no neighbor");
    }

    @Test
    void faceNeighborNeverThrowsAtRootForAnyFace() {
        var root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        for (int face = 0; face < 4; face++) {
            int f = face;
            assertDoesNotThrow(() -> root.faceNeighbor(f), "root faceNeighbor must not throw for face " + f);
        }
    }
}
