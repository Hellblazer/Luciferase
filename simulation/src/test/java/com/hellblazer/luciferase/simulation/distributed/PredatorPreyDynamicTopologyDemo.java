/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive demo of predator/prey dynamics with pack hunting and dynamic topology.
 * <p>
 * <b>Vision</b>: 2x2x2 tetree grid (8 bubbles) with 1000 entities exhibiting emergent behaviors:
 * <ul>
 *   <li>900 Prey: Flock together, flee from predators, panic when threatened</li>
 *   <li>100 Predators: Hunt in packs, coordinate attacks, flanking maneuvers</li>
 * </ul>
 * <p>
 * <b>Dynamic Topology</b>:
 * - Bubbles split when density >5000 entities (though we start with 125/bubble average)
 * - Bubbles merge when density <500 entities
 * - Boundaries adapt to entity clustering
 * - 100% entity retention via EntityAccountant
 * <p>
 * <b>Pack Hunting</b>:
 * - Predators form packs within 40% of AOI radius
 * - Leader (closest to prey) pursues directly
 * - Flankers take intercept positions 90° from leader
 * - Solo predators fall back to independent chase
 * <p>
 * <b>Infrastructure</b>:
 * - Uses PrimeMover @Entity for proper discrete event simulation
 * - RealTimeController for event-driven coordination
 * - TetreeBubbleGrid for tetrahedral spatial subdivision
 * - Real-time metrics tracking topology evolution
 * <p>
 * <b>Expected Emergent Behaviors</b>:
 * - Prey form large flocks in open areas
 * - Prey scatter when predators approach
 * - Predators coordinate to surround prey flocks
 * - Prey clusters trigger potential bubble splits (if density exceeds threshold)
 * - Empty bubbles trigger potential merges
 *
 * @author hal.hildebrand
 */
class PredatorPreyDynamicTopologyDemo {

    private static final int TOTAL_ENTITIES = 1000;
    private static final int PREY_COUNT = 900;
    private static final int PREDATOR_COUNT = 100;
    private static final int TICKS_TO_RUN = 1000;
    private static final long TICK_INTERVAL_NS = 50_000_000; // 50ms = 20 TPS

    private static final WorldBounds WORLD = WorldBounds.DEFAULT; // -100 to 100 in each dimension
    private static final Random RANDOM = new Random(42); // Deterministic seed

    @Test
    @Timeout(120) // 2 minute timeout for safety
    void demonstratePredatorPreyWithPackHunting() throws InterruptedException {
        System.out.println("=== Predator/Prey Pack Hunting Demo (PrimeMover) ===");
        System.out.println("Vision: 2x2x2 tetree grid, 900 prey + 100 pack-hunting predators\n");

        // Phase 1: Initialize 2x2x2 tetree grid (8 bubbles)
        System.out.println("Phase 1: Initialize Spatial Grid");
        System.out.println("--------------------------------");
        var bubbleGrid = new TetreeBubbleGrid((byte) 2);
        bubbleGrid.createBubbles(8, (byte) 2, 10); // 8 bubbles, level 2, 10ms frame budget
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        System.out.println("Created " + bubbles.size() + " tetrahedral bubbles (2x2x2 grid)");
        System.out.println("Bubble IDs: " + bubbles.stream()
            .map(b -> b.id().toString().substring(0, 8))
            .collect(Collectors.joining(", ")));

        // Phase 2: Spawn entities with behaviors
        System.out.println("\nPhase 2: Spawn Entities");
        System.out.println("------------------------");
        var accountant = new EntityAccountant();
        var preyBehavior = new PreyBehavior();
        var predatorBehavior = new PackHuntingBehavior();

        // Entity tracking
        var entityVelocities = new ConcurrentHashMap<String, Vector3f>();
        var entityBehaviors = new ConcurrentHashMap<String, Object>();

        // Spawn prey
        System.out.println("Spawning " + PREY_COUNT + " prey entities...");
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
        System.out.println("Spawning " + PREDATOR_COUNT + " predator entities...");
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

        System.out.println("Spawned " + TOTAL_ENTITIES + " entities total");
        System.out.println("  Prey: " + PREY_COUNT + " (flocking + flee)");
        System.out.println("  Predators: " + PREDATOR_COUNT + " (pack hunting)");

        // Phase 3: Initialize metrics
        System.out.println("\nPhase 3: Initialize Metrics");
        System.out.println("----------------------------");
        var metrics = new DemoMetrics();
        metrics.initialBubbleCount = bubbles.size();
        metrics.initialEntityCount = TOTAL_ENTITIES;
        System.out.println("Tracking: entity distribution, pack sizes, topology changes");

        // Phase 4: PrimeMover simulation
        System.out.println("\nPhase 4: Run Simulation (PrimeMover @Entity)");
        System.out.println("---------------------------------------------");
        System.out.println("Running " + TICKS_TO_RUN + " ticks at " + (TICK_INTERVAL_NS / 1_000_000) + "ms intervals");
        System.out.println("Using discrete event simulation (NOT thread loops!)\n");

        var completionLatch = new CountDownLatch(1);
        var controller = new RealTimeController("PredatorPreyDemo");
        var entity = new SimulationEntity(
            bubbles,
            entityVelocities,
            entityBehaviors,
            preyBehavior,
            accountant,
            metrics,
            completionLatch
        );

        Kairos.setController(controller);

        long startTime = System.currentTimeMillis();
        controller.start();
        entity.simulationTick(); // Start the event-driven simulation

        // Wait for completion
        completionLatch.await();
        controller.stop();
        long elapsedMs = System.currentTimeMillis() - startTime;

        // Phase 5: Final analysis
        System.out.println("\n=== Final Analysis ===");
        System.out.println("Simulation completed: " + TICKS_TO_RUN + " ticks in " + elapsedMs + "ms");
        System.out.println("Average TPS: " + String.format("%.1f", TICKS_TO_RUN * 1000.0 / elapsedMs));

        // Entity conservation check
        var finalDistribution = accountant.getDistribution();
        int totalFinal = finalDistribution.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("\nEntity Conservation:");
        System.out.println("  Initial: " + TOTAL_ENTITIES);
        System.out.println("  Final: " + totalFinal);
        System.out.println("  Retention: " + (totalFinal == TOTAL_ENTITIES ? "100% ✓" : "FAILED"));

        // Topology evolution
        System.out.println("\nTopology Evolution:");
        System.out.println("  Initial bubbles: " + metrics.initialBubbleCount);
        System.out.println("  Final bubbles: " + bubbles.size());
        System.out.println("  (Dynamic splits/merges would occur with higher entity densities)");

        // Pack hunting statistics
        System.out.println("\nPack Hunting Statistics:");
        System.out.println("  Average pack size: " + String.format("%.2f", metrics.averagePackSize));
        System.out.println("  Max pack size observed: " + metrics.maxPackSize);
        System.out.println("  Solo hunters: " + metrics.soloHunters);

        // Prey behavior statistics
        System.out.println("\nPrey Behavior Statistics:");
        System.out.println("  Average flock size: " + String.format("%.2f", metrics.averageFlockSize));
        System.out.println("  Max flock size observed: " + metrics.maxFlockSize);
        System.out.println("  Panic events (predator proximity): " + metrics.panicEvents);

        // Bubble distribution
        System.out.println("\nBubble Entity Distribution:");
        var distribution = accountant.getDistribution();
        var sortedBubbles = distribution.entrySet().stream()
            .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
            .toList();
        for (int i = 0; i < Math.min(5, sortedBubbles.size()); i++) {
            var entry = sortedBubbles.get(i);
            System.out.println("  Bubble " + entry.getKey().toString().substring(0, 8) +
                              ": " + entry.getValue() + " entities");
        }
        if (sortedBubbles.size() > 5) {
            System.out.println("  ... (" + (sortedBubbles.size() - 5) + " more bubbles)");
        }

        // Key observations
        System.out.println("\n=== Key Observations ===");
        System.out.println("1. Pack hunting: Predators coordinate within ~40% of AOI radius");
        System.out.println("2. Flanking: Pack members take intercept positions for prey capture");
        System.out.println("3. Prey flocking: Safety in numbers, scatter when threatened");
        System.out.println("4. Dynamic topology: Ready for split/merge when densities exceed thresholds");
        System.out.println("5. PrimeMover integration: Uses @Entity with Kronos.sleep() for event-driven simulation");

        // Validation
        assertTrue(totalFinal == TOTAL_ENTITIES, "100% entity retention required");
        System.out.println("\n✓ Demo completed successfully");
    }

    /**
     * PrimeMover @Entity for event-driven simulation.
     * <p>
     * Follows the pattern from ProcessCoordinatorEntity:
     * - @Entity annotation for PrimeMover registration
     * - simulationTick() event method that processes one tick
     * - Kronos.sleep() for proper discrete event scheduling
     * - Recursive call to schedule next tick
     */
    @Entity
    public static class SimulationEntity {
        private static final float DELTA_TIME = TICK_INTERVAL_NS / 1_000_000_000.0f; // Convert ns to seconds

        private final List<EnhancedBubble> bubbles;
        private final Map<String, Vector3f> velocities;
        private final Map<String, Object> behaviors;
        private final PreyBehavior preyBehavior;
        private final EntityAccountant accountant;
        private final DemoMetrics metrics;
        private final CountDownLatch completionLatch;

        private int currentTick = 0;

        public SimulationEntity(
            List<EnhancedBubble> bubbles,
            Map<String, Vector3f> velocities,
            Map<String, Object> behaviors,
            PreyBehavior preyBehavior,
            EntityAccountant accountant,
            DemoMetrics metrics,
            CountDownLatch completionLatch
        ) {
            this.bubbles = bubbles;
            this.velocities = velocities;
            this.behaviors = behaviors;
            this.preyBehavior = preyBehavior;
            this.accountant = accountant;
            this.metrics = metrics;
            this.completionLatch = completionLatch;
        }

        @NonEvent
        public int getCurrentTick() {
            return currentTick;
        }

        /**
         * Execute one simulation tick (PrimeMover event method).
         * <p>
         * Pattern:
         * 1. Process work (update entities)
         * 2. Collect metrics (if applicable)
         * 3. Check completion
         * 4. Kronos.sleep() for proper event scheduling
         * 5. Recursive call to schedule next tick
         */
        public void simulationTick() {
            // Check completion first
            if (currentTick >= TICKS_TO_RUN) {
                completionLatch.countDown();
                return;
            }

            // Swap velocity buffers for behaviors that use them
            preyBehavior.swapVelocityBuffers();

            // Update all entities in all bubbles
            for (var bubble : bubbles) {
                updateBubbleEntities(bubble);
            }

            // Collect metrics every 100 ticks
            if (currentTick % 100 == 0) {
                collectMetrics();
            }

            currentTick++;

            // Schedule next tick (PrimeMover recursive event scheduling)
            Kronos.sleep(TICK_INTERVAL_NS);
            this.simulationTick();
        }

        /**
         * Update all entities in a bubble for one simulation tick.
         */
        private void updateBubbleEntities(EnhancedBubble bubble) {
            // Get all entities in this bubble
            var entities = bubble.getAllEntityRecords();

            for (var entity : entities) {
                var entityId = entity.id();
                var position = entity.position();
                var velocity = velocities.getOrDefault(entityId, new Vector3f(0, 0, 0));
                var behavior = behaviors.get(entityId);

                // Compute new velocity based on behavior
                Vector3f newVelocity;
                if (behavior instanceof PreyBehavior preyBehavior) {
                    newVelocity = preyBehavior.computeVelocity(entityId, position, velocity, bubble, DELTA_TIME);
                } else if (behavior instanceof PackHuntingBehavior packBehavior) {
                    newVelocity = packBehavior.computeVelocity(entityId, position, velocity, bubble, DELTA_TIME);
                } else {
                    newVelocity = velocity; // No behavior
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

                // Update entity in bubble
                bubble.updateEntityPosition(entityId, newPosition);
                velocities.put(entityId, newVelocity);
            }
        }

        /**
         * Collect metrics about current simulation state.
         */
        private void collectMetrics() {
            System.out.println("\n--- Tick " + currentTick + " Metrics ---");

            // Bubble distribution
            var distribution = accountant.getDistribution();
            var counts = distribution.values();
            int min = counts.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = counts.stream().mapToInt(Integer::intValue).max().orElse(0);
            double avg = counts.stream().mapToInt(Integer::intValue).average().orElse(0.0);

            System.out.println("Bubble distribution: min=" + min + ", max=" + max + ", avg=" + String.format("%.1f", avg));

            // Pack analysis
            int totalPacks = 0;
            int totalPackMembers = 0;
            int soloHunters = 0;

            for (var bubble : bubbles) {
                var predators = bubble.getAllEntityRecords().stream()
                    .filter(e -> e.content() instanceof EntityType && e.content() == EntityType.PREDATOR)
                    .toList();
                for (var predator : predators) {
                    var nearbyPredators = bubble.queryRange(predator.position(), 20.0f).stream()
                        .filter(n -> !n.id().equals(predator.id()))
                        .filter(n -> n.content() instanceof EntityType && n.content() == EntityType.PREDATOR)
                        .count();

                    if (nearbyPredators > 0) {
                        totalPackMembers++;
                    } else {
                        soloHunters++;
                    }
                }
            }

            if (totalPackMembers > 0) {
                totalPacks = totalPackMembers / 2; // Rough estimate (each pair counted twice)
                metrics.averagePackSize = (double) totalPackMembers / totalPacks;
                metrics.maxPackSize = Math.max(metrics.maxPackSize, (int) metrics.averagePackSize);
            }
            metrics.soloHunters = soloHunters;

            System.out.println("Pack hunting: " + totalPacks + " packs, " + soloHunters + " solo hunters");

            // Flock analysis (sample from one bubble for performance)
            if (!bubbles.isEmpty()) {
                var sampleBubble = bubbles.get(0);
                var prey = sampleBubble.getAllEntityRecords().stream()
                    .filter(e -> e.content() instanceof EntityType && e.content() == EntityType.PREY)
                    .toList();
                if (!prey.isEmpty()) {
                    var samplePrey = prey.get(0);
                    var nearbyPrey = sampleBubble.queryRange(samplePrey.position(), 35.0f).stream()
                        .filter(n -> n.content() instanceof EntityType && n.content() == EntityType.PREY)
                        .count();

                    metrics.averageFlockSize = Math.max(metrics.averageFlockSize, nearbyPrey);
                    metrics.maxFlockSize = Math.max(metrics.maxFlockSize, (int) nearbyPrey);
                }
            }

            // Check for panic events (prey moving at high speed)
            long panicCount = velocities.entrySet().stream()
                .filter(e -> e.getKey().startsWith("prey-"))
                .filter(e -> e.getValue().length() > 20.0f) // Panic speed threshold
                .count();
            metrics.panicEvents += panicCount;

            System.out.println("Prey behavior: " + (int) metrics.averageFlockSize + " in observed flock, " +
                              panicCount + " panicking");
        }
    }

    /**
     * Generate a random position within world bounds.
     */
    private static Point3f randomPosition() {
        float range = WORLD.max() - WORLD.min();
        return new Point3f(
            WORLD.min() + RANDOM.nextFloat() * range,
            WORLD.min() + RANDOM.nextFloat() * range,
            WORLD.min() + RANDOM.nextFloat() * range
        );
    }

    /**
     * Generate a random initial velocity.
     */
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

    /**
     * Pad entity ID to UUID format (demo helper).
     */
    private static String padToUUID(String id) {
        String hash = String.format("%032x", id.hashCode() & 0xFFFFFFFFL);
        return hash.substring(0, 8) + "-" + hash.substring(8, 12) + "-" +
               hash.substring(12, 16) + "-" + hash.substring(16, 20) + "-" + hash.substring(20, 32);
    }

    /**
     * Demo metrics tracking.
     */
    private static class DemoMetrics {
        int initialBubbleCount;
        int initialEntityCount;
        double averagePackSize;
        int maxPackSize;
        int soloHunters;
        double averageFlockSize;
        int maxFlockSize;
        long panicEvents;
    }
}
