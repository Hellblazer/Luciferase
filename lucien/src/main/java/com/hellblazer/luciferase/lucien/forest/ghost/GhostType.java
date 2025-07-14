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

package com.hellblazer.luciferase.lucien.forest.ghost;

/**
 * Defines the types of neighbor relationships that determine which elements
 * become ghosts in a distributed spatial index.
 * 
 * Based on t8code's ghost type definitions, this controls which neighboring
 * elements are included in the ghost layer for parallel computations.
 * 
 * @author Hal Hildebrand
 */
public enum GhostType {
    /**
     * No ghost layer will be created.
     */
    NONE,
    
    /**
     * Only face neighbors (codimension 1) will be included as ghosts.
     * In 3D, these are elements that share a face.
     */
    FACES,
    
    /**
     * Edge neighbors (codimension 2) and face neighbors will be included.
     * In 3D, these are elements that share an edge or face.
     */
    EDGES,
    
    /**
     * Vertex neighbors (codimension 3), edge neighbors, and face neighbors
     * will all be included. In 3D, these are elements that share any vertex,
     * edge, or face.
     */
    VERTICES;
    
    /**
     * Checks if this ghost type includes face neighbors.
     * 
     * @return true if face neighbors are included
     */
    public boolean includesFaces() {
        return this != NONE;
    }
    
    /**
     * Checks if this ghost type includes edge neighbors.
     * 
     * @return true if edge neighbors are included
     */
    public boolean includesEdges() {
        return this == EDGES || this == VERTICES;
    }
    
    /**
     * Checks if this ghost type includes vertex neighbors.
     * 
     * @return true if vertex neighbors are included
     */
    public boolean includesVertices() {
        return this == VERTICES;
    }
}