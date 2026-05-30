/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.3 Phase C: round-trip oracle test for
 * {@link PyramidIndex#calculateSpatialIndex(Point3f, byte)}.
 *
 * <p>Invariant under test: {@code calculateSpatialIndex(centroid(key), level) == key}
 * for 100 sampled PyramidKey values at levels 1..5.
 *
 * <p>The oracle is PyramidKey.fromLevels round-trip (independent — constructs keys from raw
 * coord/type bits, not from the spatial-index path). The centroid is computed via
 * {@link Pyramid#centroid()} using the pyramid decoded from the key. This is an independent
 * geometric oracle: it does NOT call calculateSpatialIndex internally.
 *
 * @author hal.hildebrand
 */
class PyramidIndexSpatialMappingTest {

    private PyramidIndex<LongEntityID, String> index;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    /**
     * Round-trip: calculateSpatialIndex(centroid_of(key), level) == key for 100 keys at levels 1..5.
     *
     * <p>Only tests pyramid-type elements (type 6/7) so the centroid is guaranteed to be strictly
     * inside the pyramid (not on a tet-child boundary).
     */
    @Test
    void calculateSpatialIndexRoundTrip_levels1to5() {
        var rng = new Random(42L);
        int tested = 0;
        for (byte level = 1; level <= 5; level++) {
            for (int trial = 0; trial < 20; trial++) {
                var key = buildRandomPyramidKey(rng, level);
                var pyramid = pyramidFromKey(key);
                var centroid = pyramid.centroid();

                var recovered = index.calculateSpatialIndex(centroid, level);

                assertEquals(key, recovered,
                             "Round-trip failed at level=" + level
                             + " key=" + key + " centroid=" + centroid
                             + " pyramid=" + pyramid);
                tested++;
            }
        }
        assertEquals(100, tested, "Must test exactly 100 keys");
    }

    /**
     * Level-0 anchor: calculateSpatialIndex at level 0 returns the root key regardless of point.
     */
    @Test
    void calculateSpatialIndex_level0_returnsRoot() {
        var root = index.calculateSpatialIndex(new Point3f(100, 200, 300), (byte) 0);
        assertEquals(PyramidKey.getRoot(), root, "level-0 must return root key");
    }

    /**
     * Point at the origin with level 1: must fall in one of the two root pyramids (type 6 or 7).
     */
    @Test
    void calculateSpatialIndex_level1_originFallsInRootPyramid() {
        // Origin is the apex of the type-7 pyramid and the corner of type-6 — pick a point
        // clearly inside the type-6 pyramid (centroid of type-6 level-1 pyramid around origin).
        int h = Constants.lengthAtLevel((byte) 0); // edge of root cube
        // Type-6 level-0 root pyramid: base at z=0, apex at (h,h,h).
        // Its centroid: avg of 5 vertices = ((0+h+0+h+h)/5, (0+0+h+h+h)/5, (0+0+0+0+h)/5)
        //             = (3h/5, 3h/5, h/5)
        float cx = 3f * h / 5f;
        float cy = 3f * h / 5f;
        float cz = h / 5f;

        var key = index.calculateSpatialIndex(new Point3f(cx, cy, cz), (byte) 0);
        assertEquals(PyramidKey.getRoot(), key, "level 0 always returns root");

        var key1 = index.calculateSpatialIndex(new Point3f(cx, cy, cz), (byte) 1);
        assertNotNull(key1);
        assertEquals(1, key1.getLevel(), "level-1 key must have level=1");
        // Type at level 1 must be 6 or 7 (root pyramid type)
        byte t = key1.getTypeAtLevel(1);
        assertTrue(t == Pyramid.TYPE_6 || t == Pyramid.TYPE_7,
                   "level-1 root type must be 6 or 7, got: " + t);
    }

    // ===== helpers =====

    /**
     * Build a random valid PyramidKey at the given level whose path consists only of pyramid-type
     * elements (types 6/7 at each step). This guarantees the centroid is inside a pyramid, not a tet.
     * Package-visible so sibling test classes can reuse.
     */
    static PyramidKey buildRandomPyramidKey_pkg(Random rng, byte level) {
        return buildRandomPyramidKey(rng, level);
    }

    private static PyramidKey buildRandomPyramidKey(Random rng, byte level) {
        int[] coordBits = new int[level + 1];
        int[] typeBits  = new int[level + 1];

        // Step 1: sample a pyramid child from BOTH root pyramids (type-6 and type-7).
        // This fixes IMPORTANT-1: previously only cubeId=0 was used, missing all children
        // with non-zero cubeIds from the root. Now we enumerate all pyramid children of both
        // root pyramids and pick uniformly at random.
        var type6Root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        var type7Root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7);

        // Enumerate all pyramid children of both root pyramids at step 1.
        var step1Candidates = new java.util.ArrayList<int[]>(); // each: {cubeId, type, parentRow}
        for (Pyramid root : new Pyramid[]{type6Root, type7Root}) {
            int row = root.type() - Pyramid.TYPE_6;
            for (int i = 0; i < 10; i++) {
                if (root.child(i) instanceof Pyramid) {
                    int cubeId = com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i];
                    int type   = com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i];
                    step1Candidates.add(new int[]{cubeId, type, root.type()});
                }
            }
        }
        assertFalse(step1Candidates.isEmpty(), "Must have at least one pyramid child at step 1");

        int[] chosen1 = step1Candidates.get(rng.nextInt(step1Candidates.size()));
        coordBits[1] = chosen1[0]; // cubeId at step 1
        typeBits[1]  = chosen1[1]; // type at step 1

        if (level == 1) {
            return PyramidKey.fromLevels(level, coordBits, typeBits);
        }

        // Reconstruct the Pyramid selected at step 1 from its anchor derivation.
        // Anchor of step-1 child: cubeId offset by childSize = lengthAtLevel(1).
        int childSize1 = com.hellblazer.luciferase.lucien.Constants.lengthAtLevel((byte) 1);
        int cx1 = ((chosen1[0] & 1) != 0) ? childSize1 : 0;
        int cy1 = ((chosen1[0] & 2) != 0) ? childSize1 : 0;
        int cz1 = ((chosen1[0] & 4) != 0) ? childSize1 : 0;
        Pyramid current = new Pyramid(cx1, cy1, cz1, (byte) 1, (byte) chosen1[1]);

        for (int l = 2; l <= level; l++) {
            // Collect pyramid children indices at the current level
            var pyramidChildIndices = new java.util.ArrayList<Integer>();
            for (int i = 0; i < 10; i++) {
                if (current.child(i) instanceof Pyramid) {
                    pyramidChildIndices.add(i);
                }
            }
            assertTrue(!pyramidChildIndices.isEmpty(),
                       "Pyramid at level " + (l - 1) + " type " + current.type()
                       + " must have at least one pyramid child");

            int chosenLocalIndex = pyramidChildIndices.get(rng.nextInt(pyramidChildIndices.size()));
            var childCubeId = com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[current.type()
                                                                                                                      - Pyramid.TYPE_6][chosenLocalIndex];
            var childType = com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[current.type()
                                                                                                                     - Pyramid.TYPE_6][chosenLocalIndex];

            coordBits[l] = childCubeId;
            typeBits[l] = childType;

            current = (Pyramid) current.child(chosenLocalIndex);
        }
        return PyramidKey.fromLevels(level, coordBits, typeBits);
    }

    /**
     * Decode a PyramidKey to the Pyramid it addresses. Accumulates the anchor by applying each
     * step's cubeId offset. The type is the type bit at the deepest step.
     *
     * <p>This is an INDEPENDENT oracle — it does not call calculateSpatialIndex.
     */
    static Pyramid pyramidFromKey(PyramidKey key) {
        byte level = key.getLevel();
        assertNotEquals(0, level, "Cannot decode root key to pyramid");
        int px = 0, py = 0, pz = 0;
        for (int l = 1; l <= level; l++) {
            int childSize = Constants.lengthAtLevel((byte) l);
            int cubeId = key.getCoordBitsAtLevel(l);
            if ((cubeId & 1) != 0) px += childSize;
            if ((cubeId & 2) != 0) py += childSize;
            if ((cubeId & 4) != 0) pz += childSize;
        }
        byte type = key.getTypeAtLevel(level);
        return new Pyramid(px, py, pz, level, type);
    }
}
