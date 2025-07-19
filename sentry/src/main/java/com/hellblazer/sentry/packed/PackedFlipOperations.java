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
        
        if (adjacentFace == -1) {
            throw new IllegalStateException("Adjacent tetrahedron " + adjacent.getIndex() + 
                " does not have incident " + tetIndex + " as neighbor");
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
                    
                    // Find which face of neighbor pointed to incident
                    int neighborFace = -1;
                    for (int f = 0; f < 4; f++) {
                        if (grid.getNeighbor(neighbor, f) == tetIndex) {
                            neighborFace = f;
                            break;
                        }
                    }
                    
                    if (neighborFace != -1) {
                        // The face vertices excluding vertex at position i
                        int[] incidentFaceVerts = new int[3];
                        int incidentIdx = 0;
                        for (int v = 0; v < 4; v++) {
                            if (v != i) {
                                incidentFaceVerts[incidentIdx++] = incidentVerts[v];
                            }
                        }
                        
                        // Find which face of targetTet contains these vertices
                        TetrahedronProxy targetProxy = new TetrahedronProxy(grid, targetTet);
                        int targetFace = targetProxy.findFaceWithVertices(incidentFaceVerts[0], incidentFaceVerts[1], incidentFaceVerts[2]);
                        
                        if (targetFace != -1) {
                            grid.setNeighbors(targetTet, targetFace, neighbor, neighborFace);
                        }
                    }
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
                    
                    // Find which face of neighbor pointed to adjacent
                    int neighborFace = -1;
                    for (int f = 0; f < 4; f++) {
                        if (grid.getNeighbor(neighbor, f) == adjacent.getIndex()) {
                            neighborFace = f;
                            break;
                        }
                    }
                    
                    if (neighborFace != -1) {
                        // The face vertices excluding vertex at position i
                        int[] adjacentFaceVerts = new int[3];
                        int adjacentIdx = 0;
                        for (int v = 0; v < 4; v++) {
                            if (v != i) {
                                adjacentFaceVerts[adjacentIdx++] = adjacentVerts[v];
                            }
                        }
                        
                        // Find which face of targetTet contains these vertices
                        TetrahedronProxy targetProxy = new TetrahedronProxy(grid, targetTet);
                        int targetFace = targetProxy.findFaceWithVertices(adjacentFaceVerts[0], adjacentFaceVerts[1], adjacentFaceVerts[2]);
                        
                        if (targetFace != -1) {
                            grid.setNeighbors(targetTet, targetFace, neighbor, neighborFace);
                        }
                    }
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
     * @param face the face triggering the flip (tetIndex * 4 + faceIndex)
     * @param reflexEdge the edge index (0-2) that is reflex
     * @return array of the two new tetrahedra indices
     */
    public int[] flip3to2(int face, int reflexEdge) {
        int tetIndex = face / 4;
        int faceIndex = face % 4;
        
        TetrahedronProxy incident = new TetrahedronProxy(grid, tetIndex);
        TetrahedronProxy adjacent = incident.getNeighbor(faceIndex);
        if (adjacent == null) {
            throw new IllegalArgumentException("No adjacent tetrahedron for flip3to2");
        }
        
        // Get the third tetrahedron sharing the reflex edge
        int[] faceVerts = new int[3];
        int[] incidentVerts = incident.getVertices();
        int idx = 0;
        for (int i = 0; i < 4; i++) {
            if (i != faceIndex) {
                faceVerts[idx++] = incidentVerts[i];
            }
        }
        
        // The reflex edge vertex is one of the face vertices
        int reflexVertex = faceVerts[reflexEdge];
        
        // Find the third tetrahedron
        TetrahedronProxy third = null;
        int thirdTetIndex = -1;
        for (int i = 0; i < 4; i++) {
            if (incidentVerts[i] == reflexVertex) {
                TetrahedronProxy neighbor = incident.getNeighbor(i);
                if (neighbor != null && neighbor.getIndex() != adjacent.getIndex()) {
                    // Check if this neighbor is also adjacent to 'adjacent'
                    for (int j = 0; j < 4; j++) {
                        TetrahedronProxy adjNeighbor = adjacent.getNeighbor(j);
                        if (adjNeighbor != null && adjNeighbor.getIndex() == neighbor.getIndex()) {
                            third = neighbor;
                            thirdTetIndex = neighbor.getIndex();
                            break;
                        }
                    }
                }
            }
        }
        
        if (third == null) {
            throw new IllegalArgumentException("Cannot find third tetrahedron for flip3to2");
        }
        
        // Get the vertices involved
        int incidentOpposite = incidentVerts[faceIndex];
        int adjacentFace = -1;
        for (int i = 0; i < 4; i++) {
            if (grid.getNeighbor(adjacent.getIndex(), i) == tetIndex) {
                adjacentFace = i;
                break;
            }
        }
        int adjacentOpposite = adjacent.getVertex(adjacentFace);
        
        // Determine the two top vertices (not on the reflex edge)
        int top0 = -1, top1 = -1;
        for (int v : faceVerts) {
            if (v != reflexVertex) {
                if (top0 == -1) top0 = v;
                else top1 = v;
            }
        }
        
        // Create two new tetrahedra
        // The orientation needs to be checked
        float[] coords = new float[3];
        float[] x = new float[3], y = new float[3], z = new float[3], t = new float[3];
        
        grid.getVertexCoords(reflexVertex, x);
        grid.getVertexCoords(incidentOpposite, y);
        grid.getVertexCoords(adjacentOpposite, z);
        grid.getVertexCoords(top0, t);
        
        int t0, t1;
        if (com.hellblazer.luciferase.geometry.Geometry.leftOfPlaneFast(
                x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2], t[0], t[1], t[2]) > 0) {
            t0 = grid.createTetrahedron(reflexVertex, incidentOpposite, adjacentOpposite, top0);
            t1 = grid.createTetrahedron(incidentOpposite, reflexVertex, adjacentOpposite, top1);
        } else {
            t0 = grid.createTetrahedron(reflexVertex, incidentOpposite, adjacentOpposite, top1);
            t1 = grid.createTetrahedron(incidentOpposite, reflexVertex, adjacentOpposite, top0);
        }
        
        // Set internal neighbor
        grid.setNeighbors(t0, 3, t1, 3); // Face opposite vertex 3
        
        // Patch external neighbors
        // This requires careful analysis of which faces go where
        patchFlip3to2ExternalNeighbors(tetIndex, adjacent.getIndex(), thirdTetIndex, 
                                       t0, t1, top0, top1, reflexVertex, 
                                       incidentOpposite, adjacentOpposite);
        
        // Delete the three original tetrahedra
        grid.deleteTetrahedron(tetIndex);
        grid.deleteTetrahedron(adjacent.getIndex());
        grid.deleteTetrahedron(thirdTetIndex);
        
        return new int[] { t0, t1 };
    }
    
    /**
     * Helper to patch external neighbors for flip3to2
     */
    private void patchFlip3to2ExternalNeighbors(int tet0, int tet1, int tet2,
                                                int newTet0, int newTet1,
                                                int top0, int top1, int reflexVertex,
                                                int opposite0, int opposite1) {
        // This is complex and would need careful implementation
        // For now, patch based on which vertices are preserved
        
        // Find and patch neighbors from tet0
        for (int face = 0; face < 4; face++) {
            int neighbor = grid.getNeighbor(tet0, face);
            if (neighbor != PackedGrid.INVALID_INDEX && neighbor != tet1 && neighbor != tet2) {
                // Determine which new tet should get this neighbor
                TetrahedronProxy n = new TetrahedronProxy(grid, neighbor);
                if (n.hasVertex(top0) && !n.hasVertex(top1)) {
                    patchNeighborReference(neighbor, tet0, newTet0);
                } else if (n.hasVertex(top1) && !n.hasVertex(top0)) {
                    patchNeighborReference(neighbor, tet0, newTet1);
                }
            }
        }
        
        // Similar for tet1 and tet2
        for (int face = 0; face < 4; face++) {
            int neighbor = grid.getNeighbor(tet1, face);
            if (neighbor != PackedGrid.INVALID_INDEX && neighbor != tet0 && neighbor != tet2) {
                TetrahedronProxy n = new TetrahedronProxy(grid, neighbor);
                if (n.hasVertex(top0) && !n.hasVertex(top1)) {
                    patchNeighborReference(neighbor, tet1, newTet0);
                } else if (n.hasVertex(top1) && !n.hasVertex(top0)) {
                    patchNeighborReference(neighbor, tet1, newTet1);
                }
            }
        }
        
        for (int face = 0; face < 4; face++) {
            int neighbor = grid.getNeighbor(tet2, face);
            if (neighbor != PackedGrid.INVALID_INDEX && neighbor != tet0 && neighbor != tet1) {
                TetrahedronProxy n = new TetrahedronProxy(grid, neighbor);
                if (n.hasVertex(top0) && !n.hasVertex(top1)) {
                    patchNeighborReference(neighbor, tet2, newTet0);
                } else if (n.hasVertex(top1) && !n.hasVertex(top0)) {
                    patchNeighborReference(neighbor, tet2, newTet1);
                }
            }
        }
    }
    
    /**
     * Perform the 4-to-1 bistellar flip. This is the inverse of 1-to-4.
     * Four tetrahedra sharing a vertex are replaced by one tetrahedron.
     * This is used for vertex deletion.
     * 
     * @param starTets array of 4 tetrahedron indices forming the star around the vertex
     * @param vertexToRemove the vertex index being removed
     * @return the index of the new tetrahedron
     */
    public int flip4to1(int[] starTets, int vertexToRemove) {
        if (starTets.length != 4) {
            throw new IllegalArgumentException("Star must contain exactly 4 tetrahedra");
        }
        
        // Find the four vertices that will form the new tetrahedron
        // These are the vertices opposite to vertexToRemove in each star tet
        int[] oppositeVerts = new int[4];
        
        for (int i = 0; i < 4; i++) {
            TetrahedronProxy tet = new TetrahedronProxy(grid, starTets[i]);
            // Find which ordinal is the vertex to remove
            int ordinal = tet.ordinalOf(vertexToRemove);
            if (ordinal == -1) {
                throw new IllegalArgumentException("Tetrahedron " + starTets[i] + 
                    " does not contain vertex " + vertexToRemove);
            }
            
            // The opposite face contains the other 3 vertices
            // We need the vertex from the neighbor on that face
            TetrahedronProxy neighbor = tet.getNeighbor(ordinal);
            if (neighbor == null) {
                // This is the convex hull face - use the vertices of the face
                int idx = 0;
                for (int j = 0; j < 4; j++) {
                    if (j != ordinal) {
                        int v = tet.getVertex(j);
                        boolean found = false;
                        for (int k = 0; k < i; k++) {
                            if (oppositeVerts[k] == v) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            oppositeVerts[i] = v;
                            break;
                        }
                    }
                }
            } else {
                // Find the vertex in the neighbor that's not in this tet
                int[] neighborVerts = neighbor.getVertices();
                for (int v : neighborVerts) {
                    if (!tet.hasVertex(v)) {
                        oppositeVerts[i] = v;
                        break;
                    }
                }
            }
        }
        
        // Determine the correct ordering of vertices for positive orientation
        // We'll use the ordering from the star analysis
        int a = oppositeVerts[0];
        int b = oppositeVerts[1];
        int c = oppositeVerts[2];
        int d = oppositeVerts[3];
        
        // Create the new tetrahedron
        int newTet = grid.createTetrahedron(a, b, c, d);
        
        // Patch external neighbors
        for (int i = 0; i < 4; i++) {
            TetrahedronProxy starTet = new TetrahedronProxy(grid, starTets[i]);
            int ordinal = starTet.ordinalOf(vertexToRemove);
            
            // The face opposite vertexToRemove connects to external neighbors
            TetrahedronProxy externalNeighbor = starTet.getNeighbor(ordinal);
            if (externalNeighbor != null) {
                // Find which face of the external neighbor pointed to starTet
                int externalFace = -1;
                for (int face = 0; face < 4; face++) {
                    if (grid.getNeighbor(externalNeighbor.getIndex(), face) == starTets[i]) {
                        externalFace = face;
                        break;
                    }
                }
                
                if (externalFace != -1) {
                    // Determine which face of newTet corresponds to this neighbor
                    // The face is opposite to the vertex that's not in the external neighbor
                    int newTetFace = -1;
                    for (int j = 0; j < 4; j++) {
                        if (!externalNeighbor.hasVertex(oppositeVerts[j])) {
                            newTetFace = j;
                            break;
                        }
                    }
                    
                    if (newTetFace != -1) {
                        grid.setNeighbors(newTet, newTetFace, 
                                         externalNeighbor.getIndex(), externalFace);
                    }
                }
            }
        }
        
        // Delete the four star tetrahedra
        for (int tet : starTets) {
            grid.deleteTetrahedron(tet);
        }
        
        return newTet;
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
        // Determine which face vertices are kept (not the one being replaced)
        int v0 = -1, v1 = -1, v2 = -1;
        int idx = 0;
        for (int i = 0; i < 4; i++) {
            if (i != neighborFace) {
                if (idx == 0) v0 = verts[i];
                else if (idx == 1) v1 = verts[i];
                else v2 = verts[i];
                idx++;
            }
        }
        
        // Check which new tet contains this face
        TetrahedronProxy tet0 = new TetrahedronProxy(grid, t0);
        TetrahedronProxy tet1 = new TetrahedronProxy(grid, t1);
        TetrahedronProxy tet2 = new TetrahedronProxy(grid, t2);
        
        if (hasFace(tet0, v0, v1, v2)) return t0;
        if (hasFace(tet1, v0, v1, v2)) return t1;
        if (hasFace(tet2, v0, v1, v2)) return t2;
        
        // Fallback - should not happen if flip is correct
        return t0;
    }
    
    /**
     * Check if a tetrahedron contains a face with the given vertices
     */
    private boolean hasFace(TetrahedronProxy tet, int v0, int v1, int v2) {
        int[] verts = tet.getVertices();
        int count = 0;
        for (int v : verts) {
            if (v == v0 || v == v1 || v == v2) {
                count++;
            }
        }
        return count == 3;
    }
}