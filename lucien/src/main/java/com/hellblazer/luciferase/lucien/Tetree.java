package com.hellblazer.luciferase.lucien;

import javax.vecmath.Tuple3i;
import javax.vecmath.Vector3d;
import java.util.stream.Stream;

/**
 * A recursive spatial data structure based on the red refinement of a tetrahedral volume
 */
public interface Tetree {

    /** The Tetrahedrons in Bey's order */
    public static final Vector3d[][] SIMPLEX = new Vector3d[][] {
    { CORNER.c0.coords(), CORNER.c1.coords(), CORNER.c5.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c7.coords(), CORNER.c3.coords(), CORNER.c1.coords() },
    { CORNER.c0.coords(), CORNER.c2.coords(), CORNER.c3.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c7.coords(), CORNER.c6.coords(), CORNER.c2.coords() },
    { CORNER.c0.coords(), CORNER.c4.coords(), CORNER.c6.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c7.coords(), CORNER.c5.coords(), CORNER.c4.coords() } };

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

    // The corners of a cube
    public enum CORNER {
        c0 {
            @Override
            public Vector3d coords() {
                return new Vector3d(0, 0, 0);
            }
        }, c1 {
            @Override
            public Vector3d coords() {
                return new Vector3d(1, 0, 0);
            }
        }, c2 {
            @Override
            public Vector3d coords() {
                return new Vector3d(0, 1, 0);
            }
        }, c3 {
            @Override
            public Vector3d coords() {
                return new Vector3d(1, 1, 0);
            }
        }, c4 {
            @Override
            public Vector3d coords() {
                return new Vector3d(0, 0, 1);
            }
        }, c5 {
            @Override
            public Vector3d coords() {
                return new Vector3d(1, 0, 1);
            }
        }, c6 {
            @Override
            public Vector3d coords() {
                return new Vector3d(0, 1, 1);
            }
        }, c7 {
            @Override
            public Vector3d coords() {
                return new Vector3d(1, 1, 1);
            }
        };

        abstract public Vector3d coords();
    }

    public record Simplex<Data>(long index, Data cell) implements Spatial {
        @Override
        public boolean containedBy(aabt aabt) {
            return false;
        }

        @Override
        public boolean intersects(int originX, int originY, int originZ, int extentX, int extentY, int extentZ) {
            return false;
        }

        public Vector3d[] vertices() {
            var tet = Tet.tetrahedron(index, (byte) 5);
            var simplex = SIMPLEX[tet.type()];
            for (int i = 0; i < simplex.length; i++) {
                simplex[i] = new Vector3d(simplex[i].x, simplex[i].y, simplex[i].z);
                simplex[i].scale(tet.length());
            }
            return new Vector3d[] { simplex[0], simplex[1], simplex[2], simplex[3] };
        }
    }
}
