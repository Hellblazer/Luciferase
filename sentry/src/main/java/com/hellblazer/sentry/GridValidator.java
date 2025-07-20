/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the 3D Incremental Voronoi system
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.sentry;

import java.util.ArrayList;
import java.util.List;

/**
 * Validator for MutableGrid that repairs and validates vertex references.
 * This class is designed to work with package-private methods of MutableGrid.
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class GridValidator {
    
    private final MutableGrid grid;
    
    public GridValidator(MutableGrid grid) {
        this.grid = grid;
    }
    
    /**
     * Validate that all vertices have valid adjacent tetrahedron references.
     * If a vertex is missing its adjacent reference, find a valid tetrahedron containing it.
     * Also validates bidirectional consistency between vertices and tetrahedra.
     * 
     * @return validation result with counts
     */
    public ValidationResult validateAndRepairVertexReferences() {
        List<Vertex> vertices = grid.getVertices();
        if (vertices.isEmpty()) {
            return new ValidationResult(0, 0);
        }
        
        int validatedCount = 0;
        int repairedCount = 0;
        
        for (var v : vertices) {
            Tetrahedron adjacent = v.getAdjacent();
            
            // Check if vertex has no adjacent reference or reference is invalid
            if (adjacent == null || adjacent.isDeleted()) {
                adjacent = findTetrahedronContaining(v);
                if (adjacent != null) {
                    v.setAdjacent(adjacent);
                    repairedCount++;
                }
            } else {
                // Verify bidirectional consistency: tetrahedron should contain vertex
                boolean found = false;
                for (V vertex : V.values()) {
                    if (adjacent.getVertex(vertex) == v) {
                        found = true;
                        break;
                    }
                }
                
                if (!found) {
                    // Adjacent tetrahedron doesn't contain this vertex - find correct one
                    Tetrahedron correctAdjacent = findTetrahedronContaining(v);
                    if (correctAdjacent != null) {
                        v.setAdjacent(correctAdjacent);
                        repairedCount++;
                    }
                } else {
                    validatedCount++;
                }
            }
        }
        
        // Optionally log validation results for debugging large repairs only
        if (repairedCount > 50) {
            System.out.println("Vertex reference validation: " + validatedCount + " valid, " + 
                repairedCount + " repaired");
        }
        
        return new ValidationResult(validatedCount, repairedCount);
    }
    
    /**
     * Find a tetrahedron that contains the given vertex.
     * This is used to repair missing vertex-tetrahedron references.
     */
    private Tetrahedron findTetrahedronContaining(Vertex v) {
        // Get last tetrahedron from grid
        Tetrahedron last = grid.getLastTetrahedron();
        
        // Start from the last tetrahedron and walk the mesh
        if (last != null && !last.isDeleted()) {
            // Check if last contains the vertex
            if (last.includes(v)) {
                return last;
            }
            
            // Perform a breadth-first search from last
            List<Tetrahedron> visited = new ArrayList<>();
            List<Tetrahedron> queue = new ArrayList<>();
            queue.add(last);
            visited.add(last);
            
            while (!queue.isEmpty()) {
                Tetrahedron current = queue.remove(0);
                
                // Check all neighbors
                for (V vertex : V.values()) {
                    Tetrahedron neighbor = current.getNeighbor(vertex);
                    if (neighbor != null && !neighbor.isDeleted() && !visited.contains(neighbor)) {
                        if (neighbor.includes(v)) {
                            return neighbor;
                        }
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
        }
        
        return null; // Vertex not found in any tetrahedron
    }
    
    /**
     * Result of validation and repair operation.
     */
    public static class ValidationResult {
        public final int validatedCount;
        public final int repairedCount;
        
        public ValidationResult(int validatedCount, int repairedCount) {
            this.validatedCount = validatedCount;
            this.repairedCount = repairedCount;
        }
        
        public boolean hasRepairs() {
            return repairedCount > 0;
        }
        
        @Override
        public String toString() {
            return String.format("ValidationResult[validated=%d, repaired=%d]", 
                validatedCount, repairedCount);
        }
    }
}