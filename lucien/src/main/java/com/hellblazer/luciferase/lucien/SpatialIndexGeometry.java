/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;

/**
 * Common base of the per-cluster spatial-index geometry seams (RDR-008 façade operations). The cached
 * entity-position lookup is needed by every geometry consumer that resolves entities to world coordinates
 * (frustum/cull, collision, k-NN), so it is declared ONCE here and each sub-interface extends this base
 * (Luciferase-rk8hv). Previously each sub-interface re-declared the signature independently, so a drift between
 * them (or against the façade's implementation) was caught by neither the compiler nor the IDE — exactly the
 * asymmetry that bit the RDR-008 extraction. Unifying the declaration makes any drift a compile error.
 *
 * @param <ID> the entity identifier type
 * @author hal.hildebrand
 */
public interface SpatialIndexGeometry<ID extends EntityID> {

    /** Cached world position of the entity, or {@code null} if unknown. */
    Point3f getCachedEntityPosition(ID entityId);
}
