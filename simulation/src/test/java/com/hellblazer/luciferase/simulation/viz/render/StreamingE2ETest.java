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

import com.hellblazer.luciferase.simulation.viz.render.protocol.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: entity inserted → key dirtied → cache pre-warmed → snapshot requested →
 * manifest received → binary frame received → subscribe → region update pushed.
 *
 * <p>Note on binary frame delivery: {@code handleSnapshotRequest} sends binary frames
 * only for keys that are already in the {@link StreamingCache}. The cache must therefore
 * be pre-warmed (build submitted and awaited) BEFORE the snapshot request is sent.
 * This is the same order that the server-side request loop would follow in production:
 * the streaming cycle continuously builds regions into cache, and the snapshot handler
 * delivers frames from that warm cache.
 */
class StreamingE2ETest {

    private RegionBuilder builder;

    @AfterEach
    void closeBuilder() {
        if (builder != null) {
            builder.close();
        }
    }

    @Test
    void phaseCAndBFullFlow() throws Exception {
        // Setup world
        var facade = WorldFixture.octree(4, 8)
            .withEntity(1L, 500, 500, 500)
            .build();
        var transport = new InProcessTransport();
        var client = transport.clientView();
        var dirtyTracker = new DirtyTracker();

        // Identify the key containing our entity
        var keys = facade.keysContaining(new Point3f(500, 500, 500), 4, 4);
        assertFalse(keys.isEmpty(), "entity must be indexed in at least one cell");
        var key = keys.iterator().next();
        dirtyTracker.bump(key);

        var cache = new StreamingCache();
        builder = new RegionBuilder(1, 10, 8, 64);
        var buildQueue = new BuildQueue(facade, dirtyTracker, builder,
            (k, v, d) -> cache.put(k, v, d));
        var subscriptions = new SubscriptionManager();
        var session = new StreamingSession(
            "e2e-sess", transport.serverTransport(),
            facade, dirtyTracker, cache, buildQueue, subscriptions);

        // Pre-warm cache before snapshot request.
        // handleSnapshotRequest sends binary frames synchronously for warm keys;
        // without pre-warming the cache would be empty and no binary frame would arrive.
        buildQueue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
        buildQueue.awaitBuilds().get(10, TimeUnit.SECONDS);

        // ── Phase C ──
        client.sendToServer(new ClientMessage.Hello("1.0"));
        session.processNext(200, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.HelloAck.class,
            client.nextServerMessage(200, TimeUnit.MILLISECONDS));

        client.sendToServer(new ClientMessage.SnapshotRequest("req-e2e", 4));
        session.processNext(500, TimeUnit.MILLISECONDS);
        var manifestMsg = client.nextServerMessage(500, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.SnapshotManifest.class, manifestMsg);

        // Binary frame is sent inline (cache was pre-warmed above)
        var frame = client.nextBinaryFrame(500, TimeUnit.MILLISECONDS);
        assertNotNull(frame, "binary frame must be delivered after snapshot (cache pre-warmed)");

        // ── Phase B ──
        var manifest = (ServerMessage.SnapshotManifest) manifestMsg;
        var knownVersions = new HashMap<String, Long>();
        for (var entry : manifest.regions()) {
            knownVersions.put(SubscriptionManager.keyString(entry.key()), entry.snapshotVersion());
        }
        client.sendToServer(new ClientMessage.Subscribe(manifest.snapshotToken(), knownVersions));
        session.processNext(200, TimeUnit.MILLISECONDS);

        // Move entity to make the cell dirty (new version > known version)
        facade.move(1L, new Point3f(501, 501, 501));
        var movedKeys = facade.keysContaining(new Point3f(501, 501, 501), 4, 4);
        dirtyTracker.bumpAll(movedKeys);

        // Submit and await the build so cache reflects the new version.
        // StreamingCycle only pushes RegionUpdate when cache.version() == currentVersion;
        // without awaiting the build, the cycle would submit a build but not push.
        for (var k : movedKeys) {
            buildQueue.submit(k, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
        }
        buildQueue.awaitBuilds().get(5, TimeUnit.SECONDS);

        // Run streaming cycle — cache is now warm at new version, RegionUpdate must fire
        var cycle = new StreamingCycle(facade, dirtyTracker, cache, buildQueue, subscriptions);
        subscriptions.updateViewport("e2e-sess", TestFrustums.fullScene(), 4);
        cycle.tick(200_000_000L);

        var updateMsg = client.nextServerMessage(500, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.RegionUpdate.class, updateMsg,
            "moved entity should trigger REGION_UPDATE via streaming cycle");
    }
}
