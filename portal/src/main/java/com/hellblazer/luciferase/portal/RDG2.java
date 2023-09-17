/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import javax.vecmath.Vector3f;

/**
 * The Rhombic Dodecahedron Grid functions. This is the Face Center Cubic Grid.
 * This interface encapsulates how to convert from cubic to and from a
 * rhombohedral isometric lattice. This lattice is equivalent to tetrahedral /
 * octahedral packing, but without the headache of having to manage two separate
 * primitives or interlaced grid structures as with the face-centered cubic
 * lattice, which produces an equivalent structure.
 *
 *
 * @author hal.hildebrand
 */
public interface RDG2 {

    static float DIVR2 = (float) (1 / Math.sqrt(2));
    static float MULR2 = (float) Math.pow(2, -0.5);

    /**
     * Calculate the cross product of the two vectors, u and v, in tetrahedral
     * coordinates
     *
     * @param u
     * @param v
     * @return the Point3f representing the cross product in tetrahedral coordinates
     */
    default Point3f cross(Tuple3f u, Tuple3f v) {
        return new Point3f((-u.x * (v.y - v.z) + u.y * (3 * v.z + v.x) - u.z * (v.x + 3 * v.y)) * (DIVR2 / 2),
                           (-u.x * (v.y + 3 * v.z) - u.y * (v.z - v.x) + u.z * (3 * v.x + v.y)) * (DIVR2 / 2),
                           (u.x * (3 * v.y + v.z) - u.y * (v.z + 3 * v.x) - u.z * (v.x - v.y)) * (DIVR2 / 2));
    }

    /**
     * 
     * Calculate the dot product of the two vectors, u and v, in tetrahedral
     * coordinates
     *
     * @param u
     * @param v
     * @return the dot product of the two vectors
     */
    default float dot(Vector3f u, Vector3f v) {
        return (u.x * (v.x + v.y) + u.y * (v.x + v.x) + u.z * (v.y + v.x)) / 2 + u.x * v.x + u.y * v.y + u.z * v.x;
    }

    /**
     * Answer the euclidean length of the tetrahedral vector
     *
     * @param tetrahedral
     * @return the legth of the vector
     */
    default float euclideanNorm(Vector3f tetrahedral) {
        return (float) Math.sqrt(tetrahedral.x * (tetrahedral.x + tetrahedral.y + tetrahedral.z)
        + tetrahedral.y * (tetrahedral.y + tetrahedral.z) + tetrahedral.z * tetrahedral.z);
    }

    /**
     * Answer the manhattan distance
     *
     * @param tetrahedral
     * @return the manhatten distance to the vector
     */
    default float l1(Vector3f tetrahedral) {
        return Math.abs(tetrahedral.x) + Math.abs(tetrahedral.y) + Math.abs(tetrahedral.z);
    }

    /**
     * convert the tetrahedral point to the equivalent cartesian point
     *
     * @param tetrahedral
     * @return
     */
    default Point3f toCartesian(Tuple3f tetrahedral) {
        return new Point3f(tetrahedral.y + tetrahedral.z, tetrahedral.z + tetrahedral.x, tetrahedral.x + tetrahedral.y);
    }

    /**
     * convert the tetrahedral point to the equivalent cartesian point, preserving
     * edge length
     *
     * @param tetrahedral
     * @return
     */
    default Point3f toCartesianUnit(Tuple3f tetrahedral) {
        return new Point3f((tetrahedral.y + tetrahedral.z) * DIVR2, (tetrahedral.z + tetrahedral.x) * DIVR2,
                           (tetrahedral.x + tetrahedral.y) * DIVR2);
    }

    /**
     * convert the cartesian point to the equivalent tetrahedral point
     *
     * @param cartesian
     * @return
     */
    default Point3f toTetrahedral(Tuple3f cartesian) {
        return new Point3f((-cartesian.x + cartesian.y + cartesian.z) / 2,
                           (cartesian.x - cartesian.y + cartesian.z) / 2,
                           (cartesian.x + cartesian.y - cartesian.z) / 2);
    }

    /**
     * convert the cartesian point to the equivalent tetrahedral point, preserving
     * edge length
     *
     * @param cartesian
     * @return
     */
    default Point3f toTetrahedralUnit(Tuple3f cartesian) {
        return new Point3f((-cartesian.x + cartesian.y + cartesian.z) * DIVR2,
                           (cartesian.x - cartesian.y + cartesian.z) * DIVR2,
                           (cartesian.x + cartesian.y - cartesian.z) * DIVR2);
    }
}
