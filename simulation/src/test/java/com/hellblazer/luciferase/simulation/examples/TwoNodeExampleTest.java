/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.examples;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for TwoNodeExample - validates distributed volumetric animation.
 *
 * <p>Test Scenario:
 * <ul>
 *   <li>Spawn 2 JVMs (Node1, Node2) with separate bubbles
 *   <li>Node1 bounds: (0-50, 0-50, 0-50), spawns 50 entities
 *   <li>Node2 bounds: (50-100, 0-50, 0-50), receives entities
 *   <li>Entities migrate across x=50 boundary via gRPC
 *   <li>Verify: no entity duplication or loss
 * </ul>
 *
 * <p>Pattern: Reuses ProcessBuilder pattern from ProcessBuilderSpikeTest.
 *
 * @author hal.hildebrand
 */
@Tag("integration")
public class TwoNodeExampleTest {

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
    @DisabledIfEnvironmentVariable(
        named = "CI",
        matches = "true",
        disabledReason = "Flaky: PrimeMover bytecode transformation issue under CI load (NullPointerException in Devi.java futureSailor field)"
    )
    void testTwoNodeEntityMigration() throws Exception {
        // Setup: Dynamic ports for gRPC servers
        var node1Port = findAvailablePort();
        var node2Port = findAvailablePort();

        System.out.println("Starting TwoNodeExample test:");
        System.out.println("  Node 1: port " + node1Port + " (bounds: 0-50)");
        System.out.println("  Node 2: port " + node2Port + " (bounds: 50-100)");

        // Start Node 1 (will spawn entities)
        var node1 = startNodeProcess("Node1", node1Port, node2Port);
        processes.add(node1);

        // Start Node 2 (will receive entities)
        var node2 = startNodeProcess("Node2", node2Port, node1Port);
        processes.add(node2);

        // Phase 1: Wait for both nodes to be ready
        assertThat(waitForNodesReady(node1, node2, Duration.ofSeconds(15)))
            .as("Both nodes should start and be ready within 15 seconds")
            .isTrue();
        System.out.println("✓ Both nodes ready");

        // Phase 2: Wait for entities to spawn in Node 1
        assertThat(pollForMarker(0, "ENTITIES_SPAWNED", Duration.ofSeconds(10)))
            .as("Node 1 should spawn entities within 10 seconds")
            .isTrue();
        System.out.println("✓ Entities spawned in Node 1");

        // Phase 3: Wait for at least one entity to migrate to Node 2
        // (Entities move randomly, some should cross x=50 boundary within 30 seconds)
        assertThat(pollForMarker(1, "ENTITY_ARRIVED", Duration.ofSeconds(30)))
            .as("At least one entity should migrate from Node 1 to Node 2")
            .isTrue();
        System.out.println("✓ Entity migration detected");

        // Phase 4: Let simulation run for 10 seconds to accumulate migrations
        System.out.println("Letting simulation run for 10 seconds...");
        Thread.sleep(10_000);

        // Phase 5: Verify no entity duplication or loss
        // Both nodes should report consistent entity counts
        assertThat(verifyEntityAccountingConsistent(node1, node2))
            .as("Entity accounting should be consistent (no duplication or loss)")
            .isTrue();
        System.out.println("✓ Entity accounting consistent");

        System.out.println("✓ TwoNodeExample test PASSED");
    }

    /**
     * Start a node process for TwoNodeExample.
     *
     * @param nodeName Node name ("Node1" or "Node2")
     * @param serverPort Port this node listens on
     * @param peerPort Port of peer node
     * @return Started Process
     */
    private Process startNodeProcess(String nodeName, int serverPort, int peerPort) throws IOException {
        var javaHome = System.getProperty("java.home");
        var classPath = System.getProperty("java.class.path");
        var logFile = Files.createTempFile("two-node-" + nodeName.toLowerCase() + "-", ".log");
        logFiles.add(logFile);

        var pb = new ProcessBuilder(
            javaHome + "/bin/java",
            "-cp", classPath,
            "com.hellblazer.luciferase.simulation.examples.TwoNodeExample",
            nodeName,
            String.valueOf(serverPort),
            String.valueOf(peerPort)
        );

        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());

        var process = pb.start();
        System.out.println("Started " + nodeName + " (PID: " + process.pid() + ", log: " + logFile + ")");
        return process;
    }

    /**
     * Wait for both nodes to report "READY" in their output.
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
                return true;
            }

            Thread.sleep(100);
        }

        System.err.println("Timeout waiting for nodes to be ready");
        dumpProcessLogs(node1, node2);
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
                dumpProcessLogs(processes.get(logIndex));
                return false;
            }

            if (checkLogForMarker(logIndex, marker)) {
                return true;
            }

            Thread.sleep(200);
        }

        System.err.println("Timeout waiting for marker: " + marker);
        dumpProcessLogs(processes.get(logIndex));
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
     * Verify entity accounting is consistent across both nodes.
     *
     * Checks that total entity count matches initial spawn count
     * and no entities are duplicated or lost.
     */
    private boolean verifyEntityAccountingConsistent(Process node1, Process node2) throws IOException, InterruptedException {
        // ENTITY_COUNT is logged every 500ms independently by each node.
        // A migration happening between the two log events causes a momentary
        // inconsistency (sum 49 or 51). Retry a few times to catch a quiescent window.
        var expectedCount = 50; // Node1 spawns 50 entities

        for (int attempt = 0; attempt < 6; attempt++) {
            var node1Count = parseEntityCount(0);
            var node2Count = parseEntityCount(1);

            if (node1Count < 0 || node2Count < 0) {
                System.err.println("Attempt " + attempt + ": Failed to parse entity counts");
                Thread.sleep(200);
                continue;
            }

            var totalCount = node1Count + node2Count;

            System.out.println("Entity distribution (attempt " + attempt + "):");
            System.out.println("  Node 1: " + node1Count + " entities");
            System.out.println("  Node 2: " + node2Count + " entities");
            System.out.println("  Total:  " + totalCount + " (expected: " + expectedCount + ")");

            if (totalCount == expectedCount) {
                return true;
            }

            // Brief pause lets any in-flight entity complete its migration
            Thread.sleep(200);
        }

        // Final attempt: log the mismatch clearly
        var node1Count = parseEntityCount(0);
        var node2Count = parseEntityCount(1);
        System.err.println("FINAL: Node1=" + node1Count + " Node2=" + node2Count +
                          " Total=" + (node1Count + node2Count) + " Expected=" + expectedCount);
        return (node1Count + node2Count) == expectedCount;
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
                    // Extract number after "ENTITY_COUNT: "
                    var parts = line.split("ENTITY_COUNT:");
                    if (parts.length > 1) {
                        var countStr = parts[1].trim().split("\\s+")[0]; // First token after colon
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
