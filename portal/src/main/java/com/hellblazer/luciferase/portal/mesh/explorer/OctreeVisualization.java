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

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octant;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.portal.mesh.Line;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.util.Duration;

import javax.vecmath.Point3f;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JavaFX 3D visualization for Octree spatial index structures. Renders octants as wireframe cubes with level-based
 * coloring and entity positions.
 *
 * @param <ID>      The entity identifier type
 * @param <Content> The entity content type
 * @author hal.hildebrand
 */
public class OctreeVisualization<ID extends EntityID, Content> extends SpatialIndexView<MortonKey, ID, Content> {

    private final Octree<ID, Content>   octree;
    private final Map<MortonKey, Group> octantGroups = new HashMap<>();

    // Animation settings
    private final boolean  animateSubdivisions = true;
    private final Duration animationDuration   = Duration.millis(500);

    /**
     * Creates a new Octree visualization.
     *
     * @param octree The octree to visualize
     */
    public OctreeVisualization(Octree<ID, Content> octree) {
        super(octree);
        this.octree = octree;
    }

    /**
     * Animate octant subdivision.
     */
    public void animateSubdivision(MortonKey parentKey) {
        if (parentKey == null || parentKey.getLevel() >= 20) {
            return; // Cannot subdivide further
        }
        
        // Get the parent node visual
        Node parentVisual = nodeVisuals.get(parentKey);
        if (!(parentVisual instanceof MeshView parentMesh)) {
            return;
        }
        
        // Get parent bounds by decoding Morton code
        var coords = MortonCurve.decode(parentKey.getMortonCode());
        var level = parentKey.getLevel();
        var cellSize = com.hellblazer.luciferase.lucien.Constants.lengthAtLevel(level);
        double parentSize = cellSize;
        double childSize = parentSize / 2.0;
        
        // Create timeline for animation
        Timeline timeline = new Timeline();
        
        // Get all 8 children
        MortonKey[] children = parentKey.getChildren();
        if (children == null) {
            return;
        }
        
        // Create child visuals
        for (int i = 0; i < 8; i++) {
            MortonKey childKey = children[i];
            
            // Calculate child position offset based on octant index
            // Octant index bits: [z][y][x]
            double xOffset = ((i & 1) != 0) ? childSize / 2 : -childSize / 2;
            double yOffset = ((i & 2) != 0) ? childSize / 2 : -childSize / 2;
            double zOffset = ((i & 4) != 0) ? childSize / 2 : -childSize / 2;
            
            // Create child cube
            Box childCube = new Box(childSize, childSize, childSize);
            childCube.setTranslateX(parentMesh.getTranslateX() + xOffset);
            childCube.setTranslateY(parentMesh.getTranslateY() + yOffset);
            childCube.setTranslateZ(parentMesh.getTranslateZ() + zOffset);
            
            // Set material based on octant
            PhongMaterial childMaterial = new PhongMaterial();
            Color octantColor = Color.hsb(i * 45.0, 0.7, 0.8); // Different color for each octant
            childMaterial.setDiffuseColor(octantColor);
            childMaterial.setSpecularColor(Color.WHITE);
            childCube.setMaterial(childMaterial);
            childCube.setDrawMode(DrawMode.LINE);
            
            // Initially invisible and scaled to 0
            childCube.setOpacity(0.0);
            childCube.setScaleX(0.01);
            childCube.setScaleY(0.01);
            childCube.setScaleZ(0.01);
            
            // Add to scene
            nodeGroup.getChildren().add(childCube);
            nodeVisuals.put(childKey, childCube);
            
            // Animate appearance
            double delay = i * 50; // Stagger the animations
            
            KeyFrame fadeIn = new KeyFrame(Duration.millis(delay),
                new KeyValue(childCube.opacityProperty(), 0.0),
                new KeyValue(childCube.scaleXProperty(), 0.01),
                new KeyValue(childCube.scaleYProperty(), 0.01),
                new KeyValue(childCube.scaleZProperty(), 0.01)
            );
            
            KeyFrame grow = new KeyFrame(Duration.millis(delay + 300),
                new KeyValue(childCube.opacityProperty(), 0.8),
                new KeyValue(childCube.scaleXProperty(), 1.0),
                new KeyValue(childCube.scaleYProperty(), 1.0),
                new KeyValue(childCube.scaleZProperty(), 1.0)
            );
            
            timeline.getKeyFrames().addAll(fadeIn, grow);
        }
        
        // Fade out parent
        KeyFrame parentFadeStart = new KeyFrame(Duration.millis(200),
            new KeyValue(parentMesh.opacityProperty(), parentMesh.getOpacity())
        );
        
        KeyFrame parentFadeEnd = new KeyFrame(Duration.millis(500),
            new KeyValue(parentMesh.opacityProperty(), 0.0)
        );
        
        timeline.getKeyFrames().addAll(parentFadeStart, parentFadeEnd);
        
        // Remove parent from scene after animation
        timeline.setOnFinished(e -> {
            nodeGroup.getChildren().remove(parentMesh);
            nodeVisuals.remove(parentKey);
        });
        
        timeline.play();
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

    @Override
    protected int getLevelForKey(MortonKey key) {
        return key.getLevel();
    }

    @Override
    protected boolean isNodeVisible(MortonKey nodeKey) {
        int level = nodeKey.getLevel();
        if (!isLevelVisible(level)) {
            return false;
        }

        // Check if node exists by searching current nodes
        return octree.nodes().anyMatch(
        node -> node.sfcIndex().equals(nodeKey) && (showEmptyNodesProperty().get() || !node.entityIds().isEmpty()));
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
        Point3f[] vertices = { new Point3f(x, y, z), new Point3f(x + size, y, z), new Point3f(x + size, y + size, z),
                               new Point3f(x, y + size, z), new Point3f(x, y, z + size), new Point3f(x + size, y,
                                                                                                     z + size),
                               new Point3f(x + size, y + size, z + size), new Point3f(x, y + size, z + size) };

        // Define edges
        int[][] edgeIndices = { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 },  // Bottom face
                                { 4, 5 }, { 5, 6 }, { 6, 7 }, { 7, 4 },  // Top face
                                { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 }   // Vertical edges
        };

        // Create edge lines
        PhongMaterial edgeMaterial = new PhongMaterial(Color.BLACK);
        for (int[] edge : edgeIndices) {
            Point3f p1 = vertices[edge[0]];
            Point3f p2 = vertices[edge[1]];
            Line line = new Line(0.1 + (20 - level) * 0.05, new Point3D(p1.x, p1.y, p1.z),
                                 new Point3D(p2.x, p2.y, p2.z));
            line.setMaterial(edgeMaterial);
            line.setRadius(0.1 + (20 - level) * 0.05); // Thicker lines for higher levels
            edges.getChildren().add(line);
        }

        return edges;
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

    /**
     * Select a node.
     */
    private void selectNode(MortonKey key) {
        getSelectedNodes().clear();
        getSelectedNodes().add(key);
        updateNodeVisibility();
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
        return showEmptyNodesProperty().get() || !node.entityIds().isEmpty();
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
                Line line = new Line(0.5, new Point3D(query.point.x, query.point.y, query.point.z),
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
     * Visualize a ray query.
     */
    private void visualizeRayQuery(RayQuery query) {
        // Draw ray
        Point3f end = new Point3f(query.origin.x + query.direction.x * 1000, query.origin.y + query.direction.y * 1000,
                                  query.origin.z + query.direction.z * 1000);

        Line ray = new Line(1.0, new Point3D(query.origin.x, query.origin.y, query.origin.z),
                            new Point3D(end.x, end.y, end.z));
        ray.setMaterial(new PhongMaterial(Color.MAGENTA));
        ray.setRadius(1.0);
        queryGroup.getChildren().add(ray);

        // Ray intersection visualization
        // Note: Using simplified approach since exact traversal method not available
        queryGroup.getChildren().add(ray);
    }

    // Query types for visualization
    public static class RangeQuery {
        public final Point3f center;
        public final float   radius;

        public RangeQuery(Point3f center, float radius) {
            this.center = center;
            this.radius = radius;
        }
    }

    public static class KNNQuery {
        public final Point3f point;
        public final int     k;

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
