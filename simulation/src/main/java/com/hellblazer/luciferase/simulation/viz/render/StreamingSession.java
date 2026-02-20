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
import com.hellblazer.luciferase.simulation.viz.render.protocol.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-connection session handler for the streaming viz-render protocol.
 *
 * <p>Reads client messages from the transport and dispatches them to the appropriate
 * protocol phase handlers. Phase C covers handshake (Hello/HelloAck) and snapshot
 * manifest delivery (SnapshotRequest/SnapshotManifest). Phase B handlers for push
 * subscription are stubbed here and wired fully in Task 9.2 (StreamingCycle).
 *
 * @author hal.hildebrand
 */
public final class StreamingSession {

    private final String sessionId;
    private final Transport transport;
    private final SpatialIndexFacade facade;
    private final DirtyTracker dirtyTracker;
    private final StreamingCache cache;
    private final BuildQueue buildQueue;
    private final SubscriptionManager subscriptions;

    private static final AtomicLong TOKEN_COUNTER = new AtomicLong(0);

    public StreamingSession(String sessionId, Transport transport,
                            SpatialIndexFacade facade, DirtyTracker dirtyTracker,
                            StreamingCache cache, BuildQueue buildQueue,
                            SubscriptionManager subscriptions) {
        this.sessionId     = sessionId;
        this.transport     = transport;
        this.facade        = facade;
        this.dirtyTracker  = dirtyTracker;
        this.cache         = cache;
        this.buildQueue    = buildQueue;
        this.subscriptions = subscriptions;
    }

    public boolean processNext(long timeout, TimeUnit unit) throws InterruptedException {
        var msg = transport.nextClientMessage(timeout, unit);
        if (msg == null) return false;
        dispatch(msg);
        return true;
    }

    private void dispatch(ClientMessage msg) {
        switch (msg) {
            case ClientMessage.Hello h           -> handleHello(h);
            case ClientMessage.SnapshotRequest r -> handleSnapshotRequest(r);
            case ClientMessage.Subscribe s       -> handleSubscribe(s);
            case ClientMessage.ViewportUpdate v  -> handleViewportUpdate(v);
            case ClientMessage.Unsubscribe u     -> handleUnsubscribe();
        }
    }

    // Phase C
    private void handleHello(ClientMessage.Hello h) {
        transport.send(new ServerMessage.HelloAck(sessionId));
    }

    private void handleSnapshotRequest(ClientMessage.SnapshotRequest req) {
        var token = TOKEN_COUNTER.incrementAndGet();
        var occupied = facade.allOccupiedKeys(req.level());

        var entries = occupied.stream()
            .map(k -> new ServerMessage.SnapshotManifest.RegionEntry(
                k, dirtyTracker.version(k), estimateSize(k)))
            .toList();

        transport.send(new ServerMessage.SnapshotManifest(req.requestId(), token, entries));

        for (var entry : entries) {
            var cached = cache.get(entry.key());
            if (cached != null) {
                var frame = BinaryFrameCodec.encodeWithKey(
                    entry.key(), regionType(entry.key()), cached.version(), cached.data());
                transport.sendBinary(frame.array());
            } else {
                buildQueue.submit(entry.key(), RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
            }
        }
    }

    // Phase B stubs (subscription wired in Task 9.2)
    private void handleSubscribe(ClientMessage.Subscribe s) {
        subscriptions.subscribe(sessionId, transport, s.knownVersions(),
            null /* frustum set on next viewport update */, 0);
    }

    private void handleViewportUpdate(ClientMessage.ViewportUpdate v) {
        subscriptions.updateViewport(sessionId, v.frustum(), v.level());
    }

    private void handleUnsubscribe() {
        subscriptions.unsubscribe(sessionId);
    }

    private static long estimateSize(SpatialKey<?> key) { return 0L; }

    private static RegionBuilder.BuildType regionType(SpatialKey<?> key) {
        return key instanceof com.hellblazer.luciferase.lucien.tetree.TetreeKey ?
               RegionBuilder.BuildType.ESVT : RegionBuilder.BuildType.ESVO;
    }
}
