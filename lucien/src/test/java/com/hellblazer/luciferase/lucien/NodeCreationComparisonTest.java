package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Test to demonstrate the difference in node creation between Octree and Tetree
 */
public class NodeCreationComparisonTest {
    
    @Test
    public void compareNodeCreation() {
        System.out.println("=== Node Creation Comparison: Octree vs Tetree ===\n");
        
        // Test parameters
        int entityCount = 1000;
        byte insertLevel = 10;
        Random random = new Random(42); // Fixed seed for reproducibility
        
        // Generate test entities
        List<TestEntity> entities = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            entities.add(new TestEntity(
                new LongEntityID(i),
                new Point3f(
                    random.nextFloat() * 1000,
                    random.nextFloat() * 1000,
                    random.nextFloat() * 1000
                ),
                "Entity" + i
            ));
        }
        
        // Test Octree
        System.out.println("OCTREE TEST:");
        var octreeIdGen = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(octreeIdGen);
        
        // Insert entities
        for (TestEntity entity : entities) {
            octree.insert(entity.id, entity.position, insertLevel, entity.data);
        }
        
        // Access protected field via reflection for Octree
        int octreeNodes = getNodeCountViaReflection(octree);
        System.out.println("Entities inserted: " + entityCount);
        System.out.println("Nodes created: " + octreeNodes);
        System.out.println("Average entities per node: " + String.format("%.2f", (double)entityCount / octreeNodes));
        
        // Test Tetree
        System.out.println("\nTETREE TEST:");
        var tetreeIdGen = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, String>(tetreeIdGen);
        
        // Insert same entities
        for (TestEntity entity : entities) {
            tetree.insert(entity.id, entity.position, insertLevel, entity.data);
        }
        
        int tetreeNodes = tetree.getNodeCount();
        System.out.println("Entities inserted: " + entityCount);
        System.out.println("Nodes created: " + tetreeNodes);
        System.out.println("Average entities per node: " + String.format("%.2f", (double)entityCount / tetreeNodes));
        
        // Get node count by level for Tetree
        var tetreeNodesByLevel = tetree.getNodeCountByLevel();
        System.out.println("Nodes by level:");
        tetreeNodesByLevel.forEach((level, count) -> 
            System.out.println("  Level " + level + ": " + count + " nodes"));
        
        // Summary
        System.out.println("\nSUMMARY:");
        System.out.println("Octree created " + octreeNodes + " nodes");
        System.out.println("Tetree created " + tetreeNodes + " nodes");
        System.out.println("Ratio (Octree/Tetree): " + String.format("%.1fx", (double)octreeNodes / tetreeNodes));
        
        // Analysis
        System.out.println("\nANALYSIS:");
        if (tetreeNodes < octreeNodes / 10) {
            System.out.println("⚠️  Tetree is creating significantly fewer nodes than Octree!");
            System.out.println("This suggests Tetree may not be subdividing nodes properly.");
            
            // Check if all entities ended up in just a few nodes
            if (tetreeNodes <= 10) {
                System.out.println("⚠️  Tetree has very few nodes (" + tetreeNodes + "), which means:");
                System.out.println("   - Most/all entities are likely in the same node(s)");
                System.out.println("   - Subdivision is not being triggered");
                System.out.println("   - Performance will degrade with many entities in one node");
            }
        }
    }
    
    private static class TestEntity {
        final LongEntityID id;
        final Point3f position;
        final String data;
        
        TestEntity(LongEntityID id, Point3f position, String data) {
            this.id = id;
            this.position = position;
            this.data = data;
        }
    }
    
    private int getNodeCountViaReflection(Object spatialIndex) {
        try {
            Field field = AbstractSpatialIndex.class.getDeclaredField("spatialIndex");
            field.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) field.get(spatialIndex);
            return map.size();
        } catch (Exception e) {
            throw new RuntimeException("Failed to access spatialIndex field", e);
        }
    }
}