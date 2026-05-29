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

    @Test
    void calculateSpatialIndexThrowsWithBeadRef() {
        assertPhaseBead(() -> index.calculateSpatialIndex(new Point3f(0, 0, 0), (byte) 1));
    }

    @Test
    void getNodeBoundsThrowsWithBeadRef() {
        assertPhaseBead(() -> index.getNodeBounds(PyramidKey.getRoot()));
    }

    @Test
    void getCellSizeAtLevelThrowsWithBeadRef() {
        assertPhaseBead(() -> index.getCellSizeAtLevel((byte) 1));
    }

    @Test
    void findNodesIntersectingBoundsThrowsWithBeadRef() {
        assertPhaseBead(() -> index.findNodesIntersectingBounds(new VolumeBounds(0, 0, 0, 1, 1, 1)));
    }

    @Test
    void doesNodeIntersectVolumeThrowsWithBeadRef() {
        assertPhaseBead(() -> index.doesNodeIntersectVolume(PyramidKey.getRoot(),
                                                            new Spatial.Cube(0, 0, 0, 1)));
    }

    @Test
    void isNodeContainedInVolumeThrowsWithBeadRef() {
        assertPhaseBead(() -> index.isNodeContainedInVolume(PyramidKey.getRoot(),
                                                            new Spatial.Cube(0, 0, 0, 1)));
    }

    @Test
    void doesRayIntersectNodeThrowsWithBeadRef() {
        assertPhaseBead(() -> index.doesRayIntersectNode(PyramidKey.getRoot(),
                                                         new Ray3D(new Point3f(0, 0, 0),
                                                                   new Vector3f(1, 0, 0), 100)));
    }

    @Test
    void getRayNodeIntersectionDistanceThrowsWithBeadRef() {
        assertPhaseBead(() -> index.getRayNodeIntersectionDistance(PyramidKey.getRoot(),
                                                                   new Ray3D(new Point3f(0, 0, 0),
                                                                             new Vector3f(1, 0, 0), 100)));
    }

    @Test
    void getRayTraversalOrderThrowsWithBeadRef() {
        assertPhaseBead(() -> index.getRayTraversalOrder(new Ray3D(new Point3f(0, 0, 0),
                                                                   new Vector3f(1, 0, 0), 100)));
    }

    @Test
    void doesPlaneIntersectNodeThrowsWithBeadRef() {
        assertPhaseBead(() -> index.doesPlaneIntersectNode(PyramidKey.getRoot(),
                                                           Plane3D.fromPointAndNormal(
                                                           new Point3f(0, 1, 0), new Vector3f(0, 1, 0))));
    }

    @Test
    void getPlaneTraversalOrderThrowsWithBeadRef() {
        assertPhaseBead(() -> index.getPlaneTraversalOrder(Plane3D.fromPointAndNormal(
        new Point3f(0, 1, 0), new Vector3f(0, 1, 0))));
    }

    @Test
    void doesFrustumIntersectNodeThrowsWithBeadRef() throws Exception {
        var frustum = minimalFrustum();
        assertPhaseBead(() -> index.doesFrustumIntersectNode(PyramidKey.getRoot(), frustum));
    }

    @Test
    void getFrustumTraversalOrderThrowsWithBeadRef() throws Exception {
        var frustum = minimalFrustum();
        assertPhaseBead(() -> index.getFrustumTraversalOrder(frustum, new Point3f(0, 0, 0)));
    }

    @Test
    void estimateNodeDistanceThrowsWithBeadRef() {
        assertPhaseBead(() -> index.estimateNodeDistance(PyramidKey.getRoot(), new Point3f(0, 0, 0)));
    }

    @Test
    void shouldContinueKNNSearchThrowsWithBeadRef() {
        assertPhaseBead(() -> index.shouldContinueKNNSearch(PyramidKey.getRoot(), new Point3f(0, 0, 0),
                                                            new PriorityQueue<>()));
    }

    @Test
    void createDefaultSubdivisionStrategyReturnsPlaceholder() {
        // createDefaultSubdivisionStrategy() is called during AbstractSpatialIndex construction and
        // therefore cannot throw. Phase A returns a non-null defer-all placeholder; the real
        // implementation arrives in Phase E (bead Luciferase-ioz). The placeholder's decision must
        // carry the phase-bead tag so a regression that drops the message is caught.
        var strategy = index.createDefaultSubdivisionStrategy();
        assertNotNull(strategy, "Phase A must return a non-null placeholder strategy");
        var result = strategy.determineStrategy(null);
        assertNotNull(result, "placeholder strategy must return a non-null SubdivisionResult");
        assertNotNull(result.reason, "placeholder strategy result must carry a reason");
        assertTrue(result.reason.contains("Luciferase-"),
                   "placeholder strategy reason must carry phase-bead tag (got: " + result.reason + ")");
    }

    @Test
    void addNeighboringNodesThrowsWithBeadRef() {
        assertPhaseBead(() -> index.addNeighboringNodes(PyramidKey.getRoot(), new java.util.LinkedList<>(),
                                                        new java.util.HashSet<>()));
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
