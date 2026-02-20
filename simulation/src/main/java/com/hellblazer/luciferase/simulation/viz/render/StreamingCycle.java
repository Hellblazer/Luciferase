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
import com.hellblazer.luciferase.simulation.viz.render.protocol.BinaryFrameCodec;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Time-budgeted streaming cycle with fair-rotation across subscribers.
 *
 * <p>On each {@link #tick}, iterates over active subscriptions in round-robin order.
 * For each subscriber, visible keys whose cached version exceeds the client's known
 * version are pushed immediately if data is in cache; otherwise a background build
 * is submitted to the BuildQueue.
 *
 * <p>The cursor advances across calls so no single client starves others when the
 * deadline expires mid-iteration.
 *
 * @author hal.hildebrand
 */
public final class StreamingCycle {

    private final SpatialIndexFacade facade;
    private final DirtyTracker dirtyTracker;
    private final StreamingCache cache;
    private final BuildQueue buildQueue;
    private final SubscriptionManager subscriptions;
    private final AtomicInteger cursor = new AtomicInteger(0);

    public StreamingCycle(SpatialIndexFacade facade, DirtyTracker dirtyTracker,
                          StreamingCache cache, BuildQueue buildQueue,
                          SubscriptionManager subscriptions) {
        this.facade        = facade;
        this.dirtyTracker  = dirtyTracker;
        this.cache         = cache;
        this.buildQueue    = buildQueue;
        this.subscriptions = subscriptions;
    }

    public void tick(long deadlineNanos) {
        var start = System.nanoTime();
        var sessionIds = subscriptions.orderedSessionIds();
        if (sessionIds.isEmpty()) return;

        var n = sessionIds.size();
        var start_idx = cursor.get() % n;

        for (int i = 0; i < n; i++) {
            if (System.nanoTime() - start > deadlineNanos) break;
            var idx = (start_idx + i) % n;
            processClient(sessionIds.get(idx), start, deadlineNanos);
            // Advance cursor past the processed index so the next tick starts
            // at the following client — prevents double-service when deadline fires.
            cursor.set((idx + 1) % n);
        }
    }

    private void processClient(String sessionId, long cycleStart, long deadlineNanos) {
        var state = subscriptions.get(sessionId);
        if (state == null || state.frustum() == null) return;

        var visible = facade.keysVisible(state.frustum(), state.level());
        for (var key : visible) {
            if (System.nanoTime() - cycleStart > deadlineNanos) break;
            var knownVersion = subscriptions.knownVersion(sessionId, key);
            var currentVersion = dirtyTracker.version(key);
            if (currentVersion <= knownVersion) continue;

            var cached = cache.get(key);
            if (cached != null && cached.version() == currentVersion) {
                subscriptions.push(sessionId, key, currentVersion);
                var frame = BinaryFrameCodec.encodeWithKey(
                    key, regionType(key), currentVersion, cached.data());
                subscriptions.pushBinary(sessionId, frame.array());
            } else if (!buildQueue.isInFlight(key)) {
                buildQueue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
            }
        }
    }

    private static RegionBuilder.BuildType regionType(SpatialKey<?> key) {
        return key instanceof com.hellblazer.luciferase.lucien.tetree.TetreeKey ?
               RegionBuilder.BuildType.ESVT : RegionBuilder.BuildType.ESVO;
    }
}
