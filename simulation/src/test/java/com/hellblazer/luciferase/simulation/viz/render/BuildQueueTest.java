/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
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

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BuildQueueTest {

    @Test
    void duplicateSubmissionsDeduped() throws Exception {
        var facade = new OctreeSpatialIndexFacade(4, 8);
        facade.put(1L, new Point3f(100, 100, 100));
        var keys = facade.keysContaining(new Point3f(100, 100, 100), 4, 4);
        assertFalse(keys.isEmpty(), "expected at least one key containing (100,100,100)");
        var key = keys.iterator().next();

        var tracker = new DirtyTracker();
        tracker.bump(key);

        var buildCount = new AtomicInteger(0);
        try (var builder = new RegionBuilder(1, 10, 8, 64)) {
            var queue = new BuildQueue(facade, tracker, builder,
                (k, v, data) -> buildCount.incrementAndGet());

            // Submit twice — should only build once
            queue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
            queue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);

            queue.awaitBuilds().get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, buildCount.get(), "duplicate submissions must be deduplicated");
    }

    @Test
    void staleVersionDiscarded() throws Exception {
        var facade = new OctreeSpatialIndexFacade(4, 8);
        facade.put(1L, new Point3f(200, 200, 200));
        var keys = facade.keysContaining(new Point3f(200, 200, 200), 4, 4);
        assertFalse(keys.isEmpty(), "expected at least one key containing (200,200,200)");
        var key = keys.iterator().next();

        var tracker = new DirtyTracker();
        tracker.bump(key);

        var completedCount = new AtomicInteger(0);
        try (var builder = new RegionBuilder(1, 10, 8, 64)) {
            var queue = new BuildQueue(facade, tracker, builder,
                (k, v, data) -> completedCount.incrementAndGet());

            queue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
            Thread.sleep(10);
            tracker.bump(key);  // version changes before deliverPending — build is stale

            queue.awaitBuilds().get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, completedCount.get(), "stale build must be discarded");
    }
}
