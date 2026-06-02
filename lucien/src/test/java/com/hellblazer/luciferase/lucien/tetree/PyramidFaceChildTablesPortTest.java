/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity-oracle test for the pyramid hierarchical-face traversal tables ported in Luciferase-acfa
 * (RDR-010 remediation P5). Pins the ported {@link TetreeConnectivity} arrays verbatim against the
 * t8code {@code t8_dpyramid_connectivity.c} literals (main@76a5347b) and checks the structural and
 * range invariants the consumers will rely on.
 *
 * <p>This is a transcription-parity oracle (the proven validation mode for direct t8code table ports
 * where there is no live integration consumer yet — same pattern as
 * {@code T8codeDpyramidTetBoundaryOracleTest} for the corner-walk tables). The t8code literals are
 * restated independently below; any future edit to the production tables must keep them byte-identical.
 *
 * @author hal.hildebrand
 */
class PyramidFaceChildTablesPortTest {

    // ---- t8code literals (t8_dpyramid_connectivity.c, main@76a5347b), restated independently ----

    private static final byte[][][] T8_TYPE_FACE_TO_CHILDREN_AT_FACE = {
    { { 0, 3, 4, 9 }, { 2, 5, 7, 9 }, { 0, 1, 2, 9 }, { 4, 6, 7, 9 }, { 0, 2, 4, 7 } },
    { { 0, 7, 8, 9 }, { 0, 1, 4, 6 }, { 0, 5, 6, 9 }, { 0, 2, 4, 8 }, { 4, 6, 8, 9 } } };

    private static final byte[][][] T8_TYPE_FACE_TO_CHILD_FACE = {
    { { 0, 1, 0, 0 }, { 1, 0, 1, 1 }, { 2, 1, 2, 2 }, { 3, 0, 3, 3 }, { 4, 4, 4, 4 } },
    { { 0, 2, 0, 0 }, { 1, 3, 1, 1 }, { 2, 2, 2, 2 }, { 3, 3, 3, 3 }, { 4, 4, 4, 4 } } };

    private static final byte[][] T8_TRITYPE_ROOTFACE_TO_PYRATYPE = { { 6, 6, 6, 6 }, { 0, 0, 3, 3 } };
    private static final byte[][] T8_TRITYPE_ROOTFACE_TO_TETTYPE = { { 2, 1, 1, 2 }, { 0, 0, 3, 3 } };
    private static final byte[][] T8_TRITYPE_ROOTFACE_TO_FACE = { { 2, 0, 2, 0 }, { 1, 0, 1, 0 } };

    @Test
    void typeFaceToChildrenAtFace_matchesT8codeVerbatim() {
        assertDeepEquals3(T8_TYPE_FACE_TO_CHILDREN_AT_FACE, TetreeConnectivity.PYRAMID_TYPE_FACE_TO_CHILDREN_AT_FACE);
    }

    @Test
    void typeFaceToChildFace_matchesT8codeVerbatim() {
        assertDeepEquals3(T8_TYPE_FACE_TO_CHILD_FACE, TetreeConnectivity.PYRAMID_TYPE_FACE_TO_CHILD_FACE);
    }

    @Test
    void tritypeRootfaceTables_matchT8codeVerbatim() {
        assertArrayEquals(T8_TRITYPE_ROOTFACE_TO_PYRATYPE, TetreeConnectivity.PYRAMID_TRITYPE_ROOTFACE_TO_PYRATYPE);
        assertArrayEquals(T8_TRITYPE_ROOTFACE_TO_TETTYPE, TetreeConnectivity.PYRAMID_TRITYPE_ROOTFACE_TO_TETTYPE);
        assertArrayEquals(T8_TRITYPE_ROOTFACE_TO_FACE, TetreeConnectivity.PYRAMID_TRITYPE_ROOTFACE_TO_FACE);
    }

    @Test
    void dimensionsAreCorrect() {
        assertEquals(2, TetreeConnectivity.PYRAMID_TYPE_FACE_TO_CHILDREN_AT_FACE.length);
        assertEquals(2, TetreeConnectivity.PYRAMID_TYPE_FACE_TO_CHILD_FACE.length);
        for (int t = 0; t < 2; t++) {
            assertEquals(5, TetreeConnectivity.PYRAMID_TYPE_FACE_TO_CHILDREN_AT_FACE[t].length, "5 faces");
            assertEquals(5, TetreeConnectivity.PYRAMID_TYPE_FACE_TO_CHILD_FACE[t].length, "5 faces");
            for (int f = 0; f < 5; f++) {
                assertEquals(4, TetreeConnectivity.PYRAMID_TYPE_FACE_TO_CHILDREN_AT_FACE[t][f].length, "4 children/face");
                assertEquals(4, TetreeConnectivity.PYRAMID_TYPE_FACE_TO_CHILD_FACE[t][f].length, "4 children/face");
            }
        }
        for (var tbl : new byte[][][] { TetreeConnectivity.PYRAMID_TRITYPE_ROOTFACE_TO_PYRATYPE,
                                        TetreeConnectivity.PYRAMID_TRITYPE_ROOTFACE_TO_TETTYPE,
                                        TetreeConnectivity.PYRAMID_TRITYPE_ROOTFACE_TO_FACE }) {
            assertEquals(2, tbl.length, "2 triangle types");
            for (var row : tbl) {
                assertEquals(4, row.length, "4 root faces");
            }
        }
    }

    @Test
    void childrenAtFace_areValidDistinctChildIlocs() {
        for (int t = 0; t < 2; t++) {
            for (int f = 0; f < 5; f++) {
                var kids = TetreeConnectivity.PYRAMID_TYPE_FACE_TO_CHILDREN_AT_FACE[t][f];
                Set<Byte> seen = new HashSet<>();
                for (var k : kids) {
                    assertTrue(k >= 0 && k < TetreeConnectivity.CHILDREN_PER_PYRAMID,
                               "child Iloc " + k + " out of [0," + TetreeConnectivity.CHILDREN_PER_PYRAMID + ")");
                    assertTrue(seen.add(k), "type " + (t + 6) + " face " + f + " has a duplicate child " + k);
                }
            }
        }
    }

    @Test
    void childFaceValues_areValidFaceIndices() {
        for (int t = 0; t < 2; t++) {
            for (int f = 0; f < 5; f++) {
                for (var cf : TetreeConnectivity.PYRAMID_TYPE_FACE_TO_CHILD_FACE[t][f]) {
                    assertTrue(cf >= 0 && cf <= 4, "child face " + cf + " out of [0,4]");
                }
            }
        }
    }

    @Test
    void tritypeRootfaceValues_areInValidRanges() {
        for (var row : TetreeConnectivity.PYRAMID_TRITYPE_ROOTFACE_TO_PYRATYPE) {
            for (var v : row) {
                assertTrue(v >= 0 && v <= 7, "boundary type " + v + " out of unified [0,7]");
            }
        }
        for (var row : TetreeConnectivity.PYRAMID_TRITYPE_ROOTFACE_TO_TETTYPE) {
            for (var v : row) {
                assertTrue(v >= 0 && v <= 5, "tet type " + v + " out of [0,5]");
            }
        }
        for (var row : TetreeConnectivity.PYRAMID_TRITYPE_ROOTFACE_TO_FACE) {
            for (var v : row) {
                assertTrue(v >= 0 && v <= 4, "face " + v + " out of [0,4]");
            }
        }
    }

    private static void assertDeepEquals3(byte[][][] expected, byte[][][] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i].length, actual[i].length, "dim1 at " + i);
            for (int j = 0; j < expected[i].length; j++) {
                assertArrayEquals(expected[i][j], actual[i][j],
                                  "mismatch at [" + i + "][" + j + "]: expected " + Arrays.toString(expected[i][j])
                                  + " got " + Arrays.toString(actual[i][j]));
            }
        }
    }
}
