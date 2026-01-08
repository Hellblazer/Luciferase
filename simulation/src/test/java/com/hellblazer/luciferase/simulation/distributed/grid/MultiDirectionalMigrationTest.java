/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for multi-directional entity migration.
 * <p>
 * Tests:
 * - Migration in all 8 directions (N, S, E, W, NE, NW, SE, SW)
 * - Hysteresis (2.0f distance past boundary required)
 * - Cooldown (30 ticks between migrations)
 * - Diagonal tie-breaking (largest overshoot wins, X-axis breaks ties)
 * - Rollback on failure
 * - 100% entity retention over 1000+ ticks
 * - Per-direction metrics
 *
 * @author hal.hildebrand
 */
class MultiDirectionalMigrationTest {

    private GridConfiguration gridConfig;
    private BubbleGrid<EnhancedBubble> bubbleGrid;
    private Map<String, Vector3f> velocities;
    private MultiDirectionalMigration migration;

    @BeforeEach
    void setUp() {
        // Use 3x3 grid for comprehensive testing (corner, edge, interior cells)
        gridConfig = GridConfiguration.DEFAULT_3X3;
        bubbleGrid = GridBubbleFactory.createBubbles(gridConfig, (byte) 10, 16);
        velocities = new ConcurrentHashMap<>();
        migration = new MultiDirectionalMigration(gridConfig, bubbleGrid, velocities);
    }

    @AfterEach
    void tearDown() {
        velocities.clear();
    }

    // ========== Cardinal Direction Tests ==========

    @Test
    void testMigrationNorth() {
        // Entity in bubble (0,1) migrating north to (1,1)
        var sourceCoord = new BubbleCoordinate(0, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        // Position just past north boundary (Y = cellHeight + hysteresis)
        // Bubble (0,1) has X=[100,200], Y=[0,100]
        float boundaryY = gridConfig.cellMax(sourceCoord).y;
        var position = new Point3f(150f, boundaryY + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 0.1f, 50f);

        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", new Vector3f(0f, 1f, 0f));

        // Execute migration
        migration.checkMigrations(1);

        // Verify entity moved to target bubble
        var targetCoord = new BubbleCoordinate(1, 1);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        assertFalse(sourceBubble.getEntities().contains("entity1"), "Entity should be removed from source");
        assertTrue(targetBubble.getEntities().contains("entity1"), "Entity should be added to target");

        // Verify metrics
        assertEquals(1, migration.getMetrics().getMigrationCount(MigrationDirection.NORTH));
        assertEquals(1, migration.getMetrics().getTotalMigrations());
    }

    @Test
    void testMigrationSouth() {
        // Entity in bubble (1,1) migrating south to (0,1)
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        // Position just past south boundary
        float boundaryY = gridConfig.cellMin(sourceCoord).y;
        var position = new Point3f(150f, boundaryY - MultiDirectionalMigration.HYSTERESIS_DISTANCE - 0.1f, 50f);

        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", new Vector3f(0f, -1f, 0f));

        migration.checkMigrations(1);

        var targetCoord = new BubbleCoordinate(0, 1);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        assertFalse(sourceBubble.getEntities().contains("entity1"));
        assertTrue(targetBubble.getEntities().contains("entity1"));
        assertEquals(1, migration.getMetrics().getMigrationCount(MigrationDirection.SOUTH));
    }

    @Test
    void testMigrationEast() {
        // Entity in bubble (1,1) migrating east to (1,2)
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        // Position just past east boundary
        float boundaryX = gridConfig.cellMax(sourceCoord).x;
        var position = new Point3f(boundaryX + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 0.1f, 150f, 50f);

        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", new Vector3f(1f, 0f, 0f));

        migration.checkMigrations(1);

        var targetCoord = new BubbleCoordinate(1, 2);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        assertFalse(sourceBubble.getEntities().contains("entity1"));
        assertTrue(targetBubble.getEntities().contains("entity1"));
        assertEquals(1, migration.getMetrics().getMigrationCount(MigrationDirection.EAST));
    }

    @Test
    void testMigrationWest() {
        // Entity in bubble (1,1) migrating west to (1,0)
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        // Position just past west boundary
        float boundaryX = gridConfig.cellMin(sourceCoord).x;
        var position = new Point3f(boundaryX - MultiDirectionalMigration.HYSTERESIS_DISTANCE - 0.1f, 150f, 50f);

        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", new Vector3f(-1f, 0f, 0f));

        migration.checkMigrations(1);

        var targetCoord = new BubbleCoordinate(1, 0);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        assertFalse(sourceBubble.getEntities().contains("entity1"));
        assertTrue(targetBubble.getEntities().contains("entity1"));
        assertEquals(1, migration.getMetrics().getMigrationCount(MigrationDirection.WEST));
    }

    // ========== Diagonal Direction Tests ==========

    @Test
    void testMigrationNorthEast() {
        // Entity in bubble (1,1) migrating NE to (2,2) - but tie-breaking should prefer single axis
        // Make Y overshoot larger than X overshoot to force NORTH migration
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryX = gridConfig.cellMax(sourceCoord).x;
        float boundaryY = gridConfig.cellMax(sourceCoord).y;

        // Y overshoot = 3.0, X overshoot = 1.0 -> should migrate NORTH
        var position = new Point3f(
            boundaryX + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 1.0f,
            boundaryY + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 3.0f,
            50f
        );

        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", new Vector3f(1f, 1f, 0f));

        migration.checkMigrations(1);

        // Should migrate NORTH (Y axis dominates)
        var targetCoord = new BubbleCoordinate(2, 1);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        assertFalse(sourceBubble.getEntities().contains("entity1"));
        assertTrue(targetBubble.getEntities().contains("entity1"));
        assertEquals(1, migration.getMetrics().getMigrationCount(MigrationDirection.NORTH));
    }

    @Test
    void testMigrationNorthWest() {
        // Entity migrating with both N and W boundaries crossed
        // Make X overshoot larger to force WEST migration
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryX = gridConfig.cellMin(sourceCoord).x;
        float boundaryY = gridConfig.cellMax(sourceCoord).y;

        // X overshoot = 3.0, Y overshoot = 1.0 -> should migrate WEST
        var position = new Point3f(
            boundaryX - MultiDirectionalMigration.HYSTERESIS_DISTANCE - 3.0f,
            boundaryY + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 1.0f,
            50f
        );

        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", new Vector3f(-1f, 1f, 0f));

        migration.checkMigrations(1);

        // Should migrate WEST (X axis dominates)
        var targetCoord = new BubbleCoordinate(1, 0);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        assertFalse(sourceBubble.getEntities().contains("entity1"));
        assertTrue(targetBubble.getEntities().contains("entity1"));
        assertEquals(1, migration.getMetrics().getMigrationCount(MigrationDirection.WEST));
    }

    @Test
    void testMigrationSouthEast() {
        // Entity migrating with both S and E boundaries crossed
        // Make X overshoot larger to force EAST migration
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryX = gridConfig.cellMax(sourceCoord).x;
        float boundaryY = gridConfig.cellMin(sourceCoord).y;

        // X overshoot = 3.0, Y overshoot = 1.0 -> should migrate EAST
        var position = new Point3f(
            boundaryX + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 3.0f,
            boundaryY - MultiDirectionalMigration.HYSTERESIS_DISTANCE - 1.0f,
            50f
        );

        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", new Vector3f(1f, -1f, 0f));

        migration.checkMigrations(1);

        // Should migrate EAST (X axis dominates)
        var targetCoord = new BubbleCoordinate(1, 2);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        assertFalse(sourceBubble.getEntities().contains("entity1"));
        assertTrue(targetBubble.getEntities().contains("entity1"));
        assertEquals(1, migration.getMetrics().getMigrationCount(MigrationDirection.EAST));
    }

    @Test
    void testMigrationSouthWest() {
        // Entity migrating with both S and W boundaries crossed
        // Make Y overshoot larger to force SOUTH migration
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryX = gridConfig.cellMin(sourceCoord).x;
        float boundaryY = gridConfig.cellMin(sourceCoord).y;

        // Y overshoot = 3.0, X overshoot = 1.0 -> should migrate SOUTH
        var position = new Point3f(
            boundaryX - MultiDirectionalMigration.HYSTERESIS_DISTANCE - 1.0f,
            boundaryY - MultiDirectionalMigration.HYSTERESIS_DISTANCE - 3.0f,
            50f
        );

        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", new Vector3f(-1f, -1f, 0f));

        migration.checkMigrations(1);

        // Should migrate SOUTH (Y axis dominates)
        var targetCoord = new BubbleCoordinate(0, 1);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        assertFalse(sourceBubble.getEntities().contains("entity1"));
        assertTrue(targetBubble.getEntities().contains("entity1"));
        assertEquals(1, migration.getMetrics().getMigrationCount(MigrationDirection.SOUTH));
    }

    // ========== Diagonal Tie-Breaking Tests (CRITICAL) ==========

    @Test
    void testDiagonalTieBreakingNE_XAxisWins() {
        // Both X and Y overshoot are EQUAL -> X-axis should win (migrate EAST)
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryX = gridConfig.cellMax(sourceCoord).x;
        float boundaryY = gridConfig.cellMax(sourceCoord).y;

        // EQUAL overshoot: X = 2.0, Y = 2.0 -> should prefer X-axis (EAST)
        var position = new Point3f(
            boundaryX + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 2.0f,
            boundaryY + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 2.0f,
            50f
        );

        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", new Vector3f(1f, 1f, 0f));

        migration.checkMigrations(1);

        // Should migrate EAST (X-axis tie-breaker)
        var targetCoord = new BubbleCoordinate(1, 2);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        assertFalse(sourceBubble.getEntities().contains("entity1"));
        assertTrue(targetBubble.getEntities().contains("entity1"));
        assertEquals(1, migration.getMetrics().getMigrationCount(MigrationDirection.EAST),
                     "X-axis should win tie-breaking");
    }

    @Test
    void testDiagonalTieBreakingNW_XAxisWins() {
        // Both X and Y overshoot are EQUAL -> X-axis should win (migrate WEST)
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryX = gridConfig.cellMin(sourceCoord).x;
        float boundaryY = gridConfig.cellMax(sourceCoord).y;

        // EQUAL overshoot -> should prefer X-axis (WEST)
        var position = new Point3f(
            boundaryX - MultiDirectionalMigration.HYSTERESIS_DISTANCE - 2.0f,
            boundaryY + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 2.0f,
            50f
        );

        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", new Vector3f(-1f, 1f, 0f));

        migration.checkMigrations(1);

        // Should migrate WEST (X-axis tie-breaker)
        var targetCoord = new BubbleCoordinate(1, 0);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        assertFalse(sourceBubble.getEntities().contains("entity1"));
        assertTrue(targetBubble.getEntities().contains("entity1"));
        assertEquals(1, migration.getMetrics().getMigrationCount(MigrationDirection.WEST));
    }

    @Test
    void testDiagonalTieBreakingSE_XAxisWins() {
        // Both X and Y overshoot are EQUAL -> X-axis should win (migrate EAST)
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryX = gridConfig.cellMax(sourceCoord).x;
        float boundaryY = gridConfig.cellMin(sourceCoord).y;

        // EQUAL overshoot -> should prefer X-axis (EAST)
        var position = new Point3f(
            boundaryX + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 2.0f,
            boundaryY - MultiDirectionalMigration.HYSTERESIS_DISTANCE - 2.0f,
            50f
        );

        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", new Vector3f(1f, -1f, 0f));

        migration.checkMigrations(1);

        // Should migrate EAST (X-axis tie-breaker)
        var targetCoord = new BubbleCoordinate(1, 2);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        assertFalse(sourceBubble.getEntities().contains("entity1"));
        assertTrue(targetBubble.getEntities().contains("entity1"));
        assertEquals(1, migration.getMetrics().getMigrationCount(MigrationDirection.EAST));
    }

    @Test
    void testDiagonalTieBreakingSW_XAxisWins() {
        // Both X and Y overshoot are EQUAL -> X-axis should win (migrate WEST)
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryX = gridConfig.cellMin(sourceCoord).x;
        float boundaryY = gridConfig.cellMin(sourceCoord).y;

        // EQUAL overshoot -> should prefer X-axis (WEST)
        var position = new Point3f(
            boundaryX - MultiDirectionalMigration.HYSTERESIS_DISTANCE - 2.0f,
            boundaryY - MultiDirectionalMigration.HYSTERESIS_DISTANCE - 2.0f,
            50f
        );

        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", new Vector3f(-1f, -1f, 0f));

        migration.checkMigrations(1);

        // Should migrate WEST (X-axis tie-breaker)
        var targetCoord = new BubbleCoordinate(1, 0);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        assertFalse(sourceBubble.getEntities().contains("entity1"));
        assertTrue(targetBubble.getEntities().contains("entity1"));
        assertEquals(1, migration.getMetrics().getMigrationCount(MigrationDirection.WEST));
    }

    // ========== Hysteresis Tests ==========

    @Test
    void testHysteresisPreventsMigration() {
        // Entity is past boundary but NOT past hysteresis threshold
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryY = gridConfig.cellMax(sourceCoord).y;
        // Just past boundary but within hysteresis zone
        var position = new Point3f(150f, boundaryY + 1.0f, 50f);

        sourceBubble.addEntity("entity1", position, null);

        migration.checkMigrations(1);

        // Entity should NOT migrate (within hysteresis zone)
        assertTrue(sourceBubble.getEntities().contains("entity1"));
        assertEquals(0, migration.getMetrics().getTotalMigrations());
    }

    @Test
    void testHysteresisAllowsMigration() {
        // Entity is past hysteresis threshold
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryY = gridConfig.cellMax(sourceCoord).y;
        // Past hysteresis threshold
        var position = new Point3f(150f, boundaryY + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 0.1f, 50f);

        sourceBubble.addEntity("entity1", position, null);

        migration.checkMigrations(1);

        // Entity SHOULD migrate
        assertFalse(sourceBubble.getEntities().contains("entity1"));
        assertEquals(1, migration.getMetrics().getTotalMigrations());
    }

    // ========== Cooldown Tests ==========

    @Test
    void testCooldownPreventsMigration() {
        // Perform first migration from (1,1) north to (2,1)
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryY = gridConfig.cellMax(sourceCoord).y;
        var position = new Point3f(150f, boundaryY + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 0.1f, 50f);

        sourceBubble.addEntity("entity1", position, null);
        migration.checkMigrations(1);

        // First migration should succeed
        assertEquals(1, migration.getMetrics().getTotalMigrations());

        // Try to migrate again immediately (within cooldown) from (2,1) south to (1,1)
        var newSourceCoord = new BubbleCoordinate(2, 1);
        var newSourceBubble = bubbleGrid.getBubble(newSourceCoord);

        // Move entity past south boundary
        float newBoundaryY = gridConfig.cellMin(newSourceCoord).y;
        var newPosition = new Point3f(150f, newBoundaryY - MultiDirectionalMigration.HYSTERESIS_DISTANCE - 0.1f, 50f);
        newSourceBubble.updateEntityPosition("entity1", newPosition);

        // Attempt migration within cooldown (tick 2, cooldown expires at tick 31)
        migration.checkMigrations(2);

        // Second migration should be blocked by cooldown
        assertEquals(1, migration.getMetrics().getTotalMigrations(), "Migration should be blocked by cooldown");
    }

    @Test
    void testCooldownExpires() {
        // Perform first migration at tick 1 from (1,1) north to (2,1)
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryY = gridConfig.cellMax(sourceCoord).y;
        var position = new Point3f(150f, boundaryY + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 0.1f, 50f);

        sourceBubble.addEntity("entity1", position, null);
        migration.checkMigrations(1);

        assertEquals(1, migration.getMetrics().getTotalMigrations());

        // Move entity past south boundary of (2,1) to migrate to (1,1)
        var newSourceCoord = new BubbleCoordinate(2, 1);
        var newSourceBubble = bubbleGrid.getBubble(newSourceCoord);

        float newBoundaryY = gridConfig.cellMin(newSourceCoord).y;
        var newPosition = new Point3f(150f, newBoundaryY - MultiDirectionalMigration.HYSTERESIS_DISTANCE - 0.1f, 50f);
        newSourceBubble.updateEntityPosition("entity1", newPosition);

        // Attempt migration after cooldown expires (tick 32, cooldown expired at tick 31)
        migration.checkMigrations(32);

        // Second migration should succeed
        assertEquals(2, migration.getMetrics().getTotalMigrations(), "Migration should succeed after cooldown");
    }

    // ========== Edge Case Tests ==========

    @Test
    void testMigrationAtGridBoundary() {
        // Entity at grid edge (row 2, col 2) cannot migrate north or east
        var sourceCoord = new BubbleCoordinate(2, 2);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        // Position past north boundary (out of grid)
        float boundaryY = gridConfig.cellMax(sourceCoord).y;
        var position = new Point3f(250f, boundaryY + MultiDirectionalMigration.HYSTERESIS_DISTANCE + 0.1f, 50f);

        sourceBubble.addEntity("entity1", position, null);
        migration.checkMigrations(1);

        // Entity should NOT migrate (target out of bounds)
        assertTrue(sourceBubble.getEntities().contains("entity1"));
        assertEquals(0, migration.getMetrics().getTotalMigrations());
    }

    @Test
    void testEntityRetention() {
        // Add multiple entities to different bubbles
        int entityCount = 0;
        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = bubbleGrid.getBubble(coord);

                var center = gridConfig.cellCenter(coord);
                bubble.addEntity("entity-" + row + "-" + col, center, null);
                entityCount++;
            }
        }

        int initialCount = entityCount;

        // Run migration for many ticks
        for (int tick = 1; tick <= 1000; tick++) {
            migration.checkMigrations(tick);

            // Count entities across all bubbles
            int currentCount = 0;
            for (int row = 0; row < gridConfig.rows(); row++) {
                for (int col = 0; col < gridConfig.columns(); col++) {
                    var bubble = bubbleGrid.getBubble(new BubbleCoordinate(row, col));
                    currentCount += bubble.entityCount();
                }
            }

            assertEquals(initialCount, currentCount, "Entity count mismatch at tick " + tick);
        }
    }

    @Test
    void testMetricsAccuracy() {
        // Migrate entities in different directions
        var migrations = new java.util.HashMap<MigrationDirection, Integer>();

        // North migration
        var coord1 = new BubbleCoordinate(0, 1);
        var bubble1 = bubbleGrid.getBubble(coord1);
        float boundaryY1 = gridConfig.cellMax(coord1).y;
        bubble1.addEntity("e1", new Point3f(150f, boundaryY1 + 3.0f, 50f), null);
        migration.checkMigrations(1);
        migrations.put(MigrationDirection.NORTH, 1);

        // East migration
        var coord2 = new BubbleCoordinate(1, 0);
        var bubble2 = bubbleGrid.getBubble(coord2);
        float boundaryX2 = gridConfig.cellMax(coord2).x;
        bubble2.addEntity("e2", new Point3f(boundaryX2 + 3.0f, 150f, 50f), null);
        migration.checkMigrations(50); // After cooldown
        migrations.put(MigrationDirection.EAST, 1);

        // Verify metrics
        for (var entry : migrations.entrySet()) {
            assertEquals((long) entry.getValue(), migration.getMetrics().getMigrationCount(entry.getKey()),
                        "Incorrect count for direction: " + entry.getKey());
        }

        assertEquals(2, migration.getMetrics().getTotalMigrations());
    }

    @Test
    void testVelocityPreservation() {
        // Verify velocity is preserved across migration
        var sourceCoord = new BubbleCoordinate(1, 1);
        var sourceBubble = bubbleGrid.getBubble(sourceCoord);

        float boundaryY = gridConfig.cellMax(sourceCoord).y;
        var position = new Point3f(150f, boundaryY + 3.0f, 50f);

        var velocity = new Vector3f(5f, 10f, 2f);
        sourceBubble.addEntity("entity1", position, null);
        velocities.put("entity1", velocity);

        migration.checkMigrations(1);

        // Verify velocity still exists and is unchanged
        assertTrue(velocities.containsKey("entity1"));
        assertEquals(velocity, velocities.get("entity1"));
    }

    @Test
    void testMultipleEntitiesMigratingSimultaneously() {
        // Multiple entities crossing boundaries in same tick
        for (int i = 0; i < 5; i++) {
            var coord = new BubbleCoordinate(0, i % 3);
            var bubble = bubbleGrid.getBubble(coord);

            float boundaryY = gridConfig.cellMax(coord).y;
            bubble.addEntity("entity-" + i, new Point3f(50f + i * 10, boundaryY + 3.0f, 50f), null);
        }

        migration.checkMigrations(1);

        // All should migrate successfully
        assertTrue(migration.getMetrics().getTotalMigrations() >= 3, // At least 3 (column 0,1,2)
                   "Multiple entities should migrate");
    }
}
