/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.integration.multiprocess;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-process integration test for network partition scenarios.
 *
 * <p>Validates:
 * <ul>
 *   <li>System behavior during network partition (simulated by killing node)
 *   <li>Entity conservation during partition
 *   <li>Recovery when partition heals (node restarts)
 *   <li>Ghost state reconciliation after partition
 * </ul>
 *
 * <p>This test spawns separate JVM processes and validates real network communication.
 *
 * @author hal.hildebrand
 */
@Tag("integration")
public class NetworkPartitionTest {

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

    /**
     * Test partition and recovery scenario.
     *
     * Phase 1: Normal operation with 2 nodes
     * Phase 2: Kill Node 2 (simulate partition)
     * Phase 3: Restart Node 2 (partition heals)
     * Phase 4: Verify recovery
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void testPartitionAndRecovery() throws Exception {
        // Setup: Dynamic ports for gRPC servers
        var node1Port = findAvailablePort();
        var node2Port = findAvailablePort();

        System.out.println("=== Network Partition and Recovery Test ===");
        System.out.println("  Node 1: port " + node1Port + " (10 entities)");
        System.out.println("  Node 2: port " + node2Port + " (10 entities)");
        System.out.println("  Scenario: Kill Node2, verify Node1 continues, restart Node2, verify recovery");

        // Phase 1: Start both nodes
        var node1 = startNodeProcess("Node1", node1Port, node2Port, 10);
        processes.add(node1);

        var node2 = startNodeProcess("Node2", node2Port, node1Port, 10);
        processes.add(node2);

        // Wait for both nodes to be ready
        assertThat(waitForNodesReady(Duration.ofSeconds(20)))
            .as("Both nodes should start and be ready")
            .isTrue();
        System.out.println("✓ Both nodes ready");

        // Wait for entities to spawn
        assertThat(pollForMarker(0, "ENTITIES_SPAWNED", Duration.ofSeconds(10)))
            .as("Node 1 should spawn entities")
            .isTrue();
        assertThat(pollForMarker(1, "ENTITIES_SPAWNED", Duration.ofSeconds(10)))
            .as("Node 2 should spawn entities")
            .isTrue();
        System.out.println("✓ Entities spawned on both nodes");

        // Let system stabilize
        System.out.println("Letting system stabilize for 5 seconds...");
        Thread.sleep(5_000);

        // Verify initial state (both nodes running)
        var initialNode1Count = parseEntityCount(0);
        var initialNode2Count = parseEntityCount(1);
        System.out.println("Initial state: Node1=" + initialNode1Count + ", Node2=" + initialNode2Count);
        assertThat(initialNode1Count + initialNode2Count)
            .as("Initial total should be around 20 entities")
            .isGreaterThanOrEqualTo(18)  // Allow for migration
            .isLessThanOrEqualTo(22);    // Allow for async race

        // Phase 2: Simulate partition by killing Node 2
        System.out.println("\n=== Simulating Network Partition ===");
        System.out.println("Killing Node 2 (simulates partition)...");
        var node2Process = processes.get(1);
        node2Process.destroy();
        node2Process.waitFor(2, TimeUnit.SECONDS);
        if (node2Process.isAlive()) {
            node2Process.destroyForcibly();
            node2Process.waitFor(2, TimeUnit.SECONDS);
        }
        System.out.println("✓ Node 2 killed");

        // Let Node 1 run alone during partition
        System.out.println("Node 1 running alone for 5 seconds...");
        Thread.sleep(5_000);

        // Verify Node 1 still alive and functional
        assertThat(processes.get(0).isAlive())
            .as("Node 1 should still be running during partition")
            .isTrue();

        var duringPartitionCount = parseEntityCount(0);
        System.out.println("During partition: Node1=" + duringPartitionCount + " entities");
        assertThat(duringPartitionCount)
            .as("Node 1 should maintain its entities during partition")
            .isGreaterThan(0);

        // Phase 3: Heal partition by restarting Node 2
        System.out.println("\n=== Healing Partition ===");
        System.out.println("Restarting Node 2...");
        node2 = startNodeProcess("Node2", node2Port, node1Port, 0); // No initial entities
        processes.set(1, node2);

        // Wait for Node 2 to rejoin
        assertThat(pollForMarker(1, "READY", Duration.ofSeconds(15)))
            .as("Node 2 should restart and be ready")
            .isTrue();
        System.out.println("✓ Node 2 rejoined");

        // Let system recover and stabilize
        System.out.println("Letting system recover for 10 seconds...");
        Thread.sleep(10_000);

        // Phase 4: Verify recovery
        var finalNode1Count = parseEntityCount(0);
        var finalNode2Count = parseEntityCount(1);
        System.out.println("After recovery: Node1=" + finalNode1Count + ", Node2=" + finalNode2Count);

        // Both nodes should be running
        assertThat(processes.get(0).isAlive())
            .as("Node 1 should still be running")
            .isTrue();
        assertThat(processes.get(1).isAlive())
            .as("Node 2 should be running after restart")
            .isTrue();

        // Total entities should be preserved (allowing for migration and async race)
        var finalTotal = finalNode1Count + finalNode2Count;
        assertThat(finalTotal)
            .as("Total entities should be preserved after recovery")
            .isGreaterThanOrEqualTo(18)
            .isLessThanOrEqualTo(22);

        System.out.println("✓ Network Partition and Recovery Test PASSED");
    }

    /**
     * Test behavior when one node is permanently unavailable.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testPermanentPartition() throws Exception {
        // Setup: Dynamic ports for gRPC servers
        var node1Port = findAvailablePort();
        var node2Port = findAvailablePort();

        System.out.println("=== Permanent Partition Test ===");
        System.out.println("  Node 1: port " + node1Port + " (15 entities)");
        System.out.println("  Node 2: port " + node2Port + " (never started)");
        System.out.println("  Scenario: Node 1 runs alone, Node 2 never starts");

        // Start only Node 1
        var node1 = startNodeProcess("Node1", node1Port, node2Port, 15);
        processes.add(node1);

        // Wait for Node 1 to be ready
        assertThat(pollForMarker(0, "READY", Duration.ofSeconds(10)))
            .as("Node 1 should start successfully")
            .isTrue();
        System.out.println("✓ Node 1 ready");

        // Wait for entities to spawn
        assertThat(pollForMarker(0, "ENTITIES_SPAWNED", Duration.ofSeconds(10)))
            .as("Node 1 should spawn entities")
            .isTrue();
        System.out.println("✓ Entities spawned");

        // Let it run for 10 seconds
        System.out.println("Node 1 running alone for 10 seconds...");
        Thread.sleep(10_000);

        // Verify Node 1 is still running
        assertThat(processes.get(0).isAlive())
            .as("Node 1 should continue running despite peer being unavailable")
            .isTrue();

        var finalCount = parseEntityCount(0);
        System.out.println("Final: Node1=" + finalCount + " entities");

        // Node 1 should maintain its entities
        assertThat(finalCount)
            .as("Node 1 should maintain its entities")
            .isGreaterThanOrEqualTo(14)
            .isLessThanOrEqualTo(16);

        System.out.println("✓ Permanent Partition Test PASSED");
    }

    /**
     * Start a node process with specified parameters.
     */
    private Process startNodeProcess(String nodeName, int serverPort, int peerPort, int entityCount)
        throws IOException {
        var javaHome = System.getProperty("java.home");
        var classPath = System.getProperty("java.class.path");
        var logFile = Files.createTempFile("partition-test-" + nodeName.toLowerCase() + "-", ".log");
        logFiles.add(logFile);

        var pb = new ProcessBuilder(
            javaHome + "/bin/java",
            "-cp", classPath,
            "com.hellblazer.luciferase.simulation.integration.multiprocess.MigrationTestNode",
            nodeName,
            String.valueOf(serverPort),
            String.valueOf(peerPort),
            String.valueOf(entityCount)
        );

        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());

        var process = pb.start();
        System.out.println("Started " + nodeName + " (PID: " + process.pid() + ", log: " + logFile + ")");
        return process;
    }

    /**
     * Wait for all nodes to report "READY" in their output.
     */
    private boolean waitForNodesReady(Duration timeout) throws Exception {
        var deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            // Check if all processes are still alive
            for (int i = 0; i < processes.size(); i++) {
                var process = processes.get(i);
                if (!process.isAlive()) {
                    System.err.println("ERROR: Process " + i + " terminated unexpectedly");
                    dumpAllProcessLogs();
                    return false;
                }
            }

            // Check if all nodes are ready
            var ready = true;
            for (int i = 0; i < processes.size(); i++) {
                if (!checkLogForMarker(i, "READY")) {
                    ready = false;
                    break;
                }
            }

            if (ready) {
                return true;
            }

            Thread.sleep(100);
        }

        System.err.println("Timeout waiting for nodes to be ready");
        dumpAllProcessLogs();
        return false;
    }

    /**
     * Poll for a specific marker in a log file.
     */
    private boolean pollForMarker(int logIndex, String marker, Duration timeout) throws Exception {
        var deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            // Check if process is still alive
            if (logIndex < processes.size() && !processes.get(logIndex).isAlive()) {
                // Process died - check one last time
                return checkLogForMarker(logIndex, marker);
            }

            if (checkLogForMarker(logIndex, marker)) {
                return true;
            }

            Thread.sleep(200);
        }

        return false;
    }

    /**
     * Check if log file contains a specific marker.
     */
    private boolean checkLogForMarker(int logIndex, String marker) throws IOException {
        if (logIndex >= logFiles.size()) {
            return false;
        }

        var logFile = logFiles.get(logIndex);
        if (!Files.exists(logFile)) {
            return false;
        }

        var lines = Files.readAllLines(logFile);
        return lines.stream().anyMatch(line -> line.contains(marker));
    }

    /**
     * Parse entity count from node log file.
     */
    private int parseEntityCount(int logIndex) throws IOException {
        if (logIndex >= logFiles.size()) {
            return -1;
        }

        var logFile = logFiles.get(logIndex);
        if (!Files.exists(logFile)) {
            return -1;
        }

        var lines = Files.readAllLines(logFile);

        // Search backwards for most recent ENTITY_COUNT marker
        for (int i = lines.size() - 1; i >= 0; i--) {
            var line = lines.get(i);
            if (line.contains("ENTITY_COUNT:")) {
                try {
                    var parts = line.split("ENTITY_COUNT:");
                    if (parts.length > 1) {
                        var countStr = parts[1].trim().split("\\s+")[0];
                        return Integer.parseInt(countStr);
                    }
                } catch (NumberFormatException e) {
                    // Continue searching
                }
            }
        }

        return -1;
    }

    /**
     * Dump all process logs for debugging.
     */
    private void dumpAllProcessLogs() throws IOException {
        for (int i = 0; i < logFiles.size(); i++) {
            var logFile = logFiles.get(i);
            System.err.println("\n=== Log for node " + (i + 1) + " ===");
            if (Files.exists(logFile)) {
                Files.readAllLines(logFile).forEach(System.err::println);
            } else {
                System.err.println("Log file does not exist: " + logFile);
            }
        }
    }

    /**
     * Find an available port for binding.
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
