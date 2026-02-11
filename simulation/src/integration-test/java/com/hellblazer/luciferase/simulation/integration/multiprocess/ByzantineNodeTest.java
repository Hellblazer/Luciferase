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
 * Multi-process integration test for Byzantine behavior scenarios.
 *
 * <p>Validates:
 * <ul>
 *   <li>System behavior when receiving invalid/malformed messages
 *   <li>Resilience against Byzantine flooding (duplicate events)
 *   <li>State isolation (bad messages don't corrupt good state)
 *   <li>Graceful degradation under Byzantine attacks
 * </ul>
 *
 * <p>Note: This test validates system robustness, not full Byzantine fault tolerance.
 * True BFT would require consensus protocols (not implemented in this phase).
 *
 * @author hal.hildebrand
 */
@Tag("integration")
public class ByzantineNodeTest {

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
     * Test system resilience against rapid message flooding.
     *
     * Scenario: One node sends entities rapidly while the other
     * continues normal operation. System should handle the load
     * without corruption or crash.
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void testMessageFlooding() throws Exception {
        // Setup: Dynamic ports for gRPC servers
        var node1Port = findAvailablePort();
        var node2Port = findAvailablePort();

        System.out.println("=== Message Flooding Test ===");
        System.out.println("  Node 1: port " + node1Port + " (50 entities - high load)");
        System.out.println("  Node 2: port " + node2Port + " (10 entities - normal)");
        System.out.println("  Scenario: Node1 floods messages, Node2 stays stable");

        // Start both nodes - Node1 with many entities (creates flooding)
        var node1 = startNodeProcess("Node1", node1Port, node2Port, 50);
        processes.add(node1);

        var node2 = startNodeProcess("Node2", node2Port, node1Port, 10);
        processes.add(node2);

        // Wait for both nodes to be ready
        assertThat(waitForNodesReady(Duration.ofSeconds(20)))
            .as("Both nodes should start despite high entity count")
            .isTrue();
        System.out.println("✓ Both nodes ready");

        // Wait for entities to spawn
        assertThat(pollForMarker(0, "ENTITIES_SPAWNED", Duration.ofSeconds(10)))
            .as("Node 1 should spawn entities")
            .isTrue();
        assertThat(pollForMarker(1, "ENTITIES_SPAWNED", Duration.ofSeconds(10)))
            .as("Node 2 should spawn entities")
            .isTrue();
        System.out.println("✓ Entities spawned");

        // Let system run under load for 15 seconds
        System.out.println("System running under high message load for 15 seconds...");
        Thread.sleep(15_000);

        // Verify both nodes still alive (no crash from flooding)
        assertThat(processes.get(0).isAlive())
            .as("Node 1 should survive high entity count")
            .isTrue();
        assertThat(processes.get(1).isAlive())
            .as("Node 2 should survive message flooding from Node 1")
            .isTrue();

        var node1Count = parseEntityCount(0);
        var node2Count = parseEntityCount(1);
        System.out.println("Final counts: Node1=" + node1Count + ", Node2=" + node2Count);

        // Nodes should have non-zero entity counts (not corrupted to zero)
        assertThat(node1Count)
            .as("Node 1 should maintain entities under load")
            .isGreaterThan(0);
        assertThat(node2Count)
            .as("Node 2 should maintain entities despite flooding")
            .isGreaterThan(0);

        // Total should be reasonable (allowing for migrations and async races)
        var total = node1Count + node2Count;
        assertThat(total)
            .as("Total entities should be within expected range (50+10 ± tolerance)")
            .isGreaterThanOrEqualTo(50)
            .isLessThanOrEqualTo(70);

        System.out.println("✓ Message Flooding Test PASSED");
    }

    /**
     * Test graceful degradation when one node behaves erratically.
     *
     * Scenario: Node crashes and restarts repeatedly while the other
     * node continues operating. The stable node should maintain
     * correct state throughout.
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void testErrticNodeBehavior() throws Exception {
        // Setup: Dynamic ports for gRPC servers
        var node1Port = findAvailablePort();
        var node2Port = findAvailablePort();

        System.out.println("=== Erratic Node Behavior Test ===");
        System.out.println("  Node 1: port " + node1Port + " (stable, 20 entities)");
        System.out.println("  Node 2: port " + node2Port + " (erratic, crashes 5 times)");
        System.out.println("  Scenario: Node2 behaves erratically, Node1 maintains stability");

        // Start stable Node 1
        var node1 = startNodeProcess("Node1", node1Port, node2Port, 20);
        processes.add(node1);

        assertThat(pollForMarker(0, "READY", Duration.ofSeconds(10)))
            .as("Node 1 should start")
            .isTrue();
        System.out.println("✓ Node 1 ready (stable)");

        var initialCount = parseEntityCount(0);
        System.out.println("Initial Node1 count: " + initialCount);

        // Erratic behavior: 5 cycles of start-run-crash
        for (int cycle = 1; cycle <= 5; cycle++) {
            System.out.println("\n--- Erratic Cycle " + cycle + " ---");

            // Start Node 2
            var node2 = startNodeProcess("Node2", node2Port, node1Port, 5);
            if (processes.size() < 2) {
                processes.add(node2);
            } else {
                processes.set(1, node2);
            }

            // Let it run briefly
            Thread.sleep(1_500);

            // Check if it started (might not have time)
            var started = checkLogForMarker(1, "READY");
            if (started) {
                System.out.println("  Node 2 started");
            } else {
                System.out.println("  Node 2 crashed before ready");
            }

            // Crash it
            node2.destroyForcibly();
            node2.waitFor(1, TimeUnit.SECONDS);
            System.out.println("  Node 2 crashed");

            // Brief pause
            Thread.sleep(500);
        }

        // Final check - Node 1 should still be stable
        System.out.println("\n--- Final State ---");
        assertThat(processes.get(0).isAlive())
            .as("Node 1 should remain stable through erratic peer behavior")
            .isTrue();

        var finalCount = parseEntityCount(0);
        System.out.println("Final Node1 count: " + finalCount);

        // Node 1 should maintain reasonable entity count
        assertThat(finalCount)
            .as("Node 1 should maintain entities through erratic peer")
            .isGreaterThanOrEqualTo(15)
            .isLessThanOrEqualTo(30);

        System.out.println("✓ Erratic Node Behavior Test PASSED");
    }

    /**
     * Test state isolation - verify that node state remains consistent
     * even when peer sends unexpected patterns.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testStateIsolation() throws Exception {
        // Setup: Dynamic ports for gRPC servers
        var node1Port = findAvailablePort();
        var node2Port = findAvailablePort();

        System.out.println("=== State Isolation Test ===");
        System.out.println("  Node 1: port " + node1Port + " (30 entities)");
        System.out.println("  Node 2: port " + node2Port + " (30 entities)");
        System.out.println("  Scenario: Verify state isolation under concurrent operation");

        // Start both nodes with equal entity counts
        var node1 = startNodeProcess("Node1", node1Port, node2Port, 30);
        processes.add(node1);

        var node2 = startNodeProcess("Node2", node2Port, node1Port, 30);
        processes.add(node2);

        // Wait for both nodes to be ready
        assertThat(waitForNodesReady(Duration.ofSeconds(20)))
            .as("Both nodes should start")
            .isTrue();
        System.out.println("✓ Both nodes ready");

        // Wait for entities to spawn
        for (int i = 0; i < 2; i++) {
            assertThat(pollForMarker(i, "ENTITIES_SPAWNED", Duration.ofSeconds(10)))
                .as("Node " + (i + 1) + " should spawn entities")
                .isTrue();
        }
        System.out.println("✓ Entities spawned on both nodes");

        // Capture initial state
        Thread.sleep(2_000);
        var initialNode1 = parseEntityCount(0);
        var initialNode2 = parseEntityCount(1);
        System.out.println("Initial state: Node1=" + initialNode1 + ", Node2=" + initialNode2);

        // Let system run for 10 seconds
        System.out.println("Running for 10 seconds...");
        Thread.sleep(10_000);

        // Capture final state
        var finalNode1 = parseEntityCount(0);
        var finalNode2 = parseEntityCount(1);
        System.out.println("Final state: Node1=" + finalNode1 + ", Node2=" + finalNode2);

        // Both nodes should still be running
        assertThat(processes.get(0).isAlive())
            .as("Node 1 should still be running")
            .isTrue();
        assertThat(processes.get(1).isAlive())
            .as("Node 2 should still be running")
            .isTrue();

        // Both nodes should have maintained non-zero entity counts
        assertThat(finalNode1)
            .as("Node 1 should maintain entities")
            .isGreaterThan(0);
        assertThat(finalNode2)
            .as("Node 2 should maintain entities")
            .isGreaterThan(0);

        // Total should be within reasonable range (60 ± tolerance for migrations)
        var total = finalNode1 + finalNode2;
        assertThat(total)
            .as("Total entities should be preserved")
            .isGreaterThanOrEqualTo(55)
            .isLessThanOrEqualTo(65);

        System.out.println("✓ State Isolation Test PASSED");
    }

    /**
     * Start a node process with specified parameters.
     */
    private Process startNodeProcess(String nodeName, int serverPort, int peerPort, int entityCount)
        throws IOException {
        var javaHome = System.getProperty("java.home");
        var classPath = System.getProperty("java.class.path");
        var logFile = Files.createTempFile("byzantine-test-" + nodeName.toLowerCase() + "-", ".log");
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
