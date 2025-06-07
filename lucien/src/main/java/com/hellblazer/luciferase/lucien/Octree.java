package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.geometry.MortonCurve;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3i;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Octree implementation using HashMap for node storage (like C++ reference) Provides O(1) node lookups while
 * maintaining spatial structure
 *
 * @author hal.hildebrand
 */
public class Octree<Content> {
    // Main storage - NavigableMap for spatial range queries
    // Morton code is the key - it encodes both position and level
    private final NavigableMap<Long, Node<Content>> nodes;

    public Octree() {
        this.nodes = new TreeMap<>();
    }

    public static Spatial.Cube toCube(long index) {
        var point = MortonCurve.decode(index);
        byte level = Constants.toLevel(index);
        return new Spatial.Cube(point[0], point[1], point[2], Constants.lengthAtLevel(level));
    }

    public Stream<Hexahedron<Content>> boundedBy(Spatial volume) {
        return spatialRangeQuery(volume, false);
    }

    public Stream<Hexahedron<Content>> bounding(Spatial volume) {
        return spatialRangeQuery(volume, true);
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

        var node = nodes.get(index);
        var content = node != null ? node.getData() : null;
        return new Hexahedron<>(index, content);
    }

    /**
     * @param point - the point to enclose
     * @param level - refinement level for enclosure
     * @return the cube at the provided level
     */
    public Hexahedron<Content> enclosing(Tuple3i point, byte level) {
        var length = Constants.lengthAtLevel(level);
        var index = MortonCurve.encode((point.x / length) * length, (point.y / length) * length,
                                       (point.z / length) * length);

        var node = nodes.get(index);
        var content = node != null ? node.getData() : null;
        return new Hexahedron<>(index, content);
    }

    /**
     * Get the content stored at the given Morton index
     *
     * @param index the Morton index
     * @return the content data, or null if not found
     */
    public Content get(long index) {
        Node<Content> node = nodes.get(index);
        return node != null ? node.getData() : null;
    }

    /**
     * Get access to the internal map for advanced operations
     *
     * @return a NavigableMap view that exposes node data directly
     */
    public NavigableMap<Long, Content> getMap() {
        return new NodeDataMap<>(nodes);
    }

    public NavigableMap<Long, Content> getNodes() {
        return new NodeDataMap<>(nodes);
    }

    /**
     * Get stats about the octree
     */
    public OctreeStats getStats() {
        int totalEntities = nodes.size();
        return new OctreeStats(nodes.size(), totalEntities);
    }

    /**
     * Check if node exists - O(1)
     */
    public boolean hasNode(long mortonKey) {
        return nodes.containsKey(mortonKey);
    }

    public long insert(Point3f point, byte level, Content value) {
        var length = Constants.lengthAtLevel(level);
        var entityId = MortonCurve.encode((int) (Math.floor(point.x / length) * length),
                                          (int) (Math.floor(point.y / length) * length),
                                          (int) (Math.floor(point.z / length) * length));

        // Insert into node structure
        insertIntoNodeStructure(entityId, value, point, level);

        return entityId;
    }

    /**
     * Convenience method to get cube from index
     */
    public Spatial.Cube locate(long index) {
        return toCube(index);
    }

    public int size() {
        return nodes.size();
    }

    /**
     * Spatial range query
     */
    public Stream<Hexahedron<Content>> spatialRangeQuery(Spatial volume, boolean includeIntersecting) {
        List<Hexahedron<Content>> results = new ArrayList<>();
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return results.stream();
        }

        // Check all nodes (could be optimized with spatial indexing)
        for (var entry : nodes.entrySet()) {
            long nodeKey = entry.getKey();
            var node = entry.getValue();

            var cube = toCube(nodeKey);
            boolean matches = includeIntersecting ? cubeIntersectsBounds(cube, bounds) : cubeContainedInBounds(cube,
                                                                                                               bounds);
            if (matches) {
                results.add(new Hexahedron<>(nodeKey, node.getData()));
            }
        }

        return results.stream();
    }

    private boolean cubeContainedInBounds(Spatial.Cube cube, VolumeBounds bounds) {
        return cube.originX() >= bounds.minX && cube.originX() + cube.extent() <= bounds.maxX
        && cube.originY() >= bounds.minY && cube.originY() + cube.extent() <= bounds.maxY
        && cube.originZ() >= bounds.minZ && cube.originZ() + cube.extent() <= bounds.maxZ;
    }

    // Helper methods

    private boolean cubeIntersectsBounds(Spatial.Cube cube, VolumeBounds bounds) {
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

    private void insertIntoNodeStructure(long mortonIndex, Content value, Point3f point, byte targetDepth) {
        // Use the passed Morton index directly
        Node<Content> node = nodes.get(mortonIndex);
        if (node == null) {
            node = new Node<>(value);
            nodes.put(mortonIndex, node);
        } else {
            // Update existing node with new value
            node.setData(value);
        }
    }

    /**
     * Node structure matching C++ reference - stores only entity IDs
     */
    public static class Node<Content> {

        private byte    childrenMask = 0; // Bit mask for existing children
        private Content data;

        public Node() {
        }

        public Node(Content data) {
            this.data = data;
        }

        // Getters
        public byte getChildrenMask() {
            return childrenMask;
        }

        public Content getData() {
            return data;
        }

        public boolean hasChild(int octant) {
            return (childrenMask & (1 << octant)) != 0;
        }

        public boolean hasChildren() {
            return childrenMask != 0;
        }

        public void removeChild(int octant) {
            childrenMask &= ~(1 << octant);
        }

        public void setChild(int octant) {
            childrenMask |= (1 << octant);
        }

        public void setData(Content data) {
            this.data = data;
        }
    }

    public record OctreeStats(int totalNodes, int totalEntities) {
    }

    record Hexahedron<Data>(long index, Data cell) {
        public Spatial.Cube toCube() {
            return Octree.toCube(index);
        }
    }

    private record VolumeBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    }
}
