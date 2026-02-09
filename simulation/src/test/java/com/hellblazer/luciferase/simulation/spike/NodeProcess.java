/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.spike;

import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.distributed.network.GrpcBubbleNetworkChannel;
import com.hellblazer.luciferase.simulation.events.EntityDepartureEvent;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Node process for ProcessBuilder spike test.
 *
 * This class is executed in a separate JVM to validate multi-process
 * communication via GrpcBubbleNetworkChannel.
 *
 * Usage:
 *   java NodeProcess <name> <nodeId> <listenPort> <remoteNodeId> <remotePort>
 *
 * Example:
 *   java NodeProcess Node1 uuid1 9000 uuid2 9001
 *
 * @author hal.hildebrand
 */
public class NodeProcess {

    private static final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private static volatile boolean eventReceived = false;

    public static void main(String[] args) {
        if (args.length != 5) {
            System.err.println("Usage: NodeProcess <name> <nodeId> <listenPort> <remoteNodeId> <remotePort>");
            System.exit(1);
        }

        var name = args[0];
        var nodeId = UUID.fromString(args[1]);
        var listenPort = Integer.parseInt(args[2]);
        var remoteNodeId = UUID.fromString(args[3]);
        var remotePort = Integer.parseInt(args[4]);

        System.out.println("[" + name + "] Starting node " + nodeId + " on port " + listenPort);

        // Create and initialize gRPC channel
        var channel = new GrpcBubbleNetworkChannel();

        try {
            // Initialize this node's gRPC server
            channel.initialize(nodeId, "localhost:" + listenPort);
            System.out.println("[" + name + "] gRPC server initialized on localhost:" + listenPort);

            // Register remote node
            channel.registerNode(remoteNodeId, "localhost:" + remotePort);
            System.out.println("[" + name + "] Registered remote node " + remoteNodeId + " at localhost:" + remotePort);

            // Set up listener for incoming EntityDepartureEvents
            channel.setEntityDepartureListener((sourceNodeId, event) -> {
                System.out.println("[" + name + "] Received EntityDepartureEvent from " + sourceNodeId);
                System.out.println("[" + name + "]   Entity: " + event.getEntityId());
                System.out.println("[" + name + "]   Source: " + event.getSourceBubbleId());
                System.out.println("[" + name + "]   Target: " + event.getTargetBubbleId());
                System.out.println("[" + name + "]   State: " + event.getStateSnapshot());
                System.out.println("[" + name + "]   Clock: " + event.getLamportClock());
                System.out.println("[" + name + "] EVENT_RECEIVED");
                eventReceived = true;
            });

            // Signal that this node is ready
            System.out.println("[" + name + "] READY");

            // If this is Node2, send an EntityDepartureEvent to Node1 after a short delay
            if (name.equals("Node2")) {
                // Give Node1 time to initialize
                Thread.sleep(2000);

                var testEntityId = UUID.randomUUID();
                var event = new EntityDepartureEvent(
                    testEntityId,
                    nodeId,           // source bubble
                    remoteNodeId,     // target bubble
                    EntityMigrationState.MIGRATING_OUT,
                    System.nanoTime() // lamport clock
                );

                System.out.println("[" + name + "] Sending EntityDepartureEvent to " + remoteNodeId);
                System.out.println("[" + name + "]   Entity: " + testEntityId);

                var success = channel.sendEntityDeparture(remoteNodeId, event);
                if (success) {
                    System.out.println("[" + name + "] EntityDepartureEvent sent successfully");
                } else {
                    System.err.println("[" + name + "] Failed to send EntityDepartureEvent");
                }
            }

            // Set up shutdown hook for clean termination
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[" + name + "] Shutdown signal received");
                try {
                    channel.close();
                } catch (Exception e) {
                    System.err.println("[" + name + "] Error during shutdown: " + e.getMessage());
                }
                shutdownLatch.countDown();
            }));

            // Wait for shutdown or timeout (20 seconds max)
            System.out.println("[" + name + "] Running... (waiting for shutdown signal)");
            shutdownLatch.await(20, TimeUnit.SECONDS);

            System.out.println("[" + name + "] Exiting");
            System.exit(0);

        } catch (Exception e) {
            System.err.println("[" + name + "] ERROR: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
