/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.3 Phase D: TDD tests for {@link PyramidIndex#getRayTraversalOrder}.
 *
 * <p>Verifies that nodes are streamed in ascending entry-distance order.
 *
 * @author hal.hildebrand
 */
class PyramidRayTraversalOrderTest {

    private PyramidIndex<LongEntityID, String> index;
    private SequentialLongIDGenerator idGen;

    @BeforeEach
    void setUp() {
        idGen = new SequentialLongIDGenerator();
        index = new PyramidIndex<>(idGen);
    }

    /**
     * Insert entities into two distinct pyramid nodes at different distances from the ray origin.
     * getRayTraversalOrder must yield the nearer node before the farther node.
     */
    @Test
    void traversalOrderIsByEntryDistance() {
        // Insert at two well-separated points along the X axis so they land in different level-1 nodes.
        // Use Constants.lengthAtLevel(1) to know the cell size.
        float cellSize = Constants.lengthAtLevel((byte) 1);

        // Nearer node: centre of first cell
        var nearPoint = new Point3f(cellSize * 0.5f, cellSize * 0.5f, cellSize * 0.5f);
        // Farther node: well past the first cell
        var farPoint  = new Point3f(cellSize * 4.5f, cellSize * 0.5f, cellSize * 0.5f);

        index.insert(nearPoint, (byte) 1, "near");
        index.insert(farPoint,  (byte) 1, "far");

        // Ray from far left, directed +X, aimed at midY/midZ
        var origin = new Point3f(-cellSize * 2f, cellSize * 0.5f, cellSize * 0.5f);
        var dir    = new Vector3f(1f, 0f, 0f);
        var ray    = new Ray3D(origin, dir);

        List<PyramidKey> order = index.getRayTraversalOrder(ray).toList();

        assertFalse(order.isEmpty(), "Traversal order must include at least one node");

        // The first element in traversal must be nearer than the last
        if (order.size() >= 2) {
            float d0 = index.getRayNodeIntersectionDistance(order.get(0), ray);
            float d1 = index.getRayNodeIntersectionDistance(order.get(order.size() - 1), ray);
            assertTrue(d0 <= d1, "First traversed node must be at least as close as last traversed node");
        }
    }

    /**
     * Empty index: getRayTraversalOrder should return an empty stream, not throw.
     */
    @Test
    void emptyIndex_returnsEmptyStream() {
        var ray = new Ray3D(new Point3f(0f, 0f, -10f), new Vector3f(0f, 0f, 1f));
        var result = index.getRayTraversalOrder(ray).toList();
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Empty index should produce empty traversal");
    }

    /**
     * Traversal order is monotonically non-decreasing in entry distance.
     * Inserts 3 entities in distinct nodes spaced along X, shoots a +X ray.
     */
    @Test
    void traversalOrderMonotonic_threeNodes() {
        float cellSize = Constants.lengthAtLevel((byte) 1);
        float half = cellSize * 0.5f;
        float mid  = cellSize * 0.5f;

        index.insert(new Point3f(half,              mid, mid), (byte) 1, "a");
        index.insert(new Point3f(half + cellSize,   mid, mid), (byte) 1, "b");
        index.insert(new Point3f(half + cellSize*3, mid, mid), (byte) 1, "c");

        var origin = new Point3f(-cellSize, mid, mid);
        var ray    = new Ray3D(origin, new Vector3f(1f, 0f, 0f));

        List<PyramidKey> order = index.getRayTraversalOrder(ray).toList();

        float prevDist = Float.NEGATIVE_INFINITY;
        for (var key : order) {
            float d = index.getRayNodeIntersectionDistance(key, ray);
            if (d < Float.MAX_VALUE) {
                assertTrue(d >= prevDist,
                           "Traversal must be non-decreasing in entry distance, got " + prevDist + " then " + d);
                prevDist = d;
            }
        }
    }

    /**
     * Traversal only includes nodes the ray actually intersects (no spurious nodes).
     */
    @Test
    void traversalExcludesNonIntersectedNodes() {
        float cellSize = Constants.lengthAtLevel((byte) 1);
        float mid = cellSize * 0.5f;

        // Node A is on the ray path (+X direction)
        index.insert(new Point3f(mid, mid, mid), (byte) 1, "onPath");
        // Node B is far off the ray path (at large Y)
        index.insert(new Point3f(mid, mid + cellSize * 10f, mid), (byte) 1, "offPath");

        var origin = new Point3f(-cellSize, mid, mid);
        var ray    = new Ray3D(origin, new Vector3f(1f, 0f, 0f));

        List<PyramidKey> order = index.getRayTraversalOrder(ray).toList();

        // Every returned key must intersect the ray
        for (var key : order) {
            assertTrue(index.doesRayIntersectNode(key, ray),
                       "Traversal must only include nodes that intersect the ray: " + key);
        }
    }
}
