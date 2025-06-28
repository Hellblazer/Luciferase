package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Analysis test to understand memory usage differences between Octree and Tetree
 */
public class MemoryAnalysisTest {

    private static final byte TEST_LEVEL = 10;
    private static final int ENTITY_COUNT = 1000;

    @Test
    public void analyzeNodeCreation() {
        System.out.println("=== Node Creation Analysis ===");
        
        // Generate test entities
        var entities = generateEntities(ENTITY_COUNT);
        
        // Test Octree
        var idGen1 = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGen1);
        
        for (var e : entities) {
            octree.insert(e.id, e.position, TEST_LEVEL, e.data);
        }
        
        var octreeNodes = octree.nodeCount();
        
        System.out.println("\nOctree Statistics:");
        System.out.println("  Total Nodes: " + octreeNodes);
        System.out.println("  Total Entities: " + octree.entityCount());
        
        // Test Tetree
        var idGen2 = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, String>(idGen2);
        
        for (var e : entities) {
            tetree.insert(e.id, e.position, TEST_LEVEL, e.data);
        }
        
        var tetreeNodes = tetree.nodeCount();
        
        System.out.println("\nTetree Statistics:");
        System.out.println("  Total Nodes: " + tetreeNodes);
        System.out.println("  Total Entities: " + tetree.entityCount());
        
        System.out.println("\nComparison:");
        System.out.println("  Node Ratio (Tetree/Octree): " + ((double)tetreeNodes / octreeNodes));
        System.out.println("  Entities per Node (Octree): " + ((double)ENTITY_COUNT / octreeNodes));
        System.out.println("  Entities per Node (Tetree): " + ((double)ENTITY_COUNT / tetreeNodes));
        
        // Analyze memory per node
        System.gc();
        long baseMemory = getUsedMemory();
        
        // Create empty structures to measure overhead
        var emptyOctree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        System.gc();
        long octreeBaseMemory = getUsedMemory() - baseMemory;
        
        emptyOctree = null;
        System.gc();
        baseMemory = getUsedMemory();
        
        var emptyTetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        System.gc();
        long tetreeBaseMemory = getUsedMemory() - baseMemory;
        
        System.out.println("\nBase Memory Usage:");
        System.out.println("  Empty Octree: " + (octreeBaseMemory / 1024) + " KB");
        System.out.println("  Empty Tetree: " + (tetreeBaseMemory / 1024) + " KB");
    }
    
    @Test
    public void analyzeSpatialDistribution() {
        System.out.println("\n=== Spatial Distribution Analysis ===");
        
        // Test with different distributions
        var distributions = List.of(
            "UNIFORM",
            "CLUSTERED",
            "DIAGONAL"
        );
        
        for (var dist : distributions) {
            System.out.println("\n" + dist + " Distribution:");
            analyzeDistribution(dist);
        }
    }
    
    private void analyzeDistribution(String distribution) {
        var entities = generateEntitiesWithDistribution(ENTITY_COUNT, distribution);
        
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        
        for (var e : entities) {
            octree.insert(e.id, e.position, TEST_LEVEL, e.data);
            tetree.insert(e.id, e.position, TEST_LEVEL, e.data);
        }
        
        System.out.println("  Octree Nodes: " + octree.nodeCount());
        System.out.println("  Tetree Nodes: " + tetree.nodeCount());
        System.out.println("  Ratio (T/O): " + ((double)tetree.nodeCount() / octree.nodeCount()));
    }
    
    private List<TestEntity> generateEntities(int count) {
        var entities = new ArrayList<TestEntity>(count);
        var random = ThreadLocalRandom.current();
        
        for (int i = 0; i < count; i++) {
            entities.add(new TestEntity(
                new LongEntityID(i),
                new Point3f(
                    random.nextFloat(0, 1000),
                    random.nextFloat(0, 1000),
                    random.nextFloat(0, 1000)
                ),
                "Entity" + i
            ));
        }
        return entities;
    }
    
    private List<TestEntity> generateEntitiesWithDistribution(int count, String distribution) {
        var entities = new ArrayList<TestEntity>(count);
        var random = ThreadLocalRandom.current();
        
        for (int i = 0; i < count; i++) {
            Point3f position;
            switch (distribution) {
                case "CLUSTERED":
                    // Create clusters around specific points
                    var clusterCenter = (i / 100) % 10;
                    var baseX = clusterCenter * 100;
                    var baseY = (clusterCenter / 3) * 100;
                    var baseZ = (clusterCenter / 7) * 100;
                    position = new Point3f(
                        Math.max(0.1f, baseX + random.nextFloat(-10, 10)),
                        Math.max(0.1f, baseY + random.nextFloat(-10, 10)),
                        Math.max(0.1f, baseZ + random.nextFloat(-10, 10))
                    );
                    break;
                    
                case "DIAGONAL":
                    // Points along diagonal
                    var t = (float)i / count;
                    position = new Point3f(
                        t * 1000 + random.nextFloat(-5, 5),
                        t * 1000 + random.nextFloat(-5, 5),
                        t * 1000 + random.nextFloat(-5, 5)
                    );
                    break;
                    
                default: // UNIFORM
                    position = new Point3f(
                        random.nextFloat(0, 1000),
                        random.nextFloat(0, 1000),
                        random.nextFloat(0, 1000)
                    );
            }
            
            entities.add(new TestEntity(new LongEntityID(i), position, "Entity" + i));
        }
        return entities;
    }
    
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
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
}