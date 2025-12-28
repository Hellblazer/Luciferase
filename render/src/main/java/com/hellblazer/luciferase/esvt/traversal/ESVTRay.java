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
package com.hellblazer.luciferase.esvt.traversal;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Ray representation for ESVT traversal.
 *
 * <p>Encapsulates ray origin and direction along with size parameters for
 * level-of-detail termination and epsilon handling for numerical stability.
 *
 * <p>The coordinate space is [0,1] normalized (unit tetrahedra).
 *
 * @author hal.hildebrand
 */
public final class ESVTRay {

    /** Epsilon for avoiding division by zero with small direction components */
    private static final float EPSILON = (float) Math.pow(2, -21);

    // Ray origin coordinates
    public float originX;
    public float originY;
    public float originZ;

    // Ray direction (should be normalized)
    public float directionX;
    public float directionY;
    public float directionZ;

    // Size parameters for LOD termination
    public float originSize;      // Size at ray origin
    public float directionSize;   // Size scale along ray direction

    // Valid t-range for intersection
    public float tMin;
    public float tMax;

    /**
     * Create a ray from origin to direction.
     */
    public ESVTRay(float originX, float originY, float originZ,
                   float directionX, float directionY, float directionZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.directionX = directionX;
        this.directionY = directionY;
        this.directionZ = directionZ;
        this.originSize = 0.0f;
        this.directionSize = 0.0f;
        this.tMin = 0.0f;
        this.tMax = Float.MAX_VALUE;
    }

    /**
     * Create a ray from javax.vecmath types.
     */
    public ESVTRay(Point3f origin, Vector3f direction) {
        this(origin.x, origin.y, origin.z, direction.x, direction.y, direction.z);
    }

    /**
     * Create a default ray (origin at 0, direction along +Z).
     */
    public ESVTRay() {
        this(0, 0, 0, 0, 0, 1);
    }

    /**
     * Prepare ray for traversal by handling small direction components.
     * This prevents division by zero in intersection calculations.
     */
    public void prepareForTraversal() {
        if (Math.abs(directionX) < EPSILON) {
            directionX = Math.copySign(EPSILON, directionX);
        }
        if (Math.abs(directionY) < EPSILON) {
            directionY = Math.copySign(EPSILON, directionY);
        }
        if (Math.abs(directionZ) < EPSILON) {
            directionZ = Math.copySign(EPSILON, directionZ);
        }
    }

    /**
     * Normalize the direction vector.
     */
    public void normalizeDirection() {
        var len = (float) Math.sqrt(directionX * directionX +
                                    directionY * directionY +
                                    directionZ * directionZ);
        if (len > 0) {
            directionX /= len;
            directionY /= len;
            directionZ /= len;
        }
    }

    /**
     * Get the point at parameter t along the ray.
     */
    public Point3f pointAt(float t) {
        return new Point3f(
            originX + t * directionX,
            originY + t * directionY,
            originZ + t * directionZ
        );
    }

    /**
     * Get origin as Point3f.
     */
    public Point3f getOrigin() {
        return new Point3f(originX, originY, originZ);
    }

    /**
     * Get direction as Vector3f.
     */
    public Vector3f getDirection() {
        return new Vector3f(directionX, directionY, directionZ);
    }

    /**
     * Set origin from Point3f.
     */
    public void setOrigin(Point3f origin) {
        this.originX = origin.x;
        this.originY = origin.y;
        this.originZ = origin.z;
    }

    /**
     * Set direction from Vector3f.
     */
    public void setDirection(Vector3f direction) {
        this.directionX = direction.x;
        this.directionY = direction.y;
        this.directionZ = direction.z;
    }

    /**
     * Create a copy of this ray.
     */
    public ESVTRay copy() {
        var ray = new ESVTRay(originX, originY, originZ, directionX, directionY, directionZ);
        ray.originSize = this.originSize;
        ray.directionSize = this.directionSize;
        ray.tMin = this.tMin;
        ray.tMax = this.tMax;
        return ray;
    }

    @Override
    public String toString() {
        return String.format("ESVTRay[origin=(%.3f,%.3f,%.3f), dir=(%.3f,%.3f,%.3f), t=[%.3f,%.3f]]",
            originX, originY, originZ, directionX, directionY, directionZ, tMin, tMax);
    }
}
