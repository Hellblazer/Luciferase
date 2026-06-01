/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-v9ro: {@link GhostEntityHalo} — the top-level cross-tree ghost replica extracted from the deleted
 * {@code GhostZoneManager.GhostEntity}. Identity is by {@code (entityId, sourceTreeId)}; position is defensively
 * copied; null entityId/position/sourceTreeId are rejected.
 *
 * @author hal.hildebrand
 */
class GhostEntityHaloTest {

    private static GhostEntityHalo<LongEntityID, String> halo(long id, String tree) {
        return new GhostEntityHalo<>(new LongEntityID(id), "c", new Point3f(1, 2, 3), null, tree);
    }

    @Test
    void identityByEntityAndSourceTree() {
        assertEquals(halo(1, "treeA"), halo(1, "treeA"), "same (id, sourceTree) are equal");
        assertEquals(halo(1, "treeA").hashCode(), halo(1, "treeA").hashCode());
        assertNotEquals(halo(1, "treeA"), halo(1, "treeB"), "same entity from a different source tree differs");
        assertNotEquals(halo(2, "treeA"), halo(1, "treeA"), "different entity differs");
    }

    @Test
    void positionIsDefensivelyCopied() {
        var p = new Point3f(1, 2, 3);
        var h = new GhostEntityHalo<LongEntityID, String>(new LongEntityID(1), "c", p, null, "treeA");
        p.x = 99;                       // mutate caller's point after construction
        assertEquals(1f, h.getPosition().x, "constructor must copy the position");
        h.getPosition().x = 42;         // mutate the returned point
        assertEquals(1f, h.getPosition().x, "getter must return a fresh copy");
    }

    @Test
    void rejectsNullRequiredFields() {
        assertThrows(NullPointerException.class,
                     () -> new GhostEntityHalo<LongEntityID, String>(null, "c", new Point3f(), null, "t"));
        assertThrows(NullPointerException.class,
                     () -> new GhostEntityHalo<LongEntityID, String>(new LongEntityID(1), "c", null, null, "t"));
        assertThrows(NullPointerException.class,
                     () -> new GhostEntityHalo<LongEntityID, String>(new LongEntityID(1), "c", new Point3f(), null, null));
    }

    @Test
    void explicitTimestampIsStoredVerbatim() {
        // Luciferase-55pi: the deterministic seam — an injected timestamp is stored as-is (no wall-clock).
        var h = new GhostEntityHalo<LongEntityID, String>(new LongEntityID(1), "c", new Point3f(), null, "t",
                                                          123456789L);
        assertEquals(123456789L, h.getTimestamp(), "explicit timestamp must be stored verbatim");
    }

    @Test
    void clockBackedConstructorStampsFromSystemClock() {
        // The convenience constructor stamps from Clock.system() (not a direct System.currentTimeMillis call).
        long before = com.hellblazer.luciferase.common.time.Clock.system().currentTimeMillis();
        var h = new GhostEntityHalo<LongEntityID, String>(new LongEntityID(1), "c", new Point3f(), null, "t");
        long after = com.hellblazer.luciferase.common.time.Clock.system().currentTimeMillis();
        assertTrue(h.getTimestamp() >= before && h.getTimestamp() <= after,
                   "convenience constructor stamps a current timestamp via Clock.system()");
    }
}
