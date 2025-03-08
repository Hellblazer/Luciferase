package com.hellblazer.luciferase.lucien;

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

    public long index() {
        return MortonCurve.encode(x, y, z);
    }
}
