/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

/**
 * Shared declaration of the cached entity-bounds lookup used by the cull and collision geometry seams
 * (Luciferase-rk8hv). Declared once here and extended by both, so a signature drift between them is a compile error
 * rather than a silent divergence.
 *
 * @param <ID> the entity identifier type
 * @author hal.hildebrand
 */
public interface CachedEntityBoundsGeometry<ID extends EntityID> {

    /** Cached world bounds of the entity, or {@code null} for point entities. */
    EntityBounds getCachedEntityBounds(ID entityId);
}
