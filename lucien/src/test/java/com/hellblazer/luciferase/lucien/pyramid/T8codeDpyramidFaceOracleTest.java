/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.HybridFaceNeighbor;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * t8code parity oracle for pyramid-source face neighbors (RDR-010 remediation P1, bead
 * Luciferase-8xus). This is the independent whole-domain oracle the gap analysis (2026-05-31) found
 * missing (the {@code kyz9} oracle bead was closed won't-do).
 *
 * <p>The oracle is a fresh transcription of t8code {@code t8_dpyramid_face_neighbour} (pyramid branch)
 * and {@code t8_dpyramid_is_inside_root} from {@code main@76a5347b}
 * ({@code src/t8_schemes/t8_default/t8_default_pyramid/t8_dpyramid_bits.c}) — it does NOT call the
 * system-under-test ({@code Pyramid.faceNeighbor}) to derive the expected value. t8code's
 * {@code t8_dpyramid_face_neighbor_inside} computes the raw neighbor, then returns it ONLY if
 * {@code is_inside_root(neigh)} holds; otherwise there is no inside neighbor (null).
 *
 * <p><b>Known divergence under test (gap finding, HIGH):</b> {@code Pyramid.faceNeighbor} gates the
 * neighbor on an axis-aligned cube bound {@code [0, MAX_COORD]} only, whereas t8code gates on the root
 * <em>simplex</em> ({@code x>=z, y>=z} plus the degenerate-apex-face tie-breaks). Luciferase is
 * therefore over-permissive: it returns neighbors t8code nulls. This test asserts full t8code parity;
 * until the {@code is_inside_root} contract is resolved (B1 embed simplex test in the primitive, vs
 * B2 formalize the {@code PyramidKeyCodec.encode} filter as the canonical gate) it is expected to fail,
 * and its failure message quantifies the exact divergence set that drives that decision.
 *
 * @author hal.hildebrand
 */
class T8codeDpyramidFaceOracleTest {

    private static final int ROOT_LEN  = Constants.MAX_EXTENT;     // 1<<21, == t8code T8_DPYRAMID_ROOT_LEN
    private static final int MAX_SWEEP = 5;

    /**
     * Ground-truth anchors (enabled, runs in CI). Locks the t8code transcription against hand-verified
     * cases derived directly from {@code t8_dpyramid_bits.c} ({@code is_inside_root:883},
     * {@code face_neighbour:599}) at {@code main@76a5347b}, so the characterization sweep's counts cannot
     * silently drift on a transcription error (gate critique Obs3). {@code len(l1) = 2^(21-1) = 1048576}.
     */
    @Test
    void oracleGroundTruthAnchors() {
        int len1 = Constants.lengthAtLevel((byte) 1);
        assertEquals(1 << 20, len1, "len at level 1");

        // is_inside_root: level-0 root is the type-6 origin only
        org.junit.jupiter.api.Assertions.assertTrue(oracleIsInsideRoot(0, 0, 0, (byte) 0, Pyramid.TYPE_6));
        org.junit.jupiter.api.Assertions.assertFalse(oracleIsInsideRoot(0, 0, 0, (byte) 0, Pyramid.TYPE_7));

        // simplex bound: x < z is outside
        org.junit.jupiter.api.Assertions.assertFalse(oracleIsInsideRoot(3, 5, 5, (byte) 1, Pyramid.TYPE_6));
        // interior simplex point (x>=z, y>=z), no tie-break trigger -> inside
        org.junit.jupiter.api.Assertions.assertTrue(oracleIsInsideRoot(5, 3, 3, (byte) 1, (byte) 3));
        // degenerate-apex tie-break (flat type check, no shape gate — verified vs t8_dpyramid_bits.c:895):
        // x==z with tet type 3 -> outside; y==z with tet type 0 -> outside
        org.junit.jupiter.api.Assertions.assertFalse(oracleIsInsideRoot(5, 5, 5, (byte) 1, (byte) 3));
        org.junit.jupiter.api.Assertions.assertFalse(oracleIsInsideRoot(5, 5, 5, (byte) 1, (byte) 0));
        // same x==z coords but a non-tie-break tet type (1) stays inside -> tie-break is type-scoped
        org.junit.jupiter.api.Assertions.assertTrue(oracleIsInsideRoot(5, 5, 5, (byte) 1, (byte) 1));

        // face_neighbour: type-6 face 1 -> tet type 3, anchor x += len, reciprocal face 3
        int[] f1 = oracleRawPyramidNeighbor(new Pyramid(0, 0, 0, (byte) 1, Pyramid.TYPE_6), 1);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] { len1, 0, 0, 3, 3 }, f1);
        // type-6 face 4 (quad base) -> other pyramid type 7, z -= len (negative -> outside root)
        int[] f4 = oracleRawPyramidNeighbor(new Pyramid(0, 0, 0, (byte) 1, Pyramid.TYPE_6), 4);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] { 0, 0, -len1, Pyramid.TYPE_7, 4 }, f4);
        org.junit.jupiter.api.Assertions.assertFalse(oracleIsInsideRoot(f4[0], f4[1], f4[2], (byte) 1, (byte) f4[3]));
    }

    @Test
    @Disabled("""
              CHARACTERIZATION HARNESS, not yet a pass/fail gate (RDR-012 D0, ex-remediation-P1, Luciferase-8xus).
              Asserting raw t8code is_inside_root parity is the WRONG target: t8code's is_inside_root tests
              membership in a SINGLE root-pyramid simplex, whereas Luciferase PyramidIndex is CUBE-rooted
              (root pyramids 6 AND 7 plus root tets). Measured over 18660 pyramids (level<=5):
                RAW primitive : matched=48071 over=45229 under=0
                EFFECTIVE     : matched=49523 over=26569 under=17208
              Both-direction divergence is attributable to the cube-vs-single-root domain difference (the
              is_inside_root tie-break transcription is source-verified — flat type check, t8_dpyramid_bits.c:895
              — and a minTetLevel=level reconstruction probe was refuted: the under-permissive tet neighbors
              are genuinely unreachable in Luciferase's SFC). See oracleGroundTruthAnchors() for the locked
              transcription cases. Re-enable once RDR-012 D0 defines the pyramid-index DOMAIN/reachability
              contract and the oracle is reframed to assert against Luciferase's reachable-SFC set with an
              explicit single-tree<->cube mapping. See T2 rdr/rdr-010-8xus-oracle-findings-2026-05-31.""")
    void pyramidFaceNeighborsMatchT8codeOverTheRefinedTree() {
        var divergences = new ArrayList<String>();
        // Raw-primitive parity: Pyramid.faceNeighbor vs t8code (gates on cube AABB only).
        int rawMatched = 0, rawOver = 0, rawUnder = 0, rawValue = 0;
        // Effective parity: the neighbor as the live detector sees it — i.e. kept only if
        // PyramidKeyCodec.encode(neighbor) != null. This is the filter PyramidNeighborDetector applies;
        // it is the de-facto is_inside_root equivalent. If THIS matches t8code, the contract is B2.
        int effMatched = 0, effOver = 0, effUnder = 0, effValue = 0;

        for (var p : validPyramids(MAX_SWEEP)) {
            for (int f = 0; f < 5; f++) {
                HybridFaceNeighbor luc = p.faceNeighbor(f);

                // --- independent t8code oracle ---
                int[] cand = oracleRawPyramidNeighbor(p, f); // {nx,ny,nz,ntype,nface}
                boolean t8Inside = oracleIsInsideRoot(cand[0], cand[1], cand[2], p.level(), (byte) cand[3]);

                // raw-primitive classification
                if (luc == null) {
                    if (t8Inside) {
                        rawUnder++;
                    } else {
                        rawMatched++;
                    }
                } else if (!t8Inside) {
                    rawOver++;
                } else if (sameAs(luc, cand)) {
                    rawMatched++;
                } else {
                    rawValue++;
                }

                // effective classification (through the encode() filter)
                boolean effPresent = luc != null && encodes(luc.element());
                if (!effPresent) {
                    if (t8Inside) {
                        effUnder++;
                        if (divergences.size() < 40) {
                            divergences.add("EFF UNDER  " + desc(p) + " f" + f + " -> filtered/null ; t8code="
                                            + candDesc(cand, p.level()));
                        }
                    } else {
                        effMatched++;
                    }
                } else if (!t8Inside) {
                    effOver++;
                    if (divergences.size() < 40) {
                        divergences.add("EFF OVER   " + desc(p) + " f" + f + " -> luc=" + desc(luc.element())
                                        + " (encode!=null) ; t8code=null");
                    }
                } else if (sameAs(luc, cand)) {
                    effMatched++;
                } else {
                    effValue++;
                }
            }
        }

        var sb = new StringBuilder();
        sb.append("t8code pyramid face-neighbor parity over ").append(validPyramids(MAX_SWEEP).size())
          .append(" pyramids (level<=").append(MAX_SWEEP).append("):\n");
        sb.append("  RAW primitive (Pyramid.faceNeighbor, cube-AABB gate):  matched=").append(rawMatched)
          .append(" over=").append(rawOver).append(" under=").append(rawUnder).append(" value=").append(rawValue)
          .append('\n');
        sb.append("  EFFECTIVE (through encode() filter the detector uses): matched=").append(effMatched)
          .append(" over=").append(effOver).append(" under=").append(effUnder).append(" value=").append(effValue)
          .append('\n');
        sb.append("  --- sample effective divergences (max 40) ---\n");
        divergences.forEach(d -> sb.append("    ").append(d).append('\n'));

        // The live contract is the EFFECTIVE relation: PyramidKeyCodec.encode() is the canonical
        // validity gate (B2). Assert it matches t8code is_inside_root element-for-element.
        assertEquals(0, effOver + effUnder + effValue, sb.toString());
    }

    private static boolean sameAs(HybridFaceNeighbor luc, int[] cand) {
        var e = luc.element();
        return e.x() == cand[0] && e.y() == cand[1] && e.z() == cand[2] && e.type() == cand[3]
               && luc.face() == cand[4];
    }

    private static boolean encodes(HybridElement e) {
        return (e instanceof Pyramid py) ? PyramidKeyCodec.encode(py) != null
                                         : PyramidKeyCodec.encode((com.hellblazer.luciferase.lucien.tetree.Tet) e) != null;
    }

    // ---- independent t8code transcription (main@76a5347b, t8_dpyramid_bits.c) ----

    /** {@code t8_dpyramid_face_neighbour}, pyramid branch. Returns {nx,ny,nz,ntype,nface}; level unchanged. */
    private static int[] oracleRawPyramidNeighbor(Pyramid p, int f) {
        int len = Constants.lengthAtLevel(p.level());
        int nx = p.x(), ny = p.y(), nz = p.z();
        byte type = p.type();
        int ntype;
        if (f == 0 || f == 1) {
            ntype = 3;
        } else if (f == 2 || f == 3) {
            ntype = 0;
        } else { // f == 4
            ntype = (type == Pyramid.TYPE_6) ? Pyramid.TYPE_7 : Pyramid.TYPE_6;
        }
        if (f == 1) {
            nx += (type == Pyramid.TYPE_6) ? len : 0;
            ny += (type == Pyramid.TYPE_6) ? 0 : -len;
        } else if (f == 3) {
            nx += (type == Pyramid.TYPE_6) ? 0 : -len;
            ny += (type == Pyramid.TYPE_6) ? len : 0;
        } else if (f == 4) {
            nz += (type == Pyramid.TYPE_6) ? -len : len;
        }
        int nface = TetreeConnectivity.PYRAMID_TYPE_FACE_TO_NFACE[type - Pyramid.TYPE_6][f];
        return new int[] { nx, ny, nz, ntype, nface };
    }

    /** {@code t8_dpyramid_is_inside_root}. */
    private static boolean oracleIsInsideRoot(int x, int y, int z, byte level, byte type) {
        if (level == 0) {
            return type == Pyramid.TYPE_6 && x == 0 && y == 0 && z == 0;
        }
        if (0 <= z && z < ROOT_LEN && x >= z && x < ROOT_LEN && y >= z && y < ROOT_LEN) {
            // degenerate-apex-face tie-break: exactly-one-owner on the x==z / y==z planes
            if ((x == z && (type == 3 || type == 5)) || (y == z && (type == 0 || type == 4))) {
                return false;
            }
            return true;
        }
        return false;
    }

    // ---- sweep + formatting ----

    private static List<Pyramid> validPyramids(int maxLevel) {
        var out = new ArrayList<Pyramid>();
        for (var root : new Pyramid[] { new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6),
                                        new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7) }) {
            descend(root, maxLevel, out);
        }
        return out;
    }

    private static void descend(Pyramid p, int maxLevel, List<Pyramid> out) {
        if (p.level() >= 1) {
            out.add(p);
        }
        if (p.level() >= maxLevel) {
            return;
        }
        for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
            HybridElement child = p.child(i);
            if (child instanceof Pyramid pc) {
                descend(pc, maxLevel, out);
            }
        }
    }

    private static String desc(HybridElement e) {
        return "P(" + e.x() + "," + e.y() + "," + e.z() + ",l" + e.level() + ",t" + e.type() + ")";
    }

    private static String candDesc(int[] c, byte level) {
        return "(" + c[0] + "," + c[1] + "," + c[2] + ",l" + level + ",t" + c[3] + ",nface" + c[4] + ")";
    }
}
