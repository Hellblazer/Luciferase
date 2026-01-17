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
import com.hellblazer.luciferase.simulation.bubble.CubeForest;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.entity.EntityType;
import com.hellblazer.luciferase.simulation.topology.metrics.DensityMonitor;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.primeMover.controllers.RealTimeController;
import com.hellblazer.primeMover.runtime.Kairos;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web-enabled grand vision demo: 2x2x2 tetree grid with 1000 pack-hunting entities.
 * <p>
 * Runnable test version that works with PrimeMover bytecode transformation.
 * <p>
 * Usage:
 * <pre>
 * mvn test -pl simulation -Dtest=PredatorPreyGridWebDemo
 * </pre>
 * Then open: http://localhost:7081/predator-prey-grid.html
 * <p>
 * Press Ctrl+C to stop (server will continue running until JVM shutdown).
 */
public class PredatorPreyGridWebDemo {

    private static final Logger log = LoggerFactory.getLogger(PredatorPreyGridWebDemo.class);

    private static final int TOTAL_ENTITIES = 1000;
    private static final int PREY_COUNT = 900;
    private static final int PREDATOR_COUNT = 100;
    private static final long TICK_INTERVAL_NS = 50_000_000; // 50ms = 20 TPS
    private static final WorldBounds WORLD = WorldBounds.DEFAULT;
    private static final Random RANDOM = new Random(42);

    @Test
    void runWebDemo() throws Exception {
        var port = 7081;

        // Phase 1: Initialize S0-S5 cube decomposition forest
        var cubeForest = new CubeForest(WORLD.min(), WORLD.max(), (byte) 10, 10);
        var bubbles = new ArrayList<>(cubeForest.getAllBubbles());

        // Phase 2: Spawn entities
        var accountant = new EntityAccountant();
        var preyBehavior = new PreyBehavior();
        var predatorBehavior = new PackHuntingBehavior();

        // Phase 2.5: Initialize density monitoring (topology operations require TetreeBubbleGrid)
        var densityMonitor = new DensityMonitor(5000, 500); // Split at 5000 entities, merge at 500

        var entityVelocities = new ConcurrentHashMap<String, Vector3f>();
        var entityBehaviors = new ConcurrentHashMap<String, Object>();

        // VON ENTITY SPAWNING: Spatially route entities to correct bubble domains
        for (int i = 0; i < PREY_COUNT; i++) {
            var bubble = bubbles.get(i % bubbles.size()); // Distribute evenly across bubbles
            var entityId = "prey-" + i;

            // VON JOIN: Generate random position in world, classify to find accepting bubble
            // This follows VON principles: entity position determines which bubble accepts it
            var position = randomPosition();
            // Verify position is in correct bubble domain
            var acceptingBubble = cubeForest.getBubbleForPosition(position);
            if (!acceptingBubble.id().equals(bubble.id())) {
                // Reassign to correct bubble
                bubble = acceptingBubble;
            }
            var velocity = randomVelocity(preyBehavior.getMaxSpeed());

            // Add entity to the accepting bubble (the one whose domain contains the position)
            bubble.addEntity(entityId, position, EntityType.PREY);
            accountant.register(bubble.id(), UUID.fromString(padToUUID(entityId)));
            entityVelocities.put(entityId, velocity);
            entityBehaviors.put(entityId, preyBehavior);
        }

        // VON ENTITY SPAWNING: Spatially route predators to correct bubble domains
        for (int i = 0; i < PREDATOR_COUNT; i++) {
            var bubble = bubbles.get(i % bubbles.size()); // Distribute evenly across bubbles
            var entityId = "predator-" + i;

            // VON JOIN: Generate random position in world, classify to find accepting bubble
            // This follows VON principles: entity position determines which bubble accepts it
            var position = randomPosition();
            // Verify position is in correct bubble domain
            var acceptingBubble = cubeForest.getBubbleForPosition(position);
            if (!acceptingBubble.id().equals(bubble.id())) {
                // Reassign to correct bubble
                bubble = acceptingBubble;
            }
            var velocity = randomVelocity(predatorBehavior.getMaxSpeed());

            // Add entity to the accepting bubble (the one whose domain contains the position)
            bubble.addEntity(entityId, position, EntityType.PREDATOR);
            accountant.register(bubble.id(), UUID.fromString(padToUUID(entityId)));
            entityVelocities.put(entityId, velocity);
            entityBehaviors.put(entityId, predatorBehavior);
        }

        // Phase 3: Start visualization server
        var vizServer = new MultiBubbleVisualizationServer(port);
        vizServer.setBubbles(bubbles);

        // Extract S0-S5 tetrahedral geometries for visualization
        var bubbleVertices = extractS0S5Vertices(cubeForest);
        var bubbleTypes = extractS0S5Types(cubeForest);
        var bubbleSpheres = extractS0S5Spheres(cubeForest);
        vizServer.setBubbleVertices(bubbleVertices);
        vizServer.setBubbleTypes(bubbleTypes);
        vizServer.setBubbleSpheres(bubbleSpheres);

        // Wire up density monitoring (topology operations require TetreeBubbleGrid)
        densityMonitor.addListener(vizServer.getTopologyEventStream());
        vizServer.setDensityMonitor(densityMonitor);

        log.info("Density monitoring enabled:");
        log.info("  - WebSocket endpoint: ws://localhost:{}/ws/topology (density state changes)", port);
        log.info("  - Density metrics API: http://localhost:{}/api/density", port);
        log.info("Note: Full topology operations (split/merge/move) require TetreeBubbleGrid");

        vizServer.start();

        log.info("S0-S5 Forest Demo: http://localhost:{}/predator-prey-grid.html", port);

        // Phase 4: Start PrimeMover simulation
        var controller = new RealTimeController("PredatorPreyGridWebDemo");
        var entity = new SimulationEntity(
            bubbles,
            entityVelocities,
            entityBehaviors,
            preyBehavior,
            accountant,
            cubeForest,
            vizServer,
            densityMonitor
        );

        Kairos.setController(controller);
        controller.start();
        entity.simulationTick();

        // Keep server and simulation running
        // In a real test, you'd add a completion condition
        // For demo purposes, we let it run until manual interruption
        Thread.currentThread().join();
    }

    /**
     * PrimeMover @Entity for discrete event simulation.
     */
    @Entity
    public static class SimulationEntity {
        private static final float DELTA_TIME = TICK_INTERVAL_NS / 1_000_000_000.0f;
        private static final int BOX_UPDATE_INTERVAL = 30; // Update boxes every 30 ticks (~1.5 seconds at 20 TPS)
        private static final int DENSITY_UPDATE_INTERVAL = 100; // Update density every 100 ticks (~5 seconds at 20 TPS)

        private final List<EnhancedBubble> bubbles;
        private final Map<String, Vector3f> velocities;
        private final Map<String, Object> behaviors;
        private final PreyBehavior preyBehavior;
        private final EntityAccountant accountant;
        private final CubeForest cubeForest;
        private final MultiBubbleVisualizationServer vizServer;
        private final DensityMonitor densityMonitor;

        private int currentTick = 0;

        public SimulationEntity(
            List<EnhancedBubble> bubbles,
            Map<String, Vector3f> velocities,
            Map<String, Object> behaviors,
            PreyBehavior preyBehavior,
            EntityAccountant accountant,
            CubeForest cubeForest,
            MultiBubbleVisualizationServer vizServer,
            DensityMonitor densityMonitor
        ) {
            this.bubbles = bubbles;
            this.velocities = velocities;
            this.behaviors = behaviors;
            this.preyBehavior = preyBehavior;
            this.accountant = accountant;
            this.cubeForest = cubeForest;
            this.vizServer = vizServer;
            this.densityMonitor = densityMonitor;
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

            // NOTE: Spatial domains (tetrahedra) are currently STATIC
            // When Phase 9 (dynamic topology) is implemented, this will periodically update
            // to show split/merge/boundary-shift operations
            // For now, tetrahedra and spheres remain fixed at their initial positions

            // Update density metrics periodically
            if (currentTick % DENSITY_UPDATE_INTERVAL == 0) {
                var distribution = accountant.getDistribution();
                densityMonitor.update(distribution);
            }

            // Log progress every 100 ticks
            if (currentTick % 100 == 0) {
                var distribution = accountant.getDistribution();
                int total = distribution.values().stream().mapToInt(Integer::intValue).sum();
                log.info("Tick {}: {} entities across {} bubbles", currentTick, total, bubbles.size());
            }

            currentTick++;

            // Schedule next tick (PrimeMover recursive event scheduling)
            Kronos.sleep(TICK_INTERVAL_NS);
            this.simulationTick();
        }

        private void updateBubbleEntities(EnhancedBubble bubble) {
            var entities = bubble.getAllEntityRecords();
            var entitiesToMigrate = new ArrayList<String>();

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

                // VON BOUNDARY CROSSING DETECTION: S0-S5 classification to detect domain changes
                // Use deterministic classification: position determines which S0-S5 tet owns it
                var newBubble = cubeForest.getBubbleForPosition(newPosition);
                if (!newBubble.id().equals(bubble.id())) {
                    // VON MOVE: Entity crossed S0-S5 tetrahedral boundary - spatial routing
                    // This follows VON principles: position determines ownership
                    // Mark for migration (can't modify collection during iteration)
                    entitiesToMigrate.add(entityId);
                    log.debug("VON MIGRATION: Entity {} from bubble {} to {} (boundary crossing at ({},{},{}))",
                        entityId, bubble.id(), newBubble.id(),
                        newPosition.x, newPosition.y, newPosition.z);
                }

                // Update position in current bubble (will be migrated after loop)
                bubble.updateEntityPosition(entityId, newPosition);
                velocities.put(entityId, newVelocity);
            }

            // VON MIGRATION PROTOCOL: Execute entity transfers between bubbles
            // This implements VON MOVE semantics at the entity level
            int migrationsExecuted = 0;
            for (var entityId : entitiesToMigrate) {
                var entityRecord = bubble.getAllEntityRecords().stream()
                    .filter(e -> e.id().equals(entityId))
                    .findFirst();

                if (entityRecord.isPresent()) {
                    var position = entityRecord.get().position();
                    var content = entityRecord.get().content();

                    // VON SPATIAL ROUTING: S0-S5 classification finds new acceptor bubble
                    var newBubble = cubeForest.getBubbleForPosition(position);

                    if (!newBubble.id().equals(bubble.id())) {
                        // VON HANDOFF: Atomic entity transfer with accountant tracking
                        // 1. Remove from old bubble (leave)
                        // 2. Add to new bubble (join)
                        // 3. Update accountant (ownership transfer)
                        bubble.removeEntity(entityId);
                        newBubble.addEntity(entityId, position, content);
                        accountant.moveBetweenBubbles(UUID.fromString(padToUUID(entityId)), bubble.id(), newBubble.id());
                        migrationsExecuted++;
                    }
                }
            }

            if (migrationsExecuted > 0) {
                log.debug("VON MIGRATION SUMMARY: {} entities migrated from bubble {}",
                    migrationsExecuted, bubble.id());
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

    /**
     * Extract tetrahedral vertices defining each bubble's EXCLUSIVE SPATIAL DOMAIN.
     * <p>
     * Returns 4 vertices of the tetrahedron from the spatial partition (TetreeKey).
     * These regions are NON-OVERLAPPING and tile the simulation space using S0-S5
     * characteristic tetrahedra. This shows which region each bubble OWNS for load
     * balancing, not where entities happen to be.
     * <p>
     * Vertex ordering: V0, V1, V2, V3 (tetrahedron corners)
     */
    private static Map<UUID, Point3f[]> extractBubbleVertices(TetreeBubbleGrid grid, List<EnhancedBubble> bubbles) {
        var vertices = new HashMap<UUID, Point3f[]>();
        var bubblesWithKeys = grid.getBubblesWithKeys();

        final float MORTON_MAX = 1 << 21;
        final float WORLD_SIZE = WORLD.max() - WORLD.min();
        final float scale = WORLD_SIZE / MORTON_MAX;

        log.info("extractBubbleVertices: Processing {} bubbles (S0-S5 non-overlapping partition)", bubbles.size());

        for (var bubble : bubbles) {
            try {
                // Find the TetreeKey for this bubble
                for (var entry : bubblesWithKeys.entrySet()) {
                    if (entry.getValue().id().equals(bubble.id())) {
                        var tetreeKey = entry.getKey();
                        var tet = tetreeKey.toTet();
                        var coords = tet.coordinates();

                        // Convert Morton space coordinates to world space
                        var bubbleVertices = new Point3f[4];
                        for (int i = 0; i < 4; i++) {
                            float x = coords[i].x * scale + WORLD.min();
                            float y = coords[i].y * scale + WORLD.min();
                            float z = coords[i].z * scale + WORLD.min();
                            bubbleVertices[i] = new Point3f(x, y, z);
                        }

                        vertices.put(bubble.id(), bubbleVertices);

                        log.info("Bubble {} (type {}) spatial domain: V0=({},{},{}) → V3=({},{},{})",
                            bubble.id(), tet.type(),
                            bubbleVertices[0].x, bubbleVertices[0].y, bubbleVertices[0].z,
                            bubbleVertices[3].x, bubbleVertices[3].y, bubbleVertices[3].z);
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to extract tetrahedral domain for bubble {}: {}", bubble.id(), e.getMessage());
            }
        }

        log.info("Extracted spatial domains for {} bubbles (non-overlapping S0-S5 partition)", vertices.size());
        return vertices;
    }

    /**
     * Helper to convert JavaFX Point3D to vecmath Point3f.
     */
    private static Point3f toPoint3f(javafx.geometry.Point3D point) {
        return new Point3f((float) point.getX(), (float) point.getY(), (float) point.getZ());
    }

    private static Map<UUID, Byte> extractBubbleTypes(TetreeBubbleGrid grid, List<EnhancedBubble> bubbles) {
        var types = new HashMap<UUID, Byte>();
        var bubblesWithKeys = grid.getBubblesWithKeys();

        for (var bubble : bubbles) {
            for (var entry : bubblesWithKeys.entrySet()) {
                if (entry.getValue().id().equals(bubble.id())) {
                    var tetreeKey = entry.getKey();
                    var tet = tetreeKey.toTet();
                    types.put(bubble.id(), tet.type());
                    break;
                }
            }
        }

        log.info("Extracted tetrahedral types (S0-S5) for {} bubbles", types.size());
        return types;
    }

    private static Map<UUID, Map<String, Object>> extractBubbleSpheres(TetreeBubbleGrid grid, List<EnhancedBubble> bubbles) {
        var spheres = new HashMap<UUID, Map<String, Object>>();
        var bubblesWithKeys = grid.getBubblesWithKeys();

        final float MORTON_MAX = 1 << 21;
        final float WORLD_SIZE = WORLD.max() - WORLD.min();
        final float scale = WORLD_SIZE / MORTON_MAX;

        log.info("extractBubbleSpheres: Computing domain centers for {} bubbles (S0-S5 partition)", bubbles.size());

        // Compute inscribed sphere of each tetrahedral spatial domain
        // This shows the center of each bubble's SPATIAL REGION (not entity distribution)
        for (var bubble : bubbles) {
            for (var entry : bubblesWithKeys.entrySet()) {
                if (entry.getValue().id().equals(bubble.id())) {
                    var tetreeKey = entry.getKey();
                    var tet = tetreeKey.toTet();
                    var coords = tet.coordinates();

                    // Calculate tetrahedron centroid: (v0+v1+v2+v3)/4
                    float cx = 0, cy = 0, cz = 0;
                    for (int i = 0; i < 4; i++) {
                        cx += coords[i].x;
                        cy += coords[i].y;
                        cz += coords[i].z;
                    }
                    cx = (cx / 4.0f) * scale + WORLD.min();
                    cy = (cy / 4.0f) * scale + WORLD.min();
                    cz = (cz / 4.0f) * scale + WORLD.min();

                    // Calculate inscribed sphere radius (avg edge length / 4)
                    float edgeSum = 0;
                    int edgeCount = 0;
                    for (int i = 0; i < 4; i++) {
                        for (int j = i + 1; j < 4; j++) {
                            float dx = (coords[i].x - coords[j].x) * scale;
                            float dy = (coords[i].y - coords[j].y) * scale;
                            float dz = (coords[i].z - coords[j].z) * scale;
                            edgeSum += (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                            edgeCount++;
                        }
                    }
                    float radius = edgeSum / (edgeCount * 4.0f);

                    var sphereData = new HashMap<String, Object>();
                    sphereData.put("center", new Point3f(cx, cy, cz));
                    sphereData.put("radius", radius);
                    spheres.put(bubble.id(), sphereData);

                    log.info("Bubble {} (type {}) domain center: ({},{},{}) radius={}",
                        bubble.id(), tet.type(),
                        String.format("%.1f", cx), String.format("%.1f", cy), String.format("%.1f", cz),
                        String.format("%.1f", radius));
                    break;
                }
            }
        }

        log.info("Extracted domain centers for {} bubbles (S0-S5 non-overlapping partition)", spheres.size());
        return spheres;
    }

    /**
     * Generate a random position inside a bubble's tetrahedral domain.
     * Uses rejection sampling: generate random point in AABB, accept if inside tetrahedron.
     */
    private static Point3f randomPositionInBubble(TetreeBubbleGrid grid, EnhancedBubble bubble) {
        var bubblesWithKeys = grid.getBubblesWithKeys();

        // Find the TetreeKey for this bubble
        for (var entry : bubblesWithKeys.entrySet()) {
            if (entry.getValue().id().equals(bubble.id())) {
                var tetreeKey = entry.getKey();
                var tet = tetreeKey.toTet();
                var coords = tet.coordinates();

                // Compute AABB of tetrahedron
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
                for (int i = 0; i < 4; i++) {
                    minX = Math.min(minX, coords[i].x);
                    minY = Math.min(minY, coords[i].y);
                    minZ = Math.min(minZ, coords[i].z);
                    maxX = Math.max(maxX, coords[i].x);
                    maxY = Math.max(maxY, coords[i].y);
                    maxZ = Math.max(maxZ, coords[i].z);
                }

                // Scale to world space
                final float MORTON_MAX = 1 << 21;
                final float WORLD_SIZE = WORLD.max() - WORLD.min();
                final float scale = WORLD_SIZE / MORTON_MAX;

                minX = minX * scale + WORLD.min();
                minY = minY * scale + WORLD.min();
                minZ = minZ * scale + WORLD.min();
                maxX = maxX * scale + WORLD.min();
                maxY = maxY * scale + WORLD.min();
                maxZ = maxZ * scale + WORLD.min();

                // Rejection sampling: generate random point in AABB until inside tetrahedron
                int attempts = 0;
                while (attempts < 100) {
                    float x = minX + RANDOM.nextFloat() * (maxX - minX);
                    float y = minY + RANDOM.nextFloat() * (maxY - minY);
                    float z = minZ + RANDOM.nextFloat() * (maxZ - minZ);

                    // Convert back to Morton space for containment test
                    float mx = (x - WORLD.min()) / scale;
                    float my = (y - WORLD.min()) / scale;
                    float mz = (z - WORLD.min()) / scale;

                    if (tet.containsUltraFast(mx, my, mz)) {
                        return new Point3f(x, y, z);
                    }
                    attempts++;
                }

                // Fallback: use tetrahedron centroid
                float cx = 0, cy = 0, cz = 0;
                for (int i = 0; i < 4; i++) {
                    cx += coords[i].x;
                    cy += coords[i].y;
                    cz += coords[i].z;
                }
                cx = (cx / 4.0f) * scale + WORLD.min();
                cy = (cy / 4.0f) * scale + WORLD.min();
                cz = (cz / 4.0f) * scale + WORLD.min();
                return new Point3f(cx, cy, cz);
            }
        }

        // Fallback: random position in world
        return randomPosition();
    }

    /**
     * Check if a position is inside a bubble's tetrahedral domain.
     */
    private static boolean isPositionInBubble(TetreeBubbleGrid grid, EnhancedBubble bubble, Point3f position) {
        var bubblesWithKeys = grid.getBubblesWithKeys();

        // Find the TetreeKey for this bubble
        for (var entry : bubblesWithKeys.entrySet()) {
            if (entry.getValue().id().equals(bubble.id())) {
                var tetreeKey = entry.getKey();
                var tet = tetreeKey.toTet();

                // Convert world position to Morton space
                final float MORTON_MAX = 1 << 21;
                final float WORLD_SIZE = WORLD.max() - WORLD.min();
                final float scale = WORLD_SIZE / MORTON_MAX;

                float mx = (position.x - WORLD.min()) / scale;
                float my = (position.y - WORLD.min()) / scale;
                float mz = (position.z - WORLD.min()) / scale;

                return tet.containsUltraFast(mx, my, mz);
            }
        }

        return false;
    }

    /**
     * VON-STYLE SPATIAL ROUTING: Find which bubble owns a given position.
     * <p>
     * This implements the core VON principle of spatial routing - given a position,
     * find which bubble's domain contains it using the tetrahedral spatial index.
     * <p>
     * VON Architecture:
     * - Each bubble owns an exclusive tetrahedral spatial domain
     * - Entities spawn/migrate based on which domain contains their position
     * - This routing method is the foundation for entity join/move operations
     * <p>
     * In a distributed system, this would involve:
     * 1. Greedy forwarding through neighbor bubbles (routing hops)
     * 2. Eventually reaching the bubble whose domain contains the position
     * 3. That bubble becomes the "acceptor" and takes ownership
     * <p>
     * In this single-process demo, we can directly check all bubbles,
     * but the principle is the same: spatial domain ownership determines routing.
     *
     * @param grid     The tetree grid managing spatial domains
     * @param bubbles  All bubbles in the system
     * @param position The position to route to
     * @return The bubble whose domain contains this position (the acceptor)
     */
    private static EnhancedBubble findOwningBubble(TetreeBubbleGrid grid, List<EnhancedBubble> bubbles, Point3f position) {
        var bubblesWithKeys = grid.getBubblesWithKeys();

        // Convert world position to Morton space once
        final float MORTON_MAX = 1 << 21;
        final float WORLD_SIZE = WORLD.max() - WORLD.min();
        final float scale = WORLD_SIZE / MORTON_MAX;

        float mx = (position.x - WORLD.min()) / scale;
        float my = (position.y - WORLD.min()) / scale;
        float mz = (position.z - WORLD.min()) / scale;

        // VON SPATIAL ROUTING: Check all bubbles to find which domain contains the position
        // In distributed VON: this would be greedy forwarding through neighbors
        // In single-process: we can directly check all bubbles
        for (var bubble : bubbles) {
            for (var entry : bubblesWithKeys.entrySet()) {
                if (entry.getValue().id().equals(bubble.id())) {
                    var tetreeKey = entry.getKey();
                    var tet = tetreeKey.toTet();

                    // Tetrahedral containment test - the "acceptor" check
                    if (tet.containsUltraFast(mx, my, mz)) {
                        return bubble; // This bubble's domain contains the position
                    }
                    break;
                }
            }
        }

        return null; // Position not in any bubble (shouldn't happen with complete partition)
    }

    /**
     * Extract S0-S5 tetrahedral vertices for visualization.
     * Uses the documented S0-S5 cube decomposition where all 6 tetrahedra share V0 and V7.
     */
    private static Map<UUID, Point3f[]> extractS0S5Vertices(CubeForest cubeForest) {
        var vertices = new HashMap<UUID, Point3f[]>();
        var bubblesByType = cubeForest.getBubblesByType();
        float[] bounds = cubeForest.getWorldBounds();
        float min = bounds[0];
        float max = bounds[1];

        // S0-S5 characteristic tetrahedra (all share V0=(0,0,0) and V7=(h,h,h))
        // Type 0 (S0): {0,1,3,7} = {(0,0,0), (h,0,0), (h,h,0), (h,h,h)}
        // Type 1 (S1): {0,2,3,7} = {(0,0,0), (0,h,0), (h,h,0), (h,h,h)}
        // Type 2 (S2): {0,4,5,7} = {(0,0,0), (0,0,h), (h,0,h), (h,h,h)}
        // Type 3 (S3): {0,4,6,7} = {(0,0,0), (0,0,h), (0,h,h), (h,h,h)}
        // Type 4 (S4): {0,1,5,7} = {(0,0,0), (h,0,0), (h,0,h), (h,h,h)}
        // Type 5 (S5): {0,2,6,7} = {(0,0,0), (0,h,0), (0,h,h), (h,h,h)}

        Point3f[][] typeVertices = {
            {new Point3f(min, min, min), new Point3f(max, min, min), new Point3f(max, max, min), new Point3f(max, max, max)}, // S0
            {new Point3f(min, min, min), new Point3f(min, max, min), new Point3f(max, max, min), new Point3f(max, max, max)}, // S1
            {new Point3f(min, min, min), new Point3f(min, min, max), new Point3f(max, min, max), new Point3f(max, max, max)}, // S2
            {new Point3f(min, min, min), new Point3f(min, min, max), new Point3f(min, max, max), new Point3f(max, max, max)}, // S3
            {new Point3f(min, min, min), new Point3f(max, min, min), new Point3f(max, min, max), new Point3f(max, max, max)}, // S4
            {new Point3f(min, min, min), new Point3f(min, max, min), new Point3f(min, max, max), new Point3f(max, max, max)}  // S5
        };

        for (byte type = 0; type < 6; type++) {
            var bubble = bubblesByType.get(type);
            vertices.put(bubble.id(), typeVertices[type]);
        }

        return vertices;
    }

    /**
     * Extract S0-S5 types for visualization.
     */
    private static Map<UUID, Byte> extractS0S5Types(CubeForest cubeForest) {
        var types = new HashMap<UUID, Byte>();
        var bubblesByType = cubeForest.getBubblesByType();

        for (byte type = 0; type < 6; type++) {
            var bubble = bubblesByType.get(type);
            types.put(bubble.id(), type);
        }

        return types;
    }

    /**
     * Extract S0-S5 tetrahedral centroids (inscribed spheres) for visualization.
     */
    private static Map<UUID, Map<String, Object>> extractS0S5Spheres(CubeForest cubeForest) {
        var spheres = new HashMap<UUID, Map<String, Object>>();
        var bubblesByType = cubeForest.getBubblesByType();
        float[] bounds = cubeForest.getWorldBounds();
        float min = bounds[0];
        float max = bounds[1];
        float h = max - min;

        for (byte type = 0; type < 6; type++) {
            var bubble = bubblesByType.get(type);

            // Tetrahedron centroid = average of 4 vertices
            // All S0-S5 share V0=(0,0,0) and V7=(h,h,h), differ in V1 and V2
            // Centroid for any S0-S5 tet = (V0 + V1 + V2 + V7) / 4

            Point3f[] verts = null;
            switch (type) {
                case 0 -> verts = new Point3f[]{new Point3f(min,min,min), new Point3f(max,min,min), new Point3f(max,max,min), new Point3f(max,max,max)};
                case 1 -> verts = new Point3f[]{new Point3f(min,min,min), new Point3f(min,max,min), new Point3f(max,max,min), new Point3f(max,max,max)};
                case 2 -> verts = new Point3f[]{new Point3f(min,min,min), new Point3f(min,min,max), new Point3f(max,min,max), new Point3f(max,max,max)};
                case 3 -> verts = new Point3f[]{new Point3f(min,min,min), new Point3f(min,min,max), new Point3f(min,max,max), new Point3f(max,max,max)};
                case 4 -> verts = new Point3f[]{new Point3f(min,min,min), new Point3f(max,min,min), new Point3f(max,min,max), new Point3f(max,max,max)};
                case 5 -> verts = new Point3f[]{new Point3f(min,min,min), new Point3f(min,max,min), new Point3f(min,max,max), new Point3f(max,max,max)};
            }

            float cx = (verts[0].x + verts[1].x + verts[2].x + verts[3].x) / 4.0f;
            float cy = (verts[0].y + verts[1].y + verts[2].y + verts[3].y) / 4.0f;
            float cz = (verts[0].z + verts[1].z + verts[2].z + verts[3].z) / 4.0f;

            // Inscribed sphere radius = avg edge length / 4
            float radius = h / 4.0f;

            var sphereData = new HashMap<String, Object>();
            sphereData.put("center", new Point3f(cx, cy, cz));
            sphereData.put("radius", radius);
            spheres.put(bubble.id(), sphereData);
        }

        return spheres;
    }
}
