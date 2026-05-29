/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-010 pi1.2: validates the pyramid child-type / child-cube-id connectivity tables against the
 * t8code ground truth ({@code t8_dpyramid_parenttype_Iloc_to_type} / {@code _to_cid} rows 6,7) and
 * the Knapp 2026 §3 structural invariant (10 children = 6 pyramids + 4 tetrahedra).
 *
 * @author hal.hildebrand
 */
class PyramidConnectivityTest {

    @Test
    void childTypeTableMatchesT8code() {
        assertArrayEquals(new byte[] { 6, 3, 6, 0, 6, 0, 3, 6, 7, 6 },
                          TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[0], "parent type 6 child types");
        assertArrayEquals(new byte[] { 7, 0, 3, 6, 7, 3, 7, 0, 7, 7 },
                          TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[1], "parent type 7 child types");
    }

    @Test
    void childCubeIdTableMatchesT8code() {
        assertArrayEquals(new byte[] { 0, 1, 1, 2, 2, 3, 3, 3, 3, 7 },
                          TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[0], "parent type 6 child cube-ids");
        assertArrayEquals(new byte[] { 0, 4, 4, 4, 4, 5, 5, 6, 6, 7 },
                          TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[1], "parent type 7 child cube-ids");
    }

    @Test
    void eachPyramidHasTenChildrenSixPyramidsFourTets() {
        assertEquals(TetreeConnectivity.CHILDREN_PER_PYRAMID, 10);
        for (byte[] row : TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE) {
            assertEquals(10, row.length);
            int pyramids = 0;
            int tets = 0;
            for (byte t : row) {
                if (t == 6 || t == 7) {
                    pyramids++;
                } else {
                    assertTrue(t == 0 || t == 3, "pyramid tet children are types 0 or 3 only, got: " + t);
                    tets++;
                }
            }
            assertEquals(6, pyramids, "6 pyramidal children");
            assertEquals(4, tets, "4 tetrahedral children");
        }
    }

    @Test
    void cubeIdsAreInRange() {
        for (byte[] row : TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID) {
            assertEquals(10, row.length);
            for (byte cid : row) {
                assertTrue(cid >= 0 && cid <= 7, "cube-id in [0,7], got: " + cid);
            }
        }
    }
}
