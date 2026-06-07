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
import com.hellblazer.luciferase.simulation.behavior.RandomWalkBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.MultiBubbleSimulation;
import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
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
        // RDR-015 Option B: the grid is a single-level partition tiling the world domain, so the bubble
        // count is determined by the tiling at the chosen partition level, not by the requested count
        // (which is now a granularity hint). It is never zero and never the L0 root catch-all.
        assertTrue(simulation.getAllBubbles().size() > 0, "partition must produce at least one bubble");
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
        // RDR-015 Option B: count is a granularity hint; the realized count is the number of in-bounds
        // level-L tets tiling the world domain (may exceed the requested count). The old "<= requested"
        // bound was a property of the legacy mixed-level grid, not the spatial partition.
        assertTrue(bubbles > 0, "Should create bubbles");
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

    /**
     * Deterministic correctness at scale (replaces the wall-clock {@code testLargePopulation_500Entities_60fps}).
     * <p>
     * The old test asserted {@code getTicksPerSecond() >= 25}, where {@code getTicksPerSecond() = 1000 /
     * averageFrameTimeMs} and {@code frameTimeMs} is wall-elapsed {@code nanoTime} measured around the tick body
     * ({@code MultiBubbleSimulation.tick()}). Under the full-suite batch (forkCount=15, reuseForks, no -Xmx) GC
     * stop-the-world pauses and 15-way core contention bleed into that wall-elapsed measurement, collapsing the
     * reported TPS from ~336 (isolation; genuine warm tick ≈ 3 ms) to single digits. That made it a contention
     * artifact, not a correctness signal — and {@code @DisabledIfEnvironmentVariable(CI)} did not cover a local
     * batch run (no {@code CI} var). Throughput now lives in the {@code @Tag("performance")} sibling below; this
     * test pins what actually matters at 500 entities: the simulation advances and conserves entities exactly
     * ACROSS real migrations, with no wall-clock dependency (Luciferase-9sysj).
     * <p>
     * Uses a <b>seeded</b> {@link RandomWalkBehavior} (not the unseeded {@code FlockingBehavior} the old test used)
     * so the run is reproducible — matching the {@code MultiBubbleSimulationMigrationTest} pattern — and so it
     * genuinely drives migrations: at this scale, 300 seeded ticks commit 162 migrations (measured), and the
     * {@code getTotalMigrations() > 0} assertion fails loudly if a future change ever makes 300 ticks too short,
     * preventing the conservation invariant from passing vacuously in a no-migration regime.
     */
    @Test
    void testLargePopulation_500Entities_deterministicConservation() {
        simulation = new MultiBubbleSimulation(9, (byte) 2, 500, WorldBounds.DEFAULT, new RandomWalkBehavior(42L));

        int initialReal = simulation.getRealEntities().size();
        assertTrue(initialReal > 0, "fixture must start with real entities");

        // Deterministic single-stepping (no start()/Thread.sleep) — fully reproducible (Luciferase-j6ybd).
        for (int i = 0; i < 300; i++) {
            simulation.tick();
        }

        assertTrue(simulation.getTickCount() >= 300, "simulation must have advanced the driven ticks");

        // The conservation invariant must be exercised ACROSS migrations, not in a trivial no-migration regime.
        assertTrue(simulation.getMigrationMetrics().getTotalMigrations() > 0,
                   "300 ticks at 500-entity/9-bubble scale must commit migrations (else conservation is vacuous)");

        // Conservation + uniqueness must hold across all migrations driven by the 300 ticks (no loss/duplication).
        var realEntities = simulation.getRealEntities();
        assertEquals(initialReal, realEntities.size(),
                     "real entity count must be exactly conserved across 300 ticks (no loss / no creation)");
        var bubblesById = new java.util.HashMap<String, java.util.Set<Object>>();
        for (var e : realEntities) {
            bubblesById.computeIfAbsent(e.id(), k -> new java.util.HashSet<>()).add(e.bubbleKey());
        }
        for (var entry : bubblesById.entrySet()) {
            assertEquals(1, entry.getValue().size(),
                         "entity " + entry.getKey() + " must reside in exactly one bubble (no duplication)");
        }
    }

    /**
     * Throughput smoke test at 500 entities. Tagged {@code performance} so it is excluded from the default suite
     * and runs only under {@code -Pperformance} (isolated, uncontended) — see the root-cause note on
     * {@link #testLargePopulation_500Entities_deterministicConservation}. The metric is wall-clock-derived and
     * therefore only meaningful when not competing with the 15-fork batch.
     * <p>
     * The floor is deliberately <b>coarse</b> (5 TPS, not the old 25): {@code -Pperformance} flips the tag filter
     * but does NOT set {@code forkCount=1}/{@code reuseForks=false}, so even here some contention is possible. The
     * genuine warm tick is ≈ 3 ms (≈ 336 theoretical TPS), so 5 TPS = 200 ms/tick leaves ~66× headroom — it is an
     * algorithmic-regression sentinel (e.g. an O(n²) per-tick blowup), not a tight throughput SLA. Tighten this
     * only if the performance profile is changed to isolate forks.
     */
    @Test
    @org.junit.jupiter.api.Tag("performance")
    void testLargePopulation_500Entities_throughput() throws InterruptedException {
        simulation = new MultiBubbleSimulation(9, (byte) 2, 500, WorldBounds.DEFAULT, new FlockingBehavior());

        simulation.start();
        Thread.sleep(1000); // Run for 1 second

        var metrics = simulation.getMetrics();
        var tps = metrics.getTicksPerSecond();

        simulation.stop();

        assertTrue(tps >= 5, "per-tick cost regressed badly (algorithmic): expected >= 5 TPS with 500 entities, got " + tps);
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

        // Snapshot tick-listener counts per bubble before close
        var bubblesBeforeClose = simulation.getAllBubbles();
        var bubbleControllers = bubblesBeforeClose.stream()
            .map(EnhancedBubble::getRealTimeController)
            .toList();
        var listenerCountsBefore = bubbleControllers.stream()
            .mapToInt(RealTimeController::getTickListenerCount)
            .toArray();

        simulation.close();

        assertFalse(simulation.isRunning(), "Simulation should not be running after close()");

        // Every bubble's ghostCoordinator tick listener must have been removed
        for (int i = 0; i < bubbleControllers.size(); i++) {
            int after = bubbleControllers.get(i).getTickListenerCount();
            assertTrue(after < listenerCountsBefore[i],
                "Bubble[" + i + "]: tick listener count should drop after close(); before=" +
                listenerCountsBefore[i] + " after=" + after);
        }
    }

    @Test
    void testClose_idempotent() throws Exception {
        simulation = new MultiBubbleSimulation(3, (byte) 1, 30, WorldBounds.DEFAULT, new FlockingBehavior());
        simulation.start();
        Thread.sleep(50);

        assertDoesNotThrow(() -> {
            simulation.close();
            simulation.close(); // must not throw
        }, "MultiBubbleSimulation.close() must be idempotent");
    }
}
