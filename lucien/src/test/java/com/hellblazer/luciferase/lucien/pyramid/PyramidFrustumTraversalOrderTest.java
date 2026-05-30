/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.Plane3D;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PyramidIndex.getFrustumTraversalOrder (Phase E, bead Luciferase-ioz).
 * Validates that the returned stream is ordered by ascending centroid-to-camera distance.
 */
class PyramidFrustumTraversalOrderTest {

    private PyramidIndex<LongEntityID, String> index;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    /** Build a simple axis-aligned box frustum (shared with FrustumIntersectionTest). */
    static Frustum3D boxFrustum(float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {
        var pNX = Plane3D.fromPointAndNormal(new Point3f(maxX, 0, 0), new Vector3f(-1, 0, 0));
        var pPX = Plane3D.fromPointAndNormal(new Point3f(minX, 0, 0), new Vector3f(1, 0, 0));
        var pNY = Plane3D.fromPointAndNormal(new Point3f(0, maxY, 0), new Vector3f(0, -1, 0));
        var pPY = Plane3D.fromPointAndNormal(new Point3f(0, minY, 0), new Vector3f(0, 1, 0));
        var pNZ = Plane3D.fromPointAndNormal(new Point3f(0, 0, maxZ), new Vector3f(0, 0, -1));
        var pPZ = Plane3D.fromPointAndNormal(new Point3f(0, 0, minZ), new Vector3f(0, 0, 1));
        return new Frustum3D(pPZ, pNZ, pPX, pNX, pNY, pPY);
    }

    @Test
    void emptyIndex_returnsEmptyStream() {
        var frustum = boxFrustum(0, 1_000_000, 0, 1_000_000, 0, 1_000_000);
        var camera = new Point3f(500_000, 500_000, 500_000);
        try (Stream<PyramidKey> stream = index.getFrustumTraversalOrder(frustum, camera)) {
            assertEquals(0, stream.count(), "Empty index should return empty stream");
        }
    }

    @Test
    void streamIsMonotonicNonDecreasingInCentroidDistance() {
        // Insert several entities at different positions so the spatialIndex has populated nodes.
        int base = Constants.lengthAtLevel((byte) 3);
        for (int i = 0; i < 5; i++) {
            var pos = new Point3f(base * (i + 1), base * (i + 1), base * (i + 1));
            index.insert(pos, (byte) 3, "entity-" + i);
        }

        var frustum = boxFrustum(0, 1_000_000, 0, 1_000_000, 0, 1_000_000);
        var camera = new Point3f(0, 0, 0);

        List<PyramidKey> keys = new ArrayList<>();
        index.getFrustumTraversalOrder(frustum, camera).forEach(keys::add);

        // Must contain at least one element since we inserted entities
        assertFalse(keys.isEmpty(), "Non-empty index should produce non-empty traversal");

        // Verify non-decreasing distance order
        float prevDist = 0f;
        for (var key : keys) {
            var pyramid = pyramidFromKey(key);
            float dist = cameraDistance(pyramid, camera);
            assertTrue(dist >= prevDist - 1e-3f,
                       "Traversal order violated: dist=" + dist + " < prevDist=" + prevDist);
            prevDist = dist;
        }
    }

    @Test
    void singleNode_streamContainsIt() {
        int base = Constants.lengthAtLevel((byte) 2);
        var pos = new Point3f(base, base, base);
        index.insert(pos, (byte) 2, "single");

        var frustum = boxFrustum(0, 1_000_000, 0, 1_000_000, 0, 1_000_000);
        var camera = new Point3f(0, 0, 0);

        var keys = index.getFrustumTraversalOrder(frustum, camera).toList();
        assertFalse(keys.isEmpty(), "Should have at least 1 key after insert");
    }

    @Test
    void multipleNodes_orderIsConsistentAcrossDistinctCameraPositions() {
        // Insert multiple entities spread out
        int step = Constants.lengthAtLevel((byte) 3);
        for (int i = 0; i < 4; i++) {
            index.insert(new Point3f(step * (i + 1), 0, 0), (byte) 3, "x-" + i);
        }

        var frustum = boxFrustum(0, 1_000_000, 0, 1_000_000, 0, 1_000_000);

        // Camera at origin: near nodes should come first
        var cameraNear = new Point3f(0, 0, 0);
        var nearOrder = index.getFrustumTraversalOrder(frustum, cameraNear).toList();

        // Camera at far end: order should be reversed
        var cameraFar = new Point3f(step * 10f, 0, 0);
        var farOrder = index.getFrustumTraversalOrder(frustum, cameraFar).toList();

        // Both orders must be non-decreasing in their respective distances
        assertMonotonicNonDecreasing(nearOrder, cameraNear, "near camera");
        assertMonotonicNonDecreasing(farOrder, cameraFar, "far camera");
    }

    private void assertMonotonicNonDecreasing(List<PyramidKey> keys, Point3f camera, String label) {
        float prev = 0f;
        for (var key : keys) {
            var pyramid = pyramidFromKey(key);
            float d = cameraDistance(pyramid, camera);
            assertTrue(d >= prev - 1e-3f,
                       label + ": non-decreasing violated at key=" + key + " d=" + d + " prev=" + prev);
            prev = d;
        }
    }

    /** Approximate centroid distance: recompute from PyramidKey using PyramidIndex's estimateNodeDistance. */
    private float cameraDistance(Pyramid pyramid, Point3f camera) {
        if (pyramid == null) return 0f;
        return pyramid.centroid().distance(camera);
    }

    private Pyramid pyramidFromKey(PyramidKey key) {
        byte level = key.getLevel();
        if (level == 0) {
            return new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        }
        var type6Root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        var type7Root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7);
        int cb1 = key.getCoordBitsAtLevel(1);
        int tb1 = key.getTypeAtLevel(1);
        Pyramid current = null;
        outer:
        for (var root : new Pyramid[]{ type6Root, type7Root }) {
            int row = root.type() - Pyramid.TYPE_6;
            for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                if (TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i] == cb1
                    && TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i] == tb1) {
                    var child = root.child(i);
                    if (child instanceof Pyramid pc) {
                        current = pc;
                    }
                    break outer;
                }
            }
        }
        if (current == null || level == 1) return current;
        for (int l = 2; l <= level; l++) {
            int cb = key.getCoordBitsAtLevel(l);
            int tb = key.getTypeAtLevel(l);
            int row = current.type() - Pyramid.TYPE_6;
            Pyramid next = null;
            for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                if (TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i] == cb
                    && TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i] == tb) {
                    var child = current.child(i);
                    if (child instanceof Pyramid pc) next = pc;
                    break;
                }
            }
            if (next == null) return current;
            current = next;
        }
        return current;
    }
}
