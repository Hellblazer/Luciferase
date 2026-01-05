/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.EnhancedBubble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SameServerOptimizer - detects same-server bubbles for optimization.
 * <p>
 * SameServerOptimizer enables the critical same-server optimization: when two bubbles share a server, they bypass ghost
 * synchronization and use direct memory access instead. This eliminates:
 * - Network overhead (no serialization, no transmission)
 * - Ghost TTL management (direct access is always fresh)
 * - Memory overhead (no ghost copies)
 * <p>
 * The optimizer maintains local bubble references and delegates server assignment checks to ServerRegistry.
 *
 * @author hal.hildebrand
 */
class SameServerOptimizerTest {

    private ServerRegistry serverRegistry;
    private SameServerOptimizer optimizer;

    @BeforeEach
    void setUp() {
        serverRegistry = new ServerRegistry();
        optimizer = new SameServerOptimizer(serverRegistry);
    }

    @Test
    void testSameServerDetected() {
        var serverId = UUID.randomUUID();
        var bubble1 = createBubble();
        var bubble2 = createBubble();

        serverRegistry.registerBubble(bubble1.id(), serverId);
        serverRegistry.registerBubble(bubble2.id(), serverId);

        assertTrue(optimizer.isSameServer(bubble1.id(), bubble2.id()));
    }

    @Test
    void testDifferentServerDetected() {
        var server1 = UUID.randomUUID();
        var server2 = UUID.randomUUID();
        var bubble1 = createBubble();
        var bubble2 = createBubble();

        serverRegistry.registerBubble(bubble1.id(), server1);
        serverRegistry.registerBubble(bubble2.id(), server2);

        assertFalse(optimizer.isSameServer(bubble1.id(), bubble2.id()));
    }

    @Test
    void testShouldBypassGhostSync() {
        var serverId = UUID.randomUUID();
        var bubble1 = createBubble();
        var bubble2 = createBubble();

        serverRegistry.registerBubble(bubble1.id(), serverId);
        serverRegistry.registerBubble(bubble2.id(), serverId);

        // Same server -> should bypass ghost sync
        assertTrue(optimizer.shouldBypassGhostSync(bubble1.id(), bubble2.id()));
    }

    @Test
    void testNoBypassWhenDisabled() {
        var serverId = UUID.randomUUID();
        var bubble1 = createBubble();
        var bubble2 = createBubble();

        serverRegistry.registerBubble(bubble1.id(), serverId);
        serverRegistry.registerBubble(bubble2.id(), serverId);

        optimizer.setEnabled(false);

        // Optimization disabled -> should NOT bypass
        assertFalse(optimizer.shouldBypassGhostSync(bubble1.id(), bubble2.id()));
    }

    @Test
    void testGetLocalBubble() {
        var bubble = createBubble();
        optimizer.registerLocalBubble(bubble);

        var retrieved = optimizer.getLocalBubble(bubble.id());
        assertNotNull(retrieved);
        assertEquals(bubble.id(), retrieved.id());
    }

    @Test
    void testQueryDirectNeighbor() {
        var bubble1 = createBubble();
        var bubble2 = createBubble();

        // Add entities to bubble2 (use positive coordinates for Tetree)
        bubble2.addEntity("entity-1", new Point3f(1.0f, 1.0f, 1.0f), "content-1");
        bubble2.addEntity("entity-2", new Point3f(1.1f, 1.1f, 1.1f), "content-2");

        optimizer.registerLocalBubble(bubble1);
        optimizer.registerLocalBubble(bubble2);

        // Query bubble2 directly (no ghost sync)
        var results = optimizer.queryDirectNeighbor(bubble2.id(), new Point3f(1.0f, 1.0f, 1.0f), 0.5f);

        assertNotNull(results);
        assertEquals(2, results.size());
    }

    @Test
    void testRegisterUnregisterLocalBubble() {
        var bubble = createBubble();

        optimizer.registerLocalBubble(bubble);
        assertEquals(1, optimizer.getLocalBubbleCount());

        optimizer.unregisterLocalBubble(bubble.id());
        assertEquals(0, optimizer.getLocalBubbleCount());
    }

    @Test
    void testOptimizationPerformance() {
        var serverId = UUID.randomUUID();
        var bubble1 = createBubble();
        var bubble2 = createBubble();

        serverRegistry.registerBubble(bubble1.id(), serverId);
        serverRegistry.registerBubble(bubble2.id(), serverId);

        // Measure isSameServer performance (should be O(1))
        long startNs = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            optimizer.isSameServer(bubble1.id(), bubble2.id());
        }
        long elapsedNs = System.nanoTime() - startNs;

        // 10K lookups should complete in <10ms (extremely fast)
        long elapsedMs = elapsedNs / 1_000_000;
        assertTrue(elapsedMs < 10, "Expected <10ms for 10K lookups, got " + elapsedMs + "ms");
    }

    // Helper methods

    private EnhancedBubble createBubble() {
        return new EnhancedBubble(UUID.randomUUID(), (byte) 5, 16);
    }
}
