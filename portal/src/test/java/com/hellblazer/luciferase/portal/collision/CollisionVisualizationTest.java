/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.portal.collision;

import com.hellblazer.luciferase.lucien.collision.BoxShape;
import com.hellblazer.luciferase.lucien.collision.SphereShape;
import com.hellblazer.luciferase.lucien.collision.physics.InertiaTensor;
import com.hellblazer.luciferase.lucien.collision.physics.PhysicsMaterial;
import com.hellblazer.luciferase.lucien.collision.physics.RigidBody;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for collision visualization system.
 *
 * @author hal.hildebrand
 */
public class CollisionVisualizationTest {
    
    private CollisionVisualizer visualizer;
    
    @BeforeEach
    void setUp() {
        // Note: JavaFX tests require headless mode or special setup
        // This test focuses on the data model aspects
        visualizer = new CollisionVisualizer();
    }
    
    @Test
    void testShapeManagement() {
        var sphere = new SphereShape(new Point3f(0, 0, 0), 1.0f);
        var box = new BoxShape(new Point3f(2, 0, 0), new Vector3f(1, 1, 1));
        
        // Test adding shapes
        visualizer.addShape(sphere);
        visualizer.addShape(box);
        
        assertEquals(2, visualizer.getShapes().size());
        assertTrue(visualizer.getShapes().contains(sphere));
        assertTrue(visualizer.getShapes().contains(box));
        
        // Test removing shapes
        visualizer.removeShape(sphere);
        assertEquals(1, visualizer.getShapes().size());
        assertFalse(visualizer.getShapes().contains(sphere));
        assertTrue(visualizer.getShapes().contains(box));
    }
    
    @Test
    void testRigidBodyManagement() {
        var inertia = InertiaTensor.sphere(1.0f, 1.0f);
        var bodyA = new RigidBody(1.0f, inertia);
        var bodyB = new RigidBody(2.0f, inertia);
        
        // Test adding bodies
        visualizer.addRigidBody(bodyA);
        visualizer.addRigidBody(bodyB);
        
        assertEquals(2, visualizer.getBodies().size());
        assertTrue(visualizer.getBodies().contains(bodyA));
        assertTrue(visualizer.getBodies().contains(bodyB));
        
        // Test removing bodies
        visualizer.removeRigidBody(bodyA);
        assertEquals(1, visualizer.getBodies().size());
        assertFalse(visualizer.getBodies().contains(bodyA));
        assertTrue(visualizer.getBodies().contains(bodyB));
    }
    
    @Test
    void testContactManagement() {
        var contactPoint = new Point3f(1, 0, 0);
        var normal = new Vector3f(1, 0, 0);
        var penetrationDepth = 0.5f;
        
        // Test adding contacts
        visualizer.addContact(contactPoint, normal, penetrationDepth);
        assertEquals(1, visualizer.getContacts().size());
        
        var contact = visualizer.getContacts().get(0);
        assertEquals(contactPoint, contact.point);
        assertEquals(normal, contact.normal);
        assertEquals(penetrationDepth, contact.penetrationDepth);
        
        // Test clearing contacts
        visualizer.clearContacts();
        assertEquals(0, visualizer.getContacts().size());
    }
    
    @Test
    void testPropertyBinding() {
        // Test initial values
        assertTrue(visualizer.showWireframesProperty().get());
        assertTrue(visualizer.showContactPointsProperty().get());
        assertTrue(visualizer.showPenetrationVectorsProperty().get());
        assertFalse(visualizer.showVelocityVectorsProperty().get());
        assertFalse(visualizer.showForceVectorsProperty().get());
        assertFalse(visualizer.showAABBsProperty().get());
        
        // Test property changes
        visualizer.showWireframesProperty().set(false);
        assertFalse(visualizer.showWireframesProperty().get());
        
        visualizer.showVelocityVectorsProperty().set(true);
        assertTrue(visualizer.showVelocityVectorsProperty().get());
        
        // Test color properties
        visualizer.wireframeColorProperty().set(Color.RED);
        assertEquals(Color.RED, visualizer.wireframeColorProperty().get());
        
        // Test scale properties
        visualizer.vectorScaleProperty().set(2.0);
        assertEquals(2.0, visualizer.vectorScaleProperty().get());
    }
    
    @Test
    void testContactInfo() {
        var point = new Point3f(1, 2, 3);
        var normal = new Vector3f(0, 1, 0);
        var depth = 0.25f;
        
        var contact = new CollisionVisualizer.ContactInfo(point, normal, depth);
        
        // Test that data is copied (not referenced)
        assertNotSame(point, contact.point);
        assertNotSame(normal, contact.normal);
        
        // Test values
        assertEquals(point, contact.point);
        assertEquals(normal, contact.normal);
        assertEquals(depth, contact.penetrationDepth);
        
        // Test immutability by modifying originals
        point.x = 999;
        normal.y = 999;
        
        assertEquals(1, contact.point.x);
        assertEquals(1, contact.normal.y);
    }
    
    @Test
    void testVisualizationComponents() {
        // Test that scene graph components are properly initialized
        assertNotNull(visualizer.getRootGroup());
        assertNotNull(visualizer.getShapes());
        assertNotNull(visualizer.getBodies());
        assertNotNull(visualizer.getContacts());
        
        // Test that collections are observable
        assertTrue(visualizer.getShapes() instanceof javafx.collections.ObservableList);
        assertTrue(visualizer.getBodies() instanceof javafx.collections.ObservableList);
        assertTrue(visualizer.getContacts() instanceof javafx.collections.ObservableList);
    }
}