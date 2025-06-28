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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.entity.UUIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 1: Core Entity System
 *
 * @author hal.hildebrand
 */
public class OctreeTest {

    private Octree<LongEntityID, String>     longIdOctree;
    private Octree<UUIDEntityID, TestEntity> uuidOctree;

    @BeforeEach
    void setUp() {
        longIdOctree = new Octree<>(new SequentialLongIDGenerator());
        uuidOctree = new Octree<>(new UUIDGenerator());
    }

    @Test
    void testBasicInsertAndLookup() {
        // Insert with auto-generated ID
        var pos1 = new Point3f(100, 100, 100);
        var id1 = longIdOctree.insert(pos1, (byte) 15, "Entity1");

        assertNotNull(id1);
        assertEquals(0, id1.getValue());

        // Insert with explicit ID
        var id2 = new LongEntityID(42);
        var pos2 = new Point3f(200, 200, 200);
        longIdOctree.insert(id2, pos2, (byte) 15, "Entity2");

        // Lookup entities
        var idsAtPos1 = longIdOctree.lookup(pos1, (byte) 15);
        assertEquals(1, idsAtPos1.size());
        assertEquals(id1, idsAtPos1.get(0));

        // Get entity content
        var content1 = longIdOctree.getEntity(id1);
        assertEquals("Entity1", content1);

        var content2 = longIdOctree.getEntity(id2);
        assertEquals("Entity2", content2);
    }

    @Test
    void testEntityIDGeneration() {
        // Test sequential long ID generation
        var longGen = new SequentialLongIDGenerator();
        var id1 = longGen.generateID();
        var id2 = longGen.generateID();

        assertEquals(0, id1.getValue());
        assertEquals(1, id2.getValue());
        assertNotEquals(id1, id2);

        // Test UUID generation
        var uuidGen = new UUIDGenerator();
        var uuid1 = uuidGen.generateID();
        var uuid2 = uuidGen.generateID();

        assertNotNull(uuid1.getValue());
        assertNotNull(uuid2.getValue());
        assertNotEquals(uuid1, uuid2);
    }

    @Test
    void testEntityRemoval() {
        var pos = new Point3f(100, 100, 100);
        var level = (byte) 10;

        // Insert entities
        var id1 = longIdOctree.insert(pos, level, "Entity1");
        var id2 = longIdOctree.insert(pos, level, "Entity2");

        // Remove one entity
        assertTrue(longIdOctree.removeEntity(id1));
        assertFalse(longIdOctree.containsEntity(id1));
        assertTrue(longIdOctree.containsEntity(id2));

        // Lookup should only return remaining entity
        var ids = longIdOctree.lookup(pos, level);
        assertEquals(1, ids.size());
        assertEquals(id2, ids.get(0));

        // Try to remove non-existent entity
        assertFalse(longIdOctree.removeEntity(id1));
    }

    @Test
    void testEntityUpdate() {
        var oldPos = new Point3f(100, 100, 100);
        var newPos = new Point3f(5000, 5000, 5000);
        var level = (byte) 15;

        // Insert entity
        var id = longIdOctree.insert(oldPos, level, "MovingEntity");

        // Update position
        longIdOctree.updateEntity(id, newPos, level);

        // Should not be at old position
        var idsAtOld = longIdOctree.lookup(oldPos, level);
        assertTrue(idsAtOld.isEmpty());

        // Should be at new position
        var idsAtNew = longIdOctree.lookup(newPos, level);
        assertEquals(1, idsAtNew.size());
        assertEquals(id, idsAtNew.get(0));

        // Content should be preserved
        assertEquals("MovingEntity", longIdOctree.getEntity(id));
    }

    @Test
    void testMultipleEntitiesPerNode() {
        var samePos = new Point3f(100, 100, 100);
        var level = (byte) 10;

        // Insert multiple entities at same position
        var id1 = longIdOctree.insert(samePos, level, "Entity1");
        var id2 = longIdOctree.insert(samePos, level, "Entity2");
        var id3 = longIdOctree.insert(samePos, level, "Entity3");

        // All should have different IDs
        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
        assertNotEquals(id1, id3);

        // Lookup should return all three
        var ids = longIdOctree.lookup(samePos, level);
        assertEquals(3, ids.size());
        assertTrue(ids.contains(id1));
        assertTrue(ids.contains(id2));
        assertTrue(ids.contains(id3));

        // Get all content
        var contents = longIdOctree.getEntities(ids);
        assertEquals(3, contents.size());
        assertTrue(contents.contains("Entity1"));
        assertTrue(contents.contains("Entity2"));
        assertTrue(contents.contains("Entity3"));
    }

    @Test
    void testRegionQuery() {
        // Insert entities at various positions
        longIdOctree.insert(new Point3f(100, 100, 100), (byte) 10, "E1");
        longIdOctree.insert(new Point3f(150, 150, 150), (byte) 10, "E2");
        longIdOctree.insert(new Point3f(200, 200, 200), (byte) 10, "E3");
        longIdOctree.insert(new Point3f(300, 300, 300), (byte) 10, "E4");

        // Query region: cube from (50,50,50) to (250,250,250)
        var region = new Spatial.Cube(50, 50, 50, 200);
        var entitiesInRegion = longIdOctree.entitiesInRegion(region);

        // E1 (100,100,100), E2 (150,150,150), and E3 (200,200,200) are inside
        // E4 (300,300,300) is outside the region
        assertEquals(3, entitiesInRegion.size());

        var contents = longIdOctree.getEntities(entitiesInRegion);
        assertTrue(contents.contains("E1"));
        assertTrue(contents.contains("E2"));
        assertTrue(contents.contains("E3"));
        assertFalse(contents.contains("E4"));
    }

    @Test
    void testStatistics() {
        // Insert some entities
        longIdOctree.insert(new Point3f(100, 100, 100), (byte) 15, "E1");
        longIdOctree.insert(new Point3f(100, 100, 100), (byte) 15, "E2");
        longIdOctree.insert(new Point3f(5000, 5000, 5000), (byte) 15, "E3");

        var stats = longIdOctree.getStats();

        assertEquals(2, stats.nodeCount()); // Two different positions
        assertEquals(3, stats.entityCount()); // Three entities total
        assertEquals(3, stats.totalEntityReferences()); // No spanning yet
        assertTrue(stats.maxDepth() >= 0);

        System.out.println("Stats: " + stats);
    }

    @Test
    void testUUIDEntitySystem() {
        // Test with UUID-based entities
        var entity1 = new TestEntity("Test1", 100);
        var entity2 = new TestEntity("Test2", 200);

        var pos1 = new Point3f(100, 100, 100);
        var pos2 = new Point3f(200, 200, 200);

        var id1 = uuidOctree.insert(pos1, (byte) 10, entity1);
        var id2 = uuidOctree.insert(pos2, (byte) 10, entity2);

        // UUIDs should be valid
        assertNotNull(id1.getValue());
        assertNotNull(id2.getValue());
        assertNotEquals(id1.getValue(), id2.getValue());

        // Retrieve entities
        var retrieved1 = uuidOctree.getEntity(id1);
        var retrieved2 = uuidOctree.getEntity(id2);

        assertEquals("Test1", retrieved1.name);
        assertEquals(100, retrieved1.value);
        assertEquals("Test2", retrieved2.name);
        assertEquals(200, retrieved2.value);
    }

    static class TestEntity {
        final String name;
        final int    value;

        TestEntity(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }
}
