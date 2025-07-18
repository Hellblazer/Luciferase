/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the 3D Incremental Voronoi system
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.sentry;

import java.util.ArrayList;
import java.util.List;

/**
 * Optimized flip operations that reduce method call overhead and improve
 * cache locality.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class FlipOptimizer {
    
    /**
     * Pre-allocated arrays to avoid repeated allocations
     */
    private static final ThreadLocal<WorkingSet> WORKING_SET = ThreadLocal.withInitial(WorkingSet::new);
    
    /**
     * Working set for flip operations to improve cache locality
     */
    private static class WorkingSet {
        final Vertex[] vertices = new Vertex[200];
        final boolean[] reflexStatus = new boolean[200];
        final OrientedFace[] faces = new OrientedFace[200];
        final int[] reflexIndices = new int[200];
        int reflexCount = 0;
        
        void reset() {
            reflexCount = 0;
        }
    }
    
    /**
     * Optimized flip operation that minimizes method calls and improves cache locality
     */
    public static Tetrahedron flipOptimized(OrientedFace face, Vertex n, List<OrientedFace> ears) {
        if (face == null) {
            return null;
        }
        
        // Get thread-local working set
        WorkingSet ws = WORKING_SET.get();
        ws.reset();
        
        // Check if face is valid
        if (!face.getIncident().isDeleted() && face.hasAdjacent() && !face.getAdjacent().isDeleted()) {
            return processValidFace(face, n, ears, ws, 0);
        }
        
        return null;
    }
    
    /**
     * Process a valid face with reduced method call overhead
     */
    private static Tetrahedron processValidFace(OrientedFace face, Vertex n, List<OrientedFace> ears, WorkingSet ws, int index) {
        // Inline regular check to avoid method call
        Tetrahedron incident = face.getIncident();
        Vertex adjacentVertex = face.getAdjacentVertex();
        if (!incident.inSphere(adjacentVertex)) {
            return null; // Regular face, skip
        }
        
        // Count reflex edges inline
        int reflexEdge = 0;
        int reflexEdges = 0;
        
        // Unroll loop for better performance
        if (face.isReflex(0)) {
            reflexEdge = 0;
            reflexEdges++;
        }
        if (reflexEdges < 2 && face.isReflex(1)) {
            reflexEdge = 1;
            reflexEdges++;
        }
        if (reflexEdges < 2 && face.isReflex(2)) {
            reflexEdge = 2;
            reflexEdges++;
        }
        
        // Process based on reflex edge count
        if (reflexEdges == 0) {
            // Inline flip2to3 logic for better performance
            return performFlip2to3Inline(face, n, ears);
        } else if (reflexEdges == 1) {
            // Check for 3->2 flip possibility
            Vertex opposingVertex = face.getVertex(reflexEdge);
            Tetrahedron t1 = incident.getNeighbor(opposingVertex);
            Tetrahedron t2 = face.getAdjacent().getNeighbor(opposingVertex);
            if (t1 != null && t1 == t2) {
                return performFlip3to2Inline(face, reflexEdge, n, ears);
            }
        }
        
        return null;
    }
    
    /**
     * Inline flip2to3 for better performance
     */
    private static Tetrahedron performFlip2to3Inline(OrientedFace face, Vertex n, List<OrientedFace> ears) {
        Tetrahedron[] created = face.flip2to3();
        Tetrahedron lastValid = null;
        
        // Process created tetrahedra
        for (Tetrahedron t : created) {
            OrientedFace f = t.getFace(n);
            if (f.hasAdjacent()) {
                ears.add(f);
            }
            lastValid = t;
        }
        
        return lastValid;
    }
    
    /**
     * Inline flip3to2 for better performance
     */
    private static Tetrahedron performFlip3to2Inline(OrientedFace face, int reflexEdge, Vertex n, List<OrientedFace> ears) {
        Tetrahedron[] created = face.flip3to2(reflexEdge);
        Tetrahedron lastValid = null;
        
        // Process created tetrahedra
        for (Tetrahedron t : created) {
            OrientedFace f = t.getFace(n);
            if (f.hasAdjacent()) {
                ears.add(f);
            }
            lastValid = t;
        }
        
        return lastValid;
    }
    
    /**
     * Batch process multiple flip operations for better cache usage
     */
    public static void batchFlip(List<List<OrientedFace>> earLists, List<Vertex> vertices) {
        if (earLists.size() != vertices.size()) {
            throw new IllegalArgumentException("Lists must be same size");
        }
        
        // Process in batches to maximize cache usage
        final int BATCH_SIZE = 4;
        for (int i = 0; i < earLists.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, earLists.size());
            
            // Prefetch data for batch
            for (int j = i; j < end; j++) {
                List<OrientedFace> ears = earLists.get(j);
                if (!ears.isEmpty()) {
                    // Touch data to bring into cache
                    ears.get(0).getIncident();
                }
            }
            
            // Process batch
            for (int j = i; j < end; j++) {
                List<OrientedFace> ears = earLists.get(j);
                Vertex v = vertices.get(j);
                while (!ears.isEmpty()) {
                    OrientedFace face = ears.remove(ears.size() - 1);
                    flipOptimized(face, v, ears);
                }
            }
        }
    }
}