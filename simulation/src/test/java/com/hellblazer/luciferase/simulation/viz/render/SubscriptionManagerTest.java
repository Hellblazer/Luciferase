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

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.simulation.viz.render.protocol.ServerMessage;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionManagerTest {

    @Test
    void subscribeRegistersClient() {
        var manager = new SubscriptionManager();
        var transport = new InProcessTransport();
        manager.subscribe("sess-1", transport.serverTransport(),
                          Map.of(), TestFrustums.fullScene(), 5);
        assertEquals(1, manager.activeClientCount());
    }

    @Test
    void pushDeliversMsgToSubscriber() throws InterruptedException {
        var manager = new SubscriptionManager();
        var transport = new InProcessTransport();
        var client = transport.clientView();
        manager.subscribe("sess-1", transport.serverTransport(),
                          Map.of(), TestFrustums.fullScene(), 5);

        var key = new MortonKey(42L, (byte) 5);
        manager.push("sess-1", key, 1L);

        var msg = client.nextServerMessage(200, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.RegionUpdate.class, msg);
        assertEquals(key, ((ServerMessage.RegionUpdate) msg).key());
    }

    @Test
    void unsubscribeRemovesClient() {
        var manager = new SubscriptionManager();
        var transport = new InProcessTransport();
        manager.subscribe("sess-1", transport.serverTransport(),
                          Map.of(), TestFrustums.fullScene(), 5);
        manager.unsubscribe("sess-1");
        assertEquals(0, manager.activeClientCount());
    }

    @Test
    void knownVersionTrackedPerClient() {
        var manager = new SubscriptionManager();
        var transport = new InProcessTransport();
        var key = new MortonKey(7L, (byte) 5);
        manager.subscribe("sess-1", transport.serverTransport(),
                          Map.of(keyString(key), 3L), TestFrustums.fullScene(), 5);
        assertEquals(3L, manager.knownVersion("sess-1", key));
    }

    private static String keyString(MortonKey k) {
        return "oct:" + k.getLevel() + ":" + Base64.getEncoder()
                                                    .encodeToString(ByteBuffer.allocate(8).putLong(k.getMortonCode()).array());
    }
}
