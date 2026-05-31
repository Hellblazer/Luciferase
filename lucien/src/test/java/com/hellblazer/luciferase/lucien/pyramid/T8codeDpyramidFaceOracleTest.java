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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * t8code parity oracle for pyramid-source face neighbors, asserted against the <b>Luciferase
 * reachable-SFC domain contract</b> (RDR-012 D0.1 §C-1..C-5, bead {@code Luciferase-dgzx}; this gate is
 * D0.2, bead {@code Luciferase-v7xc}). This is the independent whole-domain oracle the gap analysis
 * (2026-05-31) found missing (the {@code kyz9} oracle bead was closed won't-do).
 *
 * <p>The oracle is a fresh transcription of t8code {@code t8_dpyramid_face_neighbour} (pyramid branch)
 * and {@code t8_dpyramid_is_inside_root} from {@code main@76a5347b}
 * ({@code src/t8_schemes/t8_default/t8_default_pyramid/t8_dpyramid_bits.c}) — it does NOT call the
 * system-under-test ({@code Pyramid.faceNeighbor}) to derive the expected value.
 *
 * <p><b>Why this is NOT a raw is_inside_root parity test.</b> t8code's {@code is_inside_root} tests
 * membership in a <em>single root-pyramid simplex</em> ({@code x>=z, y>=z} plus apex-face tie-breaks) —
 * one tree of a t8code forest. Luciferase {@code PyramidIndex} is <em>cube-rooted</em> (root pyramids 6
 * AND 7 plus root tets) — one tree spanning the whole cube (RDR-012 §C-2). The two domains are not the
 * same set and Luciferase's is strictly larger (§C-3), so the relation is intrinsically a partition,
 * not equality. The reachable-SFC predicate is {@code PyramidKeyCodec.encode(e) != null} — an
 * encode→decode round-trip against the canonical decoder, not a closed-form inequality (§C-1).
 *
 * <p><b>What this gate asserts (the contract, §C-4/§C-5):</b>
 * <ol>
 *   <li><b>Geometry parity ({@code value==0}).</b> Wherever Luciferase keeps the neighbor
 *       ({@code encode != null}) AND t8code reports it inside-root, the neighbor's
 *       {@code (x,y,z,type,face)} must agree exactly. This is the genuine correctness property and must
 *       hold unconditionally.</li>
 *   <li><b>D_fix tripwire ({@code liveGap==0}).</b> Every under-permissive case (t8code inside,
 *       Luciferase drops) is reconstructed with the <em>corrected</em> {@code minTetLevel = level} and
 *       must <em>still</em> fail {@code encode()}. This exhaustively proves §C-5's claim that the
 *       under-permissive set is a genuine cube-vs-single-root domain difference, not a missing-metadata
 *       {@code encode()} reachability bug. A non-zero {@code liveGap} is a live shallow-boundary
 *       reachability gap and must escalate to RDR-012 D_fix.</li>
 *   <li><b>Partition lock.</b> The exact (matched / over / under) split is pinned so the domain
 *       characterization cannot silently drift on a {@code Pyramid}/{@code encode} change (§C-4).</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
class T8codeDpyramidFaceOracleTest {

    private static final int ROOT_LEN  = Constants.MAX_EXTENT;     // 1<<21, == t8code T8_DPYRAMID_ROOT_LEN
    private static final int MAX_SWEEP = 5;

    // Locked partition over the level<=5 sweep of both root pyramids (18660 pyramids), measured through
    // the encode() reachability filter (RDR-012 §C-4; T2 rdr/rdr-010-8xus-oracle-findings-2026-05-31).
    private static final int EXPECTED_MATCHED = 49523;
    private static final int EXPECTED_OVER    = 26569; // Luciferase keeps (sibling tree in the cube); t8code "outside root"
    private static final int EXPECTED_UNDER   = 17208; // t8code "inside root"; Luciferase drops (genuinely unreachable here)

    /**
     * Ground-truth anchors (locks the t8code transcription against hand-verified cases derived directly
     * from {@code t8_dpyramid_bits.c} ({@code is_inside_root:883}, {@code face_neighbour:599}) at
     * {@code main@76a5347b}, so the partition counts cannot silently drift on a transcription error
     * (gate critique Obs3). {@code len(l1) = 2^(21-1) = 1048576}.
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

    /**
     * The contract gate (RDR-012 D0.2). Asserts geometry parity, the D_fix tripwire, and the locked
     * domain partition over the refined two-root sweep. See class javadoc for the three properties.
     */
    @Test
    void pyramidFaceNeighborsMatchT8codeOverTheRefinedTree() {
        var divergences = new ArrayList<String>();
        var liveGaps = new ArrayList<String>();

        // Partition of (pyramid, face) candidates through the encode() reachability filter vs t8code
        // is_inside_root. matched = both agree (present+inside with same geometry, or absent+outside);
        // over = Luciferase keeps / t8code outside; under = t8code inside / Luciferase drops;
        // value = both present+inside but geometry disagrees (the only true bug class for kept neighbors).
        int matched = 0, over = 0, under = 0, value = 0;
        // liveGap: an under-permissive tet that, reconstructed with the corrected minTetLevel, DOES encode.
        // Must stay 0 — a positive count is a live encode() reachability gap (RDR-012 D_fix).
        int liveGap = 0;

        for (var p : validPyramids(MAX_SWEEP)) {
            for (int f = 0; f < 5; f++) {
                HybridFaceNeighbor luc = p.faceNeighbor(f);

                // --- independent t8code oracle ---
                int[] cand = oracleRawPyramidNeighbor(p, f); // {nx,ny,nz,ntype,nface}
                boolean t8Inside = oracleIsInsideRoot(cand[0], cand[1], cand[2], p.level(), (byte) cand[3]);

                // The element as the live detector sees it: kept iff PyramidKeyCodec.encode != null
                // (RDR-012 §C-1 reachable-SFC predicate — the gate PyramidNeighborDetector applies).
                boolean kept = luc != null && encodes(luc.element());

                if (!kept) {
                    if (t8Inside) {
                        under++;
                        // D_fix tripwire: reconstruct the t8code "inside" candidate with the CORRECTED
                        // minTetLevel and verify it is STILL unreachable in Luciferase's SFC. If it now
                        // encodes, the drop was a missing-metadata bug, not a domain difference.
                        if (reconstructEncodesWithCorrectedDepth(cand, p.level())) {
                            liveGap++;
                            if (liveGaps.size() < 40) {
                                liveGaps.add(desc(p) + " f" + f + " -> t8code=" + candDesc(cand, p.level())
                                             + " encodes with minTetLevel=" + p.level());
                            }
                        } else if (divergences.size() < 40) {
                            divergences.add("UNDER " + desc(p) + " f" + f + " -> dropped ; t8code="
                                            + candDesc(cand, p.level()));
                        }
                    } else {
                        matched++;
                    }
                } else if (!t8Inside) {
                    over++;
                    if (divergences.size() < 40) {
                        divergences.add("OVER  " + desc(p) + " f" + f + " -> luc=" + desc(luc.element())
                                        + " (encode!=null) ; t8code=outside-root");
                    }
                } else if (sameAs(luc, cand)) {
                    matched++;
                } else {
                    value++;
                    if (divergences.size() < 40) {
                        divergences.add("VALUE " + desc(p) + " f" + f + " -> luc=" + desc(luc.element())
                                        + " ; t8code=" + candDesc(cand, p.level()));
                    }
                }
            }
        }

        var sb = new StringBuilder();
        sb.append("t8code pyramid face-neighbor parity (contract gate, RDR-012 D0.2) over ")
          .append(validPyramids(MAX_SWEEP).size()).append(" pyramids (level<=").append(MAX_SWEEP).append("):\n");
        sb.append("  matched=").append(matched).append(" over=").append(over).append(" under=").append(under)
          .append(" value=").append(value).append(" liveGap=").append(liveGap).append('\n');
        sb.append("  --- sample divergences (max 40) ---\n");
        divergences.forEach(d -> sb.append("    ").append(d).append('\n'));
        if (!liveGaps.isEmpty()) {
            sb.append("  --- LIVE ENCODE() GAPS (escalate to RDR-012 D_fix) ---\n");
            liveGaps.forEach(d -> sb.append("    ").append(d).append('\n'));
        }
        String report = sb.toString();

        // (1) Geometry parity: wherever both keep the neighbor, the geometry must agree. The only true
        //     bug class for kept neighbors. Must be 0 unconditionally.
        assertEquals(0, value, "Geometry divergence on kept neighbors (RDR-012 §C-4 correctness property)\n" + report);
        // (2) D_fix tripwire: no under-permissive case is a recoverable encode() reachability bug.
        //     A positive liveGap escalates to RDR-012 D_fix (§C-5).
        assertEquals(0, liveGap, "Live encode() reachability gap — escalate to RDR-012 D_fix (§C-5)\n" + report);
        // (3) Partition lock: the cube-vs-single-root domain split is pinned (§C-4). over/under are the
        //     legitimate domain difference, NOT bugs; this anchors them against silent drift.
        assertEquals(EXPECTED_MATCHED, matched, "matched count drift (partition lock, RDR-012 §C-4)\n" + report);
        assertEquals(EXPECTED_OVER, over, "over-permissive count drift (sibling-tree neighbors, §C-4)\n" + report);
        assertEquals(EXPECTED_UNDER, under, "under-permissive count drift (genuinely-unreachable tets, §C-4)\n" + report);
    }

    private static boolean sameAs(HybridFaceNeighbor luc, int[] cand) {
        var e = luc.element();
        return e.x() == cand[0] && e.y() == cand[1] && e.z() == cand[2] && e.type() == cand[3]
               && luc.face() == cand[4];
    }

    private static boolean encodes(HybridElement e) {
        return (e instanceof Pyramid py) ? PyramidKeyCodec.encode(py) != null
                                         : PyramidKeyCodec.encode((Tet) e) != null;
    }

    /**
     * D_fix tripwire helper (RDR-012 §C-5). Reconstruct the t8code inside-root candidate with the
     * <em>corrected</em> {@code minTetLevel = level} (the metadata {@code Pyramid.faceNeighbor}'s 5-arg
     * {@code Tet} ctor omits, defaulting to {@code NO_TET_ANCESTOR}) and test whether it now encodes. A
     * tet candidate (type 0..5) becomes a shallowest pyramid-rooted tet; a pyramid candidate (6/7) is
     * tried as a pure-pyramid cell. Returns {@code true} iff the corrected element is reachable — which
     * would mean the original drop hid a recoverable element (a live gap), not a domain difference.
     */
    private static boolean reconstructEncodesWithCorrectedDepth(int[] cand, byte level) {
        int x = cand[0], y = cand[1], z = cand[2];
        byte type = (byte) cand[3];
        if (x < 0 || y < 0 || z < 0) {
            return false; // outside the cube; cannot be a reachable element regardless of metadata
        }
        try {
            if (type <= 5) {
                // shallowest pyramid-rooted tet: minTetLevel == level
                return PyramidKeyCodec.encode(new Tet(x, y, z, level, type, level)) != null;
            }
            return PyramidKeyCodec.encode(new Pyramid(x, y, z, level, type)) != null;
        } catch (IllegalArgumentException | IllegalStateException | AssertionError e) {
            // Not a constructible/valid element at that cell — definitively not reachable.
            return false;
        }
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
