/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.neighbor;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;

import java.util.List;
import java.util.Set;

/**
 * Interface for detecting topological neighbors of spatial elements.
 * 
 * This interface provides methods to find face, edge, and vertex neighbors
 * of elements in a spatial index, which is essential for ghost layer creation
 * in distributed spatial indices.
 * 
 * @param <Key> the type of spatial key used by the spatial index
 * 
 * @author Hal Hildebrand
 */
public interface NeighborDetector<Key extends SpatialKey<Key>> {
    
    /**
     * Finds all face neighbors of the given element.
     * Face neighbors share a face (codimension 1) with the element.
     * 
     * @param element the spatial key of the element
     * @return list of spatial keys of face neighbors
     */
    List<Key> findFaceNeighbors(Key element);
    
    /**
     * Finds all edge neighbors of the given element.
     * Edge neighbors share an edge (codimension 2) with the element.
     * Note: This includes face neighbors as well.
     * 
     * @param element the spatial key of the element
     * @return list of spatial keys of edge neighbors
     */
    List<Key> findEdgeNeighbors(Key element);
    
    /**
     * Finds all vertex neighbors of the given element.
     * Vertex neighbors share a vertex (codimension 3) with the element.
     * Note: This includes face and edge neighbors as well.
     * 
     * @param element the spatial key of the element
     * @return list of spatial keys of vertex neighbors
     */
    List<Key> findVertexNeighbors(Key element);
    
    /**
     * Finds all neighbors based on the specified ghost type.
     * 
     * @param element the spatial key of the element
     * @param type the type of neighbors to find
     * @return list of spatial keys of neighbors
     */
    default List<Key> findNeighbors(Key element, GhostType type) {
        return switch (type) {
            case NONE -> List.of();
            case FACES -> findFaceNeighbors(element);
            case EDGES -> findEdgeNeighbors(element);
            case VERTICES -> findVertexNeighbors(element);
        };
    }
    
    /**
     * Checks if an element is at the boundary in a specific direction.
     * 
     * @param element the spatial key of the element
     * @param direction the direction to check
     * @return true if the element is at the boundary
     */
    boolean isBoundaryElement(Key element, Direction direction);
    
    /**
     * Gets all directions in which the element is at a boundary.
     * 
     * @param element the spatial key of the element
     * @return set of boundary directions
     */
    Set<Direction> getBoundaryDirections(Key element);
    
    /**
     * Finds neighbors with their ownership information.
     * This is used for distributed ghost creation.
     * 
     * @param element the spatial key of the element
     * @param type the type of neighbors to find
     * @return list of neighbor information including ownership
     */
    List<NeighborInfo<Key>> findNeighborsWithOwners(Key element, GhostType type);
    
    /**
     * Direction enumeration for boundary checking.
     */
    enum Direction {
        POSITIVE_X, NEGATIVE_X,
        POSITIVE_Y, NEGATIVE_Y,
        POSITIVE_Z, NEGATIVE_Z
    }
    
    /**
     * Information about a neighbor including ownership.
     * 
     * @param <Key> the type of spatial key
     */
    record NeighborInfo<Key extends SpatialKey<Key>>(
        Key neighborKey,
        int ownerRank,
        long treeId,
        boolean isLocal
    ) {}
}