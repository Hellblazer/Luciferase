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

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3i;
import java.util.NavigableMap;
import java.util.stream.Stream;

/**
 * Common interface for spatial indexing data structures.
 * This interface abstracts the core spatial operations to allow different implementations
 * (e.g., Octree, OctreeWithEntities via SingleContentAdapter) to be used interchangeably.
 *
 * @param <Content> The type of content stored in the spatial index
 * @author hal.hildebrand
 */
public interface SpatialIndex<Content> {
    
    /**
     * Node wrapper that provides uniform access to spatial data
     */
    record SpatialNode<Content>(long index, Content content) {
        /**
         * Convert the node's Morton index to a spatial cube
         */
        public Spatial.Cube toCube() {
            return Octree.toCube(index);
        }
    }
    
    /**
     * Insert content at a specific point and level
     *
     * @param point the 3D point location
     * @param level the refinement level
     * @param value the content to store
     * @return the Morton index of the inserted node
     */
    long insert(Point3f point, byte level, Content value);
    
    /**
     * Look up content at a specific point and level
     *
     * @param point the 3D point location
     * @param level the refinement level
     * @return the content at that location, or null if not found
     */
    default Content lookup(Point3f point, byte level) {
        long index = Constants.calculateMortonIndex(point, level);
        return get(index);
    }
    
    /**
     * Get content by Morton index
     *
     * @param index the Morton index
     * @return the content at that index, or null if not found
     */
    Content get(long index);
    
    /**
     * Stream all nodes in the spatial index
     *
     * @return stream of spatial nodes
     */
    Stream<SpatialNode<Content>> nodes();
    
    /**
     * Get all nodes within a bounding volume
     * 
     * @param volume the bounding volume
     * @return stream of nodes completely contained within the volume
     */
    Stream<SpatialNode<Content>> boundedBy(Spatial volume);
    
    /**
     * Get all nodes that intersect with a bounding volume
     * 
     * @param volume the bounding volume
     * @return stream of nodes that intersect the volume
     */
    Stream<SpatialNode<Content>> bounding(Spatial volume);
    
    /**
     * Find the minimum enclosing node for a volume
     * 
     * @param volume the volume to enclose
     * @return the minimum enclosing node, or null if not found
     */
    SpatialNode<Content> enclosing(Spatial volume);
    
    /**
     * Find the enclosing node at a specific level
     * 
     * @param point the point to enclose
     * @param level the refinement level
     * @return the enclosing node at that level
     */
    SpatialNode<Content> enclosing(Tuple3i point, byte level);
    
    /**
     * Get a navigable map view of the spatial index
     * This allows for range queries and ordered traversal
     * 
     * @return navigable map with Morton indices as keys
     */
    NavigableMap<Long, Content> getMap();
    
    /**
     * Get the number of nodes in the spatial index
     * 
     * @return the number of nodes
     */
    int size();
    
    /**
     * Check if the spatial index contains a node at the given Morton index
     * 
     * @param mortonIndex the Morton index to check
     * @return true if a node exists at that index
     */
    boolean hasNode(long mortonIndex);
    
    /**
     * Get statistics about the spatial index
     * 
     * @return statistics object
     */
    default SpatialIndexStats getStats() {
        return new SpatialIndexStats(size(), size());
    }
    
    /**
     * Statistics about the spatial index
     */
    record SpatialIndexStats(int totalNodes, int totalEntities) {
    }
}