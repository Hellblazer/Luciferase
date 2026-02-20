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
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class StreamingSessionPhaseCTest {

    @Test
    void helloAckSentOnHello() throws InterruptedException {
        var fixture = makeFixture();
        fixture.client.sendToServer(new ClientMessage.Hello("1.0"));
        fixture.session.processNext(200, TimeUnit.MILLISECONDS);
        var ack = fixture.client.nextServerMessage(200, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.HelloAck.class, ack);
    }

    @Test
    void snapshotManifestSentForOccupiedKeys() throws InterruptedException {
        var facade = WorldFixture.octree(4, 8)
            .withEntity(1L, 100, 100, 100)
            .withEntity(2L, 5000, 5000, 5000)
            .build();
        var fixture = makeFixtureWithWorld(facade);

        fixture.client.sendToServer(new ClientMessage.Hello("1.0"));
        fixture.session.processNext(200, TimeUnit.MILLISECONDS);
        fixture.client.nextServerMessage(200, TimeUnit.MILLISECONDS); // discard HelloAck

        fixture.client.sendToServer(new ClientMessage.SnapshotRequest("req-1", 4));
        fixture.session.processNext(200, TimeUnit.MILLISECONDS);

        var manifest = fixture.client.nextServerMessage(500, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.SnapshotManifest.class, manifest);
        var m = (ServerMessage.SnapshotManifest) manifest;
        assertEquals("req-1", m.requestId());
        assertFalse(m.regions().isEmpty(), "manifest must contain occupied regions");
    }

    // --- helpers ---
    record Fixture(StreamingSession session, InProcessTransport.ClientView client) {}

    static Fixture makeFixture() {
        var facade = WorldFixture.octree(4, 8).build();
        return makeFixtureWithWorld(facade);
    }

    static Fixture makeFixtureWithWorld(SpatialIndexFacade facade) {
        var transport = new InProcessTransport();
        var tracker = new DirtyTracker();
        var cache = new StreamingCache();
        var builder = new RegionBuilder(1, 10, 8, 64);
        var buildQueue = new BuildQueue(facade, tracker, builder,
            (k, v, d) -> cache.put(k, v, d));
        var subscriptions = new SubscriptionManager();
        var session = new StreamingSession(
            "sess-test", transport.serverTransport(),
            facade, tracker, cache, buildQueue, subscriptions);
        return new Fixture(session, transport.clientView());
    }
}
