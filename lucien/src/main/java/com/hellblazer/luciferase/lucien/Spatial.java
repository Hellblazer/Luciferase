package com.hellblazer.luciferase.lucien;

import javax.vecmath.Tuple3i;

/**
 * Simple bounding volume api for querying the Tetree
 */
public interface Spatial {

    boolean containedBy(aabt aabt);

    /**
     * Answer true if the axis aligned bounding tetrahedral volume intersects the receiver's volume
     *
     * @return true - if the axis aligned bounding tetrahedral volume intersects the receiver's volume
     */
    boolean intersects(int originX, int originY, int originZ, int extentX, int extentY, int extentZ);

    default boolean intersects(aabt aabp) {
        return intersects(aabp.originX, aabp.originY, aabp.originZ, aabp.extentX, aabp.extentY, aabp.extentZ);
    }

    public record Sphere(int centerX, int centerY, int centerZ, int radius) implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            return false;
        }

        @Override
        public boolean intersects(int originX, int originY, int originZ, int extentX, int extentY, int extentZ) {
            return false;
        }
    }

    /**
     * A volume formed by six parallelograms
     *
     * @param originX
     * @param originY
     * @param originZ
     * @param extentX
     * @param extentY
     * @param extentZ
     */
    public record Parallelepiped(int originX, int originY, int originZ, int extentX, int extentY, int extentZ)
    implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            return false;
        }

        @Override
        public boolean intersects(int originX, int originY, int originZ, int extentX, int extentY, int extentZ) {
            return false;
        }
    }

    public record Tetrahedron(Tuple3i a, Tuple3i b, Tuple3i c, Tuple3i d) implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            return false;
        }

        @Override
        public boolean intersects(int originX, int originY, int originZ, int extentX, int extentY, int extentZ) {
            return false;
        }
    }

    /**
     * Axis aligned bounding tetrahedron.  A tetrahedral volume in tetrahedral coordinates
     *
     * @param originX
     * @param originY
     * @param originZ
     * @param extentX
     * @param extentY
     * @param extentZ
     */
    record aabt(int originX, int originY, int originZ, int extentX, int extentY, int extentZ) implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            return false;
        }

        @Override
        public boolean intersects(int originX, int originY, int originZ, int extentX, int extentY, int extentZ) {
            return !(this.extentX < originX || this.originX > extentX || this.extentY < originY
                     || this.originY > extentY || this.extentZ < originZ || this.originZ > extentZ);
        }
    }
}
