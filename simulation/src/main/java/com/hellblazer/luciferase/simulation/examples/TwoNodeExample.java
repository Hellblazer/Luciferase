/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.examples;

import com.hellblazer.luciferase.simulation.behavior.PreyBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.network.BubbleNetworkChannel;
import com.hellblazer.luciferase.simulation.distributed.network.GrpcBubbleNetworkChannel;
import com.hellblazer.luciferase.simulation.entity.EntityType;
import com.hellblazer.luciferase.simulation.events.EntityDepartureEvent;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.annotations.NonEvent;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.primeMover.controllers.RealTimeController;
import com.hellblazer.primeMover.runtime.Kairos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Two-node distributed volumetric animation example.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>2 separate JVM processes communicating via gRPC
 *   <li>Entity migration across network when crossing spatial boundary
 *   <li>Real-time simulation at 20 TPS (50ms ticks)
 *   <li>EnhancedBubble with GrpcBubbleNetworkChannel integration
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   java TwoNodeExample <nodeName> <serverPort> <peerPort>
 *
 *   Example (terminal 1):
 *   java TwoNodeExample Node1 9000 9001
 *
 *   Example (terminal 2):
 *   java TwoNodeExample Node2 9001 9000
 * </pre>
 *
 * <p>Node Configuration:
 * <ul>
 *   <li>Node1: Bounds (0-50, 0-50, 0-50), spawns 50 entities
 *   <li>Node2: Bounds (50-100, 0-50, 0-50), receives migrated entities
 * </ul>
 *
 * @author hal.hildebrand
 */
public class TwoNodeExample {

    private static final Logger log = LoggerFactory.getLogger(TwoNodeExample.class);

    private static final long TICK_INTERVAL_NS = 50_000_000; // 50ms = 20 TPS
    private static final int ENTITY_COUNT = 50;
    private static final Random RANDOM = new Random(42);
    private static final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: TwoNodeExample <nodeName> <serverPort> <peerPort>");
            System.err.println("  nodeName: Node1 or Node2");
            System.err.println("  serverPort: gRPC port for this node");
            System.err.println("  peerPort: gRPC port for peer node");
            System.exit(1);
        }

        var nodeName = args[0];
        var serverPort = Integer.parseInt(args[1]);
        var peerPort = Integer.parseInt(args[2]);

        log.info("[{}] Starting TwoNodeExample", nodeName);
        log.info("[{}] Server port: {}, Peer port: {}", nodeName, serverPort, peerPort);

        try {
            // Phase 1: Initialize network channel
            var nodeId = UUID.randomUUID();
            var peerNodeId = UUID.randomUUID();
            var networkChannel = new GrpcBubbleNetworkChannel();
            networkChannel.initialize(nodeId, "localhost:" + serverPort);
            networkChannel.registerNode(peerNodeId, "localhost:" + peerPort);

            log.info("[{}] Network channel initialized", nodeName);

            // Phase 2: Create bubble with appropriate bounds
            var bubble = createBubble(nodeName, nodeId);
            log.info("[{}] Bubble created with bounds: {}", nodeName, describeBounds(nodeName));

            // Phase 3: Initialize shared state
            var behavior = new PreyBehavior();
            var velocities = new ConcurrentHashMap<String, Vector3f>();

            // Phase 4: Set up network event listeners
            setupNetworkListeners(nodeName, networkChannel, bubble, peerNodeId, velocities, behavior);

            // Phase 5: Wait for peer to be ready
            waitForPeer(nodeName, networkChannel, peerNodeId);

            // Emit READY marker for test
            System.out.println("[" + nodeName + "] READY");
            log.info("[{}] Node ready", nodeName);

            // Phase 6: Start simulation
            var controller = new RealTimeController(nodeName);

            // Spawn entities (Node1 only)
            if (nodeName.equals("Node1")) {
                spawnEntities(bubble, velocities, behavior);
                System.out.println("[" + nodeName + "] ENTITIES_SPAWNED");
                log.info("[{}] Spawned {} entities", nodeName, ENTITY_COUNT);
            }

            // Start simulation entity
            var entity = new SimulationEntity(
                nodeName,
                bubble,
                velocities,
                behavior,
                networkChannel,
                peerNodeId
            );

            Kairos.setController(controller);
            controller.start();
            entity.simulationTick();

            log.info("[{}] Simulation running at 20 TPS", nodeName);

            // Set up shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("[{}] Shutdown signal received", nodeName);
                controller.stop();
                try {
                    if (networkChannel instanceof AutoCloseable) {
                        ((AutoCloseable) networkChannel).close();
                    }
                } catch (Exception e) {
                    log.error("[{}] Error during shutdown", nodeName, e);
                }
                shutdownLatch.countDown();
            }));

            // Wait for shutdown
            shutdownLatch.await();
            log.info("[{}] Exiting", nodeName);

        } catch (Exception e) {
            log.error("[{}] ERROR: {}", nodeName, e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Create bubble with bounds appropriate for this node.
     */
    private static EnhancedBubble createBubble(String nodeName, UUID nodeId) {
        byte spatialLevel = 10;
        long targetFrameMs = 50;

        return new EnhancedBubble(nodeId, spatialLevel, targetFrameMs);
    }

    /**
     * Describe bounds for this node (for logging).
     */
    private static String describeBounds(String nodeName) {
        if (nodeName.equals("Node1")) {
            return "(0-50, 0-50, 0-50)";
        } else {
            return "(50-100, 0-50, 0-50)";
        }
    }

    /**
     * Set up network event listeners for entity migration.
     */
    private static void setupNetworkListeners(
        String nodeName,
        BubbleNetworkChannel networkChannel,
        EnhancedBubble bubble,
        UUID peerNodeId,
        ConcurrentHashMap<String, Vector3f> velocities,
        PreyBehavior behavior
    ) {
        // Listen for incoming entity departures (arrivals from peer)
        networkChannel.setEntityDepartureListener((sourceNodeId, event) -> {
            log.info("[{}] Received EntityDepartureEvent: entity={}", nodeName, event.getEntityId());
            System.out.println("[" + nodeName + "] ENTITY_ARRIVED");

            // Extract entity ID (convert UUID back to string format)
            var entityIdStr = "entity-" + Math.abs(event.getEntityId().hashCode() % 1000);

            // Generate new position in receiving node's bounds
            // Node2 bounds: (50-100, 0-50, 0-50), Node1 bounds: (0-50, 0-50, 0-50)
            float minX = nodeName.equals("Node2") ? 50f : 0f;
            float maxX = nodeName.equals("Node2") ? 100f : 50f;
            var position = randomPosition(minX, maxX);
            var velocity = randomVelocity(behavior.getMaxSpeed());

            // Add entity to bubble
            bubble.addEntity(entityIdStr, position, EntityType.PREY);
            velocities.put(entityIdStr, velocity);

            log.info("[{}] Entity {} arrived and added to bubble at position ({}, {}, {})",
                nodeName, entityIdStr, position.x, position.y, position.z);
        });
    }

    /**
     * Wait for peer node to be reachable.
     */
    private static void waitForPeer(
        String nodeName,
        BubbleNetworkChannel networkChannel,
        UUID peerNodeId
    ) throws InterruptedException {
        log.info("[{}] Waiting for peer to be reachable...", nodeName);

        for (int attempts = 0; attempts < 30; attempts++) {
            if (networkChannel.isNodeReachable(peerNodeId)) {
                log.info("[{}] Peer is reachable", nodeName);
                return;
            }
            Thread.sleep(500);
        }

        log.warn("[{}] Peer not reachable after 15 seconds, continuing anyway", nodeName);
    }

    /**
     * Spawn entities in Node1 bubble.
     */
    private static void spawnEntities(
        EnhancedBubble bubble,
        ConcurrentHashMap<String, Vector3f> velocities,
        PreyBehavior behavior
    ) {
        // Spawn entities in Node1 bounds (0-50)
        for (int i = 0; i < ENTITY_COUNT; i++) {
            var entityId = "entity-" + i;
            var position = randomPosition(0, 50);
            var velocity = randomVelocity(behavior.getMaxSpeed());

            bubble.addEntity(entityId, position, EntityType.PREY);
            velocities.put(entityId, velocity);
        }
    }

    /**
     * PrimeMover simulation entity.
     */
    @Entity
    public static class SimulationEntity {
        private static final float DELTA_TIME = TICK_INTERVAL_NS / 1_000_000_000.0f;
        private static final float BOUNDARY_X = 50.0f; // Migration boundary

        private final String nodeName;
        private final EnhancedBubble bubble;
        private final ConcurrentHashMap<String, Vector3f> velocities;
        private final PreyBehavior behavior;
        private final BubbleNetworkChannel networkChannel;
        private final UUID peerNodeId;

        private int currentTick = 0;

        public SimulationEntity(
            String nodeName,
            EnhancedBubble bubble,
            ConcurrentHashMap<String, Vector3f> velocities,
            PreyBehavior behavior,
            BubbleNetworkChannel networkChannel,
            UUID peerNodeId
        ) {
            this.nodeName = nodeName;
            this.bubble = bubble;
            this.velocities = velocities;
            this.behavior = behavior;
            this.networkChannel = networkChannel;
            this.peerNodeId = peerNodeId;
        }

        @NonEvent
        public int getCurrentTick() {
            return currentTick;
        }

        /**
         * Execute one simulation tick.
         */
        public void simulationTick() {
            // Update all entities
            behavior.swapVelocityBuffers();

            var entities = bubble.getAllEntityRecords();
            for (var entity : entities) {
                var entityId = entity.id();
                var position = entity.position();
                var velocity = velocities.getOrDefault(entityId, new Vector3f(0, 0, 0));

                // Compute new velocity
                var newVelocity = behavior.computeVelocity(
                    entityId, position, velocity, bubble, DELTA_TIME
                );

                // Update position
                var newPosition = new Point3f(
                    position.x + newVelocity.x * DELTA_TIME,
                    position.y + newVelocity.y * DELTA_TIME,
                    position.z + newVelocity.z * DELTA_TIME
                );

                // Clamp to world bounds (0-100)
                newPosition.x = Math.max(0, Math.min(100, newPosition.x));
                newPosition.y = Math.max(0, Math.min(100, newPosition.y));
                newPosition.z = Math.max(0, Math.min(100, newPosition.z));

                // Check for boundary crossing
                if (shouldMigrate(position, newPosition)) {
                    migrateEntity(entityId, newPosition);
                } else {
                    bubble.updateEntityPosition(entityId, newPosition);
                    velocities.put(entityId, newVelocity);
                }
            }

            // Log entity count every 10 ticks (500ms) for test visibility
            if (currentTick % 10 == 0) {
                var count = bubble.getAllEntityRecords().size();
                System.out.println("[" + nodeName + "] ENTITY_COUNT: " + count);
            }

            currentTick++;

            // Schedule next tick
            Kronos.sleep(TICK_INTERVAL_NS);
            this.simulationTick();
        }

        /**
         * Determine if entity should migrate to peer node.
         */
        private boolean shouldMigrate(Point3f oldPos, Point3f newPos) {
            // Node1 → Node2: entity crosses x=50 going right
            if (nodeName.equals("Node1") && oldPos.x < BOUNDARY_X && newPos.x >= BOUNDARY_X) {
                return true;
            }

            // Node2 → Node1: entity crosses x=50 going left
            if (nodeName.equals("Node2") && oldPos.x >= BOUNDARY_X && newPos.x < BOUNDARY_X) {
                return true;
            }

            return false;
        }

        /**
         * Migrate entity to peer node.
         */
        private void migrateEntity(String entityId, Point3f position) {
            log.info("[{}] Migrating entity {} to peer at position ({}, {}, {})",
                nodeName, entityId, position.x, position.y, position.z);

            // Create departure event
            var event = new com.hellblazer.luciferase.simulation.events.EntityDepartureEvent(
                UUID.fromString(padToUUID(entityId)),
                bubble.id(),
                peerNodeId,
                com.hellblazer.luciferase.simulation.causality.EntityMigrationState.MIGRATING_OUT,
                System.nanoTime()
            );

            // Send via network
            var success = networkChannel.sendEntityDeparture(peerNodeId, event);
            if (success) {
                // Remove from local bubble
                bubble.removeEntity(entityId);
                velocities.remove(entityId);
                log.debug("[{}] Entity {} migrated successfully", nodeName, entityId);
            } else {
                log.warn("[{}] Failed to migrate entity {}", nodeName, entityId);
            }
        }
    }

    /**
     * Generate random position within bounds.
     */
    private static Point3f randomPosition(float min, float max) {
        var range = max - min;
        return new Point3f(
            min + RANDOM.nextFloat() * range,
            min + RANDOM.nextFloat() * range,
            min + RANDOM.nextFloat() * range
        );
    }

    /**
     * Generate random velocity.
     */
    private static Vector3f randomVelocity(float maxSpeed) {
        var speed = RANDOM.nextFloat() * maxSpeed;
        var theta = RANDOM.nextFloat() * (float) (2 * Math.PI);
        var phi = RANDOM.nextFloat() * (float) Math.PI;

        return new Vector3f(
            speed * (float) (Math.sin(phi) * Math.cos(theta)),
            speed * (float) (Math.sin(phi) * Math.sin(theta)),
            speed * (float) Math.cos(phi)
        );
    }

    /**
     * Convert string ID to UUID format.
     */
    private static String padToUUID(String id) {
        var hash = String.format("%032x", id.hashCode() & 0xFFFFFFFFL);
        return hash.substring(0, 8) + "-" + hash.substring(8, 12) + "-" +
               hash.substring(12, 16) + "-" + hash.substring(16, 20) + "-" + hash.substring(20, 32);
    }
}
