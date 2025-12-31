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
import com.hellblazer.luciferase.esvt.io.ESVTDeserializer;
import com.hellblazer.luciferase.esvt.io.ESVTSerializer;
import com.hellblazer.luciferase.esvt.io.ESVTCompressedSerializer;
import com.hellblazer.luciferase.esvt.optimization.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Optimize mode for running the ESVT optimization pipeline.
 *
 * <p>Applies various optimizations to improve memory layout and traversal performance:
 * <ul>
 *   <li>Memory layout optimization (cache line alignment, type grouping)</li>
 *   <li>Bandwidth optimization (compression, streaming)</li>
 *   <li>Coalescing optimization (GPU memory access patterns)</li>
 *   <li>Traversal optimization (breadth-first layout)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTOptimizeMode {
    private static final Logger log = LoggerFactory.getLogger(ESVTOptimizeMode.class);

    private final ESVTCommandLine.Config config;
    private final PrintStream out;

    public ESVTOptimizeMode(ESVTCommandLine.Config config) {
        this(config, System.out);
    }

    public ESVTOptimizeMode(ESVTCommandLine.Config config, PrintStream out) {
        this.config = config;
        this.out = out;
    }

    /**
     * Run optimize mode.
     */
    public static int run(ESVTCommandLine.Config config) {
        return new ESVTOptimizeMode(config).execute();
    }

    /**
     * Execute optimization.
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

            reportInputInfo(loadResult);

            // Analyze before optimization
            phase("Pre-Optimization Analysis");
            var beforeProfile = analyzeData(loadResult.data);
            reportProfile("Before", beforeProfile);

            // Configure pipeline
            phase("Configuring Optimization Pipeline");
            var pipeline = configurePipeline();
            reportPipelineConfig(pipeline);

            // Run optimization
            phase("Running Optimization");
            var startTime = System.nanoTime();
            var optimizationResult = pipeline.optimize(loadResult.data);
            var optimizationTimeMs = (System.nanoTime() - startTime) / 1_000_000.0;

            var optimizedData = optimizationResult.optimizedData();
            reportOptimizationResult(optimizationResult, optimizationTimeMs);

            // Analyze after optimization
            phase("Post-Optimization Analysis");
            var afterProfile = analyzeData(optimizedData);
            reportProfile("After", afterProfile);

            // Compare before/after
            phase("Improvement Summary");
            reportComparison(beforeProfile, afterProfile);

            // Save output
            if (config.outputFile != null) {
                phase("Saving Optimized ESVT");
                saveOutput(optimizedData, Path.of(config.outputFile));
            }

            printFooter(true);
            return 0;

        } catch (Exception e) {
            error("Optimization failed: " + e.getMessage());
            log.error("Optimization failed", e);
            printFooter(false);
            return 1;
        }
    }

    private LoadResult loadESVTFile(Path path) {
        var result = new LoadResult();

        try {
            result.fileSize = Files.size(path);

            var startTime = System.nanoTime();
            var deserializer = new ESVTDeserializer();
            result.data = deserializer.deserialize(path);
            result.loadTimeMs = (System.nanoTime() - startTime) / 1_000_000.0;

        } catch (IOException e) {
            result.errorMessage = e.getMessage();
        }

        return result;
    }

    private void reportInputInfo(LoadResult result) {
        progress("File size:     " + formatBytes(result.fileSize));
        progress("Load time:     " + String.format("%.2f", result.loadTimeMs) + " ms");
        progress("Nodes:         " + result.data.nodeCount());
        progress("Leaves:        " + result.data.leafCount());
        progress("Max depth:     " + result.data.maxDepth());
    }

    private ESVTOptimizationPipeline configurePipeline() {
        var pipeline = new ESVTOptimizationPipeline();

        // Add optimizers based on configuration
        if (config.optimizeMemory) {
            pipeline.addOptimizer(new ESVTMemoryOptimizer());
        }
        if (config.optimizeBandwidth) {
            pipeline.addOptimizer(new ESVTBandwidthOptimizer());
        }
        if (config.optimizeCoalescing) {
            pipeline.addOptimizer(new ESVTCoalescingOptimizer());
        }

        // Add traversal optimizer by default
        pipeline.addOptimizer(new ESVTTraversalOptimizer());

        return pipeline;
    }

    private void reportPipelineConfig(ESVTOptimizationPipeline pipeline) {
        var optimizers = pipeline.getRegisteredOptimizers();
        progress("Registered optimizers:   " + optimizers.size());
        for (var optimizer : optimizers) {
            progress("  - " + optimizer);
        }
        progress("Memory optimization:     " + (config.optimizeMemory ? "enabled" : "disabled"));
        progress("Bandwidth optimization:  " + (config.optimizeBandwidth ? "enabled" : "disabled"));
        progress("Coalescing optimization: " + (config.optimizeCoalescing ? "enabled" : "disabled"));
        progress("Max passes:              " + config.optimizationPasses);
    }

    private void reportOptimizationResult(
            com.hellblazer.luciferase.sparse.optimization.OptimizationResult<ESVTData> result,
            double timeMs) {
        var report = result.report();

        progress("Optimization time:   " + String.format("%.2f", timeMs) + " ms");
        progress("Passes completed:    " + report.steps().size());
        progress("Overall improvement: " + String.format("%.1f%%", (report.overallImprovement() - 1) * 100));
        out.println();

        if (config.verbose) {
            progress("Optimization Steps:");
            for (var step : report.steps()) {
                out.printf("  %-30s %.2f ms  (%.1fx improvement)%n",
                        step.optimizerName(),
                        step.executionTimeMs(),
                        step.improvementFactor());
            }
        }
    }

    private DataProfile analyzeData(ESVTData data) {
        var profile = new DataProfile();

        profile.nodeCount = data.nodeCount();
        profile.leafCount = data.leafCount();
        profile.internalCount = data.internalCount();
        profile.maxDepth = data.maxDepth();
        profile.contourCount = data.contourCount();
        profile.farPointerCount = data.farPointerCount();

        // Analyze memory layout
        var memoryOptimizer = new ESVTMemoryOptimizer();
        var memLayout = memoryOptimizer.analyzeMemoryLayout(data);
        profile.cacheEfficiency = memLayout.getCacheEfficiency();
        profile.spatialLocality = memLayout.getSpatialLocality();
        profile.fragmentation = memLayout.getFragmentation();
        profile.tetTypeLocality = memLayout.getTetTypeLocality();
        profile.memoryFootprint = memLayout.getMemoryFootprint();

        // Analyze bandwidth
        var bandwidthOptimizer = new ESVTBandwidthOptimizer();
        var bandwidthProfile = bandwidthOptimizer.analyzeBandwidthUsage(data, new int[0]);
        profile.bandwidthReduction = bandwidthProfile.getBandwidthReduction();

        // Analyze tet type distribution
        var typeCounts = new int[6];
        for (var node : data.nodes()) {
            if (node.isValid()) {
                var type = node.getTetType();
                if (type >= 0 && type < 6) {
                    typeCounts[type]++;
                }
            }
        }
        profile.tetTypeCounts = typeCounts;

        return profile;
    }

    private void reportProfile(String label, DataProfile profile) {
        out.println("  " + label + " Profile:");
        out.println("  ─────────────────────────────────────");
        out.println("    Nodes:             " + profile.nodeCount);
        out.println("    Memory:            " + formatBytes(profile.memoryFootprint));
        out.println("    Cache efficiency:  " + String.format("%.1f%%", profile.cacheEfficiency * 100));
        out.println("    Spatial locality:  " + String.format("%.1f%%", profile.spatialLocality * 100));
        out.println("    Tet type locality: " + String.format("%.1f%%", profile.tetTypeLocality * 100));
        out.println("    Fragmentation:     " + String.format("%.1f%%", profile.fragmentation * 100));
    }

    private void reportComparison(DataProfile before, DataProfile after) {
        out.println("  Metric             Before      After       Change");
        out.println("  ─────────────────────────────────────────────────");

        // Cache efficiency
        var cacheChange = (after.cacheEfficiency - before.cacheEfficiency) / before.cacheEfficiency * 100;
        out.printf("  Cache efficiency   %5.1f%%      %5.1f%%      %+.1f%%%n",
                before.cacheEfficiency * 100, after.cacheEfficiency * 100, cacheChange);

        // Spatial locality
        var spatialChange = before.spatialLocality > 0 ?
                (after.spatialLocality - before.spatialLocality) / before.spatialLocality * 100 : 0;
        out.printf("  Spatial locality   %5.1f%%      %5.1f%%      %+.1f%%%n",
                before.spatialLocality * 100, after.spatialLocality * 100, spatialChange);

        // Tet type locality
        var tetChange = before.tetTypeLocality > 0 ?
                (after.tetTypeLocality - before.tetTypeLocality) / before.tetTypeLocality * 100 : 0;
        out.printf("  Tet type locality  %5.1f%%      %5.1f%%      %+.1f%%%n",
                before.tetTypeLocality * 100, after.tetTypeLocality * 100, tetChange);

        // Fragmentation (lower is better)
        var fragChange = before.fragmentation > 0 ?
                (before.fragmentation - after.fragmentation) / before.fragmentation * 100 : 0;
        out.printf("  Fragmentation      %5.1f%%      %5.1f%%      %+.1f%%%n",
                before.fragmentation * 100, after.fragmentation * 100, fragChange);

        out.println("  ─────────────────────────────────────────────────");

        // Overall assessment
        var improvements = 0;
        if (cacheChange > 0) improvements++;
        if (spatialChange > 0) improvements++;
        if (tetChange > 0) improvements++;
        if (fragChange > 0) improvements++;

        var assessment = improvements >= 3 ? "Significant improvement" :
                        improvements >= 2 ? "Moderate improvement" :
                        improvements >= 1 ? "Minor improvement" : "No significant change";

        out.println("  Assessment: " + assessment);
    }

    private void saveOutput(ESVTData data, Path outputPath) throws IOException {
        var startTime = System.nanoTime();

        // Create parent directories if needed
        var parent = outputPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        if (config.useCompression) {
            var serializer = new ESVTCompressedSerializer();
            serializer.serialize(data, outputPath);
        } else {
            var serializer = new ESVTSerializer();
            serializer.serialize(data, outputPath);
        }

        var saveTimeMs = (System.nanoTime() - startTime) / 1_000_000.0;
        var fileSize = Files.size(outputPath);

        progress("Output file:   " + outputPath);
        progress("File size:     " + formatBytes(fileSize));
        progress("Save time:     " + String.format("%.2f", saveTimeMs) + " ms");
    }

    // Helper classes

    private static class LoadResult {
        ESVTData data;
        long fileSize;
        double loadTimeMs;
        String errorMessage;
    }

    private static class DataProfile {
        int nodeCount;
        int leafCount;
        int internalCount;
        int maxDepth;
        int contourCount;
        int farPointerCount;
        float cacheEfficiency;
        float spatialLocality;
        float fragmentation;
        float tetTypeLocality;
        float bandwidthReduction;
        long memoryFootprint;
        int[] tetTypeCounts;
    }

    // Formatting helpers

    private void printHeader() {
        if (config.quiet) return;
        out.println();
        out.println("╔══════════════════════════════════════════════════════════════╗");
        out.println("║                  ESVT Optimize Mode                          ║");
        out.println("╚══════════════════════════════════════════════════════════════╝");
        out.println();
    }

    private void printFooter(boolean success) {
        if (config.quiet) return;
        out.println();
        if (success) {
            out.println("╔══════════════════════════════════════════════════════════════╗");
            out.println("║                 Optimization Complete                        ║");
            out.println("╚══════════════════════════════════════════════════════════════╝");
        } else {
            out.println("╔══════════════════════════════════════════════════════════════╗");
            out.println("║                  Optimization Failed                         ║");
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

    private void progress(String message) {
        if (config.quiet) return;
        out.println("  " + message);
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
}
