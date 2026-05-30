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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.3 Phase C: non-vacuous acceptance test for the minTetLevel re-injection contract
 * via {@link PyramidHybridContext#reinject(PyramidKey, Tet)}.
 *
 * <h2>What this tests</h2>
 * When a Tet is a child of a pyramid, it carries a {@code minTetLevel} field that records
 * the level at which its pyramidal branch began. If this field is WRONG (e.g. the default
 * {@code NO_TET_ANCESTOR = -1}), navigation with {@link Tet#parent()} behaves differently:
 * it takes the pure-Tetree path (no cross-type pyramid boundary check) and may silently
 * return a wrong parent type instead of the correct behaviour.
 *
 * <h2>Non-vacuousness proof</h2>
 * The test constructs a Tet child of a pyramid in TWO ways:
 * <ol>
 *   <li><b>Via Pyramid.child()</b> — the tet is produced with {@code minTetLevel = childLevel}
 *       (set by {@link Pyramid#child(int)}). This is the CORRECT value.</li>
 *   <li><b>Via bare constructor</b> — the tet is produced with {@code minTetLevel = NO_TET_ANCESTOR}
 *       (simulating what would happen if re-injection were forgotten).</li>
 * </ol>
 * The test then calls {@link Tet#parent()} on both and asserts that the results DIFFER.
 * This proves the test is not vacuous: dropping re-injection produces observably different
 * (wrong) parent types.
 *
 * <h2>Re-injection via PyramidHybridContext</h2>
 * The production path uses {@link PyramidHybridContext#reinject(PyramidKey, Tet)} to restore
 * the correct {@code minTetLevel} from the pyramid ancestor's key. The test verifies that a
 * reinjected tet navigates the same way as one produced directly from Pyramid.child().
 *
 * @author hal.hildebrand
 */
class MinTetLevelReinjectionTest {

    private PyramidIndex<LongEntityID, String> index;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    /**
     * Core non-vacuousness test: a Tet obtained from Pyramid.child() (correct minTetLevel)
     * and the same Tet constructed with NO_TET_ANCESTOR (wrong) produce DIFFERENT parent types
     * when navigated upward from a level > minTetLevel.
     *
     * <p>This directly demonstrates that re-injection matters — dropping it gives wrong results.
     */
    @Test
    void withoutReinjection_parentTypeIsDifferentFromWithReinjection() {
        // Find a root pyramid that has at least one tet child
        Pyramid rootPyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Tet tetChild = null;
        int tetLocalIndex = -1;

        for (int i = 0; i < 10; i++) {
            HybridElement child = rootPyramid.child(i);
            if (child instanceof Tet t) {
                tetChild = t;
                tetLocalIndex = i;
                break;
            }
        }
        assertNotNull(tetChild, "Root type-6 pyramid must have at least one tet child");
        // tetChild.minTetLevel == childLevel (set by Pyramid.child())
        assertEquals(tetChild.l(), tetChild.minTetLevel(),
                     "Tet produced by Pyramid.child() must have minTetLevel == childLevel");

        // Build the corresponding tet WITHOUT re-injection (minTetLevel = -1 = NO_TET_ANCESTOR)
        Tet tetBare = new Tet(tetChild.x(), tetChild.y(), tetChild.z(), tetChild.l(), tetChild.type());
        // Bare tet has minTetLevel = NO_TET_ANCESTOR (-1) — the default constructor
        assertEquals(Tet.NO_TET_ANCESTOR, tetBare.minTetLevel(),
                     "Bare Tet (no minTetLevel) must have NO_TET_ANCESTOR");

        // The tet is at level 1 (child of level-0 root pyramid).
        // tetChild.minTetLevel = 1 = tetChild.l(), so parent() THROWS for tetChild
        // (its parent is a pyramid, not a tet; cross-type return is deferred).
        assertThrows(IllegalStateException.class, tetChild::parent,
                     "Tet at minTetLevel boundary: parent() must throw (parent is a pyramid)");

        // tetBare.minTetLevel = -1 (NO_TET_ANCESTOR), so parent() uses pure-Tetree path
        // (WRONG for a pyramid-rooted tet) but does NOT throw.
        assertDoesNotThrow(tetBare::parent,
                           "Bare Tet (no minTetLevel): parent() must NOT throw (takes pure-Tetree path)");

        // The pure-Tetree parent type may or may not equal the "correct" answer
        // (the correct answer is "parent is a pyramid" so there IS no tet parent),
        // but at minimum the two behave DIFFERENTLY — this is the non-vacuous proof.
        // If they behaved the same, the re-injection contract would be irrelevant.
    }

    /**
     * Re-injection via PyramidHybridContext restores the correct minTetLevel.
     *
     * <h3>Non-vacuousness</h3>
     * This test is non-vacuous: a bare Tet (minTetLevel=NO_TET_ANCESTOR) is constructed,
     * and the correct ancestor PyramidKey (level=1, the pyramid that BIRTHED the tet) is used
     * to reinject. After reinjection, minTetLevel=1 = tet.l(), so parent() THROWS (the parent
     * is a pyramid, cross-type return deferred). Without reinjection (minTetLevel=-1), parent()
     * does NOT throw — it silently returns a wrong type-0 tet via the pure-Tetree path.
     *
     * <p>The behavioral difference (throws vs. no-throw) proves the test is non-vacuous:
     * dropping the re-injection call changes the observable result.
     */
    @Test
    void reinjection_restoresCorrectMinTetLevel() {
        Pyramid rootPyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Tet tetChild = null;
        int tetLocalIndex = -1;
        for (int i = 0; i < 10; i++) {
            HybridElement child = rootPyramid.child(i);
            if (child instanceof Tet t) {
                tetChild = t;
                tetLocalIndex = i;
                break;
            }
        }
        assertNotNull(tetChild);
        // tetChild was produced by Pyramid.child() → minTetLevel = childLevel = 1
        assertEquals(1, tetChild.l(), "tet child of level-0 pyramid is at level 1");
        assertEquals(1, tetChild.minTetLevel(),
                     "Pyramid.child() sets minTetLevel = childLevel = 1");

        // Simulate retrieving a bare Tet from the spatial index (no minTetLevel stored)
        Tet tetBare = new Tet(tetChild.x(), tetChild.y(), tetChild.z(), tetChild.l(), tetChild.type());
        assertEquals(Tet.NO_TET_ANCESTOR, tetBare.minTetLevel(), "bare tet has NO_TET_ANCESTOR");

        // WITHOUT reinjection: parent() uses pure-Tetree path — no throw, but WRONG result
        assertDoesNotThrow(tetBare::parent,
                           "WITHOUT reinjection: parent() must not throw (takes wrong pure-Tetree path)");

        // Build the CORRECT level-1 ancestor PyramidKey.
        // The tet is at level 1, birthed by the level-0 TYPE_6 pyramid.
        // The ancestor key is the key at level 1 pointing to the pyramid step above the tet.
        // coordBits[1] = cubeId of the tet's local position in the parent,
        // typeBits[1]  = the parent pyramid's TYPE (TYPE_6).
        // We use the parent pyramid itself as the "ancestor" key — level 1, cubeId=0, type=6.
        // (The tet's parent pyramid is at level 0 = rootPyramid; its PyramidKey is level-0 = root.)
        // Correct ancestor: the pyramid that directly contains the tet, whose PyramidKey level = 1.
        // Build a level-1 key for the FIRST pyramid child of the root (to represent the level at
        // which the tet's pyramidal lineage started — i.e., the level-0 root pyramid whose child
        // is the tet). Since the root pyramid is at level 0 and its tet child is at level 1,
        // the ancestor key must have level = childLevel = tetChild.l() = 1, so that
        // reinject(ancestorKey, tetBare) sets minTetLevel = 1 = tetChild.l().
        //
        // PyramidHybridContext.reinject(ancestor, tet) sets minTetLevel = ancestor.getLevel().
        // We want minTetLevel = 1, so ancestorKey.getLevel() must = 1.
        // We use: coordBits[1] = cubeId of the first pyramid child of rootPyramid, typeBits[1] = TYPE_6.
        int[] coord = new int[2];
        int[] type  = new int[2];
        for (int i = 0; i < 10; i++) {
            if (rootPyramid.child(i) instanceof Pyramid) {
                coord[1] = com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[rootPyramid.type() - Pyramid.TYPE_6][i];
                type[1]  = com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[rootPyramid.type() - Pyramid.TYPE_6][i];
                break;
            }
        }
        PyramidKey ancestorKey = PyramidKey.fromLevels((byte) 1, coord, type);
        assertEquals(1, ancestorKey.getLevel(), "ancestor key must be at level 1");

        // WITH reinjection: reinject(level-1 key, tetBare) sets minTetLevel = 1
        Tet reinjected = PyramidHybridContext.reinject(ancestorKey, tetBare);
        assertEquals(1, reinjected.minTetLevel(),
                     "Reinjected tet minTetLevel must equal ancestor key level (1) — matching Pyramid.child()");

        // Now minTetLevel == l == 1: parent() THROWS because the tet's parent is a pyramid
        // (cross-type return is deferred to Luciferase-q3p). This is the CORRECT behaviour.
        assertThrows(IllegalStateException.class, reinjected::parent,
                     "WITH reinjection (minTetLevel=1=l): parent() must THROW (parent is a pyramid)");

        // Non-vacuousness: the bare tet doesn't throw, the reinjected tet does.
        // If reinjection were dropped, the test would fail on the last assertThrows.
        // This proves the reinject call materially changes behaviour.
    }

    /**
     * Deep navigation test: calculateSpatialIndex descends to level 3 (deep enough to reach a
     * tet grandchild of a pyramid). For the PYRAMID keys returned at level 3 (type 6/7 at the
     * deepest step), navigation via the index methods must not throw.
     */
    @Test
    void calculateSpatialIndex_deepPyramidPath_noThrow() {
        // Use the type-6 root pyramid centroid path — will hit pyramid children, not tet children
        Pyramid cur = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        // Navigate 3 levels down through pyramid children
        List<Pyramid> path = new ArrayList<>();
        path.add(cur);
        for (int l = 0; l < 3; l++) {
            Pyramid next = null;
            for (int i = 0; i < 10; i++) {
                if (cur.child(i) instanceof Pyramid pc) {
                    next = pc;
                    break;
                }
            }
            assertNotNull(next, "Must find pyramid child at each step");
            path.add(next);
            cur = next;
        }

        // The leaf pyramid at depth 3
        Pyramid leaf = path.get(path.size() - 1);
        Point3f centroid = leaf.centroid();

        // This must not throw:
        assertDoesNotThrow(() -> index.calculateSpatialIndex(centroid, (byte) 3),
                           "calculateSpatialIndex at depth 3 must not throw");

        PyramidKey key = index.calculateSpatialIndex(centroid, (byte) 3);
        assertNotNull(key);
        assertEquals(3, key.getLevel());

        // getNodeBounds and doesNodeIntersectVolume must not throw either
        assertDoesNotThrow(() -> index.getNodeBounds(key));
    }

    /**
     * Locate a tet child via calculateSpatialIndex. The returned key should encode the tet's
     * type at the deepest level. Verifies that the method correctly handles tet children without
     * throwing, and that the key is valid.
     */
    @Test
    void calculateSpatialIndex_tetChildOfPyramid_returnsValidKey() {
        // Find a type-6 level-0 pyramid's tet child
        Pyramid root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Tet tetChild = null;
        for (int i = 0; i < 10; i++) {
            if (root.child(i) instanceof Tet t) {
                tetChild = t;
                break;
            }
        }
        assertNotNull(tetChild, "Type-6 pyramid must have tet children");

        // Compute a point inside the tet child (use tet centroid via coordinates)
        var verts = tetChild.coordinates();
        float cx = (verts[0].x + verts[1].x + verts[2].x + verts[3].x) / 4f;
        float cy = (verts[0].y + verts[1].y + verts[2].y + verts[3].y) / 4f;
        float cz = (verts[0].z + verts[1].z + verts[2].z + verts[3].z) / 4f;

        // At level 1, this should return a valid PyramidKey
        assertDoesNotThrow(() -> index.calculateSpatialIndex(new Point3f(cx, cy, cz), (byte) 1));
        PyramidKey key = index.calculateSpatialIndex(new Point3f(cx, cy, cz), (byte) 1);
        assertNotNull(key);
        assertEquals(1, key.getLevel());
        assertTrue(key.isValid(), "Key must be valid: " + key);
    }

    /**
     * Regression test for IMPORTANT-2: when a point falls in a tet child at EXACTLY the
     * target level, calculateSpatialIndex must return a key at that target level (not l-1).
     *
     * <p>This test would FAIL against the pre-fix code that returned
     * {@code PyramidKey.fromLevels(l-1, ...)} when a tet was found at step l, discarding
     * the tet's bits and returning the parent's level.
     */
    @Test
    void calculateSpatialIndex_tetAtTargetLevel_returnsKeyAtTargetLevel() {
        // Find a tet child of the root pyramid
        Pyramid root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Tet tetChild = null;
        int tetLocalIndex = -1;
        for (int i = 0; i < 10; i++) {
            if (root.child(i) instanceof Tet t) {
                tetChild = t;
                tetLocalIndex = i;
                break;
            }
        }
        assertNotNull(tetChild, "Root type-6 pyramid must have at least one tet child");
        // The tet is at level 1 (child of level-0 root)
        assertEquals(1, tetChild.l(), "tet child is at level 1");

        // A point strictly inside the tet (centroid of its 4 vertices)
        var verts = tetChild.coordinates();
        float cx = (verts[0].x + verts[1].x + verts[2].x + verts[3].x) / 4f;
        float cy = (verts[0].y + verts[1].y + verts[2].y + verts[3].y) / 4f;
        float cz = (verts[0].z + verts[1].z + verts[2].z + verts[3].z) / 4f;

        // Ask for level=1 — the point is in a tet at exactly level 1.
        // MUST return a level-1 key, not a level-0 key.
        PyramidKey key = index.calculateSpatialIndex(new Point3f(cx, cy, cz), (byte) 1);
        assertNotNull(key, "key must not be null");
        assertEquals(1, key.getLevel(),
                     "IMPORTANT-2 regression: tet found at target level=1 must return level-1 key, "
                     + "not level-0. Got: " + key);
        assertTrue(key.isValid(), "key must be valid: " + key);
        // The deepest type bits must encode a tet type (0..5), not a pyramid type (6/7)
        byte leafType = key.getTypeAtLevel(1);
        assertTrue(leafType >= 0 && leafType <= 5,
                   "tet-child key must have tet type (0-5) at level 1, got: " + leafType);
    }
}
