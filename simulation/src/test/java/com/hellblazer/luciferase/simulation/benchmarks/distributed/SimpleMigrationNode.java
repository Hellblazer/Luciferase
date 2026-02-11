/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.benchmarks.distributed;

import com.hellblazer.luciferase.simulation.behavior.PreyBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.distributed.network.GrpcBubbleNetworkChannel;
import com.hellblazer.luciferase.simulation.entity.EntityType;
import com.hellblazer.luciferase.simulation.events.EntityDepartureEvent;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.annotations.NonEvent;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.primeMover.controllers.RealTimeController;
import com.hellblazer.primeMover.runtime.Kairos;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simplified benchmark node for migration throughput measurement.
 * Minimal dependencies, based on TwoNodeExample pattern.
 */
public class SimpleMigrationNode {

    private static final long TICK_INTERVAL_NS = 10_000_000; // 10ms = 100 TPS
    private static final Random RANDOM = new Random(42);
    private static final AtomicInteger migrationsOut = new AtomicInteger(0);
    private static final AtomicInteger migrationsIn = new AtomicInteger(0);

    private static String nodeName;
    private static UUID nodeId;
    private static UUID peerNodeId;
    private static EnhancedBubble bubble;
    private static GrpcBubbleNetworkChannel networkChannel;
    private static ConcurrentHashMap<String, Vector3f> velocities = new ConcurrentHashMap<>();
    private static PreyBehavior behavior;
    private static float minX;
    private static float maxX;

    @Entity
    public static class SimulationEntity {
        private static final float DELTA_TIME = 0.01f;
        private final String name;
        private int tickCount = 0;

        public SimulationEntity(String name) {
            this.name = name;
        }

        public void simulationTick() {
            // Update entities
            var entities = bubble.getAllEntityRecords();
            for (var entity : entities) {
                var entityId = entity.id();
                var position = entity.position();
                var velocity = velocities.getOrDefault(entityId, new Vector3f(0, 0, 0));

                // Update position
                var newPosition = new Point3f(
                    position.x + velocity.x * DELTA_TIME,
                    position.y + velocity.y * DELTA_TIME,
                    position.z + velocity.z * DELTA_TIME
                );

                // Clamp coordinates to positive values (Tetree requirement)
                newPosition.x = Math.max(0.0f, Math.min(100.0f, newPosition.x));
                newPosition.y = Math.max(0.0f, Math.min(100.0f, newPosition.y));
                newPosition.z = Math.max(0.0f, Math.min(100.0f, newPosition.z));

                // Check for boundary crossing
                if (newPosition.x < minX || newPosition.x > maxX) {
                    // Migrate entity
                    var departureEvent = new EntityDepartureEvent(
                        UUID.nameUUIDFromBytes(entityId.getBytes()),
                        bubble.id(),
                        peerNodeId,
                        EntityMigrationState.MIGRATING_OUT,
                        System.nanoTime()
                    );
                    networkChannel.sendEntityDeparture(peerNodeId, departureEvent);
                    bubble.removeEntity(entityId);
                    migrationsOut.incrementAndGet();
                } else {
                    bubble.updateEntityPosition(entityId, newPosition);
                }
            }

            tickCount++;

            // Emit migration count every 100 ticks
            if (tickCount % 100 == 0) {
                System.out.println("[" + name + "] MIGRATION_COUNT: " + migrationsOut.get());
                System.out.println("[" + name + "] ENTITY_COUNT: " + bubble.getAllEntityRecords().size());
            }

            // Schedule next tick
            Kronos.sleep(TICK_INTERVAL_NS);
            this.simulationTick();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: SimpleMigrationNode <nodeName> <serverPort> <peerPort> <entityCount>");
            System.exit(1);
        }

        nodeName = args[0];
        var serverPort = Integer.parseInt(args[1]);
        var peerPort = Integer.parseInt(args[2]);
        var entityCount = Integer.parseInt(args[3]);

        // Initialize network
        nodeId = UUID.randomUUID();
        peerNodeId = UUID.randomUUID();
        networkChannel = new GrpcBubbleNetworkChannel();
        networkChannel.initialize(nodeId, "localhost:" + serverPort);
        networkChannel.registerNode(peerNodeId, "localhost:" + peerPort);

        // Set spatial bounds
        if (serverPort < peerPort) {
            minX = 0f;
            maxX = 50f;
        } else {
            minX = 50f;
            maxX = 100f;
        }

        // Create bubble
        byte spatialLevel = 10;
        long targetFrameMs = 10;
        bubble = new EnhancedBubble(nodeId, spatialLevel, targetFrameMs);
        behavior = new PreyBehavior();

        // Set up network listener
        networkChannel.setEntityDepartureListener((sourceNodeId, event) -> {
            var entityId = "entity-" + Math.abs(event.getEntityId().hashCode() % 10000);
            var x = (minX + maxX) / 2f;
            var position = new Point3f(x, RANDOM.nextFloat() * 50f, RANDOM.nextFloat() * 50f);
            var velocity = new Vector3f(
                (RANDOM.nextFloat() - 0.5f) * 2.0f,
                (RANDOM.nextFloat() - 0.5f) * 0.5f,
                (RANDOM.nextFloat() - 0.5f) * 0.5f
            );

            bubble.addEntity(entityId, position, EntityType.PREY);
            velocities.put(entityId, velocity);
            migrationsIn.incrementAndGet();
        });

        // Spawn entities (if this is the spawning node)
        if (entityCount > 0) {
            for (int i = 0; i < entityCount; i++) {
                var entityId = "entity-" + i;
                var position = new Point3f(
                    minX + RANDOM.nextFloat() * (maxX - minX),
                    RANDOM.nextFloat() * 50f,
                    RANDOM.nextFloat() * 50f
                );
                var velocity = new Vector3f(
                    (RANDOM.nextFloat() - 0.5f) * 2.0f,  // Fast X velocity for migrations
                    (RANDOM.nextFloat() - 0.5f) * 0.5f,
                    (RANDOM.nextFloat() - 0.5f) * 0.5f
                );

                bubble.addEntity(entityId, position, EntityType.PREY);
                velocities.put(entityId, velocity);
            }
        }

        // Start simulation
        var controller = new RealTimeController(nodeName);
        var entity = new SimulationEntity(nodeName);

        Kairos.setController(controller);
        controller.start();

        System.out.println("[" + nodeName + "] READY");

        // Start ticking
        entity.simulationTick();

        // Run until killed
        Thread.currentThread().join();
    }
}
