package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.TestOutputSuppressor;
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
        TestOutputSuppressor.println("=== Node Creation Comparison: Octree vs Tetree ===\n");
        
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
        TestOutputSuppressor.println("OCTREE TEST:");
        var octreeIdGen = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(octreeIdGen);
        
        // Insert entities
        for (TestEntity entity : entities) {
            octree.insert(entity.id, entity.position, insertLevel, entity.data);
        }
        
        // Access protected field via reflection for Octree
        int octreeNodes = getNodeCountViaReflection(octree);
        TestOutputSuppressor.println("Entities inserted: " + entityCount);
        TestOutputSuppressor.println("Nodes created: " + octreeNodes);
        TestOutputSuppressor.println("Average entities per node: " + String.format("%.2f", (double)entityCount / octreeNodes));
        
        // Test Tetree
        TestOutputSuppressor.println("\nTETREE TEST:");
        var tetreeIdGen = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, String>(tetreeIdGen);
        
        // Insert same entities
        for (TestEntity entity : entities) {
            tetree.insert(entity.id, entity.position, insertLevel, entity.data);
        }
        
        int tetreeNodes = tetree.getNodeCount();
        TestOutputSuppressor.println("Entities inserted: " + entityCount);
        TestOutputSuppressor.println("Nodes created: " + tetreeNodes);
        TestOutputSuppressor.println("Average entities per node: " + String.format("%.2f", (double)entityCount / tetreeNodes));
        
        // Get node count by level for Tetree
        var tetreeNodesByLevel = tetree.getNodeCountByLevel();
        TestOutputSuppressor.println("Nodes by level:");
        tetreeNodesByLevel.forEach((level, count) -> 
            TestOutputSuppressor.println("  Level " + level + ": " + count + " nodes"));
        
        // Summary
        TestOutputSuppressor.println("\nSUMMARY:");
        TestOutputSuppressor.println("Octree created " + octreeNodes + " nodes");
        TestOutputSuppressor.println("Tetree created " + tetreeNodes + " nodes");
        TestOutputSuppressor.println("Ratio (Octree/Tetree): " + String.format("%.1fx", (double)octreeNodes / tetreeNodes));
        
        // Analysis
        TestOutputSuppressor.println("\nANALYSIS:");
        if (tetreeNodes < octreeNodes / 10) {
            TestOutputSuppressor.println("⚠️  Tetree is creating significantly fewer nodes than Octree!");
            TestOutputSuppressor.println("This suggests Tetree may not be subdividing nodes properly.");
            
            // Check if all entities ended up in just a few nodes
            if (tetreeNodes <= 10) {
                TestOutputSuppressor.println("⚠️  Tetree has very few nodes (" + tetreeNodes + "), which means:");
                TestOutputSuppressor.println("   - Most/all entities are likely in the same node(s)");
                TestOutputSuppressor.println("   - Subdivision is not being triggered");
                TestOutputSuppressor.println("   - Performance will degrade with many entities in one node");
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