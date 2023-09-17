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
import javax.vecmath.Point3i;

/**
 * Functional interface for a Rhombic Dodecahedron Grid (RDG). This grid is
 * based on a a non-orthogonal coordinate system to represent the RDG where each
 * grid point represents the centroid of a rhombic dodecahedron cell. The
 * novelty of this system is that every single one of the integer coordinate
 * points is the centroid of a dodecahedron. The RDG uses integer points,
 * Point3i, to represent coordinates of cells of the grid. Translation to/from
 * Cartesian coordinates involves the square root of 2, so conversion may not be
 * precise.
 * <p>
 * The image below shows the transformation of the original cubic basis vectors
 * in (a) to the basis vectors used for the RDG in (c).
 * <p>
 * <img src=
 * "https://media.springernature.com/lw685/springer-static/image/chp%3A10.1007%2F978-3-030-14085-4_3/MediaObjects/481128_1_En_3_Fig2_HTML.png?as=webp"
 * />
 * 
 * <p>
 * These grid functions are implementations from the paper <a href=
 * "https://link.springer.com/chapter/10.1007/978-3-030-14085-4_3">Rhombic
 * Dodecahedron Grid—Coordinate System and 3D Digital Object Definitions</a>
 * <p>
 *
 * @author hal.hildebrand
 */
public interface RDG {

    static final double SQRT2 = Math.sqrt(2);

    /**
     * Answer the norm distance between two RDG points u and v
     *
     * @param u - point in RDG coordinates
     * @param v - point in RDG coordinates
     * @return the norm distance between u and v
     */
    default double distance(Point3f u, Point3f v) {
        var dx = u.x - v.x;
        var dy = u.y - v.y;
        var dz = u.z - v.z;
        var dx2 = dx * dx;
        var dy2 = dy * dy;
        var dz2 = dz * dz;
        var squared = dx2 + dy2 + dz2;
        var dxDz = dx * dz;
        var dyDz = dy * dz;

        return Math.sqrt(squared - dxDz - dyDz);
    }

    /**
     * Answer the euclidaean distance betwwen two RDG points u and v
     *
     * @param u - point in RDG coordinates
     * @param v - point in RDG coordinates
     * @return the euclidian distance between u and v
     */
    default double euclideanDistance(Point3f u, Point3f v) {
        var dx = u.x - v.x;
        var dy = u.y - v.y;
        var dz = u.z - v.z;
        var dx2 = dx * dx;
        var dy2 = dy * dy;
        var dz2 = dz * dz;
        var squared = dx2 + dy2 + dz2;
        var dxDz = dx * dz;
        var dyDz = dy * dz;

        return Math.sqrt(2 * (squared - dxDz - dyDz));
    }

    /**
     * Answer the 12 face connected neighbors in the RDB
     *
     * @param cell - the target cell
     * @return the array of Point3i vertex neighbor coordinates of the cell
     */
    default Point3i[] faceConnectedNeighbors(Point3i cell) {
        var x = cell.x;
        var y = cell.y;
        var z = cell.z;
        var neighbors = new Point3i[12];
        neighbors[0] = new Point3i(x + 1, y, z);
        neighbors[1] = new Point3i(x - 1, y, z);
        neighbors[2] = new Point3i(x, y + 1, 1);
        neighbors[3] = new Point3i(x, y - 1, z);
        neighbors[4] = new Point3i(x, y, z + 1);
        neighbors[5] = new Point3i(x, y, z - 1);
        neighbors[6] = new Point3i(x + 1, y + 1, z + 1);
        neighbors[7] = new Point3i(x - 1, y - 1, z - 1);
        neighbors[8] = new Point3i(x, y + 1, z + 1);
        neighbors[9] = new Point3i(x + 1, y, z + 1);
        neighbors[10] = new Point3i(x - 1, y, z - 1);
        neighbors[11] = new Point3i(x, y - 1, z - 1);
        return neighbors;
    }

    /**
     * Answer the symmetric point v corresponding to u in the symmetry group
     *
     * @param group - the symmetry group
     * @param u     - the point in RDG coordinates
     * @return the symmetric Point3i of u in the symmetry group
     */
    default Point3i symmetry(int group, Point3i u) {
        var x = u.x;
        var y = u.y;
        var z = u.z;
        var mx = -x;
        var my = -y;
        var mz = -z;
        return switch (group) {
        case 0 -> u;
        case 1 -> new Point3i(mx + z, y, mx + y);
        case 2 -> new Point3i(y, mx + z, z);
        case 3 -> new Point3i(y, x, z);
        case 4 -> new Point3i(my + z, x, x + my);
        case 5 -> new Point3i(mx, y + mz, mz);
        case 6 -> new Point3i(mx + z, my + z, z);
        case 7 -> new Point3i(x, my + z, x + my);
        case 8 -> new Point3i(my, x + mz, mz);
        case 9 -> new Point3i(my, mx, mz);
        case 10 -> new Point3i(y + mz, mx, mx + y);
        case 11 -> new Point3i(x + mz, my, mz);
        case 12 -> new Point3i(mx + z, y, z);
        case 13 -> new Point3i(my + z, mx + z, z);
        case 14 -> new Point3i(y, mx + z, mx + y);
        case 15 -> new Point3i(my + z, x, z);
        case 16 -> new Point3i(x + mz, y + mz, mz);
        case 17 -> new Point3i(mx, y + mz, mx + y);
        case 18 -> new Point3i(x, my + z, z);

//      Rhombic Dodecahedron Grid 33
        case 19 -> new Point3i(y + mz, y, mx + y);
        case 20 -> new Point3i(x, y, x + y + mz);
        case 21 -> new Point3i(mx, mx + z, mx + my + z);
        case 22 -> new Point3i(x + mz, x, x + my);
        case 23 -> new Point3i(y, x, x + y + mz);
        case 24 -> new Point3i(y, y + mz, x + y + mz);
        case 25 -> new Point3i(my, my + z, x + my);
        case 26 -> new Point3i(mx + z, my + z, mx + my + z);
        case 27 -> new Point3i(y + mz, x + mz, mz);
        case 28 -> new Point3i(x, x + mz, x + y + mz);
        case 29 -> new Point3i(my, x + mz, x + my);
        case 30 -> new Point3i(mx + z, mx, mx + y);
        case 31 -> new Point3i(y + mz, mx, mz);
        case 32 -> new Point3i(my, mx, mx + my + z);
        case 33 -> new Point3i(mx, my, mz);
        case 34 -> new Point3i(my + z, my, mx + my + z);
        case 35 -> new Point3i(x + mz, my, x + my);
        case 36 -> new Point3i(y + mz, y, x + y + mz);
        case 37 -> new Point3i(mx, mx + z, mx + y);
        case 38 -> new Point3i(my + z, mx + z, mx + my + z);
        case 39 -> new Point3i(x + mz, x, x + y + mz);
        case 40 -> new Point3i(y, y + mz, mx + y);
        case 41 -> new Point3i(x + mz, y + mz, x + y + mz);
        case 42 -> new Point3i(my, my + z, mx + my + z);
        case 43 -> new Point3i(x, x + mz, x + my);
        case 44 -> new Point3i(y + mz, x + mz, x + y + mz);
        case 45 -> new Point3i(mx + z, mx, mx + my + z);
        case 46 -> new Point3i(my + z, my, x + my);
        case 47 -> new Point3i(mx, my, mx + my + z);

        default -> throw new IllegalArgumentException("Invalid symmetry group: " + group);
        };
    }

    /**
     * Answer the cartesian transform of the RDG point
     *
     * @param rdg - the cartesian point to transform
     * @return the Cartesian conversion of the RDG point
     */
    default Point3f toCartesian(Point3i rdg) {
        return new Point3f((float) ((2 * rdg.y - rdg.z) / SQRT2), (float) ((-2 * rdg.x + rdg.z) / SQRT2), rdg.z);
    }

    /**
     * Answer the RDG transform of the cartesian point
     *
     * @param cartesian - the cartesian point to transform
     * @return the RDG conversion of the cartesian point
     */
    default Point3i toRDG(Point3f cartesian) {
        return new Point3i((int) (-SQRT2 * cartesian.y + cartesian.z) / 2,
                           (int) (SQRT2 * cartesian.x + cartesian.z) / 2, (int) cartesian.z);
    }

    /**
     * 
     * Answer the 6 vertex connected neighbors in the RDB
     *
     * @param cell - the target cell
     * @return the array of Point3i vertex neighbor coordinates of the cell
     */
    default Point3i[] vertexConnectedNeighbors(Point3i cell) {
        var x = cell.x;
        var y = cell.y;
        var z = cell.z;
        var neighbors = new Point3i[6];
        neighbors[0] = new Point3i(x + 1, y + 1, z);
        neighbors[1] = new Point3i(x + 1, y - 1, z);
        neighbors[2] = new Point3i(x - 1, y + 1, z);
        neighbors[3] = new Point3i(x - 1, y - 1, z);
        neighbors[4] = new Point3i(x + 1, y + 1, z + 2);
        neighbors[5] = new Point3i(x - 1, y - 1, z - 2);
        return neighbors;
    }
}
