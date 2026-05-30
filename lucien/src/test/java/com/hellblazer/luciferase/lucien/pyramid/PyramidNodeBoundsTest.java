/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.3 Phase C: acceptance tests for
 * {@link PyramidIndex#getNodeBounds(PyramidKey)}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>The returned {@link Spatial} envelope contains all 5 {@link Pyramid#coordinates()} vertices.</li>
 *   <li>For level-0 (root), the envelope spans the full root cube.</li>
 *   <li>The return type is NOT a {@code Spatial.aabt} implementor (invariant #7 preserved).</li>
 *   <li>The envelope has the correct origin (pyramid anchor) and extent (cell size at level).</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
class PyramidNodeBoundsTest {

    private PyramidIndex<LongEntityID, String> index;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    /**
     * Invariant #7: getNodeBounds must NOT return a Spatial.aabt implementor.
     * It must return a broad Spatial (e.g. Spatial.Cube), mirroring Tetree/Octree.
     */
    @Test
    void getNodeBounds_isNotAabt() {
        var key = PyramidKey.fromLevels((byte) 1,
                                        new int[] { 0, 0 },
                                        new int[] { 0, 6 });
        var bounds = index.getNodeBounds(key);
        assertNotNull(bounds);
        assertFalse(bounds instanceof Spatial.aabt,
                    "getNodeBounds must NOT return a Spatial.aabt (invariant #7). Got: " + bounds.getClass());
    }

    /**
     * Envelope spans the full root cube at level 0.
     */
    @Test
    void getNodeBounds_rootKey_spansRootCube() {
        var root = PyramidKey.getRoot();
        var bounds = index.getNodeBounds(root);
        assertNotNull(bounds);
        // Root cube: [0, MAX_EXTENT] x [0, MAX_EXTENT] x [0, MAX_EXTENT]
        var vb = com.hellblazer.luciferase.lucien.VolumeBounds.from(bounds);
        float expected = Constants.lengthAtLevel((byte) 0);
        assertEquals(0f, vb.minX(), 1e-3f, "root minX");
        assertEquals(0f, vb.minY(), 1e-3f, "root minY");
        assertEquals(0f, vb.minZ(), 1e-3f, "root minZ");
        assertEquals(expected, vb.maxX(), 1e-3f, "root maxX");
        assertEquals(expected, vb.maxY(), 1e-3f, "root maxY");
        assertEquals(expected, vb.maxZ(), 1e-3f, "root maxZ");
    }

    /**
     * For 50 random PyramidKey values at levels 1..5, the returned envelope
     * contains all 5 Pyramid.coordinates() vertices (non-vacuous).
     */
    @Test
    void getNodeBounds_containsAllFiveVertices() {
        var rng = new Random(17L);
        for (byte level = 1; level <= 5; level++) {
            for (int trial = 0; trial < 10; trial++) {
                var key = buildRandomPyramidKey(rng, level);
                var pyramid = PyramidIndexSpatialMappingTest.pyramidFromKey(key);
                var bounds = index.getNodeBounds(key);
                assertNotNull(bounds, "bounds must not be null for level=" + level);

                var vb = com.hellblazer.luciferase.lucien.VolumeBounds.from(bounds);
                Point3i[] vertices = pyramid.coordinates();
                for (int v = 0; v < vertices.length; v++) {
                    float vx = vertices[v].x;
                    float vy = vertices[v].y;
                    float vz = vertices[v].z;
                    assertTrue(vx >= vb.minX() - 1e-3f && vx <= vb.maxX() + 1e-3f,
                               "Vertex " + v + " x=" + vx + " outside bounds [" + vb.minX() + "," + vb.maxX()
                               + "] for pyramid " + pyramid);
                    assertTrue(vy >= vb.minY() - 1e-3f && vy <= vb.maxY() + 1e-3f,
                               "Vertex " + v + " y=" + vy + " outside bounds [" + vb.minY() + "," + vb.maxY()
                               + "] for pyramid " + pyramid);
                    assertTrue(vz >= vb.minZ() - 1e-3f && vz <= vb.maxZ() + 1e-3f,
                               "Vertex " + v + " z=" + vz + " outside bounds [" + vb.minZ() + "," + vb.maxZ()
                               + "] for pyramid " + pyramid);
                }
            }
        }
    }

    /**
     * Envelope dimensions equal the cell size at the pyramid's level (surrounding cube model).
     */
    @Test
    void getNodeBounds_extentMatchesCellSize() {
        var rng = new Random(99L);
        for (byte level = 1; level <= 5; level++) {
            var key = buildRandomPyramidKey(rng, level);
            var bounds = index.getNodeBounds(key);
            var vb = com.hellblazer.luciferase.lucien.VolumeBounds.from(bounds);

            float expected = Constants.lengthAtLevel(level);
            assertEquals(expected, vb.maxX() - vb.minX(), 1e-3f,
                         "extent X mismatch at level " + level);
            assertEquals(expected, vb.maxY() - vb.minY(), 1e-3f,
                         "extent Y mismatch at level " + level);
            assertEquals(expected, vb.maxZ() - vb.minZ(), 1e-3f,
                         "extent Z mismatch at level " + level);
        }
    }

    // ===== helpers =====

    private static PyramidKey buildRandomPyramidKey(Random rng, byte level) {
        return PyramidIndexSpatialMappingTest.buildRandomPyramidKey_pkg(rng, level);
    }
}
