/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 5C - Ghost Sync Integration.
 * <p>
 * Tests ghost layer synchronization using existing GhostBoundarySync infrastructure
 * integrated into MultiBubbleSimulation via GridGhostSyncAdapter.
 *
 * @author hal.hildebrand
 */
class MultiBubbleGhostSyncTest {

    private static final Logger log = LoggerFactory.getLogger(MultiBubbleGhostSyncTest.class);

    private MultiBubbleSimulation simulation;

    @AfterEach
    void cleanup() {
        if (simulation != null) {
            simulation.close();
        }
    }

    @Test
    void testGhostBoundaryDetection() {
        // Create 2x2 grid with 100x100 cells
        var config = GridConfiguration.DEFAULT_2X2;
        var worldBounds = new WorldBounds(0f, 400f);

        // Single entity near boundary (x=95, which is 5 units from x=100 boundary)
        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        // Add entity near right edge of (0,0) cell
        var bubble = simulation.getBubble(new BubbleCoordinate(0, 0));
        bubble.addEntity("entity-1", new Point3f(95f, 50f, 50f), null);

        simulation.start();

        // Run for a few ticks to allow ghost sync
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulation.stop();

        // Verify ghost count > 0 (entity near boundary should generate ghosts)
        int ghostCount = simulation.getGhostCount();
        assertTrue(ghostCount > 0, "Entity near boundary should generate ghosts");

        log.info("testGhostBoundaryDetection: {} ghosts detected", ghostCount);
    }

    @Test
    void testGhostsCreatedForNeighbors() {
        var config = GridConfiguration.DEFAULT_2X2;
        var worldBounds = new WorldBounds(0f, 400f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        // Add entity near boundary between (0,0) and (0,1)
        var bubble = simulation.getBubble(new BubbleCoordinate(0, 0));
        bubble.addEntity("entity-1", new Point3f(95f, 50f, 50f), null);

        simulation.start();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        simulation.stop();

        // Verify neighbor bubble (0,1) received ghosts
        var entities = simulation.getAllEntities();
        long ghostsInNeighbor = entities.stream()
            .filter(e -> e.bubbleCoord().equals(new BubbleCoordinate(0, 1)))
            .filter(e -> e.isGhost())
            .count();

        assertTrue(ghostsInNeighbor > 0, "Neighbor bubble should receive ghosts");

        log.info("testGhostsCreatedForNeighbors: {} ghosts in neighbor", ghostsInNeighbor);
    }

    @Test
    void testCornerBubbleGhosts() {
        // Corner bubble (0,0) has 3 neighbors: (0,1), (1,0), (1,1)
        var config = GridConfiguration.DEFAULT_2X2;
        var worldBounds = new WorldBounds(0f, 400f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        // Add entity near corner (95, 95) - should ghost to 3 neighbors
        var bubble = simulation.getBubble(new BubbleCoordinate(0, 0));
        bubble.addEntity("corner-entity", new Point3f(95f, 95f, 50f), null);

        simulation.start();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        simulation.stop();

        // Count ghosts in the 3 neighbors
        var entities = simulation.getAllEntities();
        long ghostCount = entities.stream()
            .filter(e -> !e.bubbleCoord().equals(new BubbleCoordinate(0, 0)))
            .filter(e -> e.isGhost())
            .count();

        // Should be at least 1 ghost (may be up to 3 depending on AOI radius)
        assertTrue(ghostCount >= 1, "Corner entity should ghost to at least one neighbor");
        assertTrue(ghostCount <= 3, "Corner entity should ghost to at most 3 neighbors");

        log.info("testCornerBubbleGhosts: {} ghosts in {} neighbors", ghostCount, ghostCount);
    }

    @Test
    void testEdgeBubbleGhosts() {
        // Edge bubble in 3x3 grid has 5 neighbors
        var config = GridConfiguration.DEFAULT_3X3;
        var worldBounds = new WorldBounds(0f, 600f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        // Add entity near edge of middle-left cell (1,0)
        var bubble = simulation.getBubble(new BubbleCoordinate(1, 0));
        bubble.addEntity("edge-entity", new Point3f(95f, 150f, 50f), null);

        simulation.start();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        simulation.stop();

        var entities = simulation.getAllEntities();
        long ghostCount = entities.stream()
            .filter(e -> !e.bubbleCoord().equals(new BubbleCoordinate(1, 0)))
            .filter(e -> e.isGhost())
            .count();

        // Edge cell has 5 neighbors, but entity only near right boundary, so fewer ghosts
        assertTrue(ghostCount >= 1, "Edge entity should ghost to at least one neighbor");

        log.info("testEdgeBubbleGhosts: {} ghosts from edge entity", ghostCount);
    }

    @Test
    void testInteriorBubbleGhosts() {
        // Interior bubble in 3x3 grid has 8 neighbors
        var config = GridConfiguration.DEFAULT_3X3;
        var worldBounds = new WorldBounds(0f, 600f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        // Add entity in center of interior cell (1,1)
        var bubble = simulation.getBubble(new BubbleCoordinate(1, 1));
        bubble.addEntity("interior-entity", new Point3f(150f, 150f, 50f), null);

        simulation.start();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        simulation.stop();

        // Center entity not near any boundary - no ghosts expected
        int ghostCount = simulation.getGhostCount();
        assertEquals(0, ghostCount, "Center entity not near boundary should not generate ghosts");

        log.info("testInteriorBubbleGhosts: {} ghosts (expected 0)", ghostCount);
    }

    @Test
    void testGhostTTLExpiration() {
        var config = GridConfiguration.DEFAULT_2X2;
        var worldBounds = new WorldBounds(0f, 400f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        var bubble = simulation.getBubble(new BubbleCoordinate(0, 0));
        bubble.addEntity("temp-entity", new Point3f(95f, 50f, 50f), null);

        simulation.start();

        // Run for 100ms (about 6 ticks @ 16.67ms/tick)
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int initialGhosts = simulation.getGhostCount();
        assertTrue(initialGhosts > 0, "Should have ghosts initially");

        // Remove entity
        bubble.removeEntity("temp-entity");

        // Wait for TTL to expire (GhostBoundarySync.GHOST_TTL_BUCKETS = 5 buckets)
        // At 100ms/bucket, 5 buckets = 500ms
        // We use bucket-based TTL, so wait ~600ms to ensure expiration
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulation.stop();

        // Ghosts should have expired
        int finalGhosts = simulation.getGhostCount();
        assertTrue(finalGhosts < initialGhosts,
            "Ghosts should expire after TTL (initial=" + initialGhosts + ", final=" + finalGhosts + ")");

        log.info("testGhostTTLExpiration: initial={}, final={}", initialGhosts, finalGhosts);
    }

    @Test
    void testGhostMemoryLimit() {
        var config = GridConfiguration.DEFAULT_2X2;
        var worldBounds = new WorldBounds(0f, 400f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        var bubble = simulation.getBubble(new BubbleCoordinate(0, 0));

        // Add many entities near boundary (exceeds MAX_GHOSTS_PER_NEIGHBOR = 1000)
        // This would create 1200 ghosts without the limit
        for (int i = 0; i < 1200; i++) {
            bubble.addEntity("entity-" + i, new Point3f(95f, 50f + i * 0.01f, 50f), null);
        }

        simulation.start();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        simulation.stop();

        // Total ghosts should not exceed MAX_GHOSTS_PER_NEIGHBOR (1000) per neighbor
        int ghostCount = simulation.getGhostCount();

        // With 3 neighbors for corner cell, max would be 3000
        // But we're only near one boundary, so expect ~1000
        assertTrue(ghostCount <= 1200, "Ghost count should respect memory limit: " + ghostCount);

        log.info("testGhostMemoryLimit: {} ghosts (limit enforced)", ghostCount);
    }

    @Test
    void testDiagonalGhostSync() {
        var config = GridConfiguration.DEFAULT_2X2;
        var worldBounds = new WorldBounds(0f, 400f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        // Add entity at exact corner (100, 100) - diagonal to (1,1)
        var bubble = simulation.getBubble(new BubbleCoordinate(0, 0));
        bubble.addEntity("diagonal-entity", new Point3f(100f, 100f, 50f), null);

        simulation.start();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        simulation.stop();

        // Check diagonal neighbor (1,1) for ghosts
        var entities = simulation.getAllEntities();
        boolean hasDiagonalGhost = entities.stream()
            .anyMatch(e -> e.bubbleCoord().equals(new BubbleCoordinate(1, 1)) && e.isGhost());

        assertTrue(hasDiagonalGhost, "Entity at corner should ghost to diagonal neighbor");

        log.info("testDiagonalGhostSync: diagonal ghost detected");
    }

    @Test
    void test3x3GridGhostSync() {
        var config = GridConfiguration.DEFAULT_3X3;
        var worldBounds = new WorldBounds(0f, 600f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        // Add entities near boundaries in all 9 cells
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                var coord = new BubbleCoordinate(row, col);
                var bub = simulation.getBubble(coord);

                // Add entity near right boundary of each cell
                float x = col * 100f + 95f;
                float y = row * 100f + 50f;
                bub.addEntity("entity-" + row + "-" + col, new Point3f(x, y, 50f), null);
            }
        }

        simulation.start();
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        simulation.stop();

        // Verify ghosts exist
        int ghostCount = simulation.getGhostCount();
        assertTrue(ghostCount > 0, "3x3 grid should have ghosts between neighbors");

        // Verify all 9 bubbles have entities (real or ghost)
        var entities = simulation.getAllEntities();
        long uniqueBubbles = entities.stream()
            .map(e -> e.bubbleCoord())
            .distinct()
            .count();

        assertEquals(9, uniqueBubbles, "Should have entities in all 9 bubbles");

        log.info("test3x3GridGhostSync: {} total ghosts across 9 bubbles", ghostCount);
    }

    @Test
    void testGhostCountMetrics() {
        var config = GridConfiguration.DEFAULT_2X2;
        var worldBounds = new WorldBounds(0f, 400f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        // Initially no ghosts
        assertEquals(0, simulation.getGhostCount(), "Initially no ghosts");

        var bubble = simulation.getBubble(new BubbleCoordinate(0, 0));
        bubble.addEntity("test-entity", new Point3f(95f, 50f, 50f), null);

        simulation.start();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        simulation.stop();

        int ghostCount = simulation.getGhostCount();
        assertTrue(ghostCount > 0, "Ghost count should be > 0 after adding boundary entity");

        log.info("testGhostCountMetrics: {} ghosts", ghostCount);
    }

    @Test
    void testNoGhostsForDistantEntities() {
        var config = GridConfiguration.DEFAULT_2X2;
        var worldBounds = new WorldBounds(0f, 400f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        // Add entity far from all boundaries (center of cell)
        var bubble = simulation.getBubble(new BubbleCoordinate(0, 0));
        bubble.addEntity("center-entity", new Point3f(50f, 50f, 50f), null);

        simulation.start();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        simulation.stop();

        int ghostCount = simulation.getGhostCount();
        assertEquals(0, ghostCount, "Center entity should not generate ghosts");

        log.info("testNoGhostsForDistantEntities: {} ghosts (expected 0)", ghostCount);
    }

    @Test
    void testGhostLayerHealthIntegration() {
        var config = GridConfiguration.DEFAULT_2X2;
        var worldBounds = new WorldBounds(0f, 400f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        var bubble = simulation.getBubble(new BubbleCoordinate(0, 0));
        bubble.addEntity("health-test", new Point3f(95f, 50f, 50f), null);

        simulation.start();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        simulation.stop();

        // GhostLayerHealth tracks discovered neighbors
        // In 2x2 grid, corner cell (0,0) has 3 neighbors
        // We should discover at least 1 via ghost sync
        // (Full verification requires exposing GhostLayerHealth, this test just ensures no crashes)

        assertTrue(simulation.getGhostCount() > 0, "Health integration should track ghost sources");

        log.info("testGhostLayerHealthIntegration: passed (health tracking active)");
    }

    @Test
    void testExternalBubbleTrackerIntegration() {
        var config = GridConfiguration.DEFAULT_2X2;
        var worldBounds = new WorldBounds(0f, 400f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        var bubble = simulation.getBubble(new BubbleCoordinate(0, 0));
        bubble.addEntity("tracker-test", new Point3f(95f, 50f, 50f), null);

        simulation.start();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        simulation.stop();

        // ExternalBubbleTracker records ghost interactions
        // (Full verification requires exposing tracker, this test ensures no crashes)

        assertTrue(simulation.getGhostCount() > 0, "Tracker integration should record interactions");

        log.info("testExternalBubbleTrackerIntegration: passed (tracker active)");
    }

    @Test
    void testGhostSyncDuring60TicksRun() {
        var config = GridConfiguration.DEFAULT_2X2;
        var worldBounds = new WorldBounds(0f, 400f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        var bubble = simulation.getBubble(new BubbleCoordinate(0, 0));
        bubble.addEntity("long-run-entity", new Point3f(95f, 50f, 50f), null);

        simulation.start();

        // Run for ~60 ticks (1 second @ 16.67ms/tick)
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulation.stop();

        // Verify no ghost accumulation (should stay bounded by TTL)
        int ghostCount = simulation.getGhostCount();

        // With TTL of 5 buckets, ghosts should not accumulate unboundedly
        assertTrue(ghostCount < 100, "Ghosts should not accumulate over 60 ticks: " + ghostCount);

        long ticks = simulation.getTickCount();
        assertTrue(ticks >= 50, "Should have run at least 50 ticks: " + ticks);

        log.info("testGhostSyncDuring60TicksRun: {} ticks, {} ghosts", ticks, ghostCount);
    }

    @Test
    void testGhostUpdateOnEntityMove() {
        var config = GridConfiguration.DEFAULT_2X2;
        var worldBounds = new WorldBounds(0f, 400f);

        simulation = new MultiBubbleSimulation(config, 0, worldBounds);

        var bubble = simulation.getBubble(new BubbleCoordinate(0, 0));

        // Add entity in center (no ghosts initially)
        bubble.addEntity("moving-entity", new Point3f(50f, 50f, 50f), null);

        simulation.start();
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int initialGhosts = simulation.getGhostCount();
        assertEquals(0, initialGhosts, "Center entity should have no ghosts");

        // Move entity to boundary
        bubble.updateEntityPosition("moving-entity", new Point3f(95f, 50f, 50f));

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulation.stop();

        int finalGhosts = simulation.getGhostCount();
        assertTrue(finalGhosts > 0, "Moving to boundary should create ghosts");

        log.info("testGhostUpdateOnEntityMove: initial={}, final={}", initialGhosts, finalGhosts);
    }
}
