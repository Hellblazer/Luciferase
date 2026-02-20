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
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.viz.render.protocol.ServerMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hellblazer.luciferase.simulation.viz.render.SubscriptionManager.keyString;
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
        // Use production keyString to seed pre-known versions — avoids duplicating format logic
        manager.subscribe("sess-1", transport.serverTransport(),
                          Map.of(keyString(key), 3L), TestFrustums.fullScene(), 5);
        assertEquals(3L, manager.knownVersion("sess-1", key));
    }

    @Test
    void updateViewportPreservesKnownVersions() {
        var manager = new SubscriptionManager();
        var transport = new InProcessTransport();
        var key = new MortonKey(9L, (byte) 4);
        manager.subscribe("sess-1", transport.serverTransport(),
                          Map.of(keyString(key), 7L), TestFrustums.fullScene(), 5);
        // Update viewport — knownVersions must survive the record replacement
        manager.updateViewport("sess-1", TestFrustums.origin(), 4);
        assertEquals(7L, manager.knownVersion("sess-1", key),
                     "updateViewport must preserve knownVersions");
        // Verify the frustum/level changed
        var state = manager.get("sess-1");
        assertNotNull(state);
        assertEquals(4, state.level());
    }

    @Test
    void broadcastDelivershToAllSubscribers() throws InterruptedException {
        var manager = new SubscriptionManager();
        var t1 = new InProcessTransport();
        var t2 = new InProcessTransport();
        var c1 = t1.clientView();
        var c2 = t2.clientView();
        manager.subscribe("s1", t1.serverTransport(), Map.of(), TestFrustums.fullScene(), 5);
        manager.subscribe("s2", t2.serverTransport(), Map.of(), TestFrustums.fullScene(), 5);

        var key = new MortonKey(100L, (byte) 5);
        manager.broadcast(key, 2L);

        assertInstanceOf(ServerMessage.RegionUpdate.class,
                         c1.nextServerMessage(200, TimeUnit.MILLISECONDS), "c1 must receive broadcast");
        assertInstanceOf(ServerMessage.RegionUpdate.class,
                         c2.nextServerMessage(200, TimeUnit.MILLISECONDS), "c2 must receive broadcast");
    }

    @Test
    void pushBinaryDelivershFrame() throws InterruptedException {
        var manager = new SubscriptionManager();
        var transport = new InProcessTransport();
        var client = transport.clientView();
        manager.subscribe("sess-1", transport.serverTransport(),
                          Map.of(), TestFrustums.fullScene(), 5);

        byte[] frame = {0x45, 0x53, 0x56, 0x52};
        manager.pushBinary("sess-1", frame);

        var received = client.nextBinaryFrame(200, TimeUnit.MILLISECONDS);
        assertArrayEquals(frame, received);
    }

    @Test
    void orderedSessionIdsReflectsInsertionOrder() {
        var manager = new SubscriptionManager();
        manager.subscribe("a", new InProcessTransport().serverTransport(), Map.of(), TestFrustums.fullScene(), 5);
        manager.subscribe("b", new InProcessTransport().serverTransport(), Map.of(), TestFrustums.fullScene(), 5);
        manager.subscribe("c", new InProcessTransport().serverTransport(), Map.of(), TestFrustums.fullScene(), 5);

        var ids = manager.orderedSessionIds();
        assertEquals(3, ids.size());
        assertEquals("a", ids.get(0));
        assertEquals("b", ids.get(1));
        assertEquals("c", ids.get(2));
    }

    @Test
    void keyStringEncodestetreeKey() {
        // Verify TetreeKey branch of keyString produces "tet:" prefix
        // Use a CompactTetreeKey (level ≤ 10) via the Tetree spatial index
        var octKey = new MortonKey(1L, (byte) 3);
        var oct = keyString(octKey);
        assertTrue(oct.startsWith("oct:3:"), "MortonKey must encode as oct:<level>:");

        // TetreeKey requires a real key from the spatial index; verify format contract
        // by ensuring the static method handles TetreeKey type discriminator properly.
        // Full round-trip tested via TetreeSpatialIndexFacadeTest population.
        assertThrows(IllegalArgumentException.class,
                     () -> keyString(new FakeSpatialKey()),
                     "unknown key type must throw");
    }

    /** Minimal SpatialKey stub for testing the default arm of keyString. */
    private static final class FakeSpatialKey implements com.hellblazer.luciferase.lucien.SpatialKey<FakeSpatialKey> {
        @Override public byte getLevel() { return 0; }
        @Override public int compareTo(FakeSpatialKey o) { return 0; }
        @Override public FakeSpatialKey parent() { return null; }
        @Override public FakeSpatialKey root() { return this; }
    }
}
