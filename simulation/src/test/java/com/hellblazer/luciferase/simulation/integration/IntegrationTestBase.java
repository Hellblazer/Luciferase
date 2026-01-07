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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

/**
 * Base class for integration tests.
 * <p>
 * Provides:
 * - Test cluster lifecycle management (setup/teardown)
 * - Proper resource cleanup in @AfterEach
 * - @Tag("integration") on all integration tests
 * <p>
 * Subclasses should call setupCluster(nodeCount) in their setup
 * or individual test methods.
 *
 * @author hal.hildebrand
 */
@Tag("integration")
public abstract class IntegrationTestBase {

    protected TestClusterBuilder.TestCluster cluster;

    /**
     * Setup test cluster with specified number of nodes.
     * <p>
     * Called from test methods or subclass @BeforeEach.
     *
     * @param nodeCount number of nodes in cluster
     */
    protected void setupCluster(int nodeCount) {
        cluster = TestClusterBuilder.buildCluster(nodeCount);
    }

    /**
     * Cleanup test cluster after each test.
     * <p>
     * CRITICAL: Ensures no resource leaks (threads, sockets).
     */
    @AfterEach
    void teardownCluster() {
        if (cluster != null) {
            cluster.cleanup().run();
            cluster = null;
        }
    }
}
