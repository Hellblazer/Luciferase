/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

class OctreeSpatialIndexFacadeTest {

    private OctreeSpatialIndexFacade facade;

    @BeforeEach
    void setUp() { facade = new OctreeSpatialIndexFacade(4, 12); }

    @Test
    void putAndRetrieve() {
        facade.put(1L, new Point3f(100, 100, 100));
        assertEquals(1, facade.entityCount());
        var keys = facade.keysContaining(new Point3f(100, 100, 100), 4, 4);
        assertFalse(keys.isEmpty());
        keys.forEach(k -> assertInstanceOf(MortonKey.class, k));
    }

    @Test
    void positionsAtContainsInsertedEntity() {
        var pos = new Point3f(500, 500, 500);
        facade.put(2L, pos);
        var keys = facade.keysContaining(pos, 4, 4);
        var cell = keys.iterator().next();
        var positions = facade.positionsAt(cell);
        assertTrue(positions.stream().anyMatch(p -> p.epsilonEquals(pos, 0.1f)));
    }

    @Test
    void allOccupiedKeysAtLevel() {
        facade.put(3L, new Point3f(100, 100, 100));
        var occupied = facade.allOccupiedKeys(4);
        assertFalse(occupied.isEmpty());
        occupied.forEach(k -> assertEquals(4, k.getLevel()));
    }

    @Test
    void moveUpdatesCell() {
        var oldPos = new Point3f(100, 100, 100);
        var newPos = new Point3f(500000, 500000, 500000);
        facade.put(4L, oldPos);
        facade.move(4L, newPos);
        var oldKeys = facade.keysContaining(oldPos, 4, 4);
        oldKeys.forEach(k -> assertFalse(facade.positionsAt(k).stream()
            .anyMatch(p -> p.epsilonEquals(oldPos, 0.1f)),
            "entity should no longer be in old cell after move"));
    }

    @Test
    void removeDecrementsCount() {
        facade.put(5L, new Point3f(200, 200, 200));
        facade.remove(5L);
        assertEquals(0, facade.entityCount());
    }
}
