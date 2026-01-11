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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test TestClusterBuilder infrastructure with 6-node cluster.
 * <p>
 * This verifies the TestClusterBuilder pattern works correctly.
 * <p>
 * Disabled in CI: These integration tests involve multi-node cluster bootstrapping
 * and consensus convergence, which are expensive and timeout-prone on CI hardware.
 * Developers can run locally with: mvn test -Dtest=TestClusterBuilderTest
 *
 * @author hal.hildebrand
 */
public class TestClusterBuilderTest extends IntegrationTestBase {

    @Test
    void testSixNodeCluster_usingTestClusterBuilder() throws Exception {
        // Given: 6-node cluster from TestClusterBuilder
        setupCluster(6);

        // When: Bootstrap and start the cluster
        var success = cluster.bootstrapAndStart(Duration.ofMillis(5), 30);

        // Then: Cluster should stabilize
        assertTrue(success, "Cluster did not stabilize: " + cluster.getUnstableNodes());

        // Verify all views see all members
        var views = cluster.getViews();
        for (var view : views) {
            assertEquals(6, view.getContext().activeCount(),
                         "Each view should see 6 active members");
        }

        System.out.println("6-node cluster using TestClusterBuilder converged successfully");
    }

    @Test
    void testCardinality_respected() throws Exception {
        // Given: Custom cardinality
        cluster = new TestClusterBuilder()
            .cardinality(4)
            .build();

        // Then: Should have correct number of views and members
        assertEquals(4, cluster.getViews().size());
        assertEquals(4, cluster.getMembers().size());
        assertEquals(4, cluster.getCardinality());
    }

    @Test
    void testBuildAndStart_convenience() throws Exception {
        // Given: Using static convenience method
        cluster = TestClusterBuilder.buildAndStart(8);

        // Then: Should be fully connected
        assertEquals(8, cluster.getCardinality());
        for (var view : cluster.getViews()) {
            assertEquals(8, view.getContext().activeCount());
        }
    }
}
