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

    /** Drive N deterministic single-step ticks (no start()/Thread.sleep) — fully reproducible (seeded behavior). */
    private void tickDeterministically(int ticks) {
        for (int i = 0; i < ticks; i++) {
            simulation.tick();
        }
    }

    /** Assert exact real-entity conservation and that no entity id resides in more than one bubble. */
    private void assertConservedAndUnique(int expectedRealCount) {
        var realEntities = simulation.getRealEntities();
        assertEquals(expectedRealCount, realEntities.size(),
                     "real entity count must be exactly conserved (no loss / no creation)");
        var bubblesById = new java.util.HashMap<String, java.util.Set<Object>>();
        for (var e : realEntities) {
            bubblesById.computeIfAbsent(e.id(), k -> new java.util.HashSet<>()).add(e.bubbleKey());
        }
        for (var entry : bubblesById.entrySet()) {
            assertEquals(1, entry.getValue().size(),
                         "entity " + entry.getKey() + " must reside in exactly one bubble (no duplication), "
                         + "found in " + entry.getValue());
        }
    }

    @Test
    void testSimulationRunsWithMigration() {
        // Deterministic single-stepping instead of start()+Thread.sleep (Luciferase-j6ybd).
        int initialReal = simulation.getRealEntities().size();
        tickDeterministically(2000);

        assertTrue(simulation.getTickCount() >= 2000, "simulation must have advanced the driven ticks");
        assertNotNull(simulation.getMigrationMetrics());
        // Invariant that holds whether or not a migration commits: the per-tick migration CHECK must never lose
        // or duplicate an entity. (The old assertTrue(getTotalMigrations() >= 0) was trivially true.)
        assertConservedAndUnique(initialReal);
    }

    /**
     * Exact entity conservation across a long deterministic run (replaces the RDR-004 D3-class vacuous
     * {@code finalEntities >= initialEntities * 0.9}, which tolerated dropping up to 10% of entities every run,
     * and the non-deterministic Thread.sleep). Asserts no loss AND no duplication (Luciferase-j6ybd).
     *
     * <p><b>Finding (not silent scope reduction):</b> the original AC also asked for a positive-migration
     * assertion ({@code getTotalMigrations() > 0}). That is NOT asserted here because this simulation commits
     * ZERO successful migrations through normal {@code tick()} operation — verified across fixtures up to 600
     * entities in a 15-unit world over 20,000 ticks (all yielded 0). Asserting a migration would be either
     * impossible or contrived. The likely-dead migration-commit path is filed as a separate defect bead; this
     * test pins the conservation/uniqueness invariants, which is the load-bearing guarantee regardless.
     */
    @Test
    void testNoEntityLossDuringMigration() {
        int initialReal = simulation.getRealEntities().size();
        assertTrue(initialReal > 0, "fixture must start with real entities");

        tickDeterministically(2000);

        assertConservedAndUnique(initialReal);
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
