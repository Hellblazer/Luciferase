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
import com.hellblazer.luciferase.simulation.topology.TopologyExecutor;
import com.hellblazer.luciferase.simulation.topology.TopologyMetrics;
import com.hellblazer.luciferase.simulation.topology.metrics.DensityMonitor;
import com.hellblazer.luciferase.simulation.topology.metrics.DensityState;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
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

        // Phase 1: Initialize TetreeBubbleGrid with dynamic topology support
        var bubbleGrid = new TetreeBubbleGrid((byte) 2);
        var accountant = new EntityAccountant();
        var metrics = new TopologyMetrics();
        var executor = new TopologyExecutor(bubbleGrid, accountant, metrics);

        // Create 4 initial bubbles
        bubbleGrid.createBubbles(4, (byte) 2, 10);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        log.info("Phase 1: Created {} initial bubbles", bubbles.size());

        // Phase 2: Initialize density monitoring with automatic topology proposals
        // Realistic thresholds for 1000 entities across 4-8 bubbles:
        // - Split threshold: 250 entities (triggers split proposal)
        // - Merge threshold: 100 entities (triggers merge proposal)
        var densityMonitor = new DensityMonitor(250, 100);

        var preyBehavior = new PreyBehavior();
        var predatorBehavior = new PackHuntingBehavior();

        var entityVelocities = new ConcurrentHashMap<String, Vector3f>();
        var entityBehaviors = new ConcurrentHashMap<String, Object>();

        // Create intentionally uneven distribution to trigger topology changes:
        // Start with 4 bubbles, create density pressure to trigger splits
        int bubbleCount = bubbles.size();
        int[] targetCounts = new int[bubbleCount];

        if (bubbleCount >= 1) targetCounts[0] = 300; // NEEDS_SPLIT (will trigger split)
        if (bubbleCount >= 2) targetCounts[1] = 230; // APPROACHING_SPLIT
        if (bubbleCount >= 3) targetCounts[2] = 150; // NORMAL
        if (bubbleCount >= 4) targetCounts[3] = 85; // NEEDS_MERGE (will trigger merge later)

        // Adjust to ensure total = TOTAL_ENTITIES
        int total = 0;
        for (int count : targetCounts) total += count;
        if (total < TOTAL_ENTITIES && bubbleCount > 0) {
            targetCounts[0] += (TOTAL_ENTITIES - total);
        }

        log.info("Target distribution: {}", java.util.Arrays.toString(targetCounts));

        // Spawn prey entities
        int preyDistributed = 0;
        for (int bubbleIdx = 0; bubbleIdx < bubbleCount && preyDistributed < PREY_COUNT; bubbleIdx++) {
            var bubble = bubbles.get(bubbleIdx);
            int preyForThisBubble = Math.min(
                (int) (targetCounts[bubbleIdx] * 0.9), // 90% prey
                PREY_COUNT - preyDistributed
            );

            for (int i = 0; i < preyForThisBubble; i++) {
                var entityId = "prey-" + preyDistributed++;
                var position = randomPosition();
                var velocity = randomVelocity(preyBehavior.getMaxSpeed());

                bubble.addEntity(entityId, position, EntityType.PREY);
                accountant.register(bubble.id(), UUID.fromString(padToUUID(entityId)));
                entityVelocities.put(entityId, velocity);
                entityBehaviors.put(entityId, preyBehavior);
            }
        }

        // Spawn predator entities
        int predatorDistributed = 0;
        for (int bubbleIdx = 0; bubbleIdx < bubbleCount && predatorDistributed < PREDATOR_COUNT; bubbleIdx++) {
            var bubble = bubbles.get(bubbleIdx);
            int predatorsForThisBubble = Math.min(
                targetCounts[bubbleIdx] - (int)(targetCounts[bubbleIdx] * 0.9),
                PREDATOR_COUNT - predatorDistributed
            );

            for (int i = 0; i < predatorsForThisBubble; i++) {
                var entityId = "predator-" + predatorDistributed++;
                var position = randomPosition();
                var velocity = randomVelocity(predatorBehavior.getMaxSpeed());

                bubble.addEntity(entityId, position, EntityType.PREDATOR);
                accountant.register(bubble.id(), UUID.fromString(padToUUID(entityId)));
                entityVelocities.put(entityId, velocity);
                entityBehaviors.put(entityId, predatorBehavior);
            }
        }

        // Phase 3: Start visualization server
        var vizServer = new MultiBubbleVisualizationServer(port);

        // Extract tetrahedral geometries from TetreeBubbleGrid
        var bubbleVertices = extractTetreeVertices(bubbleGrid);
        var bubbleTypes = extractTetreeTypes(bubbleGrid);
        var bubbleSpheres = extractTetreeSpheres(bubbleGrid);
        vizServer.setBubbleVertices(bubbleVertices);
        vizServer.setBubbleTypes(bubbleTypes);
        vizServer.setBubbleSpheres(bubbleSpheres);
        vizServer.setBubbles(bubbles);

        // Wire up topology event streaming
        densityMonitor.addListener(vizServer.getTopologyEventStream());
        executor.addListener(vizServer.getTopologyEventStream());
        vizServer.setDensityMonitor(densityMonitor);

        // Log initial distribution
        log.info("=== Initial Entity Distribution ===");
        var initialDistribution = accountant.getDistribution();
        for (int i = 0; i < bubbles.size(); i++) {
            var bubble = bubbles.get(i);
            var count = initialDistribution.getOrDefault(bubble.id(), 0);
            var state = densityMonitor.getState(bubble.id());
            log.info("Bubble {}: {} entities - State: {}", i, count, state);
        }

        // Trigger initial density update
        densityMonitor.update(initialDistribution);

        log.info("=== Dynamic Topology Configuration ===");
        log.info("  - Split threshold: 250 entities (triggers automatic split proposal)");
        log.info("  - Merge threshold: 100 entities (triggers automatic merge proposal)");
        log.info("  - WebSocket endpoint: ws://localhost:{}/ws/topology (real-time topology events)", port);
        log.info("  - Density metrics API: http://localhost:{}/api/density", port);
        log.info("  - Dynamic topology: ENABLED (bubbles will actually split/merge/move)");

        vizServer.start();

        log.info("Dynamic Topology Demo: http://localhost:{}/predator-prey-grid.html", port);

        // Phase 4: Start PrimeMover simulation
        var controller = new RealTimeController("PredatorPreyGridWebDemo");
        var entity = new SimulationEntity(
            bubbleGrid,
            entityVelocities,
            entityBehaviors,
            preyBehavior,
            accountant,
            executor,
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
        private static final int TOPOLOGY_CHECK_INTERVAL = 200; // Check for topology changes every 200 ticks (~10 seconds)

        private final TetreeBubbleGrid bubbleGrid;
        private final Map<String, Vector3f> velocities;
        private final Map<String, Object> behaviors;
        private final PreyBehavior preyBehavior;
        private final EntityAccountant accountant;
        private final TopologyExecutor executor;
        private final MultiBubbleVisualizationServer vizServer;
        private final DensityMonitor densityMonitor;

        private int currentTick = 0;

        public SimulationEntity(
            TetreeBubbleGrid bubbleGrid,
            Map<String, Vector3f> velocities,
            Map<String, Object> behaviors,
            PreyBehavior preyBehavior,
            EntityAccountant accountant,
            TopologyExecutor executor,
            MultiBubbleVisualizationServer vizServer,
            DensityMonitor densityMonitor
        ) {
            this.bubbleGrid = bubbleGrid;
            this.velocities = velocities;
            this.behaviors = behaviors;
            this.preyBehavior = preyBehavior;
            this.accountant = accountant;
            this.executor = executor;
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
            var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());
            for (var bubble : bubbles) {
                updateBubbleEntities(bubble);
            }

            // Update density metrics periodically
            if (currentTick % DENSITY_UPDATE_INTERVAL == 0) {
                var distribution = accountant.getDistribution();
                densityMonitor.update(distribution);
            }

            // Check for topology changes periodically
            if (currentTick % TOPOLOGY_CHECK_INTERVAL == 0) {
                checkAndExecuteTopologyChanges();
            }

            // Log progress every 100 ticks with density states
            if (currentTick % 100 == 0) {
                var distribution = accountant.getDistribution();
                int total = distribution.values().stream().mapToInt(Integer::intValue).sum();
                log.info("Tick {}: {} entities across {} bubbles", currentTick, total, bubbles.size());

                // Log current density states
                var statesSummary = new StringBuilder("Density states: [");
                for (int i = 0; i < bubbles.size(); i++) {
                    var bubble = bubbles.get(i);
                    var count = distribution.getOrDefault(bubble.id(), 0);
                    var state = densityMonitor.getState(bubble.id());
                    if (i > 0) statesSummary.append(", ");
                    statesSummary.append(String.format("B%d:%d-%s", i, count, state));
                }
                statesSummary.append("]");
                log.info(statesSummary.toString());
            }

            currentTick++;

            // Schedule next tick (PrimeMover recursive event scheduling)
            Kronos.sleep(TICK_INTERVAL_NS);
            this.simulationTick();
        }

        /**
         * Check density states and execute topology changes when thresholds exceeded.
         * <p>
         * Executes splits when bubbles exceed 250 entities (NEEDS_SPLIT state).
         * Executes merges when bubbles drop below 100 entities (NEEDS_MERGE state).
         */
        private void checkAndExecuteTopologyChanges() {
            var distribution = accountant.getDistribution();
            var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

            // Check each bubble for split candidates
            for (var bubble : bubbles) {
                var count = distribution.getOrDefault(bubble.id(), 0);
                var state = densityMonitor.getState(bubble.id());

                if (state == DensityState.NEEDS_SPLIT && count > 250) {
                    log.info("EXECUTING SPLIT: Bubble {} has {} entities (>250 threshold) - state: {}",
                             bubble.id(), count, state);

                    // Create split plane through bubble centroid
                    var centroid = bubble.bounds().centroid();
                    var splitPlane = new SplitPlane(
                        new Point3f(1.0f, 0.0f, 0.0f),  // X-axis normal
                        (float) centroid.getX()
                    );

                    var splitProposal = new SplitProposal(
                        UUID.randomUUID(),
                        bubble.id(),
                        splitPlane,
                        DigestAlgorithm.DEFAULT.getOrigin(),
                        System.currentTimeMillis()
                    );

                    var result = executor.execute(splitProposal);
                    if (result.success()) {
                        log.info("Split successful: {}", result.message());
                        updateVisualizationGeometries();
                    } else {
                        log.warn("Split failed: {}", result.message());
                    }
                }
            }

            // Check for merge candidates (adjacent bubbles both below threshold)
            bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());  // Refresh after splits
            for (int i = 0; i < bubbles.size(); i++) {
                var bubble1 = bubbles.get(i);
                var count1 = distribution.getOrDefault(bubble1.id(), 0);
                var state1 = densityMonitor.getState(bubble1.id());

                if (state1 == DensityState.NEEDS_MERGE && count1 < 100) {
                    // Find adjacent bubble also needing merge
                    var neighbors = bubbleGrid.getNeighbors(bubble1.id());
                    for (var neighborId : neighbors) {
                        var neighbor = bubbleGrid.getBubbleById(neighborId);
                        if (neighbor == null) continue;

                        var count2 = distribution.getOrDefault(neighborId, 0);
                        var state2 = densityMonitor.getState(neighborId);

                        if (state2 == DensityState.NEEDS_MERGE && count2 < 100) {
                            log.info("EXECUTING MERGE: Bubbles {} ({} entities) and {} ({} entities)",
                                     bubble1.id(), count1, neighborId, count2);

                            var mergeProposal = new MergeProposal(
                                UUID.randomUUID(),
                                bubble1.id(),
                                neighborId,
                                DigestAlgorithm.DEFAULT.getOrigin(),
                                System.currentTimeMillis()
                            );

                            var result = executor.execute(mergeProposal);
                            if (result.success()) {
                                log.info("Merge successful: {}", result.message());
                                updateVisualizationGeometries();
                                break;  // Only merge once per check
                            } else {
                                log.warn("Merge failed: {}", result.message());
                            }
                        }
                    }
                }
            }
        }

        /**
         * Update visualization geometries after topology change.
         */
        private void updateVisualizationGeometries() {
            var newBubbleVertices = extractTetreeVertices(bubbleGrid);
            var newBubbleTypes = extractTetreeTypes(bubbleGrid);
            var newBubbleSpheres = extractTetreeSpheres(bubbleGrid);
            vizServer.setBubbleVertices(newBubbleVertices);
            vizServer.setBubbleTypes(newBubbleTypes);
            vizServer.setBubbleSpheres(newBubbleSpheres);
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

                // Update position in bubble (boundary crossing simplified for demo)
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

    // Wrapper methods for TetreeBubbleGrid extraction
    private static Map<UUID, Point3f[]> extractTetreeVertices(TetreeBubbleGrid grid) {
        var bubbles = new ArrayList<>(grid.getAllBubbles());
        return extractBubbleVertices(grid, bubbles);
    }

    private static Map<UUID, Byte> extractTetreeTypes(TetreeBubbleGrid grid) {
        var bubbles = new ArrayList<>(grid.getAllBubbles());
        return extractBubbleTypes(grid, bubbles);
    }

    private static Map<UUID, Map<String, Object>> extractTetreeSpheres(TetreeBubbleGrid grid) {
        var bubbles = new ArrayList<>(grid.getAllBubbles());
        return extractBubbleSpheres(grid, bubbles);
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

}
