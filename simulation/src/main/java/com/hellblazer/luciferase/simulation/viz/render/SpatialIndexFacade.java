/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.SpatialKey;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Set;

/**
 * Owns entity positions and the spatial partition map.
 *
 * <p>Coordinates are in internal integer space (0..2^21-1 cast to float),
 * the same convention as Tetree/Octree insert().
 *
 * <p>Replaces AdaptiveRegionManager's entity-tracking responsibility.
 * DirtyTracker is a separate concern that consumes keysContaining().
 */
public interface SpatialIndexFacade {

    /** Insert a new entity at the given position. */
    void put(long entityId, Point3f position);

    /** Move an existing entity. No-op if entityId unknown. */
    void move(long entityId, Point3f newPosition);

    /** Remove an entity. No-op if entityId unknown. */
    void remove(long entityId);

    /**
     * All spatial cells containing the given point at each level in [minLevel, maxLevel].
     * Used by DirtyTracker when an entity is put/moved/removed.
     */
    Set<SpatialKey<?>> keysContaining(Point3f point, int minLevel, int maxLevel);

    /**
     * Current entity positions within the given cell.
     * Called by RegionBuilder at build time (not queue time — avoids TOCTOU).
     * Returns an empty list for cells with no entities.
     */
    List<Point3f> positionsAt(SpatialKey<?> key);

    /**
     * All occupied cells at the given level whose AABB intersects the frustum.
     * Used by SubscriptionManager to compute the visible set per viewport update.
     */
    Set<SpatialKey<?>> keysVisible(Frustum3D frustum, int level);

    /** All occupied cells at the given level (for snapshot / backfill). */
    Set<SpatialKey<?>> allOccupiedKeys(int level);

    /** Total number of tracked entities. */
    int entityCount();
}
