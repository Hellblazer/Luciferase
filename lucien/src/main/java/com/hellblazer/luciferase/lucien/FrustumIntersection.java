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

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;

/**
 * Represents the result of a frustum culling query with an entity in the spatial index. Contains the entity
 * information, content, distance from camera, closest point on entity to camera, and frustum visibility
 * classification.
 *
 * @param <ID>      The type of EntityID
 * @param <Content> The type of content stored with the entity
 * @author hal.hildebrand
 */
public record FrustumIntersection<ID extends EntityID, Content>(ID entityId, Content content, float distanceFromCamera,
                                                                Point3f closestPoint, VisibilityType visibilityType,
                                                                EntityBounds bounds)
implements Comparable<FrustumIntersection<ID, Content>> {

    /**
     * Compare frustum intersections by distance from camera. Entities closer to the camera are considered "smaller" and
     * will appear first in sorted collections.
     *
     * @param other the other intersection to compare to
     * @return negative if this intersection is closer to camera, positive if farther, 0 if equal
     */
    @Override
    public int compareTo(FrustumIntersection<ID, Content> other) {
        return Float.compare(this.distanceFromCamera, other.distanceFromCamera);
    }

    /**
     * Check if this entity is completely inside the frustum
     *
     * @return true if the entity is completely inside the frustum
     */
    public boolean isCompletelyInside() {
        return visibilityType == VisibilityType.INSIDE;
    }

    /**
     * Check if this entity is completely outside the frustum (not visible)
     *
     * @return true if the entity is outside the frustum
     */
    public boolean isOutside() {
        return visibilityType == VisibilityType.OUTSIDE;
    }

    /**
     * Check if this entity is partially visible (intersecting frustum boundary)
     *
     * @return true if the entity intersects the frustum boundary
     */
    public boolean isPartiallyVisible() {
        return visibilityType == VisibilityType.INTERSECTING;
    }

    /**
     * Check if this entity is visible (either inside or intersecting the frustum)
     *
     * @return true if the entity is visible (inside or intersecting)
     */
    public boolean isVisible() {
        return visibilityType == VisibilityType.INSIDE || visibilityType == VisibilityType.INTERSECTING;
    }

    @Override
    public String toString() {
        return String.format("FrustumIntersection[entity=%s, distance=%.3f, visibility=%s, point=%s]", entityId,
                             distanceFromCamera, visibilityType, closestPoint);
    }

    /**
     * Enum representing the type of visibility classification for frustum culling
     */
    public enum VisibilityType {
        /**
         * Entity is completely inside the frustum (fully visible)
         */
        INSIDE,

        /**
         * Entity intersects the frustum boundary (partially visible)
         */
        INTERSECTING,

        /**
         * Entity is completely outside the frustum (not visible)
         */
        OUTSIDE
    }
}
