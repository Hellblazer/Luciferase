/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.cache.KnnGeometry;
import com.hellblazer.luciferase.lucien.collision.CollisionGeometry;
import com.hellblazer.luciferase.lucien.cull.CullGeometry;
import com.hellblazer.luciferase.lucien.occlusion.FrustumGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-rk8hv: the per-cluster geometry seams each re-declared {@code getCachedEntityPosition} independently
 * (no compiler/IDE check on drift). They now extend a single {@link SpatialIndexGeometry} base, so the signature is
 * declared once and any drift is a compile error. This test pins the inheritance so the unification can't be
 * silently undone.
 *
 * @author hal.hildebrand
 */
class SpatialIndexGeometryBaseTest {

    @Test
    void entityPositionSeamsShareTheCommonBase() {
        assertTrue(SpatialIndexGeometry.class.isAssignableFrom(FrustumGeometry.class),
                   "FrustumGeometry must extend SpatialIndexGeometry (Luciferase-rk8hv)");
        assertTrue(SpatialIndexGeometry.class.isAssignableFrom(CullGeometry.class),
                   "CullGeometry must extend SpatialIndexGeometry");
        assertTrue(SpatialIndexGeometry.class.isAssignableFrom(CollisionGeometry.class),
                   "CollisionGeometry must extend SpatialIndexGeometry");
        assertTrue(SpatialIndexGeometry.class.isAssignableFrom(KnnGeometry.class),
                   "KnnGeometry must extend SpatialIndexGeometry");
    }
}
