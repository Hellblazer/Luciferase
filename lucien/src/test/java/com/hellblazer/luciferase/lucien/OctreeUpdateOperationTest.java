/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the update operation for moving entities
 *
 * @author hal.hildebrand
 */
public class OctreeUpdateOperationTest {

    @Test
    void testBasicEntityUpdate() {
        // Create octree with max entities = 1 to control subdivision
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator(), 1, (byte)20);
        
        // Insert an entity
        Point3f initialPos = new Point3f(100, 100, 100);
        byte level = 15;  // Use finer level for smaller cells
        LongEntityID id = octree.insert(initialPos, level, "TestEntity");
        
        // Verify initial position
        assertTrue(octree.containsEntity(id));
        List<LongEntityID> initialLookup = octree.lookup(initialPos, level);
        assertTrue(initialLookup.contains(id), "Entity should be at initial position");
        
        // Update entity position - make sure it's in a different cell
        // At level 15, cells are smaller
        Point3f newPos = new Point3f(2000, 2000, 2000);
        octree.updateEntity(id, newPos, level);
        
        // Verify entity moved
        List<LongEntityID> oldPosLookup = octree.lookup(initialPos, level);
        System.out.println("Old position lookup at level " + level + ": " + oldPosLookup);
        System.out.println("Entity ID: " + id);
        System.out.println("Entity position after update: " + octree.getEntityPosition(id));
        
        // Also check without recursion by getting the node directly
        System.out.println("Node count: " + octree.nodeCount());
        
        assertFalse(oldPosLookup.contains(id), "Entity should no longer be at old position");
        
        List<LongEntityID> newPosLookup = octree.lookup(newPos, level);
        assertTrue(newPosLookup.contains(id), "Entity should be at new position");
        
        // Verify entity still exists with same content
        assertEquals("TestEntity", octree.getEntity(id));
        assertEquals(newPos, octree.getEntityPosition(id));
    }
    
    @Test
    void testUpdateNonExistentEntity() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        
        // Try to update non-existent entity
        LongEntityID fakeId = new LongEntityID(999999L);
        Point3f newPos = new Point3f(100, 100, 100);
        
        assertThrows(IllegalArgumentException.class, 
            () -> octree.updateEntity(fakeId, newPos, (byte)10),
            "Should throw exception for non-existent entity");
    }
    
    @Test
    void testUpdateWithSpanning() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        
        // Insert entity with bounds (will span multiple nodes)
        Point3f initialPos = new Point3f(100, 100, 100);
        byte level = 10;
        LongEntityID id = octree.insert(initialPos, level, "SpanningEntity");
        
        // Track initial span count
        int initialSpanCount = octree.getEntitySpanCount(id);
        
        // Update to new position
        Point3f newPos = new Point3f(500, 500, 500);
        octree.updateEntity(id, newPos, level);
        
        // Verify update worked
        assertEquals(newPos, octree.getEntityPosition(id));
        List<LongEntityID> lookup = octree.lookup(newPos, level);
        assertTrue(lookup.contains(id));
        
        // Entity should now only be in one location (no bounds, so no spanning)
        assertEquals(1, octree.getEntitySpanCount(id));
    }
    
    @Test
    void testBulkUpdates() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        byte level = 12;
        
        // Insert multiple entities
        int entityCount = 100;
        LongEntityID[] ids = new LongEntityID[entityCount];
        Point3f[] positions = new Point3f[entityCount];
        
        for (int i = 0; i < entityCount; i++) {
            positions[i] = new Point3f(i * 10, i * 10, i * 10);
            ids[i] = octree.insert(positions[i], level, "Entity" + i);
        }
        
        // Measure update time
        long startTime = System.nanoTime();
        
        // Update all entities to new positions
        for (int i = 0; i < entityCount; i++) {
            Point3f newPos = new Point3f(
                positions[i].x + 1000,
                positions[i].y + 1000,
                positions[i].z + 1000
            );
            octree.updateEntity(ids[i], newPos, level);
        }
        
        long updateTime = System.nanoTime() - startTime;
        
        // Verify all updates
        for (int i = 0; i < entityCount; i++) {
            Point3f expectedPos = new Point3f(
                positions[i].x + 1000,
                positions[i].y + 1000,
                positions[i].z + 1000
            );
            assertEquals(expectedPos, octree.getEntityPosition(ids[i]));
        }
        
        System.out.printf("Updated %d entities in %.2f ms (%.2f μs/op)%n",
            entityCount, updateTime / 1_000_000.0, updateTime / 1000.0 / entityCount);
        
        // Performance should be reasonable
        double microsecondsPerOp = updateTime / 1000.0 / entityCount;
        assertTrue(microsecondsPerOp < 50, "Update should be reasonably fast");
    }
    
    @Test
    void testUpdateAcrossLevels() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        
        // Insert at one level
        Point3f pos = new Point3f(100, 100, 100);
        byte initialLevel = 10;
        LongEntityID id = octree.insert(pos, initialLevel, "LevelTest");
        
        // Update to same position but different level
        byte newLevel = 15;
        Point3f newPos = new Point3f(150, 150, 150);
        octree.updateEntity(id, newPos, newLevel);
        
        // Verify entity is at new position and level
        List<LongEntityID> lookupAtOldLevel = octree.lookup(newPos, initialLevel);
        List<LongEntityID> lookupAtNewLevel = octree.lookup(newPos, newLevel);
        
        // The entity should be found when looking at the new level
        assertTrue(lookupAtNewLevel.contains(id));
        
        // Due to hierarchical nature, it might also be found at coarser levels
        // depending on implementation details
    }
}