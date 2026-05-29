/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.Objects;

/**
 * Pyramid element of the hybrid pyramid/tetrahedron space-filling curve (RDR-010, Knapp 2026,
 * "A Morton-Type SFC for Pyramid Subdivision and Hybrid AMR"). A pyramid is the square-based
 * element that, together with a 180&deg;-rotated sibling and two tetrahedra, tiles a cube and
 * closes the documented t8code tetrahedral partition gap.
 *
 * <p>Analogous to {@link com.hellblazer.luciferase.lucien.tetree.Tet}: an element is identified by
 * its anchor coordinate (the lower-left-back corner of its surrounding cube), its refinement level,
 * and its type. Pyramids carry type {@link #TYPE_6} or {@link #TYPE_7}; the four tetrahedral
 * children of a pyramid are represented by {@code Tet} (types 0-5).
 *
 * <p>Vertex construction follows the t8code reference implementation
 * ({@code t8_dpyramid_compute_coords}, t8_default_pyramid scheme), the executable ground truth for
 * Knapp 2026 &sect;3:
 * <ul>
 *   <li><b>Type 6</b> (FIRST_TYPE): square base on the bottom face (z), apex at the
 *       (+x,+y,+z) cube corner.</li>
 *   <li><b>Type 7</b> (SECOND_TYPE): square base on the top face (z+L), apex at the anchor
 *       corner. A 180&deg; rotation of type 6.</li>
 * </ul>
 *
 * <p>The mandatory {@code minTetLevel} field (Knapp Algorithm 4.1) records the smallest level at
 * which an ancestor is a tetrahedron; it is required to keep parent/child/face-neighbor O(1) for
 * tetrahedra descending from pyramidal roots. Pure pyramids carry the sentinel
 * {@link #NO_TET_ANCESTOR} (-1).
 *
 * @author hal.hildebrand
 */
public final class Pyramid implements HybridElement {

    /** Pyramid type 6 (t8code FIRST_TYPE): base on bottom face, apex at the far cube corner. */
    public static final byte TYPE_6 = 6;
    /** Pyramid type 7 (t8code SECOND_TYPE): base on top face, apex at the anchor corner. */
    public static final byte TYPE_7 = 7;
    /** {@code minTetLevel} sentinel: no tetrahedral ancestor (pure pyramid). */
    public static final byte NO_TET_ANCESTOR = -1;
    /** Number of corners of a pyramid (4 base + 1 apex). */
    public static final int  CORNERS         = 5;

    private final int  x;
    private final int  y;
    private final int  z;
    private final byte level;
    private final byte type;
    private final byte minTetLevel;

    /**
     * Create a pure pyramid (no tetrahedral ancestor).
     */
    public Pyramid(int x, int y, int z, byte level, byte type) {
        this(x, y, z, level, type, NO_TET_ANCESTOR);
    }

    /**
     * Create a pyramid with an explicit {@code minTetLevel}.
     *
     * @param x           anchor x (lower-left-back corner, non-negative)
     * @param y           anchor y (non-negative)
     * @param z           anchor z (non-negative)
     * @param level       refinement level, 0..{@link Constants#getMaxRefinementLevel()}
     * @param type        {@link #TYPE_6} or {@link #TYPE_7}
     * @param minTetLevel smallest level at which an ancestor is a tetrahedron, or
     *                    {@link #NO_TET_ANCESTOR}
     */
    public Pyramid(int x, int y, int z, byte level, byte type, byte minTetLevel) {
        if (type != TYPE_6 && type != TYPE_7) {
            throw new IllegalArgumentException("Pyramid type must be 6 or 7, got: " + type);
        }
        if (level < 0 || level > Constants.getMaxRefinementLevel()) {
            throw new IllegalArgumentException(
            "Level must be between 0 and " + Constants.getMaxRefinementLevel() + ", got: " + level);
        }
        if (x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException(
            String.format("Negative anchor coordinates not supported: (%d,%d,%d)", x, y, z));
        }
        if (minTetLevel != NO_TET_ANCESTOR && (minTetLevel < 0 || minTetLevel > level)) {
            throw new IllegalArgumentException(
            "minTetLevel must be -1 or in [0, level], got: " + minTetLevel + " (level " + level + ")");
        }
        this.x = x;
        this.y = y;
        this.z = z;
        this.level = level;
        this.type = type;
        this.minTetLevel = minTetLevel;
    }

    /**
     * The edge length of this pyramid's surrounding cube at its level.
     *
     * @return {@code 2^(maxRefinementLevel - level)}
     */
    public int length() {
        return Constants.lengthAtLevel(level);
    }

    /**
     * The five integer vertex coordinates of this pyramid, indexed by corner 0..4 (corners 0-3 are
     * the square base, corner 4 is the apex; Knapp Fig 4.1 / t8code {@code t8_dpyramid_compute_coords}).
     *
     * @return a 5-element array of vertices
     */
    public Point3i[] coordinates() {
        var h = length();
        var c = new Point3i[CORNERS];
        if (type == TYPE_6) {
            c[0] = new Point3i(x, y, z);
            c[1] = new Point3i(x + h, y, z);
            c[2] = new Point3i(x, y + h, z);
            c[3] = new Point3i(x + h, y + h, z);
            c[4] = new Point3i(x + h, y + h, z + h);
        } else { // TYPE_7 — 180-degree rotation of type 6
            c[0] = new Point3i(x, y, z + h);
            c[1] = new Point3i(x + h, y, z + h);
            c[2] = new Point3i(x, y + h, z + h);
            c[3] = new Point3i(x + h, y + h, z + h);
            c[4] = new Point3i(x, y, z);
        }
        return c;
    }

    /**
     * The centroid of this pyramid (arithmetic mean of its five vertices). Note this is the vertex
     * centroid, not the volume centroid; pyramids are not symmetric about it (the volume centroid
     * lies at 1/4 of the height above the base). Do not use for volume-weighted operations.
     *
     * @return the vertex centroid
     */
    public Point3f centroid() {
        var verts = coordinates();
        float sx = 0, sy = 0, sz = 0;
        for (var v : verts) {
            sx += v.x;
            sy += v.y;
            sz += v.z;
        }
        return new Point3f(sx / CORNERS, sy / CORNERS, sz / CORNERS);
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public byte level() {
        return level;
    }

    public byte type() {
        return type;
    }

    public byte minTetLevel() {
        return minTetLevel;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pyramid p)) {
            return false;
        }
        return x == p.x && y == p.y && z == p.z && level == p.level && type == p.type
        && minTetLevel == p.minTetLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z, level, type, minTetLevel);
    }

    @Override
    public String toString() {
        return "Pyramid[type=" + type + ",L" + level + ",@(" + x + "," + y + "," + z + ")"
        + (minTetLevel == NO_TET_ANCESTOR ? "" : ",minTet=" + minTetLevel) + "]";
    }
}
