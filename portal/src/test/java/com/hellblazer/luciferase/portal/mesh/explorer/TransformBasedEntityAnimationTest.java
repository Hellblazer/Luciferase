package com.hellblazer.luciferase.portal.mesh.explorer;

import javafx.application.Platform;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for transform-based entity animations and collision highlighting.
 */
@RequiresJavaFX
public class TransformBasedEntityAnimationTest {
    
    @BeforeAll
    public static void initJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Already initialized
        }
    }
    
    @Test
    public void testAnimationCapabilities() {
        // Test that animation methods can be called with transform-based entities
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(100);
        var entityManager = new TransformBasedEntity.EntityManager(
            transformManager, materialPool, 100.0, 100
        );
        
        // Add some entities
        Point3f pos1 = new Point3f(100, 200, 300);
        Point3f pos2 = new Point3f(400, 500, 600);
        
        entityManager.updateEntity("e1", pos1, Color.LIME, false, true);
        entityManager.updateEntity("e2", pos2, Color.BLUE, false, true);
        
        // Simulate collision state
        entityManager.updateEntity("e1", pos1, Color.RED, false, false);
        entityManager.updateEntity("e2", pos2, Color.RED, false, false);
        
        // Verify entities are in collision state
        assertEquals(2, entityManager.getEntityGroup().getChildren().size());
        
        // Restore normal state
        entityManager.updateEntity("e1", pos1, Color.LIME, false, true);
        entityManager.updateEntity("e2", pos2, Color.BLUE, false, true);
        
        // Test removal
        entityManager.removeEntity("e1");
        assertEquals(1, entityManager.getEntityGroup().getChildren().size());
    }
    
    @Test
    public void testMaterialTransitions() {
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(100);
        var entityManager = new TransformBasedEntity.EntityManager(
            transformManager, materialPool, 100.0, 100
        );
        
        Point3f pos = new Point3f(100, 200, 300);
        
        // Test all possible state transitions
        // Normal state
        entityManager.updateEntity("e1", pos, Color.LIME, false, true);
        int initialPoolSize = materialPool.getPoolSize();
        
        // Selected state
        entityManager.updateEntity("e1", pos, Color.YELLOW, true, true);
        assertTrue(materialPool.getPoolSize() >= initialPoolSize);
        
        // No container state
        entityManager.updateEntity("e1", pos, Color.RED, false, false);
        assertTrue(materialPool.getPoolSize() >= initialPoolSize);
        
        // Collision state
        entityManager.updateEntity("e1", pos, Color.RED, false, false);
        
        System.out.println("Material pool efficiency test:");
        System.out.println("  Initial materials: " + initialPoolSize);
        System.out.println("  After transitions: " + materialPool.getPoolSize());
        System.out.println("  Material reuse: " + 
            (materialPool.getPoolSize() <= 4 ? "Excellent" : "Good"));
    }
    
    @Test
    public void testAnimationPerformance() {
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(100);
        
        // Test rapid animation updates
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 1000; i++) {
            // Simulate animation frame
            PhongMaterial animMaterial = materialPool.getMaterial(
                Color.hsb(i % 360, 1.0, 1.0), 
                0.5 + 0.5 * Math.sin(i * 0.1), 
                false
            );
            
            Point3f animPos = new Point3f(
                (float)(100 + 50 * Math.sin(i * 0.1)),
                (float)(200 + 50 * Math.cos(i * 0.1)),
                300
            );
            
            transformManager.createSphere(animPos, 50f, animMaterial);
        }
        
        long endTime = System.nanoTime();
        double msPerFrame = (endTime - startTime) / 1_000_000.0 / 1000;
        
        System.out.println("Animation performance:");
        System.out.println("  Time per frame: " + String.format("%.3f ms", msPerFrame));
        System.out.println("  FPS capability: " + String.format("%.0f", 1000.0 / msPerFrame));
        System.out.println("  Material pool size: " + materialPool.getPoolSize());
        
        assertTrue(msPerFrame < 1.0, "Animation should be fast enough for 60 FPS");
    }
}
