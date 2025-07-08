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

import com.hellblazer.luciferase.lucien.collision.CollisionShape;
import com.hellblazer.luciferase.lucien.collision.physics.RigidBody;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Sphere;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Complete visualization system for collision detection and physics debugging.
 * Provides real-time visualization of collision shapes, contact points, forces, and physics state.
 *
 * @author hal.hildebrand
 */
public class CollisionVisualizer {
    
    // Visual properties
    private final BooleanProperty showWireframes = new SimpleBooleanProperty(true);
    private final BooleanProperty showContactPoints = new SimpleBooleanProperty(true);
    private final BooleanProperty showPenetrationVectors = new SimpleBooleanProperty(true);
    private final BooleanProperty showVelocityVectors = new SimpleBooleanProperty(false);
    private final BooleanProperty showForceVectors = new SimpleBooleanProperty(false);
    private final BooleanProperty showAABBs = new SimpleBooleanProperty(false);
    
    // Color scheme properties
    private final ObjectProperty<Color> wireframeColor = new SimpleObjectProperty<>(Color.CYAN);
    private final ObjectProperty<Color> collisionColor = new SimpleObjectProperty<>(Color.RED);
    private final ObjectProperty<Color> contactPointColor = new SimpleObjectProperty<>(Color.YELLOW);
    private final ObjectProperty<Color> penetrationColor = new SimpleObjectProperty<>(Color.ORANGE);
    private final ObjectProperty<Color> velocityColor = new SimpleObjectProperty<>(Color.GREEN);
    private final ObjectProperty<Color> forceColor = new SimpleObjectProperty<>(Color.BLUE);
    private final ObjectProperty<Color> aabbColor = new SimpleObjectProperty<>(Color.GRAY);
    
    // Scale properties
    private final DoubleProperty vectorScale = new SimpleDoubleProperty(1.0);
    private final DoubleProperty contactPointScale = new SimpleDoubleProperty(1.0);
    
    // Scene graph
    private final Group rootGroup = new Group();
    private final Group shapeWireframes = new Group();
    private final Group contactPoints = new Group();
    private final Group penetrationVectors = new Group();
    private final Group velocityVectors = new Group();
    private final Group forceVectors = new Group();
    private final Group aabbBoxes = new Group();
    
    // Data storage
    private final ObservableList<CollisionShape> shapes = FXCollections.observableArrayList();
    private final ObservableList<RigidBody> bodies = FXCollections.observableArrayList();
    private final ObservableList<ContactInfo> contacts = FXCollections.observableArrayList();
    
    // Internal state
    private final ConcurrentHashMap<CollisionShape, Node> shapeNodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RigidBody, List<Node>> bodyNodes = new ConcurrentHashMap<>();
    
    public CollisionVisualizer() {
        setupSceneGraph();
        setupPropertyBindings();
    }
    
    /**
     * Initialize the scene graph structure.
     */
    private void setupSceneGraph() {
        rootGroup.getChildren().addAll(
            aabbBoxes,      // Background AABBs
            shapeWireframes, // Shape wireframes
            contactPoints,   // Contact points
            penetrationVectors, // Penetration vectors
            velocityVectors,    // Velocity vectors
            forceVectors       // Force vectors
        );
    }
    
    /**
     * Set up property bindings for dynamic visibility.
     */
    private void setupPropertyBindings() {
        shapeWireframes.visibleProperty().bind(showWireframes);
        contactPoints.visibleProperty().bind(showContactPoints);
        penetrationVectors.visibleProperty().bind(showPenetrationVectors);
        velocityVectors.visibleProperty().bind(showVelocityVectors);
        forceVectors.visibleProperty().bind(showForceVectors);
        aabbBoxes.visibleProperty().bind(showAABBs);
        
        // Listen for data changes
        shapes.addListener((javafx.collections.ListChangeListener<CollisionShape>) change -> updateShapeVisualizations());
        bodies.addListener((javafx.collections.ListChangeListener<RigidBody>) change -> updateBodyVisualizations());
        contacts.addListener((javafx.collections.ListChangeListener<ContactInfo>) change -> updateContactVisualizations());
    }
    
    /**
     * Add a collision shape to be visualized.
     */
    public void addShape(CollisionShape shape) {
        shapes.add(shape);
    }
    
    /**
     * Remove a collision shape from visualization.
     */
    public void removeShape(CollisionShape shape) {
        shapes.remove(shape);
        var node = shapeNodes.remove(shape);
        if (node != null) {
            shapeWireframes.getChildren().remove(node);
        }
    }
    
    /**
     * Add a rigid body to be visualized.
     */
    public void addRigidBody(RigidBody body) {
        bodies.add(body);
    }
    
    /**
     * Remove a rigid body from visualization.
     */
    public void removeRigidBody(RigidBody body) {
        bodies.remove(body);
        var nodes = bodyNodes.remove(body);
        if (nodes != null) {
            velocityVectors.getChildren().removeAll(nodes);
            forceVectors.getChildren().removeAll(nodes);
        }
    }
    
    /**
     * Add a contact point for visualization.
     */
    public void addContact(Point3f contactPoint, Vector3f normal, float penetrationDepth) {
        contacts.add(new ContactInfo(contactPoint, normal, penetrationDepth));
    }
    
    /**
     * Clear all contact points.
     */
    public void clearContacts() {
        contacts.clear();
    }
    
    /**
     * Update all visualizations (call this each frame).
     */
    public void update() {
        updateShapeVisualizations();
        updateBodyVisualizations();
        updateContactVisualizations();
    }
    
    /**
     * Update shape wireframe visualizations.
     */
    private void updateShapeVisualizations() {
        shapeWireframes.getChildren().clear();
        shapeNodes.clear();
        
        // Clear AABB boxes before adding new ones
        aabbBoxes.getChildren().clear();
        
        for (var shape : shapes) {
            var wireframe = CollisionShapeRenderer.renderWireframe(shape, wireframeColor.get());
            shapeNodes.put(shape, wireframe);
            shapeWireframes.getChildren().add(wireframe);
            
            // Add AABB if enabled
            if (showAABBs.get()) {
                var aabb = createAABBVisualization(shape);
                aabbBoxes.getChildren().add(aabb);
            }
        }
    }
    
    /**
     * Update rigid body visualizations (velocities, forces).
     */
    private void updateBodyVisualizations() {
        velocityVectors.getChildren().clear();
        forceVectors.getChildren().clear();
        bodyNodes.clear();
        
        for (var body : bodies) {
            var nodes = new ArrayList<Node>();
            
            // Velocity vector
            if (showVelocityVectors.get()) {
                var velocity = body.getLinearVelocity();
                if (velocity.length() > 0.01f) {
                    var velocityNode = createVectorVisualization(
                        body.getPosition(), velocity, velocityColor.get(), vectorScale.get()
                    );
                    velocityVectors.getChildren().add(velocityNode);
                    nodes.add(velocityNode);
                }
            }
            
            // Force vector (accumulated forces)
            if (showForceVectors.get()) {
                // Note: RigidBody would need to expose accumulated forces for this
                // For now, create a placeholder
                var forceNode = createForceVisualization(body);
                if (forceNode != null) {
                    forceVectors.getChildren().add(forceNode);
                    nodes.add(forceNode);
                }
            }
            
            bodyNodes.put(body, nodes);
        }
    }
    
    /**
     * Update contact point visualizations.
     */
    private void updateContactVisualizations() {
        contactPoints.getChildren().clear();
        penetrationVectors.getChildren().clear();
        
        for (var contact : contacts) {
            // Contact point
            var contactNode = CollisionShapeRenderer.createContactPoint(
                contact.point, contact.normal, contactPointColor.get()
            );
            contactPoints.getChildren().add(contactNode);
            
            // Penetration vector
            if (showPenetrationVectors.get() && contact.penetrationDepth > 0) {
                var penetrationNode = CollisionShapeRenderer.createPenetrationVector(
                    contact.point, contact.normal, contact.penetrationDepth, penetrationColor.get()
                );
                penetrationVectors.getChildren().add(penetrationNode);
            }
        }
    }
    
    /**
     * Create AABB visualization for a shape.
     */
    private Node createAABBVisualization(CollisionShape shape) {
        var bounds = shape.getAABB();
        var size = new Vector3f();
        size.sub(bounds.getMax(), bounds.getMin());
        
        var center = new Point3f();
        center.add(bounds.getMin(), bounds.getMax());
        center.scale(0.5f);
        
        var box = new com.hellblazer.luciferase.portal.collision.WireframeBox(size.x, size.y, size.z);
        box.setMaterial(new PhongMaterial(aabbColor.get()));
        box.setTranslateX(center.x);
        box.setTranslateY(center.y);
        box.setTranslateZ(center.z);
        
        return box;
    }
    
    /**
     * Create vector visualization.
     */
    private Node createVectorVisualization(Point3f origin, Vector3f vector, Color color, double scale) {
        var scaledVector = new Vector3f(vector);
        scaledVector.scale((float) scale);
        
        var end = new Point3f(origin);
        end.add(scaledVector);
        
        return CollisionShapeRenderer.createEdge(origin, end, new PhongMaterial(color));
    }
    
    /**
     * Create force visualization for a rigid body.
     */
    private Node createForceVisualization(RigidBody body) {
        // This would require exposing accumulated forces from RigidBody
        // For now, return null as a placeholder
        return null;
    }
    
    // Property getters
    public BooleanProperty showWireframesProperty() { return showWireframes; }
    public BooleanProperty showContactPointsProperty() { return showContactPoints; }
    public BooleanProperty showPenetrationVectorsProperty() { return showPenetrationVectors; }
    public BooleanProperty showVelocityVectorsProperty() { return showVelocityVectors; }
    public BooleanProperty showForceVectorsProperty() { return showForceVectors; }
    public BooleanProperty showAABBsProperty() { return showAABBs; }
    
    public ObjectProperty<Color> wireframeColorProperty() { return wireframeColor; }
    public ObjectProperty<Color> collisionColorProperty() { return collisionColor; }
    public ObjectProperty<Color> contactPointColorProperty() { return contactPointColor; }
    public ObjectProperty<Color> penetrationColorProperty() { return penetrationColor; }
    public ObjectProperty<Color> velocityColorProperty() { return velocityColor; }
    public ObjectProperty<Color> forceColorProperty() { return forceColor; }
    public ObjectProperty<Color> aabbColorProperty() { return aabbColor; }
    
    public DoubleProperty vectorScaleProperty() { return vectorScale; }
    public DoubleProperty contactPointScaleProperty() { return contactPointScale; }
    
    public Group getRootGroup() { return rootGroup; }
    public ObservableList<CollisionShape> getShapes() { return shapes; }
    public ObservableList<RigidBody> getBodies() { return bodies; }
    public ObservableList<ContactInfo> getContacts() { return contacts; }
    
    /**
     * Contact information for visualization.
     */
    public static class ContactInfo {
        public final Point3f point;
        public final Vector3f normal;
        public final float penetrationDepth;
        
        public ContactInfo(Point3f point, Vector3f normal, float penetrationDepth) {
            this.point = new Point3f(point);
            this.normal = new Vector3f(normal);
            this.penetrationDepth = penetrationDepth;
        }
    }
}