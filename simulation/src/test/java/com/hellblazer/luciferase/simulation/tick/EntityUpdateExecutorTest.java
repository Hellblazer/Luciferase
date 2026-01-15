/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.tick;

import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble.EntityRecord;
import com.hellblazer.luciferase.simulation.distributed.grid.GridConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for EntityUpdateExecutor.
 *
 * @author hal.hildebrand
 */
class EntityUpdateExecutorTest {

    private EntityUpdateExecutor executor;
    private EntityBehavior behavior;
    private Map<UUID, Vector3f> velocities;
    private GridConfiguration gridConfig;
    private EnhancedBubble bubble;

    @BeforeEach
    void setUp() {
        behavior = mock(EntityBehavior.class);
        velocities = new ConcurrentHashMap<>();
        gridConfig = new GridConfiguration(2, 2, 100f, 100f, 0f, 0f);

        executor = EntityUpdateExecutor.create(behavior, velocities, gridConfig);
        bubble = mock(EnhancedBubble.class);
    }

    @Test
    void testUpdateSingleEntity() {
        // Given: Entity at (50, 50, 50) with velocity (1, 2, 3)
        var entityId = "entity1";
        var position = new Point3f(50f, 50f, 50f);
        var entity = new EntityRecord(entityId, position, "content", 0L);
        var velocity = new Vector3f(1f, 2f, 3f);

        when(bubble.getAllEntityRecords()).thenReturn(List.of(entity));
        when(behavior.computeVelocity(eq(entityId), any(Point3f.class), any(Vector3f.class), eq(bubble), anyFloat()))
            .thenReturn(velocity);

        // When: Update entities with deltaTime = 1.0
        executor.updateEntities(bubble, 1.0f);

        // Then: Entity moved by velocity vector
        verify(bubble).updateEntityPosition(eq(entityId), argThat(pos ->
            Math.abs(pos.x - 51f) < 0.01f &&
            Math.abs(pos.y - 52f) < 0.01f &&
            Math.abs(pos.z - 53f) < 0.01f
        ));

        // Velocity map updated
        var entityUUID = UUID.nameUUIDFromBytes(entityId.getBytes());
        assertEquals(velocity, velocities.get(entityUUID));
    }

    @Test
    void testVelocityMapUpdates() {
        // Given: Entity with previous velocity in map
        var entityId = "entity1";
        var position = new Point3f(50f, 50f, 50f);
        var entity = new EntityRecord(entityId, position, "content", 0L);
        var entityUUID = UUID.nameUUIDFromBytes(entityId.getBytes());
        var oldVelocity = new Vector3f(5f, 5f, 5f);
        var newVelocity = new Vector3f(1f, 2f, 3f);

        velocities.put(entityUUID, oldVelocity);

        when(bubble.getAllEntityRecords()).thenReturn(List.of(entity));
        when(behavior.computeVelocity(eq(entityId), any(Point3f.class), eq(oldVelocity), eq(bubble), anyFloat()))
            .thenReturn(newVelocity);

        // When: Update entities
        executor.updateEntities(bubble, 1.0f);

        // Then: Velocity map updated with new velocity
        assertEquals(newVelocity, velocities.get(entityUUID));
        assertNotEquals(oldVelocity, velocities.get(entityUUID));
    }

    @Test
    void testPositionCalculation() {
        // Given: Entity at (10, 20, 30) with velocity (2, 4, 6)
        var entityId = "entity1";
        var position = new Point3f(10f, 20f, 30f);
        var entity = new EntityRecord(entityId, position, "content", 0L);
        var velocity = new Vector3f(2f, 4f, 6f);

        when(bubble.getAllEntityRecords()).thenReturn(List.of(entity));
        when(behavior.computeVelocity(anyString(), any(Point3f.class), any(Vector3f.class), eq(bubble), anyFloat()))
            .thenReturn(velocity);

        // When: Update with deltaTime = 0.5
        executor.updateEntities(bubble, 0.5f);

        // Then: Position updated by velocity * deltaTime
        // Expected: (10 + 2*0.5, 20 + 4*0.5, 30 + 6*0.5) = (11, 22, 33)
        verify(bubble).updateEntityPosition(eq(entityId), argThat(pos ->
            Math.abs(pos.x - 11f) < 0.01f &&
            Math.abs(pos.y - 22f) < 0.01f &&
            Math.abs(pos.z - 33f) < 0.01f
        ));
    }

    @Test
    void testGridBoundaryClampingX() {
        // Given: Entity moving beyond X boundary
        var entityId = "entity1";
        var position = new Point3f(195f, 50f, 50f); // Near right edge (totalWidth = 200)
        var entity = new EntityRecord(entityId, position, "content", 0L);
        var velocity = new Vector3f(100f, 0f, 0f); // Large velocity pushing right

        when(bubble.getAllEntityRecords()).thenReturn(List.of(entity));
        when(behavior.computeVelocity(anyString(), any(Point3f.class), any(Vector3f.class), eq(bubble), anyFloat()))
            .thenReturn(velocity);

        // When: Update entities
        executor.updateEntities(bubble, 1.0f);

        // Then: X clamped to grid max (200)
        verify(bubble).updateEntityPosition(eq(entityId), argThat(pos ->
            Math.abs(pos.x - 200f) < 0.01f &&
            Math.abs(pos.y - 50f) < 0.01f &&
            Math.abs(pos.z - 50f) < 0.01f
        ));
    }

    @Test
    void testGridBoundaryClampingY() {
        // Given: Entity moving below Y boundary
        var entityId = "entity1";
        var position = new Point3f(50f, 5f, 50f); // Near bottom edge
        var entity = new EntityRecord(entityId, position, "content", 0L);
        var velocity = new Vector3f(0f, -100f, 0f); // Large velocity pushing down

        when(bubble.getAllEntityRecords()).thenReturn(List.of(entity));
        when(behavior.computeVelocity(anyString(), any(Point3f.class), any(Vector3f.class), eq(bubble), anyFloat()))
            .thenReturn(velocity);

        // When: Update entities
        executor.updateEntities(bubble, 1.0f);

        // Then: Y clamped to grid min (0)
        verify(bubble).updateEntityPosition(eq(entityId), argThat(pos ->
            Math.abs(pos.x - 50f) < 0.01f &&
            Math.abs(pos.y - 0f) < 0.01f &&
            Math.abs(pos.z - 50f) < 0.01f
        ));
    }

    @Test
    void testGridBoundaryClampingZ() {
        // Given: Entity moving beyond Z boundary (hardcoded 0-100 range)
        var entityId = "entity1";
        var position = new Point3f(50f, 50f, 95f); // Near top Z
        var entity = new EntityRecord(entityId, position, "content", 0L);
        var velocity = new Vector3f(0f, 0f, 100f); // Large velocity pushing up

        when(bubble.getAllEntityRecords()).thenReturn(List.of(entity));
        when(behavior.computeVelocity(anyString(), any(Point3f.class), any(Vector3f.class), eq(bubble), anyFloat()))
            .thenReturn(velocity);

        // When: Update entities
        executor.updateEntities(bubble, 1.0f);

        // Then: Z clamped to max (100)
        verify(bubble).updateEntityPosition(eq(entityId), argThat(pos ->
            Math.abs(pos.x - 50f) < 0.01f &&
            Math.abs(pos.y - 50f) < 0.01f &&
            Math.abs(pos.z - 100f) < 0.01f
        ));
    }

    @Test
    void testMultipleEntities() {
        // Given: Multiple entities with different velocities
        var entity1 = new EntityRecord("entity1", new Point3f(10f, 10f, 10f), "c1", 0L);
        var entity2 = new EntityRecord("entity2", new Point3f(20f, 20f, 20f), "c2", 0L);
        var entity3 = new EntityRecord("entity3", new Point3f(30f, 30f, 30f), "c3", 0L);

        when(bubble.getAllEntityRecords()).thenReturn(List.of(entity1, entity2, entity3));
        when(behavior.computeVelocity(eq("entity1"), any(Point3f.class), any(Vector3f.class), eq(bubble), anyFloat()))
            .thenReturn(new Vector3f(1f, 0f, 0f));
        when(behavior.computeVelocity(eq("entity2"), any(Point3f.class), any(Vector3f.class), eq(bubble), anyFloat()))
            .thenReturn(new Vector3f(0f, 1f, 0f));
        when(behavior.computeVelocity(eq("entity3"), any(Point3f.class), any(Vector3f.class), eq(bubble), anyFloat()))
            .thenReturn(new Vector3f(0f, 0f, 1f));

        // When: Update all entities
        executor.updateEntities(bubble, 1.0f);

        // Then: All entities updated independently
        verify(bubble, times(3)).updateEntityPosition(anyString(), any(Point3f.class));
        assertEquals(3, velocities.size());
    }

    @Test
    void testEmptyBubble() {
        // Given: Bubble with no entities
        when(bubble.getAllEntityRecords()).thenReturn(Collections.emptyList());

        // When: Update entities
        executor.updateEntities(bubble, 1.0f);

        // Then: No updates performed
        verify(bubble, never()).updateEntityPosition(anyString(), any(Point3f.class));
        assertTrue(velocities.isEmpty());
    }

    @Test
    void testPerEntityExceptionHandling() {
        // Given: Entity that throws exception during update
        var entity1 = new EntityRecord("entity1", new Point3f(10f, 10f, 10f), "c1", 0L);
        var entity2 = new EntityRecord("entity2", new Point3f(20f, 20f, 20f), "c2", 0L);

        when(bubble.getAllEntityRecords()).thenReturn(List.of(entity1, entity2));
        when(behavior.computeVelocity(eq("entity1"), any(Point3f.class), any(Vector3f.class), eq(bubble), anyFloat()))
            .thenThrow(new RuntimeException("Test exception"));
        when(behavior.computeVelocity(eq("entity2"), any(Point3f.class), any(Vector3f.class), eq(bubble), anyFloat()))
            .thenReturn(new Vector3f(1f, 1f, 1f));

        // When: Update entities
        executor.updateEntities(bubble, 1.0f);

        // Then: Exception caught, entity2 still processed
        verify(bubble, never()).updateEntityPosition(eq("entity1"), any(Point3f.class));
        verify(bubble, times(1)).updateEntityPosition(eq("entity2"), any(Point3f.class));
        // Note: entity1 velocity is created by computeIfAbsent before exception, so 2 velocities exist
        assertEquals(2, velocities.size()); // Both entity velocities created (computeIfAbsent happens first)
    }

    @Test
    void testUUIDCollisionHandling() {
        // Given: Two entities that produce same UUID (edge case)
        var entityId = "entity1";
        var position = new Point3f(50f, 50f, 50f);
        var entity = new EntityRecord(entityId, position, "content", 0L);
        var entityUUID = UUID.nameUUIDFromBytes(entityId.getBytes());

        // Pre-populate velocity map
        var oldVelocity = new Vector3f(10f, 10f, 10f);
        velocities.put(entityUUID, oldVelocity);

        var newVelocity = new Vector3f(1f, 2f, 3f);
        when(bubble.getAllEntityRecords()).thenReturn(List.of(entity));
        when(behavior.computeVelocity(eq(entityId), any(Point3f.class), any(Vector3f.class), eq(bubble), anyFloat()))
            .thenReturn(newVelocity);

        // When: Update entities
        executor.updateEntities(bubble, 1.0f);

        // Then: Velocity map updated with new value (computeIfAbsent finds existing, put overwrites)
        assertEquals(newVelocity, velocities.get(entityUUID));
        assertEquals(1, velocities.size());
    }
}
