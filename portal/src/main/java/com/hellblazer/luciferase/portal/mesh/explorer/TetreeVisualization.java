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

import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.tetree.BaseTetreeKey;
import com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.portal.mesh.Line;
import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Tetrahedron;

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.*;

/**
 * JavaFX 3D visualization for Tetree spatial index structures.
 * Renders tetrahedra with level-based coloring and shows the tetrahedral decomposition.
 * 
 * @param <ID> The entity identifier type
 * @param <Content> The entity content type
 * 
 * @author hal.hildebrand
 */
public class TetreeVisualization<ID extends EntityID, Content> extends SpatialIndexView<BaseTetreeKey<? extends BaseTetreeKey>, ID, Content> {
    
    private final Tetree<ID, Content> tetree;
    private final Map<BaseTetreeKey<? extends BaseTetreeKey>, Group> tetGroups = new HashMap<>();
    
    // Tetrahedral type colors
    private final Map<Integer, Color> typeColors = new HashMap<>();
    
    /**
     * Creates a new Tetree visualization.
     * 
     * @param tetree The tetree to visualize
     */
    public TetreeVisualization(Tetree<ID, Content> tetree) {
        super(tetree);
        this.tetree = tetree;
        initializeTypeColors();
    }
    
    /**
     * Initialize colors for the 6 characteristic tetrahedron types.
     */
    private void initializeTypeColors() {
        typeColors.put(0, Color.RED);
        typeColors.put(1, Color.GREEN);
        typeColors.put(2, Color.BLUE);
        typeColors.put(3, Color.YELLOW);
        typeColors.put(4, Color.MAGENTA);
        typeColors.put(5, Color.CYAN);
    }
    
    @Override
    protected void renderNodes() {
        // Get all nodes from the tetree
        tetree.nodes().forEach(node -> {
            SpatialNode<BaseTetreeKey<? extends BaseTetreeKey>, ID> typedNode = 
                new SpatialNode<>(node.sfcIndex(), node.entityIds());
            if (shouldRenderNode(typedNode)) {
                Node tetVisual = createTetVisual(typedNode);
                if (tetVisual != null) {
                    nodeGroup.getChildren().add(tetVisual);
                    nodeVisuals.put(typedNode.sfcIndex(), tetVisual);
                }
            }
        });
    }
    
    /**
     * Check if a node should be rendered based on current settings.
     */
    private boolean shouldRenderNode(SpatialNode<BaseTetreeKey<? extends BaseTetreeKey>, ID> node) {
        int level = getLevelForKey(node.sfcIndex());
        
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
     * Create visual representation for a tetrahedron.
     */
    private Node createTetVisual(SpatialNode<BaseTetreeKey<? extends BaseTetreeKey>, ID> node) {
        BaseTetreeKey<? extends BaseTetreeKey> key = node.sfcIndex();
        Tet tet = tetreeKeyToTet(key);
        
        Group tetGroup = new Group();
        tetGroups.put(key, tetGroup);
        
        // Create wireframe tetrahedron
        if (showNodeBoundsProperty().get()) {
            Group wireframe = createWireframeTetrahedron(tet, getLevelForKey(key));
            tetGroup.getChildren().add(wireframe);
        }
        
        // Add semi-transparent face if node has entities
        if (!node.entityIds().isEmpty()) {
            MeshView face = createTransparentTetrahedron(tet, getLevelForKey(key));
            tetGroup.getChildren().add(face);
        }
        
        // Store key for interaction
        tetGroup.setUserData(key);
        
        // Add interaction handlers
        tetGroup.setOnMouseClicked(event -> {
            if (event.isControlDown()) {
                toggleNodeSelection(key);
            } else {
                selectNode(key);
            }
            event.consume();
        });
        
        tetGroup.setOnMouseEntered(event -> {
            highlightNode(key);
        });
        
        tetGroup.setOnMouseExited(event -> {
            unhighlightNode(key);
        });
        
        return tetGroup;
    }
    
    /**
     * Create wireframe tetrahedron for tet bounds.
     */
    private Group createWireframeTetrahedron(Tet tet, int level) {
        Group edges = new Group();
        
        // Get tetrahedron vertices in standard order
        Point3i[] intVertices = tet.standardOrderCoordinates();
        Point3f[] vertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f(intVertices[i].x, intVertices[i].y, intVertices[i].z);
        }
        
        // Define tetrahedron edges (6 edges)
        int[][] edgeIndices = {
            {0, 1}, {0, 2}, {0, 3},  // Edges from vertex 0
            {1, 2}, {1, 3},          // Edges from vertex 1
            {2, 3}                   // Edge from vertex 2
        };
        
        // Create edge lines
        PhongMaterial edgeMaterial = new PhongMaterial(Color.BLACK);
        for (int[] edge : edgeIndices) {
            Point3f p1 = vertices[edge[0]];
            Point3f p2 = vertices[edge[1]];
            Line line = new Line(0.1 + (20 - level) * 0.02,
                new Point3D(p1.x, p1.y, p1.z),
                new Point3D(p2.x, p2.y, p2.z));
            line.setMaterial(edgeMaterial);
            edges.getChildren().add(line);
        }
        
        return edges;
    }
    
    /**
     * Create transparent tetrahedron face.
     */
    private MeshView createTransparentTetrahedron(Tet tet, int level) {
        // Get vertices in standard order for proper face normals
        Point3i[] intVertices = tet.standardOrderCoordinates();
        Point3f[] vertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f(intVertices[i].x, intVertices[i].y, intVertices[i].z);
        }
        
        // Create triangle mesh for tetrahedron
        TriangleMesh mesh = new TriangleMesh();
        
        // Add vertices
        for (Point3f v : vertices) {
            mesh.getPoints().addAll(v.x, v.y, v.z);
        }
        
        // Add texture coordinates (simple mapping)
        mesh.getTexCoords().addAll(
            0, 0,  // Vertex 0
            1, 0,  // Vertex 1
            0.5f, 1,  // Vertex 2
            0.5f, 0.5f  // Vertex 3
        );
        
        // Define faces with correct winding order for outward-facing normals
        // Using right-hand rule: when fingers curl in vertex order, thumb points outward
        mesh.getFaces().addAll(
            0, 0, 2, 2, 1, 1,  // Face 0-2-1 (base, viewed from below)
            0, 0, 1, 1, 3, 3,  // Face 0-1-3 (front right)
            0, 0, 3, 3, 2, 2,  // Face 0-3-2 (back left)
            1, 1, 2, 2, 3, 3   // Face 1-2-3 (top, viewed from above)
        );
        
        MeshView meshView = new MeshView(mesh);
        
        // Apply material based on type and level
        Material material = getMaterialForTet(tet, level);
        meshView.setMaterial(material);
        meshView.setOpacity(nodeOpacityProperty().get());
        
        return meshView;
    }
    
    /**
     * Get material for a tetrahedron based on its type and level.
     */
    private Material getMaterialForTet(Tet tet, int level) {
        if (!showLevelColorsProperty().get()) {
            PhongMaterial material = new PhongMaterial(Color.LIGHTGRAY);
            material.setSpecularColor(Color.WHITE);
            return material;
        }
        
        // Use type-based coloring
        int type = tet.type();
        Color baseColor = typeColors.getOrDefault(type, Color.GRAY);
        
        // Modify color based on level (darker at deeper levels)
        double brightness = 1.0 - (level * 0.04); // Darken by 4% per level
        Color levelColor = baseColor.deriveColor(0, 1, brightness, 1);
        
        PhongMaterial material = new PhongMaterial(levelColor);
        material.setSpecularColor(levelColor.brighter());
        return material;
    }
    
    /**
     * Convert TetreeKey to Tet for visualization.
     */
    private Tet tetreeKeyToTet(BaseTetreeKey<? extends BaseTetreeKey> key) {
        // Use the static method from Tet to properly decode the TetreeKey
        return Tet.tetrahedron(key);
    }
    
    @Override
    protected boolean isNodeVisible(BaseTetreeKey<? extends BaseTetreeKey> nodeKey) {
        int level = getLevelForKey(nodeKey);
        if (!isLevelVisible(level)) {
            return false;
        }
        
        // Check if node exists by searching current nodes
        return tetree.nodes()
            .anyMatch(node -> node.sfcIndex().equals(nodeKey) && 
                     (showEmptyNodesProperty().get() || !node.entityIds().isEmpty()));
    }
    
    @Override
    protected int getLevelForKey(BaseTetreeKey<? extends BaseTetreeKey> key) {
        // Extract level from TetreeKey
        // The level is encoded in the key structure
        return key.getLevel();
    }
    
    @Override
    public void visualizeQuery(Object query) {
        queryGroup.getChildren().clear();
        
        if (query instanceof TetreeRangeQuery) {
            visualizeRangeQuery((TetreeRangeQuery) query);
        } else if (query instanceof TetreeKNNQuery) {
            visualizeKNNQuery((TetreeKNNQuery) query);
        } else if (query instanceof TetreeRayQuery) {
            visualizeRayQuery((TetreeRayQuery) query);
        }
    }
    
    /**
     * Visualize a range query in tetrahedral space.
     */
    private void visualizeRangeQuery(TetreeRangeQuery query) {
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
    }
    
    /**
     * Visualize a k-NN query.
     */
    private void visualizeKNNQuery(TetreeKNNQuery query) {
        // Show query point
        Sphere queryPoint = new Sphere(2.0);
        queryPoint.setTranslateX(query.point.x);
        queryPoint.setTranslateY(query.point.y);
        queryPoint.setTranslateZ(query.point.z);
        
        PhongMaterial material = new PhongMaterial(Color.RED);
        queryPoint.setMaterial(material);
        queryGroup.getChildren().add(queryPoint);
        
        // Find and visualize nearest neighbors
        List<ID> neighbors = tetree.kNearestNeighbors(query.point, query.k, Float.MAX_VALUE);
        
        // Draw lines to neighbors
        neighbors.forEach(id -> {
            Point3f entityPos = tetree.getEntityPosition(id);
            if (entityPos != null) {
                Line line = new Line(0.5,
                    new Point3D(query.point.x, query.point.y, query.point.z),
                    new Point3D(entityPos.x, entityPos.y, entityPos.z));
                line.setMaterial(new PhongMaterial(Color.ORANGE));
                queryGroup.getChildren().add(line);
                
                // Highlight neighbor entity
                Node entityVisual = entityVisuals.get(id);
                if (entityVisual instanceof Sphere) {
                    ((Sphere) entityVisual).setMaterial(new PhongMaterial(Color.ORANGE));
                }
            }
        });
    }
    
    /**
     * Visualize a ray query.
     */
    private void visualizeRayQuery(TetreeRayQuery query) {
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
        queryGroup.getChildren().add(ray);
    }
    
    /**
     * Show the characteristic tetrahedron decomposition.
     */
    public void showCharacteristicDecomposition() {
        // Clear existing visualization
        nodeGroup.getChildren().clear();
        
        // Create the 6 characteristic tetrahedra of a cube
        // These are the S0-S5 tetrahedra that decompose a unit cube
        
        // TODO: Implement visualization of the 6 characteristic types
        // This would show how a cube is decomposed into tetrahedra
    }
    
    /**
     * Select a node.
     */
    private void selectNode(BaseTetreeKey<? extends BaseTetreeKey> key) {
        getSelectedNodes().clear();
        getSelectedNodes().add(key);
        updateNodeVisibility();
    }
    
    /**
     * Toggle node selection.
     */
    private void toggleNodeSelection(BaseTetreeKey<? extends BaseTetreeKey> key) {
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
    private void unhighlightNode(BaseTetreeKey<? extends BaseTetreeKey> key) {
        Node visual = nodeVisuals.get(key);
        if (visual != null && !getSelectedNodes().contains(key)) {
            // Restore original material
            if (visual instanceof Group) {
                Group group = (Group) visual;
                group.getChildren().forEach(child -> {
                    if (child instanceof MeshView) {
                        Tet tet = tetreeKeyToTet(key);
                        int level = getLevelForKey(key);
                        ((MeshView) child).setMaterial(getMaterialForTet(tet, level));
                    }
                });
            }
        }
    }
    
    // Query types for Tetree visualization
    public static class TetreeRangeQuery {
        public final Point3f center;
        public final float radius;
        
        public TetreeRangeQuery(Point3f center, float radius) {
            this.center = center;
            this.radius = radius;
        }
    }
    
    public static class TetreeKNNQuery {
        public final Point3f point;
        public final int k;
        
        public TetreeKNNQuery(Point3f point, int k) {
            this.point = point;
            this.k = k;
        }
    }
    
    public static class TetreeRayQuery {
        public final Point3f origin;
        public final Point3f direction;
        
        public TetreeRayQuery(Point3f origin, Point3f direction) {
            this.origin = origin;
            this.direction = direction;
        }
    }
}