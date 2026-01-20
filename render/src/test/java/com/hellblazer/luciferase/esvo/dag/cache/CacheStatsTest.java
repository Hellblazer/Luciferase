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
package com.hellblazer.luciferase.esvo.dag.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for CacheStats record.
 *
 * @author hal.hildebrand
 */
class CacheStatsTest {

    @Test
    void testRecordCreation() {
        var stats = new CacheStats(100, 50, 10, 1.5f);

        assertEquals(100, stats.hitCount());
        assertEquals(50, stats.missCount());
        assertEquals(10, stats.evictionCount());
        assertEquals(1.5f, stats.averageAccessTimeMs(), 0.001f);
    }

    @Test
    void testHitRate() {
        var stats = new CacheStats(100, 50, 0, 0);

        assertEquals(100.0f / 150.0f, stats.hitRate(), 0.001f);
    }

    @Test
    void testHitRate_NoAccesses() {
        var stats = new CacheStats(0, 0, 0, 0);

        assertEquals(0.0f, stats.hitRate(), 0.001f);
    }

    @Test
    void testHitRate_OnlyHits() {
        var stats = new CacheStats(100, 0, 0, 0);

        assertEquals(1.0f, stats.hitRate(), 0.001f);
    }

    @Test
    void testHitRate_OnlyMisses() {
        var stats = new CacheStats(0, 100, 0, 0);

        assertEquals(0.0f, stats.hitRate(), 0.001f);
    }
}
