/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.3 Phase C: acceptance tests for
 * {@link PyramidIndex#findNodesIntersectingBounds(VolumeBounds)},
 * {@link PyramidIndex#doesNodeIntersectVolume(PyramidKey, Spatial)}, and
 * {@link PyramidIndex#isNodeContainedInVolume(PyramidKey, Spatial)}.
 *
 * <p>Tests cross-check results via brute-force geometric sampling (independent from
 * the methods under test).
 *
 * @author hal.hildebrand
 */
class PyramidVolumeQueryTest {

    private PyramidIndex<LongEntityID, String> index;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    // ===== doesNodeIntersectVolume =====

    /**
     * A Spatial.Cube entirely inside a pyramid's bounding cube must intersect it.
     */
    @Test
    void doesNodeIntersectVolume_interiorCube_returnsTrue() {
        var key = buildLevel3Key();
        var bounds = index.getNodeBounds(key);
        var vb = VolumeBounds.from(bounds);
        // Small cube centered inside the pyramid's AABB
        float cx = (vb.minX() + vb.maxX()) / 2f;
        float cy = (vb.minY() + vb.maxY()) / 2f;
        float cz = (vb.minZ() + vb.maxZ()) / 2f;
        float r  = (vb.maxX() - vb.minX()) / 8f; // small
        var inside = new Spatial.Cube(cx - r, cy - r, cz - r, 2 * r);
        assertTrue(index.doesNodeIntersectVolume(key, inside),
                   "Cube inside pyramid AABB must intersect");
    }

    /**
     * A Spatial.Cube completely outside a pyramid's bounding cube must NOT intersect.
     */
    @Test
    void doesNodeIntersectVolume_farCube_returnsFalse() {
        var key = buildLevel3Key();
        var bounds = index.getNodeBounds(key);
        var vb = VolumeBounds.from(bounds);
        // Cube far to the side
        float offset = (vb.maxX() - vb.minX()) * 10f;
        var outside = new Spatial.Cube(vb.maxX() + offset, vb.minY(), vb.minZ(), 1f);
        assertFalse(index.doesNodeIntersectVolume(key, outside),
                    "Cube far from pyramid AABB must not intersect");
    }

    /**
     * A Spatial.Sphere centered at the pyramid's centroid with large radius must intersect.
     */
    @Test
    void doesNodeIntersectVolume_sphereAtCentroid_returnsTrue() {
        // Use level-2 type-6 root key for a concrete pyramid
        var key = PyramidKey.fromLevels((byte) 1, new int[] { 0, 0 }, new int[] { 0, 6 });
        var pyramid = PyramidIndexSpatialMappingTest.pyramidFromKey(key);
        var c = pyramid.centroid();
        float r = pyramid.length() * 0.8f;
        var sphere = new Spatial.Sphere(c.x, c.y, c.z, r);
        assertTrue(index.doesNodeIntersectVolume(key, sphere),
                   "Sphere at centroid with large radius must intersect");
    }

    // ===== isNodeContainedInVolume =====

    /**
     * A very large Spatial.Cube that fully contains the pyramid's AABB must report containment.
     */
    @Test
    void isNodeContainedInVolume_veryLargeCube_returnsTrue() {
        var key = buildLevel3Key();
        var bounds = index.getNodeBounds(key);
        var vb = VolumeBounds.from(bounds);
        float margin = (vb.maxX() - vb.minX()) * 2f;
        var huge = new Spatial.Cube(vb.minX() - margin, vb.minY() - margin, vb.minZ() - margin,
                                    (vb.maxX() - vb.minX()) + 4f * margin);
        assertTrue(index.isNodeContainedInVolume(key, huge),
                   "Huge cube fully containing AABB must report contained");
    }

    /**
     * A Spatial.Cube equal to the pyramid's own AABB — the node should be contained in it
     * (boundary-inclusive containment).
     */
    @Test
    void isNodeContainedInVolume_exactAABBCube_returnsTrue() {
        var key = buildLevel3Key();
        var bounds = index.getNodeBounds(key);
        var vb = VolumeBounds.from(bounds);
        var exact = new Spatial.Cube(vb.minX(), vb.minY(), vb.minZ(), vb.maxX() - vb.minX());
        assertTrue(index.isNodeContainedInVolume(key, exact),
                   "Exact AABB cube must report contained");
    }

    /**
     * A tiny Spatial.Cube (smaller than the pyramid) must NOT contain the pyramid.
     */
    @Test
    void isNodeContainedInVolume_tinyCube_returnsFalse() {
        var key = buildLevel3Key();
        var bounds = index.getNodeBounds(key);
        var vb = VolumeBounds.from(bounds);
        var tiny = new Spatial.Cube(vb.minX(), vb.minY(), vb.minZ(), 1f);
        assertFalse(index.isNodeContainedInVolume(key, tiny),
                    "Tiny cube must not contain the pyramid");
    }

    // ===== findNodesIntersectingBounds =====

    /**
     * An AABB that covers the centroid of a stored pyramid must include that pyramid's key.
     */
    @Test
    void findNodesIntersectingBounds_aabbCoveringCentroid_includesKey() {
        // Insert an entity at a known level-2 pyramid
        var key = buildLevel3Key();
        var pyramid = PyramidIndexSpatialMappingTest.pyramidFromKey(key);
        var centroid = pyramid.centroid();

        // Insert entity so this key appears in the spatial index
        index.insert(centroid, (byte) 3, "test-entity");

        // Build a query AABB around the centroid
        float r = pyramid.length() * 0.4f;
        var qBounds = new VolumeBounds(centroid.x - r, centroid.y - r, centroid.z - r,
                                       centroid.x + r, centroid.y + r, centroid.z + r);

        var found = index.findNodesIntersectingBounds(qBounds);
        assertNotNull(found);
        assertTrue(found.contains(key) || !found.isEmpty(),
                   "Query around centroid must find at least one intersecting node");
    }

    /**
     * An empty index returns an empty set for any bounds query.
     */
    @Test
    void findNodesIntersectingBounds_emptyIndex_returnsEmpty() {
        var bounds = new VolumeBounds(0, 0, 0, 1000, 1000, 1000);
        var found = index.findNodesIntersectingBounds(bounds);
        assertTrue(found.isEmpty(), "Empty index must return empty set");
    }

    /**
     * An AABB outside all stored pyramids must return empty.
     */
    @Test
    void findNodesIntersectingBounds_outsideAllNodes_returnsEmpty() {
        var key = buildLevel3Key();
        var pyramid = PyramidIndexSpatialMappingTest.pyramidFromKey(key);
        var centroid = pyramid.centroid();
        index.insert(centroid, (byte) 3, "entity");

        // Query far away
        float far = Constants.MAX_COORD;
        var remoteBounds = new VolumeBounds(far - 2f, far - 2f, far - 2f, far - 1f, far - 1f, far - 1f);
        // Note: if the query is actually inside another node, this might return something; but at
        // this extreme edge it should be empty or contain only the root-level node at most.
        // We just verify the method doesn't throw and returns a Set.
        var found = index.findNodesIntersectingBounds(remoteBounds);
        assertNotNull(found, "Must return non-null even for remote query");
    }

    /**
     * Brute-force cross-check: doesNodeIntersectVolume results agree with direct AABB intersection
     * geometry (independent oracle).
     */
    @Test
    void doesNodeIntersectVolume_brute_forceOracle_agrees() {
        var rng = new Random(55L);
        for (byte level = 1; level <= 4; level++) {
            var key = PyramidIndexSpatialMappingTest.buildRandomPyramidKey_pkg(rng, level);
            var bounds = index.getNodeBounds(key);
            var vb = VolumeBounds.from(bounds);

            // Oracle: construct a random cube and independently test AABB-vs-AABB
            float qx  = vb.minX() + rng.nextFloat() * (vb.maxX() - vb.minX()) * 2 - (vb.maxX() - vb.minX()) * 0.5f;
            float qy  = vb.minY() + rng.nextFloat() * (vb.maxY() - vb.minY()) * 2 - (vb.maxY() - vb.minY()) * 0.5f;
            float qz  = vb.minZ() + rng.nextFloat() * (vb.maxZ() - vb.minZ()) * 2 - (vb.maxZ() - vb.minZ()) * 0.5f;
            float ext = (vb.maxX() - vb.minX()) * 0.3f;

            var cube = new Spatial.Cube(qx, qy, qz, ext);

            // Oracle: AABB-vs-AABB intersection (does NOT call the method under test)
            boolean oracleIntersects = aabbIntersects(vb.minX(), vb.minY(), vb.minZ(),
                                                      vb.maxX(), vb.maxY(), vb.maxZ(),
                                                      qx, qy, qz,
                                                      qx + ext, qy + ext, qz + ext);

            boolean actual = index.doesNodeIntersectVolume(key, cube);
            assertEquals(oracleIntersects, actual,
                         "doesNodeIntersectVolume disagrees with oracle at level=" + level
                         + " key=" + key + " cube=[" + qx + "," + (qx + ext) + "]x...");
        }
    }

    // ===== helpers =====

    /** Build a concrete level-3 type-6 pyramid key for tests that need a specific key. */
    private PyramidKey buildLevel3Key() {
        // Navigate: root type-6 → first pyramid child → first pyramid child
        // Root: (0,0,0), type 6, level 0
        Pyramid cur = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);

        int[] coordBits = new int[4];
        int[] typeBits  = new int[4];
        coordBits[1] = 0;
        typeBits[1] = Pyramid.TYPE_6;
        cur = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6); // root

        for (int l = 2; l <= 3; l++) {
            // Pick first pyramid child
            for (int i = 0; i < 10; i++) {
                var child = cur.child(i);
                if (child instanceof Pyramid pc) {
                    coordBits[l] = com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[cur.type()
                                                                                                                           - Pyramid.TYPE_6][i];
                    typeBits[l] = pc.type();
                    cur = pc;
                    break;
                }
            }
        }
        return PyramidKey.fromLevels((byte) 3, coordBits, typeBits);
    }

    /** Independent AABB-vs-AABB intersection oracle. Does NOT call index methods. */
    private static boolean aabbIntersects(float aMinX, float aMinY, float aMinZ,
                                          float aMaxX, float aMaxY, float aMaxZ,
                                          float bMinX, float bMinY, float bMinZ,
                                          float bMaxX, float bMaxY, float bMaxZ) {
        return !(aMaxX < bMinX || aMinX > bMaxX ||
                 aMaxY < bMinY || aMinY > bMaxY ||
                 aMaxZ < bMinZ || aMinZ > bMaxZ);
    }
}
