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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <p><b>What this gate asserts (and what it does NOT prove):</b>
 * <ol>
 *   <li><b>Geometry parity across the whole inside-root set ({@code geomDiverge==0}).</b> For EVERY
 *       {@code (pyramid, face)} where t8code reports an inside-root neighbor and {@code Pyramid.faceNeighbor}
 *       produced one, the neighbor's {@code (x,y,z,type,face)} must agree with the t8code candidate —
 *       <em>regardless of whether the Luciferase reachability gate keeps it.</em> This validates
 *       {@code faceNeighbor}'s arithmetic against the independent t8code transcription over the full
 *       inside-root set, including the 17208 under-permissive neighbors the {@code encode()} gate drops
 *       (those would otherwise escape all geometry validation). This is the genuine correctness property.</li>
 *   <li><b>Partition lock.</b> The exact (matched / over / under) split through the {@code encode()}
 *       reachability gate is pinned (§C-4) so the cube-vs-single-root domain characterization cannot
 *       silently drift on a {@code Pyramid}/{@code encode} change.</li>
 *   <li><b>Seam mapping ({@link #seamMappingDemonstratesOverPermissiveSet}).</b> Concretely exhibits an
 *       over-permissive element — valid and reachable in Luciferase ({@code encode != null}) yet outside
 *       t8code's single root-pyramid ({@code is_inside_root == false}) — demonstrating the §C-3 single-tree↔cube
 *       mapping rather than only counting it.</li>
 * </ol>
 *
 * <p><b>Scope and what green does NOT establish (honest limits, RDR-012 §C-4/§C-5):</b>
 * <ul>
 *   <li>The under-permissive set (17208) is geometrically faithful to t8code AND dropped by {@code encode()};
 *       this is consistent with the cube-vs-single-root domain difference — each such position belongs to a
 *       <em>sibling</em> tree (pyramid-7 / root-tet) in Luciferase's cube partition. That every such position is
 *       in fact owned by some reachable sibling element is implied by the Knapp space-filling cardinality
 *       {@code N(ℓ)=2·8^ℓ−6^ℓ} but is NOT independently asserted by this test (a possible D3 strengthening:
 *       assert {@code PyramidIndex.locate(centroid)} is non-null for a sample of under positions).</li>
 *   <li>This sweep covers the <b>pyramid-source</b> face branch only ({@code Pyramid.faceNeighbor}); the
 *       refinement descends into pyramid children only. Tet-source cross-shape neighbors
 *       ({@code Tet.faceNeighborElement} from a pyramid-child tet) and the deep tet-return branch are NOT
 *       covered here — they are RDR-012 D3 (bead {@code Luciferase-dk12}), which the RDR's recommended D3
 *       path treats as required, not optional.</li>
 *   <li>Green confirms {@code faceNeighbor} geometry matches t8code and that the {@code encode()} partition is
 *       stable; it does NOT independently re-derive that {@code encode()} is "correct" beyond pinning its
 *       current behavior against the contract. No live {@code encode()} reachability gap (D_fix) was found.</li>
 * </ul>
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
    private static final int EXPECTED_UNDER   = 17208; // t8code "inside root"; Luciferase drops (sibling-tree position)

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
        assertTrue(oracleIsInsideRoot(0, 0, 0, (byte) 0, Pyramid.TYPE_6));
        org.junit.jupiter.api.Assertions.assertFalse(oracleIsInsideRoot(0, 0, 0, (byte) 0, Pyramid.TYPE_7));

        // simplex bound: x < z is outside
        org.junit.jupiter.api.Assertions.assertFalse(oracleIsInsideRoot(3, 5, 5, (byte) 1, Pyramid.TYPE_6));
        // interior simplex point (x>=z, y>=z), no tie-break trigger -> inside
        assertTrue(oracleIsInsideRoot(5, 3, 3, (byte) 1, (byte) 3));
        // degenerate-apex tie-break (flat type check, no shape gate — verified vs t8_dpyramid_bits.c:895):
        // x==z with tet type 3 -> outside; y==z with tet type 0 -> outside
        org.junit.jupiter.api.Assertions.assertFalse(oracleIsInsideRoot(5, 5, 5, (byte) 1, (byte) 3));
        org.junit.jupiter.api.Assertions.assertFalse(oracleIsInsideRoot(5, 5, 5, (byte) 1, (byte) 0));
        // same x==z coords but a non-tie-break tet type (1) stays inside -> tie-break is type-scoped
        assertTrue(oracleIsInsideRoot(5, 5, 5, (byte) 1, (byte) 1));

        // face_neighbour: type-6 face 1 -> tet type 3, anchor x += len, reciprocal face 3
        int[] f1 = oracleRawPyramidNeighbor(new Pyramid(0, 0, 0, (byte) 1, Pyramid.TYPE_6), 1);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] { len1, 0, 0, 3, 3 }, f1);
        // type-6 face 4 (quad base) -> other pyramid type 7, z -= len (negative -> outside root)
        int[] f4 = oracleRawPyramidNeighbor(new Pyramid(0, 0, 0, (byte) 1, Pyramid.TYPE_6), 4);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] { 0, 0, -len1, Pyramid.TYPE_7, 4 }, f4);
        org.junit.jupiter.api.Assertions.assertFalse(oracleIsInsideRoot(f4[0], f4[1], f4[2], (byte) 1, (byte) f4[3]));
    }

    /**
     * The contract gate (RDR-012 D0.2). Asserts geometry parity over the full inside-root set and the
     * locked domain partition over the refined two-root sweep. See class javadoc for what this does and
     * does not prove.
     */
    @Test
    void pyramidFaceNeighborsMatchT8codeOverTheRefinedTree() {
        var divergences = new ArrayList<String>();

        // Partition of (pyramid, face) candidates through the encode() reachability filter vs t8code
        // is_inside_root. matched = both agree (present+inside with same geometry, or absent+outside);
        // over = Luciferase keeps / t8code outside (cube sibling); under = t8code inside / Luciferase drops.
        int matched = 0, over = 0, under = 0;
        // geomDiverge: faceNeighbor and the t8code oracle disagree on the neighbor GEOMETRY for a
        // (pyramid, face) t8code considers inside-root. This is the genuine bug class and is checked across
        // the WHOLE inside-root set — kept AND dropped — so the under set does not escape validation.
        int geomDiverge = 0;
        var sweep = validPyramids(MAX_SWEEP);

        for (var p : sweep) {
            for (int f = 0; f < 5; f++) {
                HybridFaceNeighbor luc = p.faceNeighbor(f);

                // --- independent t8code oracle ---
                int[] cand = oracleRawPyramidNeighbor(p, f); // {nx,ny,nz,ntype,nface}
                boolean t8Inside = oracleIsInsideRoot(cand[0], cand[1], cand[2], p.level(), (byte) cand[3]);

                // The element as the live detector sees it: kept iff PyramidKeyCodec.encode != null
                // (RDR-012 §C-1 reachable-SFC predicate — the gate PyramidNeighborDetector applies).
                boolean kept = luc != null && encodes(luc.element());

                // (1) Independent geometry check across the whole inside-root set. faceNeighbor (cube-AABB
                //     gate) returns the raw neighbor for any non-negative coordinate; t8code returns it only
                //     when inside-root. Wherever t8code says inside-root AND faceNeighbor produced an
                //     element, the geometry must match — independent of the encode() reachability decision.
                if (t8Inside && luc != null && !sameAs(luc, cand)) {
                    geomDiverge++;
                    if (divergences.size() < 40) {
                        divergences.add("GEOM " + desc(p) + " f" + f + " -> luc=" + desc(luc.element())
                                        + " ; t8code=" + candDesc(cand, p.level()));
                    }
                    continue; // do not also classify into the partition; it is a bug, not a domain case
                }

                // (2) Partition classification (geometry already validated above for the inside set).
                if (!kept) {
                    if (t8Inside) {
                        under++;
                    } else {
                        matched++;
                    }
                } else if (!t8Inside) {
                    over++;
                } else {
                    matched++; // kept && t8Inside && sameAs
                }
            }
        }

        var sb = new StringBuilder();
        sb.append("t8code pyramid face-neighbor parity (contract gate, RDR-012 D0.2) over ")
          .append(sweep.size()).append(" pyramids (level<=").append(MAX_SWEEP).append("):\n");
        sb.append("  matched=").append(matched).append(" over=").append(over).append(" under=").append(under)
          .append(" geomDiverge=").append(geomDiverge).append('\n');
        if (!divergences.isEmpty()) {
            sb.append("  --- geometry divergences (max 40) ---\n");
            divergences.forEach(d -> sb.append("    ").append(d).append('\n'));
        }
        String report = sb.toString();

        // (1) Geometry parity over the whole inside-root set — the genuine correctness property; must be 0.
        assertEquals(0, geomDiverge, "faceNeighbor geometry diverges from t8code on the inside-root set "
                                     + "(RDR-012 §C-4 correctness property)\n" + report);
        // (2) Partition lock: the cube-vs-single-root domain split is pinned (§C-4). over/under are the
        //     legitimate domain difference, NOT bugs; this anchors them against silent drift.
        assertEquals(EXPECTED_MATCHED, matched, "matched count drift (partition lock, RDR-012 §C-4)\n" + report);
        assertEquals(EXPECTED_OVER, over, "over-permissive count drift (sibling-tree neighbors, §C-4)\n" + report);
        assertEquals(EXPECTED_UNDER, under, "under-permissive count drift (sibling-tree positions, §C-4)\n" + report);
    }

    /**
     * Concretely demonstrates the §C-3 single-tree↔cube mapping (not just counts it): finds an
     * over-permissive element — reachable in Luciferase ({@code encode != null}) yet rejected by t8code's
     * single root-pyramid {@code is_inside_root} — and asserts it sits where the mapping predicts
     * (outside the {@code x>=z, y>=z} simplex, or on a degenerate-apex tie-break plane). This is the
     * 6↔7 / root-tet seam: the neighbor left root-pyramid-6's simplex into a sibling tree that the cube
     * partition legitimately owns. Uses a real, validated element from a small sweep rather than a
     * hand-guessed coordinate literal.
     */
    @Test
    void seamMappingDemonstratesOverPermissiveSet() {
        Pyramid witnessSrc = null;
        int witnessFace = -1;
        HybridElement witness = null;
        int[] witnessCand = null;
        outer:
        for (var p : validPyramids(2)) {
            for (int f = 0; f < 5; f++) {
                var luc = p.faceNeighbor(f);
                if (luc == null || !encodes(luc.element())) {
                    continue; // not reachable in Luciferase
                }
                int[] cand = oracleRawPyramidNeighbor(p, f);
                if (!oracleIsInsideRoot(cand[0], cand[1], cand[2], p.level(), (byte) cand[3])) {
                    witnessSrc = p;
                    witnessFace = f;
                    witness = luc.element();
                    witnessCand = cand;
                    break outer; // over-permissive: reachable here, outside t8code root
                }
            }
        }

        assertTrue(witness != null,
                   "expected at least one over-permissive (reachable-here / outside-t8code-root) neighbor "
                   + "to exist at level<=2, demonstrating the cube-vs-single-root seam");

        int nx = witnessCand[0], ny = witnessCand[1], nz = witnessCand[2];
        byte ntype = (byte) witnessCand[3];
        boolean coordsInCube = nx >= 0 && ny >= 0 && nz >= 0 && nx < ROOT_LEN && ny < ROOT_LEN && nz < ROOT_LEN;
        assertTrue(coordsInCube, "the over-permissive neighbor must be a real cube position: "
                                 + candDesc(witnessCand, witnessSrc.level()));

        // The §C-3 prediction: outside t8code root means either outside the simplex (x<z or y<z) or on a
        // degenerate-apex tie-break plane for its type. Assert one of those holds — that is the mapping.
        boolean outsideSimplex = nx < nz || ny < nz;
        boolean apexTieBreak = (nx == nz && (ntype == 3 || ntype == 5)) || (ny == nz && (ntype == 0 || ntype == 4));
        assertTrue(outsideSimplex || apexTieBreak,
                   "over-permissive neighbor must be explained by the single-root simplex/tie-break mapping "
                   + "(§C-3): " + candDesc(witnessCand, witnessSrc.level()) + " from " + desc(witnessSrc)
                   + " f" + witnessFace + " ; reachable witness=" + desc(witness));
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

    // Pyramid-source sweep: recurse into pyramid children only. The 4 tet children per pyramid are NOT
    // swept as source elements — tet-source cross-shape is RDR-012 D3 (Luciferase-dk12), out of scope here.
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
