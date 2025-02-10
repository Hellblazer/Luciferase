package com.hellblazer.luciferase.lucien;

import javax.vecmath.Tuple3i;
import java.util.stream.Stream;

/**
 * A recursive spatial data structure based on the red refinement of a tetrahedral volume
 */
public interface Tetree {
    Simplex intersecting(Spatial volume);

    /**
     * @param volume - the volume to enclose
     * @return - minimum Simplex enclosing the volume
     */
    Simplex enclosing(Spatial volume);

    /**
     * @param volume the volume to contain
     * @return the Stream of simplexes that minimally bound the volume
     */
    Stream<Simplex> bounding(Spatial volume);

    /**
     * @param volume - the enclosing volume
     * @return the Stream of simplexes bounded by the volume
     */
    Stream<Simplex> boundedBy(Spatial volume);

    /**
     * @param point - the point to enclose
     * @param level - refinement level
     * @return the simplex at the provided
     */
    Simplex enclosing(Tuple3i point, byte level);

    /**
     * @param linearIndex - the index in the space filling curve
     * @return the Simplex at the linear index
     */
    Simplex get(int linearIndex);

    public record Simplex<Data>(long index, Data cell) implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            return false;
        }

        @Override
        public boolean intersects(int originX, int originY, int originZ, int extentX, int extentY, int extentZ) {
            return false;
        }
    }
}
