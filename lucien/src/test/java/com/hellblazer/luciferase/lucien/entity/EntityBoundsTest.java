/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.entity;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-tzcv: {@link EntityBounds} is a value type (min/max), so equality must be by value, not reference
 * identity. Before this, two structurally-equal bounds compared unequal (inherited {@code Object.equals}),
 * forcing callers/tests to compare {@code getMin()}/{@code getMax()} field-by-field.
 *
 * @author hal.hildebrand
 */
class EntityBoundsTest {

    private static EntityBounds box(float a, float b) {
        return new EntityBounds(new Point3f(a, a, a), new Point3f(b, b, b));
    }

    @Test
    void equalByValue() {
        var x = box(1, 5);
        var y = box(1, 5);
        assertEquals(x, y, "structurally equal bounds are equal");
        assertEquals(x.hashCode(), y.hashCode(), "equal bounds have equal hashCode");
        assertNotSame(x, y, "precondition: distinct instances (not reference equality)");
    }

    @Test
    void reflexiveAndNullAndTypeSafe() {
        var x = box(0, 1);
        assertEquals(x, x, "reflexive");
        assertNotEquals(x, null, "never equal to null");
        assertNotEquals(x, "not bounds", "never equal to a different type");
    }

    @Test
    void differentMinOrMaxAreNotEqual() {
        assertNotEquals(box(1, 5), box(2, 5), "different min");
        assertNotEquals(box(1, 5), box(1, 6), "different max");
    }

    @Test
    void usableAsHashSetMember() {
        var set = new HashSet<EntityBounds>();
        set.add(box(1, 5));
        assertTrue(set.contains(box(1, 5)), "value-equal bounds resolve in a hash set");
        set.add(box(1, 5));
        assertEquals(1, set.size(), "duplicate value-equal bounds dedupe");
    }

    @Test
    void pointBoundsEqualByValue() {
        var p = new Point3f(3, 4, 5);
        assertEquals(EntityBounds.point(p), EntityBounds.point(new Point3f(3, 4, 5)),
                     "point bounds at the same position are equal");
    }
}
