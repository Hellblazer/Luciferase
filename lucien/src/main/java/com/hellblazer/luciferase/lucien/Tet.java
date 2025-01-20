package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3i;
import javax.vecmath.Tuple3i;

import static com.hellblazer.luciferase.lucien.TetConstants.*;

/**
 * A tetrahedron in the mesh from the paper:
 * <a href="https://arxiv.org/abs/1509.04627">A tetrahedral space-filling curve for non-conforming adaptive meshes</a>
 *
 * @author hal.hildebrand
 **/
public record Tet(int x, int y, int z, byte l, byte type) {

    /**
     * @param index - the consecutive index of the tetrahedron
     * @return the Tet corresponding to the consecutive index
     */
    public static Tet tetrahedron(long index, byte l) {
        int offsetCoords, offsetIndex, localIndex, cid = 0;
        byte type = 0;
        int childrenM1 = 7;
        var coordinates = new int[3];

        for (int i = 1; i <= l; i++) {
            offsetCoords = MAX_REFINEMENT_LEVEL - i;
            offsetIndex = l - i;
            // Get the local index of T's ancestor on level i
            localIndex = (int) ((index >> (3 * offsetIndex)) & childrenM1);
            // get the type and cube-id of T's ancestor on level i
            cid = PARENT_TYPE_LOCAL_INDEX_TO_CID[type][localIndex];
            type = PARENT_TYPE_LOCAL_INDEX_TO_TYPE[type][localIndex];
            coordinates[0] |= (cid & 1) > 0 ? 1 << offsetCoords : 0;
            coordinates[1] |= (cid & 2) > 0 ? 1 << offsetCoords : 0;
            coordinates[2] |= (cid & 4) > 0 ? 1 << offsetCoords : 0;
        }
        return new Tet(coordinates[0], coordinates[1], coordinates[2], (byte) l, type);
    }

    /**
     * @return the length of a triangle at the given level, in integer coordinates
     */
    public int lengthAtLevel() {
        return 1 << (MAX_REFINEMENT_LEVEL - l);
    }

    /**
     * Answer the 3D coordinates of the tetrahedron represented by the receiver
     *
     * @return the 3D coordinates of the tetrahedron described by the receiver in CCW order
     */
    public Tuple3i[] coordinates() {
        var coords = new Point3i[] { new Point3i(), new Point3i(), new Point3i(), new Point3i() };
        coords[0] = new Point3i(x, y, z);
        var h = lengthAtLevel();
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
     * @return the cube id of t's ancestor of level "level"
     */
    public int cubeId(byte level) {
        if (level == 0) {
            return 0;
        }
        int h = 1 << (MAX_REFINEMENT_LEVEL - level);
        int id = 0;
        id |= (x & h) > 0 ? 1 : 0;
        id |= (y & h) > 0 ? 2 : 0;
        id |= (z & h) > 0 ? 4 : 0;
        return id;
    }

    /**
     * @return the parent Tet
     */
    public Tet parent() {
        int h = lengthAtLevel();
        return new Tet(x & ~h, y & ~h, z & ~h, (byte) (l - 1), PARENT_3D[cubeId(l)][type]);
    }

    /**
     * @param i - the Morton child id
     * @return the i-th child (in Bey's order) of the receiver
     */
    public Tet child(byte i) {
        var coords = coordinates();
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
    public Tet childTM(byte i) {
        if (l == MAX_REFINEMENT_LEVEL) {
            throw new IllegalArgumentException(
            "No children at maximum refinement level: %s".formatted(MAX_REFINEMENT_LEVEL));
        }
        return child(CHILD_TYPE_3D_MORTON[type][i]);
    }

    /**
     * @return the consecutive index of the receiver on the space filling curve
     */
    public long index() {
        var I = 0L;
        var b = type;
        for (var i = l; i >= 1; i--) {
            var c = cubeId(i);
            I = (long) ((I + Math.pow(8, i)) * TYPE_CUBE_ID_TO_LOCAL_INDEX[b][c]);
            b = PARENT_3D[c][b];
        }
        return I;
    }
}
