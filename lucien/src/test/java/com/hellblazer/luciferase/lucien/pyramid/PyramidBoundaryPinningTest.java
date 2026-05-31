/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary-pinning guard for the "shallow-only live" contract (RDR-012 D2.2, bead {@code Luciferase-v9ai};
 * §Approach step 3). Pins the invariant that {@code PyramidIndex} locate/insert NEVER emits a deep
 * pyramid-rooted tet under normal operation, so the deep cross-shape machinery (RDR-010 cjwr/2l04) stays
 * dark infrastructure and the contract cannot silently drift into production.
 *
 * <p><b>The invariant (correct form).</b> Every tet element {@code PyramidIndex} emits has
 * <ul>
 *   <li>{@code minTetLevel == NO_TET_ANCESTOR (-1)} — a pure-Tetree tet (not applicable here; PyramidIndex
 *       never emits pure-Tetree tets), OR</li>
 *   <li>{@code minTetLevel == level} — a <em>shallowest</em> pyramid-boundary tet (the only tet kind the
 *       hybrid locate produces: {@code calculateSpatialIndex} stops at the first tet child it descends into
 *       and returns the key at that tet's level).</li>
 * </ul>
 * NO emitted tet has {@code 0 <= minTetLevel < level} (a deep tet-of-tet refinement below the boundary).
 *
 * <p><b>Why the naive check is wrong (RDR-012 §Approach step 3).</b> A naive {@code minTetLevel < level}
 * predicate fires on pyramids and pure-Tetree tets (both {@code minTetLevel == -1}); the deep-tet condition
 * is specifically {@code 0 <= minTetLevel < level}. This test asserts the precise band.
 *
 * <p><b>Non-vacuity.</b> The sweep is only meaningful if it actually exercises the tet branch of locate.
 * {@link #locateNeverEmitsDeepTet} therefore also asserts that at least one emitted element IS a shallow
 * pyramid-boundary tet ({@code minTetLevel == level}); if locate degenerated to pyramids-only, the guard
 * would be vacuously green and that assertion fails.
 *
 * <p>This complements (does not replace) the RDR-010 involution tests, which remain the deep-path
 * regression guard for the dark machinery itself.
 *
 * @author hal.hildebrand
 */
class PyramidBoundaryPinningTest {

    /**
     * Drive a dense point sweep through {@code PyramidIndex} insert/locate, decode every emitted key to its
     * actual leaf element, and assert the boundary invariant on every tet. Also asserts the sweep is
     * non-vacuous (at least one shallow pyramid-boundary tet was emitted).
     */
    @Test
    void locateNeverEmitsDeepTet() {
        var index = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());

        byte level = 5;
        int extent = Constants.MAX_COORD;
        // 12 samples per axis → 1728 points spread across the whole cube, dense enough to fall into many
        // distinct pyramid/tet child regions across both root pyramids (type 6 and 7).
        int samples = 12;
        var content = "e";
        for (int i = 0; i < samples; i++) {
            for (int j = 0; j < samples; j++) {
                for (int k = 0; k < samples; k++) {
                    // Strictly interior points (avoid exact 0 / MAX_COORD faces) to keep locate well-defined.
                    float x = (i + 0.5f) / samples * extent;
                    float y = (j + 0.5f) / samples * extent;
                    float z = (k + 0.5f) / samples * extent;
                    index.insert(new Point3f(x, y, z), level, content);
                }
            }
        }

        var deepTets = new ArrayList<String>();
        int shallowTetCount = 0;
        int pyramidCount = 0;
        int decodedNodes = 0;

        var nodeKeys = index.nodes().map(n -> n.sfcIndex()).toList();
        for (var key : nodeKeys) {
            HybridElement el = PyramidIndex.elementFromKey(key);
            if (el == null) {
                continue; // non-reconstructible key (shouldn't happen for emitted keys, but be defensive)
            }
            decodedNodes++;
            if (el instanceof Tet t) {
                byte mtl = t.minTetLevel();
                byte tl = t.level();
                if (mtl == Tet.NO_TET_ANCESTOR) {
                    // PyramidIndex is not expected to emit pure-Tetree tets, but -1 is a legal shallow value.
                    continue;
                }
                if (mtl == tl) {
                    shallowTetCount++;
                } else if (mtl >= 0 && mtl < tl) {
                    deepTets.add(describe(t, key));
                }
                // mtl > tl is impossible by the Tet ctor assertion (minTetLevel in [0, l]); no branch needed.
            } else {
                pyramidCount++;
            }
        }

        assertTrue(deepTets.isEmpty(),
                   "PyramidIndex emitted deep pyramid-rooted tet(s) (0 <= minTetLevel < level) under normal "
                   + "locate/insert — the shallow-only-live contract drifted (RDR-012 §D2.2). Offenders:\n  "
                   + String.join("\n  ", deepTets));

        // Non-vacuity: the sweep must actually exercise the tet branch of locate, else the guard is hollow.
        assertTrue(shallowTetCount > 0,
                   "Vacuous guard: the point sweep emitted no shallow pyramid-boundary tet (minTetLevel==level). "
                   + "decodedNodes=" + decodedNodes + " pyramids=" + pyramidCount + " shallowTets=" + shallowTetCount
                   + " — the boundary invariant was not actually tested. Increase sample density or check locate.");
    }

    /**
     * Direct unit check of the invariant's band semantics, independent of the index sweep: a deep tet
     * ({@code 0 <= minTetLevel < level}) is correctly flagged, while a shallow boundary tet
     * ({@code minTetLevel == level}) and a pure-Tetree tet ({@code minTetLevel == -1}) are not. Guards
     * against a future refactor weakening the predicate to the naive {@code minTetLevel < level} form.
     */
    @Test
    void invariantBandIsExactNotNaive() {
        // A deep pyramid-rooted tet: minTetLevel strictly between 0 and level. Build via subdivision from a
        // shallow boundary tet so the anchor coordinates are valid (Tet ctor asserts anchor validity).
        var roots = new com.hellblazer.luciferase.lucien.pyramid.Pyramid[] {
                new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6) };
        Tet shallow = null;
        for (var root : roots) {
            for (int i = 0; i < com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                if (root.child(i) instanceof Tet t) {
                    shallow = t; // a shallowest pyramid-boundary tet: minTetLevel == level == 1
                    break;
                }
            }
        }
        assertTrue(shallow != null, "expected a tet child of the type-6 root pyramid");
        assertTrue(shallow.minTetLevel() == shallow.level(),
                   "a tet child of a pyramid is the shallowest boundary tet (minTetLevel == level)");

        // Its child is a deep tet-of-tet: level grows, minTetLevel stays at the boundary level.
        var deep = (Tet) shallow.child(0);
        assertTrue(deep.minTetLevel() >= 0 && deep.minTetLevel() < deep.level(),
                   "subdivided tet-of-tet is deep: 0 <= minTetLevel(" + deep.minTetLevel() + ") < level("
                   + deep.level() + ")");

        // The exact-band predicate flags the deep tet...
        assertTrue(isDeep(deep), "deep tet must be flagged by the exact-band predicate");
        // ...but NOT the shallow boundary tet...
        assertFalse(isDeep(shallow), "shallow boundary tet (minTetLevel==level) must not be flagged");
        // ...nor a pure-Tetree tet (minTetLevel == -1), which the naive 'minTetLevel < level' would wrongly flag.
        var pureTetree = new Tet(shallow.x(), shallow.y(), shallow.z(), shallow.level(), shallow.type());
        assertTrue(pureTetree.minTetLevel() == Tet.NO_TET_ANCESTOR, "control is pure-Tetree (minTetLevel == -1)");
        assertFalse(isDeep(pureTetree),
                    "pure-Tetree tet (minTetLevel==-1) must NOT be flagged — the naive 'minTetLevel<level' "
                    + "predicate would wrongly fire here (RDR-012 §D2.2)");
    }

    /** The exact deep-tet band: a pyramid-rooted tet refined below its boundary level. */
    private static boolean isDeep(Tet t) {
        byte mtl = t.minTetLevel();
        return mtl >= 0 && mtl < t.level();
    }

    private static String describe(Tet t, PyramidKey key) {
        return "Tet(" + t.x() + "," + t.y() + "," + t.z() + ",l" + t.level() + ",t" + t.type()
               + ",minTet=" + t.minTetLevel() + ") keyLevel=" + key.getLevel();
    }
}
