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
 * Represents the result of a plane intersection query with an entity in the spatial index. Contains the entity
 * information, content, distance from plane, closest point on entity to plane, and intersection classification.
 *
 * @param <ID>      The type of EntityID
 * @param <Content> The type of content stored with the entity
 * @author hal.hildebrand
 */
public record PlaneIntersection<ID extends EntityID, Content>(ID entityId, Content content, float distanceFromPlane,
                                                              Point3f closestPoint, IntersectionType intersectionType,
                                                              EntityBounds bounds)
implements Comparable<PlaneIntersection<ID, Content>> {

    /**
     * Check if this entity actually intersects the plane (not just on one side)
     *
     * @return true if the entity intersects or lies on the plane
     */
    public boolean actuallyIntersects() {
        return intersectionType == IntersectionType.INTERSECTING || intersectionType == IntersectionType.ON_PLANE;
    }

    /**
     * Compare plane intersections by absolute distance from plane. Entities closer to the plane are considered
     * "smaller" and will appear first in sorted collections.
     *
     * @param other the other intersection to compare to
     * @return negative if this intersection is closer to plane, positive if farther, 0 if equal
     */
    @Override
    public int compareTo(PlaneIntersection<ID, Content> other) {
        return Float.compare(Math.abs(this.distanceFromPlane), Math.abs(other.distanceFromPlane));
    }

    /**
     * Check if this entity is on the negative side of the plane
     *
     * @return true if the entity is on the negative side (or intersecting/on plane)
     */
    public boolean isOnNegativeSide() {
        return intersectionType == IntersectionType.NEGATIVE_SIDE || intersectionType == IntersectionType.INTERSECTING
        || intersectionType == IntersectionType.ON_PLANE;
    }

    /**
     * Check if this entity is on the positive side of the plane
     *
     * @return true if the entity is on the positive side (or intersecting/on plane)
     */
    public boolean isOnPositiveSide() {
        return intersectionType == IntersectionType.POSITIVE_SIDE || intersectionType == IntersectionType.INTERSECTING
        || intersectionType == IntersectionType.ON_PLANE;
    }

    @Override
    public String toString() {
        return String.format("PlaneIntersection[entity=%s, distance=%.3f, type=%s, point=%s]", entityId,
                             distanceFromPlane, intersectionType, closestPoint);
    }

    /**
     * Enum representing the type of intersection between an entity and a plane
     */
    public enum IntersectionType {
        /**
         * Entity is completely on the positive side of the plane (in direction of normal)
         */
        POSITIVE_SIDE,

        /**
         * Entity is completely on the negative side of the plane (opposite to normal)
         */
        NEGATIVE_SIDE,

        /**
         * Entity intersects the plane (spans both sides)
         */
        INTERSECTING,

        /**
         * Entity lies exactly on the plane (within tolerance)
         */
        ON_PLANE
    }
}
