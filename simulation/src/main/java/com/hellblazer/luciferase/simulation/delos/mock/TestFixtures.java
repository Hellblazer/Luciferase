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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test fixture utilities for creating mock Delos clusters.
 * <p>
 * Provides convenient methods for setting up multi-node test clusters
 * with pre-configured membership views and gossip adapters.
 *
 * @author hal.hildebrand
 */
public class TestFixtures {

    /**
     * Create a mock cluster with the specified number of nodes.
     * <p>
     * All nodes are pre-configured to see each other via their membership views.
     * Each node gets its own gossip adapter for testing pub/sub behavior.
     *
     * @param nodeCount the number of nodes in the cluster
     * @return a MockCluster containing views and adapters for all nodes
     */
    public static MockCluster createMockCluster(int nodeCount) {
        if (nodeCount < 1) {
            throw new IllegalArgumentException("Node count must be at least 1");
        }

        // Generate unique node IDs for the cluster
        var nodeIds = new ArrayList<UUID>();
        for (int i = 0; i < nodeCount; i++) {
            nodeIds.add(UUID.randomUUID());
        }

        // Create views and adapters for each node
        var views = new ArrayList<MockFirefliesView<UUID>>();
        var adapters = new ArrayList<MockGossipAdapter>();

        for (var nodeId : nodeIds) {
            var view = new MockFirefliesView<UUID>();

            // Add all nodes (including self) to each view
            for (var otherId : nodeIds) {
                view.addMember(otherId);
            }

            views.add(view);
            adapters.add(new MockGossipAdapter());
        }

        return new MockCluster(views, adapters);
    }

    /**
     * Represents a mock cluster configuration.
     *
     * @param views    membership views for each node
     * @param adapters gossip adapters for each node
     */
    public record MockCluster(
        List<MockFirefliesView<UUID>> views,
        List<MockGossipAdapter> adapters
    ) {
    }
}
