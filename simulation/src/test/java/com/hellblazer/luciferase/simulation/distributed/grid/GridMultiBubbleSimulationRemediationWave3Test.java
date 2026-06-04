/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wave-3 regression tests for GridMultiBubbleSimulation:
 * <ul>
 *   <li>Luciferase-0frcy.98: {@code initializeVelocities()} must be deterministically seeded. Two
 *       identically-configured simulations with identical entities must produce identical initial
 *       velocity maps. Previously it used an unseeded {@code new Random()}.</li>
 *   <li>Luciferase-0frcy.64 / .97: ghost writes are now performed inside {@code snapshotLock} in
 *       {@code tick()}, so a concurrent {@code getAllEntities()} never observes torn ghost state or an
 *       inconsistent real-entity / ghost snapshot. The concurrent reader must never throw and must
 *       always observe a deduplicated real-entity set.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class GridMultiBubbleSimulationRemediationWave3Test {

    private static final WorldBounds WORLD = new WorldBounds(0f, 400f);

    @Test
    void initialVelocitiesAreDeterministic() throws Exception {
        var config = GridConfiguration.DEFAULT_2X2;

        var velA = computeInitialVelocities(new GridMultiBubbleSimulation(config, 200, WORLD));
        var velB = computeInitialVelocities(new GridMultiBubbleSimulation(config, 200, WORLD));

        assertFalse(velA.isEmpty(), "velocity map must be populated");
        assertEquals(velA.keySet(), velB.keySet(), "same entities must be present in both runs");
        for (var id : velA.keySet()) {
            var a = velA.get(id);
            var b = velB.get(id);
            assertEquals(a.x, b.x, 0.0f, "velocity X must be deterministic for " + id + " (Luciferase-0frcy.98)");
            assertEquals(a.y, b.y, 0.0f, "velocity Y must be deterministic for " + id);
            assertEquals(a.z, b.z, 0.0f, "velocity Z must be deterministic for " + id);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Vector3f> computeInitialVelocities(GridMultiBubbleSimulation sim) throws Exception {
        Method init = GridMultiBubbleSimulation.class.getDeclaredMethod("initializeVelocities");
        init.setAccessible(true);
        init.invoke(sim);

        Field f = GridMultiBubbleSimulation.class.getDeclaredField("velocities");
        f.setAccessible(true);
        // Defensive copy so the two sims' live maps don't alias.
        return new HashMap<>((Map<String, Vector3f>) f.get(sim));
    }

    @Test
    void concurrentGetAllEntitiesNeverThrowsAndStaysConsistent() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var config = GridConfiguration.DEFAULT_2X2;
            try (var simulation = new GridMultiBubbleSimulation(config, 400, WORLD)) {
                var error = new AtomicReference<Throwable>();
                var reader = new Thread(() -> {
                    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                    try {
                        while (System.nanoTime() < deadline && error.get() == null) {
                            var snapshot = simulation.getAllEntities();
                            // Real entities must be unique within a single locked snapshot — a torn read
                            // racing with ghost writes outside the lock could otherwise surface anomalies.
                            long realCount = snapshot.stream().filter(e -> !e.isGhost()).count();
                            long distinctReal = snapshot.stream().filter(e -> !e.isGhost())
                                                        .map(e -> e.id()).distinct().count();
                            assertEquals(realCount, distinctReal,
                                         "real entities must be unique within a locked snapshot "
                                         + "(Luciferase-0frcy.64/.97)");
                        }
                    } catch (Throwable t) {
                        error.set(t);
                    }
                }, "snapshot-reader");

                simulation.start();
                reader.start();
                reader.join(Duration.ofSeconds(20).toMillis());
                simulation.stop();

                assertNull(error.get(), () -> "concurrent getAllEntities() observed an inconsistency: "
                                              + error.get());
            }
        });
    }
}
