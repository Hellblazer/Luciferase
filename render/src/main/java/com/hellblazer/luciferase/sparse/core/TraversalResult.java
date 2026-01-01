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
package com.hellblazer.luciferase.sparse.core;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Base class for ray traversal results.
 *
 * <p>Contains the common fields shared by both ESVO and ESVT traversal results:
 * hit status, t-parameter, hit position, node information, and traversal statistics.
 *
 * <p><b>Thread Safety:</b> This class is mutable and not thread-safe.
 * Each thread should use its own result instance.
 *
 * <p><b>Usage Pattern:</b> Results are typically pooled and reused via {@link #reset()}.
 *
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.esvo.core.ESVOResult
 * @see com.hellblazer.luciferase.esvt.traversal.ESVTResult
 */
public class TraversalResult {

    /** Whether the ray hit a leaf node */
    public boolean hit;

    /** t-parameter at intersection (distance along ray direction) */
    public float t;

    /** Hit point X coordinate */
    public float x;

    /** Hit point Y coordinate */
    public float y;

    /** Hit point Z coordinate */
    public float z;

    /** Surface normal at hit point (may be null if no contour refinement) */
    public Vector3f normal;

    /** Index of the node containing the hit */
    public int nodeIndex;

    /** Child index within the node (0-7) */
    public int childIndex;

    /** Depth/scale level at hit */
    public int depth;

    /** Number of iterations in traversal */
    public int iterations;

    /**
     * Create an empty result (miss).
     */
    public TraversalResult() {
        reset();
    }

    /**
     * Reset to miss state for reuse.
     *
     * <p>This method should be called before reusing a result object.
     */
    public void reset() {
        this.hit = false;
        this.t = Float.MAX_VALUE;
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.normal = null;
        this.nodeIndex = -1;
        this.childIndex = -1;
        this.depth = -1;
        this.iterations = 0;
    }

    /**
     * Set hit result with basic information.
     *
     * @param t         t-parameter at intersection
     * @param x         hit point X
     * @param y         hit point Y
     * @param z         hit point Z
     * @param nodeIndex index of hit node
     * @param childIndex child index within node
     * @param depth     depth level at hit
     */
    public void setHit(float t, float x, float y, float z,
                       int nodeIndex, int childIndex, int depth) {
        this.hit = true;
        this.t = t;
        this.x = x;
        this.y = y;
        this.z = z;
        this.nodeIndex = nodeIndex;
        this.childIndex = childIndex;
        this.depth = depth;
    }

    /**
     * Check if this is a valid hit.
     *
     * @return true if hit with valid t-parameter
     */
    public boolean isHit() {
        return hit && t >= 0 && t < Float.MAX_VALUE;
    }

    /**
     * Get hit distance.
     *
     * @return t-parameter if hit, Float.MAX_VALUE otherwise
     */
    public float getHitDistance() {
        return isHit() ? t : Float.MAX_VALUE;
    }

    /**
     * Get hit point as Point3f.
     *
     * @return hit point, or origin if no hit
     */
    public Point3f getHitPoint() {
        return new Point3f(x, y, z);
    }

    /**
     * Get hit position as array.
     *
     * @return float array [x, y, z]
     */
    public float[] getHitPosition() {
        return new float[]{x, y, z};
    }

    /**
     * Copy data from another result.
     *
     * @param other source result
     */
    public void copyFrom(TraversalResult other) {
        this.hit = other.hit;
        this.t = other.t;
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        this.normal = other.normal != null ? new Vector3f(other.normal) : null;
        this.nodeIndex = other.nodeIndex;
        this.childIndex = other.childIndex;
        this.depth = other.depth;
        this.iterations = other.iterations;
    }

    /**
     * Check if this result is closer than another.
     *
     * @param other result to compare
     * @return true if this hit is closer (smaller t)
     */
    public boolean isCloserThan(TraversalResult other) {
        if (!this.hit) return false;
        if (!other.hit) return true;
        return this.t < other.t;
    }

    @Override
    public String toString() {
        if (hit) {
            return String.format("TraversalResult[HIT: t=%.6f, pos=(%.3f,%.3f,%.3f), node=%d, child=%d, depth=%d, iter=%d]",
                t, x, y, z, nodeIndex, childIndex, depth, iterations);
        } else {
            return String.format("TraversalResult[MISS: iter=%d]", iterations);
        }
    }
}
