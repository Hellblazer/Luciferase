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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the entity position API additions to OctreeWithEntities
 *
 * @author hal.hildebrand
 */
public class PositionAPITest {
    
    private OctreeWithEntities<LongEntityID, String> octree;
    private final byte testLevel = 10;
    
    @BeforeEach
    void setUp() {
        octree = new OctreeWithEntities<>(new SequentialLongIDGenerator());
    }
    
    @Test
    void testGetEntityPosition() {
        // Insert entities at specific positions
        Point3f pos1 = new Point3f(100.0f, 200.0f, 300.0f);
        Point3f pos2 = new Point3f(400.0f, 500.0f, 600.0f);
        
        LongEntityID id1 = octree.insert(pos1, testLevel, "Entity1");
        LongEntityID id2 = octree.insert(pos2, testLevel, "Entity2");
        
        // Verify positions can be retrieved
        Point3f retrievedPos1 = octree.getEntityPosition(id1);
        Point3f retrievedPos2 = octree.getEntityPosition(id2);
        
        assertNotNull(retrievedPos1);
        assertNotNull(retrievedPos2);
        
        assertEquals(pos1.x, retrievedPos1.x, 0.001f);
        assertEquals(pos1.y, retrievedPos1.y, 0.001f);
        assertEquals(pos1.z, retrievedPos1.z, 0.001f);
        
        assertEquals(pos2.x, retrievedPos2.x, 0.001f);
        assertEquals(pos2.y, retrievedPos2.y, 0.001f);
        assertEquals(pos2.z, retrievedPos2.z, 0.001f);
    }
    
    @Test
    void testGetEntityPositionForNonExistentEntity() {
        // Try to get position for non-existent entity
        LongEntityID fakeId = new LongEntityID(999999L);
        Point3f position = octree.getEntityPosition(fakeId);
        
        assertNull(position);
    }
    
    @Test
    void testGetEntitiesWithPositions() {
        // Insert multiple entities
        Point3f[] positions = {
            new Point3f(100.0f, 100.0f, 100.0f),
            new Point3f(200.0f, 200.0f, 200.0f),
            new Point3f(300.0f, 300.0f, 300.0f),
            new Point3f(400.0f, 400.0f, 400.0f)
        };
        
        LongEntityID[] ids = new LongEntityID[positions.length];
        for (int i = 0; i < positions.length; i++) {
            ids[i] = octree.insert(positions[i], testLevel, "Entity" + i);
        }
        
        // Get all entities with positions
        Map<LongEntityID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        assertEquals(positions.length, entitiesWithPositions.size());
        
        // Verify each entity has correct position
        for (int i = 0; i < positions.length; i++) {
            Point3f retrievedPos = entitiesWithPositions.get(ids[i]);
            assertNotNull(retrievedPos);
            assertEquals(positions[i].x, retrievedPos.x, 0.001f);
            assertEquals(positions[i].y, retrievedPos.y, 0.001f);
            assertEquals(positions[i].z, retrievedPos.z, 0.001f);
        }
    }
    
    @Test
    void testPositionUpdateAfterEntityMove() {
        // Insert entity
        Point3f originalPos = new Point3f(100.0f, 100.0f, 100.0f);
        LongEntityID id = octree.insert(originalPos, testLevel, "MovingEntity");
        
        // Verify original position
        Point3f pos1 = octree.getEntityPosition(id);
        assertEquals(originalPos.x, pos1.x, 0.001f);
        assertEquals(originalPos.y, pos1.y, 0.001f);
        assertEquals(originalPos.z, pos1.z, 0.001f);
        
        // Update entity position
        Point3f newPos = new Point3f(500.0f, 500.0f, 500.0f);
        octree.updateEntity(id, newPos, testLevel);
        
        // Verify new position
        Point3f pos2 = octree.getEntityPosition(id);
        assertEquals(newPos.x, pos2.x, 0.001f);
        assertEquals(newPos.y, pos2.y, 0.001f);
        assertEquals(newPos.z, pos2.z, 0.001f);
    }
    
    @Test
    void testMultipleEntitiesAtSamePosition() {
        // Insert multiple entities at the same position
        Point3f sharedPos = new Point3f(250.0f, 250.0f, 250.0f);
        
        LongEntityID id1 = octree.insert(sharedPos, testLevel, "Entity1");
        LongEntityID id2 = octree.insert(sharedPos, testLevel, "Entity2");
        LongEntityID id3 = octree.insert(sharedPos, testLevel, "Entity3");
        
        // Each entity should maintain its own position
        Point3f pos1 = octree.getEntityPosition(id1);
        Point3f pos2 = octree.getEntityPosition(id2);
        Point3f pos3 = octree.getEntityPosition(id3);
        
        assertNotNull(pos1);
        assertNotNull(pos2);
        assertNotNull(pos3);
        
        // All should have the same position
        assertEquals(sharedPos.x, pos1.x, 0.001f);
        assertEquals(sharedPos.x, pos2.x, 0.001f);
        assertEquals(sharedPos.x, pos3.x, 0.001f);
        
        // But they should be distinct entities
        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
        assertNotEquals(id1, id3);
    }
    
    @Test
    void testPositionPersistsAfterRemoval() {
        // Insert multiple entities
        Point3f pos1 = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f pos2 = new Point3f(200.0f, 200.0f, 200.0f);
        
        LongEntityID id1 = octree.insert(pos1, testLevel, "Entity1");
        LongEntityID id2 = octree.insert(pos2, testLevel, "Entity2");
        
        // Remove first entity
        octree.removeEntity(id1);
        
        // Second entity position should still be valid
        Point3f retrievedPos2 = octree.getEntityPosition(id2);
        assertNotNull(retrievedPos2);
        assertEquals(pos2.x, retrievedPos2.x, 0.001f);
        
        // First entity position should be null
        Point3f retrievedPos1 = octree.getEntityPosition(id1);
        assertNull(retrievedPos1);
    }
    
    @Test
    void testPositionAccuracyWithKNearestNeighbor() {
        // Create entities at known distances
        Point3f queryPoint = new Point3f(500.0f, 500.0f, 500.0f);
        
        // Insert entities at various distances
        octree.insert(new Point3f(510.0f, 500.0f, 500.0f), testLevel, "10 units away");  // 10 units
        octree.insert(new Point3f(520.0f, 500.0f, 500.0f), testLevel, "20 units away");  // 20 units
        octree.insert(new Point3f(530.0f, 500.0f, 500.0f), testLevel, "30 units away");  // 30 units
        octree.insert(new Point3f(550.0f, 500.0f, 500.0f), testLevel, "50 units away");  // 50 units
        
        // Find k nearest neighbors
        var nearest = MultiEntityKNearestNeighborSearch.findKNearestEntities(queryPoint, 3, octree);
        
        assertEquals(3, nearest.size());
        
        // Verify they're returned in correct order
        assertEquals("10 units away", nearest.get(0).content);
        assertEquals("20 units away", nearest.get(1).content);
        assertEquals("30 units away", nearest.get(2).content);
        
        // Verify distances are correct
        assertEquals(10.0f, nearest.get(0).distance, 0.001f);
        assertEquals(20.0f, nearest.get(1).distance, 0.001f);
        assertEquals(30.0f, nearest.get(2).distance, 0.001f);
    }
    
    @Test
    void testSphereContainmentWithRealPositions() {
        // Create sphere at origin with radius 100
        Spatial.Sphere sphere = new Spatial.Sphere(0.0f, 0.0f, 0.0f, 100.0f);
        
        // Insert entities inside and outside sphere
        octree.insert(new Point3f(50.0f, 0.0f, 0.0f), testLevel, "Inside1");      // Distance = 50
        octree.insert(new Point3f(0.0f, 70.0f, 0.0f), testLevel, "Inside2");      // Distance = 70
        octree.insert(new Point3f(0.0f, 0.0f, 90.0f), testLevel, "Inside3");      // Distance = 90
        octree.insert(new Point3f(150.0f, 0.0f, 0.0f), testLevel, "Outside1");    // Distance = 150
        octree.insert(new Point3f(0.0f, 120.0f, 0.0f), testLevel, "Outside2");    // Distance = 120
        
        // Find entities in sphere
        var entitiesInSphere = MultiEntityContainmentSearch.findEntitiesInSphere(sphere, octree);
        
        // Should find exactly 3 entities
        assertEquals(3, entitiesInSphere.size());
        
        // Verify only inside entities are found
        var contents = entitiesInSphere.stream()
            .map(e -> e.content)
            .toList();
        
        assertTrue(contents.contains("Inside1"));
        assertTrue(contents.contains("Inside2"));
        assertTrue(contents.contains("Inside3"));
        assertFalse(contents.contains("Outside1"));
        assertFalse(contents.contains("Outside2"));
    }
}