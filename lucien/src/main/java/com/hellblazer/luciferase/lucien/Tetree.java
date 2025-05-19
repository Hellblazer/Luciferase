package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.geometry.Geometry;

import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
import javax.vecmath.Vector3d;
import java.lang.reflect.Array;
import java.util.*;
import java.util.stream.Stream;

import static com.hellblazer.luciferase.lucien.Constants.SIMPLEX_STANDARD;

/**
 * Recursive subdivision of a tetrahedron.
 * <p>
 * <img src="reference-simplexes.png" alt="reference simplexes">
 * </p>
 *
 * @author hal.hildebrand
 **/
public class Tetree<Content> {

    public final static byte[][] TYPE_TRAVERSALS;

    static {
        TYPE_TRAVERSALS = new Permutations<>(new byte[][] { { 0, 1, 2, 3, 4, 5 } }).next();
    }

    private final NavigableMap<Long, Content> contents;

    public Tetree(NavigableMap<Long, Content> contents) {
        this.contents = contents;
    }

    /**
     * @param volume - the enclosing volume
     * @return the Stream of simplexes bounded by the volume
     */
    public Stream<Simplex<Content>> boundedBy(Spatial volume) {
        return Stream.empty();
    }

    /**
     * @param volume the volume to contain
     * @return the Stream of simplexes that minimally bound the volume
     */
    public Stream<Simplex<Content>> bounding(Spatial volume) {
        return Stream.empty();
    }

    /**
     * @param volume - the volume to enclose
     * @return - minimum Simplex enclosing the volume
     */
    public Simplex<Content> enclosing(Spatial volume) {
        return null;
    }

    /**
     * @param point - the point to enclose
     * @param level - refinement level for enclosure
     * @return the simplex at the provided
     */
    public Simplex<Content> enclosing(Tuple3i point, byte level) {
        return null;
    }

    /**
     * @param linearIndex - the index in the space filling curve
     * @return the Content at the linear index
     */
    public Content get(int linearIndex) {
        return contents.get(linearIndex);
    }

    /**
     * @param point   - point in the interior of the S0 tetrahedron
     * @param level   - refinement level
     * @param content - content to store
     * @return the tetrahedral SFC index for this content
     */
    public long insert(Tuple3f point, byte level, Content content) {
        var index = locate(point, level).index();
        contents.put(index, content);
        return index;
    }

    public Simplex<Content> intersecting(Spatial volume) {
        return null;
    }

    public Tet locate(Tuple3f point, byte level) {
        var length = Constants.lengthAtLevel(level);
        var c0 = new Point3i((int) (Math.floor(point.x / length) * length),
                             (int) (Math.floor(point.y / length) * length),
                             (int) (Math.floor(point.z / length) * length));
        var c7 = new Point3i(c0.x + length, c0.y + length, c0.z + length);

        var c1 = new Point3i(c0.x + length, c0.y, c0.z);

        if (Geometry.leftOfPlaneFast(c0.x, c0.y, c0.z, c7.x, c7.y, c7.z, c1.x, c1.x, c1.z, point.x, point.y, point.z)
        > 0.0) {
            var c5 = new Point3i(c0.x + length, c0.y + length, c0.y + length);
            if (Geometry.leftOfPlaneFast(c7.x, c7.y, c7.z, c5.x, c5.y, c5.z, c0.x, c0.x, c0.z, point.x, point.y,
                                         point.z) > 0.0) {
                var c4 = new Point3i(c0.x, c0.y, c0.z + length);
                if (Geometry.leftOfPlaneFast(c7.x, c7.y, c7.z, c4.x, c4.y, c4.z, c1.x, c1.x, c1.z, point.x, point.y,
                                             point.z) > 0.0) {
                    return new Tet(c0, level, 4);
                }
                return new Tet(c0, level, 5);
            } else {
                return new Tet(c0, level, 0);
            }
        } else {
            var c3 = new Point3i(c0.x + length, c0.y + length, c0.z);
            if (Geometry.leftOfPlaneFast(c7.x, c7.y, c7.z, c0.x, c0.y, c0.z, c3.x, c3.x, c3.z, point.x, point.y,
                                         point.z) > 0.0) {
                var c2 = new Point3i(c0.x, c0.y + length, c0.z);
                if (Geometry.leftOfPlaneFast(c7.x, c7.y, c7.z, c0.x, c0.y, c0.z, c2.x, c2.x, c2.z, point.x, point.y,
                                             point.z) > 0.0) {
                    return new Tet(c0, level, 2);
                } else {
                    return new Tet(c0, level, 3);
                }
            } else {
                return new Tet(c0, level, 1);
            }
        }
    }

    static class Permutations<E> implements Iterator<E[]> {

        private final E[]     arr;
        private final int[]   ind;
        public        E[]     output;//next() returns this array, make it public
        private       boolean has_next;

        Permutations(E[] arr) {
            this.arr = arr.clone();
            ind = new int[arr.length];
            //convert an array of any elements into array of integers - first occurrence is used to enumerate
            Map<E, Integer> hm = new HashMap<E, Integer>();
            for (int i = 0; i < arr.length; i++) {
                Integer n = hm.get(arr[i]);
                if (n == null) {
                    hm.put(arr[i], i);
                    n = i;
                }
                ind[i] = n.intValue();
            }
            Arrays.sort(ind);//start with ascending sequence of integers

            //output = new E[arr.length]; <-- cannot do in Java with generics, so use reflection
            output = (E[]) Array.newInstance(arr.getClass().getComponentType(), arr.length);
            has_next = true;
        }

        public boolean hasNext() {
            return has_next;
        }

        /**
         * Computes next permutations. Same array instance is returned every time!
         *
         * @return the next permutation
         */
        public E[] next() {
            if (!has_next) {
                throw new NoSuchElementException();
            }

            for (int i = 0; i < ind.length; i++) {
                output[i] = arr[ind[i]];
            }

            //get next permutation
            has_next = false;
            for (int tail = ind.length - 1; tail > 0; tail--) {
                if (ind[tail - 1] < ind[tail]) {//still increasing

                    //find last element which does not exceed ind[tail-1]
                    int s = ind.length - 1;
                    while (ind[tail - 1] >= ind[s])
                        s--;

                    swap(ind, tail - 1, s);

                    //reverse order of elements in the tail
                    for (int i = tail, j = ind.length - 1; i < j; i++, j--) {
                        swap(ind, i, j);
                    }
                    has_next = true;
                    break;
                }

            }
            return output;
        }

        public void remove() {

        }

        private void swap(int[] arr, int i, int j) {
            int t = arr[i];
            arr[i] = arr[j];
            arr[j] = t;
        }
    }

    public record Simplex<Data>(long index, Data cell) implements Spatial {
        @Override
        public boolean containedBy(aabt aabt) {
            return false;
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
            return false;
        }

        public Vector3d[] vertices() {
            var tet = Tet.tetrahedron(index);
            var length = tet.length();
            var vertices = new Vector3d[4];
            var i = 0;
            for (var vertex : SIMPLEX_STANDARD[tet.type()]) {
                vertices[i] = new Vector3d(vertex.x, vertex.y, vertex.z);
                vertices[i].scale(length);
                i++;
            }
            return vertices;
        }
    }
}
