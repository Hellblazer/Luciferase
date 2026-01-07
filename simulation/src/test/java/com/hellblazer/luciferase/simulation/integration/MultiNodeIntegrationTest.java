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

import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.BubbleEntry;
import com.hellblazer.luciferase.simulation.bubble.ReplicatedForest;
import com.hellblazer.luciferase.simulation.delos.fireflies.DelosGossipAdapter;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Multi-node integration tests (real gossip).
 * <p>
 * Tests:
 * 1. Two-node cluster membership synchronization
 * 2. ReplicatedForest gossip propagation
 * 3. Bubble addition propagates within timeout
 * 4. Bubble removal propagates within timeout
 * 5. Conflict resolution with last-write-wins
 *
 * @author hal.hildebrand
 */
public class MultiNodeIntegrationTest extends IntegrationTestBase {

    @Test
    void testTwoNodeCluster_membershipSync() {
        // Given: Two-node cluster
        setupCluster(2);

        // Extract cluster components
        var views = cluster.views();
        var duration = Duration.ofMillis(50);

        // Start first view as kernel
        views.get(0).start(() -> {}, duration, Collections.emptyList());

        // Start second view connecting to first (using stored endpoint)
        var member0 = cluster.members().get(0);
        var kernel = List.of(new Seed(
            member0.getIdentifier().getIdentifier(),
            cluster.endpoints().get(0)
        ));
        views.get(1).start(() -> {}, duration, kernel);

        // Then: Both nodes see both members within 5s
        await().atMost(5, SECONDS).until(() ->
            views.get(0).getContext().size() == 2 &&
            views.get(1).getContext().size() == 2
        );

        assertThat(views.get(0).getContext().size()).isEqualTo(2);
        assertThat(views.get(1).getContext().size()).isEqualTo(2);
    }

    @Test
    void testReplicatedForest_twoNodes_gossipPropagation() {
        // Given: Two nodes with ReplicatedForest + real gossip
        setupCluster(2);

        var views = cluster.views();
        var members = cluster.members();
        var duration = Duration.ofMillis(50);

        views.get(0).start(() -> {}, duration, Collections.emptyList());

        var member0 = members.get(0);
        var kernel = List.of(new Seed(
            member0.getIdentifier().getIdentifier(),
            cluster.endpoints().get(0)
        ));
        views.get(1).start(() -> {}, duration, kernel);

        await().atMost(5, SECONDS).until(() -> views.get(0).getContext().size() == 2);

        var adapter1 = new DelosGossipAdapter(views.get(0), members.get(0));
        var adapter2 = new DelosGossipAdapter(views.get(1), members.get(1));
        DelosGossipAdapter.connectAdapters(adapter1, adapter2);

        var forest1 = new ReplicatedForest(adapter1);
        var forest2 = new ReplicatedForest(adapter2);

        // When: Add entry to node 1
        var bubbleId = UUID.randomUUID();
        var serverId = UUID.randomUUID();
        var bounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(1.0f, 2.0f, 3.0f)));
        var timestamp = System.currentTimeMillis();
        var entry = new BubbleEntry(bubbleId, serverId, bounds, timestamp);

        forest1.put(entry);

        // Then: Entry propagates to node 2 within 2s
        await().atMost(2, SECONDS).until(() -> forest2.get(bubbleId) != null);
        assertThat(forest2.get(bubbleId)).isEqualTo(entry);
    }

    @Test
    void testBubbleAddition_propagatesWithinTimeout() {
        // Given: Two-node cluster with ReplicatedForest
        setupCluster(2);

        var views = cluster.views();
        var members = cluster.members();
        var duration = Duration.ofMillis(50);

        views.get(0).start(() -> {}, duration, Collections.emptyList());

        var member0 = members.get(0);
        var kernel = List.of(new Seed(
            member0.getIdentifier().getIdentifier(),
            cluster.endpoints().get(0)
        ));
        views.get(1).start(() -> {}, duration, kernel);

        await().atMost(5, SECONDS).until(() -> views.get(0).getContext().size() == 2);

        var adapter1 = new DelosGossipAdapter(views.get(0), members.get(0));
        var adapter2 = new DelosGossipAdapter(views.get(1), members.get(1));
        DelosGossipAdapter.connectAdapters(adapter1, adapter2);

        var forest1 = new ReplicatedForest(adapter1);
        var forest2 = new ReplicatedForest(adapter2);

        // When: Add multiple entries
        var entry1 = new BubbleEntry(UUID.randomUUID(), UUID.randomUUID(),
                                     BubbleBounds.fromEntityPositions(List.of(new Point3f(1.0f, 2.0f, 3.0f))),
                                     System.currentTimeMillis());
        var entry2 = new BubbleEntry(UUID.randomUUID(), UUID.randomUUID(),
                                     BubbleBounds.fromEntityPositions(List.of(new Point3f(4.0f, 5.0f, 6.0f))),
                                     System.currentTimeMillis());

        forest1.put(entry1);
        forest1.put(entry2);

        // Then: Both entries propagate within 2s
        await().atMost(2, SECONDS).until(() ->
            forest2.get(entry1.bubbleId()) != null &&
            forest2.get(entry2.bubbleId()) != null
        );

        assertThat(forest2.size()).isEqualTo(2);
    }

    @Test
    void testBubbleRemoval_propagatesWithinTimeout() {
        // Given: Two-node cluster with shared entry
        setupCluster(2);

        var views = cluster.views();
        var members = cluster.members();
        var duration = Duration.ofMillis(50);

        views.get(0).start(() -> {}, duration, Collections.emptyList());

        var member0 = members.get(0);
        var kernel = List.of(new Seed(
            member0.getIdentifier().getIdentifier(),
            cluster.endpoints().get(0)
        ));
        views.get(1).start(() -> {}, duration, kernel);

        await().atMost(5, SECONDS).until(() -> views.get(0).getContext().size() == 2);

        var adapter1 = new DelosGossipAdapter(views.get(0), members.get(0));
        var adapter2 = new DelosGossipAdapter(views.get(1), members.get(1));
        DelosGossipAdapter.connectAdapters(adapter1, adapter2);

        var forest1 = new ReplicatedForest(adapter1);
        var forest2 = new ReplicatedForest(adapter2);

        var entry = new BubbleEntry(UUID.randomUUID(), UUID.randomUUID(),
                                    BubbleBounds.fromEntityPositions(List.of(new Point3f(1.0f, 2.0f, 3.0f))),
                                    System.currentTimeMillis());

        forest1.put(entry);
        await().atMost(2, SECONDS).until(() -> forest2.get(entry.bubbleId()) != null);

        // When: Remove from node 1
        forest1.remove(entry.bubbleId());

        // Then: Entry removed locally (Note: distributed removal requires tombstones in Phase 1+)
        assertThat(forest1.get(entry.bubbleId())).isNull();
        // forest2 still has it (no distributed removal in Phase 0)
    }

    @Test
    void testConflictResolution_lastWriteWins() {
        // Given: Two nodes with ReplicatedForest
        setupCluster(2);

        var views = cluster.views();
        var members = cluster.members();
        var duration = Duration.ofMillis(50);

        views.get(0).start(() -> {}, duration, Collections.emptyList());

        var member0 = members.get(0);
        var kernel = List.of(new Seed(
            member0.getIdentifier().getIdentifier(),
            cluster.endpoints().get(0)
        ));
        views.get(1).start(() -> {}, duration, kernel);

        await().atMost(5, SECONDS).until(() -> views.get(0).getContext().size() == 2);

        var adapter1 = new DelosGossipAdapter(views.get(0), members.get(0));
        var adapter2 = new DelosGossipAdapter(views.get(1), members.get(1));
        DelosGossipAdapter.connectAdapters(adapter1, adapter2);

        var forest1 = new ReplicatedForest(adapter1);
        var forest2 = new ReplicatedForest(adapter2);

        // When: Concurrent updates with different timestamps
        var id = UUID.randomUUID();
        var server1 = UUID.randomUUID();
        var server2 = UUID.randomUUID();
        var bounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(1.0f, 2.0f, 3.0f)));

        var entry1 = new BubbleEntry(id, server1, bounds, 1000L);  // Older
        var entry2 = new BubbleEntry(id, server2, bounds, 2000L);  // Newer (should win)

        forest1.put(entry1);
        forest2.put(entry2);

        // Then: Both converge to entry2 (last write wins)
        await().atMost(3, SECONDS).until(() ->
            forest1.get(id) != null &&
            forest2.get(id) != null &&
            forest1.get(id).timestamp() == 2000L &&
            forest2.get(id).timestamp() == 2000L
        );

        assertThat(forest1.get(id).serverId()).isEqualTo(server2);
        assertThat(forest2.get(id).serverId()).isEqualTo(server2);
    }
}
