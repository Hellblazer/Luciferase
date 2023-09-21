/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 * <p>
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal;

import com.hellblazer.luciferase.portal.mesh.Line;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.shape.MeshView;
import javafx.util.Pair;

import javax.vecmath.*;

import static com.hellblazer.luciferase.portal.mesh.explorer.Colors.*;

;

/**
 * This lattice is equivalent to tetrahedral / octahedral packing, but without the
 * headache of having to manage two separate primitives or interlaced grid
 * structures as with the face-centered cubic lattice, which produces an
 * equivalent structure.
 * <p>
 * This implementation is based off the
 * <a href="https://gist.github.com/paniq/3afdb420b5d94bf99e36">python gist by
 * Leonard Ritter</a>
 * <p>
 * There is another good grid mapping that uses a non-orthogonal basis described
 * in the paper <a href=
 * "https://www.researchgate.net/publication/347616453_Digital_Objects_in_Rhombic_Dodecahedron_Grid/fulltext/609b5f7a458515d31513fb0a/Digital-Objects-in-Rhombic-Dodecahedron-Grid.pdf">Rhombic
 * Dodecahedron Grid—Coordinate System and 3D Digital Object Definitions</a>. I
 * like the simplicity of the Tetrahedral coordinates, although having 2 basis
 * vectors be orthogonal would be pretty sweet.
 *
 * @author hal.hildebrand
 */
public class Tetrahedral extends RDGCS {

    public Tetrahedral() {
    }

    public Tetrahedral(double edgeLength, int extent) {
        super(edgeLength, extent);
    }

    public Tetrahedral(double edgeLength, Pair<Integer, Integer> xExtent, Pair<Integer, Integer> yExtent, Pair<Integer, Integer> zExtent) {
        super(edgeLength, xExtent, yExtent, zExtent);
    }

    public Tetrahedral(Point3D origin, Pair<Integer, Integer> xExtent, double intervalX, Pair<Integer, Integer> yExtent, double intervalY, Pair<Integer, Integer> zExtent, double intervalZ) {
        super(origin, xExtent, intervalX, yExtent, intervalY, zExtent, intervalZ);
    }

    /**
     * Answer the Euclidean length of the tetrahedral vector
     *
     * @param rdg
     * @return the legth of the vector
     */
    public static float euclideanNorm(Vector3f rdg) {
        return (float) Math.sqrt(rdg.x * (rdg.x + rdg.y + rdg.z) + rdg.y * (rdg.y + rdg.z) + rdg.z * rdg.z);
    }

    /**
     * Answer the manhattan distance
     *
     * @param rdg
     * @return the manhatten distance to the vector
     */
    public static float l1(Vector3f rdg) {
        return Math.abs(rdg.x) + Math.abs(rdg.y) + Math.abs(rdg.z);
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
    public float dot(Vector3f u, Vector3f v) {
        return (u.x * (v.x + v.y) + u.y * (v.x + v.x) + u.z * (v.y + v.x)) / 2 + u.x * v.x + u.y * v.y + u.z * v.x;
    }

    @Override
    public Point3i[] faceConnectedNeighbors(Point3i cell) {
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

    @Override
    public Vector3f rotateVectorCC(Vector3f vec, Vector3f axis, double theta) {
        float x, y, z;
        float u, v, w;
        x = vec.getX();
        y = vec.getY();
        z = vec.getZ();
        u = axis.getX();
        v = axis.getY();
        w = axis.getZ();
        float C = u * x + v * y + w * z;
        float xPrime = (float) (u * C * (1d - Math.cos(theta)) + x * Math.cos(theta) + (-w * y + v * z) * Math.sin(theta));
        float yPrime = (float) (v * C * (1d - Math.cos(theta)) + y * Math.cos(theta) + (w * x - u * z) * Math.sin(theta));
        float zPrime = (float) (w * C * (1d - Math.cos(theta)) + z * Math.cos(theta) + (-v * x + u * y) * Math.sin(theta));
        return new Vector3f(xPrime, yPrime, zPrime);
    }

    @Override
    public Point3D toCartesian(Point3D rdg) {
        return new Point3D((rdg.getY() + rdg.getZ()) * DIVR2, (rdg.getZ() + rdg.getX()) * DIVR2, (rdg.getX() + rdg.getY()) * DIVR2);
    }

    @Override
    public Point3D toCartesian(Tuple3i rdg) {
        return new Point3D((rdg.y + rdg.z) * DIVR2, (rdg.z + rdg.x) * DIVR2, (rdg.x + rdg.y) * DIVR2);
    }

    @Override
    public Point3i toRDG(Tuple3f cartesian) {
        return new Point3i((int) ((-cartesian.x + cartesian.y + cartesian.z) * MULR2), (int) ((cartesian.x - cartesian.y + cartesian.z) * MULR2), (int) ((cartesian.x + cartesian.y - cartesian.z) * MULR2));
    }

    @Override
    public Point3i[] vertexConnectedNeighbors(Point3i cell) {
        var x = cell.x;
        var y = cell.y;
        var z = cell.z;
        var neighbors = new Point3i[6];
        neighbors[0] = new Point3i(x + 1, y + 1, z);
        neighbors[1] = new Point3i(x + 1, y - 1, z);
        neighbors[2] = new Point3i(x - 1, y + 1, z);
        neighbors[3] = new Point3i(x - 1, y - 1, z);
        neighbors[4] = new Point3i(x, y - 1, z + 1);
        neighbors[5] = new Point3i(x, y + 1, z - 1);
        return neighbors;
    }

    @Override
    public Point3f cross(Tuple3f u, Tuple3f v) {
        return new Point3f((-u.x * (v.y - v.z) + u.y * (3 * v.z + v.x) - u.z * (v.x + 3 * v.y)) * (RDGCS.DIVR2 / 2), (-u.x * (v.y + 3 * v.z) - u.y * (v.z - v.x) + u.z * (3 * v.x + v.y)) * (RDGCS.DIVR2 / 2), (u.x * (3 * v.y + v.z) - u.y * (v.z + 3 * v.x) - u.z * (v.x - v.y)) * (RDGCS.DIVR2 / 2));
    }
}
