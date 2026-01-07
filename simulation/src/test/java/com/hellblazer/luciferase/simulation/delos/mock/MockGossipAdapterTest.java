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

package com.hellblazer.luciferase.simulation.delos.mock;

import com.hellblazer.luciferase.simulation.delos.GossipAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test-driven development for MockGossipAdapter.
 * <p>
 * Tests verify that the mock provides in-memory pub/sub messaging
 * for testing cluster coordination without real Fireflies gossip.
 *
 * @author hal.hildebrand
 */
class MockGossipAdapterTest {

    private MockGossipAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MockGossipAdapter();
    }

    @Test
    void testBroadcastReachesSubscribers() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var received = new ArrayList<GossipAdapter.Message>();

        adapter.subscribe("test-topic", message -> {
            received.add(message);
            latch.countDown();
        });

        var senderId = UUID.randomUUID();
        var payload = "test-payload".getBytes();
        var message = new GossipAdapter.Message(senderId, payload);

        adapter.broadcast("test-topic", message);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).senderId()).isEqualTo(senderId);
        assertThat(received.get(0).payload()).isEqualTo(payload);
    }

    @Test
    void testMultipleSubscribers() throws InterruptedException {
        var latch = new CountDownLatch(3);
        var received1 = new ArrayList<GossipAdapter.Message>();
        var received2 = new ArrayList<GossipAdapter.Message>();
        var received3 = new ArrayList<GossipAdapter.Message>();

        adapter.subscribe("multi-topic", message -> {
            received1.add(message);
            latch.countDown();
        });
        adapter.subscribe("multi-topic", message -> {
            received2.add(message);
            latch.countDown();
        });
        adapter.subscribe("multi-topic", message -> {
            received3.add(message);
            latch.countDown();
        });

        var senderId = UUID.randomUUID();
        var message = new GossipAdapter.Message(senderId, "multi-test".getBytes());

        adapter.broadcast("multi-topic", message);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received1).hasSize(1);
        assertThat(received2).hasSize(1);
        assertThat(received3).hasSize(1);
    }

    @Test
    void testTopicIsolation() throws InterruptedException {
        var topicALatch = new CountDownLatch(1);
        var topicBLatch = new CountDownLatch(1);
        var topicAReceived = new ArrayList<GossipAdapter.Message>();
        var topicBReceived = new ArrayList<GossipAdapter.Message>();

        adapter.subscribe("topic-a", message -> {
            topicAReceived.add(message);
            topicALatch.countDown();
        });
        adapter.subscribe("topic-b", message -> {
            topicBReceived.add(message);
            topicBLatch.countDown();
        });

        var senderId1 = UUID.randomUUID();
        var senderId2 = UUID.randomUUID();
        var messageA = new GossipAdapter.Message(senderId1, "message-a".getBytes());
        var messageB = new GossipAdapter.Message(senderId2, "message-b".getBytes());

        adapter.broadcast("topic-a", messageA);
        adapter.broadcast("topic-b", messageB);

        assertThat(topicALatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(topicBLatch.await(1, TimeUnit.SECONDS)).isTrue();

        // Topic A should only receive message A
        assertThat(topicAReceived).hasSize(1);
        assertThat(topicAReceived.get(0).senderId()).isEqualTo(senderId1);

        // Topic B should only receive message B
        assertThat(topicBReceived).hasSize(1);
        assertThat(topicBReceived.get(0).senderId()).isEqualTo(senderId2);
    }

    @Test
    void testNoSubscribersDoesNotFail() {
        // Broadcasting to a topic with no subscribers should not throw
        var senderId = UUID.randomUUID();
        var message = new GossipAdapter.Message(senderId, "orphan-message".getBytes());

        // This should not throw an exception
        adapter.broadcast("no-subscribers", message);

        // No assertions needed - just verify no exception
    }
}
