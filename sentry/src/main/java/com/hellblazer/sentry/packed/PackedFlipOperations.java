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
import java.util.List;

/**
 * Flip operations for the packed grid implementation.
 * These operations maintain the Delaunay condition by performing bistellar flips.
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class PackedFlipOperations {
    
    private final PackedGrid grid;
    
    public PackedFlipOperations(PackedGrid grid) {
        this.grid = grid;
    }
    
    /**
     * Perform the 1-to-4 bistellar flip. This flip replaces one tetrahedron with
     * four tetrahedra when a new vertex is inserted inside the tetrahedron.
     * 
     * @param tetIndex the index of the tetrahedron to flip
     * @param newVertex the new vertex index being inserted
     * @param ears list to collect faces that may need further flipping
     * @return one of the four new tetrahedra indices
     */
    public int flip1to4(int tetIndex, int newVertex, List<Integer> ears) {
        // Get the vertices of the original tetrahedron
        int[] verts = new int[4];
        grid.getTetrahedronVertices(tetIndex, verts);
        int a = verts[0];
        int b = verts[1];
        int c = verts[2];
        int d = verts[3];
        
        // Create four new tetrahedra
        // t0: ABC + new vertex
        int t0 = grid.createTetrahedron(a, b, c, newVertex);
        // t1: ADB + new vertex  
        int t1 = grid.createTetrahedron(a, d, b, newVertex);
        // t2: ACD + new vertex
        int t2 = grid.createTetrahedron(a, c, d, newVertex);
        // t3: BDC + new vertex
        int t3 = grid.createTetrahedron(b, d, c, newVertex);
        
        // Set internal neighbors between the new tetrahedra
        // Each new tetrahedron has the new vertex as vertex D (index 3)
        // The face opposite D connects to the original neighbors
        
        // t0 neighbors: face opposite A connects to t3, B to t2, C to t1
        grid.setNeighbor(t0, 0, t3); // face opposite A
        grid.setNeighbor(t0, 1, t2); // face opposite B
        grid.setNeighbor(t0, 2, t1); // face opposite C
        
        // t1 neighbors: face opposite A connects to t3, B to t0, C to t2
        grid.setNeighbor(t1, 0, t3); // face opposite A
        grid.setNeighbor(t1, 1, t0); // face opposite B
        grid.setNeighbor(t1, 2, t2); // face opposite C
        
        // t2 neighbors: face opposite A connects to t3, B to t1, C to t0
        grid.setNeighbor(t2, 0, t3); // face opposite A
        grid.setNeighbor(t2, 1, t1); // face opposite B
        grid.setNeighbor(t2, 2, t0); // face opposite C
        
        // t3 neighbors: face opposite A connects to t2, B to t0, C to t1
        grid.setNeighbor(t3, 0, t2); // face opposite A
        grid.setNeighbor(t3, 1, t0); // face opposite B
        grid.setNeighbor(t3, 2, t1); // face opposite C
        
        // Patch external neighbors
        // Each new tet inherits one face from the original
        patchExternalNeighbor(tetIndex, 3, t0, 3); // Face opposite D -> t0's face opposite D
        patchExternalNeighbor(tetIndex, 2, t1, 3); // Face opposite C -> t1's face opposite D
        patchExternalNeighbor(tetIndex, 1, t2, 3); // Face opposite B -> t2's face opposite D
        patchExternalNeighbor(tetIndex, 0, t3, 3); // Face opposite A -> t3's face opposite D
        
        // Delete the original tetrahedron
        grid.deleteTetrahedron(tetIndex);
        
        // Add faces opposite the new vertex to ears for processing
        addFaceToEars(t0, 3, ears); // Face opposite new vertex
        addFaceToEars(t1, 3, ears);
        addFaceToEars(t2, 3, ears);
        addFaceToEars(t3, 3, ears);
        
        return t1; // Return one of the new tetrahedra
    }
    
    /**
     * Perform the 2-to-3 bistellar flip. This flip replaces two tetrahedra that share
     * a face with three tetrahedra that share an edge.
     * 
     * @param face the shared face (encoded as tetIndex * 4 + faceIndex)
     * @return array of the three new tetrahedra indices
     */
    public int[] flip2to3(int face) {
        int tetIndex = face / 4;
        int faceIndex = face % 4;
        
        TetrahedronProxy incident = new TetrahedronProxy(grid, tetIndex);
        TetrahedronProxy adjacent = incident.getNeighbor(faceIndex);
        if (adjacent == null) {
            throw new IllegalArgumentException("No adjacent tetrahedron for flip2to3");
        }
        
        // Get the shared face vertices and the opposing vertices
        int[] incidentVerts = incident.getVertices();
        int[] adjacentVerts = adjacent.getVertices();
        
        // Find which face of adjacent is shared with incident
        int adjacentFace = -1;
        for (int i = 0; i < 4; i++) {
            if (grid.getNeighbor(adjacent.getIndex(), i) == tetIndex) {
                adjacentFace = i;
                break;
            }
        }
        
        // Get the vertices of the shared face (3 vertices)
        int[] faceVerts = new int[3];
        int idx = 0;
        for (int i = 0; i < 4; i++) {
            if (i != faceIndex) {
                faceVerts[idx++] = incidentVerts[i];
            }
        }
        
        // Get the opposing vertices
        int incidentOpposite = incidentVerts[faceIndex];
        int adjacentOpposite = adjacentVerts[adjacentFace];
        
        // Create three new tetrahedra
        int t0 = grid.createTetrahedron(faceVerts[0], incidentOpposite, faceVerts[1], adjacentOpposite);
        int t1 = grid.createTetrahedron(faceVerts[1], incidentOpposite, faceVerts[2], adjacentOpposite);
        int t2 = grid.createTetrahedron(faceVerts[0], faceVerts[2], incidentOpposite, adjacentOpposite);
        
        // Set internal neighbors
        grid.setNeighbor(t0, 0, t1); // face opposite first vertex
        grid.setNeighbor(t0, 2, t2); // face opposite third vertex
        
        grid.setNeighbor(t1, 0, t2); // face opposite first vertex
        grid.setNeighbor(t1, 2, t0); // face opposite third vertex
        
        grid.setNeighbor(t2, 0, t1); // face opposite first vertex
        grid.setNeighbor(t2, 1, t0); // face opposite second vertex
        
        // Patch external neighbors from incident tetrahedron
        for (int i = 0; i < 4; i++) {
            if (i != faceIndex) {
                int neighbor = grid.getNeighbor(tetIndex, i);
                if (neighbor != PackedGrid.INVALID_INDEX) {
                    // Determine which new tet gets this neighbor
                    int targetTet = determineTargetTetForNeighbor(i, faceIndex, incidentVerts, t0, t1, t2);
                    patchNeighborReference(neighbor, tetIndex, targetTet);
                }
            }
        }
        
        // Patch external neighbors from adjacent tetrahedron
        for (int i = 0; i < 4; i++) {
            if (i != adjacentFace) {
                int neighbor = grid.getNeighbor(adjacent.getIndex(), i);
                if (neighbor != PackedGrid.INVALID_INDEX) {
                    // Determine which new tet gets this neighbor
                    int targetTet = determineTargetTetForNeighbor(i, adjacentFace, adjacentVerts, t0, t1, t2);
                    patchNeighborReference(neighbor, adjacent.getIndex(), targetTet);
                }
            }
        }
        
        // Delete the original tetrahedra
        grid.deleteTetrahedron(tetIndex);
        grid.deleteTetrahedron(adjacent.getIndex());
        
        return new int[] { t0, t1, t2 };
    }
    
    /**
     * Perform the 3-to-2 bistellar flip. This is the inverse of 2-to-3.
     * Three tetrahedra sharing an edge are replaced by two sharing a face.
     * 
     * @param edge the shared edge (encoded with the three tet indices)
     * @param reflexVertex the vertex index of the reflex edge
     * @return array of the two new tetrahedra indices
     */
    public int[] flip3to2(int[] tetIndices, int reflexVertex) {
        // TODO: Implement 3-to-2 flip
        // This is more complex and requires identifying the three tets sharing an edge
        throw new UnsupportedOperationException("flip3to2 not yet implemented");
    }
    
    /**
     * Perform the 4-to-1 bistellar flip. This is the inverse of 1-to-4.
     * Four tetrahedra sharing a vertex are replaced by one tetrahedron.
     * This is used for vertex deletion.
     * 
     * @param vertexIndex the vertex to remove
     * @return the index of the new tetrahedron
     */
    public int flip4to1(int vertexIndex) {
        // TODO: Implement 4-to-1 flip
        // This requires finding all tets incident to the vertex
        throw new UnsupportedOperationException("flip4to1 not yet implemented");
    }
    
    /**
     * Helper to patch external neighbor relationships
     */
    private void patchExternalNeighbor(int oldTet, int oldFace, int newTet, int newFace) {
        int neighbor = grid.getNeighbor(oldTet, oldFace);
        if (neighbor != PackedGrid.INVALID_INDEX) {
            // Find which face of neighbor points back to oldTet
            for (int i = 0; i < 4; i++) {
                if (grid.getNeighbor(neighbor, i) == oldTet) {
                    grid.setNeighbors(newTet, newFace, neighbor, i);
                    break;
                }
            }
        }
    }
    
    /**
     * Helper to add a face to the ears list if it has an adjacent tetrahedron
     */
    private void addFaceToEars(int tetIndex, int faceIndex, List<Integer> ears) {
        int neighbor = grid.getNeighbor(tetIndex, faceIndex);
        if (neighbor != PackedGrid.INVALID_INDEX && neighbor >= 0) {
            ears.add(tetIndex * 4 + faceIndex);
        }
    }
    
    /**
     * Helper to patch a neighbor's reference from an old tet to a new tet
     */
    private void patchNeighborReference(int neighbor, int oldTet, int newTet) {
        for (int i = 0; i < 4; i++) {
            if (grid.getNeighbor(neighbor, i) == oldTet) {
                grid.setNeighbor(neighbor, i, newTet);
                break;
            }
        }
    }
    
    /**
     * Helper to determine which new tet should inherit a neighbor relationship
     */
    private int determineTargetTetForNeighbor(int neighborFace, int sharedFace, 
                                              int[] verts, int t0, int t1, int t2) {
        // This is a simplified version - proper implementation would need to
        // check which new tet contains the face that was adjacent to the neighbor
        // For now, distribute based on face index
        if (neighborFace == 0) return t0;
        if (neighborFace == 1) return t1;
        return t2;
    }
}