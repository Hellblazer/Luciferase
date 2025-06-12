package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.geometry.MortonCurve;

/**
 * @author hal.hildebrand
 **/
public record Octant(int x, int y, int z, byte level) {
    public Octant(int x, int y, int z, int level) {
        this(x, y, z, (byte) level);
    }

    public long index() {
        return MortonCurve.encode(x, y, z);
    }
}
