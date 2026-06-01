package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance gate for Luciferase-4bmd: {@link Tet#faceNeighbor(int)} is the one remaining core dtet
 * primitive without a t8code ground-truth oracle. A connectivity-table transcription bug in the
 * face-neighbour algorithm would corrupt {@code TetreeNeighborDetector} output (ghost-zone
 * construction, kNN BFS expansion) while leaving every existing reciprocity-only test green.
 *
 * <p>This test embeds a re-transcription of {@code t8_dtri_face_neighbour} (the 3D
 * {@code T8_DTRI_TO_DTET} branch, from t8code {@code main}
 * {@code src/t8_schemes/t8_default/t8_default_tri/t8_dtri_bits.c}, lines 685-757) and asserts that
 * {@code Tet.faceNeighbor} matches it — neighbour coordinates, level, type, AND the returned dual
 * face — over a DFS of a refined tree across all 6 types.
 *
 * <p><b>Independence scope (be precise about what this catches).</b> {@code Tet.faceNeighbor} is
 * itself a Java transliteration of the same C function, so the DFS-parity arm catches per-character
 * transcription divergence between the two Java copies but NOT a defect faithfully copied into both
 * (or pre-existing in t8code). Two arms ARE genuinely independent: (1) the reciprocity / involution
 * check, which re-derives nothing — it round-trips the SUT against itself and would catch any
 * asymmetry between the face-0/3 coord arithmetic and the face-1/2 type arithmetic; and (2) the
 * {@code faceNeighborSpotChecks} anchors, hand-computed by stepping the C logic by hand for specific
 * (type, face) pairs, which pin absolute expected values rather than re-running the algorithm.
 *
 * <p>Validation method follows t8code's own ({@code t8_gtest_face_neigh.cxx}): oracle parity plus
 * reciprocity / involution, never a "shares >= N vertices" geometric assertion (Tet face neighbors
 * are non-conforming and share 0-3 vertices even for pure tets — see CLAUDE.md "Face-neighbor
 * testing caveat").
 *
 * <p>The root tet ({@code l == 0}) is excluded from the sweep: {@code Tet.faceNeighbor} returns null for all
 * four root faces (faces 0/3 via the coordinate out-of-bounds check, faces 1/2 via the
 * {@code l == 0 && typeNew != 0} guard). That guard is CORRECT, not spurious (Luciferase-t6su resolved this):
 * it inlines t8code's caller-side {@code t8_dtri_is_inside_root} check — a level-0 non-type-0 simplex is
 * outside the root tree, so a type-changing root face has no same-tree neighbor. This oracle pins the
 * algorithm for {@code l >= 1}, where t8code and Luciferase agree unconditionally; root behavior is pinned by
 * {@code TetFaceNeighborRootTest}.
 */
class T8codeDtetFaceNeighborOracleTest {

    private static final byte MAX = Constants.getMaxRefinementLevel();

    private record OracleTet(int x, int y, int z, byte level, byte type) {}

    /** Result of the oracle face-neighbour: the neighbour plus the dual (return) face. */
    private record OracleNeighbor(OracleTet tet, int dualFace) {}

    /**
     * Independent transcription of {@code t8_dtri_face_neighbour}, 3D branch ({@code T8_DTRI_TO_DTET}).
     * Returns the neighbour and the dual face {@code ret}. Coordinates are NOT bounds-clamped here —
     * the caller decides domain membership, exactly as t8code does.
     */
    private static OracleNeighbor faceNeighbour(OracleTet t, int face) {
        int typeOld = t.type;
        int typeNew = typeOld;
        int[] coords = { t.x, t.y, t.z };
        int len = 1 << (MAX - t.level); // T8_DTRI_LEN(level)
        int ret;

        typeNew += 6; // compute modulo six without negatives

        if (face == 1 || face == 2) {
            int sign = (typeNew % 2 == 0 ? 1 : -1);
            sign *= (face % 2 == 0 ? 1 : -1);
            typeNew += sign;
            typeNew %= 6;
            ret = face;
        } else {
            if (face == 0) {
                coords[typeOld / 2] += len;
                typeNew += (typeNew % 2 == 0 ? 4 : 2);
            } else { // face == 3
                coords[((typeNew + 3) % 6) / 2] -= len;
                typeNew += (typeNew % 2 == 0 ? 2 : 4);
            }
            typeNew %= 6;
            ret = 3 - face;
        }
        return new OracleNeighbor(new OracleTet(coords[0], coords[1], coords[2], t.level, (byte) typeNew), ret);
    }

    private static boolean inBounds(OracleTet t) {
        return t.x >= 0 && t.y >= 0 && t.z >= 0 && t.x <= Constants.MAX_COORD && t.y <= Constants.MAX_COORD
        && t.z <= Constants.MAX_COORD;
    }

    /** Independent t8code child (Morton order), reused from the dtet connectivity port. */
    private static final int[][] INDEX_TO_BEY = { { 0, 1, 4, 5, 2, 7, 6, 3 }, { 0, 1, 5, 4, 7, 2, 6, 3 },
                                                  { 0, 4, 5, 1, 2, 7, 6, 3 }, { 0, 1, 5, 4, 6, 7, 2, 3 },
                                                  { 0, 4, 5, 1, 6, 2, 7, 3 }, { 0, 5, 4, 1, 6, 7, 2, 3 } };
    private static final int[][] TYPE_OF_CHILD = { { 0, 0, 0, 0, 4, 5, 2, 1 }, { 1, 1, 1, 1, 3, 2, 5, 0 },
                                                   { 2, 2, 2, 2, 0, 1, 4, 3 }, { 3, 3, 3, 3, 5, 4, 1, 2 },
                                                   { 4, 4, 4, 4, 2, 3, 0, 5 }, { 5, 5, 5, 5, 1, 0, 3, 4 } };
    private static final int[]   BEYID_TO_VERTEX = { 0, 1, 2, 3, 1, 1, 2, 2 };

    private static int[] computeCoords(OracleTet t, int vertex) {
        int h = 1 << (MAX - t.level);
        int ei = t.type / 2;
        int ej = (ei + ((t.type % 2 == 0) ? 2 : 1)) % 3;
        int[] c = { t.x, t.y, t.z };
        if (vertex == 0) {
            return c;
        }
        c[ei] += h;
        if (vertex == 1) {
            return c;
        }
        if (vertex == 2) {
            c[ej] += h;
            return c;
        }
        c[0] = t.x + h;
        c[1] = t.y + h;
        c[2] = t.z + h;
        return c;
    }

    private static OracleTet child(OracleTet parent, int morton) {
        int bey = INDEX_TO_BEY[parent.type][morton];
        byte childLevel = (byte) (parent.level + 1);
        int ax, ay, az;
        if (bey == 0) {
            ax = parent.x;
            ay = parent.y;
            az = parent.z;
        } else {
            int[] vc = computeCoords(parent, BEYID_TO_VERTEX[bey]);
            ax = (parent.x + vc[0]) >> 1;
            ay = (parent.y + vc[1]) >> 1;
            az = (parent.z + vc[2]) >> 1;
        }
        return new OracleTet(ax, ay, az, childLevel, (byte) TYPE_OF_CHILD[parent.type][bey]);
    }

    @Test
    void faceNeighborMatchesT8codeOverDFS() {
        int maxDepth = 5;
        Deque<OracleTet> work = new ArrayDeque<>();
        work.push(new OracleTet(0, 0, 0, (byte) 0, (byte) 0));

        int parityChecks = 0;   // in-bounds neighbours matched against oracle
        int boundaryChecks = 0; // out-of-bounds neighbours where Tet returns null
        int reciprocityChecks = 0;
        var typesSeen = new java.util.HashSet<Byte>();

        while (!work.isEmpty()) {
            var o = work.pop();

            if (o.level >= 1) {
                typesSeen.add(o.type);
                var tet = new Tet(o.x, o.y, o.z, o.level, o.type);
                for (int face = 0; face < 4; face++) {
                    var oracle = faceNeighbour(o, face);
                    var fn = tet.faceNeighbor(face);

                    if (!inBounds(oracle.tet)) {
                        assertNull(fn, "Tet.faceNeighbor must be null at domain boundary: " + o + " face " + face);
                        boundaryChecks++;
                        continue;
                    }

                    assertNotNull(fn, "Tet.faceNeighbor null but oracle in-bounds: " + o + " face " + face);
                    var n = fn.tet();
                    String ctx = " (tet=" + o + " face=" + face + ")";
                    assertEquals(oracle.tet.x, n.x(), "neighbor x mismatch" + ctx);
                    assertEquals(oracle.tet.y, n.y(), "neighbor y mismatch" + ctx);
                    assertEquals(oracle.tet.z, n.z(), "neighbor z mismatch" + ctx);
                    assertEquals(oracle.tet.level, n.l(), "neighbor level mismatch" + ctx);
                    assertEquals(oracle.tet.type, n.type(), "neighbor type mismatch" + ctx);
                    assertEquals(oracle.dualFace, fn.face(), "dual (return) face mismatch" + ctx);
                    parityChecks++;

                    // Reciprocity / involution: neighbor(neighbor(e, f).dualFace) == e.
                    var back = n.faceNeighbor(oracle.dualFace);
                    assertNotNull(back, "reciprocal neighbor null" + ctx);
                    var b = back.tet();
                    assertEquals(o.x, b.x(), "reciprocity x" + ctx);
                    assertEquals(o.y, b.y(), "reciprocity y" + ctx);
                    assertEquals(o.z, b.z(), "reciprocity z" + ctx);
                    assertEquals(o.level, b.l(), "reciprocity level" + ctx);
                    assertEquals(o.type, b.type(), "reciprocity type" + ctx);
                    assertEquals(face, back.face(), "reciprocity dual-of-dual face" + ctx);
                    reciprocityChecks++;
                }
            }

            if (o.level < maxDepth) {
                for (int m = 0; m < 8; m++) {
                    work.push(child(o, m));
                }
            }
        }

        // Non-vacuity. Depth-5 DFS from a type-0 root visits sum_{l=1}^{5} 8^l ~= 37,448 level>=1 tets,
        // each exercising 4 faces (~150k face pairs, the bulk in-bounds). A threshold of 100k catches a
        // traversal that silently stalls; > 1000 was ~100x too weak to defend that.
        assertTrue(parityChecks > 100_000, "expected substantial in-bounds parity coverage, got " + parityChecks);
        assertTrue(reciprocityChecks == parityChecks,
                   "every in-bounds neighbor must round-trip: parity=" + parityChecks + " reciprocity="
                   + reciprocityChecks);
        assertTrue(boundaryChecks > 0, "expected some domain-boundary (null) cases, got " + boundaryChecks);
        assertEquals(6, typesSeen.size(), "DFS must exercise all 6 tet types, saw " + typesSeen);
    }

    /**
     * Truly independent anchors: expected neighbour values hand-computed by stepping the t8code C logic
     * by hand (NOT by running the algorithm). Covers an even type (0) and an odd type (3) so both the
     * face-1/2 sign branch and the face-0/3 coordinate branch are pinned to absolute values. Anchors use
     * level 2 (h = 2^19 = 524288), offset to (h,h,h) so face-3 stays in bounds.
     */
    @Test
    void faceNeighborSpotChecks() {
        final int h = 1 << (MAX - 2); // 524288

        // type 0 @ (h,h,h), level 2:
        //   face0 -> coords[0]+=h, type (0+6+4)%6=4, dual 3-0=3
        //   face1 -> sign=+1*-1=-1, type (0+6-1)%6=5, dual 1
        //   face2 -> sign=+1*+1=+1, type (0+6+1)%6=1, dual 2
        //   face3 -> coords[((6+3)%6)/2=1]-=h, type (0+6+2)%6=2, dual 0
        assertSpot(new Tet(h, h, h, (byte) 2, (byte) 0), 0, 2 * h, h, h, (byte) 2, (byte) 4, 3);
        assertSpot(new Tet(h, h, h, (byte) 2, (byte) 0), 1, h, h, h, (byte) 2, (byte) 5, 1);
        assertSpot(new Tet(h, h, h, (byte) 2, (byte) 0), 2, h, h, h, (byte) 2, (byte) 1, 2);
        assertSpot(new Tet(h, h, h, (byte) 2, (byte) 0), 3, h, 0, h, (byte) 2, (byte) 2, 0);

        // type 3 @ (h,h,h), level 2:
        //   face0 -> coords[3/2=1]+=h, type (3+6+2)%6=5, dual 3
        //   face1 -> sign=-1*-1=+1, type (3+6+1)%6=4, dual 1
        //   face2 -> sign=-1*+1=-1, type (3+6-1)%6=2, dual 2
        //   face3 -> coords[((9+3)%6)/2=0]-=h, type (3+6+4)%6=1, dual 0
        assertSpot(new Tet(h, h, h, (byte) 2, (byte) 3), 0, h, 2 * h, h, (byte) 2, (byte) 5, 3);
        assertSpot(new Tet(h, h, h, (byte) 2, (byte) 3), 1, h, h, h, (byte) 2, (byte) 4, 1);
        assertSpot(new Tet(h, h, h, (byte) 2, (byte) 3), 2, h, h, h, (byte) 2, (byte) 2, 2);
        assertSpot(new Tet(h, h, h, (byte) 2, (byte) 3), 3, 0, h, h, (byte) 2, (byte) 1, 0);
    }

    private static void assertSpot(Tet t, int face, int ex, int ey, int ez, byte elvl, byte etype, int edual) {
        var fn = t.faceNeighbor(face);
        String ctx = " (tet type=" + t.type() + " face=" + face + ")";
        assertNotNull(fn, "spot-check neighbor null" + ctx);
        assertEquals(ex, fn.tet().x(), "spot x" + ctx);
        assertEquals(ey, fn.tet().y(), "spot y" + ctx);
        assertEquals(ez, fn.tet().z(), "spot z" + ctx);
        assertEquals(elvl, fn.tet().l(), "spot level" + ctx);
        assertEquals(etype, fn.tet().type(), "spot type" + ctx);
        assertEquals(edual, fn.face(), "spot dual face" + ctx);
    }
}
