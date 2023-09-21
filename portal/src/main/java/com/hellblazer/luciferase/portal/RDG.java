package com.hellblazer.luciferase.portal;

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.util.Pair;

import javax.vecmath.*;

/*
 * This implementation of the RDGCS is based on a non-orthogonal coordinate system
 * to represent the RDG where each grid point represents the centroid of a
 * rhombic dodecahedron cell. This lattice ensures that every single one of
 * the integer coordinate points is the centroid of a rhombic dodecahedron.
 * The RDG uses integer points, Point3i, to represent coordinates of cells of the grid.
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
 * "https://www.researchgate.net/publication/347616453_Digital_Objects_in_Rhombic_Dodecahedron_Grid/fulltext/609b5f7a458515d31513fb0a/Digital-Objects-in-Rhombic-Dodecahedron-Grid.pdf">Rhombic
 * Dodecahedron Grid—Coordinate System and 3D Digital Object Definitions</a>
 * <p>
 *
 * @author hal.hildebrand
 **/
public class RDG extends RDGCS {
    public RDG(double intervalX, double intervalY, double intervalZ, Point3D origin, Pair<Integer, Integer> xExtent, Pair<Integer, Integer> yExtent, Pair<Integer, Integer> zExtent) {
        super(intervalX, intervalY, intervalZ, origin, xExtent, yExtent, zExtent);
    }

    public RDG() {
    }

    public RDG(Point3D origin, Pair<Integer, Integer> xExtent, double intervalX, Pair<Integer, Integer> yExtent, double intervalY, Pair<Integer, Integer> zExtent, double intervalZ) {
        super(origin, xExtent, intervalX, yExtent, intervalY, zExtent, intervalZ);
    }

    public RDG(double edgeLength, int extent) {
        super(edgeLength, extent);
    }

    public RDG(double edgeLength, Pair<Integer, Integer> xExtent, Pair<Integer, Integer> yExtent, Pair<Integer, Integer> zExtent) {
        super(edgeLength, xExtent, yExtent, zExtent);
    }

    @Override
    public Point3f cross(Tuple3f u, Tuple3f v) {
        return null;
    }

    @Override
    public float dot(Vector3f u, Vector3f v) {
        return 0;
    }

    @Override
    public Point3i[] faceConnectedNeighbors(Point3i cell) {
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

    @Override
    public Vector3f rotateVectorCC(Vector3f vec, Vector3f axis, double theta) {
        return null;
    }

    @Override
    public Point3D toCartesian(Point3D rdg) {
        var x = rdg.getX();
        var y = rdg.getY();
        var z = rdg.getZ();
        return new Point3D(x + y - z, -x + y, z);
    }

    @Override
    public Point3D toCartesian(Tuple3i rdg) {
        var x = rdg.x;
        var y = rdg.y;
        var z = rdg.z;
        return new Point3D(x + y - z, -x + y, z);
    }

    @Override
    public Point3i toRDG(Tuple3f cartesian) {
        var x = cartesian.getX();
        var y = cartesian.getY();
        var z = cartesian.getZ();
        return new Point3i((int) ((x - y + z) / 2), (int) ((x + y + z) / 2), (int) z);
    }

    /**
     * Answer the symmetric point v corresponding to point u in the symmetry group
     *
     * @param group - the symmetry group
     * @param u     - the point in rdg coordinates
     * @return the symmetric Point3i of u in the symmetry group in rdg coordinates
     */
    public Point3i symmetry(int group, Point3i u) {
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

            case 12 -> new Point3i(y + mz, y, mx + y);
            case 13 -> new Point3i(x, y, x + y + mz);
            case 14 -> new Point3i(mx, mx + z, mx - y + z);
            case 15 -> new Point3i(x + mz, x, x + my);
            case 16 -> new Point3i(y, x, x + y + mz);
            case 17 -> new Point3i(y, y + mz, x + y + mz);
            case 18 -> new Point3i(my, my + z, x + my);
            case 19 -> new Point3i(mx + z, my + z, mx + my + z);
            case 20 -> new Point3i(x, x + mz, x + y + mz);
            case 21 -> new Point3i(mx + z, mx, mx + y);
            case 22 -> new Point3i(my, mx, mx + my + z);
            case 23 -> new Point3i(my + z, my, mx + my + z);

            case 24 -> new Point3i(mx + z, y, z);
            case 25 -> new Point3i(my + z, mx + z, z);
            case 26 -> new Point3i(y, mx + z, mx + y);
            case 27 -> new Point3i(my + z, x, z);
            case 28 -> new Point3i(x + mz, y + mz, mz);
            case 29 -> new Point3i(mx, y + mz, mx + y);
            case 30 -> new Point3i(x, my + z, z);
            case 31 -> new Point3i(y + mz, x + mz, mz);
            case 32 -> new Point3i(my, x + mz, x + my);
            case 33 -> new Point3i(y + mz, mx, mz);
            case 34 -> new Point3i(mx, my, mz);
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
     * Answer the symmetric point v corresponding to u in the symmetry group
     *
     * @param group - the symmetry group
     * @param u     - the point in orthogonal coordinates
     * @return the symmetric Point3i of u in the symmetry group in orthogonal
     * coordinates
     */
    public Point3i symmetryOrtho(int group, Point3i u) {
        var x = u.x;
        var y = u.y;
        var z = u.z;
        var mx = -x;
        var my = -y;
        var mz = -z;

        return switch (group) {
            case 0 -> u;
            case 1 -> new Point3i(z, x, y);
            case 2 -> new Point3i(y, mx, z);
            case 3 -> new Point3i(x, my, z);
            case 4 -> new Point3i(z, x, my);
            case 5 -> new Point3i(y, x, mz);
            case 6 -> new Point3i(mx, my, z);
            case 7 -> new Point3i(z, mx, my);
            case 8 -> new Point3i(my, x, mz);
            case 9 -> new Point3i(mx, y, mz);
            case 10 -> new Point3i(mz, mx, y);
            case 11 -> new Point3i(my, mx, mz);

            case 12 -> new Point3i(x, z, y);
            case 13 -> new Point3i(z, y, x);
            case 14 -> new Point3i(y, z, mx);
            case 15 -> new Point3i(x, z, my);
            case 16 -> new Point3i(z, my, x);
            case 17 -> new Point3i(y, mz, x);
            case 18 -> new Point3i(mx, z, my);
            case 19 -> new Point3i(z, my, mx);
            case 20 -> new Point3i(my, mz, x);
            case 21 -> new Point3i(mx, mz, y);
            case 22 -> new Point3i(mz, y, mx);
            case 23 -> new Point3i(my, mz, mx);

            case 24 -> new Point3i(y, x, z);
            case 25 -> new Point3i(mx, y, z);
            case 26 -> new Point3i(z, mx, y);
            case 27 -> new Point3i(my, x, z);
            case 28 -> new Point3i(x, y, mz);
            case 29 -> new Point3i(mz, x, y);
            case 30 -> new Point3i(my, mx, z);
            case 31 -> new Point3i(x, my, mz);
            case 32 -> new Point3i(mz, x, my);
            case 33 -> new Point3i(y, mx, mz);
            case 34 -> new Point3i(mx, my, mz);
            case 35 -> new Point3i(mz, mx, my);

            case 36 -> new Point3i(y, z, x);
            case 37 -> new Point3i(mx, z, y);
            case 38 -> new Point3i(z, y, mx);
            case 39 -> new Point3i(my, z, x);
            case 40 -> new Point3i(x, mz, y);
            case 41 -> new Point3i(mz, y, x);
            case 42 -> new Point3i(my, z, mx);
            case 43 -> new Point3i(x, mz, my);
            case 44 -> new Point3i(mz, my, x);
            case 45 -> new Point3i(y, mz, mx);
            case 46 -> new Point3i(mx, mz, my);
            case 47 -> new Point3i(mz, my, mx);

            default -> throw new IllegalArgumentException("Invalid symmetry group: " + group);
        };
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
        neighbors[4] = new Point3i(x + 1, y + 1, z + 2);
        neighbors[5] = new Point3i(x - 1, y - 1, z - 2);
        return neighbors;
    }

    @Override
    public void addAxes(Group grid, float radius, float height, float lineRadius, int divisions) {

    }
}
