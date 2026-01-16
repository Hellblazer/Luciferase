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
 * # PrimeMover bytecode transformation requires process-classes phase first
 * mvn process-classes exec:java -pl simulation \
 *   -Dexec.mainClass=com.hellblazer.luciferase.simulation.viz.PredatorPreyGridDemo \
 *   -Dexec.args="7081"
 * </pre>
 * Then open: http://localhost:7081/predator-prey-grid.html
 * <p>
 * Note: PrimeMover transforms {@code @Entity} classes during the {@code process-classes} phase.
 * Running {@code mvn exec:java} alone will fail with "should have been rewritten" error.
 */
public class PredatorPreyGridDemo {

    private static final Logger log = LoggerFactory.getLogger(PredatorPreyGridDemo.class);

    private static final int TOTAL_ENTITIES = 1810;
    private static final int PREY_COUNT = 1800;
    private static final int PREDATOR_COUNT = 10;
    private static final long TICK_INTERVAL_NS = 50_000_000; // 50ms = 20 TPS
    private static final WorldBounds WORLD = WorldBounds.DEFAULT;
    private static final Random RANDOM = new Random(42);

    public static void main(String[] args) {
        var port = args.length > 0 ? Integer.parseInt(args[0]) : 7081;

        log.info("=== Grand Vision: Pack Hunting Predator-Prey Grid ===");
        log.info("Vision: 4x4x4 tetree grid, 1800 prey + 10 pack-hunting predators");
        log.info("Using PrimeMover discrete event simulation (NOT thread loops!)");

        // Phase 1: Initialize 4x4x4 tetree grid
        log.info("Phase 1: Initialize 4x4x4 Tetree Grid");
        var bubbleGrid = new TetreeBubbleGrid((byte) 3);

        // Create a SPATIAL grid instead of hierarchical tree
        // For 4x4x4 grid in world [0,200], use cell size of 50
        var bubbles = createSpatialGrid(bubbleGrid, 4, 50f, 10);
        log.info("Created {} spatially distributed tetrahedral bubbles", bubbles.size());

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

        // Extract tetrahedral vertices, types, and inscribed spheres for proper visualization
        var bubbleVertices = extractBubbleVertices(bubbleGrid, bubbles);
        var bubbleTypes = extractBubbleTypes(bubbleGrid, bubbles);
        var bubbleSpheres = extractBubbleSpheres(bubbleGrid, bubbles);
        vizServer.setBubbleVertices(bubbleVertices);
        vizServer.setBubbleTypes(bubbleTypes);
        vizServer.setBubbleSpheres(bubbleSpheres);

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

    /**
     * Extract RDGCS bounding box corners from actual simulation volumes.
     * <p>
     * Returns 8 vertices defining the axis-aligned bounding box where entities actually live.
     * This is NOT the tetrahedral spatial index - it's the actual simulation volume.
     * <p>
     * Box corner ordering:
     * 0: (minX, minY, minZ)  4: (minX, minY, maxZ)
     * 1: (maxX, minY, minZ)  5: (maxX, minY, maxZ)
     * 2: (maxX, maxY, minZ)  6: (maxX, maxY, maxZ)
     * 3: (minX, maxY, minZ)  7: (minX, maxY, maxZ)
     */
    private static Map<UUID, Point3f[]> extractBubbleVertices(TetreeBubbleGrid grid, List<EnhancedBubble> bubbles) {
        var vertices = new HashMap<UUID, Point3f[]>();

        for (var bubble : bubbles) {
            try {
                // Get all entity positions in world space
                var entities = bubble.getAllEntityRecords();
                if (entities.isEmpty()) {
                    log.debug("Bubble {} has no entities yet", bubble.id());
                    continue;
                }

                // Compute axis-aligned bounding box from actual entity positions
                float minX = Float.POSITIVE_INFINITY;
                float minY = Float.POSITIVE_INFINITY;
                float minZ = Float.POSITIVE_INFINITY;
                float maxX = Float.NEGATIVE_INFINITY;
                float maxY = Float.NEGATIVE_INFINITY;
                float maxZ = Float.NEGATIVE_INFINITY;

                for (var entity : entities) {
                    var pos = entity.position();
                    minX = Math.min(minX, pos.x);
                    minY = Math.min(minY, pos.y);
                    minZ = Math.min(minZ, pos.z);
                    maxX = Math.max(maxX, pos.x);
                    maxY = Math.max(maxY, pos.y);
                    maxZ = Math.max(maxZ, pos.z);
                }

                // Create 8 box corners in world space
                var bubbleVertices = new Point3f[8];

                // Bottom face (minZ)
                bubbleVertices[0] = new Point3f(minX, minY, minZ);
                bubbleVertices[1] = new Point3f(maxX, minY, minZ);
                bubbleVertices[2] = new Point3f(maxX, maxY, minZ);
                bubbleVertices[3] = new Point3f(minX, maxY, minZ);

                // Top face (maxZ)
                bubbleVertices[4] = new Point3f(minX, minY, maxZ);
                bubbleVertices[5] = new Point3f(maxX, minY, maxZ);
                bubbleVertices[6] = new Point3f(maxX, maxY, maxZ);
                bubbleVertices[7] = new Point3f(minX, maxY, maxZ);

                // Debug: log first 3 bubbles to verify coordinates
                if (vertices.size() < 3) {
                    log.info("Bubble {} world-space AABB: ({},{},{}) to ({},{},{}) - {} entities",
                        vertices.size(), minX, minY, minZ, maxX, maxY, maxZ, entities.size());
                }

                vertices.put(bubble.id(), bubbleVertices);
            } catch (Exception e) {
                log.warn("Failed to extract world-space AABB for bubble {}: {}", bubble.id(), e.getMessage());
            }
        }

        log.info("Extracted world-space AABBs for {} bubbles", vertices.size());
        return vertices;
    }

    /**
     * Helper to convert JavaFX Point3D to vecmath Point3f.
     */
    private static Point3f toPoint3f(javafx.geometry.Point3D point) {
        return new Point3f((float) point.getX(), (float) point.getY(), (float) point.getZ());
    }

    /**
     * Extract tetrahedral types for visualization color-coding.
     */
    private static Map<UUID, Byte> extractBubbleTypes(TetreeBubbleGrid grid, List<EnhancedBubble> bubbles) {
        var types = new HashMap<UUID, Byte>();
        var bubblesWithKeys = grid.getBubblesWithKeys();

        for (var bubble : bubbles) {
            try {
                for (var entry : bubblesWithKeys.entrySet()) {
                    if (entry.getValue().id().equals(bubble.id())) {
                        var tetreeKey = entry.getKey();
                        var tet = tetreeKey.toTet();
                        types.put(bubble.id(), tet.type());
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to extract type for bubble {}: {}", bubble.id(), e.getMessage());
            }
        }

        log.info("Extracted tetrahedral types for {} bubbles", types.size());
        return types;
    }

    /**
     * Extract inscribed sphere data (center and radius) for each bubble.
     * Returns map from bubble UUID to {center: Point3f, radius: float}
     */
    private static Map<UUID, Map<String, Object>> extractBubbleSpheres(TetreeBubbleGrid grid, List<EnhancedBubble> bubbles) {
        var spheres = new HashMap<UUID, Map<String, Object>>();
        var bubblesWithKeys = grid.getBubblesWithKeys();

        // Tetree coordinates are in Morton space [0, 2^21]
        // World space is [0, 200]
        final float MORTON_MAX = 1 << 21;
        final float WORLD_SIZE = WORLD.size();
        final float scale = WORLD_SIZE / MORTON_MAX;

        for (var bubble : bubbles) {
            try {
                for (var entry : bubblesWithKeys.entrySet()) {
                    if (entry.getValue().id().equals(bubble.id())) {
                        var tetreeKey = entry.getKey();
                        var tet = tetreeKey.toTet();
                        var coords = tet.coordinates();

                        // Calculate centroid (center of inscribed sphere)
                        float cx = 0, cy = 0, cz = 0;
                        for (int i = 0; i < 4; i++) {
                            cx += coords[i].x;
                            cy += coords[i].y;
                            cz += coords[i].z;
                        }
                        cx = (cx / 4.0f) * scale + WORLD.min();
                        cy = (cy / 4.0f) * scale + WORLD.min();
                        cz = (cz / 4.0f) * scale + WORLD.min();

                        // Approximate inscribed sphere radius as distance from centroid to nearest face
                        // For simplicity, use average edge length / 4 as a reasonable approximation
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
                        float avgEdge = edgeSum / edgeCount;
                        float radius = avgEdge / 4.0f; // Conservative inscribed sphere approximation

                        var sphereData = new HashMap<String, Object>();
                        sphereData.put("center", new Point3f(cx, cy, cz));
                        sphereData.put("radius", radius);
                        spheres.put(bubble.id(), sphereData);
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to extract sphere for bubble {}: {}", bubble.id(), e.getMessage());
            }
        }

        log.info("Extracted inscribed spheres for {} bubbles", spheres.size());
        return spheres;
    }

    /**
     * Create a spatially distributed grid of bubbles instead of hierarchical tree.
     * This creates bubbles at regular positions across the world space.
     *
     * @param grid TetreeBubbleGrid to add bubbles to
     * @param gridSize Size of grid in each dimension (e.g., 4 for 4x4x4)
     * @param cellSize Size of each grid cell in world units
     * @param targetFrameMs Target frame time for each bubble
     * @return List of created bubbles
     */
    private static List<EnhancedBubble> createSpatialGrid(TetreeBubbleGrid grid, int gridSize, float cellSize, long targetFrameMs) {
        var bubbles = new ArrayList<EnhancedBubble>();

        // For each grid position, create one bubble with spatially-varying type
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                for (int z = 0; z < gridSize; z++) {
                    // Calculate world position
                    float worldX = WORLD.min() + x * cellSize;
                    float worldY = WORLD.min() + y * cellSize;
                    float worldZ = WORLD.min() + z * cellSize;

                    // Convert to Morton space coordinates
                    final float MORTON_MAX = 1 << 21; // 2^21
                    final float scale = MORTON_MAX / WORLD.size();
                    int mortonX = (int) ((worldX - WORLD.min()) * scale);
                    int mortonY = (int) ((worldY - WORLD.min()) * scale);
                    int mortonZ = (int) ((worldZ - WORLD.min()) * scale);

                    // Level calculation: smaller cells = higher level
                    byte level = (byte) Math.max(0, Math.min(20, (int) (Math.log(MORTON_MAX / (cellSize * scale)) / Math.log(2))));

                    // Assign tetrahedral type based on position (creates colorful 3D pattern)
                    byte type = (byte) ((x + y + z) % 6);

                    var tet = new com.hellblazer.luciferase.lucien.tetree.Tet(
                        mortonX, mortonY, mortonZ, level, type
                    );

                    // Create bubble and add to grid
                    var bubble = new EnhancedBubble(UUID.randomUUID(), level, targetFrameMs);
                    var tetreeKey = tet.tmIndex();

                    // Add to grid (this will register it in the spatial index)
                    try {
                        grid.addBubble(bubble, tetreeKey);
                        bubbles.add(bubble);
                        log.debug("Created bubble type {} at world ({},{},{}) morton ({},{},{}) level {}",
                            type, (int) worldX, (int) worldY, (int) worldZ,
                            mortonX, mortonY, mortonZ, level);
                    } catch (Exception e) {
                        log.warn("Failed to add bubble type {} at ({},{},{}): {}", type, worldX, worldY, worldZ, e.getMessage());
                    }
                }
            }
        }

        return bubbles;
    }
}
