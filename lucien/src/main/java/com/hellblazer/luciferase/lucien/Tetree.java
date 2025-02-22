package com.hellblazer.luciferase.lucien;

import javax.vecmath.Tuple3i;
import javax.vecmath.Vector3d;
import java.util.stream.Stream;

/**
 * A recursive spatial data structure based on the red refinement of a tetrahedral volume
 */
public interface Tetree<Content> {

    /**
     * @param volume - the enclosing volume
     * @return the Stream of simplexes bounded by the volume
     */
    Stream<Simplex<Content>> boundedBy(Spatial volume);

    /**
     * @param volume the volume to contain
     * @return the Stream of simplexes that minimally bound the volume
     */
    Stream<Simplex<Content>> bounding(Spatial volume);

    /**
     * @param volume - the volume to enclose
     * @return - minimum Simplex enclosing the volume
     */
    Simplex<Content> enclosing(Spatial volume);

    /**
     * @param point - the point to enclose
     * @param level - refinement level for enclosure
     * @return the simplex at the provided
     */
    Simplex<Content> enclosing(Tuple3i point, byte level);

    /**
     * @param linearIndex - the index in the space filling curve
     * @return the Simplex at the linear index
     */
    Simplex<Content> get(int linearIndex);

    Simplex<Content> intersecting(Spatial volume);

    record Simplex<Data>(long index, Data cell) implements Spatial {
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
            var vertices = new Vector3d[4];
            var i = 0;
            for (var vertex : TetConstants.SIMPLEX[tet.type()]) {
                vertices[i] = new Vector3d(vertex.x, vertex.y, vertex.z);
                vertices[i].scale(tet.length());
                i++;
            }
            return vertices;
        }
    }
}
