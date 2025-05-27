package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.geometry.Geometry;

import javax.vecmath.*;
import java.lang.reflect.Array;
import java.util.*;
import java.util.stream.Stream;

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
        // Use spatial range query to efficiently find tetrahedra within volume
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        return spatialRangeQuery(bounds, false).filter(entry -> {
            var tet = Tet.tetrahedron(entry.getKey());
            return tetrahedronContainedInVolume(tet, volume);
        }).map(entry -> new Simplex<>(entry.getKey(), entry.getValue()));
    }

    /**
     * @param volume the volume to contain
     * @return the Stream of simplexes that minimally bound the volume
     */
    public Stream<Simplex<Content>> bounding(Spatial volume) {
        // Use spatial range query to efficiently find tetrahedra intersecting volume
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        return spatialRangeQuery(bounds, true).filter(entry -> {
            var tet = Tet.tetrahedron(entry.getKey());
            return tetrahedronIntersectsVolume(tet, volume);
        }).map(entry -> new Simplex<>(entry.getKey(), entry.getValue()));
    }

    /**
     * @param volume - the volume to enclose
     * @return - minimum Simplex enclosing the volume
     */
    public Simplex<Content> enclosing(Spatial volume) {
        // Extract bounding box of the volume
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return null;
        }

        // Find the minimum level that can contain the volume
        byte level = findMinimumContainingLevel(bounds);

        // Find a tetrahedron at that level that contains the volume
        var centerPoint = new Point3f((bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2,
                                      (bounds.minZ + bounds.maxZ) / 2);

        var tet = locate(centerPoint, level);
        var content = contents.get(tet.index());
        return new Simplex<>(tet.index(), content);
    }

    /**
     * @param point - the point to enclose
     * @param level - refinement level for enclosure
     * @return the simplex at the provided
     */
    public Simplex<Content> enclosing(Tuple3i point, byte level) {
        var tet = locate(new Point3f(point.x, point.y, point.z), level);
        var content = contents.get(tet.index());
        return new Simplex<>(tet.index(), content);
    }

    /**
     * @param linearIndex - the index in the space filling curve
     * @return the Content at the linear index
     */
    public Content get(long linearIndex) {
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
        // For now, use a simple approach: find the enclosing tetrahedron
        // TODO: Implement proper intersection testing for more precise results
        return enclosing(volume);
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

    // Compute SFC ranges for all tetrahedra in a grid cell
    private List<SFCRange> computeCellSFCRanges(Point3f cellOrigin, byte level) {
        List<SFCRange> ranges = new ArrayList<>();

        // For a grid cell, there can be multiple tetrahedra (6 types)
        // Find the SFC indices for all tetrahedron types at this location
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet((int) cellOrigin.x, (int) cellOrigin.y, (int) cellOrigin.z, level, type);
            long index = tet.index();
            ranges.add(new SFCRange(index, index));
        }

        return ranges;
    }

    // Compute SFC ranges that could contain tetrahedra intersecting the volume
    private List<SFCRange> computeSFCRanges(VolumeBounds bounds, boolean includeIntersecting) {
        List<SFCRange> ranges = new ArrayList<>();

        // Find appropriate refinement levels for the query volume
        byte minLevel = (byte) Math.max(0, findMinimumContainingLevel(bounds) - 2);
        byte maxLevel = (byte) Math.min(Constants.getMaxRefinementLevel(), findMinimumContainingLevel(bounds) + 3);

        for (byte level = minLevel; level <= maxLevel; level++) {
            int length = Constants.lengthAtLevel(level);

            // Calculate grid bounds at this level
            int minX = (int) Math.floor(bounds.minX / length);
            int maxX = (int) Math.ceil(bounds.maxX / length);
            int minY = (int) Math.floor(bounds.minY / length);
            int maxY = (int) Math.ceil(bounds.maxY / length);
            int minZ = (int) Math.floor(bounds.minZ / length);
            int maxZ = (int) Math.ceil(bounds.maxZ / length);

            // Find SFC ranges for grid cells that could intersect the volume
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Point3f cellPoint = new Point3f(x * length, y * length, z * length);

                        // Check if this grid cell could intersect our bounds
                        if (gridCellIntersectsBounds(cellPoint, length, bounds, includeIntersecting)) {
                            // Find the SFC range for all tetrahedra in this grid cell
                            var cellRanges = computeCellSFCRanges(cellPoint, level);
                            ranges.addAll(cellRanges);
                        }
                    }
                }
            }
        }

        // Merge overlapping ranges for efficiency
        return mergeRanges(ranges);
    }

    // Create a spatial volume from bounds for final filtering
    private Spatial createSpatialFromBounds(VolumeBounds bounds) {
        return new Spatial.aabb(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    // Find minimum level that can contain the volume
    private byte findMinimumContainingLevel(VolumeBounds bounds) {
        float maxExtent = Math.max(Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY),
                                   bounds.maxZ - bounds.minZ);

        // Find the level where tetrahedron length >= maxExtent
        for (byte level = 0; level <= Constants.getMaxRefinementLevel(); level++) {
            if (Constants.lengthAtLevel(level) >= maxExtent) {
                return level;
            }
        }
        return Constants.getMaxRefinementLevel();
    }

    // Get bounding box of a tetrahedron for quick filtering
    private VolumeBounds getTetrahedronBounds(Tet tet) {
        var vertices = tet.coordinates();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;

        for (var vertex : vertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }

        return new VolumeBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // Extract bounding box from various spatial volume types
    private VolumeBounds getVolumeBounds(Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube -> new VolumeBounds(cube.originX(), cube.originY(), cube.originZ(),
                                                       cube.originX() + cube.extent(), cube.originY() + cube.extent(),
                                                       cube.originZ() + cube.extent());
            case Spatial.Sphere sphere -> new VolumeBounds(sphere.centerX() - sphere.radius(),
                                                           sphere.centerY() - sphere.radius(),
                                                           sphere.centerZ() - sphere.radius(),
                                                           sphere.centerX() + sphere.radius(),
                                                           sphere.centerY() + sphere.radius(),
                                                           sphere.centerZ() + sphere.radius());
            case Spatial.aabb aabb -> new VolumeBounds(aabb.originX(), aabb.originY(), aabb.originZ(), aabb.extentX(),
                                                       aabb.extentY(), aabb.extentZ());
            case Spatial.aabt aabt -> new VolumeBounds(aabt.originX(), aabt.originY(), aabt.originZ(), aabt.extentX(),
                                                       aabt.extentY(), aabt.extentZ());
            case Spatial.Parallelepiped para -> new VolumeBounds(para.originX(), para.originY(), para.originZ(),
                                                                 para.extentX(), para.extentY(), para.extentZ());
            case Spatial.Tetrahedron tet -> {
                var vertices = new Tuple3f[] { tet.a(), tet.b(), tet.c(), tet.d() };
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
                for (var vertex : vertices) {
                    minX = Math.min(minX, vertex.x);
                    minY = Math.min(minY, vertex.y);
                    minZ = Math.min(minZ, vertex.z);
                    maxX = Math.max(maxX, vertex.x);
                    maxY = Math.max(maxY, vertex.y);
                    maxZ = Math.max(maxZ, vertex.z);
                }
                yield new VolumeBounds(minX, minY, minZ, maxX, maxY, maxZ);
            }
            default -> null;
        };
    }

    // Check if a grid cell intersects with the query bounds
    private boolean gridCellIntersectsBounds(Point3f cellOrigin, int cellSize, VolumeBounds bounds,
                                             boolean includeIntersecting) {
        float cellMaxX = cellOrigin.x + cellSize;
        float cellMaxY = cellOrigin.y + cellSize;
        float cellMaxZ = cellOrigin.z + cellSize;

        if (includeIntersecting) {
            // Check for any intersection
            return !(cellMaxX < bounds.minX || cellOrigin.x > bounds.maxX || cellMaxY < bounds.minY
                     || cellOrigin.y > bounds.maxY || cellMaxZ < bounds.minZ || cellOrigin.z > bounds.maxZ);
        } else {
            // Check for complete containment within bounds
            return cellOrigin.x >= bounds.minX && cellMaxX <= bounds.maxX && cellOrigin.y >= bounds.minY
            && cellMaxY <= bounds.maxY && cellOrigin.z >= bounds.minZ && cellMaxZ <= bounds.maxZ;
        }
    }

    // Merge overlapping SFC ranges for efficiency
    private List<SFCRange> mergeRanges(List<SFCRange> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }

        ranges.sort((a, b) -> Long.compare(a.start, b.start));
        List<SFCRange> merged = new ArrayList<>();
        SFCRange current = ranges.get(0);

        for (int i = 1; i < ranges.size(); i++) {
            SFCRange next = ranges.get(i);
            if (current.end + 1 >= next.start) {
                // Merge overlapping ranges
                current = new SFCRange(current.start, Math.max(current.end, next.end));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        return merged;
    }

    // Efficient spatial range query using tetrahedral space-filling curve properties
    private Stream<Map.Entry<Long, Content>> spatialRangeQuery(VolumeBounds bounds, boolean includeIntersecting) {
        // Use SFC properties to find ranges of indices that could intersect the volume
        var sfcRanges = computeSFCRanges(bounds, includeIntersecting);

        return sfcRanges.stream().flatMap(range -> {
            // Use NavigableMap.subMap to efficiently get entries in SFC range
            var subMap = contents.subMap(range.start, true, range.end, true);
            return subMap.entrySet().stream();
        }).filter(entry -> {
            // Final precise filtering for elements that passed SFC range test
            var tet = Tet.tetrahedron(entry.getKey());
            if (includeIntersecting) {
                return tetrahedronIntersectsVolume(tet, createSpatialFromBounds(bounds));
            } else {
                return tetrahedronContainedInVolume(tet, createSpatialFromBounds(bounds));
            }
        });
    }

    // Check if a tetrahedron is completely contained within a volume
    private boolean tetrahedronContainedInVolume(Tet tet, Spatial volume) {
        var vertices = tet.coordinates();
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return false;
        }

        // Simple AABB containment test - all vertices must be within bounds
        for (var vertex : vertices) {
            if (vertex.x < bounds.minX || vertex.x > bounds.maxX || vertex.y < bounds.minY || vertex.y > bounds.maxY
            || vertex.z < bounds.minZ || vertex.z > bounds.maxZ) {
                return false;
            }
        }
        return true;
    }

    // Check if a tetrahedron intersects with a volume
    private boolean tetrahedronIntersectsVolume(Tet tet, Spatial volume) {
        var vertices = tet.coordinates();
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return false;
        }

        // Simple AABB intersection test - any vertex within bounds indicates intersection
        for (var vertex : vertices) {
            if (vertex.x >= bounds.minX && vertex.x <= bounds.maxX && vertex.y >= bounds.minY && vertex.y <= bounds.maxY
            && vertex.z >= bounds.minZ && vertex.z <= bounds.maxZ) {
                return true;
            }
        }

        // Also check if the volume center is inside the tetrahedron
        var centerPoint = new Point3f((bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2,
                                      (bounds.minZ + bounds.maxZ) / 2);
        return tet.contains(centerPoint);
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
            var coords = tet.coordinates();
            var vertices = new Vector3d[4];
            for (int i = 0; i < 4; i++) {
                vertices[i] = new Vector3d(coords[i].x, coords[i].y, coords[i].z);
            }
            return vertices;
        }
    }

    // Helper record for volume bounds
    private record VolumeBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    }

    // Record to represent SFC index ranges
    private record SFCRange(long start, long end) {
    }
}
