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

package com.hellblazer.luciferase.simulation.delos.fireflies;

import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.simulation.delos.GossipAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test for DelosGossipAdapter - Delos-aware gossip adapter.
 * <p>
 * These tests verify that the adapter provides:
 * 1. Topic-based broadcast using internal routing
 * 2. Topic-based subscription with message delivery
 * 3. Message serialization round-trip
 * <p>
 * NOTE: This adapter uses in-memory pub/sub as Fireflies doesn't expose
 * a simple application-level message passing API. Future versions may
 * integrate with Delos messaging protocols when available.
 *
 * @author hal.hildebrand
 */
class DelosGossipAdapterTest {

    private View                mockView;
    private Member              localMember;
    private DelosGossipAdapter  adapter1;
    private DelosGossipAdapter  adapter2;

    @BeforeEach
    void setUp() {
        mockView = mock(View.class);
        localMember = mock(Member.class);
        when(localMember.getId()).thenReturn(mock(com.hellblazer.delos.cryptography.Digest.class));
    }

    /**
     * Test 1: Verify broadcast() delivers to local subscribers
     */
    @Test
    void testBroadcast() throws Exception {
        // Given: An adapter with a subscriber on the same topic
        adapter1 = new DelosGossipAdapter(mockView, localMember);

        var topic = "test-topic";
        var receivedMessages = new ArrayList<GossipAdapter.Message>();
        var latch = new CountDownLatch(1);

        adapter1.subscribe(topic, msg -> {
            receivedMessages.add(msg);
            latch.countDown();
        });

        // When: We broadcast a message
        var senderId = UUID.randomUUID();
        var payload = "test payload".getBytes();
        var message = new GossipAdapter.Message(senderId, payload);
        adapter1.broadcast(topic, message);

        // Then: Subscriber should receive the message
        var notified = latch.await(1, TimeUnit.SECONDS);
        assertThat(notified)
            .as("Subscriber should receive broadcast message")
            .isTrue();

        assertThat(receivedMessages).hasSize(1);
        var received = receivedMessages.get(0);
        assertThat(received.senderId()).isEqualTo(senderId);
        assertThat(received.payload()).isEqualTo(payload);
    }

    /**
     * Test 2: Verify subscribe() receives messages on specific topics
     */
    @Test
    void testSubscribe() throws Exception {
        // Given: Two adapters connected to the same cluster
        adapter1 = new DelosGossipAdapter(mockView, localMember);
        adapter2 = new DelosGossipAdapter(mockView, localMember);

        // Connect them (simulate cluster)
        DelosGossipAdapter.ClusterGossip.create(java.util.List.of(adapter1, adapter2));

        var topic = "test-topic";
        var receivedMessages = new ArrayList<GossipAdapter.Message>();
        var latch = new CountDownLatch(1);

        adapter2.subscribe(topic, msg -> {
            receivedMessages.add(msg);
            latch.countDown();
        });

        // When: adapter1 broadcasts
        var senderId = UUID.randomUUID();
        var payload = "test message".getBytes();
        var message = new GossipAdapter.Message(senderId, payload);
        adapter1.broadcast(topic, message);

        // Then: adapter2's subscriber should receive the message
        var notified = latch.await(1, TimeUnit.SECONDS);
        assertThat(notified)
            .as("Subscriber should receive message from other adapter")
            .isTrue();

        assertThat(receivedMessages).hasSize(1);
        var received = receivedMessages.get(0);
        assertThat(received.senderId()).isEqualTo(senderId);
        assertThat(received.payload()).isEqualTo(payload);
    }

    /**
     * Test 3: Verify message serialization and deserialization round-trip
     */
    @Test
    void testMessageSerialization() {
        // Given: A message
        var senderId = UUID.randomUUID();
        var payload = "test data with special chars: \n\t\r".getBytes();
        var original = new GossipAdapter.Message(senderId, payload);

        // When: We serialize and deserialize
        var serialized = DelosGossipAdapter.serialize(original);
        var deserialized = DelosGossipAdapter.deserialize(serialized);

        // Then: Should get back the original message
        assertThat(deserialized.senderId())
            .as("Sender ID should match after round-trip")
            .isEqualTo(original.senderId());
        assertThat(deserialized.payload())
            .as("Payload should match after round-trip")
            .isEqualTo(original.payload());
    }
}
