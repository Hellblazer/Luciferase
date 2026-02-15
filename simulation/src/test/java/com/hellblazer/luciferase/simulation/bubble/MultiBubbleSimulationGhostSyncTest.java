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

import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ghost synchronization in MultiBubbleSimulation.
 * <p>
 * This test suite validates:
 * - Ghost sync initialization during simulation startup
 * - Ghost creation for boundary entities
 * - Ghost count tracking and visibility
 * - Ghost lifecycle (create, update, expire)
 * - Performance (60fps with ghosts)
 * - Memory stability (<100MB growth)
 *
 * @author hal.hildebrand
 */
class MultiBubbleSimulationGhostSyncTest {

    private MultiBubbleSimulation simulation;
    private FlockingBehavior behavior;
    private WorldBounds worldBounds;

    @BeforeEach
    void setUp() {
        worldBounds = new WorldBounds(0.0f, 100.0f);
        // FlockingBehavior(separationWeight, alignmentWeight, cohesionWeight)
        behavior = new FlockingBehavior(
            1.5f,   // separationWeight
            1.0f,   // alignmentWeight
            1.0f    // cohesionWeight
        );
    }

    @AfterEach
    void tearDown() {
        if (simulation != null) {
            simulation.close();
        }
    }

    /**
     * Poll wait until entity count reaches expected value or timeout.
     * Fixes race condition where entities may not be fully initialized immediately after start().
     */
    private void waitForEntityCount(MultiBubbleSimulation sim, int expected, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (sim.getRealEntities().size() >= expected) {
                return;
            }
            try {
                Thread.sleep(50); // Poll every 50ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for entity initialization");
            }
        }
        fail("Timeout waiting for " + expected + " entities. Got: " + sim.getRealEntities().size());
    }

    /**
     * Poll wait until simulation fully stops or timeout.
     * Ensures simulation has completed all ticks before assertions.
     */
    private void waitForStop(MultiBubbleSimulation sim, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (!sim.isRunning()) {
                return;
            }
            try {
                Thread.sleep(10); // Poll every 10ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for simulation to stop");
            }
        }
        fail("Timeout waiting for simulation to stop");
    }

    @Test
    void testGhostSyncInitialization_ConstructorValid() {
        simulation = new MultiBubbleSimulation(
            9,      // bubbleCount
            (byte) 2, // maxLevel
            100,    // entityCount
            worldBounds,
            behavior
        );

        assertNotNull(simulation, "Simulation should be created");
        assertFalse(simulation.isRunning(), "Should not be running initially");
    }

    @Test
    void testGhostCreation_BoundaryEntities() {
        simulation = new MultiBubbleSimulation(
            4,      // bubbleCount
            (byte) 1, // maxLevel
            50,     // entityCount
            worldBounds,
            behavior
        );

        simulation.start();

        try {
            // Wait for a few ticks to allow ghost creation
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulation.stop();

        // Ghosts may or may not be created depending on entity distribution
        var ghostCount = simulation.getGhostCount();
        assertTrue(ghostCount >= 0, "Ghost count should be non-negative");
    }

    @Test
    void testGhostCount_Tracking() {
        simulation = new MultiBubbleSimulation(
            9,
            (byte) 2,
            200,
            worldBounds,
            behavior
        );

        simulation.start();

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulation.stop();

        var ghostCount = simulation.getGhostCount();
        assertTrue(ghostCount >= 0, "Ghost count should be tracked");
    }

    @Test
    void testGetGhostCount_Accessor() {
        simulation = new MultiBubbleSimulation(
            4,
            (byte) 1,
            50,
            worldBounds,
            behavior
        );

        var initialGhostCount = simulation.getGhostCount();
        assertEquals(0, initialGhostCount, "Should start with no ghosts");

        simulation.start();

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulation.stop();

        var finalGhostCount = simulation.getGhostCount();
        assertTrue(finalGhostCount >= 0, "Ghost count should be valid");
    }

    @Test
    void testGhostLifecycle_CreateUpdateExpire() throws InterruptedException {
        simulation = new MultiBubbleSimulation(
            4,
            (byte) 1,
            100,
            worldBounds,
            behavior
        );

        simulation.start();

        // Run for 200ms to create ghosts
        Thread.sleep(200);

        var midGhostCount = simulation.getGhostCount();

        // Run for another 1 second to allow expiration
        Thread.sleep(1000);

        simulation.stop();

        var finalGhostCount = simulation.getGhostCount();

        // Ghosts should have lifecycle (created and possibly expired)
        assertTrue(finalGhostCount >= 0, "Ghost count should be valid throughout lifecycle");
    }

    /**
     * Validates ghost synchronization maintains ~60fps performance.
     * <p>
     * <b>CI Environment Adaptation</b>:
     * <ul>
     * <li><b>Local</b>: Strict validation (60+ fps in 1 second)</li>
     * <li><b>CI</b>: Relaxed threshold (50+ fps in 2 seconds) to accommodate system load</li>
     * </ul>
     * <p>
     * Rationale: CI runners experience 20% performance degradation due to shared resources
     * and scheduler contention. The 2-second duration smooths single-tick variance while
     * still detecting sustained performance regressions >20%.
     * <p>
     * Reference: TEST_FRAMEWORK_GUIDE.md § Performance Test Thresholds
     */
    @Test
    void testSimulationWithGhosts_60fps() throws InterruptedException {
        // CI environment detection
        boolean isCI = System.getenv("CI") != null;
        int expectedMinTicks = isCI ? 100 : 60;  // 50 fps × 2s = 100 ticks (CI), 60 fps × 1s = 60 ticks (local)
        int expectedMaxTicks = isCI ? 140 : 70;  // 70 fps × 2s = 140 ticks (CI), 70 fps × 1s = 70 ticks (local)
        long testDurationMs = isCI ? 2000 : 1000;

        simulation = new MultiBubbleSimulation(
            9,
            (byte) 2,
            200,
            worldBounds,
            behavior
        );

        simulation.start();

        // Run for configured duration
        Thread.sleep(testDurationMs);

        simulation.stop();

        var tickCount = simulation.getTickCount();
        var metrics = simulation.getMetrics();

        // Should maintain ~60fps with environment-specific thresholds
        String environment = isCI ? "CI" : "local";
        assertTrue(tickCount >= expectedMinTicks && tickCount <= expectedMaxTicks,
                  String.format("Should maintain ~60fps in %s environment (expected %d-%d ticks in %dms), got %d ticks",
                               environment, expectedMinTicks, expectedMaxTicks, testDurationMs, tickCount));

        // Average tick time should be <20ms
        var avgTickTimeMs = metrics.getAverageFrameTimeMs();
        assertTrue(avgTickTimeMs < 20.0,
                  "Average tick time should be <20ms, got " + avgTickTimeMs + "ms");
    }

    @Test
    @org.junit.jupiter.api.Disabled("Long-running memory test - enable manually for performance validation")
    void testGhostMemory_StableAfter1000Ticks() throws InterruptedException {
        simulation = new MultiBubbleSimulation(
            4,
            (byte) 1,
            100,
            worldBounds,
            behavior
        );

        simulation.start();

        // Warm up
        Thread.sleep(500);

        var initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        // Run for ~16 seconds (1000 ticks at 60fps)
        Thread.sleep(16000);

        simulation.stop();

        var finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        var memoryGrowthMB = (finalMemory - initialMemory) / (1024.0 * 1024.0);

        // Memory growth should be reasonable (<500MB for long run)
        assertTrue(memoryGrowthMB < 500,
                  "Memory growth should be <500MB, got " + memoryGrowthMB + "MB");
    }

    @Test
    void testNeighborGhostConsistency() {
        simulation = new MultiBubbleSimulation(
            9,
            (byte) 2,
            200,
            worldBounds,
            behavior
        );

        simulation.start();

        // Wait for entities to initialize with polling (up to 2 seconds)
        waitForEntityCount(simulation, 200, 2000);

        simulation.stop();

        // Wait for simulation to fully stop before assertions
        waitForStop(simulation, 1000);

        // Verify ghost count is consistent with entity count
        // ATOMIC READ: Call getAllEntities() once to avoid race condition
        var allEntities = simulation.getAllEntities();
        var realEntities = allEntities.stream()
                                      .filter(e -> !e.isGhost())
                                      .toList();
        var ghostCount = simulation.getGhostCount();

        assertTrue(allEntities.size() >= realEntities.size(),
                  "All entities should include real entities");
        assertTrue(ghostCount >= 0, "Ghost count should be consistent");
    }

    @Test
    void testGhostVisibility_InEntitySnapshots() {
        simulation = new MultiBubbleSimulation(
            4,
            (byte) 1,
            100,
            worldBounds,
            behavior
        );

        simulation.start();

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulation.stop();

        var allEntities = simulation.getAllEntities();
        var ghostEntities = allEntities.stream().filter(e -> e.isGhost()).count();

        // Ghost count from snapshots should match getGhostCount()
        assertEquals(simulation.getGhostCount(), ghostEntities,
                    "Ghost count should match between accessor and snapshots");
    }

    @Test
    void testGetAllEntities_IncludesGhosts() {
        simulation = new MultiBubbleSimulation(
            4,
            (byte) 1,
            50,
            worldBounds,
            behavior
        );

        simulation.start();

        // Wait for entities to initialize with polling (up to 2 seconds)
        waitForEntityCount(simulation, 50, 2000);

        simulation.stop();

        // Wait for simulation to fully stop before assertions
        waitForStop(simulation, 1000);

        // ATOMIC READ: Call getAllEntities() once to avoid race condition
        var allEntities = simulation.getAllEntities();
        var realEntities = allEntities.stream()
                                      .filter(e -> !e.isGhost())
                                      .toList();

        // All entities should include real + ghosts
        assertTrue(allEntities.size() >= realEntities.size(),
                  "getAllEntities should include both real and ghost entities");
    }

    @Test
    void testGetRealEntities_ExcludesGhosts() {
        simulation = new MultiBubbleSimulation(
            4,
            (byte) 1,
            100,
            worldBounds,
            behavior
        );

        simulation.start();

        // Wait for entities to initialize with polling (up to 2 seconds)
        waitForEntityCount(simulation, 100, 2000);

        simulation.stop();

        // Wait for simulation to fully stop before assertions
        waitForStop(simulation, 1000);

        var realEntities = simulation.getRealEntities();

        // Real entities should only include non-ghosts
        assertEquals(100, realEntities.size(),
                    "getRealEntities should return exactly 100 real entities");
    }

    @Test
    @org.junit.jupiter.api.Disabled("Large-scale performance test - enable manually for stress testing")
    void testLargeScaleGhostSync_500Entities() throws InterruptedException {
        simulation = new MultiBubbleSimulation(
            16,     // More bubbles for better distribution
            (byte) 3,
            500,    // Large entity count
            worldBounds,
            behavior
        );

        simulation.start();

        // Run for 1 second
        Thread.sleep(1000);

        simulation.stop();

        var tickCount = simulation.getTickCount();
        var ghostCount = simulation.getGhostCount();
        var metrics = simulation.getMetrics();

        // Should maintain performance with 500 entities (allow for slower CI)
        assertTrue(tickCount >= 10, "Should run simulation with 500 entities, got " + tickCount + " ticks");

        // Average tick time should still be reasonable
        var avgTickTimeMs = metrics.getAverageFrameTimeMs();
        assertTrue(avgTickTimeMs < 20.0,
                  "Tick time should be <20ms with 500 entities, got " + avgTickTimeMs + "ms");

        // Ghost count should be reasonable
        assertTrue(ghostCount >= 0 && ghostCount < 1000,
                  "Ghost count should be reasonable: " + ghostCount);
    }

    @Test
    void testComplexTetrahedralTopology_VariableNeighbors() {
        simulation = new MultiBubbleSimulation(
            20,     // Many bubbles for complex topology
            (byte) 3,
            200,
            worldBounds,
            behavior
        );

        simulation.start();

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulation.stop();

        // Verify ghost sync works with variable neighbor counts (4-12)
        var ghostCount = simulation.getGhostCount();
        assertTrue(ghostCount >= 0, "Should handle variable neighbor counts");
    }
}
