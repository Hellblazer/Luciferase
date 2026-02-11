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
 * Multi-process integration test for crash detection and recovery.
 *
 * <p>Validates:
 * <ul>
 *   <li>Detection when peer process crashes unexpectedly
 *   <li>Graceful degradation when peer unavailable
 *   <li>Recovery when crashed peer restarts
 *   <li>Entity redistribution after crash
 * </ul>
 *
 * <p>Difference from NetworkPartitionTest:
 * - NetworkPartitionTest: Controlled shutdown (destroy())
 * - ProcessCrashTest: Unexpected termination (destroyForcibly())
 *
 * @author hal.hildebrand
 */
@Tag("integration")
public class ProcessCrashTest {

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
     * Test crash during normal operation.
     *
     * Phase 1: Both nodes running normally
     * Phase 2: Node 2 crashes (forcibly terminated)
     * Phase 3: Node 1 continues operating
     * Phase 4: Node 2 restarts and recovers
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void testCrashDuringOperation() throws Exception {
        // Setup: Dynamic ports for gRPC servers
        var node1Port = findAvailablePort();
        var node2Port = findAvailablePort();

        System.out.println("=== Process Crash Test ===");
        System.out.println("  Node 1: port " + node1Port + " (10 entities)");
        System.out.println("  Node 2: port " + node2Port + " (10 entities)");
        System.out.println("  Scenario: Node2 crashes forcibly, Node1 continues, Node2 recovers");

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

        // Let system run for 3 seconds
        System.out.println("System running normally for 3 seconds...");
        Thread.sleep(3_000);

        var beforeCrashNode1 = parseEntityCount(0);
        var beforeCrashNode2 = parseEntityCount(1);
        System.out.println("Before crash: Node1=" + beforeCrashNode1 + ", Node2=" + beforeCrashNode2);

        // Phase 2: Crash Node 2 (forcibly terminate)
        System.out.println("\n=== Simulating Unexpected Crash ===");
        System.out.println("Forcibly terminating Node 2...");
        var node2Process = processes.get(1);
        node2Process.destroyForcibly();
        node2Process.waitFor(2, TimeUnit.SECONDS);
        System.out.println("✓ Node 2 crashed (exit code: " + node2Process.exitValue() + ")");

        // Phase 3: Node 1 continues operating
        System.out.println("\nNode 1 operating alone for 5 seconds...");
        Thread.sleep(5_000);

        // Verify Node 1 still alive
        assertThat(processes.get(0).isAlive())
            .as("Node 1 should continue running after peer crash")
            .isTrue();

        var afterCrashCount = parseEntityCount(0);
        System.out.println("After crash: Node1=" + afterCrashCount + " entities");
        assertThat(afterCrashCount)
            .as("Node 1 should maintain entities after peer crash")
            .isGreaterThan(0);

        // Phase 4: Restart crashed node
        System.out.println("\n=== Recovery ===");
        System.out.println("Restarting Node 2...");
        node2 = startNodeProcess("Node2", node2Port, node1Port, 0); // No initial entities
        processes.set(1, node2);

        // Wait for Node 2 to recover
        assertThat(pollForMarker(1, "READY", Duration.ofSeconds(15)))
            .as("Node 2 should restart successfully")
            .isTrue();
        System.out.println("✓ Node 2 recovered");

        // Let system stabilize
        System.out.println("Letting system stabilize for 10 seconds...");
        Thread.sleep(10_000);

        // Verify both nodes running
        assertThat(processes.get(0).isAlive())
            .as("Node 1 should still be running")
            .isTrue();
        assertThat(processes.get(1).isAlive())
            .as("Node 2 should be running after recovery")
            .isTrue();

        var recoveryNode1 = parseEntityCount(0);
        var recoveryNode2 = parseEntityCount(1);
        System.out.println("After recovery: Node1=" + recoveryNode1 + ", Node2=" + recoveryNode2);

        // System should have recovered
        assertThat(recoveryNode1 + recoveryNode2)
            .as("Total entities should be preserved after recovery")
            .isGreaterThanOrEqualTo(18)
            .isLessThanOrEqualTo(22);

        System.out.println("✓ Process Crash Test PASSED");
    }

    /**
     * Test rapid crash and recovery cycles.
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void testRapidCrashRecovery() throws Exception {
        // Setup: Dynamic ports for gRPC servers
        var node1Port = findAvailablePort();
        var node2Port = findAvailablePort();

        System.out.println("=== Rapid Crash/Recovery Test ===");
        System.out.println("  Node 1: port " + node1Port + " (stable)");
        System.out.println("  Node 2: port " + node2Port + " (crashes 3 times)");
        System.out.println("  Scenario: Node2 crashes and recovers repeatedly");

        // Start Node 1 (stable)
        var node1 = startNodeProcess("Node1", node1Port, node2Port, 15);
        processes.add(node1);

        // Wait for Node 1 to be ready
        assertThat(pollForMarker(0, "READY", Duration.ofSeconds(10)))
            .as("Node 1 should start")
            .isTrue();
        System.out.println("✓ Node 1 ready");

        // Crash/recovery cycle 3 times
        for (int cycle = 1; cycle <= 3; cycle++) {
            System.out.println("\n=== Crash/Recovery Cycle " + cycle + " ===");

            // Start Node 2
            var node2 = startNodeProcess("Node2", node2Port, node1Port, 0);
            if (processes.size() < 2) {
                processes.add(node2);
            } else {
                processes.set(1, node2);
            }

            // Wait for Node 2 to start
            assertThat(pollForMarker(1, "READY", Duration.ofSeconds(10)))
                .as("Node 2 should start in cycle " + cycle)
                .isTrue();
            System.out.println("  ✓ Node 2 started");

            // Let it run briefly
            Thread.sleep(2_000);

            // Crash Node 2
            node2.destroyForcibly();
            node2.waitFor(2, TimeUnit.SECONDS);
            System.out.println("  ✓ Node 2 crashed");

            // Let Node 1 run alone
            Thread.sleep(1_000);

            // Verify Node 1 still alive
            assertThat(processes.get(0).isAlive())
                .as("Node 1 should survive cycle " + cycle)
                .isTrue();
        }

        // Final check - Node 1 should still be running
        assertThat(processes.get(0).isAlive())
            .as("Node 1 should survive all crash cycles")
            .isTrue();

        var finalCount = parseEntityCount(0);
        System.out.println("\nFinal: Node1=" + finalCount + " entities");
        assertThat(finalCount)
            .as("Node 1 should maintain entities through crash cycles")
            .isGreaterThan(0);

        System.out.println("✓ Rapid Crash/Recovery Test PASSED");
    }

    /**
     * Start a node process with specified parameters.
     */
    private Process startNodeProcess(String nodeName, int serverPort, int peerPort, int entityCount)
        throws IOException {
        var javaHome = System.getProperty("java.home");
        var classPath = System.getProperty("java.class.path");
        var logFile = Files.createTempFile("crash-test-" + nodeName.toLowerCase() + "-", ".log");
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

        return false;
    }

    /**
     * Poll for a specific marker in a log file.
     */
    private boolean pollForMarker(int logIndex, String marker, Duration timeout) throws Exception {
        var deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            // Check if process is still alive (for crash tests, it might not be)
            if (logIndex < processes.size()) {
                var process = processes.get(logIndex);
                if (!process.isAlive() && checkLogForMarker(logIndex, marker)) {
                    // Process died but marker exists
                    return true;
                }
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
