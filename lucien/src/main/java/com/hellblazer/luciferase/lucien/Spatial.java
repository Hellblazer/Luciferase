package com.hellblazer.luciferase.lucien;

import javax.vecmath.Tuple3f;

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
    boolean intersects(float originX, float originY, float originZ, float extentX, float extentY, float extentZ);

    default boolean intersects(aabt aabp) {
        return intersects(aabp.originX, aabp.originY, aabp.originZ, aabp.extentX, aabp.extentY, aabp.extentZ);
    }

    record Cube(float originX, float originY, float originZ, float extent) implements Spatial {
        @Override
        public boolean containedBy(aabt aabt) {
            return false;
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
            return false;
        }
    }

    record Sphere(float centerX, float centerY, float centerZ, float radius) implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            return false;
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
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
    record Parallelepiped(float originX, float originY, float originZ, float extentX, float extentY, float extentZ)
    implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            return false;
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
            return false;
        }
    }

    record Tetrahedron(Tuple3f a, Tuple3f b, Tuple3f c, Tuple3f d) implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            return false;
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
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
    record aabt(float originX, float originY, float originZ, float extentX, float extentY, float extentZ)
    implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            return false;
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
            return !(this.extentX < originX || this.originX > extentX || this.extentY < originY
                     || this.originY > extentY || this.extentZ < originZ || this.originZ > extentZ);
        }
    }
}
