/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */
package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.events.EntityDepartureEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.99: {@code isNodeReachable()} must not report a crashed node as
 * reachable forever. Previously it returned {@code nodeAddresses.containsKey(nodeId)} — true for any
 * registered node regardless of liveness. The fix tracks consecutive terminal delivery failures and
 * marks a node unreachable after a threshold (reset on any success).
 *
 * @author hal.hildebrand
 */
class GrpcBubbleNetworkChannelReachabilityTest {

    private GrpcBubbleNetworkChannel channel;

    @BeforeEach
    void setUp() {
        // Explicit plaintext opt-in for tests (Luciferase-7wzml.200).
        channel = new GrpcBubbleNetworkChannel(true);
        channel.initialize(UUID.randomUUID(), "localhost:0");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.close();
        }
    }

    @Test
    void unregisteredNodeIsUnreachable() {
        assertFalse(channel.isNodeReachable(UUID.randomUUID()),
                    "an unregistered node must be unreachable");
    }

    @Test
    void freshlyRegisteredNodeIsReachable() {
        var node = UUID.randomUUID();
        channel.registerNode(node, "localhost:1"); // address present, no failures yet
        assertTrue(channel.isNodeReachable(node), "a freshly registered node is reachable");
    }

    @Test
    void crashedNodeBecomesUnreachableAfterRepeatedFailures() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var dead = UUID.randomUUID();
            // Register a dead peer: a port with no server. Deliveries terminally fail after retries.
            channel.registerNode(dead, "localhost:1");

            var event = new EntityDepartureEvent(
                UUID.randomUUID(), UUID.randomUUID(), dead, EntityMigrationState.MIGRATING_OUT, 0L);

            // Issue enough departures to accumulate the terminal-failure threshold.
            for (int i = 0; i < 6; i++) {
                channel.sendEntityDeparture(dead, event);
            }

            // Reachability must flip to false once consecutive terminal failures cross the threshold.
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            boolean unreachable = false;
            while (System.nanoTime() < deadline) {
                if (!channel.isNodeReachable(dead)) {
                    unreachable = true;
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue(unreachable,
                       "a registered-but-dead node must become unreachable after repeated terminal "
                       + "delivery failures (Luciferase-0frcy.99)");

            // Recovery clears the failure streak.
            channel.markNodeRecovered(dead);
            assertTrue(channel.isNodeReachable(dead), "markNodeRecovered must restore reachability");
        });
    }
}
