package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.geometry.MortonCurve;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3i;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.stream.Stream;

/**
 * @author hal.hildebrand
 **/
public class Octree<Content> {

    private final NavigableMap<Long, Content> map;

    public Octree(NavigableMap map) {
        this.map = map;
    }

    public static Spatial.Cube toCube(long index) {
        var point = MortonCurve.decode(index);
        return new Spatial.Cube(point[0], point[1], point[2], Constants.lengthAtLevel(Constants.toLevel(index)));
    }

    /**
     * @param volume - the enclosing volume
     * @return the Stream of simplexes bounded by the volume
     */
    public Stream<Hexahedron<Content>> boundedBy(Spatial volume) {
        // Use spatial range query to efficiently find cubes within volume
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        return spatialRangeQuery(bounds, false).filter(entry -> {
            var cube = toCube(entry.getKey());
            return cubeContainedInVolume(cube, volume);
        }).map(entry -> new Hexahedron<>(entry.getKey(), entry.getValue()));
    }

    /**
     * @param volume the volume to contain
     * @return the Stream of simplexes that minimally bound the volume
     */
    public Stream<Hexahedron<Content>> bounding(Spatial volume) {
        // Use spatial range query to efficiently find cubes intersecting volume
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        return spatialRangeQuery(bounds, true).filter(entry -> {
            var cube = toCube(entry.getKey());
            return cubeIntersectsVolume(cube, volume);
        }).map(entry -> new Hexahedron<>(entry.getKey(), entry.getValue()));
    }

    /**
     * @param volume - the volume to enclose
     * @return - minimum cube enclosing the volume
     */
    public Hexahedron<Content> enclosing(Spatial volume) {
        // Extract bounding box of the volume
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return null;
        }

        // Find the minimum level that can contain the volume
        byte level = findMinimumContainingLevel(bounds);

        // Find a cube at that level that contains the volume
        var centerPoint = new Point3f((bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2,
                                      (bounds.minZ + bounds.maxZ) / 2);

        var length = Constants.lengthAtLevel(level);
        var index = MortonCurve.encode((int) (Math.floor(centerPoint.x / length) * length),
                                       (int) (Math.floor(centerPoint.y / length) * length),
                                       (int) (Math.floor(centerPoint.z / length) * length));
        var content = map.get(index);
        return new Hexahedron<>(index, content);
    }

    /**
     * @param point - the point to enclose
     * @param level - refinement level for enclosure
     * @return the cube at the provided
     */
    public Hexahedron<Content> enclosing(Tuple3i point, byte level) {
        var length = Constants.lengthAtLevel(level);
        var index = MortonCurve.encode((int) (Math.floor(point.x / length) * length),
                                       (int) (Math.floor(point.y / length) * length),
                                       (int) (Math.floor(point.z / length) * length));
        var content = map.get(index);
        return new Hexahedron<>(index, content);
    }

    public Content get(long key) {
        return map.get(key);
    }

    public long insert(Point3f point, byte level, Content value) {
        var length = Constants.lengthAtLevel(level);
        var index = MortonCurve.encode((int) (Math.floor(point.x / length) * length),
                                       (int) (Math.floor(point.y / length) * length),
                                       (int) (Math.floor(point.z / length) * length));
        map.put(index, value);
        return index;
    }

    public Spatial.Cube locate(long index) {
        return Octree.toCube(index);
    }

    // Compute Morton ranges that could contain cubes intersecting the volume
    private List<MortonRange> computeMortonRanges(VolumeBounds bounds, boolean includeIntersecting) {
        List<MortonRange> ranges = new ArrayList<>();

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

            // Find Morton ranges for grid cells that could intersect the volume
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        // Align to grid
                        int gridX = x * length;
                        int gridY = y * length;
                        int gridZ = z * length;

                        // Check if this grid cell could intersect our bounds
                        if (gridCellIntersectsBounds(gridX, gridY, gridZ, length, bounds, includeIntersecting)) {
                            // Encode using Morton curve to get the index
                            long index = MortonCurve.encode(gridX, gridY, gridZ);
                            ranges.add(new MortonRange(index, index));
                        }
                    }
                }
            }
        }

        // Merge overlapping ranges for efficiency
        return mergeMortonRanges(ranges);
    }

    // Create a spatial volume from bounds for final filtering
    private Spatial createSpatialFromBounds(VolumeBounds bounds) {
        return new Spatial.aabb(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    // Check if a cube is completely contained within a volume
    private boolean cubeContainedInVolume(Spatial.Cube cube, Spatial volume) {
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return false;
        }

        // All cube corners must be within volume bounds
        return cube.originX() >= bounds.minX && cube.originX() + cube.extent() <= bounds.maxX
        && cube.originY() >= bounds.minY && cube.originY() + cube.extent() <= bounds.maxY
        && cube.originZ() >= bounds.minZ && cube.originZ() + cube.extent() <= bounds.maxZ;
    }

    // Check if a cube intersects with a volume
    private boolean cubeIntersectsVolume(Spatial.Cube cube, Spatial volume) {
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return false;
        }

        // AABB intersection test
        return !(cube.originX() + cube.extent() < bounds.minX || cube.originX() > bounds.maxX
                 || cube.originY() + cube.extent() < bounds.minY || cube.originY() > bounds.maxY
                 || cube.originZ() + cube.extent() < bounds.minZ || cube.originZ() > bounds.maxZ);
    }

    // Find minimum level that can contain the volume
    private byte findMinimumContainingLevel(VolumeBounds bounds) {
        float maxExtent = Math.max(Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY),
                                   bounds.maxZ - bounds.minZ);

        // Find the level where cube length >= maxExtent
        for (byte level = 0; level <= Constants.getMaxRefinementLevel(); level++) {
            if (Constants.lengthAtLevel(level) >= maxExtent) {
                return level;
            }
        }
        return Constants.getMaxRefinementLevel();
    }

    // Extract bounding box from various spatial volume types (reused from Tetree)
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
                var vertices = new javax.vecmath.Tuple3f[] { tet.a(), tet.b(), tet.c(), tet.d() };
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
    private boolean gridCellIntersectsBounds(int cellX, int cellY, int cellZ, int cellSize, VolumeBounds bounds,
                                             boolean includeIntersecting) {
        float cellMaxX = cellX + cellSize;
        float cellMaxY = cellY + cellSize;
        float cellMaxZ = cellZ + cellSize;

        if (includeIntersecting) {
            // Check for any intersection
            return !(cellMaxX < bounds.minX || cellX > bounds.maxX || cellMaxY < bounds.minY || cellY > bounds.maxY
                     || cellMaxZ < bounds.minZ || cellZ > bounds.maxZ);
        } else {
            // Check for complete containment within bounds
            return cellX >= bounds.minX && cellMaxX <= bounds.maxX && cellY >= bounds.minY && cellMaxY <= bounds.maxY
            && cellZ >= bounds.minZ && cellMaxZ <= bounds.maxZ;
        }
    }

    // Merge overlapping Morton ranges for efficiency
    private List<MortonRange> mergeMortonRanges(List<MortonRange> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }

        ranges.sort((a, b) -> Long.compare(a.start, b.start));
        List<MortonRange> merged = new ArrayList<>();
        MortonRange current = ranges.get(0);

        for (int i = 1; i < ranges.size(); i++) {
            MortonRange next = ranges.get(i);
            if (current.end + 1 >= next.start) {
                // Merge overlapping ranges
                current = new MortonRange(current.start, Math.max(current.end, next.end));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        return merged;
    }

    // Efficient spatial range query using Morton curve properties
    private Stream<Map.Entry<Long, Content>> spatialRangeQuery(VolumeBounds bounds, boolean includeIntersecting) {
        // Use Morton curve properties to find ranges of indices that could intersect the volume
        var mortonRanges = computeMortonRanges(bounds, includeIntersecting);

        Stream<Map.Entry<Long, Content>> ranges = mortonRanges.stream().flatMap(range -> {
            // Use NavigableMap.subMap to efficiently get entries in Morton range
            var subMap = map.subMap(range.start, true, range.end, true);
            return subMap.entrySet().stream();
        });
        return ranges.filter(entry -> {
            // Final precise filtering for elements that passed Morton range test
            var cube = toCube(entry.getKey());
            if (includeIntersecting) {
                return cubeIntersectsVolume(cube, createSpatialFromBounds(bounds));
            } else {
                return cubeContainedInVolume(cube, createSpatialFromBounds(bounds));
            }
        });
    }

    record Hexahedron<Data>(long index, Data cell) {
        public Spatial.Cube toCube() {
            return Octree.toCube(index);
        }
    }

    // Helper record for volume bounds (reused from Tetree)
    private record VolumeBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    }

    // Record to represent Morton index ranges
    private record MortonRange(long start, long end) {
    }
}
