/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import com.hellblazer.luciferase.simulation.von.VonBubble;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for BubbleReference implementations.
 *
 * @author hal.hildebrand
 */
class SimpleBubbleReferenceTest {

    private LocalServerTransport.Registry registry;
    private VonBubble bubble;

    @BeforeEach
    void setUp() {
        registry = LocalServerTransport.Registry.create();
        var transport = registry.register(UUID.randomUUID());
        bubble = new VonBubble(UUID.randomUUID(), (byte) 10, 100L, transport);
    }

    @AfterEach
    void tearDown() {
        if (bubble != null) {
            bubble.close();
        }
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void testLocalBubbleReferenceIsLocal() {
        var ref = new LocalBubbleReference(bubble);
        assertTrue(ref.isLocal());
    }

    @Test
    void testLocalBubbleReferenceAsLocal() {
        var ref = new LocalBubbleReference(bubble);
        assertSame(ref, ref.asLocal());
    }

    @Test
    void testLocalBubbleReferenceAsRemoteThrows() {
        var ref = new LocalBubbleReference(bubble);
        assertThrows(IllegalStateException.class, ref::asRemote);
    }

    @Test
    void testLocalBubbleReferenceDelegation() {
        var ref = new LocalBubbleReference(bubble);
        assertEquals(bubble.id(), ref.getBubbleId());
        assertNotNull(ref.getPosition());
        assertNotNull(ref.getNeighbors());
    }

    @Test
    void testRemoteBubbleProxyIsLocal() {
        var transport = registry.register(UUID.randomUUID());
        var proxy = new RemoteBubbleProxy(UUID.randomUUID(), transport);
        assertFalse(proxy.isLocal());
    }

    @Test
    void testRemoteBubbleProxyAsRemote() {
        var transport = registry.register(UUID.randomUUID());
        var proxy = new RemoteBubbleProxy(UUID.randomUUID(), transport);
        assertSame(proxy, proxy.asRemote());
    }

    @Test
    void testRemoteBubbleProxyAsLocalThrows() {
        var transport = registry.register(UUID.randomUUID());
        var proxy = new RemoteBubbleProxy(UUID.randomUUID(), transport);
        assertThrows(IllegalStateException.class, proxy::asLocal);
    }

    @Test
    void testLocalBubbleReferenceNullBubbleThrows() {
        assertThrows(NullPointerException.class, () -> new LocalBubbleReference(null));
    }

    @Test
    void testRemoteBubbleProxyNullIdThrows() {
        var transport = registry.register(UUID.randomUUID());
        assertThrows(NullPointerException.class, () -> new RemoteBubbleProxy(null, transport));
    }

    @Test
    void testRemoteBubbleProxyNullTransportThrows() {
        assertThrows(NullPointerException.class, () -> new RemoteBubbleProxy(UUID.randomUUID(), null));
    }
}
