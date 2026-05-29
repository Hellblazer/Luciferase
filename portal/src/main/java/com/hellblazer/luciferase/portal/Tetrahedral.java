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
package com.hellblazer.luciferase.portal;

import com.hellblazer.luciferase.geometry.rd.RDGCoordinates;
import javafx.geometry.Point3D;
import javafx.util.Pair;

import javax.vecmath.*;

/**
 * This lattice is equivalent to tetrahedral / octahedral packing, but without the headache of having to manage two
 * separate primitives or interlaced grid structures as with the face-centered cubic lattice, which produces an
 * equivalent structure.
 * <p>
 * This implementation is based off the
 * <a href="https://gist.github.com/paniq/3afdb420b5d94bf99e36">python gist by
 * Leonard Ritter</a>
 * <p>
 * There is another good grid mapping that uses a non-orthogonal basis described in the paper <a href=
 * "https://www.researchgate.net/publication/347616453_Digital_Objects_in_Rhombic_Dodecahedron_Grid/fulltext/609b5f7a458515d31513fb0a/Digital-Objects-in-Rhombic-Dodecahedron-Grid.pdf">Rhombic
 * Dodecahedron Grid—Coordinate System and 3D Digital Object Definitions</a>. I like the simplicity of the Tetrahedral
 * coordinates, although having 2 basis vectors be orthogonal would be pretty sweet.
 *
 * @author hal.hildebrand
 */
public class Tetrahedral extends RDGCS {

    public Tetrahedral() {
    }

    public Tetrahedral(double edgeLength, int extent) {
        super(edgeLength, extent);
    }

    public Tetrahedral(double edgeLength, Pair<Integer, Integer> xExtent, Pair<Integer, Integer> yExtent,
                       Pair<Integer, Integer> zExtent) {
        super(edgeLength, xExtent, yExtent, zExtent);
    }

    public Tetrahedral(Point3D origin, Pair<Integer, Integer> xExtent, double intervalX, Pair<Integer, Integer> yExtent,
                       double intervalY, Pair<Integer, Integer> zExtent, double intervalZ) {
        super(origin, xExtent, intervalX, yExtent, intervalY, zExtent, intervalZ);
    }

    /**
     * Answer the Euclidean length of the tetrahedral vector
     *
     * @param rdg
     * @return the legth of the vector
     */
    public static float euclideanNorm(Vector3f rdg) {
        return RDGCoordinates.euclideanNorm(rdg);
    }

    /**
     * Answer the manhattan distance
     *
     * @param rdg
     * @return the manhatten distance to the vector
     */
    public static float l1(Vector3f rdg) {
        return RDGCoordinates.l1(rdg);
    }

    public Point3f axisAngle(float radians, Vector3f u, Vector3f w) {
        var sin = (float) Math.sin(radians);
        var cos = (float) Math.cos(radians);
        var cross = cross(w, u);
        var t = (1 - cos) * dot(w, u);

        var vx = cos * u.x + sin * cross.x + t * w.x;
        var vy = cos * u.y + sin * cross.y + t * w.y;
        var vz = cos * u.z + sin * cross.z + t * w.z;
        return new Point3f(vx, vy, vz);
    }

    @Override
    public Point3f cross(Tuple3f u, Tuple3f v) {
        return RDGCoordinates.cross(u, v);
    }

    /**
     * Tetrahedral-basis inner product derived from the metric tensor
     * {@code G_ii = 1, G_ij = 1/2} (i ≠ j). The previous implementation had
     * multiple incorrect coefficients ({@code u.y*(v.x+v.x)} instead of
     * {@code u.y*v.y + (...)/2}, trailing {@code u.z*v.x} instead of
     * {@code u.z*v.z}) — Luciferase-7jk.
     *
     * @param u left vector in Tetrahedral basis
     * @param v right vector in Tetrahedral basis
     * @return {@code Σ_ij G_ij · u_i · v_j}
     */
    @Override
    public float dot(Vector3f u, Vector3f v) {
        return RDGCoordinates.dot(u, v);
    }

    @Override
    public Point3i[] faceConnectedNeighbors(Point3i cell) {
        return RDGCoordinates.faceConnectedNeighbors(cell);
    }

    @Override
    public Vector3f rotateVectorCC(Vector3f vec, Vector3f axis, double theta) {
        return RDGCoordinates.rotateVectorCC(vec, axis, theta);
    }

    @Override
    public Point3D toCartesian(Tuple3i rdg) {
        var p = RDGCoordinates.toCartesian(rdg);
        return new Point3D(p.x, p.y, p.z);
    }

    @Override
    public Point3D toCartesian(Point3D rdg) {
        return new Point3D((rdg.getY() + rdg.getZ()) * DIVIDE_ROOT_2, (rdg.getZ() + rdg.getX()) * DIVIDE_ROOT_2,
                           (rdg.getX() + rdg.getY()) * DIVIDE_ROOT_2);
    }

    /**
     * Inverse of {@link #toCartesian(Tuple3i)} via the Tetrahedral basis change.
     * <p>
     * Uses {@link Math#round} (not C-style {@code (int)} truncation) to invert
     * the {@code 1/√2} basis scale faithfully. The earlier truncation
     * implementation broke the {@code toRDG(toCartesian(p)) == p} round-trip
     * for unit-magnitude integer points: for example,
     * {@code toCartesian((1, 0, 0)) = (0, 1/√2, 1/√2)} and the inverse
     * computation produced floats in the {@code [0.99999..., 1.00000...]}
     * neighbourhood that {@code (int)} cast to {@code 0} (Luciferase-6oa
     * audit finding).
     */
    @Override
    public Point3i toRDG(Tuple3f cartesian) {
        return RDGCoordinates.toRDG(cartesian);
    }

    /**
     * Return the 6 FCC second-shell vertex-connected neighbors of {@code cell}.
     * <p>
     * In Tetrahedral coordinates these are the 6 offsets {@code (±1, ±1, ∓1)}
     * with mixed signs whose Cartesian images are the 6 axis-aligned points at
     * distance {@code sqrt(2)} from origin (the FCC second shell). The
     * previous implementation returned a mix of face-neighbor (distance 1) and
     * third-shell (distance sqrt(11)) offsets — Luciferase-xnf.
     *
     * @param cell the cell whose vertex-connected neighbors to return
     * @return 6 distinct offsets all at Cartesian distance {@code sqrt(2)} from
     *         {@code cell}
     */
    @Override
    public Point3i[] vertexConnectedNeighbors(Point3i cell) {
        return RDGCoordinates.vertexConnectedNeighbors(cell);
    }
}
