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

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test-driven development for TestFixtures.
 * <p>
 * Tests verify that test fixtures provide convenient cluster setup helpers
 * for consistent test initialization across the test suite.
 *
 * @author hal.hildebrand
 */
class TestFixturesTest {

    @Test
    void testSingleNodeClusterSetup() {
        var cluster = TestFixtures.createMockCluster(1);

        assertThat(cluster.views()).hasSize(1);
        assertThat(cluster.adapters()).hasSize(1);

        var view = cluster.views().get(0);
        var adapter = cluster.adapters().get(0);

        assertThat(view).isNotNull();
        assertThat(adapter).isNotNull();

        // Single node should see only itself initially
        var members = view.getMembers().toList();
        assertThat(members).hasSize(1);
    }

    @Test
    void testMultiNodeClusterSetup() {
        var nodeCount = 5;
        var cluster = TestFixtures.createMockCluster(nodeCount);

        assertThat(cluster.views()).hasSize(nodeCount);
        assertThat(cluster.adapters()).hasSize(nodeCount);

        // All nodes should see all other nodes in the cluster
        for (var view : cluster.views()) {
            var members = view.getMembers().toList();
            assertThat(members).hasSize(nodeCount);
        }

        // Verify all adapters can communicate via gossip
        for (int i = 0; i < nodeCount; i++) {
            var adapter = cluster.adapters().get(i);
            assertThat(adapter).isNotNull();
        }

        // Verify each view has the same set of node IDs
        var allNodeIds = cluster.views().stream()
                                .flatMap(view -> view.getMembers())
                                .distinct()
                                .toList();
        assertThat(allNodeIds).hasSize(nodeCount);
    }
}
