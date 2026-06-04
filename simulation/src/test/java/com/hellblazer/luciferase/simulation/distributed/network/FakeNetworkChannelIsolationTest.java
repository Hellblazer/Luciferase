/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.events.EntityDepartureEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolation contract for {@link FakeNetworkChannel}'s JVM-global {@code NETWORK} registry
 * (Luciferase-5yh9h).
 *
 * <p>{@code FakeNetworkChannel} routes through a {@code static} {@code NETWORK} map shared
 * across every channel in the classloader. That sharing is what enables cross-test
 * contamination if a test leaks a channel. These tests pin the mitigation contract that
 * test fixtures rely on: {@link FakeNetworkChannel#clearNetwork()} fully de-registers every
 * channel so a leaked channel from a prior test can neither be reached nor deliver into a
 * subsequent test's listeners. They are falsifiable — a regression that left stale entries
 * in {@code NETWORK} after {@code clearNetwork()} (or that delivered to a cleared channel)
 * would fail here.
 *
 * @author hal.hildebrand
 */
class FakeNetworkChannelIsolationTest {

    @BeforeEach
    void setUp() {
        FakeNetworkChannel.clearNetwork();
    }

    @AfterEach
    void tearDown() {
        FakeNetworkChannel.clearNetwork();
    }

    @Test
    @Timeout(5)
    void clearNetworkMakesPreviouslyInitializedChannelsUnreachable() {
        var nodeA = UUID.randomUUID();
        var nodeB = UUID.randomUUID();

        var channelA = new FakeNetworkChannel(nodeA);
        var channelB = new FakeNetworkChannel(nodeB);
        channelA.initialize(nodeA, "localhost:20001");
        channelB.initialize(nodeB, "localhost:20002");
        channelA.registerNode(nodeB, "localhost:20002");

        // Baseline: B is reachable while registered in NETWORK.
        assertTrue(channelA.isNodeReachable(nodeB), "B must be reachable before clearNetwork");

        // Simulate the @AfterEach of a prior test: clear the global registry.
        FakeNetworkChannel.clearNetwork();

        // A leaked reference to channelA must no longer be able to reach B: the global
        // registry no longer holds B, so isNodeReachable() is false and sends are dropped.
        assertFalse(channelA.isNodeReachable(nodeB),
                    "After clearNetwork, a stale channel must not reach a de-registered peer");
    }

    @Test
    @Timeout(5)
    void leakedChannelCannotDeliverIntoAFreshTestsListener() {
        // Prior "test": create A and B, register a rollback observer on B, but DELIBERATELY
        // leak channelA (never null it) to emulate a missing @AfterEach.
        var oldA = UUID.randomUUID();
        var oldB = UUID.randomUUID();
        var leakedA = new FakeNetworkChannel(oldA);
        var oldChannelB = new FakeNetworkChannel(oldB);
        leakedA.initialize(oldA, "localhost:21001");
        oldChannelB.initialize(oldB, "localhost:21002");
        leakedA.registerNode(oldB, "localhost:21002");

        var staleDeliveries = new AtomicInteger(0);
        oldChannelB.setEntityDepartureListener((src, evt) -> staleDeliveries.incrementAndGet());

        // Fresh test boundary.
        FakeNetworkChannel.clearNetwork();

        // The leaked channel tries to send to its old peer. Because NETWORK was cleared,
        // the target is unreachable and the send is dropped — the stale listener never fires,
        // so it cannot corrupt the fresh test's expectations.
        var event = new EntityDepartureEvent(UUID.randomUUID(), oldA, oldB,
                                             EntityMigrationState.MIGRATING_OUT, 0L);
        boolean delivered = leakedA.sendEntityDeparture(oldB, event);

        assertFalse(delivered, "Leaked channel must not deliver after clearNetwork");
        assertEquals(0, staleDeliveries.get(),
                     "A stale listener from a prior test must receive nothing after clearNetwork");
    }
}
