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

package com.hellblazer.sentry.packed;

import com.hellblazer.luciferase.common.IntArrayList;
import com.hellblazer.luciferase.geometry.Geometry;
import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Mutable packed grid implementation with point insertion/tracking capabilities.
 * This extends the basic PackedGrid with the ability to insert points and maintain
 * the Delaunay condition through flip operations.
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class PackedMutableGrid extends PackedGrid {
    
    private final PackedFlipOperations flipOps;
    
    // Vertex tracking - linked list of vertex indices
    private final IntArrayList nextVertex;  // Next vertex in linked list
    private final IntArrayList prevVertex;  // Previous vertex in linked list
    private int headVertex = INVALID_INDEX;
    private int tailVertex = INVALID_INDEX;
    private int vertexCount = 0;
    
    public PackedMutableGrid() {
        super();
        this.flipOps = new PackedFlipOperations(this);
        this.nextVertex = new IntArrayList();
        this.prevVertex = new IntArrayList();
        
        // Initialize linked list arrays for the four corners
        for (int i = 0; i < 4; i++) {
            nextVertex.add(INVALID_INDEX);
            prevVertex.add(INVALID_INDEX);
        }
    }
    
    /**
     * Track a point into the tetrahedralization.
     * 
     * @param p the point to be inserted
     * @param entropy source of randomness for locate
     * @return the vertex index in the tetrahedralization, or -1 if outside bounds
     */
    public int track(Point3f p, Random entropy) {
        if (!contains(p)) {
            return INVALID_INDEX;
        }
        
        int v = addVertex(p.x, p.y, p.z);
        
        int target = locate(p, -1, entropy);
        if (target == INVALID_INDEX) {
            return INVALID_INDEX;
        }
        
        insert(v, target, entropy);
        appendToVertexList(v);
        vertexCount++;
        
        return v;
    }
    
    /**
     * Track a point into the tetrahedralization starting from a nearby tetrahedron.
     * 
     * @param p the point to be inserted
     * @param nearTet a nearby tetrahedron to start the search
     * @param entropy source of randomness for locate
     * @return the vertex index in the tetrahedralization, or -1 if outside bounds
     */
    public int track(Point3f p, int nearTet, Random entropy) {
        if (!contains(p)) {
            return INVALID_INDEX;
        }
        
        int v = addVertex(p.x, p.y, p.z);
        int target = locate(p, nearTet, entropy);
        if (target == INVALID_INDEX) {
            return INVALID_INDEX;
        }
        
        insert(v, target, entropy);
        appendToVertexList(v);
        vertexCount++;
        
        return v;
    }
    
    /**
     * Check if a point is contained within the bounds of the tetrahedralization.
     */
    private boolean contains(Point3f point) {
        return Geometry.insideTetrahedron(point, 
            FOUR_CORNERS[0], FOUR_CORNERS[1], FOUR_CORNERS[2], FOUR_CORNERS[3]);
    }
    
    /**
     * Insert a vertex into the target tetrahedron and restore Delaunay condition.
     */
    private void insert(int vertexIndex, int targetTet, Random entropy) {
        // Perform initial 1-to-4 flip
        List<Integer> ears = new ArrayList<>();
        lastTet = flipOps.flip1to4(targetTet, vertexIndex, ears);
        
        // Process ears to restore Delaunay condition
        while (!ears.isEmpty()) {
            int face = ears.remove(ears.size() - 1);
            Tetrahedron lastFlipped = flip(face, vertexIndex, ears, entropy);
            if (lastFlipped != null) {
                lastTet = lastFlipped.index;
            }
        }
    }
    
    /**
     * Perform flip operation to restore Delaunay condition.
     * 
     * @param face encoded face (tetIndex * 4 + faceIndex)
     * @param insertedVertex the vertex that was just inserted
     * @param ears list to collect new faces that may need flipping
     * @param entropy source of randomness
     * @return the last tetrahedron created, or null if no flip performed
     */
    private Tetrahedron flip(int face, int insertedVertex, List<Integer> ears, Random entropy) {
        int tetIndex = face / 4;
        int faceIndex = face % 4;
        
        if (!isValidTetrahedron(tetIndex)) {
            return null;
        }
        
        TetrahedronProxy incident = new TetrahedronProxy(this, tetIndex);
        TetrahedronProxy adjacent = incident.getNeighbor(faceIndex);
        if (adjacent == null) {
            return null; // On convex hull
        }
        
        // Debug check - ensure bidirectional neighbor relationship
        boolean foundBackReference = false;
        for (int i = 0; i < 4; i++) {
            if (getNeighbor(adjacent.getIndex(), i) == tetIndex) {
                foundBackReference = true;
                break;
            }
        }
        if (!foundBackReference) {
            // Non-bidirectional neighbor relationship, skip this flip
            return null;
        }
        
        // Check if flip is needed (Delaunay condition)
        if (!needsFlip(incident, adjacent, insertedVertex)) {
            return null;
        }
        
        // Determine type of flip needed
        int reflexEdges = countReflexEdges(face, insertedVertex);
        
        Tetrahedron result = null;
        
        if (reflexEdges == 0) {
            // Perform 2-to-3 flip
            int[] newTets = flipOps.flip2to3(face);
            for (int t : newTets) {
                // Add faces opposite the inserted vertex to ears
                TetrahedronProxy tet = new TetrahedronProxy(this, t);
                // Find which ordinal position the inserted vertex occupies
                int insertedOrdinal = tet.ordinalOf(insertedVertex);
                if (insertedOrdinal != -1) {
                    // Add the face opposite to the inserted vertex
                    addFaceToEarsIfAdjacent(t, insertedOrdinal, ears);
                }
                result = new Tetrahedron(t);
            }
        } else if (reflexEdges == 1) {
            // Check if 3-to-2 flip is possible
            int reflexEdge = findReflexEdge(face, insertedVertex);
            if (canFlip3to2(face, reflexEdge)) {
                int[] newTets = flipOps.flip3to2(face, reflexEdge);
                for (int t : newTets) {
                    // Add faces opposite the inserted vertex to ears
                    TetrahedronProxy tet = new TetrahedronProxy(this, t);
                    // Find which ordinal position the inserted vertex occupies
                    int insertedOrdinal = tet.ordinalOf(insertedVertex);
                    if (insertedOrdinal != -1) {
                        // Add the face opposite to the inserted vertex
                        addFaceToEarsIfAdjacent(t, insertedOrdinal, ears);
                    }
                    result = new Tetrahedron(t);
                }
            }
        }
        // If reflexEdges >= 2, no flip is performed
        
        return result;
    }
    
    /**
     * Check if a flip is needed based on the Delaunay condition.
     */
    private boolean needsFlip(TetrahedronProxy incident, TetrahedronProxy adjacent, int insertedVertex) {
        // Find the vertex of adjacent that's not in incident
        int[] adjVerts = adjacent.getVertices();
        int oppositeVertex = -1;
        for (int v : adjVerts) {
            if (!incident.hasVertex(v)) {
                oppositeVertex = v;
                break;
            }
        }
        
        if (oppositeVertex == -1) {
            return false;
        }
        
        // Check if opposite vertex is inside circumsphere of incident
        return inSphere(incident, oppositeVertex);
    }
    
    /**
     * Check if a vertex is inside the circumsphere of a tetrahedron.
     */
    private boolean inSphere(TetrahedronProxy tet, int queryVertex) {
        float[] q = new float[3];
        float[] a = new float[3];
        float[] b = new float[3];
        float[] c = new float[3];
        float[] d = new float[3];
        
        getVertexCoords(queryVertex, q);
        getVertexCoords(tet.a(), a);
        getVertexCoords(tet.b(), b);
        getVertexCoords(tet.c(), c);
        getVertexCoords(tet.d(), d);
        
        return Geometry.inSphereFast(
            q[0], q[1], q[2],
            a[0], a[1], a[2],
            b[0], b[1], b[2],
            c[0], c[1], c[2],
            d[0], d[1], d[2]
        ) > 0;
    }
    
    /**
     * Count the number of reflex edges on a face.
     */
    private int countReflexEdges(int face, int insertedVertex) {
        // This is a simplified version - proper implementation would
        // check visibility of faces from the inserted vertex
        return 0; // For now, always try 2-to-3 flip
    }
    
    /**
     * Find which edge is reflex.
     */
    private int findReflexEdge(int face, int insertedVertex) {
        // Simplified - would need proper visibility check
        return 0;
    }
    
    /**
     * Check if a 3-to-2 flip is possible.
     */
    private boolean canFlip3to2(int face, int reflexEdge) {
        // Would need to verify that three tets share the reflex edge
        return false; // For now, don't attempt 3-to-2 flips
    }
    
    /**
     * Add a face to ears list if it has an adjacent tetrahedron.
     */
    private void addFaceToEarsIfAdjacent(int tetIndex, int faceIndex, List<Integer> ears) {
        if (getNeighbor(tetIndex, faceIndex) != INVALID_INDEX) {
            ears.add(tetIndex * 4 + faceIndex);
        }
    }
    
    /**
     * Append a vertex to the linked list.
     */
    private void appendToVertexList(int vertex) {
        // Ensure arrays are large enough
        while (nextVertex.size() <= vertex) {
            nextVertex.add(INVALID_INDEX);
            prevVertex.add(INVALID_INDEX);
        }
        
        if (headVertex == INVALID_INDEX) {
            headVertex = vertex;
            tailVertex = vertex;
        } else {
            nextVertex.setInt(tailVertex, vertex);
            prevVertex.setInt(vertex, tailVertex);
            tailVertex = vertex;
        }
    }
    
    /**
     * Get the number of tracked vertices.
     */
    public int getVertexCount() {
        return vertexCount;
    }
    
    /**
     * Get the head of the vertex linked list.
     */
    public int getHeadVertex() {
        return headVertex;
    }
    
    /**
     * Get the next vertex in the linked list.
     */
    public int getNextVertex(int vertex) {
        if (vertex < 0 || vertex >= nextVertex.size()) {
            return INVALID_INDEX;
        }
        return nextVertex.getInt(vertex);
    }
    
    /**
     * Simple holder for tetrahedron index.
     */
    private static class Tetrahedron {
        final int index;
        
        Tetrahedron(int index) {
            this.index = index;
        }
    }
}