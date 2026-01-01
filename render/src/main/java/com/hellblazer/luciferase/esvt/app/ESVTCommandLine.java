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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Command-line argument parser for ESVT operations.
 *
 * <p>Supports multiple modes of operation:
 * <ul>
 *   <li>BUILD - Build ESVT from mesh, voxels, or Tetree data</li>
 *   <li>INSPECT - Inspect and validate ESVT file structure</li>
 *   <li>OPTIMIZE - Run optimization pipeline on ESVT data</li>
 *   <li>BENCHMARK - Performance benchmarking with ray traversal</li>
 *   <li>INTERACTIVE - Real-time visualization</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTCommandLine {
    private static final Logger log = LoggerFactory.getLogger(ESVTCommandLine.class);

    /**
     * Available operation modes.
     */
    public enum Mode {
        INTERACTIVE("interactive", "Launch interactive visualization mode"),
        BUILD("build", "Build ESVT from mesh or voxel input"),
        INSPECT("inspect", "Inspect and validate ESVT file structure"),
        OPTIMIZE("optimize", "Optimize ESVT structure and memory layout"),
        BENCHMARK("benchmark", "Performance benchmarking with ray traversal"),
        HELP("help", "Show help information");

        private final String command;
        private final String description;

        Mode(String command, String description) {
            this.command = command;
            this.description = description;
        }

        public String getCommand() {
            return command;
        }

        public String getDescription() {
            return description;
        }

        public static Mode fromString(String s) {
            for (var mode : values()) {
                if (mode.command.equalsIgnoreCase(s)) {
                    return mode;
                }
            }
            return null;
        }

        /**
         * Get mode by command name.
         */
        public static Mode fromCommand(String command) {
            return fromString(command);
        }
    }

    /**
     * Configuration holder for all command-line options.
     */
    public static class Config {
        // Mode
        public Mode mode = Mode.HELP;

        // Input/Output files
        public String inputFile;
        public String outputFile;
        public String cameraPathFile;
        public String reportFile;

        // Build options
        public int maxDepth = 12;
        public int gridResolution = 256;
        public boolean buildContours = true;
        public boolean useCompression = false;

        // Display options
        public int frameWidth = 1024;
        public int frameHeight = 768;
        public boolean fullscreen = false;
        public boolean vsync = true;

        // Benchmark options
        public int warmupIterations = 100;
        public int benchmarkIterations = 1000;
        public int numRays = 100000;
        public boolean stressTest = false;

        // Optimization options
        public boolean optimizeMemory = true;
        public boolean optimizeBandwidth = true;
        public boolean optimizeCoalescing = true;
        public int optimizationPasses = 3;

        // Inspect options
        public boolean verbose = false;
        public boolean validateStructure = true;
        public boolean analyzePerformance = true;

        // General options
        public boolean quiet = false;
        public boolean debug = false;

        public boolean isValid() {
            return switch (mode) {
                case BUILD -> inputFile != null && outputFile != null;
                case INSPECT, OPTIMIZE, BENCHMARK, INTERACTIVE -> inputFile != null;
                case HELP -> true;
            };
        }

        public List<String> getValidationErrors() {
            var errors = new ArrayList<String>();

            if (mode == Mode.BUILD) {
                if (inputFile == null) {
                    errors.add("Build mode requires --input file");
                }
                if (outputFile == null) {
                    errors.add("Build mode requires --output file");
                }
                if (inputFile != null && !Files.exists(Path.of(inputFile))) {
                    errors.add("Input file does not exist: " + inputFile);
                }
            } else if (mode != Mode.HELP) {
                if (inputFile == null) {
                    errors.add(mode.getCommand() + " mode requires --input file");
                }
                if (inputFile != null && !Files.exists(Path.of(inputFile))) {
                    errors.add("Input file does not exist: " + inputFile);
                }
            }

            if (maxDepth < 1 || maxDepth > 21) {
                errors.add("Max depth must be between 1 and 21");
            }

            if (gridResolution < 16 || gridResolution > 4096) {
                errors.add("Grid resolution must be between 16 and 4096");
            }

            return errors;
        }

        @Override
        public String toString() {
            return String.format("Config{mode=%s, input=%s, output=%s, maxDepth=%d, grid=%d}",
                    mode, inputFile, outputFile, maxDepth, gridResolution);
        }
    }

    /**
     * Parse command-line arguments into configuration.
     */
    public static Config parse(String[] args) {
        var config = new Config();

        if (args.length == 0) {
            config.mode = Mode.HELP;
            return config;
        }

        // First argument is the mode
        var modeArg = args[0];
        config.mode = Mode.fromString(modeArg);

        if (config.mode == null) {
            // Check if it looks like a flag
            if (modeArg.startsWith("-")) {
                config.mode = Mode.HELP;
            } else {
                log.warn("Unknown mode: {}. Use 'help' for available modes.", modeArg);
                config.mode = Mode.HELP;
                return config;
            }
        }

        // Parse remaining arguments
        for (int i = 1; i < args.length; i++) {
            var arg = args[i];

            switch (arg) {
                case "-i", "--input" -> {
                    if (i + 1 < args.length) {
                        config.inputFile = args[++i];
                    }
                }
                case "-o", "--output" -> {
                    if (i + 1 < args.length) {
                        config.outputFile = args[++i];
                    }
                }
                case "--camera-path" -> {
                    if (i + 1 < args.length) {
                        config.cameraPathFile = args[++i];
                    }
                }
                case "--report" -> {
                    if (i + 1 < args.length) {
                        config.reportFile = args[++i];
                    }
                }
                case "-d", "--depth" -> {
                    if (i + 1 < args.length) {
                        config.maxDepth = Integer.parseInt(args[++i]);
                    }
                }
                case "-g", "--grid" -> {
                    if (i + 1 < args.length) {
                        config.gridResolution = Integer.parseInt(args[++i]);
                    }
                }
                case "-w", "--width" -> {
                    if (i + 1 < args.length) {
                        config.frameWidth = Integer.parseInt(args[++i]);
                    }
                }
                case "-h", "--height" -> {
                    if (i + 1 < args.length) {
                        config.frameHeight = Integer.parseInt(args[++i]);
                    }
                }
                case "--warmup" -> {
                    if (i + 1 < args.length) {
                        config.warmupIterations = Integer.parseInt(args[++i]);
                    }
                }
                case "--iterations" -> {
                    if (i + 1 < args.length) {
                        config.benchmarkIterations = Integer.parseInt(args[++i]);
                    }
                }
                case "--rays" -> {
                    if (i + 1 < args.length) {
                        config.numRays = Integer.parseInt(args[++i]);
                    }
                }
                case "--passes" -> {
                    if (i + 1 < args.length) {
                        config.optimizationPasses = Integer.parseInt(args[++i]);
                    }
                }
                case "--contours" -> config.buildContours = true;
                case "--no-contours" -> config.buildContours = false;
                case "--compress" -> config.useCompression = true;
                case "--no-compress" -> config.useCompression = false;
                case "--fullscreen" -> config.fullscreen = true;
                case "--no-vsync" -> config.vsync = false;
                case "--stress" -> config.stressTest = true;
                case "--memory" -> config.optimizeMemory = true;
                case "--no-memory" -> config.optimizeMemory = false;
                case "--bandwidth" -> config.optimizeBandwidth = true;
                case "--no-bandwidth" -> config.optimizeBandwidth = false;
                case "--coalescing" -> config.optimizeCoalescing = true;
                case "--no-coalescing" -> config.optimizeCoalescing = false;
                case "-v", "--verbose" -> config.verbose = true;
                case "-q", "--quiet" -> config.quiet = true;
                case "--debug" -> config.debug = true;
                case "--validate" -> config.validateStructure = true;
                case "--no-validate" -> config.validateStructure = false;
                case "--analyze" -> config.analyzePerformance = true;
                case "--no-analyze" -> config.analyzePerformance = false;
                case "--help" -> config.mode = Mode.HELP;
                default -> {
                    if (arg.startsWith("-")) {
                        log.warn("Unknown option: {}", arg);
                    }
                }
            }
        }

        return config;
    }

    /**
     * Print usage information.
     */
    public static void printUsage(PrintStream out) {
        out.println("ESVT - Efficient Sparse Voxel Tetrahedra Tool");
        out.println();
        out.println("Usage: esvt <mode> [options]");
        out.println();
        out.println("Modes:");
        for (var mode : Mode.values()) {
            out.printf("  %-12s  %s%n", mode.getCommand(), mode.getDescription());
        }
        out.println();
        out.println("Common Options:");
        out.println("  -i, --input <file>     Input file (mesh, voxels, or .esvt)");
        out.println("  -o, --output <file>    Output file");
        out.println("  -v, --verbose          Enable verbose output");
        out.println("  -q, --quiet            Suppress non-essential output");
        out.println("  --debug                Enable debug logging");
        out.println("  --help                 Show this help message");
        out.println();
        out.println("Build Options:");
        out.println("  -d, --depth <n>        Maximum tree depth (1-21, default: 12)");
        out.println("  -g, --grid <n>         Grid resolution (16-4096, default: 256)");
        out.println("  --contours             Build contour data (default)");
        out.println("  --no-contours          Skip contour data");
        out.println("  --compress             Use compression for output");
        out.println();
        out.println("Display Options:");
        out.println("  -w, --width <n>        Window width (default: 1024)");
        out.println("  -h, --height <n>       Window height (default: 768)");
        out.println("  --fullscreen           Start in fullscreen mode");
        out.println("  --no-vsync             Disable vertical sync");
        out.println();
        out.println("Benchmark Options:");
        out.println("  --warmup <n>           Warmup iterations (default: 100)");
        out.println("  --iterations <n>       Benchmark iterations (default: 1000)");
        out.println("  --rays <n>             Number of rays per iteration (default: 100000)");
        out.println("  --camera-path <file>   Camera path file for benchmarking");
        out.println("  --report <file>        Output report file (CSV)");
        out.println("  --stress               Run stress tests");
        out.println();
        out.println("Optimization Options:");
        out.println("  --passes <n>           Number of optimization passes (default: 3)");
        out.println("  --memory               Enable memory optimization (default)");
        out.println("  --no-memory            Disable memory optimization");
        out.println("  --bandwidth            Enable bandwidth optimization (default)");
        out.println("  --no-bandwidth         Disable bandwidth optimization");
        out.println("  --coalescing           Enable coalescing optimization (default)");
        out.println("  --no-coalescing        Disable coalescing optimization");
        out.println();
        out.println("Inspect Options:");
        out.println("  --validate             Validate structure (default)");
        out.println("  --no-validate          Skip validation");
        out.println("  --analyze              Analyze performance characteristics (default)");
        out.println("  --no-analyze           Skip performance analysis");
        out.println();
        out.println("Examples:");
        out.println("  esvt build -i model.obj -o model.esvt -d 14");
        out.println("  esvt inspect -i model.esvt -v");
        out.println("  esvt optimize -i model.esvt -o optimized.esvt --passes 5");
        out.println("  esvt benchmark -i model.esvt --iterations 5000 --report perf.csv");
        out.println("  esvt interactive -i model.esvt --fullscreen");
    }

    /**
     * Validate configuration and print any errors.
     */
    public static boolean validate(Config config, PrintStream out) {
        var errors = config.getValidationErrors();
        if (!errors.isEmpty()) {
            out.println("Configuration errors:");
            for (var error : errors) {
                out.println("  - " + error);
            }
            out.println();
            out.println("Use 'esvt help' for usage information.");
            return false;
        }
        return true;
    }

    /**
     * Main entry point for CLI.
     */
    public static void main(String[] args) {
        var config = parse(args);

        if (config.mode == Mode.HELP) {
            printUsage(System.out);
            return;
        }

        if (!validate(config, System.err)) {
            System.exit(1);
        }

        if (!config.quiet) {
            log.info("ESVT mode: {}", config.mode);
            log.info("Configuration: {}", config);
        }

        try {
            var exitCode = switch (config.mode) {
                case BUILD -> ESVTBuildMode.run(config);
                case INSPECT -> ESVTInspectMode.run(config);
                case OPTIMIZE -> ESVTOptimizeMode.run(config);
                case BENCHMARK -> ESVTBenchmarkMode.run(config);
                case INTERACTIVE -> ESVTInteractiveMode.run(config);
                case HELP -> {
                    printUsage(System.out);
                    yield 0;
                }
            };

            System.exit(exitCode);

        } catch (Exception e) {
            log.error("Error executing ESVT {}: {}", config.mode, e.getMessage(), e);
            System.exit(1);
        }
    }
}
