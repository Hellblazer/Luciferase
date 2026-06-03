/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness tests for the LIVE {@link TetreeLevelCache} key/slot machinery (Luciferase-egjwk).
 *
 * <p>This test used to be built around an old bit-packed key formula ({@code x<<32 | y<<16 | z | level<<8 | type})
 * that no longer exists in {@code TetreeLevelCache}; it reconstructed that dead formula locally and asserted a
 * collision in it as a positive, so it passed vacuously while the live {@code generateCacheKey} (fast-path
 * bit-pack for small coordinates / golden-ratio hash otherwise) had zero coverage.
 *
 * <p>The live cache is a direct-mapped open-addressing table: {@code slot = key & (SIZE-1)} with a stored-key
 * guard on read ({@code KEYS[slot] == key}). A slot collision therefore MUST return a miss ({@code -1}), never
 * another tuple's value. These tests exercise that contract through the public cache API, and are non-vacuous:
 * removing the stored-key guard (serving {@code VALUES[slot]} unconditionally) would make
 * {@link #slotCollisionNeverServesAnotherTuplesValue} fail.
 *
 * @author hal.hildebrand
 */
public class TetreeLevelCacheKeyCollisionTest {

    /** A tuple's cached value is its own identity, so any wrong-value serve is detectable. */
    private static long valueFor(int x, int y, int z, byte level, byte type) {
        return ((long) x << 40) ^ ((long) y << 24) ^ ((long) z << 8) ^ ((long) level << 3) ^ type ^ 0x5A5A5A5AL;
    }

    @Test
    public void roundTripReturnsTheCachedValue() {
        int x = 123, y = 456, z = 789;
        byte level = 7, type = 3;
        long v = valueFor(x, y, z, level, type);
        TetreeLevelCache.cacheIndex(x, y, z, level, type, v);
        assertEquals(v, TetreeLevelCache.getCachedIndex(x, y, z, level, type),
                     "freshly cached tuple must read back its own value");
    }

    /**
     * The original bug class: z overlapping level/type bits so that changing only level or only type collided.
     * Under the live fast-path pack, level and type occupy their own bits — caching one (level,type) must not
     * corrupt or overwrite a neighbouring (level,type) at the same coordinate.
     */
    @Test
    public void levelAndTypeAreIndependentInTheFastPath() {
        int x = 50, y = 60, z = 70;   // small coords -> fast-path pack
        TetreeLevelCache.cacheIndex(x, y, z, (byte) 5, (byte) 1, 1111L);
        TetreeLevelCache.cacheIndex(x, y, z, (byte) 0, (byte) 0, 2222L);  // old bug: same key as above
        TetreeLevelCache.cacheIndex(x, y, z, (byte) 5, (byte) 4, 3333L);  // differs only in type

        assertEquals(1111L, TetreeLevelCache.getCachedIndex(x, y, z, (byte) 5, (byte) 1));
        assertEquals(2222L, TetreeLevelCache.getCachedIndex(x, y, z, (byte) 0, (byte) 0));
        assertEquals(3333L, TetreeLevelCache.getCachedIndex(x, y, z, (byte) 5, (byte) 4));
    }

    /**
     * Cache two tuples chosen to land in the SAME index-cache slot but with DIFFERENT keys. After caching the
     * second, the first must report a miss (-1) — evicted — and never the second's (stale/wrong) value.
     */
    @Test
    public void slotCollisionEvictsRatherThanServingStale() {
        // INDEX_CACHE_SIZE = 4096 -> slot is key & 0xFFF. For small coords, key = x<<28|y<<18|z<<8|level<<3|type,
        // so the low 12 bits depend on (z low 4 bits, level, type). Pick two tuples with identical low-12-bit
        // patterns but different high bits (different y) => same slot, different key.
        int yA = 1, yB = 2;            // different high bits -> different key
        int x = 0, z = 0;
        byte level = 2, type = 1;      // identical low bits for both
        long vA = 9001L, vB = 9002L;

        TetreeLevelCache.cacheIndex(x, yA, z, level, type, vA);
        long slotShared = TetreeLevelCache.getCachedIndex(x, yA, z, level, type);
        assertEquals(vA, slotShared, "precondition: A is cached");

        TetreeLevelCache.cacheIndex(x, yB, z, level, type, vB);  // collides into A's slot with a different key

        // B reads its own value; A must NOT read B's value — at worst a miss.
        assertEquals(vB, TetreeLevelCache.getCachedIndex(x, yB, z, level, type));
        long a = TetreeLevelCache.getCachedIndex(x, yA, z, level, type);
        assertTrue(a == vA || a == -1L,
                   "after a same-slot/different-key write, the evicted tuple must read its own value or a miss, "
                   + "never the colliding tuple's value (got " + a + ")");
    }

    /**
     * Broad sweep: cache many distinct tuples, then read each back. The stored-key guard guarantees every read
     * returns either the tuple's own value or -1 (slot evicted) — never a value belonging to a different tuple.
     */
    @Test
    public void slotCollisionNeverServesAnotherTuplesValue() {
        Map<Long, long[]> valueToTuple = new HashMap<>();  // value -> {x,y,z,level,type}
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            int x = i % 800;
            int y = (i / 800) % 800;
            int z = (i * 7) % 800;
            byte level = (byte) (i % 21);
            byte type = (byte) (i % 6);
            long v = valueFor(x, y, z, level, type);
            valueToTuple.put(v, new long[] { x, y, z, level, type });
            TetreeLevelCache.cacheIndex(x, y, z, level, type, v);
        }

        int hits = 0;
        for (var e : valueToTuple.entrySet()) {
            long expected = e.getKey();
            long[] t = e.getValue();
            long got = TetreeLevelCache.getCachedIndex((int) t[0], (int) t[1], (int) t[2], (byte) t[3], (byte) t[4]);
            // Either this tuple still owns its slot (its own value) or it was evicted (-1). Never another's value.
            assertTrue(got == expected || got == -1L,
                       "slot collision served a wrong tuple's value: got " + got + " expected " + expected
                       + " or -1");
            if (got == expected) {
                hits++;
            }
        }
        // Sanity: with a 4096-slot table and 20k distinct tuples, some survive — proves reads aren't all misses
        // (which would make the no-wrong-value assertion vacuous).
        assertTrue(hits > 0, "expected some surviving cache entries, got none");
    }
}
