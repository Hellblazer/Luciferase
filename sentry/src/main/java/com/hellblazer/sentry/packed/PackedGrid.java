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

import com.hellblazer.luciferase.common.FloatArrayList;
import com.hellblazer.luciferase.common.IntArrayList;
import com.hellblazer.sentry.Vertex;
import javax.vecmath.Tuple3f;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Structure-of-Arrays (SoA) implementation of the Delaunay tetrahedralization grid.
 * This implementation stores tetrahedra data in parallel arrays for improved cache efficiency.
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class PackedGrid {
    
    // Constants
    protected static final int VERTICES_PER_TET = 4;
    protected static final int FACES_PER_TET = 4;
    protected static final int COORDS_PER_VERTEX = 3;
    protected static final int INVALID_INDEX = -1;
    
    // Scale for the encompassing tetrahedron
    private static final float SCALE = (float) Math.pow(2, 24);
    
    // Four corners of the encompassing tetrahedron
    protected static final Vertex[] FOUR_CORNERS = {
        new Vertex(-1, 1, -1, SCALE),
        new Vertex(1, 1, 1, SCALE),
        new Vertex(1, -1, -1, SCALE),
        new Vertex(-1, -1, 1, SCALE)
    };
    
    // Vertex storage
    protected final FloatArrayList vertices;      // x,y,z triplets
    
    // Tetrahedron storage
    protected final IntArrayList tetrahedra;      // a,b,c,d quads (vertex indices)
    protected final IntArrayList adjacent;        // nA,nB,nC,nD quads (neighbor indices)
    
    // Free list for tetrahedron reuse
    protected final Deque<Integer> freedTets;
    
    // Last accessed tetrahedron (for spatial locality)
    protected int lastTet = 0;
    
    /**
     * Create an empty packed grid
     */
    public PackedGrid() {
        this.vertices = new FloatArrayList();
        this.tetrahedra = new IntArrayList();
        this.adjacent = new IntArrayList();
        this.freedTets = new ArrayDeque<>();
        
        initializeFourCorners();
        initializeFirstTetrahedron();
    }
    
    /**
     * Initialize the four corner vertices of the encompassing tetrahedron
     */
    private void initializeFourCorners() {
        // Add the four corners as vertices 0-3
        for (Vertex corner : FOUR_CORNERS) {
            vertices.add(corner.x);
            vertices.add(corner.y);
            vertices.add(corner.z);
        }
    }
    
    /**
     * Initialize the first tetrahedron from the four corners
     */
    private void initializeFirstTetrahedron() {
        // Create tetrahedron 0 from vertices 0,1,2,3
        tetrahedra.add(0);  // a
        tetrahedra.add(1);  // b
        tetrahedra.add(2);  // c
        tetrahedra.add(3);  // d
        
        // No neighbors initially (all on convex hull)
        adjacent.add(INVALID_INDEX);  // nA
        adjacent.add(INVALID_INDEX);  // nB
        adjacent.add(INVALID_INDEX);  // nC
        adjacent.add(INVALID_INDEX);  // nD
    }
    
    /**
     * Allocate a new tetrahedron, reusing freed slots when possible
     * 
     * @return the index of the allocated tetrahedron
     */
    protected int allocateTetrahedron() {
        Integer freed = freedTets.pollLast();
        if (freed != null) {
            return freed;
        }
        
        // Allocate new tetrahedron at end
        int newIndex = tetrahedra.size() / VERTICES_PER_TET;
        
        // Reserve space for vertices
        for (int i = 0; i < VERTICES_PER_TET; i++) {
            tetrahedra.add(INVALID_INDEX);
        }
        
        // Reserve space for neighbors
        for (int i = 0; i < FACES_PER_TET; i++) {
            adjacent.add(INVALID_INDEX);
        }
        
        return newIndex;
    }
    
    /**
     * Delete a tetrahedron, clearing neighbor references and adding to free list
     * 
     * @param tetIndex the index of the tetrahedron to delete
     */
    protected void deleteTetrahedron(int tetIndex) {
        // Clear neighbor references to this tetrahedron
        for (int face = 0; face < FACES_PER_TET; face++) {
            int neighborIdx = getNeighbor(tetIndex, face);
            if (neighborIdx != INVALID_INDEX) {
                // Find which face of the neighbor points back to us
                clearNeighborReference(neighborIdx, tetIndex);
            }
        }
        
        // Clear this tetrahedron's data
        int baseVertex = tetIndex * VERTICES_PER_TET;
        int baseNeighbor = tetIndex * FACES_PER_TET;
        
        for (int i = 0; i < VERTICES_PER_TET; i++) {
            tetrahedra.setInt(baseVertex + i, INVALID_INDEX);
        }
        
        for (int i = 0; i < FACES_PER_TET; i++) {
            adjacent.setInt(baseNeighbor + i, INVALID_INDEX);
        }
        
        // Add to free list for reuse
        freedTets.add(tetIndex);
    }
    
    /**
     * Clear a neighbor's reference to a tetrahedron
     */
    private void clearNeighborReference(int neighborIdx, int tetIndex) {
        int base = neighborIdx * FACES_PER_TET;
        for (int face = 0; face < FACES_PER_TET; face++) {
            if (adjacent.getInt(base + face) == tetIndex) {
                adjacent.setInt(base + face, INVALID_INDEX);
                break;
            }
        }
    }
    
    // Basic accessors
    
    /**
     * Get a vertex index from a tetrahedron
     */
    protected int getVertex(int tetIndex, int vertexOrdinal) {
        return tetrahedra.getInt(tetIndex * VERTICES_PER_TET + vertexOrdinal);
    }
    
    /**
     * Set a vertex index in a tetrahedron
     */
    protected void setVertex(int tetIndex, int vertexOrdinal, int vertexIndex) {
        tetrahedra.setInt(tetIndex * VERTICES_PER_TET + vertexOrdinal, vertexIndex);
    }
    
    /**
     * Get a neighbor index from a tetrahedron
     */
    protected int getNeighbor(int tetIndex, int face) {
        return adjacent.getInt(tetIndex * FACES_PER_TET + face);
    }
    
    /**
     * Set a neighbor index for a tetrahedron face
     */
    protected void setNeighbor(int tetIndex, int face, int neighborIndex) {
        adjacent.setInt(tetIndex * FACES_PER_TET + face, neighborIndex);
    }
    
    /**
     * Set bidirectional neighbor relationship
     */
    protected void setNeighbors(int tet1, int face1, int tet2, int face2) {
        setNeighbor(tet1, face1, tet2);
        if (tet2 != INVALID_INDEX) {
            setNeighbor(tet2, face2, tet1);
        }
    }
    
    /**
     * Get vertex coordinates
     */
    public void getVertexCoords(int vertexIndex, float[] coords) {
        int base = vertexIndex * COORDS_PER_VERTEX;
        coords[0] = vertices.getFloat(base);
        coords[1] = vertices.getFloat(base + 1);
        coords[2] = vertices.getFloat(base + 2);
    }
    
    /**
     * Get the number of tetrahedra in the grid (including deleted ones)
     */
    public int getTetrahedronCount() {
        return tetrahedra.size() / VERTICES_PER_TET;
    }
    
    /**
     * Get the number of valid (non-deleted) tetrahedra in the grid
     */
    public int getValidTetrahedronCount() {
        int count = 0;
        int totalTets = tetrahedra.size() / VERTICES_PER_TET;
        for (int i = 0; i < totalTets; i++) {
            if (isValidTetrahedron(i)) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Add a new vertex
     */
    protected int addVertex(float x, float y, float z) {
        int index = vertices.size() / COORDS_PER_VERTEX;
        vertices.add(x);
        vertices.add(y);
        vertices.add(z);
        return index;
    }
    
    /**
     * Check if a tetrahedron is valid (not deleted)
     */
    protected boolean isValidTetrahedron(int tetIndex) {
        if (tetIndex < 0 || tetIndex >= tetrahedra.size() / VERTICES_PER_TET) {
            return false;
        }
        // A deleted tetrahedron has INVALID_INDEX for its first vertex
        return getVertex(tetIndex, 0) != INVALID_INDEX;
    }
    
    /**
     * Find the first valid (non-deleted) tetrahedron.
     * @return the index of the first valid tetrahedron, or INVALID_INDEX if none found
     */
    private int findFirstValidTetrahedron() {
        int maxTets = tetrahedra.size() / VERTICES_PER_TET;
        for (int i = 0; i < maxTets; i++) {
            if (isValidTetrahedron(i)) {
                return i;
            }
        }
        return INVALID_INDEX;
    }
    
    /**
     * Get the number of vertices
     */
    public int getVertexCount() {
        return vertices.size() / COORDS_PER_VERTEX;
    }
    
    /**
     * Get the number of tetrahedra (including deleted ones)
     */
    public int getTetrahedronCapacity() {
        return tetrahedra.size() / VERTICES_PER_TET;
    }
    
    /**
     * Create a new tetrahedron from four vertex indices
     * 
     * @param v0 vertex A index
     * @param v1 vertex B index
     * @param v2 vertex C index
     * @param v3 vertex D index
     * @return the index of the created tetrahedron
     */
    protected int createTetrahedron(int v0, int v1, int v2, int v3) {
        int tetIndex = allocateTetrahedron();
        setVertex(tetIndex, 0, v0);
        setVertex(tetIndex, 1, v1);
        setVertex(tetIndex, 2, v2);
        setVertex(tetIndex, 3, v3);
        return tetIndex;
    }
    
    /**
     * Set all four vertices of a tetrahedron at once
     */
    protected void setTetrahedronVertices(int tetIndex, int v0, int v1, int v2, int v3) {
        int base = tetIndex * VERTICES_PER_TET;
        tetrahedra.setInt(base, v0);
        tetrahedra.setInt(base + 1, v1);
        tetrahedra.setInt(base + 2, v2);
        tetrahedra.setInt(base + 3, v3);
    }
    
    /**
     * Get all four vertices of a tetrahedron
     * 
     * @param tetIndex the tetrahedron index
     * @param vertices array to store the four vertex indices
     */
    protected void getTetrahedronVertices(int tetIndex, int[] vertices) {
        int base = tetIndex * VERTICES_PER_TET;
        vertices[0] = tetrahedra.getInt(base);
        vertices[1] = tetrahedra.getInt(base + 1);
        vertices[2] = tetrahedra.getInt(base + 2);
        vertices[3] = tetrahedra.getInt(base + 3);
    }
    
    /**
     * Find which face of a tetrahedron contains the given three vertices
     * 
     * @param tetIndex the tetrahedron to check
     * @param v0, v1, v2 the three vertices to find
     * @return the face index (0-3) or -1 if not found
     */
    protected int findFaceWithVertices(int tetIndex, int v0, int v1, int v2) {
        int[] verts = new int[4];
        getTetrahedronVertices(tetIndex, verts);
        
        // Check each face
        // Face 0 (opposite A): contains B,C,D (indices 1,2,3)
        if (containsAll(verts[1], verts[2], verts[3], v0, v1, v2)) {
            return 0;
        }
        // Face 1 (opposite B): contains A,C,D (indices 0,2,3)
        if (containsAll(verts[0], verts[2], verts[3], v0, v1, v2)) {
            return 1;
        }
        // Face 2 (opposite C): contains A,B,D (indices 0,1,3)
        if (containsAll(verts[0], verts[1], verts[3], v0, v1, v2)) {
            return 2;
        }
        // Face 3 (opposite D): contains A,B,C (indices 0,1,2)
        if (containsAll(verts[0], verts[1], verts[2], v0, v1, v2)) {
            return 3;
        }
        
        return -1;
    }
    
    /**
     * Helper to check if three vertices contain all of another three (in any order)
     */
    private boolean containsAll(int a1, int a2, int a3, int b1, int b2, int b3) {
        return (a1 == b1 || a1 == b2 || a1 == b3) &&
               (a2 == b1 || a2 == b2 || a2 == b3) &&
               (a3 == b1 || a3 == b2 || a3 == b3);
    }
    
    /**
     * Locate the tetrahedron which contains the query point via a stochastic walk
     * through the delaunay triangulation. This is based on the jump-and-walk algorithm.
     * 
     * @param query the query point
     * @param start the starting tetrahedron index (-1 to start from lastTet)
     * @param random source of randomness for the walk
     * @return the index of the tetrahedron containing the query, or -1 if outside
     */
    public int locate(Tuple3f query, int start, Random random) {
        if (start < 0) {
            start = lastTet;
        }
        
        // Make sure we start with a valid tetrahedron
        while (!isValidTetrahedron(start)) {
            start = findFirstValidTetrahedron();
            if (start == INVALID_INDEX) {
                return INVALID_INDEX;
            }
        }
        
        // Try the walk from the starting position
        int result = performWalk(query, start, random);
        if (result != INVALID_INDEX) {
            return result;
        }
        
        // If the walk failed, try from multiple random starting points
        int attempts = 0;
        while (attempts < 10) {
            // Pick a random valid tetrahedron as starting point
            int randomStart = findRandomValidTetrahedron(random);
            if (randomStart == INVALID_INDEX) {
                return INVALID_INDEX;
            }
            
            result = performWalk(query, randomStart, random);
            if (result != INVALID_INDEX) {
                return result;
            }
            
            attempts++;
        }
        
        // If all attempts failed, do an exhaustive search
        return exhaustiveLocate(query);
    }
    
    
    /**
     * Perform the walking algorithm from a specific starting tetrahedron.
     * 
     * @param query the query point
     * @param start the starting tetrahedron index
     * @param random source of randomness for the walk
     * @return the index of the tetrahedron containing the query, or -1 if walk failed
     */
    private int performWalk(Tuple3f query, int start, Random random) {
        TetrahedronProxy current = new TetrahedronProxy(this, start);
        
        // Check if query is inside starting tetrahedron
        int negativeFace = -1;
        for (int face = 0; face < 4; face++) {
            double orient = current.orientationWrt(face, query);
            if (orient < 0.0) {
                negativeFace = face;
                break;
            }
        }
        
        if (negativeFace == -1) {
            // Query is inside current tetrahedron
            lastTet = start;
            return start;
        }
        
        // Stochastic walk to find containing tetrahedron
        int stepCount = 0;
        while (true) {
            stepCount++;
            
            // Get the tetrahedron on the other side of the negative face
            TetrahedronProxy neighbor = current.getNeighbor(negativeFace);
            if (neighbor == null) {
                // We've hit a boundary - walk failed
                return INVALID_INDEX;
            }
            
            // Skip deleted tetrahedra
            if (!isValidTetrahedron(neighbor.getIndex())) {
                // Neighbor has been deleted
                return INVALID_INDEX;
            }
            
            // Find which face of neighbor we came through
            int neighborFace = findSharedFace(current.getIndex(), neighbor.getIndex());
            if (neighborFace == -1) {
                // Should not happen if neighbor tracking is correct
                return INVALID_INDEX;
            }
            
            // Check the other three faces of neighbor in random order
            int[] faceOrder = getRandomFaceOrder(neighborFace, random);
            boolean found = false;
            
            for (int i = 0; i < 3; i++) {
                int face = faceOrder[i];
                double orient = neighbor.orientationWrt(face, query);
                if (orient < 0.0) {
                    // Found a face where query is on negative side
                    negativeFace = face;
                    current = neighbor;
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                // Query is inside neighbor tetrahedron
                lastTet = neighbor.getIndex();
                return neighbor.getIndex();
            }
            
            if (stepCount > 100) {
                // Prevent infinite loops
                return INVALID_INDEX;
            }
        }
    }
    
    /**
     * Find a random valid tetrahedron.
     */
    private int findRandomValidTetrahedron(Random random) {
        int tetCount = tetrahedra.size() / VERTICES_PER_TET;
        int validCount = 0;
        for (int i = 0; i < tetCount; i++) {
            if (isValidTetrahedron(i)) {
                validCount++;
            }
        }
        
        if (validCount == 0) {
            return INVALID_INDEX;
        }
        
        int target = random.nextInt(validCount);
        int count = 0;
        for (int i = 0; i < tetCount; i++) {
            if (isValidTetrahedron(i)) {
                if (count == target) {
                    return i;
                }
                count++;
            }
        }
        
        return INVALID_INDEX;
    }
    
    /**
     * Exhaustive search through all tetrahedra to find the one containing the query point.
     * This is a fallback when the walking algorithm fails.
     */
    private int exhaustiveLocate(Tuple3f query) {
        int tetCount = tetrahedra.size() / VERTICES_PER_TET;
        for (int i = 0; i < tetCount; i++) {
            if (!isValidTetrahedron(i)) {
                continue;
            }
            
            TetrahedronProxy tet = new TetrahedronProxy(this, i);
            boolean inside = true;
            
            // Check all four faces
            for (int face = 0; face < 4; face++) {
                if (tet.orientationWrt(face, query) < 0.0) {
                    inside = false;
                    break;
                }
            }
            
            if (inside) {
                lastTet = i;
                return i;
            }
        }
        
        return INVALID_INDEX;
    }
    
    /**
     * Find which face of tet2 is shared with tet1
     */
    private int findSharedFace(int tet1, int tet2) {
        for (int face = 0; face < 4; face++) {
            if (getNeighbor(tet2, face) == tet1) {
                return face;
            }
        }
        return -1;
    }
    
    /**
     * Get a random ordering of the three faces excluding the given face
     */
    private int[] getRandomFaceOrder(int excludeFace, Random random) {
        int[] faces = new int[3];
        int idx = 0;
        for (int i = 0; i < 4; i++) {
            if (i != excludeFace) {
                faces[idx++] = i;
            }
        }
        
        // Fisher-Yates shuffle
        for (int i = 2; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temp = faces[i];
            faces[i] = faces[j];
            faces[j] = temp;
        }
        
        return faces;
    }
}