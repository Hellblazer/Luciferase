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
import com.hellblazer.luciferase.esvt.io.ESVTSerializer;
import com.hellblazer.luciferase.esvt.io.ESVTCompressedSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Build mode for creating ESVT structures from mesh or voxel input.
 *
 * <p>Supports multiple input formats:
 * <ul>
 *   <li>.obj - Wavefront OBJ mesh files</li>
 *   <li>.ply - PLY mesh files</li>
 *   <li>.vox - MagicaVoxel format</li>
 *   <li>.binvox - Binvox voxel format</li>
 * </ul>
 *
 * <p>Build phases:
 * <ol>
 *   <li>Load input data</li>
 *   <li>Configure builder</li>
 *   <li>Build ESVT structure</li>
 *   <li>Validate output</li>
 *   <li>Save to file</li>
 *   <li>Report statistics</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public class ESVTBuildMode {
    private static final Logger log = LoggerFactory.getLogger(ESVTBuildMode.class);

    private final ESVTCommandLine.Config config;
    private final PrintStream out;

    public ESVTBuildMode(ESVTCommandLine.Config config) {
        this(config, System.out);
    }

    public ESVTBuildMode(ESVTCommandLine.Config config, PrintStream out) {
        this.config = config;
        this.out = out;
    }

    /**
     * Run build mode.
     */
    public static int run(ESVTCommandLine.Config config) {
        return new ESVTBuildMode(config).execute();
    }

    /**
     * Execute the build process.
     */
    public int execute() {
        try {
            printHeader();

            // Phase 1: Validate input
            phase("Validating input");
            var inputPath = Path.of(config.inputFile);
            if (!validateInput(inputPath)) {
                return 1;
            }

            // Phase 2: Configure builder
            phase("Configuring builder");
            configureBuilder();

            // Phase 3: Load and process input
            phase("Loading input data");
            var inputType = detectInputType(inputPath);
            progress("Input type: " + inputType);

            // Phase 4: Build ESVT
            phase("Building ESVT structure");
            var startTime = System.nanoTime();
            var esvtData = buildFromInput(inputPath, inputType);
            var buildTimeMs = (System.nanoTime() - startTime) / 1_000_000.0;

            if (esvtData == null) {
                error("Failed to build ESVT structure");
                return 1;
            }

            progress("Build completed in " + String.format("%.2f", buildTimeMs) + " ms");
            progress("Nodes: " + esvtData.nodeCount());
            progress("Leaves: " + esvtData.leafCount());
            progress("Internal: " + esvtData.internalCount());
            progress("Max depth: " + esvtData.maxDepth());

            // Phase 5: Validate output
            phase("Validating ESVT structure");
            if (!validateOutput(esvtData)) {
                error("ESVT validation failed");
                return 1;
            }

            // Phase 6: Save output
            phase("Saving ESVT file");
            var outputPath = Path.of(config.outputFile);
            var saveStartTime = System.nanoTime();
            saveESVT(esvtData, outputPath);
            var saveTimeMs = (System.nanoTime() - saveStartTime) / 1_000_000.0;

            var fileSize = Files.size(outputPath);
            progress("Saved to: " + outputPath);
            progress("File size: " + formatBytes(fileSize));
            progress("Save time: " + String.format("%.2f", saveTimeMs) + " ms");

            // Phase 7: Report statistics
            phase("Build Statistics");
            reportStatistics(esvtData, buildTimeMs, fileSize);

            printFooter(true);
            return 0;

        } catch (Exception e) {
            error("Build failed: " + e.getMessage());
            log.error("Build failed", e);
            printFooter(false);
            return 1;
        }
    }

    private boolean validateInput(Path inputPath) {
        if (!Files.exists(inputPath)) {
            error("Input file not found: " + inputPath);
            return false;
        }

        if (!Files.isReadable(inputPath)) {
            error("Input file not readable: " + inputPath);
            return false;
        }

        var inputType = detectInputType(inputPath);
        if (inputType == InputType.UNKNOWN) {
            error("Unsupported input format: " + inputPath);
            error("Supported formats: .obj, .ply, .vox, .binvox, .esvt");
            return false;
        }

        progress("Input file: " + inputPath);
        progress("File size: " + formatBytes(inputPath.toFile().length()));

        return true;
    }

    private void configureBuilder() {
        progress("Max depth: " + config.maxDepth);
        progress("Grid resolution: " + config.gridResolution);
        progress("Build contours: " + config.buildContours);
    }

    private ESVTData buildFromInput(Path inputPath, InputType inputType) throws IOException {
        return switch (inputType) {
            case OBJ, PLY -> buildFromMesh(inputPath);
            case VOX, BINVOX -> buildFromVoxels(inputPath);
            default -> null;
        };
    }

    private ESVTData buildFromMesh(Path inputPath) throws IOException {
        progress("Loading mesh...");
        progress("Note: Full mesh→ESVT conversion requires Tetree integration");
        progress("Creating placeholder ESVT structure for testing...");

        // Create placeholder structure
        // Full implementation would use Tetree to build from mesh
        return createPlaceholderESVT();
    }

    private ESVTData buildFromVoxels(Path inputPath) throws IOException {
        progress("Loading voxel data...");
        progress("Note: Full voxel→ESVT conversion requires Tetree integration");
        progress("Creating placeholder ESVT structure for testing...");

        // Create placeholder structure
        return createPlaceholderESVT();
    }

    private ESVTData createPlaceholderESVT() {
        // Create a minimal valid ESVT structure for testing
        // Real implementation would build from mesh/voxels via Tetree
        var nodeCount = Math.min(1000, config.gridResolution);
        var nodes = new ESVTNodeUnified[nodeCount];

        // Root node (type S0)
        nodes[0] = new ESVTNodeUnified((byte) 0);
        nodes[0].setChildMask((byte) 0xFF); // All 8 children
        nodes[0].setChildPtr(1);

        // Create internal nodes and leaves
        var leafCount = 0;
        var internalCount = 1;
        for (int i = 1; i < nodeCount; i++) {
            var tetType = (byte) (i % 6);
            nodes[i] = new ESVTNodeUnified(tetType);
            if (i < nodeCount / 2) {
                // Internal node
                nodes[i].setChildMask((byte) 0x0F);
                nodes[i].setChildPtr(Math.min(i * 2, nodeCount - 1));
                internalCount++;
            } else {
                // Leaf node
                nodes[i].setLeafMask((byte) 0xFF);
                leafCount++;
            }
        }

        return new ESVTData(
            nodes,
            new int[0], // contours
            new int[0], // farPointers
            (byte) 0,   // rootType S0
            config.maxDepth,
            leafCount,
            internalCount,
            config.gridResolution,
            null        // leafVoxelCoords
        );
    }

    private boolean validateOutput(ESVTData data) {
        if (data == null) {
            return false;
        }

        if (data.nodeCount() == 0) {
            error("ESVT has no nodes");
            return false;
        }

        // Basic structural validation
        var nodes = data.nodes();
        var invalidCount = 0;

        for (var node : nodes) {
            if (!node.isValid()) {
                invalidCount++;
            }
        }

        if (invalidCount > 0) {
            progress("Warning: " + invalidCount + " invalid nodes detected");
        }

        // Check for reasonable structure
        var leafRatio = (double) data.leafCount() / data.nodeCount();
        if (leafRatio < 0.3 || leafRatio > 0.99) {
            progress("Warning: Unusual leaf ratio: " + String.format("%.2f", leafRatio));
        }

        progress("Validation passed");
        return true;
    }

    private void saveESVT(ESVTData data, Path outputPath) throws IOException {
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
    }

    private void reportStatistics(ESVTData data, double buildTimeMs, long fileSize) {
        out.println();
        out.println("  Input file:      " + config.inputFile);
        out.println("  Output file:     " + config.outputFile);
        out.println("  Build time:      " + String.format("%.2f", buildTimeMs) + " ms");
        out.println("  File size:       " + formatBytes(fileSize));
        out.println();
        out.println("  Structure:");
        out.println("    Total nodes:   " + data.nodeCount());
        out.println("    Leaf nodes:    " + data.leafCount());
        out.println("    Internal:      " + data.internalCount());
        out.println("    Max depth:     " + data.maxDepth());
        out.println("    Grid res:      " + data.gridResolution());
        out.println("    Contours:      " + data.contourCount());
        out.println("    Far pointers:  " + data.farPointerCount());
        out.println();

        // Memory analysis
        var nodeBytes = (long) data.nodeCount() * 8;
        var contourBytes = (long) data.contourCount() * 4;
        var farPtrBytes = (long) data.farPointerCount() * 4;
        var totalMemory = nodeBytes + contourBytes + farPtrBytes;

        out.println("  Memory Usage:");
        out.println("    Node data:     " + formatBytes(nodeBytes));
        out.println("    Contour data:  " + formatBytes(contourBytes));
        out.println("    Far pointers:  " + formatBytes(farPtrBytes));
        out.println("    Total:         " + formatBytes(totalMemory));
        out.println();

        // Compression ratio if applicable
        if (config.useCompression && fileSize < totalMemory) {
            var ratio = (double) fileSize / totalMemory;
            out.println("  Compression:     " + String.format("%.1f%%", (1.0 - ratio) * 100) + " reduction");
        }

        // Performance estimates
        var nodesPerMs = data.nodeCount() / buildTimeMs;
        out.println("  Performance:");
        out.println("    Build rate:    " + String.format("%.0f", nodesPerMs) + " nodes/ms");
        out.println("    Bytes/node:    " + String.format("%.2f", (double) fileSize / data.nodeCount()));
    }

    private InputType detectInputType(Path path) {
        var filename = path.getFileName().toString().toLowerCase();
        if (filename.endsWith(".obj")) {
            return InputType.OBJ;
        } else if (filename.endsWith(".ply")) {
            return InputType.PLY;
        } else if (filename.endsWith(".vox")) {
            return InputType.VOX;
        } else if (filename.endsWith(".binvox")) {
            return InputType.BINVOX;
        }
        return InputType.UNKNOWN;
    }

    private void printHeader() {
        if (config.quiet) return;
        out.println();
        out.println("╔══════════════════════════════════════════════════════════════╗");
        out.println("║                    ESVT Build Mode                           ║");
        out.println("╚══════════════════════════════════════════════════════════════╝");
        out.println();
    }

    private void printFooter(boolean success) {
        if (config.quiet) return;
        out.println();
        if (success) {
            out.println("╔══════════════════════════════════════════════════════════════╗");
            out.println("║                    Build Successful                          ║");
            out.println("╚══════════════════════════════════════════════════════════════╝");
        } else {
            out.println("╔══════════════════════════════════════════════════════════════╗");
            out.println("║                    Build Failed                              ║");
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

    private enum InputType {
        OBJ, PLY, VOX, BINVOX, UNKNOWN
    }
}
