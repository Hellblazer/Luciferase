package com.hellblazer.luciferase.lucien;

import javax.vecmath.Tuple3f;
import javax.vecmath.Vector3d;

import static com.hellblazer.luciferase.lucien.Constants.*;

/**
 * @author hal.hildebrand
 **/
public record Octant(int x, int y, int z, byte level) {

    public Octant(int x, int y, int z, int level) {
        this(x, y, z, (byte) level);
    }

    public Octant(long morton) {
        this(MortonCurve.decode(morton), Constants.toLevel(morton));
    }

    private Octant(int[] decode, byte level) {
        this(decode[0], decode[1], decode[2], level);
    }

    public boolean contains(Tuple3f point) {
        var length = lengthAtLevel(level);
        return x <= point.x && point.x <= x + length && y <= point.y && point.y <= y + length && z <= point.z
        && point.z <= z + length;
    }

    public long index() {
        return MortonCurve.encode(x, y, z);
    }

    public Octant parent() {
        return new Octant(MortonCurve.decode(index()), level);
    }

    public Octant[] split() {
        if (level == MAX_REFINEMENT_LEVEL) {
            throw new IllegalArgumentException("Cannot split max refinement level: " + level);
        }
        var result = new Octant[8];
        byte next = (byte) (level + 1);
        var length = Constants.lengthAtLevel(next);
        result[0] = new Octant(x, y, z, next);
        result[1] = new Octant(x + length, y, z, next);
        result[2] = new Octant(x, y + length, z, next);
        result[3] = new Octant(x + length, y + length, z, next);
        result[4] = new Octant(x, y, z + length, next);
        result[5] = new Octant(x + length, y, z + length, next);
        result[6] = new Octant(x, y + length, z + length, next);
        result[7] = new Octant(x + length, y + length, z + length, next);
        return result;
    }

    public Vector3d[] vertices() {
        int edgeLength = Constants.lengthAtLevel(level);
        Vector3d[] vs = new Vector3d[8];
        for (int i = 0; i < 8; i++) {
            Vector3d current = new Vector3d(CORNER_COORDINATES[i][0] * edgeLength,
                                            CORNER_COORDINATES[i][1] * edgeLength,
                                            CORNER_COORDINATES[i][2] * edgeLength);
            current.x += x;
            current.y += y;
            current.z += z;
            vs[i] = current;
        }
        return vs;
    }
}
