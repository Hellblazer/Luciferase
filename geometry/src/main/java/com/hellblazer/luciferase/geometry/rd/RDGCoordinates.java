/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.geometry.rd;

import javax.vecmath.Point3d;
import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
import javax.vecmath.Vector3f;

/**
 * UI-free rhombic-dodecahedron / FCC (RDGCS) coordinate math kernel.
 * <p>
 * This is the JavaFX-free extraction of the coordinate math previously only available through
 * {@code portal.Tetrahedral}, whose {@code Tetrahedral → RDGCS → Grid} inheritance chain drags the
 * entire JavaFX toolkit (the {@code Grid} base imports {@code javafx.scene.*}). Headless consumers
 * — the distributed simulation, RDR-003's FCC spatial-index work — need only this math, not the
 * scene-graph rendering layer.
 * <p>
 * Deliberately a <b>standalone</b> class with NO inheritance from {@code Grid}/{@code RDGCS} and no
 * {@code javafx.*} reference: reaching this math by subclassing {@code RDGCS} would silently drag
 * the JavaFX chain back in (RDR-006 §Approach). All methods are pure functions of their arguments.
 * <p>
 * Cartesian outputs use {@link Point3d} (double precision) rather than the float-typed inputs to
 * avoid a precision regression on position math (RDR-006 implementation decision); the float
 * {@code toRDG} input matches the existing call sites.
 *
 * @author hal.hildebrand
 */
public final class RDGCoordinates {

    /** {@code 1/√2} — the RDGCS basis scale. */
    public static final double DIVIDE_ROOT_2 = 1.0 / Math.sqrt(2);

    private RDGCoordinates() {
        // static math kernel — not instantiable
    }

    /**
     * Answer the Euclidean length of the tetrahedral vector.
     *
     * @param rdg vector in Tetrahedral basis
     * @return the length of the vector
     */
    public static float euclideanNorm(Vector3f rdg) {
        return (float) Math.sqrt(rdg.x * (rdg.x + rdg.y + rdg.z) + rdg.y * (rdg.y + rdg.z) + rdg.z * rdg.z);
    }

    /**
     * Answer the manhattan (L1) distance of the tetrahedral vector.
     *
     * @param rdg vector in Tetrahedral basis
     * @return the manhattan distance
     */
    public static float l1(Vector3f rdg) {
        return Math.abs(rdg.x) + Math.abs(rdg.y) + Math.abs(rdg.z);
    }

    /**
     * Tetrahedral-basis cross product.
     *
     * @param u left vector
     * @param v right vector
     * @return the cross product in the Tetrahedral basis
     */
    public static Point3f cross(Tuple3f u, Tuple3f v) {
        return new Point3f(
        (float) ((-u.x * (v.y - v.z) + u.y * (3 * v.z + v.x) - u.z * (v.x + 3 * v.y)) * (DIVIDE_ROOT_2 / 2)),
        (float) ((-u.x * (v.y + 3 * v.z) - u.y * (v.z - v.x) + u.z * (3 * v.x + v.y)) * (DIVIDE_ROOT_2 / 2)),
        (float) ((u.x * (3 * v.y + v.z) - u.y * (v.z + 3 * v.x) - u.z * (v.x - v.y)) * (DIVIDE_ROOT_2 / 2)));
    }

    /**
     * Tetrahedral-basis inner product derived from the metric tensor {@code G_ii = 1, G_ij = 1/2}
     * (i ≠ j). The previous implementation had multiple incorrect coefficients
     * ({@code u.y*(v.x+v.x)} instead of {@code u.y*v.y + (...)/2}, trailing {@code u.z*v.x} instead
     * of {@code u.z*v.z}) — Luciferase-7jk.
     *
     * @param u left vector in Tetrahedral basis
     * @param v right vector in Tetrahedral basis
     * @return {@code Σ_ij G_ij · u_i · v_j}
     */
    public static float dot(Vector3f u, Vector3f v) {
        return u.x * v.x + u.y * v.y + u.z * v.z
             + (u.x * v.y + u.y * v.x + u.x * v.z + u.z * v.x + u.y * v.z + u.z * v.y) / 2f;
    }

    /**
     * Rotate {@code vec} about {@code axis} by {@code theta} radians (counter-clockwise).
     *
     * @param vec   vector to rotate
     * @param axis  rotation axis
     * @param theta angle in radians
     * @return the rotated vector
     */
    public static Vector3f rotateVectorCC(Vector3f vec, Vector3f axis, double theta) {
        float x = vec.getX(), y = vec.getY(), z = vec.getZ();
        float u = axis.getX(), v = axis.getY(), w = axis.getZ();
        float C = u * x + v * y + w * z;
        float xPrime = (float) (u * C * (1d - Math.cos(theta)) + x * Math.cos(theta) + (-w * y + v * z) * Math.sin(theta));
        float yPrime = (float) (v * C * (1d - Math.cos(theta)) + y * Math.cos(theta) + (w * x - u * z) * Math.sin(theta));
        float zPrime = (float) (w * C * (1d - Math.cos(theta)) + z * Math.cos(theta) + (-v * x + u * y) * Math.sin(theta));
        return new Vector3f(xPrime, yPrime, zPrime);
    }

    /**
     * Convert integer RDGCS coordinates to Cartesian.
     *
     * @param rdg RDGCS coordinates
     * @return Cartesian position (double precision)
     */
    public static Point3d toCartesian(Tuple3i rdg) {
        return new Point3d((rdg.y + rdg.z) * DIVIDE_ROOT_2, (rdg.z + rdg.x) * DIVIDE_ROOT_2,
                           (rdg.x + rdg.y) * DIVIDE_ROOT_2);
    }

    /**
     * Inverse of {@link #toCartesian(Tuple3i)} via the Tetrahedral basis change.
     * <p>
     * Uses {@link Math#round} (not C-style {@code (int)} truncation) to invert the {@code 1/√2}
     * basis scale faithfully. The earlier truncation implementation broke the
     * {@code toRDG(toCartesian(p)) == p} round-trip for unit-magnitude integer points: for example,
     * {@code toCartesian((1, 0, 0)) = (0, 1/√2, 1/√2)} and the inverse computation produced floats
     * in the {@code [0.99999..., 1.00000...]} neighbourhood that {@code (int)} cast to {@code 0}
     * (Luciferase-6oa audit finding).
     *
     * @param cartesian Cartesian position
     * @return RDGCS coordinates
     */
    public static Point3i toRDG(Tuple3f cartesian) {
        return new Point3i((int) Math.round((-cartesian.x + cartesian.y + cartesian.z) * DIVIDE_ROOT_2),
                           (int) Math.round(( cartesian.x - cartesian.y + cartesian.z) * DIVIDE_ROOT_2),
                           (int) Math.round(( cartesian.x + cartesian.y - cartesian.z) * DIVIDE_ROOT_2));
    }

    /**
     * Return the 6 face-connected + 6 second-shell neighbors (12 total) of {@code cell}.
     *
     * @param cell the cell whose face-connected neighbors to return
     * @return 12 neighbor offsets
     */
    public static Point3i[] faceConnectedNeighbors(Point3i cell) {
        var x = cell.x;
        var y = cell.y;
        var z = cell.z;
        var neighbors = new Point3i[12];
        neighbors[0] = new Point3i(x + 1, y, z);
        neighbors[1] = new Point3i(x - 1, y, z);
        neighbors[2] = new Point3i(x, y + 1, z);
        neighbors[3] = new Point3i(x, y - 1, z);
        neighbors[4] = new Point3i(x, y, z + 1);
        neighbors[5] = new Point3i(x, y, z - 1);

        neighbors[6] = new Point3i(x, y + 1, z - 1);
        neighbors[7] = new Point3i(x, y - 1, z + 1);
        neighbors[8] = new Point3i(x - 1, y, z + 1);
        neighbors[9] = new Point3i(x + 1, y, z - 1);
        neighbors[10] = new Point3i(x + 1, y - 1, z);
        neighbors[11] = new Point3i(x - 1, y + 1, z);
        return neighbors;
    }

    /**
     * Return the 6 FCC second-shell vertex-connected neighbors of {@code cell}.
     * <p>
     * In Tetrahedral coordinates these are the 6 offsets {@code (±1, ±1, ∓1)} with mixed signs whose
     * Cartesian images are the 6 axis-aligned points at distance {@code sqrt(2)} from origin (the
     * FCC second shell). The previous implementation returned a mix of face-neighbor (distance 1)
     * and third-shell (distance sqrt(11)) offsets — Luciferase-xnf.
     *
     * @param cell the cell whose vertex-connected neighbors to return
     * @return 6 distinct offsets all at Cartesian distance {@code sqrt(2)} from {@code cell}
     */
    public static Point3i[] vertexConnectedNeighbors(Point3i cell) {
        var x = cell.x;
        var y = cell.y;
        var z = cell.z;
        var neighbors = new Point3i[6];
        neighbors[0] = new Point3i(x + 1, y + 1, z - 1);
        neighbors[1] = new Point3i(x - 1, y - 1, z + 1);
        neighbors[2] = new Point3i(x + 1, y - 1, z + 1);
        neighbors[3] = new Point3i(x - 1, y + 1, z - 1);
        neighbors[4] = new Point3i(x - 1, y + 1, z + 1);
        neighbors[5] = new Point3i(x + 1, y - 1, z - 1);
        return neighbors;
    }
}
