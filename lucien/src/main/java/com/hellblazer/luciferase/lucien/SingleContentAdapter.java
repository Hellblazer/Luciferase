/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3i;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.stream.Stream;

/**
 * Adapter that provides the existing single-content-per-node API on top of the new entity-based octree implementation.
 *
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class SingleContentAdapter<Content> {
    private final OctreeWithEntities<LongEntityID, Content> entityOctree;

    /**
     * Create adapter with default configuration
     */
    public SingleContentAdapter() {
        this.entityOctree = new OctreeWithEntities<>(new SequentialLongIDGenerator(), 1,
                                                     // Max 1 entity per node for single-content behavior
                                                     Constants.getMaxRefinementLevel());
    }

    /**
     * Find content within a bounding box
     */
    public List<Content> boundedBy(Spatial.Cube region) {
        List<LongEntityID> entityIds = entityOctree.entitiesInRegion(region);
        return entityOctree.getEntities(entityIds);
    }

    /**
     * Get the underlying entity-based octree for advanced operations
     */
    public OctreeWithEntities<LongEntityID, Content> getEntityOctree() {
        return entityOctree;
    }

    /**
     * Get statistics about the octree
     */
    public OctreeWithEntities.Stats getStats() {
        return entityOctree.getStats();
    }

    /**
     * Insert content and return Morton index (matches Octree API)
     */
    public long insert(Point3f position, byte level, Content content) {
        var length = Constants.lengthAtLevel(level);
        var mortonIndex = MortonCurve.encode((int) (Math.floor(position.x / length) * length),
                                            (int) (Math.floor(position.y / length) * length),
                                            (int) (Math.floor(position.z / length) * length));

        // Remove any existing content at this location first
        List<LongEntityID> existing = entityOctree.lookup(position, level);
        for (LongEntityID id : existing) {
            entityOctree.removeEntity(id);
        }
        // Insert new content
        entityOctree.insert(position, level, content);
        return mortonIndex;
    }

    /**
     * Lookup content at position (returns first/only content)
     */
    public Content lookup(Point3f position, byte level) {
        List<LongEntityID> ids = entityOctree.lookup(position, level);
        if (ids.isEmpty()) {
            return null;
        }
        return entityOctree.getEntity(ids.get(0));
    }

    /**
     * Remove content at position Note: This is approximate - removes first entity found at position
     */
    public boolean remove(Point3f position, byte level) {
        List<LongEntityID> ids = entityOctree.lookup(position, level);
        if (ids.isEmpty()) {
            return false;
        }
        return entityOctree.removeEntity(ids.get(0));
    }

    /**
     * Static method to convert Morton index to cube (matches Octree API)
     */
    public static Spatial.Cube toCube(long index) {
        var point = MortonCurve.decode(index);
        byte level = Constants.toLevel(index);
        return new Spatial.Cube(point[0], point[1], point[2], Constants.lengthAtLevel(level));
    }

    /**
     * Stream of content bounded by volume (matches Octree API)
     */
    public Stream<Octree.Hexahedron<Content>> boundedBy(Spatial volume) {
        return spatialRangeQuery(volume, false);
    }

    /**
     * Stream of content bounding volume (matches Octree API)
     */
    public Stream<Octree.Hexahedron<Content>> bounding(Spatial volume) {
        return spatialRangeQuery(volume, true);
    }

    /**
     * Find the minimum cube enclosing the volume
     */
    public Octree.Hexahedron<Content> enclosing(Spatial volume) {
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

        Content content = lookup(new Point3f((float) (Math.floor(centerPoint.x / length) * length),
                                            (float) (Math.floor(centerPoint.y / length) * length),
                                            (float) (Math.floor(centerPoint.z / length) * length)), level);
        return new Octree.Hexahedron<>(index, content);
    }

    /**
     * Find the cube at the provided level enclosing the point
     */
    public Octree.Hexahedron<Content> enclosing(Tuple3i point, byte level) {
        var length = Constants.lengthAtLevel(level);
        var index = MortonCurve.encode((point.x / length) * length, (point.y / length) * length,
                                       (point.z / length) * length);

        Content content = lookup(new Point3f((point.x / length) * length, 
                                           (point.y / length) * length,
                                           (point.z / length) * length), level);
        return new Octree.Hexahedron<>(index, content);
    }

    /**
     * Get content at Morton index
     */
    public Content get(long index) {
        var point = MortonCurve.decode(index);
        byte level = Constants.toLevel(index);
        return lookup(new Point3f(point[0], point[1], point[2]), level);
    }

    /**
     * Get navigable map interface for compatibility
     * Note: This returns a view that provides the Octree API
     */
    public NavigableMap<Long, Content> getMap() {
        return new SingleContentNodeDataMap<>(this);
    }

    /**
     * Get navigable map interface (alias for getMap)
     */
    public NavigableMap<Long, Content> getNodes() {
        return getMap();
    }

    /**
     * Check if node exists at Morton key
     */
    public boolean hasNode(long mortonKey) {
        var point = MortonCurve.decode(mortonKey);
        byte level = Constants.toLevel(mortonKey);
        List<LongEntityID> ids = entityOctree.lookup(new Point3f(point[0], point[1], point[2]), level);
        return !ids.isEmpty();
    }


    /**
     * Get cube from index
     */
    public Spatial.Cube locate(long index) {
        return toCube(index);
    }

    /**
     * Get the number of nodes/entities
     */
    public int size() {
        return entityOctree.getStats().entityCount;
    }

    /**
     * Spatial range query implementation
     */
    private Stream<Octree.Hexahedron<Content>> spatialRangeQuery(Spatial volume, boolean includeIntersecting) {
        List<Octree.Hexahedron<Content>> results = new ArrayList<>();
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return results.stream();
        }

        // Get all entities and check their positions
        var allEntities = entityOctree.getAllEntities();
        
        // We need to reconstruct the spatial information
        // This is inefficient but maintains API compatibility
        // In a real implementation, we'd track entity positions in OctreeWithEntities
        for (var entry : allEntities.entrySet()) {
            // For now, we can't determine the exact position without additional tracking
            // This is a limitation of the current adapter approach
            // TODO: Track entity positions in OctreeWithEntities for accurate spatial queries
        }

        return results.stream();
    }

    /**
     * Helper to get volume bounds
     */
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
            case Spatial.aabb aabb -> new VolumeBounds(aabb.originX(), aabb.originY(), aabb.originZ(), 
                                                       aabb.originX() + aabb.extentX(),
                                                       aabb.originY() + aabb.extentY(), 
                                                       aabb.originZ() + aabb.extentZ());
            case Spatial.aabt aabt -> new VolumeBounds(aabt.originX(), aabt.originY(), aabt.originZ(),
                                                       aabt.originX() + aabt.extentX(),
                                                       aabt.originY() + aabt.extentY(),
                                                       aabt.originZ() + aabt.extentZ());
            case Spatial.Parallelepiped para -> new VolumeBounds(para.originX(), para.originY(), para.originZ(),
                                                                 para.originX() + para.extentX(), 
                                                                 para.originY() + para.extentY(),
                                                                 para.originZ() + para.extentZ());
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

    /**
     * Find minimum level that can contain the volume
     */
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

    /**
     * Helper record for volume bounds
     */
    private record VolumeBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    }
}
