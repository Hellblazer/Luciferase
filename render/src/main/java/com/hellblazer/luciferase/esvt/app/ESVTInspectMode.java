/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvt.app;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import com.hellblazer.luciferase.esvt.io.ESVTDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Inspect mode for analyzing ESVT file structure and validity.
 *
 * <p>Provides comprehensive inspection of ESVT files including:
 * <ul>
 *   <li>File information and header validation</li>
 *   <li>Structural validation (orphaned nodes, invalid pointers)</li>
 *   <li>Tree analysis (depth, branching factor, balance)</li>
 *   <li>Tetrahedron type distribution (S0-S5)</li>
 *   <li>Memory usage analysis</li>
 *   <li>Performance characteristics estimation</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTInspectMode {
    private static final Logger log = LoggerFactory.getLogger(ESVTInspectMode.class);

    private final ESVTCommandLine.Config config;
    private final PrintStream out;

    public ESVTInspectMode(ESVTCommandLine.Config config) {
        this(config, System.out);
    }

    public ESVTInspectMode(ESVTCommandLine.Config config, PrintStream out) {
        this.config = config;
        this.out = out;
    }

    /**
     * Run inspect mode.
     */
    public static int run(ESVTCommandLine.Config config) {
        return new ESVTInspectMode(config).execute();
    }

    /**
     * Execute inspection.
     */
    public int execute() {
        try {
            printHeader();

            // Load ESVT file
            phase("Loading ESVT File");
            var inputPath = Path.of(config.inputFile);
            var loadResult = loadESVTFile(inputPath);

            if (loadResult.data == null) {
                error("Failed to load ESVT file: " + loadResult.errorMessage);
                printFooter(false);
                return 1;
            }

            var data = loadResult.data;
            reportFileInfo(inputPath, loadResult);

            // Structural validation
            if (config.validateStructure) {
                phase("Structural Validation");
                var validation = validateStructure(data);
                reportValidation(validation);

                if (!validation.isValid() && validation.criticalErrors > 0) {
                    error("Critical structural errors detected");
                }
            }

            // Tree analysis
            phase("Tree Analysis");
            var analysis = analyzeTree(data);
            reportTreeAnalysis(analysis);

            // Tetrahedron type distribution
            phase("Tetrahedron Type Distribution");
            var tetDist = analyzeTetTypeDistribution(data);
            reportTetDistribution(tetDist);

            // Memory analysis
            phase("Memory Analysis");
            var memory = analyzeMemory(data);
            reportMemoryAnalysis(memory);

            // Performance characteristics
            if (config.analyzePerformance) {
                phase("Performance Characteristics");
                var perf = analyzePerformance(data, analysis);
                reportPerformanceAnalysis(perf);
            }

            // GPU compatibility
            phase("GPU Compatibility");
            reportGPUCompatibility(data);

            printFooter(true);
            return 0;

        } catch (Exception e) {
            error("Inspection failed: " + e.getMessage());
            log.error("Inspection failed", e);
            printFooter(false);
            return 1;
        }
    }

    private LoadResult loadESVTFile(Path path) {
        var result = new LoadResult();
        result.filePath = path;

        try {
            result.fileSize = Files.size(path);
            result.lastModified = Files.getLastModifiedTime(path).toMillis();

            var startTime = System.nanoTime();
            var deserializer = new ESVTDeserializer();
            result.data = deserializer.deserialize(path);
            result.loadTimeMs = (System.nanoTime() - startTime) / 1_000_000.0;

        } catch (IOException e) {
            result.errorMessage = e.getMessage();
        }

        return result;
    }

    private void reportFileInfo(Path path, LoadResult result) {
        out.println("  File path:       " + path.toAbsolutePath());
        out.println("  File size:       " + formatBytes(result.fileSize));
        out.println("  Load time:       " + String.format("%.2f", result.loadTimeMs) + " ms");
        out.println();
        out.println("  Basic Info:");
        out.println("    Total nodes:   " + result.data.nodeCount());
        out.println("    Leaf nodes:    " + result.data.leafCount());
        out.println("    Internal:      " + result.data.internalCount());
        out.println("    Max depth:     " + result.data.maxDepth());
        out.println("    Grid res:      " + result.data.gridResolution());
        out.println("    Root type:     " + result.data.rootType());
        out.println("    Contours:      " + result.data.contourCount());
        out.println("    Far pointers:  " + result.data.farPointerCount());
    }

    private ValidationResult validateStructure(ESVTData data) {
        var result = new ValidationResult();
        var nodes = data.nodes();

        // Track reachable nodes
        var reachable = new boolean[nodes.length];
        var queue = new ArrayDeque<Integer>();

        // Start from root (index 0)
        if (nodes.length > 0) {
            queue.add(0);
            reachable[0] = true;
        }

        // BFS to find all reachable nodes
        while (!queue.isEmpty()) {
            var idx = queue.poll();
            var node = nodes[idx];

            if (node.isValid() && node.getChildMask() != 0) {
                var childPtr = node.getChildPtr();
                var childMask = node.getChildMask();

                // Validate child pointer
                if (childPtr < 0 || childPtr >= nodes.length) {
                    result.invalidPointers++;
                    result.criticalErrors++;
                    if (config.verbose) {
                        out.println("  ! Invalid child pointer at node " + idx + ": " + childPtr);
                    }
                    continue;
                }

                // Check each child
                var childCount = Integer.bitCount(childMask);
                for (int c = 0; c < childCount; c++) {
                    var childIdx = childPtr + c;
                    if (childIdx >= 0 && childIdx < nodes.length && !reachable[childIdx]) {
                        reachable[childIdx] = true;
                        queue.add(childIdx);
                    }
                }
            }
        }

        // Count orphaned nodes
        for (int i = 0; i < nodes.length; i++) {
            if (!reachable[i] && nodes[i].isValid()) {
                result.orphanedNodes++;
            }
        }

        // Validate node integrity
        for (int i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            if (!node.isValid()) {
                result.invalidNodes++;
                continue;
            }

            var tetType = node.getTetType();
            if (tetType < 0 || tetType > 5) {
                result.invalidTetTypes++;
                if (config.verbose) {
                    out.println("  ! Invalid tet type at node " + i + ": " + tetType);
                }
            }
        }

        // Check contour references
        var contours = data.contours();
        for (var node : nodes) {
            if (node.isValid() && node.hasContour()) {
                var contourIdx = node.getContourPtr();
                if (contourIdx < 0 || contourIdx >= contours.length) {
                    result.invalidContourRefs++;
                }
            }
        }

        // Check far pointer references
        var farPointers = data.farPointers();
        for (var node : nodes) {
            if (node.isValid() && node.isFar()) {
                var farIdx = node.getChildPtr();
                if (farIdx < 0 || farIdx >= farPointers.length) {
                    result.invalidFarPtrRefs++;
                }
            }
        }

        result.totalNodes = nodes.length;
        return result;
    }

    private void reportValidation(ValidationResult result) {
        var statusSymbol = result.isValid() ? "✓" : "✗";
        out.println("  Status:          " + statusSymbol + " " + (result.isValid() ? "VALID" : "INVALID"));
        out.println();
        out.println("  Node Statistics:");
        out.println("    Total nodes:       " + result.totalNodes);
        out.println("    Invalid nodes:     " + result.invalidNodes);
        out.println("    Orphaned nodes:    " + result.orphanedNodes);
        out.println();
        out.println("  Pointer Validation:");
        out.println("    Invalid pointers:  " + result.invalidPointers);
        out.println("    Invalid tet types: " + result.invalidTetTypes);
        out.println("    Invalid contours:  " + result.invalidContourRefs);
        out.println("    Invalid far ptrs:  " + result.invalidFarPtrRefs);

        if (result.criticalErrors > 0) {
            out.println();
            out.println("  ⚠ CRITICAL ERRORS: " + result.criticalErrors);
        }
    }

    private TreeAnalysis analyzeTree(ESVTData data) {
        var analysis = new TreeAnalysis();
        var nodes = data.nodes();

        if (nodes.length == 0) {
            return analysis;
        }

        // Compute depth for each node via BFS
        var depths = new int[nodes.length];
        Arrays.fill(depths, -1);
        var queue = new ArrayDeque<Integer>();

        queue.add(0);
        depths[0] = 0;

        while (!queue.isEmpty()) {
            var idx = queue.poll();
            var node = nodes[idx];
            var depth = depths[idx];

            analysis.maxDepth = Math.max(analysis.maxDepth, depth);
            analysis.nodesPerDepth.merge(depth, 1, Integer::sum);

            if (node.isValid() && node.getChildMask() != 0) {
                var childPtr = node.getChildPtr();
                var childMask = node.getChildMask();
                var childCount = Integer.bitCount(childMask);

                analysis.branchingFactorSum += childCount;
                analysis.internalNodes++;

                for (int c = 0; c < childCount; c++) {
                    var childIdx = childPtr + c;
                    if (childIdx >= 0 && childIdx < nodes.length && depths[childIdx] == -1) {
                        depths[childIdx] = depth + 1;
                        queue.add(childIdx);
                    }
                }
            } else {
                analysis.leafNodes++;
                analysis.leafDepths.add(depth);
            }
        }

        // Calculate depth statistics
        if (!analysis.leafDepths.isEmpty()) {
            analysis.minLeafDepth = Collections.min(analysis.leafDepths);
            analysis.maxLeafDepth = Collections.max(analysis.leafDepths);
            analysis.avgLeafDepth = analysis.leafDepths.stream().mapToInt(Integer::intValue).average().orElse(0);
        }

        return analysis;
    }

    private void reportTreeAnalysis(TreeAnalysis analysis) {
        out.println("  Tree Depth:");
        out.println("    Maximum depth:     " + analysis.maxDepth);
        out.println("    Min leaf depth:    " + analysis.minLeafDepth);
        out.println("    Max leaf depth:    " + analysis.maxLeafDepth);
        out.println("    Avg leaf depth:    " + String.format("%.2f", analysis.avgLeafDepth));
        out.println();
        out.println("  Tree Structure:");
        out.println("    Internal nodes:    " + analysis.internalNodes);
        out.println("    Leaf nodes:        " + analysis.leafNodes);

        var avgBranching = analysis.internalNodes > 0 ?
                (double) analysis.branchingFactorSum / analysis.internalNodes : 0;
        out.println("    Avg branching:     " + String.format("%.2f", avgBranching));

        // Depth distribution (abbreviated)
        if (config.verbose && !analysis.nodesPerDepth.isEmpty()) {
            out.println();
            out.println("  Nodes per Depth:");
            for (var entry : analysis.nodesPerDepth.entrySet()) {
                out.println("    Level " + entry.getKey() + ":         " + entry.getValue());
            }
        }
    }

    private TetTypeDistribution analyzeTetTypeDistribution(ESVTData data) {
        var dist = new TetTypeDistribution();
        var nodes = data.nodes();

        for (var node : nodes) {
            if (node.isValid()) {
                var type = node.getTetType();
                if (type >= 0 && type <= 5) {
                    dist.typeCounts[type]++;
                }
            }
        }

        dist.totalValid = Arrays.stream(dist.typeCounts).sum();
        return dist;
    }

    private void reportTetDistribution(TetTypeDistribution dist) {
        String[] typeNames = {"S0", "S1", "S2", "S3", "S4", "S5"};

        out.println("  Type   Count      Percentage");
        out.println("  ─────────────────────────────");

        for (int i = 0; i < 6; i++) {
            var count = dist.typeCounts[i];
            var pct = dist.totalValid > 0 ? (100.0 * count / dist.totalValid) : 0;
            out.printf("  %s     %-10d %.1f%%%n", typeNames[i], count, pct);
        }

        out.println("  ─────────────────────────────");
        out.println("  Total  " + dist.totalValid);
    }

    private MemoryAnalysis analyzeMemory(ESVTData data) {
        var mem = new MemoryAnalysis();

        mem.nodeBytes = (long) data.nodeCount() * ESVTNodeUnified.SIZE_BYTES;
        mem.contourBytes = (long) data.contourCount() * 4;
        mem.farPointerBytes = (long) data.farPointerCount() * 4;
        mem.leafCoordsBytes = data.leafVoxelCoords() != null ? data.leafVoxelCoords().length * 4L : 0;
        mem.totalBytes = mem.nodeBytes + mem.contourBytes + mem.farPointerBytes + mem.leafCoordsBytes;

        mem.bytesPerNode = data.nodeCount() > 0 ? (double) mem.totalBytes / data.nodeCount() : 0;

        return mem;
    }

    private void reportMemoryAnalysis(MemoryAnalysis mem) {
        out.println("  Component        Size          Percentage");
        out.println("  ─────────────────────────────────────────");

        var nodePct = mem.totalBytes > 0 ? (100.0 * mem.nodeBytes / mem.totalBytes) : 0;
        var contourPct = mem.totalBytes > 0 ? (100.0 * mem.contourBytes / mem.totalBytes) : 0;
        var farPct = mem.totalBytes > 0 ? (100.0 * mem.farPointerBytes / mem.totalBytes) : 0;
        var coordsPct = mem.totalBytes > 0 ? (100.0 * mem.leafCoordsBytes / mem.totalBytes) : 0;

        out.printf("  Node data        %-12s  %.1f%%%n", formatBytes(mem.nodeBytes), nodePct);
        out.printf("  Contour data     %-12s  %.1f%%%n", formatBytes(mem.contourBytes), contourPct);
        out.printf("  Far pointers     %-12s  %.1f%%%n", formatBytes(mem.farPointerBytes), farPct);
        out.printf("  Leaf coords      %-12s  %.1f%%%n", formatBytes(mem.leafCoordsBytes), coordsPct);
        out.println("  ─────────────────────────────────────────");
        out.printf("  Total            %-12s%n", formatBytes(mem.totalBytes));
        out.println();
        out.println("  Average:         " + String.format("%.2f", mem.bytesPerNode) + " bytes/node");
    }

    private PerformanceAnalysis analyzePerformance(ESVTData data, TreeAnalysis tree) {
        var perf = new PerformanceAnalysis();

        // Estimate traversal cost
        perf.avgTraversalDepth = tree.avgLeafDepth;
        perf.maxTraversalDepth = tree.maxLeafDepth;

        // Memory bandwidth estimate (bytes per traversal)
        perf.bytesPerTraversal = perf.avgTraversalDepth * ESVTNodeUnified.SIZE_BYTES;

        // Cache efficiency estimate
        var cacheLineSize = 64;
        var nodesPerCacheLine = cacheLineSize / ESVTNodeUnified.SIZE_BYTES;
        perf.cacheEfficiency = Math.min(1.0, (double) nodesPerCacheLine / (perf.avgTraversalDepth + 1));

        // Theoretical rays per second (very rough estimate)
        // Assumes ~100 ns per node access on modern hardware
        var nsPerNode = 100.0;
        var nsPerRay = perf.avgTraversalDepth * nsPerNode;
        perf.estimatedRaysPerSecond = 1_000_000_000.0 / nsPerRay;

        return perf;
    }

    private void reportPerformanceAnalysis(PerformanceAnalysis perf) {
        out.println("  Traversal Estimates:");
        out.println("    Avg depth:         " + String.format("%.1f", perf.avgTraversalDepth) + " nodes");
        out.println("    Max depth:         " + perf.maxTraversalDepth + " nodes");
        out.println("    Bytes/traversal:   " + String.format("%.0f", perf.bytesPerTraversal) + " B");
        out.println();
        out.println("  Efficiency:");
        out.println("    Cache efficiency:  " + String.format("%.1f%%", perf.cacheEfficiency * 100));
        out.println("    Est. rays/sec:     " + formatNumber(perf.estimatedRaysPerSecond));
    }

    private void reportGPUCompatibility(ESVTData data) {
        // Check GPU compatibility constraints
        var issues = new ArrayList<String>();

        // Check node count (GPU memory limits)
        if (data.nodeCount() > 100_000_000) {
            issues.add("Very large node count may exceed GPU memory");
        }

        // Check for far pointers (may affect GPU efficiency)
        if (data.farPointerCount() > data.nodeCount() * 0.1) {
            issues.add("High far pointer ratio (" + String.format("%.1f%%",
                    100.0 * data.farPointerCount() / data.nodeCount()) + ") may reduce GPU efficiency");
        }

        // Check max depth (affects stack depth on GPU)
        if (data.maxDepth() > 21) {
            issues.add("Max depth " + data.maxDepth() + " exceeds recommended limit for GPU");
        }

        if (issues.isEmpty()) {
            out.println("  ✓ GPU Compatible");
            out.println();
            out.println("  Recommendations:");
            out.println("    - Suitable for GPU ray traversal");
            out.println("    - 8-byte nodes align well with GPU memory");
            out.println("    - Consider memory coalescing optimization");
        } else {
            out.println("  ⚠ GPU Compatibility Issues:");
            for (var issue : issues) {
                out.println("    - " + issue);
            }
        }
    }

    // Helper classes

    private static class LoadResult {
        Path filePath;
        long fileSize;
        long lastModified;
        double loadTimeMs;
        ESVTData data;
        String errorMessage;
    }

    private static class ValidationResult {
        int totalNodes;
        int invalidNodes;
        int orphanedNodes;
        int invalidPointers;
        int invalidTetTypes;
        int invalidContourRefs;
        int invalidFarPtrRefs;
        int criticalErrors;

        boolean isValid() {
            return invalidPointers == 0 && criticalErrors == 0;
        }
    }

    private static class TreeAnalysis {
        int maxDepth;
        int minLeafDepth = Integer.MAX_VALUE;
        int maxLeafDepth;
        double avgLeafDepth;
        int internalNodes;
        int leafNodes;
        int branchingFactorSum;
        Map<Integer, Integer> nodesPerDepth = new TreeMap<>();
        List<Integer> leafDepths = new ArrayList<>();
    }

    private static class TetTypeDistribution {
        int[] typeCounts = new int[6];
        int totalValid;
    }

    private static class MemoryAnalysis {
        long nodeBytes;
        long contourBytes;
        long farPointerBytes;
        long leafCoordsBytes;
        long totalBytes;
        double bytesPerNode;
    }

    private static class PerformanceAnalysis {
        double avgTraversalDepth;
        int maxTraversalDepth;
        double bytesPerTraversal;
        double cacheEfficiency;
        double estimatedRaysPerSecond;
    }

    // Formatting helpers

    private void printHeader() {
        if (config.quiet) return;
        out.println();
        out.println("╔══════════════════════════════════════════════════════════════╗");
        out.println("║                   ESVT Inspect Mode                          ║");
        out.println("╚══════════════════════════════════════════════════════════════╝");
        out.println();
    }

    private void printFooter(boolean success) {
        if (config.quiet) return;
        out.println();
        if (success) {
            out.println("╔══════════════════════════════════════════════════════════════╗");
            out.println("║                  Inspection Complete                         ║");
            out.println("╚══════════════════════════════════════════════════════════════╝");
        } else {
            out.println("╔══════════════════════════════════════════════════════════════╗");
            out.println("║                   Inspection Failed                          ║");
            out.println("╚══════════════════════════════════════════════════════════════╝");
        }
        out.println();
    }

    private void phase(String name) {
        if (config.quiet) return;
        out.println();
        out.println("► " + name);
        out.println("─".repeat(62));
    }

    private void error(String message) {
        out.println("  ✗ ERROR: " + message);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    private static String formatNumber(double n) {
        if (n >= 1_000_000_000) {
            return String.format("%.1f B", n / 1_000_000_000);
        } else if (n >= 1_000_000) {
            return String.format("%.1f M", n / 1_000_000);
        } else if (n >= 1_000) {
            return String.format("%.1f K", n / 1_000);
        } else {
            return String.format("%.0f", n);
        }
    }
}
