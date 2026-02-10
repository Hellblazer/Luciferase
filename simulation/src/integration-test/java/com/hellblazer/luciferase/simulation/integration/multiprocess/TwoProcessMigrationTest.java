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
 * Multi-process integration test for entity migration.
 *
 * <p>Validates:
 * <ul>
 *   <li>Entity migration across process boundaries via gRPC
 *   <li>No entity duplication (conservation law)
 *   <li>No entity loss during migration
 *   <li>Causal consistency across processes
 * </ul>
 *
 * <p>This test spawns 2 separate JVM processes and validates real network communication.
 *
 * @author hal.hildebrand
 */
@Tag("integration")
public class TwoProcessMigrationTest {

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
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testEntityMigrationConservation() throws Exception {
        // Setup: Dynamic ports for gRPC servers
        var node1Port = findAvailablePort();
        var node2Port = findAvailablePort();

        System.out.println("=== Two-Process Migration Test ===");
        System.out.println("  Node 1: port " + node1Port + " (bounds: x=0-50)");
        System.out.println("  Node 2: port " + node2Port + " (bounds: x=50-100)");
        System.out.println("  Expected: Entities migrate from Node1 to Node2 as they cross x=50");

        // Start Node 1 (spawns 20 entities, owns x=0-50 region)
        var node1 = startNodeProcess("Node1", node1Port, node2Port, 20);
        processes.add(node1);

        // Start Node 2 (owns x=50-100 region, receives migrated entities)
        var node2 = startNodeProcess("Node2", node2Port, node1Port, 0);
        processes.add(node2);

        // Phase 1: Wait for both nodes to be ready
        assertThat(waitForNodesReady(Duration.ofSeconds(15)))
            .as("Both nodes should start and be ready within 15 seconds")
            .isTrue();
        System.out.println("✓ Both nodes ready");

        // Phase 2: Wait for Node1 to spawn entities
        assertThat(pollForMarker(0, "ENTITIES_SPAWNED", Duration.ofSeconds(10)))
            .as("Node 1 should spawn 20 entities within 10 seconds")
            .isTrue();
        System.out.println("✓ Entities spawned in Node 1");

        // Phase 3: Wait for first migration to Node2
        assertThat(pollForMarker(1, "ENTITY_ARRIVED", Duration.ofSeconds(30)))
            .as("At least one entity should migrate from Node 1 to Node 2 within 30 seconds")
            .isTrue();
        System.out.println("✓ Entity migration detected");

        // Phase 4: Let simulation run to accumulate migrations
        System.out.println("Letting simulation run for 15 seconds...");
        Thread.sleep(15_000);

        // Phase 5: Verify entity conservation (no duplication or loss)
        assertThat(verifyEntityConservation(20))
            .as("Total entity count should remain 20 (no duplication or loss)")
            .isTrue();
        System.out.println("✓ Entity conservation validated");

        // Phase 6: Verify causal consistency (lamport clocks monotonic)
        assertThat(verifyCausalConsistency())
            .as("Lamport clocks should be monotonically increasing")
            .isTrue();
        System.out.println("✓ Causal consistency validated");

        System.out.println("✓ Two-Process Migration Test PASSED");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testBidirectionalMigration() throws Exception {
        // Setup: Dynamic ports for gRPC servers
        var node1Port = findAvailablePort();
        var node2Port = findAvailablePort();

        System.out.println("=== Bidirectional Migration Test ===");
        System.out.println("  Node 1: port " + node1Port + " (spawns 10 entities)");
        System.out.println("  Node 2: port " + node2Port + " (spawns 10 entities)");
        System.out.println("  Expected: Entities migrate in both directions");

        // Start both nodes with 10 entities each
        var node1 = startNodeProcess("Node1", node1Port, node2Port, 10);
        processes.add(node1);

        var node2 = startNodeProcess("Node2", node2Port, node1Port, 10);
        processes.add(node2);

        // Wait for both nodes to be ready
        assertThat(waitForNodesReady(Duration.ofSeconds(15)))
            .as("Both nodes should start and be ready")
            .isTrue();
        System.out.println("✓ Both nodes ready");

        // Wait for entities to spawn on both nodes
        assertThat(pollForMarker(0, "ENTITIES_SPAWNED", Duration.ofSeconds(10)))
            .as("Node 1 should spawn entities")
            .isTrue();
        assertThat(pollForMarker(1, "ENTITIES_SPAWNED", Duration.ofSeconds(10)))
            .as("Node 2 should spawn entities")
            .isTrue();
        System.out.println("✓ Entities spawned on both nodes");

        // Let simulation run for bidirectional migration
        System.out.println("Letting simulation run for 20 seconds...");
        Thread.sleep(20_000);

        // Verify total conservation (20 entities total)
        assertThat(verifyEntityConservation(20))
            .as("Total entity count should remain 20 across both nodes")
            .isTrue();
        System.out.println("✓ Bidirectional migration conservation validated");

        System.out.println("✓ Bidirectional Migration Test PASSED");
    }

    /**
     * Start a node process with specified parameters.
     *
     * @param nodeName Node identifier
     * @param serverPort Port this node listens on
     * @param peerPort Port of peer node
     * @param entityCount Number of entities to spawn (0 for no spawning)
     * @return Started Process
     */
    private Process startNodeProcess(String nodeName, int serverPort, int peerPort, int entityCount)
        throws IOException {
        var javaHome = System.getProperty("java.home");
        var classPath = System.getProperty("java.class.path");
        var logFile = Files.createTempFile("migration-test-" + nodeName.toLowerCase() + "-", ".log");
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
        var allReady = false;

        while (System.currentTimeMillis() < deadline) {
            // Check if all processes are still alive
            for (var process : processes) {
                if (!process.isAlive()) {
                    System.err.println("ERROR: Process terminated unexpectedly");
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
                System.err.println("ERROR: Process " + logIndex + " terminated unexpectedly");
                dumpProcessLog(logIndex);
                return false;
            }

            if (checkLogForMarker(logIndex, marker)) {
                return true;
            }

            Thread.sleep(200);
        }

        System.err.println("Timeout waiting for marker: " + marker);
        dumpProcessLog(logIndex);
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
     * Verify entity conservation across all nodes.
     *
     * @param expectedTotal Expected total entity count
     * @return true if conservation holds
     */
    private boolean verifyEntityConservation(int expectedTotal) throws IOException {
        var counts = new int[processes.size()];
        var totalCount = 0;

        for (int i = 0; i < processes.size(); i++) {
            counts[i] = parseEntityCount(i);
            if (counts[i] < 0) {
                System.err.println("ERROR: Failed to parse entity count from node " + i);
                return false;
            }
            totalCount += counts[i];
        }

        System.out.println("Entity distribution:");
        for (int i = 0; i < counts.length; i++) {
            System.out.println("  Node " + (i + 1) + ": " + counts[i] + " entities");
        }
        System.out.println("  Total:  " + totalCount + " (expected: " + expectedTotal + ")");

        return totalCount == expectedTotal;
    }

    /**
     * Verify causal consistency by checking lamport clock ordering.
     */
    private boolean verifyCausalConsistency() throws IOException {
        // For each node, verify lamport clocks are monotonically increasing
        for (int i = 0; i < logFiles.size(); i++) {
            var logFile = logFiles.get(i);
            if (!Files.exists(logFile)) {
                continue;
            }

            var lines = Files.readAllLines(logFile);
            long lastClock = -1;

            for (var line : lines) {
                if (line.contains("LAMPORT_CLOCK:")) {
                    try {
                        var parts = line.split("LAMPORT_CLOCK:");
                        if (parts.length > 1) {
                            var clockStr = parts[1].trim().split("\\s+")[0];
                            var clock = Long.parseLong(clockStr);

                            if (lastClock >= 0 && clock < lastClock) {
                                System.err.println("ERROR: Lamport clock violation in node " + i);
                                System.err.println("  Previous: " + lastClock + ", Current: " + clock);
                                return false;
                            }
                            lastClock = clock;
                        }
                    } catch (NumberFormatException e) {
                        // Skip malformed lines
                    }
                }
            }
        }

        return true;
    }

    /**
     * Parse entity count from node log file.
     * Looks for "ENTITY_COUNT: N" marker emitted by node.
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
     * Dump a single process log for debugging.
     */
    private void dumpProcessLog(int logIndex) throws IOException {
        if (logIndex >= logFiles.size()) {
            return;
        }

        var logFile = logFiles.get(logIndex);
        System.err.println("\n=== Log for node " + (logIndex + 1) + " ===");
        if (Files.exists(logFile)) {
            Files.readAllLines(logFile).forEach(System.err::println);
        } else {
            System.err.println("Log file does not exist: " + logFile);
        }
    }

    /**
     * Dump all process logs for debugging.
     */
    private void dumpAllProcessLogs() throws IOException {
        for (int i = 0; i < logFiles.size(); i++) {
            dumpProcessLog(i);
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
