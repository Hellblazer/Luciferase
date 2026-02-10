/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.integration.multiprocess;

import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.distributed.network.GrpcBubbleNetworkChannel;
import com.hellblazer.luciferase.simulation.events.EntityDepartureEvent;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Node process for multi-process integration tests.
 *
 * <p>Each node runs a simple entity manager with real gRPC networking.
 * Entities migrate between nodes as they cross spatial boundaries.
 *
 * <p>Usage:
 * <pre>
 * java MigrationTestNode <nodeName> <serverPort> <peerPort> <entityCount>
 * </pre>
 *
 * @author hal.hildebrand
 */
public class MigrationTestNode {

    private static final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private static final ConcurrentHashMap<UUID, TestEntity> entities = new ConcurrentHashMap<>();
    private static final AtomicLong lamportClock = new AtomicLong(0);

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Usage: MigrationTestNode <nodeName> <serverPort> <peerPort> <entityCount>");
            System.exit(1);
        }

        var nodeName = args[0];
        var serverPort = Integer.parseInt(args[1]);
        var peerPort = Integer.parseInt(args[2]);
        var entityCount = Integer.parseInt(args[3]);

        var nodeId = UUID.randomUUID();

        System.out.println("[" + nodeName + "] Starting node " + nodeId);
        System.out.println("[" + nodeName + "]   Server port: " + serverPort);
        System.out.println("[" + nodeName + "]   Peer port: " + peerPort);
        System.out.println("[" + nodeName + "]   Entity count: " + entityCount);

        try {
            // Create network channel
            var networkChannel = new GrpcBubbleNetworkChannel();
            networkChannel.initialize(nodeId, "localhost:" + serverPort);
            System.out.println("[" + nodeName + "] gRPC server initialized");

            // Register peer node
            var peerNodeId = UUID.randomUUID(); // In real setup, peer ID would be known
            networkChannel.registerNode(peerNodeId, "localhost:" + peerPort);
            System.out.println("[" + nodeName + "] Registered peer node at localhost:" + peerPort);

            // Set up entity departure listener
            networkChannel.setEntityDepartureListener((sourceNodeId, event) -> {
                System.out.println("[" + nodeName + "] ENTITY_ARRIVED from " + sourceNodeId);
                System.out.println("[" + nodeName + "]   Entity: " + event.getEntityId());
                System.out.println("[" + nodeName + "]   Lamport: " + event.getLamportClock());

                // Update lamport clock
                lamportClock.updateAndGet(current -> Math.max(current, event.getLamportClock()) + 1);

                // Add entity to local tracking
                var entityId = event.getEntityId();
                var entity = new TestEntity(entityId, new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
                entities.put(entityId, entity);

                System.out.println("[" + nodeName + "] ENTITY_COUNT: " + entities.size());
            });

            // Spawn initial entities if requested
            if (entityCount > 0) {
                spawnEntities(nodeName, entityCount);
                System.out.println("[" + nodeName + "] ENTITIES_SPAWNED: " + entityCount);
            }

            // Signal ready
            System.out.println("[" + nodeName + "] READY");

            // Start simple simulation loop
            var executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleAtFixedRate(() -> {
                try {
                    // Increment lamport clock
                    lamportClock.incrementAndGet();

                    // Update all entities
                    entities.values().forEach(entity -> entity.update(0.01f));

                    // Periodically report entity count and lamport clock
                    if (lamportClock.get() % 100 == 0) { // Every 100 ticks = 1 second
                        System.out.println("[" + nodeName + "] ENTITY_COUNT: " + entities.size());
                        System.out.println("[" + nodeName + "] LAMPORT_CLOCK: " + lamportClock.get());
                    }
                } catch (Exception e) {
                    System.err.println("[" + nodeName + "] Update error: " + e.getMessage());
                }
            }, 10, 10, TimeUnit.MILLISECONDS); // 100 Hz

            System.out.println("[" + nodeName + "] Simulation loop started");

            // Set up shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[" + nodeName + "] Shutdown signal received");
                executor.shutdown();
                try {
                    networkChannel.close();
                } catch (Exception e) {
                    System.err.println("[" + nodeName + "] Error during shutdown: " + e.getMessage());
                }
                shutdownLatch.countDown();
            }));

            // Wait for shutdown (max 60 seconds for test)
            System.out.println("[" + nodeName + "] Running... (waiting for shutdown signal)");
            shutdownLatch.await(60, TimeUnit.SECONDS);

            System.out.println("[" + nodeName + "] Final ENTITY_COUNT: " + entities.size());
            System.out.println("[" + nodeName + "] Exiting");
            System.exit(0);

        } catch (Exception e) {
            System.err.println("[" + nodeName + "] ERROR: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Spawn initial entities.
     */
    private static void spawnEntities(String nodeName, int count) {
        var random = new Random(42); // Fixed seed for reproducibility

        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();

            // Random position within bounds
            var x = random.nextFloat() * 100f;
            var y = random.nextFloat() * 100f;
            var z = random.nextFloat() * 100f;
            var position = new Point3f(x, y, z);

            // Random velocity
            var vx = (random.nextFloat() - 0.5f) * 2f; // -1 to 1
            var vy = (random.nextFloat() - 0.5f) * 2f;
            var vz = (random.nextFloat() - 0.5f) * 2f;
            var velocity = new Vector3f(vx, vy, vz);

            var entity = new TestEntity(entityId, position, velocity);
            entities.put(entityId, entity);
        }

        System.out.println("[" + nodeName + "] Spawned " + count + " entities");
    }

    /**
     * Simple test entity with position and velocity.
     */
    private static class TestEntity {
        final UUID id;
        final Point3f position;
        final Vector3f velocity;

        TestEntity(UUID id, Point3f position, Vector3f velocity) {
            this.id = id;
            this.position = position;
            this.velocity = velocity;
        }

        /**
         * Update entity position based on velocity.
         */
        void update(float dt) {
            position.x += velocity.x * dt;
            position.y += velocity.y * dt;
            position.z += velocity.z * dt;

            // Simple boundary wrapping
            if (position.x < 0f) position.x = 100f;
            if (position.x > 100f) position.x = 0f;
            if (position.y < 0f) position.y = 100f;
            if (position.y > 100f) position.y = 0f;
            if (position.z < 0f) position.z = 100f;
            if (position.z > 100f) position.z = 0f;
        }
    }
}
