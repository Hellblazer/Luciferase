/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Plane3D;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.3 Phase D: TDD tests for {@link PyramidIndex#getPlaneTraversalOrder}.
 *
 * <p>The traversal order is front-to-back by absolute plane-signed-distance from node centroid
 * to the plane. Nodes closer to the plane come first.
 *
 * @author hal.hildebrand
 */
class PyramidPlaneTraversalOrderTest {

    private PyramidIndex<LongEntityID, String> index;
    private SequentialLongIDGenerator idGen;

    @BeforeEach
    void setUp() {
        idGen = new SequentialLongIDGenerator();
        index = new PyramidIndex<>(idGen);
    }

    /**
     * Empty index: getPlaneTraversalOrder should return an empty stream, not throw.
     */
    @Test
    void emptyIndex_returnsEmptyStream() {
        var plane = Plane3D.parallelToXY(100f);
        var result = index.getPlaneTraversalOrder(plane).toList();
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Empty index should produce empty traversal");
    }

    /**
     * Insert entities at two different Z positions.  A horizontal plane (Z = constant) between
     * them should yield the node closer to the plane first.
     *
     * <p>Node A is at z=cellSize/2 (near the plane), node B is at z=10*cellSize (far from plane).
     * Plane at z = cellSize → |dist(A)| < |dist(B)|.
     */
    @Test
    void traversalOrderByAbsoluteDistance_nearBeforeFar() {
        float cellSize = Constants.lengthAtLevel((byte) 1);
        float mid = cellSize * 0.5f;

        // Insert near and far nodes
        index.insert(new Point3f(mid, mid, mid),                    (byte) 1, "near");
        index.insert(new Point3f(mid, mid, mid + cellSize * 10f),   (byte) 1, "far");

        // Plane just above the near node's z-centre
        var plane = Plane3D.parallelToXY(mid + cellSize * 0.1f);

        List<PyramidKey> order = index.getPlaneTraversalOrder(plane).toList();
        assertTrue(order.size() >= 2, "Should have at least 2 nodes in traversal");

        // Compute absolute plane distance for first and last
        float d0 = Math.abs(planeSignedDist(plane, order.get(0)));
        float dL = Math.abs(planeSignedDist(plane, order.get(order.size() - 1)));
        assertTrue(d0 <= dL,
                   "First traversed node must be no farther from plane than last (d0=" + d0 + " dL=" + dL + ")");
    }

    /**
     * Traversal order is monotonically non-decreasing in |plane-signed-distance|.
     */
    @Test
    void traversalOrderMonotonic_multipleNodes() {
        float cellSize = Constants.lengthAtLevel((byte) 1);
        float mid = cellSize * 0.5f;

        // Nodes at increasing Z offsets
        for (int i = 0; i < 4; i++) {
            index.insert(new Point3f(mid, mid, mid + cellSize * i), (byte) 1, "node" + i);
        }
        // Plane at z = 0 (below all nodes)
        var plane = Plane3D.parallelToXY(0.5f);

        List<PyramidKey> order = index.getPlaneTraversalOrder(plane).toList();

        float prevDist = Float.NEGATIVE_INFINITY;
        for (var key : order) {
            float d = Math.abs(planeSignedDist(plane, key));
            assertTrue(d >= prevDist,
                       "Traversal must be monotonically non-decreasing in |plane-distance|, "
                       + "got " + prevDist + " then " + d);
            prevDist = d;
        }
    }

    /**
     * Single entity: traversal order is non-empty and the single node is returned.
     */
    @Test
    void singleNode_returned() {
        float cellSize = Constants.lengthAtLevel((byte) 1);
        float mid = cellSize * 0.5f;

        index.insert(new Point3f(mid, mid, mid), (byte) 1, "only");
        var plane = Plane3D.parallelToXY(mid + 1f);

        List<PyramidKey> order = index.getPlaneTraversalOrder(plane).toList();
        assertFalse(order.isEmpty(), "Single-node index must produce a non-empty traversal");
    }

    // --- helpers ---

    /** Signed distance from the centroid of a node's surrounding cube to the plane. */
    private float planeSignedDist(Plane3D plane, PyramidKey key) {
        float px = 0, py = 0, pz = 0;
        byte level = key.getLevel();
        for (int l = 1; l <= level; l++) {
            float childSize = Constants.lengthAtLevel((byte) l);
            int cubeId = key.getCoordBitsAtLevel(l);
            if ((cubeId & 1) != 0) px += childSize;
            if ((cubeId & 2) != 0) py += childSize;
            if ((cubeId & 4) != 0) pz += childSize;
        }
        float half = Constants.lengthAtLevel(level) / 2f;
        return plane.distanceToPoint(new Point3f(px + half, py + half, pz + half));
    }
}
