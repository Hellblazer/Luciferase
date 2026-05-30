/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.Plane3D;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.lang.reflect.Field;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.3 Phase A: construction and collaborator-wiring acceptance tests for
 * {@link PyramidIndex}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>Default and parametric constructors succeed without exception.</li>
 *   <li>The seven collaborators (core, knn, culler, collisions, entityLifecycle, ghost,
 *       entityManager) are non-null after construction.</li>
 *   <li>Each of the 17 abstract geometry stubs throws {@link UnsupportedOperationException}
 *       whose message contains {@code "Luciferase-"} (non-vacuous phase-bead routing).</li>
 *   <li>Static sub-interface cast conformance: {@code PyramidIndex} can be referenced as each
 *       cluster type (compile-time check only; no runtime assertion needed).</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
class PyramidIndexConstructionTest {

    private PyramidIndex<LongEntityID, String> index;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    // ===== 1. Construction =====

    @Test
    void defaultConstructorSucceeds() {
        assertNotNull(index, "default-constructor PyramidIndex must not be null");
    }

    @Test
    void parametricConstructorSucceeds() {
        var custom = new PyramidIndex<>(new SequentialLongIDGenerator(), 5, (byte) 10);
        assertNotNull(custom);
    }

    @Test
    void fullConstructorSucceeds() {
        var full = new PyramidIndex<>(new SequentialLongIDGenerator(), 8, (byte) 12,
                                      new EntitySpanningPolicy());
        assertNotNull(full);
    }

    // ===== 2. Collaborator non-null checks (via reflection on protected fields) =====

    @Test
    void coreIsNonNull() throws Exception {
        assertNotNull(collaboratorField(AbstractSpatialIndex.class, "core"), "core");
    }

    @Test
    void knnIsNonNull() throws Exception {
        assertNotNull(collaboratorField(AbstractSpatialIndex.class, "knn"), "knn");
    }

    @Test
    void cullerIsNonNull() throws Exception {
        assertNotNull(collaboratorField(AbstractSpatialIndex.class, "culler"), "culler");
    }

    @Test
    void collisionsIsNonNull() throws Exception {
        assertNotNull(collaboratorField(AbstractSpatialIndex.class, "collisions"), "collisions");
    }

    @Test
    void entityLifecycleIsNonNull() throws Exception {
        assertNotNull(collaboratorField(AbstractSpatialIndex.class, "entityLifecycle"), "entityLifecycle");
    }

    @Test
    void ghostIsNonNull() throws Exception {
        assertNotNull(collaboratorField(AbstractSpatialIndex.class, "ghost"), "ghost");
    }

    @Test
    void entityManagerIsNonNull() throws Exception {
        assertNotNull(collaboratorField(AbstractSpatialIndex.class, "entityManager"), "entityManager");
    }

    // ===== 3. Phase-bead routing: each stub throws UnsupportedOperationException("...Luciferase-...") =====
    // NOTE: Phase-C methods (calculateSpatialIndex, getNodeBounds, getCellSizeAtLevel,
    // findNodesIntersectingBounds, doesNodeIntersectVolume, isNodeContainedInVolume) are now
    // IMPLEMENTED (bead Luciferase-2l0). Their Phase-A stub tests have been removed.
    // Phase-C acceptance tests live in PyramidIndexSpatialMappingTest, PyramidNodeBoundsTest,
    // PyramidVolumeQueryTest, and MinTetLevelReinjectionTest.
    //
    // NOTE: Phase-D methods (doesRayIntersectNode, getRayNodeIntersectionDistance,
    // getRayTraversalOrder, doesPlaneIntersectNode, getPlaneTraversalOrder) are now
    // IMPLEMENTED (bead Luciferase-jm6). Their Phase-A stub tests have been removed.
    // Phase-D acceptance tests live in PyramidRayIntersectionTest, PyramidRayTraversalOrderTest,
    // PyramidPlaneIntersectionTest, and PyramidPlaneTraversalOrderTest.

    @Test
    void calculateSpatialIndex_level0_returnsRoot() {
        var root = index.calculateSpatialIndex(new Point3f(0.1f, 0.1f, 0.1f), (byte) 0);
        assertEquals(PyramidKey.getRoot(), root);
    }

    @Test
    void getNodeBounds_rootKey_nonNull() {
        var bounds = index.getNodeBounds(PyramidKey.getRoot());
        assertNotNull(bounds);
        assertFalse(bounds instanceof Spatial.aabt, "must not be aabt (invariant #7)");
    }

    @Test
    void getCellSizeAtLevel_returnsPositive() {
        assertTrue(index.getCellSizeAtLevel((byte) 1) > 0);
    }

    @Test
    void findNodesIntersectingBounds_emptyIndex_returnsEmpty() {
        var found = index.findNodesIntersectingBounds(new VolumeBounds(0, 0, 0, 1, 1, 1));
        assertNotNull(found);
        assertTrue(found.isEmpty());
    }

    @Test
    void doesNodeIntersectVolume_rootKeyAndLargeCube_returnsTrue() {
        // root cube covers entire domain — any small cube inside should intersect
        var large = new Spatial.Cube(0, 0, 0, (float) com.hellblazer.luciferase.lucien.Constants.MAX_COORD);
        assertTrue(index.doesNodeIntersectVolume(PyramidKey.getRoot(), large));
    }

    @Test
    void isNodeContainedInVolume_rootKeyContainedInHuge_returnsTrue() {
        // A cube slightly larger than the root cube must contain it
        float edge = (float) com.hellblazer.luciferase.lucien.Constants.lengthAtLevel((byte) 0);
        var huge = new Spatial.Cube(-1f, -1f, -1f, edge + 2f);
        assertTrue(index.isNodeContainedInVolume(PyramidKey.getRoot(), huge));
    }

    @Test
    void doesFrustumIntersectNode_phaseE_implemented() {
        // Phase E: doesFrustumIntersectNode must not throw (real implementation active)
        var frustum = minimalFrustum();
        assertDoesNotThrow(() -> index.doesFrustumIntersectNode(PyramidKey.getRoot(), frustum),
                           "Phase E: doesFrustumIntersectNode must not throw");
    }

    @Test
    void getFrustumTraversalOrder_phaseE_implemented() {
        // Phase E: getFrustumTraversalOrder must not throw (real implementation active)
        var frustum = minimalFrustum();
        assertDoesNotThrow(() -> index.getFrustumTraversalOrder(frustum, new Point3f(0, 0, 0)),
                           "Phase E: getFrustumTraversalOrder must not throw");
    }

    @Test
    void estimateNodeDistance_phaseE_returnsFiniteValue() {
        // Phase E: estimateNodeDistance must return a finite non-negative value
        float dist = index.estimateNodeDistance(PyramidKey.getRoot(), new Point3f(0, 0, 0));
        assertTrue(Float.isFinite(dist), "Phase E: estimateNodeDistance must return a finite value");
        assertTrue(dist >= 0, "Phase E: estimateNodeDistance must return a non-negative value");
    }

    @Test
    void shouldContinueKNNSearch_phaseE_trueWhenEmpty() {
        // Phase E: shouldContinueKNNSearch must return true for empty candidates (nothing to prune)
        boolean cont = index.shouldContinueKNNSearch(PyramidKey.getRoot(), new Point3f(0, 0, 0),
                                                     new PriorityQueue<>());
        assertTrue(cont, "Phase E: shouldContinueKNNSearch must return true when candidates empty");
    }

    @Test
    void createDefaultSubdivisionStrategy_phaseE_isRealStrategy() {
        // Phase E: createDefaultSubdivisionStrategy must return the real PyramidSubdivisionStrategy,
        // not the Phase-A defer-all placeholder.  The real strategy must NOT unconditionally defer.
        var strategy = index.createDefaultSubdivisionStrategy();
        assertNotNull(strategy, "Phase E: createDefaultSubdivisionStrategy must return non-null");
        // A critically-overloaded context should get FORCE_SUBDIVISION, not DEFER_SUBDIVISION
        var ctx = new com.hellblazer.luciferase.lucien.SubdivisionStrategy.SubdivisionContext<PyramidKey, com.hellblazer.luciferase.lucien.entity.LongEntityID>(
            PyramidKey.getRoot(), (byte) 0, 25, 10, false, null, java.util.List.of(),
            PyramidKey.MAX_PYRAMID_LEVEL);
        var result = strategy.determineStrategy(ctx);
        assertNotNull(result, "Phase E strategy must return non-null result");
        assertNotEquals(com.hellblazer.luciferase.lucien.SubdivisionStrategy.ControlFlow.DEFER_SUBDIVISION,
                        result.decision,
                        "Phase E strategy must not unconditionally defer (was: " + result.decision + ")");
    }

    @Test
    void addNeighboringNodes_phaseE_doesNotThrow() {
        // Phase E: addNeighboringNodes must not throw (real implementation active)
        assertDoesNotThrow(() -> index.addNeighboringNodes(PyramidKey.getRoot(), new java.util.LinkedList<>(),
                                                           new java.util.HashSet<>()),
                           "Phase E: addNeighboringNodes must not throw");
    }

    // ===== 4. Sub-interface compile-time conformance (cast checks — no runtime assertion needed) =====

    /**
     * If this method compiles, PyramidIndex satisfies all required sub-interface types through its
     * parent class. The casts would throw ClassCastException at runtime if the type hierarchy were
     * broken, but in normal operation this method simply verifies assignability.
     */
    @SuppressWarnings("unused")
    private static void subInterfaceConformanceCompiles(
    PyramidIndex<LongEntityID, String> pi) {
        // Verify the index is an AbstractSpatialIndex (the cluster wiring type)
        AbstractSpatialIndex<PyramidKey, LongEntityID, String> asi = pi;
        assertNotNull(asi);
    }

    // ===== helpers =====

    private Object collaboratorField(Class<?> clazz, String fieldName) throws Exception {
        Field f = clazz.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(index);
    }

    private static void assertPhaseBead(ThrowingRunnable runnable) {
        var ex = assertThrows(UnsupportedOperationException.class, runnable::run,
                              "expected UnsupportedOperationException");
        var msg = ex.getMessage();
        assertNotNull(msg, "UnsupportedOperationException message must not be null");
        assertTrue(msg.contains("Luciferase-"),
                   "message must contain 'Luciferase-' bead ref, but was: " + msg);
    }

    private Frustum3D minimalFrustum() {
        // Construct a trivial frustum using six planes that form a small box.
        // Frustum3D(planes[6]): near, far, left, right, top, bottom.
        return new Frustum3D(
            Plane3D.fromPointAndNormal(new Point3f(0, 0, 1), new Vector3f(0, 0, 1)),    // near
            Plane3D.fromPointAndNormal(new Point3f(0, 0, 100), new Vector3f(0, 0, -1)), // far
            Plane3D.fromPointAndNormal(new Point3f(-50, 0, 0), new Vector3f(1, 0, 0)),  // left
            Plane3D.fromPointAndNormal(new Point3f(50, 0, 0), new Vector3f(-1, 0, 0)),  // right
            Plane3D.fromPointAndNormal(new Point3f(0, 50, 0), new Vector3f(0, -1, 0)),  // top
            Plane3D.fromPointAndNormal(new Point3f(0, -50, 0), new Vector3f(0, 1, 0))   // bottom
        );
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run();
    }
}
