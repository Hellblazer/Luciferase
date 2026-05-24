/*
 * Copyright (c) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the GhostLayer removal API added in PR Tranche C.
 * The original GhostLayer exposed only addGhostElement / addRemoteElement /
 * clear; there was no way to drop a single entry without wiping everything,
 * so callers leaked ghosts as topology changed.
 */
class GhostLayerRemovalTest {

    private static GhostElement<MortonKey, LongEntityID, String> ghost(int code, long id, String body) {
        return new GhostElement<>(
            new MortonKey(code),
            new LongEntityID(id),
            body,
            new Point3f(0f, 0f, 0f),
            0,
            0L
        );
    }

    private static GhostLayer.RemoteElement<MortonKey, LongEntityID, String> remote(int code, long id, String body) {
        return new GhostLayer.RemoteElement<>(
            new MortonKey(code),
            new LongEntityID(id),
            body,
            new Point3f(0f, 0f, 0f),
            0L
        );
    }

    @Test
    @DisplayName("removeGhostElement returns true and decrements count when present")
    void testRemoveGhostElementPresent() {
        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        var g1 = ghost(1, 1L, "a");
        var g2 = ghost(1, 2L, "b");
        layer.addGhostElement(g1);
        layer.addGhostElement(g2);
        assertEquals(2L, layer.getNumGhostElements());

        assertTrue(layer.removeGhostElement(new MortonKey(1), g1));
        assertEquals(1L, layer.getNumGhostElements());
        assertEquals(1, layer.getGhostElements(new MortonKey(1)).size());
        assertEquals(g2, layer.getGhostElements(new MortonKey(1)).get(0));
    }

    @Test
    @DisplayName("removeGhostElement returns false when absent or wrong key")
    void testRemoveGhostElementAbsent() {
        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        layer.addGhostElement(ghost(1, 1L, "a"));

        assertFalse(layer.removeGhostElement(new MortonKey(1), ghost(1, 99L, "missing")));
        assertFalse(layer.removeGhostElement(new MortonKey(2), ghost(1, 1L, "a")));
        assertEquals(1L, layer.getNumGhostElements());
    }

    @Test
    @DisplayName("removeGhostElement drops the per-key bucket when it empties")
    void testRemoveGhostElementCollapsesEmptyBucket() {
        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        var g = ghost(1, 1L, "a");
        layer.addGhostElement(g);

        assertTrue(layer.removeGhostElement(new MortonKey(1), g));
        // Bucket should be gone — getGhostElements returns empty list, not a
        // lingering empty list, and getAllGhostElements is empty.
        assertTrue(layer.getGhostElements(new MortonKey(1)).isEmpty());
        assertTrue(layer.getAllGhostElements().isEmpty());
    }

    @Test
    @DisplayName("removeGhostElementsAt removes every entry at the key")
    void testRemoveGhostElementsAt() {
        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        layer.addGhostElement(ghost(7, 1L, "a"));
        layer.addGhostElement(ghost(7, 2L, "b"));
        layer.addGhostElement(ghost(7, 3L, "c"));
        layer.addGhostElement(ghost(8, 4L, "elsewhere"));

        int removed = layer.removeGhostElementsAt(new MortonKey(7));
        assertEquals(3, removed);
        assertEquals(1L, layer.getNumGhostElements());
        assertTrue(layer.getGhostElements(new MortonKey(7)).isEmpty());
        assertEquals(1, layer.getGhostElements(new MortonKey(8)).size());
    }

    @Test
    @DisplayName("removeRemoteElement returns true and decrements count when present")
    void testRemoveRemoteElementPresent() {
        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        var r1 = remote(1, 1L, "a");
        var r2 = remote(1, 2L, "b");
        layer.addRemoteElement(0, r1);
        layer.addRemoteElement(0, r2);
        assertEquals(2L, layer.getNumRemoteElements());

        assertTrue(layer.removeRemoteElement(0, r1));
        assertEquals(1L, layer.getNumRemoteElements());
        assertTrue(layer.getRemoteElements(0).contains(r2));
        assertFalse(layer.getRemoteElements(0).contains(r1));
    }

    @Test
    @DisplayName("removeRemoteElement drops the rank bucket when it empties")
    void testRemoveRemoteElementCollapsesEmptyBucket() {
        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        var r = remote(1, 1L, "a");
        layer.addRemoteElement(5, r);

        assertTrue(layer.removeRemoteElement(5, r));
        assertTrue(layer.getRemoteElements(5).isEmpty());
        assertFalse(layer.getRemoteRanks().contains(5));
    }

    @Test
    @DisplayName("removeRemoteElement returns false when absent or wrong rank")
    void testRemoveRemoteElementAbsent() {
        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        layer.addRemoteElement(0, remote(1, 1L, "a"));

        assertFalse(layer.removeRemoteElement(0, remote(1, 99L, "missing")));
        assertFalse(layer.removeRemoteElement(7, remote(1, 1L, "a")));
        assertEquals(1L, layer.getNumRemoteElements());
    }
}
