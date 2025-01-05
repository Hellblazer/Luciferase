package com.hellblazer.luciferase.lucien.index;

import javax.vecmath.Point3i;
import javax.vecmath.Tuple3i;

import static com.hellblazer.luciferase.lucien.index.TetConstants.*;

/**
 * A tetrahedron in the mesh
 *
 * @author hal.hildebrand
 **/
public record Tet(int x, int y, int z, byte l, byte type) {

    /**
     * @param L - maximum refinement level
     * @return the length of a triangle at the given level, in integer coordinates
     */
    public int lengthAtLevel(int L) {
        return 1 << (L - l);
    }

    /**
     * Answer the 3D coordinates of the tetrahedron represented by the receiver
     *
     * @param L - max refinement level
     * @return the 3D coordinates of the tetrahedron described by the receiver in CCW order
     */
    public Tuple3i[] coordinates(int L) {
        var coords = new Point3i[4];
        coords[0] = new Point3i(x, y, z);
        var h = lengthAtLevel(L);
        var i = type / 2;
        var j = 0;
        if (type / 2 == 0) {
            j = (i + 2) % 3;
        } else {
            j = (i + 1) % 3;
        }

        if (i == 0) {
            coords[1] = new Point3i(coords[0].x + h, coords[0].y, coords[0].z);
        } else if (i == 1) {
            coords[1] = new Point3i(coords[0].x, coords[0].y + h, coords[0].z);
        } else if (i == 2) {
            coords[2] = new Point3i(coords[0].x, coords[0].y, coords[0].z + h);
        }

        if (j == 0) {
            coords[2] = new Point3i(coords[1].x + h, coords[1].y, coords[1].z);
        } else if (j == 1) {
            coords[2] = new Point3i(coords[1].x, coords[1].y + h, coords[1].z);
        } else if (j == 2) {
            coords[2] = new Point3i(coords[1].x, coords[1].y, coords[1].z + h);
        }

        coords[3] = new Point3i(coords[1].x + h, coords[1].y + h, coords[1].z + h);
        return coords;
    }

    /**
     * @param L - max refinement level
     * @return the cube id of the receiver
     */
    public int cubeId(int L) {
        if (l == 0) {
            return 0;
        }
        int h = lengthAtLevel(L);
        int i = 0;
        i |= (x & h) > 0 ? 1 : 0;
        i |= (y & h) > 0 ? 2 : 0;
        i |= (z & h) > 0 ? 4 : 0;
        return i;
    }

    /**
     * @param L - max refinement level
     * @return the parent Tet
     */
    public Tet parent(int L) {
        int h = lengthAtLevel(L);
        return new Tet(x & ~h, y & ~h, z & ~h, (byte) (l - 1), PARENT_3D[cubeId(L)][type]);
    }

    /**
     * @param i - the Morton child id
     * @return the i-th child (in Bey's order) of the receiver
     */
    public Tet child(int i, int L) {
        var coords = coordinates(L);
        var j = 0;
        if (i == 1 || i == 4 || i == 5) {
            j = 1;
        } else if (i == 2 || i == 6 || i == 7) {
            j = 2;
        }
        if (i == 3) {
            j = 3;
        }
        return new Tet((coords[0].x + coords[j].x) / 2, (coords[0].x + coords[j].x) / 2,
                       (coords[0].x + coords[j].x) / 2, (byte) (l + 1), CHILD_TYPE_3D[type][i]);
    }

    /**
     * @param i - the Tet Morton child id
     * @return the i-th child (in Tet Morton order) of the receiver
     */
    public Tet childTM(int i, int L) {
        return child(CHILD_TYPE_3D_MORTON[type][i], L);
    }
}
