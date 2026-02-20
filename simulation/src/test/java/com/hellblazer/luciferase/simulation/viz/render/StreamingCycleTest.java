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

import com.hellblazer.luciferase.simulation.viz.render.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class StreamingCycleTest {

    @Test
    void dirtyKeyDeliveredToSubscriber() throws Exception {
        var facade = WorldFixture.octree(4, 8)
            .withEntity(1L, 100, 100, 100).build();
        var transport = new InProcessTransport();
        var client = transport.clientView();
        var tracker = new DirtyTracker();
        var cache = new StreamingCache();
        var builder = new RegionBuilder(1, 10, 8, 64);
        var buildQueue = new BuildQueue(facade, tracker, builder,
            (k, v, d) -> cache.put(k, v, d));
        var subscriptions = new SubscriptionManager();

        // Subscribe with frustum that contains the entity
        subscriptions.subscribe("s1", transport.serverTransport(),
            java.util.Map.of(), TestFrustums.fullScene(), 4);

        // Make a key dirty
        var keys = facade.keysContaining(new Point3f(100, 100, 100), 4, 4);
        var key = keys.iterator().next();
        tracker.bump(key);

        // Wait for build, then run one streaming cycle
        buildQueue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
        buildQueue.awaitBuilds().get(5, TimeUnit.SECONDS);

        var cycle = new StreamingCycle(facade, tracker, cache, buildQueue, subscriptions);
        cycle.tick(100_000_000L); // 100ms budget

        var msg = client.nextServerMessage(500, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.RegionUpdate.class, msg,
            "dirty key in frustum must be pushed after tick");
    }
}
