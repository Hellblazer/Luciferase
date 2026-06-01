/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;

import javax.vecmath.Point3f;
import java.util.Objects;

/**
 * A read-only replica of an entity from another tree's ghost zone (Luciferase-v9ro).
 *
 * <p>Extracted to a top-level type from the former {@code GhostZoneManager.GhostEntity} nested class (and the
 * identical copy carried by {@link GhostBoundaryDetector}) when the dead {@code GhostZoneManager} outer class
 * was retired. Identity is by {@code (entityId, sourceTreeId)} — the same entity replicated from two different
 * source trees is two distinct halos.
 *
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 *
 * @author Hal Hildebrand
 */
public final class GhostEntityHalo<ID extends EntityID, Content> {
    private final ID           entityId;
    private final Content      content;
    private final Point3f      position;
    private final EntityBounds bounds;
    private final String       sourceTreeId;
    private final long         timestamp;

    /**
     * Create a halo stamped with the current time from {@link Clock#system()} (Luciferase-55pi: replaces a
     * direct {@code System.currentTimeMillis()} call, honouring the no-raw-wall-clock contract). For
     * deterministic tests, use the {@link #GhostEntityHalo(EntityID, Object, Point3f, EntityBounds, String,
     * long) explicit-timestamp constructor}.
     */
    public GhostEntityHalo(ID entityId, Content content, Point3f position, EntityBounds bounds,
                           String sourceTreeId) {
        this(entityId, content, position, bounds, sourceTreeId, Clock.system().currentTimeMillis());
    }

    /**
     * Create a halo with an explicit observation timestamp (Luciferase-55pi). This is the injectable seam:
     * callers with a {@link Clock} pass {@code clock.currentTimeMillis()}, and tests pass a fixed value, so
     * staleness-detection logic is deterministic.
     *
     * @param timestamp the observation time in milliseconds since epoch
     */
    public GhostEntityHalo(ID entityId, Content content, Point3f position, EntityBounds bounds,
                           String sourceTreeId, long timestamp) {
        this.entityId = Objects.requireNonNull(entityId, "Entity ID cannot be null");
        this.content = content;
        this.position = new Point3f(Objects.requireNonNull(position, "Position cannot be null"));
        this.bounds = bounds;
        this.sourceTreeId = Objects.requireNonNull(sourceTreeId, "Source tree ID cannot be null");
        this.timestamp = timestamp;
    }

    public ID getEntityId() { return entityId; }
    public Content getContent() { return content; }
    public Point3f getPosition() { return new Point3f(position); }
    public EntityBounds getBounds() { return bounds; }
    public String getSourceTreeId() { return sourceTreeId; }
    public long getTimestamp() { return timestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        var that = (GhostEntityHalo<?, ?>) o;
        return entityId.equals(that.entityId) && sourceTreeId.equals(that.sourceTreeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityId, sourceTreeId);
    }
}
