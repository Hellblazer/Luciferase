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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test TestClusterBuilder infrastructure with 6-node cluster.
 * <p>
 * This verifies the TestClusterBuilder pattern works correctly.
 *
 * @author hal.hildebrand
 */
public class TestClusterBuilderTest extends IntegrationTestBase {

    @Test
    void testSixNodeCluster_usingTestClusterBuilder() throws Exception {
        // Given: 6-node cluster from TestClusterBuilder
        setupCluster(6);

        var views = cluster.views();
        var members = cluster.members();
        var endpoints = cluster.endpoints();

        // Create seed from first member
        var kernel = List.of(new Seed(
            members.get(0).getIdentifier().getIdentifier(),
            endpoints.get(0)
        ));

        var gossipDuration = Duration.ofMillis(5);

        // When: Bootstrap kernel node (view 0) with no seeds
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());

        // Then: Kernel node should bootstrap within 30s
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Kernel node did not bootstrap");

        // When: Start ALL views with kernel as seed
        countdown.set(new CountDownLatch(6));
        views.forEach(view ->
            view.start(() -> countdown.get().countDown(), gossipDuration, kernel)
        );

        // Then: All nodes should start within 30s
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Not all nodes started");

        // Wait for cluster convergence
        Thread.sleep(5000);

        // Then: All 6 nodes should see all 6 members
        var failed = views.stream()
                          .filter(v -> v.getContext().activeCount() != 6)
                          .map(v -> String.format("View %s has activeCount %d (expected 6)",
                                                  v.getContext().getId(),
                                                  v.getContext().activeCount()))
                          .toList();

        assertTrue(failed.isEmpty(),
                   "Cluster did not converge. Failed: " + failed.size() + "\n" + String.join("\n", failed));

        System.out.println("✅ 6-node cluster using TestClusterBuilder converged successfully");
    }
}
