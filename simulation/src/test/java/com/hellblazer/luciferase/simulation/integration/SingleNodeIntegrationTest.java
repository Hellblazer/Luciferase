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

package com.hellblazer.luciferase.simulation.integration;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.BubbleEntry;
import com.hellblazer.luciferase.simulation.bubble.ReplicatedForest;
import com.hellblazer.luciferase.simulation.delos.fireflies.DelosGossipAdapter;
import com.hellblazer.luciferase.simulation.delos.mock.MockGossipAdapter;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Single-node integration tests (fast, no network).
 * <p>
 * Tests:
 * 1. Single-node cluster forms membership correctly
 * 2. ReplicatedForest works locally without network
 * 3. Gossip broadcast works locally without network
 *
 * @author hal.hildebrand
 */
public class SingleNodeIntegrationTest extends IntegrationTestBase {

    @Test
    void testSingleNodeCluster_formsMembership() {
        // Given: Single-node cluster
        setupCluster(1);

        // When: Start view as single-node kernel
        var view = cluster.getView(0);
        view.start(() -> {}, java.time.Duration.ofMillis(50), java.util.Collections.emptyList());

        // Then: View should have exactly 1 member (self)
        await().atMost(5, SECONDS).until(() -> view.getContext().size() == 1);
        assertThat(view.getContext().size()).isEqualTo(1);
    }

    @Test
    void testReplicatedForest_singleNode_localOnly() {
        // Given: Single node with ReplicatedForest (mock gossip, no network)
        setupCluster(1);
        var forest = new ReplicatedForest(new MockGossipAdapter());

        // When: Add bubble entry
        var bubbleId = UUID.randomUUID();
        var serverId = UUID.randomUUID();
        var bounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(1.0f, 2.0f, 3.0f)));
        var timestamp = System.currentTimeMillis();
        var entry = new BubbleEntry(bubbleId, serverId, bounds, timestamp);

        forest.put(entry);

        // Then: Entry retrievable locally (no network)
        assertThat(forest.get(bubbleId)).isEqualTo(entry);
        assertThat(forest.size()).isEqualTo(1);
    }

    @Test
    void testGossipBroadcast_singleNode_noNetwork() {
        // Given: Single node with DelosGossipAdapter
        setupCluster(1);
        var view = cluster.getView(0);
        view.start(() -> {}, java.time.Duration.ofMillis(50), java.util.Collections.emptyList());

        var member = cluster.getMember(0);
        var adapter = new DelosGossipAdapter(view, member);

        // When: Subscribe and broadcast
        var received = new AtomicReference<com.hellblazer.luciferase.simulation.delos.GossipAdapter.Message>();
        adapter.subscribe("test-topic", received::set);

        var senderId = UUID.randomUUID();
        var payload = "test".getBytes();
        var message = new com.hellblazer.luciferase.simulation.delos.GossipAdapter.Message(senderId, payload);

        adapter.broadcast("test-topic", message);

        // Then: Message received locally (no network needed)
        await().atMost(1, SECONDS).until(() -> received.get() != null);
        assertThat(received.get()).isNotNull();
        assertThat(received.get().senderId()).isEqualTo(senderId);
        assertThat(received.get().payload()).isEqualTo(payload);
    }
}
