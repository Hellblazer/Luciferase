package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for the multi-entity spatial index interface
 *
 * @author hal.hildebrand
 */
public class SpatialIndexTest {

    private SpatialIndex<LongEntityID, String> spatialIndex;

    @BeforeEach
    void setUp() {
        // Create an Octree instance that implements the interface
        spatialIndex = new Octree<>(new SequentialLongIDGenerator());
    }

    @Test
    void testBasicInsertAndLookup() {
        // Insert with auto-generated ID
        Point3f pos1 = new Point3f(100, 100, 100);
        LongEntityID id1 = spatialIndex.insert(pos1, (byte) 15, "Entity1");

        assertNotNull(id1);
        assertEquals(0, id1.getValue());

        // Insert with explicit ID
        LongEntityID id2 = new LongEntityID(42);
        Point3f pos2 = new Point3f(200, 200, 200);
        spatialIndex.insert(id2, pos2, (byte) 15, "Entity2");

        // Lookup entities
        List<LongEntityID> idsAtPos1 = spatialIndex.lookup(pos1, (byte) 15);
        assertEquals(1, idsAtPos1.size());
        assertEquals(id1, idsAtPos1.get(0));

        // Get entity content
        String content1 = spatialIndex.getEntity(id1);
        assertEquals("Entity1", content1);

        String content2 = spatialIndex.getEntity(id2);
        assertEquals("Entity2", content2);
    }

    @Test
    void testEnclosingNode() {
        Point3f pos = new Point3f(100, 100, 100);
        byte level = 10;
        LongEntityID id = spatialIndex.insert(pos, level, "Test");

        // Test enclosing with Point3i
        Point3i point = new Point3i(100, 100, 100);
        var enclosing = spatialIndex.enclosing(point, level);
        assertNotNull(enclosing);
        assertTrue(enclosing.entityIds().contains(id));

        // Test enclosing with volume
        Spatial.Cube smallCube = new Spatial.Cube(90, 90, 90, 20);
        var volumeEnclosing = spatialIndex.enclosing(smallCube);
        assertNotNull(volumeEnclosing);
    }

    @Test
    void testEntityRemoval() {
        Point3f pos = new Point3f(100, 100, 100);
        byte level = 10;

        // Insert entities
        LongEntityID id1 = spatialIndex.insert(pos, level, "Entity1");
        LongEntityID id2 = spatialIndex.insert(pos, level, "Entity2");

        // Remove one entity
        assertTrue(spatialIndex.removeEntity(id1));
        assertFalse(spatialIndex.containsEntity(id1));
        assertTrue(spatialIndex.containsEntity(id2));

        // Lookup should only return remaining entity
        List<LongEntityID> ids = spatialIndex.lookup(pos, level);
        assertEquals(1, ids.size());
        assertEquals(id2, ids.get(0));
    }

    @Test
    void testEntityUpdate() {
        Point3f oldPos = new Point3f(100, 100, 100);
        Point3f newPos = new Point3f(5000, 5000, 5000);
        byte level = 15;

        // Insert entity
        LongEntityID id = spatialIndex.insert(oldPos, level, "MovingEntity");

        // Update position
        spatialIndex.updateEntity(id, newPos, level);

        // Should not be at old position
        List<LongEntityID> idsAtOld = spatialIndex.lookup(oldPos, level);
        assertTrue(idsAtOld.isEmpty());

        // Should be at new position
        List<LongEntityID> idsAtNew = spatialIndex.lookup(newPos, level);
        assertEquals(1, idsAtNew.size());
        assertEquals(id, idsAtNew.get(0));

        // Content should be preserved
        assertEquals("MovingEntity", spatialIndex.getEntity(id));
    }

    @Test
    void testMultipleEntitiesPerNode() {
        Point3f samePos = new Point3f(100, 100, 100);
        byte level = 10;

        // Insert multiple entities at same position
        LongEntityID id1 = spatialIndex.insert(samePos, level, "Entity1");
        LongEntityID id2 = spatialIndex.insert(samePos, level, "Entity2");
        LongEntityID id3 = spatialIndex.insert(samePos, level, "Entity3");

        // All should have different IDs
        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
        assertNotEquals(id1, id3);

        // Lookup should return all three
        List<LongEntityID> ids = spatialIndex.lookup(samePos, level);
        assertEquals(3, ids.size());
        assertTrue(ids.contains(id1));
        assertTrue(ids.contains(id2));
        assertTrue(ids.contains(id3));

        // Get all content
        List<String> contents = spatialIndex.getEntities(ids);
        assertEquals(3, contents.size());
        assertTrue(contents.contains("Entity1"));
        assertTrue(contents.contains("Entity2"));
        assertTrue(contents.contains("Entity3"));
    }

    @Test
    void testSpatialMap() {
        // Insert some entities
        spatialIndex.insert(new Point3f(100, 100, 100), (byte) 15, "E1");
        spatialIndex.insert(new Point3f(200, 200, 200), (byte) 15, "E2");

        var spatialMap = spatialIndex.getSpatialMap();
        assertNotNull(spatialMap);
        assertEquals(2, spatialMap.size());

        // Each entry should have entity IDs
        spatialMap.forEach((mortonIndex, entityIds) -> {
            assertNotNull(entityIds);
            assertFalse(entityIds.isEmpty());
        });
    }

    @Test
    void testSpatialQueries() {
        // Insert entities at various positions
        spatialIndex.insert(new Point3f(100, 100, 100), (byte) 15, "E1");
        spatialIndex.insert(new Point3f(150, 150, 150), (byte) 15, "E2");
        spatialIndex.insert(new Point3f(200, 200, 200), (byte) 15, "E3");
        spatialIndex.insert(new Point3f(300, 300, 300), (byte) 15, "E4");

        // Stream all nodes
        long nodeCount = spatialIndex.nodes().count();
        assertTrue(nodeCount > 0);

        // Test bounded query
        Spatial.Cube region = new Spatial.Cube(0, 0, 0, 300);
        var bounded = spatialIndex.boundedBy(region);
        assertNotNull(bounded);

        // Each node should have entity IDs
        bounded.forEach(node -> {
            assertNotNull(node.entityIds());
            assertFalse(node.entityIds().isEmpty());
        });
    }

    @Test
    void testStatistics() {
        // Insert some entities
        spatialIndex.insert(new Point3f(100, 100, 100), (byte) 15, "E1");
        spatialIndex.insert(new Point3f(100, 100, 100), (byte) 15, "E2");
        spatialIndex.insert(new Point3f(5000, 5000, 5000), (byte) 15, "E3");

        var stats = spatialIndex.getStats();

        assertEquals(2, stats.nodeCount()); // Two different positions
        assertEquals(3, stats.entityCount()); // Three entities total
        assertEquals(3, stats.totalEntityReferences()); // No spanning yet
        assertTrue(stats.maxDepth() >= 0);

        System.out.println("Stats: " + stats);
        System.out.println("Average entities per node: " + stats.averageEntitiesPerNode());
        System.out.println("Entity spanning factor: " + stats.entitySpanningFactor());
    }
}
