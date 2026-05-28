/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityManager;

import javax.vecmath.Point3f;
import java.util.Map;

/**
 * Façade operations the {@link StackBasedTreeBuilder} consumes (RDR-008 P6 follow-up — bead {@code Luciferase-ts8}).
 *
 * <p>Pre-extraction, {@code StackBasedTreeBuilder.buildTree} accepted {@code AbstractSpatialIndex<Key,ID,Content>}
 * directly because the builder reaches into the subclass-overridden geometry hook
 * ({@link #calculateSpatialIndex}), the DSOC-aware pool-aware {@link #createNode}, the {@link EntityManager} for
 * id generation and entity registration, the underlying spatial-index map for direct put/get/computeIfAbsent
 * access, plus the {@link #maxDepth} and {@link #maxEntitiesPerNode} sizing constants. None of those are on the
 * public {@link SpatialIndex} interface, so the original god-class type leaked into every caller — including
 * {@link com.hellblazer.luciferase.lucien.entity.EntityLifecycleHost} after the P6 entity-lifecycle extraction.
 *
 * <p>This host interface is the narrow named seam (P3 per-cluster sub-interface pattern applied here): the
 * façade implements it via a private inner class so the underlying methods keep their original visibility, and
 * the builder + {@link com.hellblazer.luciferase.lucien.entity.EntityLifecycleHost#stackBuilderTarget()} both
 * speak this interface instead of the concrete {@code AbstractSpatialIndex}. Removes the
 * {@code AbstractSpatialIndex} import from {@code lucien.entity}.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public interface StackBuilderHost<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    /** Calculate the spatial key for a position at a given level (subclass-specific SFC encoding). */
    Key calculateSpatialIndex(Point3f position, byte level);

    /**
     * Acquire a new spatial node — pool-aware. Returns an occlusion-aware node when DSOC is enabled, mirroring
     * the façade's {@code createNode} behavior.
     */
    SpatialNodeImpl<ID> createNode();

    /** The entity manager — exposed for id generation, entity registration, and entity-location tracking. */
    EntityManager<Key, ID, Content> getEntityManager();

    /** The underlying spatial-index storage map (for direct put / get / computeIfAbsent access in the builder). */
    Map<Key, SpatialNodeImpl<ID>> getSpatialIndex();

    /** Maximum subdivision depth allowed by the index. */
    byte getMaxDepth();

    /** Maximum entities per node before subdivision triggers. */
    int getMaxEntitiesPerNode();
}
