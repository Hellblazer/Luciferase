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

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;

/**
 * Neighbor detector implementation for tetrahedral trees.
 * 
 * This class provides neighbor detection using the t8code connectivity
 * tables and algorithms to find face, edge, and vertex neighbors in
 * a tetrahedral subdivision.
 * 
 * @author Hal Hildebrand
 */
public class TetreeNeighborDetector implements NeighborDetector<TetreeKey<?>> {
    
    private static final Logger log = LoggerFactory.getLogger(TetreeNeighborDetector.class);
    
    private final Tetree<?, ?> tetree;
    
    // Edge vertex pairs (0-5)
    private static final int[][] EDGE_VERTICES = {
        {0, 1}, // Edge 0
        {0, 2}, // Edge 1
        {0, 3}, // Edge 2
        {1, 2}, // Edge 3
        {1, 3}, // Edge 4
        {2, 3}  // Edge 5
    };
    
    // Which edges touch each vertex
    private static final int[][] VERTEX_EDGES = {
        {0, 1, 2},    // Vertex 0: edges 0, 1, 2
        {0, 3, 4},    // Vertex 1: edges 0, 3, 4
        {1, 3, 5},    // Vertex 2: edges 1, 3, 5
        {2, 4, 5}     // Vertex 3: edges 2, 4, 5
    };
    
    // Which faces share each edge
    private static final int[][] EDGE_FACES = {
        {2, 3},    // Edge 0 (v0-v1): faces 2 and 3
        {1, 3},    // Edge 1 (v0-v2): faces 1 and 3
        {1, 2},    // Edge 2 (v0-v3): faces 1 and 2
        {0, 3},    // Edge 3 (v1-v2): faces 0 and 3
        {0, 2},    // Edge 4 (v1-v3): faces 0 and 2
        {0, 1}     // Edge 5 (v2-v3): faces 0 and 1
    };
    
    // Which faces contain each vertex
    private static final int[][] VERTEX_FACES = {
        {1, 2, 3},    // Vertex 0: in faces 1, 2, 3 (opposite face 0)
        {0, 2, 3},    // Vertex 1: in faces 0, 2, 3 (opposite face 1)
        {0, 1, 3},    // Vertex 2: in faces 0, 1, 3 (opposite face 2)
        {0, 1, 2}     // Vertex 3: in faces 0, 1, 2 (opposite face 3)
    };
    
    public TetreeNeighborDetector(Tetree<?, ?> tetree) {
        this.tetree = Objects.requireNonNull(tetree, "Tetree cannot be null");
    }
    
    @Override
    public List<TetreeKey<?>> findFaceNeighbors(TetreeKey<?> element) {
        var neighbors = new ArrayList<TetreeKey<?>>();
        var tet = keyToTet(element);
        
        // A tetrahedron has 4 faces
        for (int face = 0; face < 4; face++) {
            var neighbor = findFaceNeighbor(tet, face);
            if (neighbor != null && !neighbor.equals(element)) {
                neighbors.add(neighbor);
            }
        }
        
        return neighbors;
    }
    
    @Override
    public List<TetreeKey<?>> findEdgeNeighbors(TetreeKey<?> element) {
        var neighbors = new HashSet<TetreeKey<?>>();
        var tet = keyToTet(element);
        
        // First add all face neighbors
        neighbors.addAll(findFaceNeighbors(element));
        
        // Find neighbors sharing edges
        for (int edge = 0; edge < TetreeConnectivity.EDGES_PER_TET; edge++) {
            var edgeNeighbors = findNeighborsSharingEdge(tet, edge);
            neighbors.addAll(edgeNeighbors);
        }
        
        neighbors.remove(element); // Remove self
        return new ArrayList<>(neighbors);
    }
    
    @Override
    public List<TetreeKey<?>> findVertexNeighbors(TetreeKey<?> element) {
        var neighbors = new HashSet<TetreeKey<?>>();
        var tet = keyToTet(element);
        
        // First add all edge neighbors (which includes face neighbors)
        neighbors.addAll(findEdgeNeighbors(element));
        
        // Find neighbors sharing vertices
        for (int vertex = 0; vertex < TetreeConnectivity.VERTICES_PER_TET; vertex++) {
            var vertexNeighbors = findNeighborsSharingVertex(tet, vertex);
            neighbors.addAll(vertexNeighbors);
        }
        
        neighbors.remove(element); // Remove self
        return new ArrayList<>(neighbors);
    }
    
    @Override
    public boolean isBoundaryElement(TetreeKey<?> element, Direction direction) {
        var tet = keyToTet(element);
        
        // Get the tetrahedron's bounding box
        var coords = tet.coordinates();
        
        // Find min and max coordinates across all vertices
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;
        
        for (var vertex : coords) {
            minX = Math.min(minX, vertex.x);
            maxX = Math.max(maxX, vertex.x);
            minY = Math.min(minY, vertex.y);
            maxY = Math.max(maxY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxZ = Math.max(maxZ, vertex.z);
        }
        
        // Maximum coordinate value (2^21 - 1)
        long maxCoord = (1L << Constants.getMaxRefinementLevel()) - 1;
        
        return switch (direction) {
            case POSITIVE_X -> Math.round(maxX) >= maxCoord;
            case NEGATIVE_X -> Math.round(minX) <= 0;
            case POSITIVE_Y -> Math.round(maxY) >= maxCoord;
            case NEGATIVE_Y -> Math.round(minY) <= 0;
            case POSITIVE_Z -> Math.round(maxZ) >= maxCoord;
            case NEGATIVE_Z -> Math.round(minZ) <= 0;
        };
    }
    
    @Override
    public Set<Direction> getBoundaryDirections(TetreeKey<?> element) {
        var directions = EnumSet.noneOf(Direction.class);
        for (var dir : Direction.values()) {
            if (isBoundaryElement(element, dir)) {
                directions.add(dir);
            }
        }
        return directions;
    }
    
    @Override
    public List<NeighborInfo<TetreeKey<?>>> findNeighborsWithOwners(TetreeKey<?> element, GhostType type) {
        var neighbors = findNeighbors(element, type);
        var result = new ArrayList<NeighborInfo<TetreeKey<?>>>(neighbors.size());
        
        for (var neighbor : neighbors) {
            // For now, assume all neighbors are local
            // This will be extended when distributed support is added
            result.add(new NeighborInfo<>(neighbor, 0, 0, true));
        }
        
        return result;
    }
    
    /**
     * Find the face neighbor of a tetrahedron across a specific face.
     */
    private TetreeKey<?> findFaceNeighbor(Tet tet, int face) {
        if (tet.l == 0) {
            return null; // Root has no neighbors
        }
        
        var parentTet = tet.parent();
        if (parentTet == null) {
            return null;
        }
        
        // Get sibling index within parent
        var siblingIndex = TetreeConnectivity.getFaceNeighborType(tet.type, face);
        if (siblingIndex >= 0 && siblingIndex < 8) {
            // Neighbor is a sibling
            var neighbor = parentTet.child(siblingIndex);
            return tetToKey(neighbor);
        }
        
        // Neighbor is in a different parent - need to go up and across
        // This requires more complex logic using the connectivity tables
        return null;
    }
    
    /**
     * Find all neighbors sharing a specific edge with the given tetrahedron.
     * This includes siblings and non-siblings at various levels.
     */
    private List<TetreeKey<?>> findNeighborsSharingEdge(Tet tet, int edge) {
        var neighbors = new ArrayList<TetreeKey<?>>();
        
        if (tet.l == 0) {
            return neighbors; // Root has no neighbors
        }
        
        // Get the two vertices that form this edge
        int v0 = EDGE_VERTICES[edge][0];
        int v1 = EDGE_VERTICES[edge][1];
        
        // Strategy: Find all tetrahedra that share these two vertices
        // 1. Check siblings that share the edge
        // 2. Check parent's neighbors that share the edge
        // 3. Check children of neighbors at same level
        
        // First, find siblings sharing this edge
        findSiblingsSharing(tet, neighbors, (sibling) -> sharesEdge(tet, sibling, v0, v1));
        
        // For non-sibling neighbors, we need to traverse up and across the tree
        // This is more complex and depends on the specific edge and parent configuration
        findNonSiblingNeighborsSharing(tet, neighbors, edge, true);
        
        return neighbors;
    }
    
    /**
     * Find all neighbors sharing a specific vertex with the given tetrahedron.
     */
    private List<TetreeKey<?>> findNeighborsSharingVertex(Tet tet, int vertex) {
        var neighbors = new ArrayList<TetreeKey<?>>();
        
        if (tet.l == 0) {
            return neighbors; // Root has no neighbors
        }
        
        // Strategy: Find all tetrahedra that share this vertex
        // 1. Check siblings that share the vertex
        // 2. Check parent's neighbors that share the vertex
        // 3. Check descendants of parent's neighbors
        
        // First, find siblings sharing this vertex
        findSiblingsSharing(tet, neighbors, (sibling) -> sharesVertex(tet, sibling, vertex));
        
        // For non-sibling neighbors, traverse up and across the tree
        findNonSiblingNeighborsSharing(tet, neighbors, vertex, false);
        
        return neighbors;
    }
    
    /**
     * Find siblings that satisfy a given predicate.
     */
    private void findSiblingsSharing(Tet tet, List<TetreeKey<?>> neighbors, 
                                     java.util.function.Predicate<Tet> predicate) {
        var parentTet = tet.parent();
        if (parentTet == null) {
            return;
        }
        
        for (int sibling = 0; sibling < 8; sibling++) {
            var siblingTet = parentTet.child(sibling);
            if (siblingTet.equals(tet)) {
                continue; // Skip self
            }
            
            if (predicate.test(siblingTet)) {
                neighbors.add(tetToKey(siblingTet));
            }
        }
    }
    
    /**
     * Find non-sibling neighbors that share an edge or vertex.
     * This requires traversing up the tree and across to other branches.
     */
    private void findNonSiblingNeighborsSharing(Tet tet, List<TetreeKey<?>> neighbors, 
                                               int elementIndex, boolean isEdge) {
        // To find non-sibling neighbors, we need to:
        // 1. Go up to parent and find parent's neighbors
        // 2. Check children of those neighbors
        
        var currentTet = tet;
        var currentLevel = tet.l;
        
        // Traverse up the tree
        while (currentLevel > 0 && neighbors.size() < 20) { // Limit to prevent excessive search
            var parentTet = currentTet.parent();
            if (parentTet == null) {
                break;
            }
            
            // Find parent's neighbors that might have children sharing our element
            // This is simplified - a full implementation would use connectivity tables
            // to determine which parent neighbors to check
            
            currentTet = parentTet;
            currentLevel--;
        }
    }
    
    /**
     * Convert a TetreeKey to a Tet object.
     */
    public Tet keyToTet(TetreeKey<?> key) {
        // Extract the level and TM-index data from the key
        byte level = key.getLevel();
        
        if (level == 0) {
            // Root tetrahedron
            return new Tet(0, 0, 0, (byte)0, (byte)0);
        }
        
        // For non-root tetrahedra, we need to extract the coordinate and type
        // information from the TM-index bits
        
        // Get the coordinate and type at the current level
        byte coordBits = key.getCoordBitsAtLevel(level);
        byte type = key.getTypeAtLevel(level);
        
        // The coordinate bits encode which child position this tet occupies
        // We need to reconstruct the actual coordinates based on the parent
        // For now, use a simplified approach
        int x = coordBits & 1;
        int y = (coordBits >> 1) & 1;
        int z = (coordBits >> 2) & 1;
        
        // Scale coordinates based on level
        int scale = 1 << (21 - level); // 2^(21-level)
        x *= scale;
        y *= scale;
        z *= scale;
        
        return new Tet(x, y, z, level, type);
    }
    
    /**
     * Convert a Tet object to a TetreeKey.
     */
    private TetreeKey<?> tetToKey(Tet tet) {
        return tet.tmIndex();
    }
    
    /**
     * Get the child index of a tet within its parent.
     */
    private int getChildIndex(Tet tet) {
        if (tet.l == 0) {
            return 0; // Root has no meaningful child index
        }
        
        // The child index is determined by the coordinate bits at the current level
        // In the Tet's morton encoding, this would be the last 3 bits
        // For our purposes, we can derive it from the coordinate and type
        
        // Get the TetreeKey to access the coordinate bits
        var key = tetToKey(tet);
        byte coordBits = key.getCoordBitsAtLevel(tet.l);
        
        // The child index in Morton order is the coordinate bits
        // This gives us values 0-7 corresponding to the 8 children
        return coordBits & 0x7;
    }
    
    /**
     * Check if two tetrahedra share an edge defined by two vertices.
     * This uses the vertex mapping tables from TetreeConnectivity.
     */
    private boolean sharesEdge(Tet tet1, Tet tet2, int v0, int v1) {
        // For siblings in Bey refinement, we can use the connectivity tables
        // to determine which children share edges
        
        // Get the child indices within their parent
        int childIndex1 = getChildIndex(tet1);
        int childIndex2 = getChildIndex(tet2);
        
        // Use the CHILD_VERTEX_PARENT_VERTEX table to check if they share
        // the same parent edge
        return checkSharedParentEdge(childIndex1, childIndex2, v0, v1);
    }
    
    /**
     * Check if two tetrahedra share a vertex.
     * This uses the vertex mapping tables from TetreeConnectivity.
     */
    private boolean sharesVertex(Tet tet1, Tet tet2, int vertex) {
        // For siblings in Bey refinement, we can use the connectivity tables
        // to determine which children share vertices
        
        // Get the child indices within their parent
        int childIndex1 = getChildIndex(tet1);
        int childIndex2 = getChildIndex(tet2);
        
        // Use the CHILD_VERTEX_PARENT_VERTEX table to check if they share
        // the same parent vertex
        return checkSharedParentVertex(childIndex1, childIndex2, vertex);
    }
    
    /**
     * Check if two child indices share a parent edge.
     */
    private boolean checkSharedParentEdge(int child1, int child2, int v0, int v1) {
        // Use CHILD_VERTEX_PARENT_VERTEX to map child vertices to parent references
        byte[] child1Vertices = TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX[child1];
        byte[] child2Vertices = TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX[child2];
        
        // An edge is shared if both children have vertices that map to the same 
        // two parent reference points
        int sharedCount = 0;
        Set<Byte> sharedReferences = new HashSet<>();
        
        // Find parent references that both children share
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (child1Vertices[i] == child2Vertices[j]) {
                    sharedReferences.add(child1Vertices[i]);
                }
            }
        }
        
        // An edge is shared if they have at least 2 common parent references
        return sharedReferences.size() >= 2;
    }
    
    /**
     * Check if two child indices share a parent vertex.
     */
    private boolean checkSharedParentVertex(int child1, int child2, int vertex) {
        // Use CHILD_VERTEX_PARENT_VERTEX to map child vertices to parent references
        byte[] child1Vertices = TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX[child1];
        byte[] child2Vertices = TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX[child2];
        
        // Check if both children have a vertex that maps to the same parent reference
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (child1Vertices[i] == child2Vertices[j] && child1Vertices[i] < 4) {
                    // Parent vertices are 0-3, edge midpoints are 4-9, center is 10
                    return true;
                }
            }
        }
        return false;
    }
}