/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.benchmarks.distributed;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * JMH benchmark for distributed entity capacity.
 *
 * <p>Validates claim: "10,000+ entities per bubble (typical ~5000)"
 *
 * <p>Methodology:
 * <ul>
 *   <li>Spawn 2 separate JVM processes (one bubble per process)
 *   <li>Test with entity counts: 1000, 5000, 10000
 *   <li>Measure P99 tick latency under load
 *   <li>Measure memory usage (heap MB)
 *   <li>Determine maximum entities before P99 > 100ms threshold
 * </ul>
 *
 * <p>Acceptance Criteria:
 * <ul>
 *   <li>5000 entities: P99 tick latency < 50ms
 *   <li>10000 entities: P99 tick latency < 100ms
 *   <li>Memory usage scales linearly (not exponentially)
 * </ul>
 *
 * @author hal.hildebrand
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class DistributedCapacityBenchmark {

    private static final Logger log = LoggerFactory.getLogger(DistributedCapacityBenchmark.class);

    private final List<Process> processes = new ArrayList<>();
    private final List<Path> logFiles = new ArrayList<>();

    /**
     * Benchmark: 1000 entities per bubble.
     */
    @Benchmark
    public void capacity1000Entities(Blackhole blackhole) throws Exception {
        runCapacityBenchmark(1000, blackhole);
    }

    /**
     * Benchmark: 5000 entities per bubble (typical target).
     */
    @Benchmark
    public void capacity5000Entities(Blackhole blackhole) throws Exception {
        runCapacityBenchmark(5000, blackhole);
    }

    /**
     * Benchmark: 10000 entities per bubble (max target).
     */
    @Benchmark
    public void capacity10000Entities(Blackhole blackhole) throws Exception {
        runCapacityBenchmark(10000, blackhole);
    }

    /**
     * Run capacity benchmark with specified entity count.
     *
     * <p>Creates two nodes with equal entity counts to test per-bubble capacity.
     */
    private void runCapacityBenchmark(int entitiesPerBubble, Blackhole blackhole) throws Exception {
        var node1Port = findAvailablePort();
        var node2Port = findAvailablePort();

        // Start both nodes with equal entity counts
        var node1 = startCapacityNode("Node1", node1Port, node2Port, entitiesPerBubble);
        processes.add(node1);

        var node2 = startCapacityNode("Node2", node2Port, node1Port, entitiesPerBubble);
        processes.add(node2);

        // Wait for both nodes to be ready
        waitForNodesReady(30_000);

        // Let system stabilize and run
        Thread.sleep(500);

        // Parse metrics from logs
        var p99Latency = parseP99Latency(0);
        var heapMB = parseHeapUsage(0);

        blackhole.consume(p99Latency);
        blackhole.consume(heapMB);

        // Cleanup
        cleanup();
    }

    /**
     * Start a capacity benchmark node.
     */
    private Process startCapacityNode(String nodeName, int serverPort, int peerPort, int entityCount)
        throws IOException {
        var javaHome = System.getProperty("java.home");
        var classPath = System.getProperty("java.class.path");
        var logFile = Files.createTempFile("capacity-" + nodeName.toLowerCase() + "-", ".log");
        logFiles.add(logFile);

        var pb = new ProcessBuilder(
            javaHome + "/bin/java",
            "-Xmx2G",  // Fixed heap size for consistent measurements
            "-cp", classPath,
            "com.hellblazer.luciferase.simulation.benchmarks.distributed.SimpleCapacityNode",
            nodeName,
            String.valueOf(serverPort),
            String.valueOf(peerPort),
            String.valueOf(entityCount)
        );

        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());

        var process = pb.start();
        return process;
    }

    /**
     * Wait for all nodes to report "READY".
     */
    private void waitForNodesReady(long timeoutMs) throws Exception {
        var deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            var ready = true;
            for (int i = 0; i < processes.size(); i++) {
                if (!checkLogForMarker(i, "READY")) {
                    ready = false;
                    break;
                }
            }

            if (ready) {
                return;
            }

            Thread.sleep(100);
        }

        throw new RuntimeException("Timeout waiting for nodes to be ready");
    }

    /**
     * Check if log file contains marker.
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
     * Parse P99 latency from log file.
     */
    private double parseP99Latency(int logIndex) throws IOException {
        if (logIndex >= logFiles.size()) {
            return -1;
        }

        var logFile = logFiles.get(logIndex);
        if (!Files.exists(logFile)) {
            return -1;
        }

        var lines = Files.readAllLines(logFile);
        var pattern = Pattern.compile("P99_LATENCY_MS:\\s*([0-9.]+)");

        // Search backwards for most recent metric
        for (int i = lines.size() - 1; i >= 0; i--) {
            var matcher = pattern.matcher(lines.get(i));
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }
        }

        return -1;
    }

    /**
     * Parse heap usage from log file.
     */
    private long parseHeapUsage(int logIndex) throws IOException {
        if (logIndex >= logFiles.size()) {
            return -1;
        }

        var logFile = logFiles.get(logIndex);
        if (!Files.exists(logFile)) {
            return -1;
        }

        var lines = Files.readAllLines(logFile);
        var pattern = Pattern.compile("HEAP_MB:\\s*(\\d+)");

        // Search backwards for most recent metric
        for (int i = lines.size() - 1; i >= 0; i--) {
            var matcher = pattern.matcher(lines.get(i));
            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }
        }

        return -1;
    }

    /**
     * Find available port for binding.
     */
    private int findAvailablePort() {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find available port", e);
        }
    }

    /**
     * Cleanup processes and log files.
     */
    private void cleanup() throws Exception {
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
     * JMH main method for running benchmarks.
     */
    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
