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
package com.hellblazer.luciferase.lucien.entity;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.SpatialNodeImpl;

import javax.vecmath.Point3f;

/**
 * Façade operations the {@link EntityLifecycleManager} consumes (RDR-008 P6).
 *
 * <p>Following the P3 per-cluster sub-interface refinement (RDR-008 §Decision item 1 P3 refinement), the
 * entity-lifecycle cluster depends only on the narrow surface it actually uses — the four subclass-overridden
 * geometry/topology hooks ({@link #calculateSpatialIndex}, {@link #getCellSizeAtLevel},
 * {@link #insertWithSpanning}, {@link #validateSpatialConstraints}), the three subclass-overrideable
 * subdivision/node-tracking hooks ({@link #hasChildren}, {@link #handleNodeSubdivision},
 * {@link #onNodeRemoved}), and the empty-node cleanup helper ({@link #cleanupEmptyNode}). The spanning-policy
 * predicate (the pre-extraction {@code shouldSpanEntity}) is now private to {@link EntityLifecycleManager}; node
 * creation reaches the facade via the {@link com.hellblazer.luciferase.lucien.SpatialNodePool} factory captured
 * at facade construction time ({@code this::createNode}), not through this callback. The façade implements this
 * interface through a private inner class so the underlying methods keep their original visibility.
 *
 * <p><b>Why this surface is broader than the sibling sub-interfaces.</b> The entity-lifecycle cluster is the
 * broadest cluster (RDR-008 §Decision item 2 phase 6: "broadest shared-state footprint, most disruptive,
 * deliberately last"); it owns insertions/removals/updates plus their spanning variants and bulk batch paths, so
 * it consumes every subclass extension point the {@code AbstractSpatialIndex} façade exposes. The cluster's
 * facade-internal infrastructure dependencies are routed through a separate {@link EntityLifecycleHost} seam —
 * the "two-seam" application of the P3 per-cluster sub-interface refinement (geometry callback + host
 * infrastructure) is unique to this broadest cluster.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public interface EntityLifecycleGeometry<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    // ===== Subclass-overridden geometry/topology hooks =====

    /** Calculate the spatial key for a position at a given level (subclass-specific SFC encoding). */
    Key calculateSpatialIndex(Point3f position, byte level);

    /**
     * Cell size at a given level, in the same (world) coordinate space as entity positions and query distances
     * (subclass-specific).
     */
    float getCellSizeAtLevel(byte level);

    /**
     * Hook for subclasses to handle entity spanning. Overridden by all four concrete spatial indices
     * (Octree, Tetree, Prism, SFCArrayIndex). The default implementation falls back to a single-node insertion
     * via the entity manager's current position.
     */
    void insertWithSpanning(ID entityId, EntityBounds bounds, byte level);

    /**
     * Validate spatial constraints for a position (e.g., positive coordinates for Tetree). Overridden by Tetree;
     * default implementation does nothing.
     */
    void validateSpatialConstraints(Point3f position);

    // ===== Subclass-overrideable subdivision / node-tracking hooks =====

    /** Whether the node at this spatial key has children (subclass-specific subdivision tracking). */
    boolean hasChildren(Key spatialIndex);

    /** Hook for subclasses to handle node subdivision (overridden by Tetree). */
    void handleNodeSubdivision(Key spatialIndex, byte level, SpatialNodeImpl<ID> node);

    /** Hook for subclasses to react when a node is removed (overridden by Tetree). */
    void onNodeRemoved(Key spatialIndex);

    // ===== Façade-resident helpers =====

    /** Remove an empty node from the spatial index and release it to the node pool, if eligible. */
    void cleanupEmptyNode(Key spatialIndex, SpatialNodeImpl<ID> node);
}
