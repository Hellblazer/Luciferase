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
package com.hellblazer.luciferase.lucien.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for EntityManager
 *
 * @author hal.hildebrand
 */
public class EntityManagerTest {

    private EntityManager<LongEntityID, String> entityManager;
    private SequentialLongIDGenerator           idGenerator;

    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        entityManager = new EntityManager<>(idGenerator);
    }

    @Test
    void testClear() {
        // Add multiple entities
        for (int i = 0; i < 10; i++) {
            LongEntityID id = new LongEntityID(i);
            entityManager.createOrUpdateEntity(id, "Entity" + i, new Point3f(i, i, i), null);
            entityManager.addEntityLocation(id, i * 1000L);
        }

        assertEquals(10, entityManager.getEntityCount());
        assertFalse(entityManager.isEmpty());

        // Clear all
        entityManager.clear();

        assertEquals(0, entityManager.getEntityCount());
        assertTrue(entityManager.isEmpty());
        assertTrue(entityManager.getAllEntityIds().isEmpty());
    }

    @Test
    void testCreateOrUpdateEntity() {
        LongEntityID id = new LongEntityID(1);
        Point3f position = new Point3f(100, 200, 300);
        String content = "Test Entity";

        // Create new entity
        Entity<String> entity = entityManager.createOrUpdateEntity(id, content, position, null);
        assertNotNull(entity);
        assertEquals(content, entity.getContent());
        assertEquals(position, entity.getPosition());
        assertNull(entity.getBounds());

        // Update existing entity
        Point3f newPosition = new Point3f(400, 500, 600);
        EntityBounds bounds = new EntityBounds(newPosition, 50);
        Entity<String> updatedEntity = entityManager.createOrUpdateEntity(id, content, newPosition, bounds);
        assertNotNull(updatedEntity);
        assertEquals(newPosition, updatedEntity.getPosition());
        assertNotNull(updatedEntity.getBounds());
        assertEquals(bounds, updatedEntity.getBounds());
    }

    @Test
    void testCustomStorageMap() {
        // Test with custom ConcurrentHashMap
        ConcurrentHashMap<LongEntityID, Entity<String>> customMap = new ConcurrentHashMap<>();
        EntityManager<LongEntityID, String> customManager = new EntityManager<>(idGenerator, customMap);

        LongEntityID id = new LongEntityID(1);
        customManager.createOrUpdateEntity(id, "Test", new Point3f(0, 0, 0), null);

        // Verify the entity is in our custom map
        assertTrue(customMap.containsKey(id));
        assertEquals("Test", customMap.get(id).getContent());
    }

    @Test
    void testEntitiesWithPositions() {
        Map<LongEntityID, Point3f> expectedMap = new HashMap<>();

        for (int i = 0; i < 10; i++) {
            LongEntityID id = new LongEntityID(i);
            Point3f position = new Point3f(i * 10, i * 20, i * 30);
            expectedMap.put(id, position);
            entityManager.createOrUpdateEntity(id, "Entity" + i, position, null);
        }

        Map<LongEntityID, Point3f> actualMap = entityManager.getEntitiesWithPositions();
        assertEquals(10, actualMap.size());

        for (Map.Entry<LongEntityID, Point3f> entry : expectedMap.entrySet()) {
            assertTrue(actualMap.containsKey(entry.getKey()));
            assertEquals(entry.getValue(), actualMap.get(entry.getKey()));
        }

        // Verify the map is unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> actualMap.put(new LongEntityID(999), new Point3f()));
    }

    @Test
    void testEntityLifecycle() {
        LongEntityID id = new LongEntityID(42);
        Point3f position = new Point3f(1, 2, 3);
        String content = "Lifecycle Test";

        // Initially doesn't exist
        assertFalse(entityManager.containsEntity(id));
        assertEquals(0, entityManager.getEntityCount());
        assertTrue(entityManager.isEmpty());

        // Create entity
        entityManager.createOrUpdateEntity(id, content, position, null);
        assertTrue(entityManager.containsEntity(id));
        assertEquals(1, entityManager.getEntityCount());
        assertFalse(entityManager.isEmpty());

        // Add some locations
        entityManager.addEntityLocation(id, 1000L);
        entityManager.addEntityLocation(id, 2000L);

        // Remove entity
        Entity<String> removed = entityManager.removeEntity(id);
        assertNotNull(removed);
        assertEquals(content, removed.getContent());
        assertFalse(entityManager.containsEntity(id));
        assertEquals(0, entityManager.getEntityCount());
        assertTrue(entityManager.isEmpty());

        // Try to remove again
        assertNull(entityManager.removeEntity(id));
    }

    @Test
    void testEntityRetrieval() {
        LongEntityID id = new LongEntityID(42);
        Point3f position = new Point3f(10, 20, 30);
        String content = "Retrieval Test";
        EntityBounds bounds = new EntityBounds(position, 25);

        entityManager.createOrUpdateEntity(id, content, position, bounds);

        // Test getEntity
        Entity<String> entity = entityManager.getEntity(id);
        assertNotNull(entity);
        assertEquals(content, entity.getContent());

        // Test getEntityContent
        assertEquals(content, entityManager.getEntityContent(id));

        // Test getEntityPosition
        assertEquals(position, entityManager.getEntityPosition(id));

        // Test getEntityBounds
        assertEquals(bounds, entityManager.getEntityBounds(id));

        // Test with non-existent entity
        LongEntityID nonExistentId = new LongEntityID(999);
        assertNull(entityManager.getEntity(nonExistentId));
        assertNull(entityManager.getEntityContent(nonExistentId));
        assertNull(entityManager.getEntityPosition(nonExistentId));
        assertNull(entityManager.getEntityBounds(nonExistentId));
    }

    @Test
    void testEntitySpanCount() {
        LongEntityID id = new LongEntityID(1);
        entityManager.createOrUpdateEntity(id, "Test", new Point3f(0, 0, 0), null);

        // Initially no span
        assertEquals(0, entityManager.getEntitySpanCount(id));

        // Add locations
        entityManager.addEntityLocation(id, 100L);
        entityManager.addEntityLocation(id, 200L);
        entityManager.addEntityLocation(id, 300L);

        assertEquals(3, entityManager.getEntitySpanCount(id));

        // Test non-existent entity
        assertEquals(0, entityManager.getEntitySpanCount(new LongEntityID(999)));
    }

    @Test
    void testFindEntitiesInRegion() {
        // Create entities in a grid pattern
        Map<LongEntityID, Point3f> entityPositions = new HashMap<>();
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 10; y++) {
                for (int z = 0; z < 10; z++) {
                    LongEntityID id = new LongEntityID(x * 100 + y * 10 + z);
                    Point3f position = new Point3f(x * 100, y * 100, z * 100);
                    entityPositions.put(id, position);
                    entityManager.createOrUpdateEntity(id, "Entity", position, null);
                }
            }
        }

        // Find entities in a specific region
        List<LongEntityID> entitiesInRegion = entityManager.findEntitiesInRegion(150, 350,  // x range: 150-350
                                                                                 150, 350,  // y range: 150-350
                                                                                 150, 350   // z range: 150-350
                                                                                );

        // Should find entities at positions (200,200,200), (200,200,300), (200,300,200), etc.
        assertEquals(8, entitiesInRegion.size()); // 2x2x2 cube

        // Verify all found entities are actually in the region
        for (LongEntityID id : entitiesInRegion) {
            Point3f pos = entityPositions.get(id);
            assertTrue(pos.x >= 150 && pos.x <= 350);
            assertTrue(pos.y >= 150 && pos.y <= 350);
            assertTrue(pos.z >= 150 && pos.z <= 350);
        }

        // Test empty region
        List<LongEntityID> emptyRegion = entityManager.findEntitiesInRegion(1000, 2000, 1000, 2000, 1000, 2000);
        assertTrue(emptyRegion.isEmpty());
    }

    @Test
    void testGenerateEntityId() {
        LongEntityID id1 = entityManager.generateEntityId();
        LongEntityID id2 = entityManager.generateEntityId();

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
        assertTrue(id2.getValue() > id1.getValue());
    }

    @Test
    void testGetAllEntityIds() {
        Set<LongEntityID> expectedIds = new HashSet<>();

        for (int i = 0; i < 20; i++) {
            LongEntityID id = new LongEntityID(i);
            expectedIds.add(id);
            entityManager.createOrUpdateEntity(id, "Entity" + i, new Point3f(i, i, i), null);
        }

        Set<LongEntityID> actualIds = entityManager.getAllEntityIds();
        assertEquals(expectedIds, actualIds);

        // Verify returned set is a copy
        actualIds.clear();
        assertEquals(20, entityManager.getEntityCount());
    }

    @Test
    void testLocationManagement() {
        LongEntityID id = new LongEntityID(1);
        entityManager.createOrUpdateEntity(id, "Test", new Point3f(0, 0, 0), null);

        // Initially no locations
        Set<Long> locations = entityManager.getEntityLocations(id);
        assertTrue(locations.isEmpty());

        // Add locations
        entityManager.addEntityLocation(id, 12345L);
        entityManager.addEntityLocation(id, 67890L);

        locations = entityManager.getEntityLocations(id);
        assertEquals(2, locations.size());
        assertTrue(locations.contains(12345L));
        assertTrue(locations.contains(67890L));

        // Remove a location
        entityManager.removeEntityLocation(id, 12345L);
        locations = entityManager.getEntityLocations(id);
        assertEquals(1, locations.size());
        assertTrue(locations.contains(67890L));
        assertFalse(locations.contains(12345L));

        // Clear all locations
        entityManager.clearEntityLocations(id);
        locations = entityManager.getEntityLocations(id);
        assertTrue(locations.isEmpty());

        // Test with non-existent entity
        LongEntityID nonExistentId = new LongEntityID(999);
        entityManager.addEntityLocation(nonExistentId, 11111L); // Should not throw
        assertTrue(entityManager.getEntityLocations(nonExistentId).isEmpty());
    }

    @Test
    void testMultipleEntitiesContent() {
        List<LongEntityID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LongEntityID id = new LongEntityID(i);
            ids.add(id);
            entityManager.createOrUpdateEntity(id, "Content" + i, new Point3f(i, i, i), null);
        }

        List<String> contents = entityManager.getEntitiesContent(ids);
        assertEquals(5, contents.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("Content" + i, contents.get(i));
        }

        // Test with some non-existent IDs
        ids.add(new LongEntityID(999));
        contents = entityManager.getEntitiesContent(ids);
        assertEquals(5, contents.size()); // Non-existent entities are filtered out
    }

    @Test
    void testNullHandling() {
        // Test null ID generator
        assertThrows(NullPointerException.class, () -> new EntityManager<LongEntityID, String>(null));

        // Test null storage map
        assertThrows(NullPointerException.class, () -> new EntityManager<>(idGenerator, null));
    }

    @Test
    void testUpdateEntityPosition() {
        LongEntityID id = new LongEntityID(1);
        Point3f initialPosition = new Point3f(10, 20, 30);
        entityManager.createOrUpdateEntity(id, "Test", initialPosition, null);

        Point3f newPosition = new Point3f(40, 50, 60);
        entityManager.updateEntityPosition(id, newPosition);

        assertEquals(newPosition, entityManager.getEntityPosition(id));

        // Test with non-existent entity
        LongEntityID nonExistentId = new LongEntityID(999);
        assertThrows(IllegalArgumentException.class,
                     () -> entityManager.updateEntityPosition(nonExistentId, new Point3f()));
    }
}
