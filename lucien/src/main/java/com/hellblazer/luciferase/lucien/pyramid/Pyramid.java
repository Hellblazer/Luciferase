/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.HybridFaceNeighbor;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;

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

    /**
     * The parent of this pyramid, which is always a pyramid (possibly of the other type); RDR-010,
     * Knapp 2026 Algorithm 4.1 (pyramid branch). The parent type is selected by this pyramid's type
     * and the cube-id it occupies within the parent cube
     * ({@link TetreeConnectivity#PYRAMID_TYPE_CID_TO_PARENT_TYPE}); its anchor clears this level's
     * bit and its level is one less. Pure pyramids only, so the returned parent carries the
     * {@link #NO_TET_ANCESTOR} sentinel.
     *
     * @return the parent pyramid
     * @throws IllegalStateException if invoked on the root (level 0)
     */
    public Pyramid parent() {
        if (level == 0) {
            throw new IllegalStateException("Root pyramid (level 0) has no parent");
        }
        var h = length();
        var cubeId = (x & h) != 0 ? 1 : 0;
        cubeId |= (y & h) != 0 ? 2 : 0;
        cubeId |= (z & h) != 0 ? 4 : 0;
        var parentType = TetreeConnectivity.PYRAMID_TYPE_CID_TO_PARENT_TYPE[type - TYPE_6][cubeId];
        if (parentType < 0) {
            throw new IllegalStateException(
            "Unreachable pyramid: type " + type + " cannot occupy cube-id " + cubeId);
        }
        return new Pyramid(x & ~h, y & ~h, z & ~h, (byte) (level - 1), parentType);
    }

    /**
     * The {@code i}-th child of this pyramid (RDR-010, Knapp 2026 Algorithm 4.2; t8code
     * {@code t8_dpyramid_child}). A pyramid refines into ten children: six pyramids (types 6/7,
     * returned as {@link Pyramid} with {@link #NO_TET_ANCESTOR}) and four tetrahedra (types 0/3,
     * returned as {@link Tet} carrying {@code minTetLevel = child.level}, the level at which the
     * tetrahedral branch begins). Child type and cube-id come from
     * {@link TetreeConnectivity#PYRAMID_PARENT_TO_CHILD_TYPE} /
     * {@link TetreeConnectivity#PYRAMID_PARENT_TO_CHILD_CID}.
     *
     * @param i child local index, 0..9
     * @return the child element (pyramid or tetrahedron)
     * @throws IndexOutOfBoundsException if {@code i} is outside [0, 9]
     */
    public HybridElement child(int i) {
        if (level >= Constants.getMaxRefinementLevel()) {
            throw new IllegalStateException(
            "Cannot refine pyramid at maximum level " + Constants.getMaxRefinementLevel());
        }
        if (i < 0 || i >= TetreeConnectivity.CHILDREN_PER_PYRAMID) {
            throw new IndexOutOfBoundsException(
            "Pyramid child index must be in [0, " + (TetreeConnectivity.CHILDREN_PER_PYRAMID - 1)
            + "], got: " + i);
        }
        var row = type - TYPE_6;
        var childType = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i];
        var cubeId = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i];
        var childLevel = (byte) (level + 1);
        var ch = Constants.lengthAtLevel(childLevel);
        var cx = x + ((cubeId & 1) != 0 ? ch : 0);
        var cy = y + ((cubeId & 2) != 0 ? ch : 0);
        var cz = z + ((cubeId & 4) != 0 ? ch : 0);
        if (childType >= TYPE_6) {
            return new Pyramid(cx, cy, cz, childLevel, childType);
        }
        // Tetrahedral child: the table's t8code tet type is now directly a Luciferase Tet type
        // (RDR-010 Luciferase-4pd alignment). The tet branch starts here, so minTetLevel = childLevel.
        return new Tet(cx, cy, cz, childLevel, childType, childLevel);
    }

    /** Number of faces of a pyramid (4 triangular + 1 quadrilateral base). */
    public static final int FACES = 5;

    /**
     * The face-neighbor of this pyramid across face {@code f} (RDR-010 q3p, Knapp 2026 §4.4; t8code
     * {@code t8_dpyramid_face_neighbour}). A pyramid has five faces: f0&ndash;f3 triangular, f4 the
     * quadrilateral base. Triangular faces neighbor a tetrahedron (f0/f1 &rarr; type 3, f2/f3 &rarr;
     * type 0); the quad base neighbors the opposite-type pyramid (6&harr;7). The neighbor is a
     * same-level element; tetrahedral neighbors carry {@code minTetLevel = level} (a shallowest tet of
     * the hybrid partition), pyramid neighbors carry {@link #NO_TET_ANCESTOR}.
     *
     * @param f face index, 0..4
     * @return the face neighbor wrapped with its reciprocal face index, or {@code null} if the
     *         neighbor would lie outside the domain
     * @throws IndexOutOfBoundsException if {@code f} is outside [0, 4]
     */
    public HybridFaceNeighbor faceNeighbor(int f) {
        if (f < 0 || f >= FACES) {
            throw new IndexOutOfBoundsException("Pyramid face must be in [0, 4], got: " + f);
        }
        var len = length();
        var nx = x;
        var ny = y;
        var nz = z;
        byte neighborType;
        if (f == 0 || f == 1) {
            neighborType = 0x3; // tetrahedron type 3
        } else if (f == 2 || f == 3) {
            neighborType = 0; // tetrahedron type 0
        } else { // f == 4, quad base
            neighborType = type == TYPE_6 ? TYPE_7 : TYPE_6;
        }
        if (f == 1) {
            nx += type == TYPE_6 ? len : 0;
            ny += type == TYPE_6 ? 0 : -len;
        } else if (f == 3) {
            nx += type == TYPE_6 ? 0 : -len;
            ny += type == TYPE_6 ? len : 0;
        } else if (f == 4) {
            nz += type == TYPE_6 ? -len : len;
        }
        if (nx < 0 || ny < 0 || nz < 0 || nx > Constants.MAX_COORD || ny > Constants.MAX_COORD
        || nz > Constants.MAX_COORD) {
            return null; // neighbor lies outside the domain
        }
        var nface = TetreeConnectivity.PYRAMID_TYPE_FACE_TO_NFACE[type - TYPE_6][f];
        // neighborType for triangular faces is a t8code tet type == Luciferase Tet type (Luciferase-4pd).
        HybridElement neighbor = neighborType >= TYPE_6
                                 ? new Pyramid(nx, ny, nz, level, neighborType)
                                 : new Tet(nx, ny, nz, level, neighborType, level);
        return new HybridFaceNeighbor(nface, neighbor);
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
        // minTetLevel is contextual tree metadata, excluded from geometric identity (mirrors Tet).
        return x == p.x && y == p.y && z == p.z && level == p.level && type == p.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z, level, type);
    }

    @Override
    public String toString() {
        return "Pyramid[type=" + type + ",L" + level + ",@(" + x + "," + y + "," + z + ")"
        + (minTetLevel == NO_TET_ANCESTOR ? "" : ",minTet=" + minTetLevel) + "]";
    }
}
