/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the Tetree multi-entity implementation
 *
 * @author hal.hildebrand
 */
public class TetreeTest {

    private Tetree<LongEntityID, String> tetree;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }

    @Test
    void testBasicInsertAndLookup() {
        // Insert at positive coordinates (Tetree requirement)
        Point3f position = new Point3f(100, 100, 100);
        byte level = 10;

        LongEntityID id1 = tetree.insert(position, level, "Entity1");
        LongEntityID id2 = tetree.insert(position, level, "Entity2");

        // Verify entities exist
        assertTrue(tetree.containsEntity(id1));
        assertTrue(tetree.containsEntity(id2));
        assertEquals(2, tetree.entityCount());

        // Lookup at position
        List<LongEntityID> found = tetree.lookup(position, level);
        assertEquals(2, found.size());
        assertTrue(found.contains(id1));
        assertTrue(found.contains(id2));

        // Get content
        assertEquals("Entity1", tetree.getEntity(id1));
        assertEquals("Entity2", tetree.getEntity(id2));
    }

    @Test
    void testEntityRemoval() {
        Point3f position = new Point3f(200, 200, 200);
        byte level = 12;

        LongEntityID id1 = tetree.insert(position, level, "ToRemove1");
        LongEntityID id2 = tetree.insert(position, level, "ToKeep");

        assertEquals(2, tetree.entityCount());

        // Remove one entity
        assertTrue(tetree.removeEntity(id1));
        assertEquals(1, tetree.entityCount());
        assertFalse(tetree.containsEntity(id1));
        assertTrue(tetree.containsEntity(id2));

        // Lookup should only find remaining entity
        List<LongEntityID> found = tetree.lookup(position, level);
        assertEquals(1, found.size());
        assertTrue(found.contains(id2));
    }

    @Test
    void testEntityUpdate() {
        // Use positions that are in different grid cells
        Point3f oldPos = new Point3f(300, 300, 300);
        Point3f newPos = new Point3f(3000, 3000, 3000); // Different cell at level 10
        byte level = 10;

        LongEntityID id = tetree.insert(oldPos, level, "Mobile");

        // Update position
        tetree.updateEntity(id, newPos, level);

        // Should not be at old position
        List<LongEntityID> atOld = tetree.lookup(oldPos, level);
        assertFalse(atOld.contains(id));

        // Should be at new position
        List<LongEntityID> atNew = tetree.lookup(newPos, level);
        assertTrue(atNew.contains(id));

        // Position should be updated
        assertEquals(newPos, tetree.getEntityPosition(id));
    }

    @Test
    void testKNearestNeighbors() {
        // Use a finer level for more precise positioning
        byte level = 15;

        // Insert some entities
        LongEntityID id1 = tetree.insert(new Point3f(100, 100, 100), level, "Near1");
        LongEntityID id2 = tetree.insert(new Point3f(110, 110, 110), level, "Near2");
        LongEntityID id3 = tetree.insert(new Point3f(200, 200, 200), level, "Far1");
        LongEntityID id4 = tetree.insert(new Point3f(500, 500, 500), level, "VeryFar");

        // Find 2 nearest to (105, 105, 105)
        Point3f queryPoint = new Point3f(105, 105, 105);
        List<LongEntityID> nearest = tetree.kNearestNeighbors(queryPoint, 2, Float.MAX_VALUE);

        assertEquals(2, nearest.size());
        // The two nearest should be id1 and id2
        assertTrue(nearest.contains(id1));
        assertTrue(nearest.contains(id2));

        // Verify that far entities are not included
        assertFalse(nearest.contains(id3));
        assertFalse(nearest.contains(id4));
    }

    @Test
    void testMultipleTypesInSameCell() {
        // In tetrahedral decomposition, each grid cell has 6 tetrahedra (types 0-5)
        // Entities in the same grid cell but different tetrahedra should be separate

        Point3f pos = new Point3f(100, 100, 100);
        byte level = 10;

        // Insert multiple entities at same position
        // They should all go to the same tetrahedron
        LongEntityID id1 = tetree.insert(pos, level, "E1");
        LongEntityID id2 = tetree.insert(pos, level, "E2");
        LongEntityID id3 = tetree.insert(pos, level, "E3");

        // All should be found at this position
        List<LongEntityID> found = tetree.lookup(pos, level);
        assertEquals(3, found.size());
        assertTrue(found.contains(id1));
        assertTrue(found.contains(id2));
        assertTrue(found.contains(id3));
    }

    @Test
    void testNegativeCoordinatesRejected() {
        // Tetree requires positive coordinates
        Point3f negativePos = new Point3f(-10, 50, 50);

        assertThrows(IllegalArgumentException.class, () -> {
            tetree.insert(negativePos, (byte) 10, "Should fail");
        });
    }

    @Test
    void testRegionQuery() {
        // At level 10, cell size is 2048, so use a finer level for better precision
        byte level = 15; // Smaller cells for more precise positioning

        // Insert entities at various positions
        LongEntityID id1 = tetree.insert(new Point3f(100, 100, 100), level, "E1");
        LongEntityID id2 = tetree.insert(new Point3f(150, 150, 150), level, "E2");
        LongEntityID id3 = tetree.insert(new Point3f(200, 200, 200), level, "E3");
        LongEntityID id4 = tetree.insert(new Point3f(300, 300, 300), level, "E4");

        // Query region from (50,50,50) to (250,250,250)
        Spatial.Cube region = new Spatial.Cube(50, 50, 50, 200);

        List<LongEntityID> inRegion = tetree.entitiesInRegion(region);

        // Should find E1, E2, E3 but not E4
        assertEquals(3, inRegion.size());
        assertTrue(inRegion.contains(id1));
        assertTrue(inRegion.contains(id2));
        assertTrue(inRegion.contains(id3));
        assertFalse(inRegion.contains(id4));
    }

    @Test
    void testSpatialNodeStream() {
        // Use positions that are far enough apart to be in different cells
        byte level = 10;

        // Insert entities at positions that will be in different grid cells
        tetree.insert(new Point3f(100, 100, 100), level, "E1");
        tetree.insert(new Point3f(3000, 3000, 3000), level, "E2");  // Different cell
        tetree.insert(new Point3f(5000, 5000, 5000), level, "E3");  // Different cell

        // Stream all nodes
        long nodeCount = tetree.nodes().count();
        assertEquals(3, nodeCount);

        // Each node should have exactly one entity (different positions)
        tetree.nodes().forEach(node -> assertEquals(1, node.entityIds().size()));
    }

    @Test
    void testStatistics() {
        // At level 10, cell size is 2048, so we need positions farther apart
        // Insert multiple entities
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(3000, 3000, 3000); // Far enough to be in different cell
        byte level = 10;

        tetree.insert(pos1, level, "E1");
        tetree.insert(pos1, level, "E2"); // Same position as E1
        tetree.insert(pos2, level, "E3"); // Different position

        var stats = tetree.getStats();
        assertEquals(2, stats.nodeCount()); // Two different positions
        assertEquals(3, stats.entityCount()); // Three entities total
        assertEquals(3, stats.totalEntityReferences()); // No spanning yet
        assertTrue(stats.maxDepth() >= 0);
    }

    @Test
    void testGetReturnsAllEntities() {
        // Insert multiple entities at the same position
        Point3f position = new Point3f(100, 100, 100);
        byte level = 10;
        
        tetree.insert(position, level, "Entity1");
        tetree.insert(position, level, "Entity2");
        tetree.insert(position, level, "Entity3");
        
        // Find the tetrahedral index for this position
        Tet tet = tetree.locateTetrahedron(position, level);
        long tetIndex = tet.index();
        
        // Get all entities at this index
        List<String> contents = tetree.get(tetIndex);
        
        // Verify we get all three entities
        assertEquals(3, contents.size());
        assertTrue(contents.contains("Entity1"));
        assertTrue(contents.contains("Entity2"));
        assertTrue(contents.contains("Entity3"));
        
        // Test empty index returns empty list
        List<String> emptyContents = tetree.get(tetIndex + 1000); // Different index
        assertTrue(emptyContents.isEmpty());
    }
}
