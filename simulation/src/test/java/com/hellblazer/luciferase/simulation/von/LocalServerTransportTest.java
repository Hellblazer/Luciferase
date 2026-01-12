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

package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for LocalServerTransport P2P messaging.
 *
 * @author hal.hildebrand
 */
public class LocalServerTransportTest {

    private LocalServerTransport.Registry registry;
    private final VonMessageFactory factory = VonMessageFactory.system();

    @BeforeEach
    void setUp() {
        registry = LocalServerTransport.Registry.create();
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void testRegistry_registerAndGet() {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();

        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);

        assertThat(registry.size()).isEqualTo(2);
        assertThat(transport1.getLocalId()).isEqualTo(id1);
        assertThat(transport2.getLocalId()).isEqualTo(id2);
    }

    @Test
    void testSendToNeighbor_deliversMessage() throws Exception {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();

        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);

        var latch = new CountDownLatch(1);
        var received = new AtomicReference<VonMessage>();

        transport2.onMessage(msg -> {
            received.set(msg);
            latch.countDown();
        });

        var message = factory.createLeave(id1);
        transport1.sendToNeighbor(id2, message);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).isInstanceOf(VonMessage.Leave.class);
        assertThat(((VonMessage.Leave) received.get()).nodeId()).isEqualTo(id1);
    }

    @Test
    void testSendToNeighborAsync_completesWithAck() throws Exception {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();

        var transport1 = registry.register(id1);
        registry.register(id2);

        var message = factory.createLeave(id1);
        var future = transport1.sendToNeighborAsync(id2, message);

        var ack = future.get(1, TimeUnit.SECONDS);
        assertThat(ack).isNotNull();
        assertThat(ack.senderId()).isEqualTo(id2);
    }

    @Test
    void testSendToUnknownNeighbor_throwsException() {
        var id1 = UUID.randomUUID();
        var unknownId = UUID.randomUUID();

        var transport1 = registry.register(id1);

        var message = factory.createLeave(id1);
        assertThatThrownBy(() -> transport1.sendToNeighbor(unknownId, message))
            .isInstanceOf(VonTransport.TransportException.class)
            .hasMessageContaining("Unknown neighbor");
    }

    @Test
    void testLookupMember_findsRegisteredMember() {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();

        var transport1 = registry.register(id1);
        registry.register(id2);

        var memberInfo = transport1.lookupMember(id2);

        assertThat(memberInfo).isPresent();
        assertThat(memberInfo.get().nodeId()).isEqualTo(id2);
    }

    @Test
    void testLookupMember_returnsEmptyForUnknown() {
        var id1 = UUID.randomUUID();
        var unknownId = UUID.randomUUID();

        var transport1 = registry.register(id1);

        var memberInfo = transport1.lookupMember(unknownId);

        assertThat(memberInfo).isEmpty();
    }

    @Test
    void testRouteToKey_deterministicRouting() {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var id3 = UUID.randomUUID();

        var transport1 = registry.register(id1);
        registry.register(id2);
        registry.register(id3);

        // Create bounds and get the key
        var bounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(0.5f, 0.5f, 0.5f)));
        var key = bounds.rootKey();

        // Route should be deterministic
        var result1 = transport1.routeToKey(key);
        var result2 = transport1.routeToKey(key);

        assertThat(result1.nodeId()).isEqualTo(result2.nodeId());
    }

    @Test
    void testClose_disconnectsTransport() {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();

        var transport1 = registry.register(id1);
        registry.register(id2);

        assertThat(transport1.isConnected()).isTrue();

        transport1.close();

        assertThat(transport1.isConnected()).isFalse();
        assertThatThrownBy(() -> transport1.sendToNeighbor(id2, factory.createLeave(id1)))
            .isInstanceOf(VonTransport.TransportException.class)
            .hasMessageContaining("closed");
    }

    @Test
    void testMultipleHandlers_allReceiveMessage() throws Exception {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();

        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);

        var latch = new CountDownLatch(2);
        var count = new java.util.concurrent.atomic.AtomicInteger(0);

        transport2.onMessage(msg -> {
            count.incrementAndGet();
            latch.countDown();
        });
        transport2.onMessage(msg -> {
            count.incrementAndGet();
            latch.countDown();
        });

        var message = factory.createLeave(id1);
        transport1.sendToNeighbor(id2, message);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(count.get()).isEqualTo(2);
    }

    @Test
    void testRemoveMessageHandler_stopsReceiving() throws Exception {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();

        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);

        var count = new java.util.concurrent.atomic.AtomicInteger(0);
        var handler = (java.util.function.Consumer<VonMessage>) msg -> count.incrementAndGet();

        transport2.onMessage(handler);

        // First message should be received
        transport1.sendToNeighbor(id2, factory.createLeave(id1));
        Thread.sleep(100);
        assertThat(count.get()).isEqualTo(1);

        // Remove handler
        transport2.removeMessageHandler(handler);

        // Second message should not be received
        transport1.sendToNeighbor(id2, factory.createLeave(id1));
        Thread.sleep(100);
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void testJoinRequest_deliveredCorrectly() throws Exception {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();

        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);

        var latch = new CountDownLatch(1);
        var received = new AtomicReference<VonMessage.JoinRequest>();

        transport2.onMessage(msg -> {
            if (msg instanceof VonMessage.JoinRequest jr) {
                received.set(jr);
                latch.countDown();
            }
        });

        var position = new Point3D(1.0, 2.0, 3.0);
        var bounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(1.0f, 2.0f, 3.0f)));
        var request = factory.createJoinRequest(id1, position, bounds);

        transport1.sendToNeighbor(id2, request);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().joinerId()).isEqualTo(id1);
        assertThat(received.get().position()).isEqualTo(position);
    }
}
