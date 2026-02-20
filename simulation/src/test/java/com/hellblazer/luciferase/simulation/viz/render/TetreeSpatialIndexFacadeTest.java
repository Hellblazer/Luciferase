/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

class TetreeSpatialIndexFacadeTest {

    private TetreeSpatialIndexFacade facade;

    @BeforeEach
    void setUp() { facade = new TetreeSpatialIndexFacade(6, 10); }

    @Test
    void putAndRetrieve() {
        facade.put(1L, new Point3f(100, 100, 100));
        assertEquals(1, facade.entityCount());
        var keys = facade.keysContaining(new Point3f(100, 100, 100), 6, 6);
        assertFalse(keys.isEmpty());
        keys.forEach(k -> assertInstanceOf(TetreeKey.class, k));
    }

    @Test
    void positionsAtContainsInsertedEntity() {
        var pos = new Point3f(200, 200, 200);
        facade.put(2L, pos);
        var keys = facade.keysContaining(pos, 6, 6);
        assertFalse(keys.isEmpty());
        var cell = keys.iterator().next();
        var positions = facade.positionsAt(cell);
        assertTrue(positions.stream().anyMatch(p -> p.epsilonEquals(pos, 0.1f)));
    }

    @Test
    void moveUpdatesCell() {
        var oldPos = new Point3f(100, 100, 100);
        var newPos = new Point3f(5000, 5000, 5000);
        facade.put(3L, oldPos);
        facade.move(3L, newPos);
        var oldKeys = facade.keysContaining(oldPos, 6, 6);
        // old cell should now be empty (or the entity is no longer there)
        oldKeys.forEach(k -> assertFalse(facade.positionsAt(k).stream()
                                                .anyMatch(p -> p.epsilonEquals(oldPos, 0.1f))));
    }

    @Test
    void removeDecrementsCount() {
        facade.put(4L, new Point3f(300, 300, 300));
        facade.remove(4L);
        assertEquals(0, facade.entityCount());
    }

    @Test
    void allOccupiedKeysAtLevel() {
        facade.put(5L, new Point3f(100, 100, 100));
        facade.put(6L, new Point3f(5000, 5000, 5000));
        var occupied = facade.allOccupiedKeys(6);
        assertFalse(occupied.isEmpty());
        occupied.forEach(k -> assertEquals(6, k.getLevel()));
    }
}
