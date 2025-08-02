package com.hellblazer.luciferase.portal.mesh.explorer;

import javafx.application.Platform;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for transform-based entity system.
 */
@RequiresJavaFX
public class TransformBasedEntityTest {
    
    @BeforeAll
    public static void initJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Already initialized
        }
    }
    
    @Test
    public void testEntityStateCreation() {
        Point3f pos = new Point3f(100, 200, 300);
        var state = new TransformBasedEntity.EntityState(pos, Color.LIME, false, true, 10.0);
        
        assertEquals(pos, state.position);
        assertEquals(Color.LIME, state.color);
        assertFalse(state.selected);
        assertTrue(state.hasContainer);
        assertEquals(10.0, state.scale);
        
        // Test material key generation
        String key = state.getMaterialKey();
        assertTrue(key.contains("entity_"));
        assertTrue(key.contains(Color.LIME.toString()));
        assertTrue(key.contains("false")); // selected
        assertTrue(key.contains("true")); // hasContainer
    }
    
    @Test
    public void testEntityPoolBasics() {
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(100);
        var pool = new TransformBasedEntity.EntityPool(transformManager, materialPool, 50);
        
        // Test getting new instance
        Point3f pos1 = new Point3f(100, 200, 300);
        var state1 = new TransformBasedEntity.EntityState(pos1, Color.LIME, false, true, 10.0);
        var instance1 = pool.getInstance("entity1", state1);
        
        assertNotNull(instance1);
        assertEquals("entity1", instance1.getUserData());
        assertTrue(instance1.isVisible());
        
        // Test getting same entity returns same instance
        var instance1Again = pool.getInstance("entity1", state1);
        assertSame(instance1, instance1Again);
        
        // Test returning instance
        pool.returnInstance("entity1");
        assertFalse(instance1.isVisible());
        
        // Test reusing returned instance
        Point3f pos2 = new Point3f(400, 500, 600);
        var state2 = new TransformBasedEntity.EntityState(pos2, Color.RED, true, false, 20.0);
        var instance2 = pool.getInstance("entity2", state2);
        
        // Should reuse the returned instance
        assertSame(instance1, instance2);
        assertEquals("entity2", instance2.getUserData());
        assertTrue(instance2.isVisible());
    }
    
    @Test
    public void testEntityPoolStatistics() {
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(100);
        var pool = new TransformBasedEntity.EntityPool(transformManager, materialPool, 10);
        
        // Create multiple entities
        for (int i = 0; i < 5; i++) {
            Point3f pos = new Point3f(i * 100, i * 200, i * 300);
            var state = new TransformBasedEntity.EntityState(pos, Color.LIME, false, true, 10.0);
            pool.getInstance("entity" + i, state);
        }
        
        String stats = pool.getStatistics();
        assertTrue(stats.contains("total=5"));
        assertTrue(stats.contains("active=5"));
        assertTrue(stats.contains("available=0"));
        
        // Return some instances
        pool.returnInstance("entity0");
        pool.returnInstance("entity1");
        
        stats = pool.getStatistics();
        assertTrue(stats.contains("active=3"));
        assertTrue(stats.contains("available=2"));
    }
    
    @Test
    public void testEntityManager() {
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(100);
        var manager = new TransformBasedEntity.EntityManager(transformManager, materialPool, 100.0, 50);
        
        // Add entities
        manager.updateEntity("e1", new Point3f(100, 200, 300), Color.LIME, false, true);
        manager.updateEntity("e2", new Point3f(400, 500, 600), Color.RED, true, false);
        
        assertEquals(2, manager.getEntityGroup().getChildren().size());
        
        // Update entity (should reuse instance)
        manager.updateEntity("e1", new Point3f(150, 250, 350), Color.YELLOW, true, true);
        assertEquals(2, manager.getEntityGroup().getChildren().size());
        
        // Remove entity
        manager.removeEntity("e2");
        assertEquals(1, manager.getEntityGroup().getChildren().size());
        
        // Clear all
        manager.clearAll();
        assertEquals(0, manager.getEntityGroup().getChildren().size());
    }
    
    @Test
    public void testMaterialReuse() {
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(100);
        var pool = new TransformBasedEntity.EntityPool(transformManager, materialPool, 50);
        
        // Create entities with same visual state
        Point3f pos1 = new Point3f(100, 200, 300);
        Point3f pos2 = new Point3f(400, 500, 600);
        var state1 = new TransformBasedEntity.EntityState(pos1, Color.LIME, false, true, 10.0);
        var state2 = new TransformBasedEntity.EntityState(pos2, Color.LIME, false, true, 10.0);
        
        var instance1 = pool.getInstance("entity1", state1);
        var instance2 = pool.getInstance("entity2", state2);
        
        // Should share the same material
        assertSame(instance1.getMaterial(), instance2.getMaterial());
        
        // Different state should get different material
        var state3 = new TransformBasedEntity.EntityState(pos1, Color.RED, true, false, 10.0);
        var instance3 = pool.getInstance("entity3", state3);
        assertNotSame(instance1.getMaterial(), instance3.getMaterial());
    }
    
    @Test
    public void testPoolSizeLimit() {
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(100);
        var pool = new TransformBasedEntity.EntityPool(transformManager, materialPool, 2); // Small pool
        
        // Create and return more instances than pool size
        for (int i = 0; i < 5; i++) {
            Point3f pos = new Point3f(i * 100, 0, 0);
            var state = new TransformBasedEntity.EntityState(pos, Color.LIME, false, true, 10.0);
            pool.getInstance("entity" + i, state);
        }
        
        // Return all instances
        for (int i = 0; i < 5; i++) {
            pool.returnInstance("entity" + i);
        }
        
        // Pool should only keep maxPoolSize instances
        String stats = pool.getStatistics();
        assertTrue(stats.contains("available=2")); // Only 2 kept in pool
    }
}
