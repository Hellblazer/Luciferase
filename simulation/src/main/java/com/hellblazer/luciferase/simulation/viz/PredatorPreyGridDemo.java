/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz;

import com.hellblazer.luciferase.simulation.behavior.PackHuntingBehavior;
import com.hellblazer.luciferase.simulation.behavior.PreyBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.entity.EntityType;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.annotations.NonEvent;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.primeMover.controllers.RealTimeController;
import com.hellblazer.primeMover.runtime.Kairos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grand vision demo: 2x2x2 tetree grid with 1000 pack-hunting entities.
 * <p>
 * Combines:
 * - PrimeMover discrete event simulation (@Entity, Kronos.sleep)
 * - 2x2x2 tetrahedral spatial grid (8 bubbles)
 * - 900 prey (flocking + flee)
 * - 100 predators (pack hunting with flanking)
 * - Real-time web visualization via WebSocket
 * <p>
 * Usage:
 * <pre>
 * mvn exec:java -pl simulation \
 *   -Dexec.mainClass=com.hellblazer.luciferase.simulation.viz.PredatorPreyGridDemo \
 *   -Dexec.args="7081"
 * </pre>
 * Then open: http://localhost:7081/predator-prey-grid.html
 */
public class PredatorPreyGridDemo {

    private static final Logger log = LoggerFactory.getLogger(PredatorPreyGridDemo.class);

    private static final int TOTAL_ENTITIES = 1000;
    private static final int PREY_COUNT = 900;
    private static final int PREDATOR_COUNT = 100;
    private static final long TICK_INTERVAL_NS = 50_000_000; // 50ms = 20 TPS
    private static final WorldBounds WORLD = WorldBounds.DEFAULT;
    private static final Random RANDOM = new Random(42);

    public static void main(String[] args) {
        var port = args.length > 0 ? Integer.parseInt(args[0]) : 7081;

        log.info("=== Grand Vision: Pack Hunting Predator-Prey Grid ===");
        log.info("Vision: 2x2x2 tetree grid, 900 prey + 100 pack-hunting predators");
        log.info("Using PrimeMover discrete event simulation (NOT thread loops!)");

        // Phase 1: Initialize 2x2x2 tetree grid
        log.info("Phase 1: Initialize 2x2x2 Tetree Grid");
        var bubbleGrid = new TetreeBubbleGrid((byte) 2);
        bubbleGrid.createBubbles(8, (byte) 2, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        log.info("Created {} tetrahedral bubbles", bubbles.size());

        // Phase 2: Spawn entities
        log.info("Phase 2: Spawn Entities");
        var accountant = new EntityAccountant();
        var preyBehavior = new PreyBehavior();
        var predatorBehavior = new PackHuntingBehavior();

        var entityVelocities = new ConcurrentHashMap<String, Vector3f>();
        var entityBehaviors = new ConcurrentHashMap<String, Object>();

        // Spawn prey
        log.info("Spawning {} prey entities...", PREY_COUNT);
        for (int i = 0; i < PREY_COUNT; i++) {
            var bubble = bubbles.get(RANDOM.nextInt(bubbles.size()));
            var entityId = "prey-" + i;
            var position = randomPosition();
            var velocity = randomVelocity(preyBehavior.getMaxSpeed());

            bubble.addEntity(entityId, position, EntityType.PREY);
            accountant.register(bubble.id(), UUID.fromString(padToUUID(entityId)));
            entityVelocities.put(entityId, velocity);
            entityBehaviors.put(entityId, preyBehavior);
        }

        // Spawn predators
        log.info("Spawning {} predator entities...", PREDATOR_COUNT);
        for (int i = 0; i < PREDATOR_COUNT; i++) {
            var bubble = bubbles.get(RANDOM.nextInt(bubbles.size()));
            var entityId = "predator-" + i;
            var position = randomPosition();
            var velocity = randomVelocity(predatorBehavior.getMaxSpeed());

            bubble.addEntity(entityId, position, EntityType.PREDATOR);
            accountant.register(bubble.id(), UUID.fromString(padToUUID(entityId)));
            entityVelocities.put(entityId, velocity);
            entityBehaviors.put(entityId, predatorBehavior);
        }

        log.info("Spawned {} total entities", TOTAL_ENTITIES);

        // Phase 3: Start visualization server
        log.info("Phase 3: Start Visualization Server");
        var vizServer = new MultiBubbleVisualizationServer(port);
        vizServer.setBubbles(bubbles);
        vizServer.start();

        log.info("Visualization server running on http://localhost:{}", port);
        log.info("Open http://localhost:{}/predator-prey-grid.html to view", port);

        // Phase 4: Start PrimeMover simulation
        log.info("Phase 4: Start PrimeMover Simulation");
        var controller = new RealTimeController("PredatorPreyGridDemo");
        var entity = new SimulationEntity(
            bubbles,
            entityVelocities,
            entityBehaviors,
            preyBehavior,
            accountant
        );

        Kairos.setController(controller);
        controller.start();
        entity.simulationTick();

        log.info("Simulation running at 20 TPS (50ms ticks)");
        log.info("Press Ctrl+C to stop");

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            controller.stop();
            vizServer.stop();
        }));
    }

    /**
     * PrimeMover @Entity for discrete event simulation.
     */
    @Entity
    public static class SimulationEntity {
        private static final float DELTA_TIME = TICK_INTERVAL_NS / 1_000_000_000.0f;

        private final List<EnhancedBubble> bubbles;
        private final Map<String, Vector3f> velocities;
        private final Map<String, Object> behaviors;
        private final PreyBehavior preyBehavior;
        private final EntityAccountant accountant;

        private int currentTick = 0;

        public SimulationEntity(
            List<EnhancedBubble> bubbles,
            Map<String, Vector3f> velocities,
            Map<String, Object> behaviors,
            PreyBehavior preyBehavior,
            EntityAccountant accountant
        ) {
            this.bubbles = bubbles;
            this.velocities = velocities;
            this.behaviors = behaviors;
            this.preyBehavior = preyBehavior;
            this.accountant = accountant;
        }

        @NonEvent
        public int getCurrentTick() {
            return currentTick;
        }

        /**
         * Execute one simulation tick (PrimeMover event method).
         */
        public void simulationTick() {
            // Swap velocity buffers
            preyBehavior.swapVelocityBuffers();

            // Update all entities in all bubbles
            for (var bubble : bubbles) {
                updateBubbleEntities(bubble);
            }

            // Log progress every 100 ticks
            if (currentTick % 100 == 0) {
                var distribution = accountant.getDistribution();
                int total = distribution.values().stream().mapToInt(Integer::intValue).sum();
                log.info("Tick {}: {} entities across {} bubbles", currentTick, total, bubbles.size());
            }

            currentTick++;

            // Schedule next tick
            Kronos.sleep(TICK_INTERVAL_NS);
            this.simulationTick();
        }

        private void updateBubbleEntities(EnhancedBubble bubble) {
            var entities = bubble.getAllEntityRecords();

            for (var entity : entities) {
                var entityId = entity.id();
                var position = entity.position();
                var velocity = velocities.getOrDefault(entityId, new Vector3f(0, 0, 0));
                var behavior = behaviors.get(entityId);

                // Compute new velocity
                Vector3f newVelocity;
                if (behavior instanceof PreyBehavior preyBehavior) {
                    newVelocity = preyBehavior.computeVelocity(entityId, position, velocity, bubble, DELTA_TIME);
                } else if (behavior instanceof PackHuntingBehavior packBehavior) {
                    newVelocity = packBehavior.computeVelocity(entityId, position, velocity, bubble, DELTA_TIME);
                } else {
                    newVelocity = velocity;
                }

                // Update position
                var newPosition = new Point3f(
                    position.x + newVelocity.x * DELTA_TIME,
                    position.y + newVelocity.y * DELTA_TIME,
                    position.z + newVelocity.z * DELTA_TIME
                );

                // Clamp to world bounds
                newPosition.x = Math.max(WORLD.min(), Math.min(WORLD.max(), newPosition.x));
                newPosition.y = Math.max(WORLD.min(), Math.min(WORLD.max(), newPosition.y));
                newPosition.z = Math.max(WORLD.min(), Math.min(WORLD.max(), newPosition.z));

                // Update
                bubble.updateEntityPosition(entityId, newPosition);
                velocities.put(entityId, newVelocity);
            }
        }
    }

    private static Point3f randomPosition() {
        float range = WORLD.max() - WORLD.min();
        return new Point3f(
            WORLD.min() + RANDOM.nextFloat() * range,
            WORLD.min() + RANDOM.nextFloat() * range,
            WORLD.min() + RANDOM.nextFloat() * range
        );
    }

    private static Vector3f randomVelocity(float maxSpeed) {
        float speed = RANDOM.nextFloat() * maxSpeed;
        float theta = RANDOM.nextFloat() * (float) (2 * Math.PI);
        float phi = RANDOM.nextFloat() * (float) Math.PI;

        return new Vector3f(
            speed * (float) (Math.sin(phi) * Math.cos(theta)),
            speed * (float) (Math.sin(phi) * Math.sin(theta)),
            speed * (float) Math.cos(phi)
        );
    }

    private static String padToUUID(String id) {
        String hash = String.format("%032x", id.hashCode() & 0xFFFFFFFFL);
        return hash.substring(0, 8) + "-" + hash.substring(8, 12) + "-" +
               hash.substring(12, 16) + "-" + hash.substring(16, 20) + "-" + hash.substring(20, 32);
    }
}
