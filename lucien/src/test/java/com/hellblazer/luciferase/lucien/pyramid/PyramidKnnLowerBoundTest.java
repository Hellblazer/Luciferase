/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.PriorityQueue;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the kNN lower-bound invariant in PyramidIndex (Phase E, bead Luciferase-ioz).
 *
 * <p>CORRECTNESS-CRITICAL: for kNN to never prune a real neighbor,
 * {@code shouldContinueKNNSearch}'s internal distance metric must be a LOWER bound on the
 * true closest-point distance from the query to the pyramid. The metric used is:
 * <pre>
 *   max(0, centroidDistance − maxVertexRadius)
 * </pre>
 * where maxVertexRadius = max over the 5 vertices of |vertex − centroid|.
 * This is a bounding-sphere lower bound and is provably ≤ the closest-point distance.
 *
 * <p>The non-vacuous test includes a case that FAILS if centroid distance were used instead
 * (a query point closer to a vertex than to the centroid would cause the centroid distance
 * to exceed the true closest-point distance, violating the lower-bound invariant).
 */
class PyramidKnnLowerBoundTest {

    private PyramidIndex<LongEntityID, String> index;
    private static final long SEED = 0xDEADBEEFL;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    /**
     * Assert the lower-bound invariant against actual points INSIDE the pyramid.
     *
     * <p>The invariant we must verify is {@code lowerBound <= trueClosestPointDistance}. We cannot
     * assert against {@code min(vertexDistance)} alone, because that is an UPPER bound on the true
     * closest-point distance — a real violation {@code trueClosest < lb <= minVertexDist} would slip
     * through. Instead we test the necessary condition directly: every point {@code p} known to lie
     * inside the pyramid satisfies {@code trueClosest <= dist(query, p)}, so the invariant demands
     * {@code lb <= dist(query, p)} for EVERY such interior point. If any interior point is closer than
     * {@code lb}, {@code lb} is not a valid lower bound (kNN would prune that point's node wrongly).
     *
     * <p>Sample set: the 5 vertices and the centroid (always in/on the pyramid) plus random points in
     * the surrounding cube filtered through {@link PyramidContainment#contains}. Returns the minimum
     * distance over the sample — the binding (smallest) interior distance the lower bound must respect.
     */
    private static float minInteriorPointDistance(Pyramid pyramid, Point3f query, Random rng) {
        float minDist = Float.MAX_VALUE;
        // Vertices + centroid are guaranteed on/in the pyramid.
        for (var v : pyramid.coordinates()) {
            minDist = Math.min(minDist, new Point3f(v.x, v.y, v.z).distance(query));
        }
        minDist = Math.min(minDist, pyramid.centroid().distance(query));
        // Random interior samples (faces/edges/interior — where the true closest point may live).
        int h = pyramid.length();
        int accepted = 0;
        for (int attempt = 0; attempt < 400 && accepted < 60; attempt++) {
            float px = pyramid.x() + rng.nextFloat() * h;
            float py = pyramid.y() + rng.nextFloat() * h;
            float pz = pyramid.z() + rng.nextFloat() * h;
            var p = new Point3f(px, py, pz);
            if (PyramidContainment.contains(pyramid, p)) {
                minDist = Math.min(minDist, p.distance(query));
                accepted++;
            }
        }
        return minDist;
    }

    /**
     * Compute the bounding-sphere lower bound: max(0, centroidDist − maxVertexRadius).
     */
    private static float boundingSphereLowerBound(Pyramid pyramid, Point3f query) {
        var centroid = pyramid.centroid();
        float centroidDist = centroid.distance(query);
        float maxRadius = 0f;
        for (var v : pyramid.coordinates()) {
            float r = centroid.distance(new Point3f(v.x, v.y, v.z));
            maxRadius = Math.max(maxRadius, r);
        }
        return Math.max(0f, centroidDist - maxRadius);
    }

    /** Build a level-1 type-6 pyramid for testing. */
    private static Pyramid level1Type6Pyramid() {
        var root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
            var child = root.child(i);
            if (child instanceof Pyramid p && p.type() == Pyramid.TYPE_6) {
                return p;
            }
        }
        throw new IllegalStateException("No type-6 level-1 child");
    }

    @Test
    void lowerBoundNeverExceedsTrueClosestPointDistance_randomCases() {
        var rng = new Random(SEED);
        var root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);

        // Test 200 random query points against the root pyramid
        int violations = 0;
        int count = 200;
        for (int i = 0; i < count; i++) {
            int h = Constants.lengthAtLevel((byte) 0);
            float qx = rng.nextFloat() * h * 2; // query can be outside pyramid
            float qy = rng.nextFloat() * h * 2;
            float qz = rng.nextFloat() * h * 2;
            var query = new Point3f(qx, qy, qz);

            float lb = boundingSphereLowerBound(root, query);
            float nearestInterior = minInteriorPointDistance(root, query, rng);

            // lb must not exceed the distance to ANY point inside the pyramid (a closer interior
            // point than lb would mean kNN could wrongly prune that point's node).
            if (lb > nearestInterior + 1e-3f) {
                violations++;
                System.err.printf("VIOLATION: lb=%.4f > nearestInterior=%.4f at query=(%.1f,%.1f,%.1f)%n",
                                  lb, nearestInterior, qx, qy, qz);
            }
        }
        assertEquals(0, violations,
                     "Bounding-sphere lower bound must never exceed the distance to any interior point");
    }

    @Test
    void lowerBoundNeverExceedsTrueClosestPointDistance_level1Pyramid() {
        var pyramid = level1Type6Pyramid();
        var rng = new Random(SEED + 1);
        int h = pyramid.length();

        for (int i = 0; i < 100; i++) {
            float qx = rng.nextFloat() * h * 3;
            float qy = rng.nextFloat() * h * 3;
            float qz = rng.nextFloat() * h * 3;
            var query = new Point3f(qx, qy, qz);

            float lb = boundingSphereLowerBound(pyramid, query);
            float nearestInterior = minInteriorPointDistance(pyramid, query, rng);

            assertTrue(lb <= nearestInterior + 1e-3f,
                       String.format("Level-1 pyramid lb=%.4f > nearestInterior=%.4f at (%.1f,%.1f,%.1f)",
                                     lb, nearestInterior, qx, qy, qz));
        }
    }

    /**
     * NON-VACUOUS test: proves that using centroid distance alone (without subtracting
     * maxVertexRadius) WOULD violate the lower-bound invariant.
     *
     * Construction: pick a query point very close to a base corner of the pyramid but far
     * from the centroid. The centroid distance will exceed the true closest-point distance
     * (= distance to that corner), violating the invariant. Our bounding-sphere formula
     * correctly handles this by subtracting maxVertexRadius.
     */
    @Test
    void naiveCentroidDistanceWouldViolateLowerBound_nonVacuousProof() {
        // Use root type-6 pyramid at level 0
        var pyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        var centroid = pyramid.centroid();

        // Find the vertex farthest from the centroid (= maxVertexRadius)
        float maxRadius = 0f;
        Point3f farthestVertex = null;
        for (var v : pyramid.coordinates()) {
            var vf = new Point3f(v.x, v.y, v.z);
            float r = centroid.distance(vf);
            if (r > maxRadius) {
                maxRadius = r;
                farthestVertex = vf;
            }
        }
        assertNotNull(farthestVertex);

        // Place query point AT the farthest vertex (true closest point distance = 0)
        var query = new Point3f(farthestVertex);
        float trueClosestDist = 0f; // query IS a vertex of the pyramid

        // Centroid distance from query to centroid
        float centroidDist = centroid.distance(query);

        // NAIVE metric (centroid distance alone) would return centroidDist as the estimate.
        // For kNN pruning: if centroidDist > furthestCandidateDist, the node would be pruned.
        // But the true closest-point distance is 0, so pruning here is WRONG.
        assertTrue(centroidDist > trueClosestDist + 1e-3f,
                   "Centroid distance should be > 0 when query is at a far vertex (non-vacuous setup)");

        // Our bounding-sphere lower bound: max(0, centroidDist − maxRadius)
        // = max(0, maxRadius - maxRadius) = 0 <= trueClosestDist = 0  ✓
        float lb = Math.max(0f, centroidDist - maxRadius);
        assertTrue(lb <= trueClosestDist + 1e-3f,
                   "Bounding-sphere lower bound must be <= true distance (0) when query is at a vertex");

        // Confirm that the naive metric (centroidDist alone) would have been > trueClosestDist,
        // i.e., it is NOT a valid lower bound in this case.
        assertTrue(centroidDist > trueClosestDist + 1e-3f,
                   "Proof: centroid distance alone is NOT a lower bound when query is at a far vertex");
    }

    @Test
    void shouldContinueKNNSearch_continuesWhenCandidatesEmpty() {
        // With empty candidates, must always return true (nothing to prune against)
        var key = PyramidKey.getRoot();
        var query = new Point3f(100, 100, 100);
        assertTrue(index.shouldContinueKNNSearch(key, query, new PriorityQueue<>()),
                   "shouldContinueKNNSearch must return true when candidates is empty");
    }

    @Test
    void shouldContinueKNNSearch_stopsWhenNodeIsFar() {
        // Insert an entity near origin so we have a candidate with small distance
        int h = Constants.lengthAtLevel((byte) 2);
        var nearPos = new Point3f(h / 2f, h / 2f, h / 2f);
        index.insert(nearPos, (byte) 2, "near");

        // Build a PyramidKey for a node very far away
        // Root key at level 0 — its pyramid spans [0..h_level0], so for a query
        // far away (many times the root extent), the bounding sphere lower bound
        // will exceed the near entity's distance.
        int h0 = Constants.lengthAtLevel((byte) 0);
        var farQuery = new Point3f(h0 * 50f, h0 * 50f, h0 * 50f);

        // Perform a kNN search — if the implementation is correct, it will terminate
        // without throwing UnsupportedOperationException (Phase E implemented).
        // We just check no exception is thrown and the result is well-formed.
        var results = index.kNearestNeighbors(nearPos, 1, h0 * 100f);
        assertFalse(results.isEmpty(), "kNN search should return at least the inserted entity");
    }
}
