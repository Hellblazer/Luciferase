/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.portal.mesh.explorer;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.Octant;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.portal.mesh.Line;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Sphere;
import javafx.util.Duration;

import javax.vecmath.Point3f;
import java.util.*;

/**
 * JavaFX 3D visualization for Octree spatial index structures.
 * Renders octants as wireframe cubes with level-based coloring and entity positions.
 * 
 * @param <ID> The entity identifier type
 * @param <Content> The entity content type
 * 
 * @author hal.hildebrand
 */
public class OctreeVisualization<ID extends EntityID, Content> extends SpatialIndexView<MortonKey, ID, Content> {
    
    private final Octree<ID, Content> octree;
    private final Map<MortonKey, Group> octantGroups = new HashMap<>();
    
    // Animation settings
    private boolean animateSubdivisions = true;
    private Duration animationDuration = Duration.millis(500);
    
    /**
     * Creates a new Octree visualization.
     * 
     * @param octree The octree to visualize
     */
    public OctreeVisualization(Octree<ID, Content> octree) {
        super(octree);
        this.octree = octree;
    }
    
    @Override
    protected void renderNodes() {
        // Get all nodes from the octree
        octree.nodes().forEach(node -> {
            if (shouldRenderNode(node)) {
                Node octantVisual = createOctantVisual(node);
                if (octantVisual != null) {
                    nodeGroup.getChildren().add(octantVisual);
                    nodeVisuals.put(node.sfcIndex(), octantVisual);
                    // Count tracked in parent class
                }
            }
        });
    }
    
    /**
     * Check if a node should be rendered based on current settings.
     */
    private boolean shouldRenderNode(SpatialNode<MortonKey, ID> node) {
        int level = node.sfcIndex().getLevel();
        
        // Check level visibility
        if (!isLevelVisible(level)) {
            return false;
        }
        
        // Check empty node visibility
        if (!showEmptyNodesProperty().get() && node.entityIds().isEmpty()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Create visual representation for an octant.
     */
    private Node createOctantVisual(SpatialNode<MortonKey, ID> node) {
        MortonKey key = node.sfcIndex();
        Octant octant = mortonKeyToOctant(key);
        
        Group octantGroup = new Group();
        octantGroups.put(key, octantGroup);
        
        // Create wireframe cube
        if (showNodeBoundsProperty().get()) {
            Group wireframe = createWireframeCube(octant, key.getLevel());
            octantGroup.getChildren().add(wireframe);
        }
        
        // Add transparent face if node has entities
        if (!node.entityIds().isEmpty()) {
            Box face = createTransparentCube(octant, key.getLevel());
            octantGroup.getChildren().add(face);
        }
        
        // Store key for interaction
        octantGroup.setUserData(key);
        
        // Add interaction handlers
        octantGroup.setOnMouseClicked(event -> {
            if (event.isControlDown()) {
                toggleNodeSelection(key);
            } else {
                selectNode(key);
            }
            event.consume();
        });
        
        octantGroup.setOnMouseEntered(event -> {
            highlightNode(key);
        });
        
        octantGroup.setOnMouseExited(event -> {
            unhighlightNode(key);
        });
        
        return octantGroup;
    }
    
    /**
     * Create wireframe cube for octant bounds.
     */
    private Group createWireframeCube(Octant octant, int level) {
        Group edges = new Group();
        
        // Calculate cell size at this level
        float cellSize = 1000.0f / (1 << level); // Assuming world size of 1000
        float x = octant.x() * cellSize;
        float y = octant.y() * cellSize;
        float z = octant.z() * cellSize;
        float size = cellSize;
        
        // Define cube vertices
        Point3f[] vertices = {
            new Point3f(x, y, z),
            new Point3f(x + size, y, z),
            new Point3f(x + size, y + size, z),
            new Point3f(x, y + size, z),
            new Point3f(x, y, z + size),
            new Point3f(x + size, y, z + size),
            new Point3f(x + size, y + size, z + size),
            new Point3f(x, y + size, z + size)
        };
        
        // Define edges
        int[][] edgeIndices = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},  // Bottom face
            {4, 5}, {5, 6}, {6, 7}, {7, 4},  // Top face
            {0, 4}, {1, 5}, {2, 6}, {3, 7}   // Vertical edges
        };
        
        // Create edge lines
        PhongMaterial edgeMaterial = new PhongMaterial(Color.BLACK);
        for (int[] edge : edgeIndices) {
            Point3f p1 = vertices[edge[0]];
            Point3f p2 = vertices[edge[1]];
            Line line = new Line(0.1 + (20 - level) * 0.05,
                new Point3D(p1.x, p1.y, p1.z),
                new Point3D(p2.x, p2.y, p2.z));
            line.setMaterial(edgeMaterial);
            line.setRadius(0.1 + (20 - level) * 0.05); // Thicker lines for higher levels
            edges.getChildren().add(line);
        }
        
        return edges;
    }
    
    /**
     * Create transparent cube face for octant.
     */
    private Box createTransparentCube(Octant octant, int level) {
        float cellSize = 1000.0f / (1 << level);
        Box box = new Box(cellSize, cellSize, cellSize);
        
        // Position at octant center
        float x = octant.x() * cellSize;
        float y = octant.y() * cellSize;
        float z = octant.z() * cellSize;
        box.setTranslateX(x + cellSize / 2);
        box.setTranslateY(y + cellSize / 2);
        box.setTranslateZ(z + cellSize / 2);
        
        // Apply level-based material
        Material material = getMaterialForLevel(level);
        box.setMaterial(material);
        box.setOpacity(nodeOpacityProperty().get());
        
        return box;
    }
    
    /**
     * Animate octant subdivision.
     * TODO: Implement when MortonKey.getChild() method is available
     */
    public void animateSubdivision(MortonKey parentKey) {
        // Animation disabled - requires getChild method on MortonKey
        // which is not currently available in the API
    }
    
    /**
     * Convert MortonKey to Octant for visualization.
     */
    private Octant mortonKeyToOctant(MortonKey key) {
        long mortonCode = key.getMortonCode();
        byte level = key.getLevel();
        
        // Decode Morton code to get coordinates
        int[] coords = MortonCurve.decode(mortonCode);
        
        // Create octant with grid coordinates
        return new Octant(coords[0], coords[1], coords[2], level);
    }
    
    @Override
    protected boolean isNodeVisible(MortonKey nodeKey) {
        int level = nodeKey.getLevel();
        if (!isLevelVisible(level)) {
            return false;
        }
        
        // Check if node exists by searching current nodes
        return octree.nodes()
            .anyMatch(node -> node.sfcIndex().equals(nodeKey) && 
                     (showEmptyNodesProperty().get() || !node.entityIds().isEmpty()));
    }
    
    @Override
    protected int getLevelForKey(MortonKey key) {
        return key.getLevel();
    }
    
    @Override
    public void visualizeQuery(Object query) {
        queryGroup.getChildren().clear();
        
        if (query instanceof RangeQuery) {
            visualizeRangeQuery((RangeQuery) query);
        } else if (query instanceof KNNQuery) {
            visualizeKNNQuery((KNNQuery) query);
        } else if (query instanceof RayQuery) {
            visualizeRayQuery((RayQuery) query);
        }
    }
    
    /**
     * Visualize a range query.
     */
    private void visualizeRangeQuery(RangeQuery query) {
        // Create semi-transparent sphere for range
        Sphere rangeSphere = new Sphere(query.radius);
        rangeSphere.setTranslateX(query.center.x);
        rangeSphere.setTranslateY(query.center.y);
        rangeSphere.setTranslateZ(query.center.z);
        
        PhongMaterial material = new PhongMaterial(Color.CYAN);
        material.setSpecularColor(Color.WHITE);
        rangeSphere.setMaterial(material);
        rangeSphere.setOpacity(0.3);
        
        queryGroup.getChildren().add(rangeSphere);
        
        // Highlight nodes within range - use bounding volume query
        // Note: This is a simplified approach since exact method not available
        queryGroup.getChildren().add(rangeSphere);
    }
    
    /**
     * Visualize a k-NN query.
     */
    private void visualizeKNNQuery(KNNQuery query) {
        // Show query point
        Sphere queryPoint = new Sphere(2.0);
        queryPoint.setTranslateX(query.point.x);
        queryPoint.setTranslateY(query.point.y);
        queryPoint.setTranslateZ(query.point.z);
        
        PhongMaterial material = new PhongMaterial(Color.RED);
        queryPoint.setMaterial(material);
        queryGroup.getChildren().add(queryPoint);
        
        // Find and visualize nearest neighbors
        List<ID> neighbors = octree.kNearestNeighbors(query.point, query.k, Float.MAX_VALUE);
        
        // Draw lines to neighbors
        neighbors.forEach(id -> {
            Point3f entityPos = octree.getEntityPosition(id);
            if (entityPos != null) {
                Line line = new Line(0.5, 
                    new Point3D(query.point.x, query.point.y, query.point.z),
                    new Point3D(entityPos.x, entityPos.y, entityPos.z));
                line.setMaterial(new PhongMaterial(Color.ORANGE));
                line.setRadius(0.5);
                queryGroup.getChildren().add(line);
                
                // Highlight neighbor entity
                Node entityVisual = entityVisuals.get(id);
                if (entityVisual != null && entityVisual instanceof Sphere) {
                    ((Sphere) entityVisual).setMaterial(new PhongMaterial(Color.ORANGE));
                }
            }
        });
    }
    
    /**
     * Visualize a ray query.
     */
    private void visualizeRayQuery(RayQuery query) {
        // Draw ray
        Point3f end = new Point3f(
            query.origin.x + query.direction.x * 1000,
            query.origin.y + query.direction.y * 1000,
            query.origin.z + query.direction.z * 1000
        );
        
        Line ray = new Line(1.0,
            new Point3D(query.origin.x, query.origin.y, query.origin.z),
            new Point3D(end.x, end.y, end.z));
        ray.setMaterial(new PhongMaterial(Color.MAGENTA));
        ray.setRadius(1.0);
        queryGroup.getChildren().add(ray);
        
        // Ray intersection visualization
        // Note: Using simplified approach since exact traversal method not available
        queryGroup.getChildren().add(ray);
    }
    
    /**
     * Select a node.
     */
    private void selectNode(MortonKey key) {
        getSelectedNodes().clear();
        getSelectedNodes().add(key);
        updateNodeVisibility();
    }
    
    /**
     * Toggle node selection.
     */
    private void toggleNodeSelection(MortonKey key) {
        if (getSelectedNodes().contains(key)) {
            getSelectedNodes().remove(key);
        } else {
            getSelectedNodes().add(key);
        }
        updateNodeVisibility();
    }
    
    /**
     * Unhighlight a node.
     */
    private void unhighlightNode(MortonKey key) {
        Node visual = nodeVisuals.get(key);
        if (visual != null && !getSelectedNodes().contains(key)) {
            // Restore original material
            int level = key.getLevel();
            Material material = getMaterialForLevel(level);
            
            if (visual instanceof Group) {
                ((Group) visual).getChildren().forEach(child -> {
                    if (child instanceof Box) {
                        ((Box) child).setMaterial(material);
                    }
                });
            }
        }
    }
    
    // Query types for visualization
    public static class RangeQuery {
        public final Point3f center;
        public final float radius;
        
        public RangeQuery(Point3f center, float radius) {
            this.center = center;
            this.radius = radius;
        }
    }
    
    public static class KNNQuery {
        public final Point3f point;
        public final int k;
        
        public KNNQuery(Point3f point, int k) {
            this.point = point;
            this.k = k;
        }
    }
    
    public static class RayQuery {
        public final Point3f origin;
        public final Point3f direction;
        
        public RayQuery(Point3f origin, Point3f direction) {
            this.origin = origin;
            this.direction = direction;
        }
    }
}