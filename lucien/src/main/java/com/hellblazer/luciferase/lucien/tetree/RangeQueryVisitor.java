/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.visitor.AbstractTreeVisitor;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * A visitor for efficient range queries using tree-based traversal.
 * This provides an alternative to range-based iteration with support for:
 * - Early termination based on node bounds
 * - Level-aware pruning of subtrees
 * - Neighbor-based expansion for connected regions
 * 
 * @param <ID>      The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * @author hal.hildebrand
 */
public class RangeQueryVisitor<ID extends EntityID, Content> 
    extends AbstractTreeVisitor<TetreeKey<? extends TetreeKey>, ID, Content> {
    
    private VolumeBounds queryBounds;
    private final boolean includeIntersecting;
    private final List<SpatialNode<TetreeKey<? extends TetreeKey>, ID>> results;
    private final Set<TetreeKey<? extends TetreeKey>> visitedNodes;
    private final BiPredicate<TetreeKey<? extends TetreeKey>, VolumeBounds> boundsPredicate;
    
    // Statistics
    private int nodesVisited = 0;
    private int nodesPruned = 0;
    private int entitiesFound = 0;
    
    /**
     * Create a range query visitor.
     * 
     * @param queryBounds The spatial bounds to query
     * @param includeIntersecting If true, include nodes that intersect bounds; 
     *                           if false, only include nodes completely contained
     */
    public RangeQueryVisitor(VolumeBounds queryBounds, boolean includeIntersecting) {
        this.queryBounds = queryBounds;
        this.includeIntersecting = includeIntersecting;
        this.results = new ArrayList<>();
        this.visitedNodes = new HashSet<>();
        
        // Set up the appropriate bounds predicate
        if (includeIntersecting) {
            this.boundsPredicate = this::nodeIntersectsBounds;
        } else {
            this.boundsPredicate = this::nodeContainedInBounds;
        }
    }
    
    @Override
    public boolean visitNode(SpatialNode<TetreeKey<? extends TetreeKey>, ID> node, 
                           int level, TetreeKey<? extends TetreeKey> parentIndex) {
        nodesVisited++;
        var nodeKey = node.sfcIndex();
        
        // Avoid revisiting nodes
        if (visitedNodes.contains(nodeKey)) {
            nodesPruned++;
            return false;
        }
        visitedNodes.add(nodeKey);
        
        // Check if this node could possibly contain results
        if (!couldContainResults(nodeKey, level)) {
            nodesPruned++;
            return false; // Prune this subtree
        }
        
        // Check if this node matches our query
        if (boundsPredicate.test(nodeKey, queryBounds)) {
            results.add(node);
            entitiesFound += node.entityIds().size();
        }
        
        // Continue to children if we're not at max depth
        return level < getMaxDepth() || getMaxDepth() == -1;
    }
    
    @Override
    public void visitEntity(ID entityId, Content content, 
                          TetreeKey<? extends TetreeKey> nodeIndex, int level) {
        // Entities are handled at the node level in this visitor
        // Override if you need per-entity processing
    }
    
    /**
     * Get the nodes that matched the range query.
     * 
     * @return List of matching spatial nodes
     */
    public List<SpatialNode<TetreeKey<? extends TetreeKey>, ID>> getResults() {
        return new ArrayList<>(results);
    }
    
    /**
     * Get statistics about the traversal.
     * 
     * @return Statistics string
     */
    public String getStatistics() {
        return String.format(
            "RangeQuery Stats: Visited=%d, Pruned=%d (%.1f%%), Found=%d nodes with %d entities",
            nodesVisited, nodesPruned, 
            nodesVisited > 0 ? (100.0 * nodesPruned / nodesVisited) : 0,
            results.size(), entitiesFound
        );
    }
    
    /**
     * Reset the visitor for reuse with different bounds.
     * 
     * @param newBounds The new query bounds
     */
    public void reset(VolumeBounds newBounds) {
        this.queryBounds = newBounds;
        this.results.clear();
        this.visitedNodes.clear();
        this.nodesVisited = 0;
        this.nodesPruned = 0;
        this.entitiesFound = 0;
    }
    
    /**
     * Enable neighbor-based expansion for connected region queries.
     * This finds all connected nodes that match the query criteria.
     * 
     * @param tetree The Tetree to use for neighbor finding
     * @param startNode The starting node for expansion
     */
    public void expandFromSeed(Tetree<ID, Content> tetree, 
                              SpatialNode<TetreeKey<? extends TetreeKey>, ID> startNode) {
        var toProcess = new ArrayList<SpatialNode<TetreeKey<? extends TetreeKey>, ID>>();
        toProcess.add(startNode);
        
        while (!toProcess.isEmpty()) {
            var current = toProcess.remove(toProcess.size() - 1);
            var currentKey = current.sfcIndex();
            
            if (visitedNodes.contains(currentKey)) {
                continue;
            }
            
            // Visit this node
            if (boundsPredicate.test(currentKey, queryBounds)) {
                results.add(current);
                visitedNodes.add(currentKey);
                
                // Find and add neighbors
                // Note: This would require access to internal node structure
                // For now, this is a placeholder for the expansion logic
                // In practice, would need to traverse from current node to neighbors
            }
        }
    }
    
    /**
     * Check if a node could possibly contain results based on its level and bounds.
     * This enables early termination of subtree traversal.
     */
    private boolean couldContainResults(TetreeKey<? extends TetreeKey> nodeKey, int level) {
        // For coarse pruning, we can use the AABB of the tetrahedron's containing cube
        // This is conservative but safe - we won't miss any actual intersections
        var tet = Tet.tetrahedron(nodeKey);
        var nodeLength = tet.length();
        
        // Quick rejection test using the containing cube's bounds
        float nodeMinX = tet.x();
        float nodeMinY = tet.y();
        float nodeMinZ = tet.z();
        float nodeMaxX = nodeMinX + nodeLength;
        float nodeMaxY = nodeMinY + nodeLength;
        float nodeMaxZ = nodeMinZ + nodeLength;
        
        // If the containing cube doesn't intersect query bounds, the tetrahedron can't either
        return !(nodeMaxX < queryBounds.minX() || nodeMinX > queryBounds.maxX() ||
                nodeMaxY < queryBounds.minY() || nodeMinY > queryBounds.maxY() ||
                nodeMaxZ < queryBounds.minZ() || nodeMinZ > queryBounds.maxZ());
    }
    
    /**
     * Check if a node intersects the query bounds.
     */
    private boolean nodeIntersectsBounds(TetreeKey<? extends TetreeKey> nodeKey, VolumeBounds bounds) {
        var tet = Tet.tetrahedron(nodeKey);
        
        // Get the actual tetrahedron vertices
        Point3i[] intVertices = tet.coordinates();
        Point3f[] vertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f(intVertices[i].x, intVertices[i].y, intVertices[i].z);
        }
        
        // Create EntityBounds from VolumeBounds for the intersection test
        Point3f minPoint = new Point3f(bounds.minX(), bounds.minY(), bounds.minZ());
        Point3f maxPoint = new Point3f(bounds.maxX(), bounds.maxY(), bounds.maxZ());
        EntityBounds entityBounds = new EntityBounds(minPoint, maxPoint);
        
        // Use the proper tetrahedral geometry intersection test
        return TetrahedralGeometry.aabbIntersectsTetrahedron(entityBounds, vertices);
    }
    
    /**
     * Check if a node is completely contained within the query bounds.
     */
    private boolean nodeContainedInBounds(TetreeKey<? extends TetreeKey> nodeKey, VolumeBounds bounds) {
        var tet = Tet.tetrahedron(nodeKey);
        
        // Get the actual tetrahedron vertices
        Point3i[] intVertices = tet.coordinates();
        
        // Check if all 4 vertices are inside the bounds
        for (Point3i v : intVertices) {
            if (v.x < bounds.minX() || v.x > bounds.maxX() ||
                v.y < bounds.minY() || v.y > bounds.maxY() ||
                v.z < bounds.minZ() || v.z > bounds.maxZ()) {
                return false;
            }
        }
        
        return true;
    }
}