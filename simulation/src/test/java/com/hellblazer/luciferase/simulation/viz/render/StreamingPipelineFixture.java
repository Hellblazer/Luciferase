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

import com.hellblazer.luciferase.lucien.SpatialKey;

import java.util.concurrent.TimeUnit;

/**
 * Wires together SpatialIndexFacade + DirtyTracker + BuildQueue + StreamingCache
 * for deterministic pipeline testing. Call {@link #submitBuild(SpatialKey)} to
 * enqueue a region build and {@link #awaitBuilds()} to drain all in-flight work.
 *
 * @author hal.hildebrand
 */
public final class StreamingPipelineFixture {

    public final SpatialIndexFacade world;
    public final DirtyTracker dirtyTracker;
    public final StreamingCache cache;
    public final BuildQueue buildQueue;

    public StreamingPipelineFixture(SpatialIndexFacade world) {
        this.world = world;
        this.dirtyTracker = new DirtyTracker();
        this.cache = new StreamingCache();
        var builder = new RegionBuilder(2, 20, 8, 64);
        this.buildQueue = new BuildQueue(world, dirtyTracker, builder,
                                         (key, version, data) -> cache.put(key, version, data));
    }

    /** Mark the key dirty and submit a visible-priority build for it. */
    public void submitBuild(SpatialKey<?> key) {
        dirtyTracker.bump(key);
        buildQueue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
    }

    /** Wait (up to 10 seconds) for all in-flight builds to complete. */
    public void awaitBuilds() {
        try {
            buildQueue.awaitBuilds().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Build timed out", e);
        }
    }
}
