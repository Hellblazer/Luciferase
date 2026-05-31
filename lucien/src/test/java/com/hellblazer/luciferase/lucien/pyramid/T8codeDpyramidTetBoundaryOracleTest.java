/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.HybridFaceNeighbor;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * t8code parity oracle for the <b>tet-source</b> cross-shape face branch (RDR-012 D3.1, bead
 * {@code Luciferase-dk12}). The companion {@link T8codeDpyramidFaceOracleTest} (D0.2) covers the
 * <em>pyramid-source</em> branch of {@code t8_dpyramid_face_neighbour}; this one covers the branch where
 * the source element is a tetrahedron — specifically the deep {@code t8_dpyramid_tet_boundary} tet-return
 * branch ({@code l > minTetLevel}) that {@link T8codeDpyramidFaceOracleTest} explicitly excludes. It is
 * the independent face-by-face parity oracle the gap analysis flagged missing (declined {@code kyz9}).
 *
 * <p><b>Independent transcription (main@76a5347b, {@code t8_dpyramid_bits.c}).</b>
 * {@link #oracleTetBoundary} re-implements {@code t8_dpyramid_tet_boundary:822} and
 * {@code t8_dpyramid_tet_pyra_face_connection:800}; the cross-shape neighbor geometry is transcribed
 * directly from the type-0/type-3 switch in {@code t8_dpyramid_face_neighbour:638-690}. The
 * {@code tet_pyra_face_connection} arithmetic is fully table-free (the part most exposed to a
 * transcription slip). The deep ancestor/corner walk consumes the {@code t8_dtet_*} table values via
 * {@link TetreeConnectivity} ({@code TYPE_CID_TO_BEYID}, {@code FACE_CHILDID_TO_IS_INSIDE},
 * {@code CID_TYPE_TO_PARENTTYPE}) — independently re-walked here, NOT by calling
 * {@code Tet.tetBoundary}; those table values are themselves oracle-verified by {@code T8codeDtetOracleTest}.
 * This mirrors the independence stance of the pyramid-source oracle.
 *
 * <p><b>What this gate asserts</b>, over a DFS of both root pyramids to {@link #MAX_SWEEP} restricted to
 * <b>deep</b> pyramid-rooted tets ({@code 0 <= minTetLevel < level}) of type 0/3 (the only tets that can
 * touch a pyramid):
 * <ol>
 *   <li><b>Cross-shape decision parity ({@code decisionMismatch==0}).</b> {@code Tet.faceNeighborElement(f)}
 *       returns a {@link Pyramid} iff the oracle's {@code tet_boundary} says the face touches a pyramid.
 *       This is the genuine correctness property for the deep dark machinery.</li>
 *   <li><b>Pyramid-return geometry parity ({@code geomMismatch==0}).</b> When both say pyramid, the
 *       returned pyramid's {@code (x,y,z,type)} and the reciprocal face index match the independent
 *       t8code switch.</li>
 *   <li><b>Involution.</b> When {@code faceNeighborElement} returns a pyramid, that pyramid's reciprocal
 *       face neighbour (via the separate {@link Pyramid#faceNeighbor} code path) is geometrically the
 *       source deep tet — cross-implementation reciprocity the table parity alone cannot establish.</li>
 *   <li><b>Non-vacuity.</b> The sweep must actually exercise deep tet→pyramid faces (asserts a positive
 *       count), else the gate is hollow.</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
class T8codeDpyramidTetBoundaryOracleTest {

    private static final int MAX_SWEEP = 4;

    @Test
    void deepTetFaceNeighborsMatchT8codeTetBoundary() {
        int decisionMismatch = 0, geomMismatch = 0, involutionMismatch = 0;
        int pyramidTouches = 0, deepTetFacesChecked = 0;
        var failures = new ArrayList<String>();

        int nonTouchTypesChecked = 0;
        for (var t : deepPyramidRootedTets(MAX_SWEEP)) {
            if (t.type() != 0 && t.type() != 3) {
                // t8code guard: only type 0/3 tets ever touch a pyramid. Assert the negative explicitly
                // (do NOT silently skip) — faceNeighborElement must never return a Pyramid for these.
                for (int f = 0; f < 4; f++) {
                    var fn = t.faceNeighborElement(f);
                    if (fn != null && fn.element() instanceof Pyramid) {
                        decisionMismatch++;
                        if (failures.size() < 40) {
                            failures.add("NEG-DECISION " + desc(t) + " f" + f
                                         + " returned a Pyramid but type∉{0,3} cannot touch a pyramid: "
                                         + descEl(fn.element()));
                        }
                    }
                    nonTouchTypesChecked++;
                }
                continue;
            }
            for (int f = 0; f < 4; f++) {
                deepTetFacesChecked++;
                boolean oracleSaysPyramid = oracleTetBoundary(t, f);
                HybridFaceNeighbor luc = t.faceNeighborElement(f);
                boolean lucSaysPyramid = luc != null && luc.element() instanceof Pyramid;

                if (oracleSaysPyramid != lucSaysPyramid) {
                    decisionMismatch++;
                    if (failures.size() < 40) {
                        failures.add("DECISION " + desc(t) + " f" + f + " oracle=pyramid:" + oracleSaysPyramid
                                     + " luc=pyramid:" + lucSaysPyramid
                                     + (luc == null ? " (luc null)" : " luc=" + descEl(luc.element())));
                    }
                    continue;
                }
                if (!oracleSaysPyramid) {
                    continue; // tet-touches-tet: plain Tet neighbor, covered by pure-Tetree tests
                }
                pyramidTouches++;

                // (2) geometry parity against the independent t8code switch (incl. level: t8code sets
                //     neigh->level = p->level for the same-level cross-shape neighbor).
                int[] exp = oraclePyramidReturn(t, f); // {nx,ny,nz,ntype,nface,nlevel}
                var py = (Pyramid) luc.element();
                if (!(py.x() == exp[0] && py.y() == exp[1] && py.z() == exp[2] && py.type() == exp[3]
                      && luc.face() == exp[4] && py.level() == exp[5])) {
                    geomMismatch++;
                    if (failures.size() < 40) {
                        failures.add("GEOM " + desc(t) + " f" + f + " luc=" + descEl(py) + ",face" + luc.face()
                                     + ",l" + py.level() + " ; t8code=(" + exp[0] + "," + exp[1] + "," + exp[2]
                                     + ",t" + exp[3] + ",face" + exp[4] + ",l" + exp[5] + ")");
                    }
                    continue;
                }

                // (3) involution: the pyramid's reciprocal-face neighbour is geometrically this deep tet.
                var back = py.faceNeighbor(luc.face());
                boolean involutes = back != null && sameGeom(back.element(), t);
                if (!involutes) {
                    involutionMismatch++;
                    if (failures.size() < 40) {
                        failures.add("INVOL " + desc(t) + " f" + f + " -> pyr=" + descEl(py)
                                     + " back=" + (back == null ? "null" : descEl(back.element())));
                    }
                }
            }
        }

        var report = new StringBuilder("t8code tet-boundary parity (RDR-012 D3.1) over deep pyramid-rooted "
                                       + "tets (level<=" + MAX_SWEEP + "): deepTetFacesChecked="
                                       + deepTetFacesChecked + " pyramidTouches=" + pyramidTouches
                                       + " decisionMismatch=" + decisionMismatch + " geomMismatch=" + geomMismatch
                                       + " involutionMismatch=" + involutionMismatch + "\n");
        failures.forEach(s -> report.append("  ").append(s).append('\n'));
        String r = report.toString();

        assertTrue(nonTouchTypesChecked > 0, "expected some non-0/3 deep tets to negative-check\n" + r);
        assertEquals(0, decisionMismatch, "deep tet->pyramid cross-shape DECISION diverges from t8code\n" + r);
        assertEquals(0, geomMismatch, "deep tet->pyramid pyramid-return GEOMETRY diverges from t8code\n" + r);
        assertEquals(0, involutionMismatch, "deep tet->pyramid neighbour fails cross-impl involution\n" + r);
        assertTrue(pyramidTouches > 0,
                   "vacuous: the sweep exercised no deep tet->pyramid face — non-vacuity failed\n" + r);
    }

    // ---- independent t8code transcription (main@76a5347b, t8_dpyramid_bits.c) ----

    /** {@code t8_dpyramid_tet_pyra_face_connection} ({@code :800}) — table-free. cubeId at the tet's level. */
    private static boolean oracleTetPyraFaceConnection(byte type, int cubeId, int face) {
        if ((cubeId == 2 && face != 1) || (cubeId == 6 && face != 2)) {
            return type == 0;
        } else if ((cubeId == 1 && face != 1) || (cubeId == 5 && face != 2)) {
            return type == 3;
        } else if (cubeId == 3) {
            return face != 0;
        } else if (cubeId == 4) {
            return face != 3;
        }
        return false;
    }

    /** {@code t8_dpyramid_tet_boundary} ({@code :822}) — shallow direct test + deep ancestor/corner walk. */
    private static boolean oracleTetBoundary(Tet p, int face) {
        byte level = p.level();
        byte minTet = p.minTetLevel();
        if (level == minTet) {
            return oracleTetPyraFaceConnection(p.type(), cubeId(p, level), face);
        }
        // anc = ancestor at switch_shape_at_level (== minTetLevel): walk the type up.
        byte ancType = p.type();
        for (int i = level; i > minTet; i--) {
            ancType = TetreeConnectivity.CID_TYPE_TO_PARENTTYPE[cubeId(p, (byte) i)][ancType];
        }
        boolean validTouch = oracleTetPyraFaceConnection(ancType, cubeId(p, minTet), face);
        if (validTouch) {
            byte typeTemp = p.type();
            for (int i = level; i > minTet; i--) {
                int cid = cubeId(p, (byte) i);
                int beyId = TetreeConnectivity.TYPE_CID_TO_BEYID[typeTemp][cid];
                if (TetreeConnectivity.FACE_CHILDID_TO_IS_INSIDE[face][beyId] == -1) {
                    return false;
                }
                typeTemp = TetreeConnectivity.CID_TYPE_TO_PARENTTYPE[cid][typeTemp];
            }
        }
        return validTouch;
    }

    /**
     * The pyramid-return geometry from {@code t8_dpyramid_face_neighbour} ({@code :638-690}) for a type-0/3
     * tet whose {@code face} touches a pyramid. Returns {nx,ny,nz,ntype,nface}; coords start from the tet
     * anchor (same-level, level unchanged). Transcribed directly from the t8code switch.
     */
    private static int[] oraclePyramidReturn(Tet p, int face) {
        int len = Constants.lengthAtLevel(p.level());
        int nx = p.x(), ny = p.y(), nz = p.z();
        byte ntype;
        byte nface;
        if (p.type() == 0) {
            switch (face) {
                case 0 -> { nx += len; ntype = Pyramid.TYPE_7; nface = 3; }
                case 1 -> { ntype = Pyramid.TYPE_7; nface = 2; }
                case 2 -> { ntype = Pyramid.TYPE_6; nface = 2; }
                default -> { ny -= len; ntype = Pyramid.TYPE_6; nface = 3; } // face 3
            }
        } else { // type 3
            switch (face) {
                case 0 -> { ny += len; ntype = Pyramid.TYPE_7; nface = 1; }
                case 1 -> { ntype = Pyramid.TYPE_7; nface = 0; }
                case 2 -> { ntype = Pyramid.TYPE_6; nface = 0; }
                default -> { nx -= len; ntype = Pyramid.TYPE_6; nface = 1; } // face 3
            }
        }
        // t8code: neigh->pyramid.level = p->pyramid.level (same-level cross-shape neighbor).
        return new int[] { nx, ny, nz, ntype, nface, p.level() };
    }

    /**
     * Independent table-parity gate (RDR-012 D3.1, twaf review). The deep ancestor/corner walk in both
     * {@code Tet.tetBoundary} (SUT) and {@link #oracleTetBoundary} (this oracle) consumes the SAME
     * {@link TetreeConnectivity#TYPE_CID_TO_BEYID} and {@link TetreeConnectivity#FACE_CHILDID_TO_IS_INSIDE}
     * tables, so the parity sweep alone cannot catch a <em>coordinated</em> transcription error in those
     * two tables (the substantive-critic's shared-table point — and {@code T8codeDtetOracleTest} does not
     * exercise either table). This test closes that gap by transcribing the t8code literals directly
     * (main@76a5347b: {@code t8_dtet_type_cid_to_beyid}, {@code t8_dtet_connectivity.c:72};
     * {@code t8_dpyramid_face_childid_to_is_inside}, {@code t8_dpyramid_connectivity.c:105}) and asserting
     * the production tables match element-by-element.
     */
    @Test
    void cornerWalkTablesMatchT8codeVerbatim() {
        int[][] t8TypeCidToBeyid = {
            { 0, 1, 4, 7, 5, 2, 6, 3 },
            { 0, 1, 5, 2, 4, 7, 6, 3 },
            { 0, 5, 1, 2, 4, 6, 7, 3 },
            { 0, 4, 1, 7, 5, 6, 2, 3 },
            { 0, 4, 5, 6, 1, 7, 2, 3 },
            { 0, 5, 4, 6, 1, 2, 7, 3 } };
        assertEquals(t8TypeCidToBeyid.length, TetreeConnectivity.TYPE_CID_TO_BEYID.length,
                     "TYPE_CID_TO_BEYID row count");
        for (int type = 0; type < t8TypeCidToBeyid.length; type++) {
            for (int cid = 0; cid < 8; cid++) {
                assertEquals(t8TypeCidToBeyid[type][cid], TetreeConnectivity.TYPE_CID_TO_BEYID[type][cid],
                             "TYPE_CID_TO_BEYID[" + type + "][" + cid + "] diverges from t8code "
                             + "t8_dtet_type_cid_to_beyid");
            }
        }

        int[][] t8FaceChildidToIsInside = {
            { -1, 0, 0, 0, -1, -1, -1, -1 },
            { 0, -1, 0, 0, -1, -1, -1, -1 },
            { 0, 0, -1, 0, -1, -1, -1, -1 },
            { 0, 0, 0, -1, -1, -1, -1, -1 } };
        assertEquals(t8FaceChildidToIsInside.length, TetreeConnectivity.FACE_CHILDID_TO_IS_INSIDE.length,
                     "FACE_CHILDID_TO_IS_INSIDE row count");
        for (int face = 0; face < t8FaceChildidToIsInside.length; face++) {
            for (int bey = 0; bey < 8; bey++) {
                assertEquals(t8FaceChildidToIsInside[face][bey],
                             TetreeConnectivity.FACE_CHILDID_TO_IS_INSIDE[face][bey],
                             "FACE_CHILDID_TO_IS_INSIDE[" + face + "][" + bey + "] diverges from t8code "
                             + "t8_dpyramid_face_childid_to_is_inside");
            }
        }
    }

    /** cube-id (0..7) of the tet's anchor at refinement level {@code l} ({@code compute_cubeid}). */
    private static int cubeId(Tet t, byte l) {
        int h = Constants.lengthAtLevel(l);
        return ((t.x() & h) != 0 ? 1 : 0) | ((t.y() & h) != 0 ? 2 : 0) | ((t.z() & h) != 0 ? 4 : 0);
    }

    // ---- universe ----

    /** All DEEP pyramid-rooted tets (0 <= minTetLevel < level) at any level up to maxLevel, by refinement. */
    private static List<Tet> deepPyramidRootedTets(int maxLevel) {
        var out = new ArrayList<Tet>();
        var stack = new ArrayDeque<HybridElement>();
        stack.push(new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6));
        stack.push(new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7));
        while (!stack.isEmpty()) {
            var e = stack.pop();
            if (e instanceof Tet t && t.minTetLevel() != Tet.NO_TET_ANCESTOR && t.minTetLevel() < t.level()) {
                out.add(t);
            }
            if (e.level() >= maxLevel) {
                continue;
            }
            if (e instanceof Pyramid p) {
                for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                    stack.push(p.child(i));
                }
            } else {
                var t = (Tet) e;
                for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_TET; i++) {
                    stack.push(t.child(i));
                }
            }
        }
        return out;
    }

    private static boolean sameGeom(HybridElement a, Tet t) {
        return a instanceof Tet at && at.x() == t.x() && at.y() == t.y() && at.z() == t.z()
               && at.level() == t.level() && at.type() == t.type();
    }

    private static String desc(Tet t) {
        return "Tet(" + t.x() + "," + t.y() + "," + t.z() + ",l" + t.level() + ",t" + t.type()
               + ",minTet=" + t.minTetLevel() + ")";
    }

    private static String descEl(HybridElement e) {
        return e.getClass().getSimpleName() + "(" + e.x() + "," + e.y() + "," + e.z() + ",l" + e.level()
               + ",t" + e.type() + ")";
    }
}
