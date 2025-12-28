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

/**
 * Result of ESVT ray traversal.
 *
 * <p>Contains information about the intersection including hit status,
 * t-parameter, hit point, and traversal statistics.
 *
 * @author hal.hildebrand
 */
public final class ESVTResult {

    /** Whether the ray hit a leaf node */
    public boolean hit;

    /** t-parameter at intersection (distance along ray) */
    public float t;

    /** Hit point coordinates */
    public float x;
    public float y;
    public float z;

    /** Index of the node containing the hit */
    public int nodeIndex;

    /** Child index within the node (0-7) */
    public int childIndex;

    /** Tetrahedron type at hit location (0-5) */
    public byte tetType;

    /** Entry face of the hit tetrahedron (0-3) */
    public byte entryFace;

    /** Exit face of the hit tetrahedron (0-3) */
    public byte exitFace;

    /** Scale/depth level at hit */
    public int scale;

    /** Number of iterations in traversal */
    public int iterations;

    /**
     * Create an empty result (miss).
     */
    public ESVTResult() {
        this.hit = false;
        this.t = Float.MAX_VALUE;
        this.nodeIndex = -1;
        this.childIndex = -1;
        this.tetType = -1;
        this.entryFace = -1;
        this.exitFace = -1;
        this.scale = -1;
        this.iterations = 0;
    }

    /**
     * Reset to miss state.
     */
    public void reset() {
        this.hit = false;
        this.t = Float.MAX_VALUE;
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.nodeIndex = -1;
        this.childIndex = -1;
        this.tetType = -1;
        this.entryFace = -1;
        this.exitFace = -1;
        this.scale = -1;
        this.iterations = 0;
    }

    /**
     * Set hit result.
     */
    public void setHit(float t, float x, float y, float z,
                       int nodeIndex, int childIndex, byte tetType,
                       byte entryFace, int scale) {
        this.hit = true;
        this.t = t;
        this.x = x;
        this.y = y;
        this.z = z;
        this.nodeIndex = nodeIndex;
        this.childIndex = childIndex;
        this.tetType = tetType;
        this.entryFace = entryFace;
        this.scale = scale;
    }

    /**
     * Get hit point as Point3f.
     */
    public Point3f getHitPoint() {
        return new Point3f(x, y, z);
    }

    /**
     * Check if this is a valid hit.
     */
    public boolean isHit() {
        return hit && t >= 0 && t < Float.MAX_VALUE;
    }

    @Override
    public String toString() {
        if (hit) {
            return String.format("ESVTResult[HIT t=%.4f, point=(%.3f,%.3f,%.3f), " +
                               "node=%d, child=%d, type=%d, face=%d, scale=%d, iters=%d]",
                t, x, y, z, nodeIndex, childIndex, tetType, entryFace, scale, iterations);
        } else {
            return String.format("ESVTResult[MISS, iters=%d]", iterations);
        }
    }
}
