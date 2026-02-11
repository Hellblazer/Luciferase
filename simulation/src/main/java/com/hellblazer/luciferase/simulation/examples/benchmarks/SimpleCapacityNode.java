/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.examples.benchmarks;

import com.hellblazer.luciferase.simulation.behavior.PreyBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.distributed.network.GrpcBubbleNetworkChannel;
import com.hellblazer.luciferase.simulation.entity.EntityType;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.primeMover.controllers.RealTimeController;
import com.hellblazer.primeMover.runtime.Kairos;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simplified benchmark node for capacity measurement.
 * Tests how many entities can be handled before performance degrades.
 */
public class SimpleCapacityNode {

    private static final long TICK_INTERVAL_NS = 10_000_000; // 10ms = 100 TPS
    private static final Random RANDOM = new Random(42);

    private static String nodeName;
    private static EnhancedBubble bubble;
    private static ConcurrentHashMap<String, Vector3f> velocities = new ConcurrentHashMap<>();
    private static PreyBehavior behavior;
    private static List<Long> tickLatencies = new ArrayList<>();

    @Entity
    public static class SimulationEntity {
        private static final float DELTA_TIME = 0.01f;
        private final String name;
        private int tickCount = 0;

        public SimulationEntity(String name) {
            this.name = name;
        }

        public void simulationTick() {
            long startNs = System.nanoTime();

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

                // Bounce off boundaries
                if (newPosition.x < 0 || newPosition.x > 100) velocity.x *= -1;
                if (newPosition.y < 0 || newPosition.y > 100) velocity.y *= -1;
                if (newPosition.z < 0 || newPosition.z > 100) velocity.z *= -1;

                // Clamp coordinates to positive values (Tetree requirement)
                newPosition.x = Math.max(0.0f, Math.min(100.0f, newPosition.x));
                newPosition.y = Math.max(0.0f, Math.min(100.0f, newPosition.y));
                newPosition.z = Math.max(0.0f, Math.min(100.0f, newPosition.z));

                bubble.updateEntityPosition(entityId, newPosition);
            }

            // Record latency
            long endNs = System.nanoTime();
            synchronized (tickLatencies) {
                tickLatencies.add(endNs - startNs);
                if (tickLatencies.size() > 1000) {
                    tickLatencies.remove(0);
                }
            }

            tickCount++;

            // Emit metrics every 100 ticks
            if (tickCount % 100 == 0) {
                emitMetrics();
            }

            // Schedule next tick
            Kronos.sleep(TICK_INTERVAL_NS);
            this.simulationTick();
        }

        private void emitMetrics() {
            // Calculate P99 latency
            List<Long> sorted;
            synchronized (tickLatencies) {
                sorted = new ArrayList<>(tickLatencies);
            }
            sorted.sort(Long::compareTo);

            double p99Ns = 0;
            if (!sorted.isEmpty()) {
                int p99Index = (int) Math.ceil(0.99 * sorted.size()) - 1;
                p99Ns = sorted.get(Math.max(0, p99Index));
            }
            double p99Ms = p99Ns / 1_000_000.0;

            // Get heap usage
            var memoryBean = ManagementFactory.getMemoryMXBean();
            var heapUsage = memoryBean.getHeapMemoryUsage();
            long heapMB = heapUsage.getUsed() / (1024 * 1024);

            System.out.println("[" + name + "] P99_LATENCY_MS: " + String.format("%.2f", p99Ms));
            System.out.println("[" + name + "] HEAP_MB: " + heapMB);
            System.out.println("[" + name + "] ENTITY_COUNT: " + bubble.getAllEntityRecords().size());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: SimpleCapacityNode <nodeName> <serverPort> <peerPort> <entityCount>");
            System.exit(1);
        }

        nodeName = args[0];
        var serverPort = Integer.parseInt(args[1]);
        var peerPort = Integer.parseInt(args[2]);
        var entityCount = Integer.parseInt(args[3]);

        // Initialize network (required but not used for capacity test)
        var nodeId = UUID.randomUUID();
        var peerNodeId = UUID.randomUUID();
        var networkChannel = new GrpcBubbleNetworkChannel();
        networkChannel.initialize(nodeId, "localhost:" + serverPort);
        networkChannel.registerNode(peerNodeId, "localhost:" + peerPort);

        // Create bubble
        byte spatialLevel = 10;
        long targetFrameMs = 10;
        bubble = new EnhancedBubble(nodeId, spatialLevel, targetFrameMs);
        behavior = new PreyBehavior();

        // Spawn entities
        for (int i = 0; i < entityCount; i++) {
            var entityId = "entity-" + i;
            var position = new Point3f(
                RANDOM.nextFloat() * 100f,
                RANDOM.nextFloat() * 100f,
                RANDOM.nextFloat() * 100f
            );
            var velocity = new Vector3f(
                (RANDOM.nextFloat() - 0.5f) * 1.0f,
                (RANDOM.nextFloat() - 0.5f) * 1.0f,
                (RANDOM.nextFloat() - 0.5f) * 1.0f
            );

            bubble.addEntity(entityId, position, EntityType.PREY);
            velocities.put(entityId, velocity);
        }

        // Start simulation
        var controller = new RealTimeController(nodeName);
        var entity = new SimulationEntity(nodeName);

        Kairos.setController(controller);
        controller.start();

        System.out.println("[" + nodeName + "] READY");
        System.out.println("[" + nodeName + "] Entity count: " + entityCount);

        // Start ticking
        entity.simulationTick();

        // Run until killed
        Thread.currentThread().join();
    }
}
