/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.spike;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike test to validate ProcessBuilder multi-JVM testing pattern.
 *
 * Goal: Prove that ProcessBuilder can spawn 2 JVMs that communicate
 * via GrpcBubbleNetworkChannel for distributed testing.
 *
 * This is a MANDATORY spike - if this fails, we switch to Docker Compose.
 *
 * @author hal.hildebrand
 */
@Tag("integration")
public class ProcessBuilderSpikeTest {

    private final List<Process> processes = new ArrayList<>();
    private final List<Path> logFiles = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        // Destroy all spawned processes
        for (var process : processes) {
            if (process.isAlive()) {
                process.destroy();
                process.waitFor(2, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
        processes.clear();

        // Clean up log files
        for (var logFile : logFiles) {
            try {
                Files.deleteIfExists(logFile);
            } catch (IOException e) {
                // Ignore cleanup errors
            }
        }
        logFiles.clear();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testTwoProcessCommunication() throws Exception {
        // Setup: Dynamic ports for gRPC servers
        var node1Port = findAvailablePort();
        var node2Port = findAvailablePort();

        var node1Id = UUID.randomUUID();
        var node2Id = UUID.randomUUID();
        var testEntityId = UUID.randomUUID();

        System.out.println("Starting spike test:");
        System.out.println("  Node 1: " + node1Id + " on port " + node1Port);
        System.out.println("  Node 2: " + node2Id + " on port " + node2Port);
        System.out.println("  Test Entity: " + testEntityId);

        // Start Node 1 (will receive event from Node 2)
        var node1 = startNodeProcess("Node1", node1Id, node1Port, node2Id, node2Port);
        processes.add(node1);

        // Start Node 2 (will send event to Node 1)
        var node2 = startNodeProcess("Node2", node2Id, node2Port, node1Id, node1Port);
        processes.add(node2);

        // Wait for both nodes to be ready (check log output)
        assertThat(waitForNodesReady(node1, node2, Duration.ofSeconds(10)))
            .as("Both nodes should start and be ready within 10 seconds")
            .isTrue();

        // Node 2 sends EntityDepartureEvent to Node 1
        // This happens automatically in NodeProcess after initialization
        System.out.println("Waiting for entity departure event to be received...");

        // Poll Node 1 output for event reception confirmation
        var eventReceived = pollForEvent(node1, Duration.ofSeconds(10));
        assertThat(eventReceived)
            .as("Node 1 should receive EntityDepartureEvent from Node 2")
            .isTrue();

        System.out.println("✓ Spike test PASSED: Two JVMs communicated via gRPC");
        System.out.println("✓ Pattern validated for distributed testing");
    }

    /**
     * Start a node process with gRPC server.
     *
     * @param name Node name for logging
     * @param nodeId UUID for this node
     * @param listenPort Port this node listens on
     * @param remoteNodeId UUID of remote node
     * @param remotePort Port of remote node
     * @return Started Process
     */
    private Process startNodeProcess(String name, UUID nodeId, int listenPort,
                                     UUID remoteNodeId, int remotePort) throws IOException {
        var javaHome = System.getProperty("java.home");
        var classPath = System.getProperty("java.class.path");
        var logFile = Files.createTempFile("node-" + name + "-", ".log");
        logFiles.add(logFile);

        var pb = new ProcessBuilder(
            javaHome + "/bin/java",
            "-cp", classPath,
            "com.hellblazer.luciferase.simulation.spike.NodeProcess",
            name,
            nodeId.toString(),
            String.valueOf(listenPort),
            remoteNodeId.toString(),
            String.valueOf(remotePort)
        );

        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());

        var process = pb.start();
        System.out.println("Started " + name + " (PID: " + process.pid() + ", log: " + logFile + ")");
        return process;
    }

    /**
     * Wait for both nodes to report "READY" in their output.
     *
     * @param node1 First process
     * @param node2 Second process
     * @param timeout Maximum time to wait
     * @return true if both nodes ready, false on timeout
     */
    private boolean waitForNodesReady(Process node1, Process node2, Duration timeout) throws Exception {
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        var node1Ready = false;
        var node2Ready = false;

        while (System.currentTimeMillis() < deadline) {
            // Check if processes are still alive
            if (!node1.isAlive() || !node2.isAlive()) {
                System.err.println("ERROR: One or both nodes terminated unexpectedly");
                dumpProcessLogs(node1, node2);
                return false;
            }

            // Check log files for READY markers
            if (!node1Ready) {
                node1Ready = checkLogForMarker(0, "READY");
            }
            if (!node2Ready) {
                node2Ready = checkLogForMarker(1, "READY");
            }

            if (node1Ready && node2Ready) {
                System.out.println("Both nodes are READY");
                return true;
            }

            Thread.sleep(100);
        }

        System.err.println("Timeout waiting for nodes to be ready");
        dumpProcessLogs(node1, node2);
        return false;
    }

    /**
     * Poll Node 1 output for event reception confirmation.
     *
     * @param node1 Process to poll
     * @param timeout Maximum time to wait
     * @return true if event received, false on timeout
     */
    private boolean pollForEvent(Process node1, Duration timeout) throws Exception {
        var deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            if (!node1.isAlive()) {
                System.err.println("ERROR: Node 1 terminated unexpectedly");
                dumpProcessLogs(node1);
                return false;
            }

            // Check for event reception marker in log
            if (checkLogForMarker(0, "EVENT_RECEIVED")) {
                return true;
            }

            Thread.sleep(100);
        }

        System.err.println("Timeout waiting for event reception");
        dumpProcessLogs(node1);
        return false;
    }

    /**
     * Check if log file contains a specific marker.
     *
     * @param logIndex Index into logFiles list
     * @param marker Marker string to search for
     * @return true if marker found
     */
    private boolean checkLogForMarker(int logIndex, String marker) throws IOException {
        var logFile = logFiles.get(logIndex);
        if (!Files.exists(logFile)) {
            return false;
        }

        var lines = Files.readAllLines(logFile);
        return lines.stream().anyMatch(line -> line.contains(marker));
    }

    /**
     * Dump process logs for debugging.
     */
    private void dumpProcessLogs(Process... processes) throws IOException {
        for (int i = 0; i < processes.length; i++) {
            if (i < logFiles.size()) {
                var logFile = logFiles.get(i);
                System.err.println("\n=== Log for process " + (i + 1) + " ===");
                if (Files.exists(logFile)) {
                    Files.readAllLines(logFile).forEach(System.err::println);
                } else {
                    System.err.println("Log file does not exist: " + logFile);
                }
            }
        }
    }

    /**
     * Find an available port for binding.
     *
     * @return Available port number
     */
    private int findAvailablePort() {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find available port", e);
        }
    }
}
