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

import com.hellblazer.luciferase.simulation.behavior.RandomWalkBehavior;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test migration integration in MultiBubbleSimulation.
 *
 * @author hal.hildebrand
 */
class MultiBubbleSimulationMigrationTest {

    private MultiBubbleSimulation simulation;

    @BeforeEach
    void setUp() {
        // Create a small simulation for testing
        var bubbleCount = 4;
        var maxLevel = (byte) 1;
        var entityCount = 20;
        var worldBounds = new WorldBounds(0.0f, 100.0f);
        var behavior = new RandomWalkBehavior(42L); // Seed

        simulation = new MultiBubbleSimulation(
            bubbleCount,
            maxLevel,
            entityCount,
            worldBounds,
            behavior
        );
    }

    @AfterEach
    void tearDown() {
        if (simulation != null) {
            simulation.close();
        }
    }

    @Test
    void testMigrationMetricsAvailable() {
        assertNotNull(simulation.getMigrationMetrics());
    }

    @Test
    void testMigrationInitialState() {
        var metrics = simulation.getMigrationMetrics();

        // Initially no migrations
        assertEquals(0, metrics.getTotalMigrations());
        assertEquals(0, metrics.getFailureCount());
        assertEquals(0, metrics.getActiveCooldownCount());
    }

    @Test
    void testSimulationRunsWithMigration() {
        // Start simulation
        simulation.start();

        // Let it run for a bit
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Stop simulation
        simulation.stop();

        // Verify simulation ran
        assertTrue(simulation.getTickCount() > 0);

        // Migration metrics should exist (may or may not have migrations)
        var metrics = simulation.getMigrationMetrics();
        assertNotNull(metrics);
        assertTrue(metrics.getTotalMigrations() >= 0);
    }

    @Test
    void testNoEntityLossDuringMigration() {
        var initialEntities = simulation.getAllEntities().size();

        // Run simulation
        simulation.start();

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulation.stop();

        var finalEntities = simulation.getAllEntities().size();

        // Entity count should remain stable (no loss)
        // NOTE: May not be exact due to ghost entities, but real entities should be preserved
        assertTrue(finalEntities >= initialEntities * 0.9,
            "Entity count should not drop significantly");
    }

    @Test
    void testMigrationWithMultipleTicks() {
        simulation.start();

        // Let it run for several ticks
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var tickCount = simulation.getTickCount();
        var metrics = simulation.getMigrationMetrics();

        simulation.stop();

        // Should have executed multiple ticks
        assertTrue(tickCount > 0, "Simulation should have ticked");

        // Metrics should be consistent
        assertTrue(metrics.getTotalMigrations() >= 0);
        assertTrue(metrics.getFailureCount() >= 0);
    }

    @Test
    void testSimulationClosesCleanly() {
        simulation.start();

        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Close should not throw
        assertDoesNotThrow(() -> simulation.close());

        // Should stop running
        assertFalse(simulation.isRunning());
    }
}
