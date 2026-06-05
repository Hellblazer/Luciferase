/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.bubble;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for Point3f aliasing/mutation hazard in BubbleEntityStore (Luciferase-7wzml.189).
 * <p>
 * BubbleEntityData and BubbleEntityRecord now defensively copy Point3f so that:
 * (1) mutating the caller's Point3f after addEntity does not corrupt stored state, and
 * (2) mutating the Point3f returned by getAllEntityRecords does not corrupt the internal data.
 */
class BubbleEntityStoreAliasingTest {

    private BubbleEntityStore newStore() {
        var controller = new RealTimeController(UUID.randomUUID(), "alias-test", 100);
        return new BubbleEntityStore((byte) 10, controller);
    }

    /**
     * Mutating the caller's Point3f after addEntity must not corrupt the stored position.
     */
    @Test
    void addEntity_callerMutationDoesNotCorruptStoredPosition() {
        var store = newStore();
        var pos = new Point3f(10f, 20f, 30f);
        store.addEntity("e1", pos, "content");

        // Mutate the caller-owned Point3f after the add.
        pos.set(99f, 99f, 99f);

        // The stored position must still reflect the original value.
        var records = store.getAllEntityRecords();
        assertEquals(1, records.size());
        var stored = records.get(0).position();
        assertEquals(10f, stored.x, 0.001f, "stored x must not be corrupted by caller mutation after addEntity");
        assertEquals(20f, stored.y, 0.001f, "stored y must not be corrupted by caller mutation after addEntity");
        assertEquals(30f, stored.z, 0.001f, "stored z must not be corrupted by caller mutation after addEntity");
    }

    /**
     * Mutating the caller's Point3f after updateEntityPosition must not corrupt stored state.
     */
    @Test
    void updateEntityPosition_callerMutationDoesNotCorruptStoredPosition() {
        var store = newStore();
        store.addEntity("e1", new Point3f(1f, 1f, 1f), "content");

        var newPos = new Point3f(50f, 60f, 70f);
        store.updateEntityPosition("e1", newPos);

        // Mutate after update.
        newPos.set(0f, 0f, 0f);

        var records = store.getAllEntityRecords();
        assertEquals(1, records.size());
        var stored = records.get(0).position();
        assertEquals(50f, stored.x, 0.001f, "stored x after updateEntityPosition must not be corrupted");
        assertEquals(60f, stored.y, 0.001f, "stored y after updateEntityPosition must not be corrupted");
        assertEquals(70f, stored.z, 0.001f, "stored z after updateEntityPosition must not be corrupted");
    }

    /**
     * Mutating the Point3f returned by getAllEntityRecords must not corrupt the internal data.
     * A second call to getAllEntityRecords must return the original stored value.
     */
    @Test
    void getAllEntityRecords_returnedPositionMutationDoesNotCorruptStoredData() {
        var store = newStore();
        store.addEntity("e1", new Point3f(5f, 10f, 15f), "content");

        var firstFetch = store.getAllEntityRecords();
        assertEquals(1, firstFetch.size());

        // Mutate the returned position.
        firstFetch.get(0).position().set(999f, 999f, 999f);

        // A second fetch must still return the original stored value.
        var secondFetch = store.getAllEntityRecords();
        assertEquals(1, secondFetch.size());
        var stored = secondFetch.get(0).position();
        assertEquals(5f, stored.x, 0.001f, "internal position must not be corrupted by returned-record mutation");
        assertEquals(10f, stored.y, 0.001f);
        assertEquals(15f, stored.z, 0.001f);
    }
}
