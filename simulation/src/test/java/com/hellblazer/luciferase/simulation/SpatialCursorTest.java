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
package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.simulation.Cursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SpatialCursor adapter.
 *
 * @author hal.hildebrand
 */
class SpatialCursorTest {

    private static final byte LEVEL = 10;

    private Octree<LongEntityID, String> octree;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator(), 16, (byte) 21);
    }

    @Test
    void testGetLocation() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);
        var entityId = octree.insert(position, LEVEL, "test-entity");

        var cursor = new SpatialCursor<>(octree, entityId, LEVEL);

        var location = cursor.getLocation();
        assertNotNull(location);
        assertEquals(position.x, location.x, 0.0001f);
        assertEquals(position.y, location.y, 0.0001f);
        assertEquals(position.z, location.z, 0.0001f);
    }

    @Test
    void testMoveBy() {
        var initialPos = new Point3f(0.5f, 0.5f, 0.5f);
        var entityId = octree.insert(initialPos, LEVEL, "test-entity");

        var cursor = new SpatialCursor<>(octree, entityId, LEVEL);

        var delta = new Vector3f(0.1f, 0.1f, 0.1f);
        cursor.moveBy(delta);

        var newLocation = cursor.getLocation();
        assertEquals(0.6f, newLocation.x, 0.0001f);
        assertEquals(0.6f, newLocation.y, 0.0001f);
        assertEquals(0.6f, newLocation.z, 0.0001f);
    }

    @Test
    void testMoveTo() {
        var initialPos = new Point3f(0.5f, 0.5f, 0.5f);
        var entityId = octree.insert(initialPos, LEVEL, "test-entity");

        var cursor = new SpatialCursor<>(octree, entityId, LEVEL);

        var newPos = new Point3f(0.8f, 0.2f, 0.9f);
        cursor.moveTo(newPos);

        var location = cursor.getLocation();
        assertEquals(0.8f, location.x, 0.0001f);
        assertEquals(0.2f, location.y, 0.0001f);
        assertEquals(0.9f, location.z, 0.0001f);
    }

    @Test
    void testNeighbors() {
        // Insert several entities in proximity
        var center = new Point3f(0.5f, 0.5f, 0.5f);
        var centerId = octree.insert(center, LEVEL, "center");

        var nearby1 = octree.insert(new Point3f(0.51f, 0.5f, 0.5f), LEVEL, "nearby1");
        var nearby2 = octree.insert(new Point3f(0.5f, 0.51f, 0.5f), LEVEL, "nearby2");
        var nearby3 = octree.insert(new Point3f(0.5f, 0.5f, 0.51f), LEVEL, "nearby3");

        // Insert a far entity
        octree.insert(new Point3f(0.9f, 0.9f, 0.9f), LEVEL, "far");

        var cursor = new SpatialCursor<>(octree, centerId, LEVEL, 3, 0.1f);

        var neighbors = cursor.neighbors().toList();

        // Should find 3 nearby entities (excluding self)
        assertEquals(3, neighbors.size());

        // Verify neighbors are Cursor instances
        for (var neighbor : neighbors) {
            assertInstanceOf(Cursor.class, neighbor);
            assertNotNull(neighbor.getLocation());
        }
    }

    @Test
    void testVisitNeighbors() {
        var center = new Point3f(0.5f, 0.5f, 0.5f);
        var centerId = octree.insert(center, LEVEL, "center");

        octree.insert(new Point3f(0.51f, 0.5f, 0.5f), LEVEL, "nearby1");
        octree.insert(new Point3f(0.5f, 0.51f, 0.5f), LEVEL, "nearby2");

        var cursor = new SpatialCursor<>(octree, centerId, LEVEL, 5, 0.1f);

        var visited = new ArrayList<Cursor>();
        cursor.visitNeighbors(visited::add);

        assertEquals(2, visited.size());
    }

    @Test
    void testEqualsAndHashCode() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);
        var entityId = octree.insert(position, LEVEL, "test");

        var cursor1 = new SpatialCursor<>(octree, entityId, LEVEL);
        var cursor2 = new SpatialCursor<>(octree, entityId, LEVEL);

        assertEquals(cursor1, cursor2);
        assertEquals(cursor1.hashCode(), cursor2.hashCode());
    }

    @Test
    void testGetEntityId() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);
        var entityId = octree.insert(position, LEVEL, "test");

        var cursor = new SpatialCursor<>(octree, entityId, LEVEL);

        assertEquals(entityId, cursor.getEntityId());
    }

    @Test
    void testToString() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);
        var entityId = octree.insert(position, LEVEL, "test");

        var cursor = new SpatialCursor<>(octree, entityId, LEVEL);

        var str = cursor.toString();
        assertTrue(str.contains("SpatialCursor"));
        assertTrue(str.contains("level=" + LEVEL));
    }

    @Test
    void testMultipleMovements() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);
        var entityId = octree.insert(position, LEVEL, "test");

        var cursor = new SpatialCursor<>(octree, entityId, LEVEL);

        // Perform multiple movements
        for (int i = 0; i < 10; i++) {
            cursor.moveBy(new Vector3f(0.01f, 0.01f, 0.01f));
        }

        var finalLocation = cursor.getLocation();
        assertEquals(0.6f, finalLocation.x, 0.001f);
        assertEquals(0.6f, finalLocation.y, 0.001f);
        assertEquals(0.6f, finalLocation.z, 0.001f);
    }
}
