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

package com.hellblazer.luciferase.simulation.von.transport;

import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import com.hellblazer.luciferase.simulation.von.Message;
import com.hellblazer.luciferase.simulation.von.MessageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test failure injection API for LocalServerTransport.
 * <p>
 * Phase 6A requirement: LocalServerTransport must support failure injection
 * to enable testing of distributed failure scenarios without external tools.
 * <p>
 * Tests:
 * <ul>
 *   <li>injectDelay(ms) - Simulates network latency</li>
 *   <li>injectPartition(enabled) - Simulates network partition (message drops)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class FailureInjectionTest {

    private LocalServerTransport.Registry registry;
    private final MessageFactory factory = MessageFactory.system();

    @AfterEach
    void cleanup() {
        if (registry != null) {
            registry.close();
        }
    }

    /**
     * Test injectDelay() adds latency to message delivery.
     */
    @Test
    void testDelayedMessageDelivery() throws InterruptedException {
        registry = LocalServerTransport.Registry.create();

        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();

        var transport1 = registry.register(uuid1);
        var transport2 = registry.register(uuid2);

        // Setup message capture
        BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        transport2.onMessage(received::offer);

        // Inject 150ms delay
        transport1.injectDelay(150);

        // Send message and measure time
        var message = factory.createAck(UUID.randomUUID(), uuid1);

        var start = System.currentTimeMillis();
        transport1.sendToNeighbor(uuid2, message);

        // Wait for message to arrive
        var receivedMsg = received.poll(2, TimeUnit.SECONDS);
        var elapsed = System.currentTimeMillis() - start;

        assertNotNull(receivedMsg, "Message should be received despite delay");
        assertTrue(elapsed >= 150, String.format("Delay should cause at least 150ms latency. Actual: %d ms", elapsed));
        assertTrue(elapsed < 500, String.format("Delay should not be excessive. Actual: %d ms", elapsed));
    }

    /**
     * Test injectDelay(0) disables delay.
     */
    @Test
    void testDelayDisabled() throws InterruptedException {
        registry = LocalServerTransport.Registry.create();

        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();

        var transport1 = registry.register(uuid1);
        var transport2 = registry.register(uuid2);

        BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        transport2.onMessage(received::offer);

        // Initially no delay
        var message = factory.createAck(UUID.randomUUID(), uuid1);

        var start = System.currentTimeMillis();
        transport1.sendToNeighbor(uuid2, message);

        var receivedMsg = received.poll(1, TimeUnit.SECONDS);
        var elapsed = System.currentTimeMillis() - start;

        assertNotNull(receivedMsg, "Message should be received quickly");
        assertTrue(elapsed < 100, String.format("No delay should result in fast delivery. Actual: %d ms", elapsed));

        // Enable delay
        transport1.injectDelay(200);
        start = System.currentTimeMillis();
        transport1.sendToNeighbor(uuid2, factory.createAck(UUID.randomUUID(), uuid1));
        received.poll(2, TimeUnit.SECONDS);
        elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 200, "Delay should be active");

        // Disable delay
        transport1.injectDelay(0);
        start = System.currentTimeMillis();
        transport1.sendToNeighbor(uuid2, factory.createAck(UUID.randomUUID(), uuid1));
        received.poll(1, TimeUnit.SECONDS);
        elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 100, "Delay should be disabled");
    }

    /**
     * Test injectPartition(true) drops all messages.
     */
    @Test
    void testPartitionDropsMessages() throws InterruptedException {
        registry = LocalServerTransport.Registry.create();

        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();

        var transport1 = registry.register(uuid1);
        var transport2 = registry.register(uuid2);

        BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        transport2.onMessage(received::offer);

        // Inject partition
        transport1.injectPartition(true);

        // Send message - should be dropped
        var message = factory.createAck(UUID.randomUUID(), uuid1);
        transport1.sendToNeighbor(uuid2, message);

        // Wait and verify message NOT received
        var receivedMsg = received.poll(500, TimeUnit.MILLISECONDS);
        assertNull(receivedMsg, "Message should be dropped during partition");
    }

    /**
     * Test injectPartition(false) restores normal operation.
     */
    @Test
    void testPartitionRecovery() throws InterruptedException {
        registry = LocalServerTransport.Registry.create();

        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();

        var transport1 = registry.register(uuid1);
        var transport2 = registry.register(uuid2);

        BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        transport2.onMessage(received::offer);

        // Enable partition
        transport1.injectPartition(true);

        // Send message - should be dropped
        transport1.sendToNeighbor(uuid2, factory.createAck(UUID.randomUUID(), uuid1));
        assertNull(received.poll(500, TimeUnit.MILLISECONDS), "Message should be dropped");

        // Disable partition
        transport1.injectPartition(false);

        // Send message - should arrive
        transport1.sendToNeighbor(uuid2, factory.createAck(UUID.randomUUID(), uuid1));
        var receivedMsg = received.poll(1, TimeUnit.SECONDS);
        assertNotNull(receivedMsg, "Message should arrive after partition cleared");
    }

    /**
     * Test combined delay + partition.
     */
    @Test
    void testCombinedFailures() throws InterruptedException {
        registry = LocalServerTransport.Registry.create();

        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();

        var transport1 = registry.register(uuid1);
        var transport2 = registry.register(uuid2);

        BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        transport2.onMessage(received::offer);

        // Inject both delay and partition
        transport1.injectDelay(100);
        transport1.injectPartition(true);

        // Send message - should be dropped (partition overrides delay)
        transport1.sendToNeighbor(uuid2, factory.createAck(UUID.randomUUID(), uuid1));
        assertNull(received.poll(500, TimeUnit.MILLISECONDS), "Partition should drop message even with delay");

        // Clear partition but keep delay
        transport1.injectPartition(false);

        // Send message - should arrive with delay
        var start = System.currentTimeMillis();
        transport1.sendToNeighbor(uuid2, factory.createAck(UUID.randomUUID(), uuid1));
        var receivedMsg = received.poll(1, TimeUnit.SECONDS);
        var elapsed = System.currentTimeMillis() - start;

        assertNotNull(receivedMsg, "Message should arrive after clearing partition");
        assertTrue(elapsed >= 100, "Delay should still be active");
    }

    /**
     * Test multiple senders with independent failure injection.
     */
    @Test
    void testIndependentFailureInjection() throws InterruptedException {
        registry = LocalServerTransport.Registry.create();

        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();
        var uuid3 = UUID.randomUUID();

        var transport1 = registry.register(uuid1);
        var transport2 = registry.register(uuid2);
        var transport3 = registry.register(uuid3);

        BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        transport3.onMessage(received::offer);

        // Inject partition only on transport1
        transport1.injectPartition(true);

        // Send from transport1 - should be dropped
        transport1.sendToNeighbor(uuid3, factory.createAck(UUID.randomUUID(), uuid1));
        assertNull(received.poll(500, TimeUnit.MILLISECONDS), "Transport1 message should be dropped");

        // Send from transport2 - should arrive
        transport2.sendToNeighbor(uuid3, factory.createAck(UUID.randomUUID(), uuid2));
        var receivedMsg = received.poll(1, TimeUnit.SECONDS);
        assertNotNull(receivedMsg, "Transport2 message should arrive (no partition)");
    }

    /**
     * Test that failure injection doesn't affect async sends.
     */
    @Test
    void testFailureInjectionAffectsAsyncSends() throws Exception {
        registry = LocalServerTransport.Registry.create();

        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();

        var transport1 = registry.register(uuid1);
        var transport2 = registry.register(uuid2);

        BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        transport2.onMessage(received::offer);

        // Inject delay
        transport1.injectDelay(150);

        // Send async
        var start = System.currentTimeMillis();
        var future = transport1.sendToNeighborAsync(uuid2, factory.createAck(UUID.randomUUID(), uuid1));

        // Wait for completion
        future.get(2, TimeUnit.SECONDS);
        var elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 150, "Async send should also experience injected delay");

        // Verify message arrived
        var receivedMsg = received.poll(500, TimeUnit.MILLISECONDS);
        assertNotNull(receivedMsg, "Async message should arrive");
    }
}
