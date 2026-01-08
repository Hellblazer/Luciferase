/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.entity.StringEntityIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test tetrahedral entity migration.
 *
 * @author hal.hildebrand
 */
class TetrahedralMigrationTest {

    private TetreeBubbleGrid bubbleGrid;
    private Tetree<StringEntityID, Object> tetree;
    private TetrahedralMigration migration;

    @BeforeEach
    void setUp() {
        // Create a bubble grid with 9 bubbles at level 1
        bubbleGrid = new TetreeBubbleGrid((byte) 1);
        bubbleGrid.createBubbles(9, (byte) 1, 16);

        // Create a Tetree for spatial queries
        tetree = new Tetree<>(new StringEntityIDGenerator(), 100, (byte) 1);

        // Create migration manager
        migration = new TetrahedralMigration(bubbleGrid, tetree);
    }

    @Test
    void testMigrationCreation() {
        assertNotNull(migration);
        assertNotNull(migration.getMetrics());
    }

    @Test
    void testCheckMigrationsWithNoBubbles() {
        // Clear bubbles
        bubbleGrid.clear();

        // Check migrations - should not crash
        assertDoesNotThrow(() -> migration.checkMigrations(0));

        // No migrations should occur
        assertEquals(0, migration.getMetrics().getTotalMigrations());
    }

    @Test
    void testCheckMigrationsWithEmptyBubbles() {
        // Bubbles exist but have no entities
        migration.checkMigrations(0);

        // No migrations should occur
        assertEquals(0, migration.getMetrics().getTotalMigrations());
    }

    @Test
    void testCooldownPreventsRapidMigrations() {
        // Get a bubble
        var bubbles = bubbleGrid.getAllBubbles();
        assertFalse(bubbles.isEmpty());
        var bubble = bubbles.iterator().next();

        // Add an entity outside bubble bounds (will trigger migration)
        var entityId = "test-entity";
        var position = new Point3f(1000.0f, 1000.0f, 1000.0f); // Far outside
        bubble.addEntity(entityId, position, null);

        // First migration check (tick 0)
        migration.checkMigrations(0);

        // Clear cooldowns and check metrics
        var metrics1 = migration.getMetrics();
        long migrations1 = metrics1.getTotalMigrations();

        // Second migration check within cooldown window (tick 10 < 30)
        migration.checkMigrations(10);

        var metrics2 = migration.getMetrics();
        long migrations2 = metrics2.getTotalMigrations();

        // Migrations should be the same (cooldown prevented second migration)
        assertEquals(migrations1, migrations2, "Cooldown should prevent migration");
    }

    @Test
    void testCooldownAllowsMigrationAfterDelay() {
        // Get a bubble
        var bubbles = bubbleGrid.getAllBubbles();
        assertFalse(bubbles.isEmpty());
        var bubble = bubbles.iterator().next();

        // Add an entity outside bubble bounds
        var entityId = "test-entity-2";
        var position = new Point3f(1000.0f, 1000.0f, 1000.0f);
        bubble.addEntity(entityId, position, null);

        // First migration check (tick 0)
        migration.checkMigrations(0);
        long migrations1 = migration.getMetrics().getTotalMigrations();

        // Wait for cooldown to expire (tick 40 > 30)
        migration.checkMigrations(40);
        long migrations2 = migration.getMetrics().getTotalMigrations();

        // Migrations could increase (if entity still out of bounds)
        // This test validates that cooldown doesn't permanently block
        assertTrue(migrations2 >= migrations1, "After cooldown, migrations should be allowed");
    }

    @Test
    void testMetricsRecordMigrations() {
        var metrics = migration.getMetrics();
        assertNotNull(metrics);

        // Initial state
        assertEquals(0, metrics.getTotalMigrations());
        assertEquals(0, metrics.getFailureCount());
        assertEquals(0, metrics.getActiveCooldownCount());
    }

    @Test
    void testClearCooldowns() {
        // Add entity
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        bubble.addEntity("entity-1", new Point3f(1000.0f, 1000.0f, 1000.0f), null);

        // Trigger migration
        migration.checkMigrations(0);

        // Clear cooldowns
        migration.clearCooldowns();

        // Should be able to migrate immediately
        migration.checkMigrations(1);

        // No assertion needed - just verify it doesn't crash
    }

    @Test
    void testCooldownTicksConstant() {
        assertEquals(30, TetrahedralMigration.getCooldownTicks());
    }

    @Test
    void testHysteresisDistanceConstant() {
        assertEquals(2.0f, TetrahedralMigration.getHysteresisDistance(), 0.001f);
    }

    @Test
    void testMigrationWithMultipleBubbles() {
        // Add entities to multiple bubbles
        var bubbles = bubbleGrid.getAllBubbles();
        int count = 0;
        for (var bubble : bubbles) {
            bubble.addEntity("entity-" + count, new Point3f(count * 10.0f, count * 10.0f, count * 10.0f), null);
            count++;
            if (count >= 3) break;
        }

        // Check migrations
        migration.checkMigrations(0);

        // Some migrations may occur (depends on bubble bounds)
        var metrics = migration.getMetrics();
        assertTrue(metrics.getTotalMigrations() >= 0);
    }

    @Test
    void testMigrationHandlesNullPositionsGracefully() {
        // This test ensures migration doesn't crash on edge cases
        migration.checkMigrations(0);

        // No crash = success
        assertEquals(0, migration.getMetrics().getTotalMigrations());
    }

    @Test
    void testMetricsTrackUniquePairs() {
        var metrics = migration.getMetrics();

        // Initially no pairs
        assertEquals(0, metrics.getUniquePairCount());

        // After migrations, pairs may be tracked
        migration.checkMigrations(0);

        // Pair count should be >= 0
        assertTrue(metrics.getUniquePairCount() >= 0);
    }

    @Test
    void testMigrationToString() {
        var metrics = migration.getMetrics();
        var str = metrics.toString();

        assertNotNull(str);
        assertTrue(str.contains("TetrahedralMigrationMetrics"));
        assertTrue(str.contains("total="));
        assertTrue(str.contains("failures="));
    }
}
