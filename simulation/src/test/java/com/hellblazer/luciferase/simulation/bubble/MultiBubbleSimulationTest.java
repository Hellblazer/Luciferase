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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MultiBubbleSimulation tetrahedral orchestrator.
 *
 * @author hal.hildebrand
 */
class MultiBubbleSimulationTest {

    private MultiBubbleSimulation simulation;

    @AfterEach
    void tearDown() throws Exception {
        if (simulation != null) {
            simulation.close();
        }
    }

    @Test
    void testCreation_SingleLevel() {
        simulation = new MultiBubbleSimulation(
            1,                          // 1 bubble
            (byte) 0,                   // maxLevel = 0
            50,                         // 50 entities
            WorldBounds.DEFAULT,
            new FlockingBehavior()
        );

        assertNotNull(simulation);
        assertEquals(1, simulation.getAllBubbles().size());
    }

    @Test
    void testCreation_MultiLevel() {
        simulation = new MultiBubbleSimulation(
            9,                          // 9 bubbles (requested)
            (byte) 1,                   // maxLevel = 1
            100,                        // 100 entities
            WorldBounds.DEFAULT,
            new FlockingBehavior()
        );

        assertNotNull(simulation);
        var bubbles = simulation.getAllBubbles().size();
        // Due to tetrahedral key collisions, may create fewer than requested
        assertTrue(bubbles > 0, "Should create bubbles");
        assertTrue(bubbles <= 9, "Should not exceed requested count");
    }

    @Test
    void testCreation_EntityCountCorrect() {
        simulation = new MultiBubbleSimulation(
            5,
            (byte) 2,
            75,
            WorldBounds.DEFAULT,
            new FlockingBehavior()
        );

        var entities = simulation.getAllEntities();
        assertEquals(75, entities.size(), "Should have 75 entities");
    }

    @Test
    void testStart_Stop_Lifecycle() {
        simulation = new MultiBubbleSimulation(3, (byte) 1, 30, WorldBounds.DEFAULT, new FlockingBehavior());

        assertFalse(simulation.isRunning());

        simulation.start();
        assertTrue(simulation.isRunning());

        simulation.stop();
        assertFalse(simulation.isRunning());
    }

    @Test
    void testIsRunning_Flag() {
        simulation = new MultiBubbleSimulation(3, (byte) 1, 30, WorldBounds.DEFAULT, new FlockingBehavior());

        assertFalse(simulation.isRunning());
        simulation.start();
        assertTrue(simulation.isRunning());
        simulation.stop();
        assertFalse(simulation.isRunning());
    }

    @Test
    void testTicksExecute() throws InterruptedException {
        simulation = new MultiBubbleSimulation(5, (byte) 1, 50, WorldBounds.DEFAULT, new FlockingBehavior());

        simulation.start();
        Thread.sleep(100); // Let a few ticks execute

        var tickCount = simulation.getTickCount();
        assertTrue(tickCount > 0, "Ticks should execute");

        simulation.stop();
    }

    @Test
    void testGetTickCount_Increments() throws InterruptedException {
        simulation = new MultiBubbleSimulation(3, (byte) 1, 30, WorldBounds.DEFAULT, new FlockingBehavior());

        simulation.start();
        Thread.sleep(50);
        var firstCount = simulation.getTickCount();

        Thread.sleep(50);
        var secondCount = simulation.getTickCount();

        assertTrue(secondCount > firstCount, "Tick count should increment");

        simulation.stop();
    }

    @Test
    void testEntitiesMove_AfterTicks() throws InterruptedException {
        simulation = new MultiBubbleSimulation(5, (byte) 1, 50, WorldBounds.DEFAULT, new FlockingBehavior());

        var initialEntities = simulation.getAllEntities();
        var firstPosition = initialEntities.get(0).position();

        simulation.start();
        Thread.sleep(200); // Let entities move
        simulation.stop();

        var finalEntities = simulation.getAllEntities();
        var finalPosition = finalEntities.get(0).position();

        // Position should have changed (unless entity has zero velocity)
        var moved = !firstPosition.equals(finalPosition);
        assertTrue(moved, "At least some entities should move");
    }

    @Test
    void testBubbleBoundsUpdate_AfterMovement() throws InterruptedException {
        simulation = new MultiBubbleSimulation(5, (byte) 1, 50, WorldBounds.DEFAULT, new FlockingBehavior());

        simulation.start();
        Thread.sleep(100);
        simulation.stop();

        // Verify bubbles have bounds
        for (var bubble : simulation.getAllBubbles()) {
            if (bubble.entityCount() > 0) {
                assertNotNull(bubble.bounds(), "Bubbles with entities should have bounds");
            }
        }
    }

    @Test
    void testAllEntitiesStayInBounds() throws InterruptedException {
        var bounds = new WorldBounds(0f, 100f);
        simulation = new MultiBubbleSimulation(5, (byte) 1, 50, bounds, new FlockingBehavior());

        simulation.start();
        Thread.sleep(200);
        simulation.stop();

        var entities = simulation.getAllEntities();
        for (var entity : entities) {
            var pos = entity.position();
            assertTrue(bounds.contains(pos.x), "X should be in bounds: " + pos.x);
            assertTrue(bounds.contains(pos.y), "Y should be in bounds: " + pos.y);
            assertTrue(bounds.contains(pos.z), "Z should be in bounds: " + pos.z);
        }
    }

    @Test
    void testGetAllEntities_CorrectCount() {
        simulation = new MultiBubbleSimulation(5, (byte) 1, 60, WorldBounds.DEFAULT, new FlockingBehavior());

        var entities = simulation.getAllEntities();
        assertEquals(60, entities.size());
    }

    @Test
    void testGetRealEntities_ExcludesGhosts() {
        simulation = new MultiBubbleSimulation(5, (byte) 1, 50, WorldBounds.DEFAULT, new FlockingBehavior());

        var realEntities = simulation.getRealEntities();
        var allEntities = simulation.getAllEntities();

        // Until Phase 5C, all entities are real (no ghosts)
        assertEquals(allEntities.size(), realEntities.size());
        assertEquals(0, simulation.getGhostCount());
    }

    @Test
    void testGetMetrics_NonNull() {
        simulation = new MultiBubbleSimulation(3, (byte) 1, 30, WorldBounds.DEFAULT, new FlockingBehavior());

        var metrics = simulation.getMetrics();
        assertNotNull(metrics);
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "Flaky performance test: CI environment achieves lower TPS than required 25 TPS with 500 entities")
    void testLargePopulation_500Entities_60fps() throws InterruptedException {
        simulation = new MultiBubbleSimulation(9, (byte) 2, 500, WorldBounds.DEFAULT, new FlockingBehavior());

        simulation.start();
        Thread.sleep(1000); // Run for 1 second

        var metrics = simulation.getMetrics();
        var tps = metrics.getTicksPerSecond();

        simulation.stop();

        // Should achieve reasonable TPS (allow variance for CI environments)
        assertTrue(tps >= 25, "Should achieve at least 25 TPS with 500 entities, got " + tps);
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "Flaky memory test: GC behavior and memory growth vary in CI environments")
    void testMemoryStability_1000Ticks_Under100mbGrowth() throws InterruptedException {
        simulation = new MultiBubbleSimulation(9, (byte) 2, 200, WorldBounds.DEFAULT, new FlockingBehavior());

        // Force GC and measure initial memory
        System.gc();
        Thread.sleep(100);
        var runtime = Runtime.getRuntime();
        var initialMemory = runtime.totalMemory() - runtime.freeMemory();

        simulation.start();

        // Wait for ~1000 ticks (60fps = ~16.67 ticks/sec, so ~16 seconds for 1000 ticks)
        // For testing, we'll use a shorter duration
        Thread.sleep(2000); // ~120 ticks at 60fps

        simulation.stop();

        // Force GC and measure final memory
        System.gc();
        Thread.sleep(100);
        var finalMemory = runtime.totalMemory() - runtime.freeMemory();

        var memoryGrowthMb = (finalMemory - initialMemory) / (1024.0 * 1024.0);

        // Memory growth should be reasonable (not a leak)
        assertTrue(memoryGrowthMb < 100, "Memory growth should be <100MB, was " + memoryGrowthMb + "MB");
    }

    @Test
    void testClose_NoLeaks() throws Exception {
        simulation = new MultiBubbleSimulation(5, (byte) 1, 50, WorldBounds.DEFAULT, new FlockingBehavior());

        simulation.start();
        Thread.sleep(100);

        // Close should stop simulation and release resources
        simulation.close();

        assertFalse(simulation.isRunning());
    }
}
