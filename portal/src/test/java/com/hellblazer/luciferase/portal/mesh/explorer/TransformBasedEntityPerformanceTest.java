package com.hellblazer.luciferase.portal.mesh.explorer;

import javafx.application.Platform;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

/**
 * Performance test for transform-based entity system.
 * Measures memory usage and object count reduction.
 */
public class TransformBasedEntityPerformanceTest {
    
    @BeforeAll
    public static void initJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Already initialized
        }
    }
    
    @Test
    public void testEntityCountComparison() {
        System.out.println("\n=== Transform-Based Entity Performance Test ===\n");
        
        // Test with different entity counts
        int[] entityCounts = {100, 1000, 10000};
        
        for (int count : entityCounts) {
            System.out.printf("Testing with %d entities:\n", count);
            
            // Traditional approach (simulated)
            long traditionalObjects = count; // Each entity = 1 sphere object
            long traditionalMaterials = count; // Worst case: each has unique material
            
            // Transform-based approach
            var transformManager = new PrimitiveTransformManager();
            var materialPool = new MaterialPool(100);
            var entityManager = new TransformBasedEntity.EntityManager(
                transformManager, materialPool, 100.0, Math.min(count / 10, 1000)
            );
            
            Random rand = new Random(42); // Fixed seed for reproducibility
            
            // Add entities with various states
            for (int i = 0; i < count; i++) {
                String id = "entity" + i;
                Point3f pos = new Point3f(
                    rand.nextFloat() * 1000,
                    rand.nextFloat() * 1000,
                    rand.nextFloat() * 1000
                );
                
                // Vary the visual states
                Color color = (i % 3 == 0) ? Color.LIME : (i % 3 == 1) ? Color.RED : Color.BLUE;
                boolean selected = i % 10 == 0;
                boolean hasContainer = i % 5 != 0;
                
                entityManager.updateEntity(id, pos, color, selected, hasContainer);
            }
            
            // Get statistics
            String stats = entityManager.getStatistics();
            System.out.println("  Entity Manager: " + stats);
            System.out.println("  Transform Manager: " + transformManager.getStatistics());
            System.out.println("  Material Pool Size: " + materialPool.getPoolSize());
            
            // Calculate reduction
            long transformObjects = 1; // Single reference sphere mesh
            long transformMaterials = materialPool.getPoolSize();
            
            double objectReduction = (1.0 - (double)transformObjects / traditionalObjects) * 100;
            double materialReduction = (1.0 - (double)transformMaterials / traditionalMaterials) * 100;
            
            System.out.printf("  Object Reduction: %.1f%% (%d → %d)\n", 
                objectReduction, traditionalObjects, transformObjects);
            System.out.printf("  Material Reduction: %.1f%% (%d → %d)\n", 
                materialReduction, traditionalMaterials, transformMaterials);
            System.out.println();
        }
        
        System.out.println("=== Memory Estimation ===");
        System.out.println("Traditional approach (per entity):");
        System.out.println("  - Sphere mesh: ~2KB");
        System.out.println("  - Material: ~200B");
        System.out.println("  - Transform: ~200B");
        System.out.println("  Total: ~2.4KB per entity");
        System.out.println();
        System.out.println("Transform-based approach:");
        System.out.println("  - Reference sphere: 2KB (shared)");
        System.out.println("  - Materials: ~200B × unique states");
        System.out.println("  - Per entity: Transform (200B) + reference (8B)");
        System.out.println("  Total: ~208B per entity + shared resources");
        System.out.println();
        
        // Memory savings calculation
        for (int count : entityCounts) {
            double traditionalMemoryKB = count * 2.4;
            double transformMemoryKB = (count * 0.208) + 2 + (10 * 0.2); // entities + sphere + materials
            double savingsPercent = (1.0 - transformMemoryKB / traditionalMemoryKB) * 100;
            
            System.out.printf("%d entities: %.1fKB → %.1fKB (%.1f%% savings)\n",
                count, traditionalMemoryKB, transformMemoryKB, savingsPercent);
        }
    }
    
    @Test
    public void testUpdatePerformance() {
        System.out.println("\n=== Entity Update Performance Test ===\n");
        
        var transformManager = new PrimitiveTransformManager();
        var materialPool = new MaterialPool(100);
        var entityManager = new TransformBasedEntity.EntityManager(
            transformManager, materialPool, 100.0, 1000
        );
        
        int entityCount = 1000;
        Random rand = new Random(42);
        
        // Create initial entities
        for (int i = 0; i < entityCount; i++) {
            String id = "entity" + i;
            Point3f pos = new Point3f(
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000
            );
            entityManager.updateEntity(id, pos, Color.LIME, false, true);
        }
        
        // Test update performance
        long startTime = System.nanoTime();
        int updates = 10000;
        
        for (int i = 0; i < updates; i++) {
            String id = "entity" + (i % entityCount);
            Point3f newPos = new Point3f(
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000
            );
            boolean selected = rand.nextBoolean();
            entityManager.updateEntity(id, newPos, Color.LIME, selected, true);
        }
        
        long endTime = System.nanoTime();
        double msPerUpdate = (endTime - startTime) / 1_000_000.0 / updates;
        double updatesPerSecond = 1000.0 / msPerUpdate;
        
        System.out.printf("Update performance: %.3f ms per update\n", msPerUpdate);
        System.out.printf("Updates per second: %.0f\n", updatesPerSecond);
        System.out.println("\nFinal statistics:");
        System.out.println("  " + entityManager.getStatistics());
        System.out.println("  Material Pool Size: " + materialPool.getPoolSize());
    }
}