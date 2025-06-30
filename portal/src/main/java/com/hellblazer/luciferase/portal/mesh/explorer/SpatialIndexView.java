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

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.portal.mesh.Line;
import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Cube;
import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Tetrahedron;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.MeshView;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract base class for visualizing spatial index structures.
 * Provides common functionality for rendering Octree and Tetree data structures.
 * 
 * @param <Key> The spatial key type (MortonKey or TetreeKey)
 * @param <ID> The entity identifier type
 * @param <Content> The entity content type
 * 
 * @author hal.hildebrand
 */
public abstract class SpatialIndexView<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    // Visual components
    protected final Group sceneRoot = new Group();
    protected final Group nodeGroup = new Group();
    protected final Group entityGroup = new Group();
    protected final Group queryGroup = new Group();
    protected final Group overlayGroup = new Group();
    
    // Data structures
    protected final AbstractSpatialIndex<Key, ID, Content, ?> spatialIndex;
    protected final Map<Key, Node> nodeVisuals = new ConcurrentHashMap<>();
    protected final Map<ID, Node> entityVisuals = new ConcurrentHashMap<>();
    protected final Map<Integer, Material> levelMaterials = new HashMap<>();
    
    // Observable properties for configuration
    private final BooleanProperty showEmptyNodes = new SimpleBooleanProperty(false);
    private final BooleanProperty showEntityPositions = new SimpleBooleanProperty(true);
    private final BooleanProperty showNodeBounds = new SimpleBooleanProperty(true);
    private final BooleanProperty showLevelColors = new SimpleBooleanProperty(true);
    private final IntegerProperty minLevel = new SimpleIntegerProperty(0);
    private final IntegerProperty maxLevel = new SimpleIntegerProperty(20);
    private final DoubleProperty nodeOpacity = new SimpleDoubleProperty(0.3);
    private final DoubleProperty entitySize = new SimpleDoubleProperty(1.0);
    
    // Selection and highlighting
    protected final ObservableList<Key> selectedNodes = FXCollections.observableArrayList();
    protected final ObservableList<ID> selectedEntities = FXCollections.observableArrayList();
    
    // Performance tracking
    private long lastUpdateTime = 0;
    protected int visibleNodeCount = 0;
    protected int visibleEntityCount = 0;
    
    /**
     * Creates a new spatial index visualization.
     * 
     * @param spatialIndex The spatial index to visualize
     */
    protected SpatialIndexView(AbstractSpatialIndex<Key, ID, Content, ?> spatialIndex) {
        this.spatialIndex = spatialIndex;
        initializeScene();
        initializeMaterials();
        setupPropertyListeners();
    }
    
    /**
     * Initialize the scene graph structure.
     */
    private void initializeScene() {
        sceneRoot.getChildren().addAll(nodeGroup, entityGroup, queryGroup, overlayGroup);
        
        // Set render order - entities on top of nodes
        nodeGroup.setViewOrder(1.0);
        entityGroup.setViewOrder(0.0);
        queryGroup.setViewOrder(-1.0);
        overlayGroup.setViewOrder(-2.0);
    }
    
    /**
     * Initialize level-based materials with gradient colors.
     */
    private void initializeMaterials() {
        // Create gradient from blue (level 0) to red (level 20)
        for (int level = 0; level <= 20; level++) {
            double hue = 240 - (level * 12); // Blue to red
            Color color = Color.hsb(hue, 0.8, 0.8);
            PhongMaterial material = new PhongMaterial(color);
            material.setSpecularColor(color.brighter());
            levelMaterials.put(level, material);
        }
    }
    
    /**
     * Setup property change listeners for reactive updates.
     */
    private void setupPropertyListeners() {
        // Update visibility when properties change
        showEmptyNodes.addListener((obs, old, val) -> updateNodeVisibility());
        showEntityPositions.addListener((obs, old, val) -> updateEntityVisibility());
        showNodeBounds.addListener((obs, old, val) -> updateNodeVisibility());
        minLevel.addListener((obs, old, val) -> updateLevelVisibility());
        maxLevel.addListener((obs, old, val) -> updateLevelVisibility());
        nodeOpacity.addListener((obs, old, val) -> updateNodeOpacity());
        entitySize.addListener((obs, old, val) -> updateEntitySize());
    }
    
    /**
     * Updates the entire visualization based on current spatial index state.
     */
    public void updateVisualization() {
        long startTime = System.currentTimeMillis();
        
        // Clear existing visuals
        clearVisualization();
        
        // Render nodes and entities
        renderNodes();
        renderEntities();
        
        // Update performance metrics
        lastUpdateTime = System.currentTimeMillis() - startTime;
        updatePerformanceOverlay();
    }
    
    /**
     * Clear all visual elements.
     */
    protected void clearVisualization() {
        nodeGroup.getChildren().clear();
        entityGroup.getChildren().clear();
        nodeVisuals.clear();
        entityVisuals.clear();
        visibleNodeCount = 0;
        visibleEntityCount = 0;
    }
    
    /**
     * Render all nodes in the spatial index.
     */
    protected abstract void renderNodes();
    
    /**
     * Render all entities in the spatial index.
     */
    protected void renderEntities() {
        if (!showEntityPositions.get()) return;
        
        // Get all entities from nodes
        spatialIndex.nodes().forEach(node -> {
            node.entityIds().forEach(id -> {
                if (!entityVisuals.containsKey(id)) {
                    Node entityVisual = createEntityVisual(id);
                    if (entityVisual != null) {
                        entityGroup.getChildren().add(entityVisual);
                        entityVisuals.put(id, entityVisual);
                        visibleEntityCount++;
                    }
                }
            });
        });
    }
    
    /**
     * Create visual representation for an entity.
     */
    protected Node createEntityVisual(ID id) {
        Point3f pos = spatialIndex.getEntityPosition(id);
        if (pos == null) return null;
        
        Sphere sphere = new Sphere(entitySize.get());
        sphere.setTranslateX(pos.x);
        sphere.setTranslateY(pos.y);
        sphere.setTranslateZ(pos.z);
        
        // Color based on selection state
        PhongMaterial material = new PhongMaterial();
        if (selectedEntities.contains(id)) {
            material.setDiffuseColor(Color.YELLOW);
            material.setSpecularColor(Color.WHITE);
        } else {
            material.setDiffuseColor(Color.LIME);
            material.setSpecularColor(Color.LIGHTGREEN);
        }
        sphere.setMaterial(material);
        
        // Store entity ID for interaction
        sphere.setUserData(id);
        
        // Add click handler
        sphere.setOnMouseClicked(event -> {
            if (event.isControlDown()) {
                toggleEntitySelection(id);
            } else {
                selectEntity(id);
            }
            event.consume();
        });
        
        return sphere;
    }
    
    /**
     * Get material for a specific level.
     */
    protected Material getMaterialForLevel(int level) {
        if (!showLevelColors.get()) {
            PhongMaterial material = new PhongMaterial(Color.LIGHTGRAY);
            material.setSpecularColor(Color.WHITE);
            return material;
        }
        return levelMaterials.getOrDefault(level, levelMaterials.get(0));
    }
    
    /**
     * Check if a level should be visible.
     */
    protected boolean isLevelVisible(int level) {
        return level >= minLevel.get() && level <= maxLevel.get();
    }
    
    /**
     * Highlight a specific node.
     */
    public void highlightNode(Key nodeKey) {
        Node visual = nodeVisuals.get(nodeKey);
        if (visual != null) {
            PhongMaterial highlight = new PhongMaterial(Color.YELLOW);
            highlight.setSpecularColor(Color.WHITE);
            if (visual instanceof MeshView) {
                ((MeshView) visual).setMaterial(highlight);
            } else if (visual instanceof Box) {
                ((Box) visual).setMaterial(highlight);
            }
        }
    }
    
    /**
     * Show only a specific level.
     */
    public void showLevel(int level) {
        minLevel.set(level);
        maxLevel.set(level);
    }
    
    /**
     * Select an entity.
     */
    public void selectEntity(ID id) {
        selectedEntities.clear();
        selectedEntities.add(id);
        updateEntityVisibility();
    }
    
    /**
     * Toggle entity selection.
     */
    public void toggleEntitySelection(ID id) {
        if (selectedEntities.contains(id)) {
            selectedEntities.remove(id);
        } else {
            selectedEntities.add(id);
        }
        updateEntityVisibility();
    }
    
    /**
     * Update node visibility based on current settings.
     */
    protected void updateNodeVisibility() {
        nodeVisuals.forEach((key, node) -> {
            boolean visible = showNodeBounds.get() && isNodeVisible(key);
            node.setVisible(visible);
        });
    }
    
    /**
     * Check if a node should be visible based on current settings.
     */
    protected abstract boolean isNodeVisible(Key nodeKey);
    
    /**
     * Update entity visibility based on current settings.
     */
    protected void updateEntityVisibility() {
        entityVisuals.forEach((id, node) -> {
            node.setVisible(showEntityPositions.get());
            
            // Update selection highlighting
            if (node instanceof Sphere) {
                Sphere sphere = (Sphere) node;
                PhongMaterial material = (PhongMaterial) sphere.getMaterial();
                if (selectedEntities.contains(id)) {
                    material.setDiffuseColor(Color.YELLOW);
                } else {
                    material.setDiffuseColor(Color.LIME);
                }
            }
        });
    }
    
    /**
     * Update level visibility.
     */
    protected void updateLevelVisibility() {
        nodeVisuals.forEach((key, node) -> {
            int level = getLevelForKey(key);
            node.setVisible(showNodeBounds.get() && isLevelVisible(level));
        });
    }
    
    /**
     * Get the level for a spatial key.
     */
    protected abstract int getLevelForKey(Key key);
    
    /**
     * Update node opacity.
     */
    protected void updateNodeOpacity() {
        double opacity = nodeOpacity.get();
        nodeVisuals.values().forEach(node -> node.setOpacity(opacity));
    }
    
    /**
     * Update entity size.
     */
    protected void updateEntitySize() {
        double size = entitySize.get();
        entityVisuals.values().forEach(node -> {
            if (node instanceof Sphere) {
                ((Sphere) node).setRadius(size);
            }
        });
    }
    
    /**
     * Update performance overlay display.
     */
    protected void updatePerformanceOverlay() {
        // Subclasses can override to display performance metrics
    }
    
    /**
     * Visualize a spatial query.
     */
    public abstract void visualizeQuery(Object query);
    
    // Property getters
    public BooleanProperty showEmptyNodesProperty() { return showEmptyNodes; }
    public BooleanProperty showEntityPositionsProperty() { return showEntityPositions; }
    public BooleanProperty showNodeBoundsProperty() { return showNodeBounds; }
    public BooleanProperty showLevelColorsProperty() { return showLevelColors; }
    public IntegerProperty minLevelProperty() { return minLevel; }
    public IntegerProperty maxLevelProperty() { return maxLevel; }
    public DoubleProperty nodeOpacityProperty() { return nodeOpacity; }
    public DoubleProperty entitySizeProperty() { return entitySize; }
    
    public Group getSceneRoot() { return sceneRoot; }
    public int getVisibleNodeCount() { return visibleNodeCount; }
    public int getVisibleEntityCount() { return visibleEntityCount; }
    public long getLastUpdateTime() { return lastUpdateTime; }
    public ObservableList<Key> getSelectedNodes() { return selectedNodes; }
    public ObservableList<ID> getSelectedEntities() { return selectedEntities; }
}