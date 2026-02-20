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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deduplicating build dispatcher. At most one in-flight build per SpatialKey<?>.
 * Positions are fetched from facade AT BUILD TIME (not submission time).
 *
 * <p>When a build completes, the result is stored in a pending map. Callbacks are
 * delivered — with a staleness check — when {@link #awaitBuilds()} completes. This
 * deferred delivery ensures that any version bumps which occur after submission but
 * before {@code awaitBuilds()} are visible at check time, making staleness detection
 * reliable regardless of how quickly a build executes.
 *
 * <p>Delivery ordering guarantee: {@code pending.put} always precedes
 * {@code inFlight.remove} so that any call to {@code awaitBuilds()} made after
 * {@code inFlight} is cleared is guaranteed to see the result in {@code pending}.
 *
 * @author hal.hildebrand
 */
public final class BuildQueue {

    @FunctionalInterface
    public interface BuildCompleteCallback {
        void onComplete(SpatialKey<?> key, long buildVersion, byte[] data);
    }

    private final SpatialIndexFacade facade;
    private final DirtyTracker dirtyTracker;
    private final RegionBuilder builder;
    private final BuildCompleteCallback callback;
    private final ConcurrentHashMap<SpatialKey<?>, CompletableFuture<Void>> inFlight =
        new ConcurrentHashMap<>();
    /**
     * Holds completed-but-undelivered build results. Populated by the build
     * completion handler; consumed and cleared by {@link #awaitBuilds()}.
     */
    private final ConcurrentHashMap<SpatialKey<?>, RegionBuilder.BuiltKeyedRegion> pending =
        new ConcurrentHashMap<>();

    public BuildQueue(SpatialIndexFacade facade, DirtyTracker dirtyTracker,
                      RegionBuilder builder, BuildCompleteCallback callback) {
        this.facade = facade;
        this.dirtyTracker = dirtyTracker;
        this.builder = builder;
        this.callback = callback;
    }

    /**
     * Submit a build for key if not already in-flight.
     * No-op if a build for this key is already running.
     *
     * @param key      the spatial key to build
     * @param priority the priority for scheduling this build
     */
    public void submit(SpatialKey<?> key, RegionBuilder.KeyedBuildRequest.Priority priority) {
        inFlight.computeIfAbsent(key, k -> {
            var expectedVersion = dirtyTracker.version(k);
            return builder.buildKeyed(k, facade, expectedVersion)
                          .whenComplete((result, err) -> {
                              // Store result BEFORE removing from inFlight. This guarantees
                              // that any awaitBuilds() caller observing an empty inFlight
                              // will always find the result already in pending.
                              if (result != null) {
                                  pending.put(k, result);
                              }
                              inFlight.remove(k);
                          })
                          .thenApply(r -> (Void) null);
        });
    }

    /**
     * Returns a future that completes when all currently in-flight builds finish,
     * then delivers pending results to the callback after performing staleness checks.
     *
     * <p>The stale check runs AFTER all in-flight futures resolve, so any version bumps
     * made by the caller before invoking this method are visible to the check. A result
     * whose {@code buildVersion} no longer matches the current {@link DirtyTracker}
     * version is silently discarded.
     *
     * <p>Safe to call from tests: takes a snapshot of inFlight at call time.
     *
     * @return a future that completes once all in-flight builds are done and pending
     *         results have been delivered or discarded
     */
    public CompletableFuture<Void> awaitBuilds() {
        var futures = inFlight.values().toArray(new CompletableFuture[0]);
        return CompletableFuture.allOf(futures).thenRun(this::deliverPending);
    }

    /**
     * Process all pending build results: deliver fresh ones, discard stale ones.
     * A result is stale if the DirtyTracker version for its key no longer matches
     * the version that was current when the build was dispatched.
     */
    private void deliverPending() {
        pending.forEach((k, result) -> {
            // Conditional remove ensures each result is delivered at most once,
            // even if deliverPending() is called concurrently.
            if (pending.remove(k, result)) {
                if (dirtyTracker.version(k) == result.buildVersion()) {
                    callback.onComplete(k, result.buildVersion(), result.serializedData());
                }
            }
        });
    }
}
