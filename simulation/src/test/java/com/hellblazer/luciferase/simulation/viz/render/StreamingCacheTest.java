/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamingCacheTest {

    @Test
    void freshCacheReturnsEmpty() {
        var cache = new StreamingCache();
        var key = new MortonKey(0L, (byte) 5);
        assertNull(cache.get(key));
        assertEquals(0L, cache.cacheVersion(key));
    }

    @Test
    void putAndGet() {
        var cache = new StreamingCache();
        var key = new MortonKey(1L, (byte) 5);
        byte[] data = {1, 2, 3};
        cache.put(key, 1L, data);
        var entry = cache.get(key);
        assertNotNull(entry);
        assertEquals(1L, entry.version());
        assertArrayEquals(data, entry.data());
    }

    @Test
    void cacheVersionTracked() {
        var cache = new StreamingCache();
        var key = new MortonKey(2L, (byte) 5);
        assertEquals(0L, cache.cacheVersion(key));
        cache.put(key, 3L, new byte[]{});
        assertEquals(3L, cache.cacheVersion(key));
    }

    @Test
    void removeEvictsEntry() {
        var cache = new StreamingCache();
        var key = new MortonKey(3L, (byte) 5);
        cache.put(key, 7L, new byte[]{9});
        cache.remove(key);
        assertNull(cache.get(key));
        assertEquals(0L, cache.cacheVersion(key));
    }
}
