/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.bubble.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ServerRegistry - tracks bubble-to-server assignments.
 *
 * @author hal.hildebrand
 */
class ServerRegistryTest {

    @Test
    void testRegisterBubble() {
        var registry = new ServerRegistry();
        var bubbleId = UUID.randomUUID();
        var serverId = UUID.randomUUID();

        registry.registerBubble(bubbleId, serverId);

        assertEquals(serverId, registry.getServerId(bubbleId));
        assertTrue(registry.getBubblesOnServer(serverId).contains(bubbleId));
    }

    @Test
    void testGetServerId() {
        var registry = new ServerRegistry();
        var bubble1 = UUID.randomUUID();
        var server1 = UUID.randomUUID();

        registry.registerBubble(bubble1, server1);

        assertEquals(server1, registry.getServerId(bubble1));
    }

    @Test
    void testGetBubblesOnServer() {
        var registry = new ServerRegistry();
        var serverId = UUID.randomUUID();
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();
        var bubble3 = UUID.randomUUID();  // Different server

        registry.registerBubble(bubble1, serverId);
        registry.registerBubble(bubble2, serverId);
        registry.registerBubble(bubble3, UUID.randomUUID());

        var bubbles = registry.getBubblesOnServer(serverId);
        assertEquals(2, bubbles.size());
        assertTrue(bubbles.contains(bubble1));
        assertTrue(bubbles.contains(bubble2));
    }

    @Test
    void testUnregisterBubble() {
        var registry = new ServerRegistry();
        var bubbleId = UUID.randomUUID();
        var serverId = UUID.randomUUID();

        registry.registerBubble(bubbleId, serverId);
        registry.unregisterBubble(bubbleId);

        assertNull(registry.getServerId(bubbleId));
        assertFalse(registry.getBubblesOnServer(serverId).contains(bubbleId));
    }

    @Test
    void testBubbleNotFound() {
        var registry = new ServerRegistry();
        var unknownBubble = UUID.randomUUID();

        assertNull(registry.getServerId(unknownBubble));
    }

    @Test
    void testIsSameServer() {
        var registry = new ServerRegistry();
        var serverId = UUID.randomUUID();
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();
        var bubble3 = UUID.randomUUID();  // Different server

        registry.registerBubble(bubble1, serverId);
        registry.registerBubble(bubble2, serverId);
        registry.registerBubble(bubble3, UUID.randomUUID());

        assertTrue(registry.isSameServer(bubble1, bubble2));
        assertFalse(registry.isSameServer(bubble1, bubble3));
    }

    @Test
    void testConcurrentRegistration() throws Exception {
        var registry = new ServerRegistry();
        var serverId = UUID.randomUUID();
        int threadCount = 10;
        int bubblesPerThread = 100;

        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                for (int i = 0; i < bubblesPerThread; i++) {
                    registry.registerBubble(UUID.randomUUID(), serverId);
                }
                latch.countDown();
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount * bubblesPerThread, registry.getBubblesOnServer(serverId).size());
    }

    @Test
    void testServerMigration() {
        var registry = new ServerRegistry();
        var bubbleId = UUID.randomUUID();
        var server1 = UUID.randomUUID();
        var server2 = UUID.randomUUID();

        registry.registerBubble(bubbleId, server1);
        assertEquals(server1, registry.getServerId(bubbleId));

        // Migrate to server2
        registry.registerBubble(bubbleId, server2);

        assertEquals(server2, registry.getServerId(bubbleId));
        assertFalse(registry.getBubblesOnServer(server1).contains(bubbleId));
        assertTrue(registry.getBubblesOnServer(server2).contains(bubbleId));
    }

    @Test
    void testServerCleanupAfterLastBubbleRemoved() {
        var registry = new ServerRegistry();
        var bubbleId = UUID.randomUUID();
        var serverId = UUID.randomUUID();

        registry.registerBubble(bubbleId, serverId);
        assertEquals(1, registry.getServerCount());

        registry.unregisterBubble(bubbleId);
        assertEquals(0, registry.getServerCount());  // Server entry should be removed
    }
}
