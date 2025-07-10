/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.collision;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.collision.CollisionShape;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Translate;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Specialized spatial index visualization for collision system debugging.
 * Shows spatial partitioning, entity distribution, and collision hotspots.
 *
 * @author hal.hildebrand
 */
public class SpatialIndexDebugVisualizer {
    
    // Visual components
    private final Group rootGroup = new Group();
    private final Group nodeGroup = new Group();
    private final Group entityGroup = new Group();
    private final Group boundsGroup = new Group();
    private final Group hotspotGroup = new Group();
    
    // Configuration properties
    private final BooleanProperty showNodes = new SimpleBooleanProperty(true);
    private final BooleanProperty showEntities = new SimpleBooleanProperty(true);
    private final BooleanProperty showBounds = new SimpleBooleanProperty(false);
    private final BooleanProperty showHotspots = new SimpleBooleanProperty(true);
    private final BooleanProperty showEmptyNodes = new SimpleBooleanProperty(false);
    
    private final IntegerProperty minLevel = new SimpleIntegerProperty(0);
    private final IntegerProperty maxLevel = new SimpleIntegerProperty(10);
    private final DoubleProperty nodeOpacity = new SimpleDoubleProperty(0.3);
    private final DoubleProperty entityScale = new SimpleDoubleProperty(1.0);
    
    // Color scheme
    private final ObjectProperty<Color> nodeColor = new SimpleObjectProperty<>(Color.LIGHTBLUE);
    private final ObjectProperty<Color> entityColor = new SimpleObjectProperty<>(Color.GREEN);
    private final ObjectProperty<Color> boundsColor = new SimpleObjectProperty<>(Color.ORANGE);
    private final ObjectProperty<Color> hotspotColor = new SimpleObjectProperty<>(Color.RED);
    
    // Data structures
    private final ObservableList<SpatialIndexEntry> indexEntries = FXCollections.observableArrayList();
    private final ObservableList<EntityEntry> entityEntries = FXCollections.observableArrayList();
    private final ObservableList<HotspotEntry> hotspotEntries = FXCollections.observableArrayList();
    
    // Visual mappings
    private final Map<Object, Node> nodeVisuals = new ConcurrentHashMap<>();
    private final Map<Object, Node> entityVisuals = new ConcurrentHashMap<>();
    
    // Performance tracking
    private final Map<Object, Integer> collisionCounts = new ConcurrentHashMap<>();
    private final Map<Object, Long> lastAccessTime = new ConcurrentHashMap<>();
    
    // Spatial index reference
    private AbstractSpatialIndex<?, ?, ?> spatialIndex;
    
    public SpatialIndexDebugVisualizer() {
        setupSceneGraph();
        setupPropertyBindings();
    }
    
    /**
     * Initialize the scene graph structure.
     */
    private void setupSceneGraph() {
        rootGroup.getChildren().addAll(
            boundsGroup,    // Background bounds
            nodeGroup,      // Spatial nodes
            entityGroup,    // Entities
            hotspotGroup    // Collision hotspots
        );
    }
    
    /**
     * Set up property bindings for dynamic visibility.
     */
    private void setupPropertyBindings() {
        nodeGroup.visibleProperty().bind(showNodes);
        entityGroup.visibleProperty().bind(showEntities);
        boundsGroup.visibleProperty().bind(showBounds);
        hotspotGroup.visibleProperty().bind(showHotspots);
        
        // Listen for property changes
        indexEntries.addListener((javafx.collections.ListChangeListener<SpatialIndexEntry>) change -> updateNodeVisualizations());
        entityEntries.addListener((javafx.collections.ListChangeListener<EntityEntry>) change -> updateEntityVisualizations());
        hotspotEntries.addListener((javafx.collections.ListChangeListener<HotspotEntry>) change -> updateHotspotVisualizations());
    }
    
    /**
     * Set the spatial index to visualize.
     */
    public void setSpatialIndex(AbstractSpatialIndex<?, ?, ?> spatialIndex) {
        this.spatialIndex = spatialIndex;
        refresh();
    }
    
    /**
     * Add a collision shape to be tracked.
     */
    public void addCollisionShape(CollisionShape shape, Object entityId) {
        var bounds = shape.getAABB();
        var center = new Point3f();
        center.add(bounds.getMin(), bounds.getMax());
        center.scale(0.5f);
        
        var size = new Vector3f();
        size.sub(bounds.getMax(), bounds.getMin());
        
        entityEntries.add(new EntityEntry(entityId, center, size, shape.getClass().getSimpleName()));
    }
    
    /**
     * Remove a collision shape from tracking.
     */
    public void removeCollisionShape(Object entityId) {
        entityEntries.removeIf(entry -> entry.entityId.equals(entityId));
        var visual = entityVisuals.remove(entityId);
        if (visual != null) {
            entityGroup.getChildren().remove(visual);
        }
    }
    
    /**
     * Record a collision at a specific location.
     */
    public void recordCollision(Point3f location) {
        // Create a simple hotspot based on location
        var hotspotId = "hotspot_" + location.x + "_" + location.y + "_" + location.z;
        var count = collisionCounts.getOrDefault(hotspotId, 0) + 1;
        collisionCounts.put(hotspotId, count);
        
        // Update or create hotspot entry
        hotspotEntries.removeIf(entry -> entry.id.equals(hotspotId));
        hotspotEntries.add(new HotspotEntry(hotspotId, location, count));
    }
    
    /**
     * Update spatial index node visualization.
     */
    public void updateSpatialNode(Object nodeId, EntityBounds bounds, int level, int entityCount) {
        var center = new Point3f();
        center.add(bounds.getMin(), bounds.getMax());
        center.scale(0.5f);
        
        var size = new Vector3f();
        size.sub(bounds.getMax(), bounds.getMin());
        
        // Remove old entry
        indexEntries.removeIf(entry -> entry.nodeId.equals(nodeId));
        
        // Add new entry
        if (entityCount > 0 || showEmptyNodes.get()) {
            indexEntries.add(new SpatialIndexEntry(nodeId, center, size, level, entityCount));
        }
    }
    
    /**
     * Refresh all visualizations.
     */
    public void refresh() {
        updateNodeVisualizations();
        updateEntityVisualizations();
        updateHotspotVisualizations();
    }
    
    /**
     * Update node visualizations based on current data.
     */
    private void updateNodeVisualizations() {
        nodeGroup.getChildren().clear();
        nodeVisuals.clear();
        
        for (var entry : indexEntries) {
            if (entry.level >= minLevel.get() && entry.level <= maxLevel.get()) {
                var visual = createNodeVisualization(entry);
                nodeVisuals.put(entry.nodeId, visual);
                nodeGroup.getChildren().add(visual);
            }
        }
    }
    
    /**
     * Update entity visualizations.
     */
    private void updateEntityVisualizations() {
        entityGroup.getChildren().clear();
        entityVisuals.clear();
        
        for (var entry : entityEntries) {
            var visual = createEntityVisualization(entry);
            entityVisuals.put(entry.entityId, visual);
            entityGroup.getChildren().add(visual);
        }
    }
    
    /**
     * Update hotspot visualizations.
     */
    private void updateHotspotVisualizations() {
        hotspotGroup.getChildren().clear();
        
        for (var entry : hotspotEntries) {
            var visual = createHotspotVisualization(entry);
            hotspotGroup.getChildren().add(visual);
        }
    }
    
    /**
     * Create visualization for a spatial index node.
     */
    private Node createNodeVisualization(SpatialIndexEntry entry) {
        var wireframe = new WireframeBox(entry.size.x, entry.size.y, entry.size.z);
        
        // Color based on entity count and level
        var intensity = Math.min(1.0, entry.entityCount / 10.0);
        var levelHue = (entry.level * 30) % 360;
        var color = Color.hsb(levelHue, 0.7, 0.5 + intensity * 0.5);
        
        var material = new PhongMaterial(color);
        material.setSpecularColor(Color.WHITE);
        wireframe.setMaterial(material);
        wireframe.setOpacity(nodeOpacity.get());
        
        wireframe.getTransforms().add(new Translate(entry.center.x, entry.center.y, entry.center.z));
        
        return wireframe;
    }
    
    /**
     * Create visualization for an entity.
     */
    private Node createEntityVisualization(EntityEntry entry) {
        var sphere = new Sphere(0.1 * entityScale.get());
        var material = new PhongMaterial(entityColor.get());
        sphere.setMaterial(material);
        
        sphere.getTransforms().add(new Translate(entry.center.x, entry.center.y, entry.center.z));
        
        return sphere;
    }
    
    /**
     * Create visualization for a collision hotspot.
     */
    private Node createHotspotVisualization(HotspotEntry entry) {
        var intensity = Math.min(1.0, entry.collisionCount / 20.0);
        var radius = 0.2 + intensity * 0.3;
        
        var sphere = new Sphere(radius);
        var color = Color.color(1.0, 1.0 - intensity, 1.0 - intensity, 0.7); // Red with transparency
        var material = new PhongMaterial(color);
        sphere.setMaterial(material);
        
        sphere.getTransforms().add(new Translate(entry.location.x, entry.location.y, entry.location.z));
        
        return sphere;
    }
    
    /**
     * Get spatial index statistics.
     */
    public SpatialIndexStats getStats() {
        var nodeCount = indexEntries.size();
        var entityCount = entityEntries.size();
        var hotspotCount = hotspotEntries.size();
        var totalCollisions = collisionCounts.values().stream().mapToInt(Integer::intValue).sum();
        
        // Calculate entity distribution by level
        var entitiesByLevel = new HashMap<Integer, Integer>();
        for (var entry : indexEntries) {
            entitiesByLevel.merge(entry.level, entry.entityCount, Integer::sum);
        }
        
        return new SpatialIndexStats(nodeCount, entityCount, hotspotCount, totalCollisions, entitiesByLevel);
    }
    
    /**
     * Clear all visualization data.
     */
    public void clear() {
        indexEntries.clear();
        entityEntries.clear();
        hotspotEntries.clear();
        collisionCounts.clear();
        lastAccessTime.clear();
        nodeVisuals.clear();
        entityVisuals.clear();
    }
    
    // Property getters
    public BooleanProperty showNodesProperty() { return showNodes; }
    public BooleanProperty showEntitiesProperty() { return showEntities; }
    public BooleanProperty showBoundsProperty() { return showBounds; }
    public BooleanProperty showHotspotsProperty() { return showHotspots; }
    public BooleanProperty showEmptyNodesProperty() { return showEmptyNodes; }
    
    public IntegerProperty minLevelProperty() { return minLevel; }
    public IntegerProperty maxLevelProperty() { return maxLevel; }
    public DoubleProperty nodeOpacityProperty() { return nodeOpacity; }
    public DoubleProperty entityScaleProperty() { return entityScale; }
    
    public ObjectProperty<Color> nodeColorProperty() { return nodeColor; }
    public ObjectProperty<Color> entityColorProperty() { return entityColor; }
    public ObjectProperty<Color> boundsColorProperty() { return boundsColor; }
    public ObjectProperty<Color> hotspotColorProperty() { return hotspotColor; }
    
    public Group getRootGroup() { return rootGroup; }
    public ObservableList<SpatialIndexEntry> getIndexEntries() { return indexEntries; }
    public ObservableList<EntityEntry> getEntityEntries() { return entityEntries; }
    public ObservableList<HotspotEntry> getHotspotEntries() { return hotspotEntries; }
    
    // Data classes
    public static class SpatialIndexEntry {
        public final Object nodeId;
        public final Point3f center;
        public final Vector3f size;
        public final int level;
        public final int entityCount;
        
        public SpatialIndexEntry(Object nodeId, Point3f center, Vector3f size, int level, int entityCount) {
            this.nodeId = nodeId;
            this.center = new Point3f(center);
            this.size = new Vector3f(size);
            this.level = level;
            this.entityCount = entityCount;
        }
    }
    
    public static class EntityEntry {
        public final Object entityId;
        public final Point3f center;
        public final Vector3f size;
        public final String type;
        
        public EntityEntry(Object entityId, Point3f center, Vector3f size, String type) {
            this.entityId = entityId;
            this.center = new Point3f(center);
            this.size = new Vector3f(size);
            this.type = type;
        }
    }
    
    public static class HotspotEntry {
        public final String id;
        public final Point3f location;
        public final int collisionCount;
        
        public HotspotEntry(String id, Point3f location, int collisionCount) {
            this.id = id;
            this.location = new Point3f(location);
            this.collisionCount = collisionCount;
        }
    }
    
    public static class SpatialIndexStats {
        public final int nodeCount;
        public final int entityCount;
        public final int hotspotCount;
        public final int totalCollisions;
        public final Map<Integer, Integer> entitiesByLevel;
        
        public SpatialIndexStats(int nodeCount, int entityCount, int hotspotCount, int totalCollisions, Map<Integer, Integer> entitiesByLevel) {
            this.nodeCount = nodeCount;
            this.entityCount = entityCount;
            this.hotspotCount = hotspotCount;
            this.totalCollisions = totalCollisions;
            this.entitiesByLevel = new HashMap<>(entitiesByLevel);
        }
    }
}