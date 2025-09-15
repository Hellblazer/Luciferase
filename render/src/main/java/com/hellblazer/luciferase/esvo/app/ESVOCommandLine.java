package com.hellblazer.luciferase.esvo.app;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

/**
 * ESVO Command Line Interface - Java port of App.hpp operational modes.
 * 
 * Provides the 6 operational modes from the NVIDIA CUDA reference:
 * 1. runInteractive - Interactive GUI mode with real-time rendering
 * 2. runBuild - Build octree from mesh input file 
 * 3. runInspect - Inspect and validate octree file structure
 * 4. runAmbient - Compute ambient occlusion for input mesh
 * 5. runOptimize - Optimize octree structure and memory layout
 * 6. runBenchmark - Performance benchmarking with camera paths
 * 
 * Usage:
 *   java ESVOCommandLine interactive [options]
 *   java ESVOCommandLine build <input> <output> [options]
 *   java ESVOCommandLine inspect <input>
 *   java ESVOCommandLine ambient <input> [options]
 *   java ESVOCommandLine optimize <input> <output> [options]
 *   java ESVOCommandLine benchmark <input> [options]
 */
public class ESVOCommandLine {
    
    public enum Mode {
        INTERACTIVE("interactive", "Launch interactive GUI mode with real-time rendering"),
        BUILD("build", "Build octree from mesh input file"),
        INSPECT("inspect", "Inspect and validate octree file structure"), 
        AMBIENT("ambient", "Compute ambient occlusion for input mesh"),
        OPTIMIZE("optimize", "Optimize octree structure and memory layout"),
        BENCHMARK("benchmark", "Performance benchmarking with camera paths"),
        HELP("help", "Show help information");
        
        private final String command;
        private final String description;
        
        Mode(String command, String description) {
            this.command = command;
            this.description = description;
        }
        
        public String getCommand() { return command; }
        public String getDescription() { return description; }
        
        public static Mode fromString(String command) {
            for (Mode mode : values()) {
                if (mode.command.equals(command)) {
                    return mode;
                }
            }
            return null;
        }
    }
    
    /**
     * Configuration for all operational modes
     */
    public static class Config {
        // Input/Output files
        public String inputFile;
        public String outputFile;
        public String stateFile;
        
        // Interactive mode options
        public int frameWidth = 1024;
        public int frameHeight = 768;
        public int maxThreads = Runtime.getRuntime().availableProcessors();
        
        // Build mode options
        public int numLevels = 10;
        public boolean buildContours = true;
        public float colorError = 0.01f;
        public float normalError = 0.05f;
        public float contourError = 0.001f;
        
        // Ambient mode options
        public float aoRadius = 0.1f;
        public boolean flipNormals = false;
        public int aoSamples = 64;
        
        // Optimize mode options
        public boolean includeMesh = true;
        
        // Benchmark mode options
        public int framesPerLaunch = 100;
        public int warmupLaunches = 10;
        public int measureFrames = 1000;
        public List<String> cameraFiles = new ArrayList<>();
        
        @Override
        public String toString() {
            return String.format("Config[input=%s, output=%s, threads=%d, levels=%d]",
                inputFile, outputFile, maxThreads, numLevels);
        }
    }
    
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }
        
        try {
            Config config = parseArguments(args);
            Mode mode = Mode.fromString(args[0]);
            
            if (mode == null || mode == Mode.HELP) {
                printUsage();
                System.exit(mode == Mode.HELP ? 0 : 1);
            }
            
            System.out.println("ESVO Command Line Interface");
            System.out.println("Mode: " + mode.getDescription());
            System.out.println("Config: " + config);
            System.out.println();
            
            executeMode(mode, config);
            
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            printUsage();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Execution failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static Config parseArguments(String[] args) {
        Config config = new Config();
        Mode mode = Mode.fromString(args[0]);
        
        if (mode == null) {
            throw new IllegalArgumentException("Unknown mode: " + args[0]);
        }
        
        // Parse based on mode requirements
        switch (mode) {
            case INTERACTIVE:
                parseInteractiveArgs(args, config);
                break;
            case BUILD:
                parseBuildArgs(args, config);
                break;
            case INSPECT:
                parseInspectArgs(args, config);
                break;
            case AMBIENT:
                parseAmbientArgs(args, config);
                break;
            case OPTIMIZE:
                parseOptimizeArgs(args, config);
                break;
            case BENCHMARK:
                parseBenchmarkArgs(args, config);
                break;
        }
        
        return config;
    }
    
    private static void parseInteractiveArgs(String[] args, Config config) {
        // interactive [--state <file>] [--input <file>] [--size <width>x<height>] [--threads <n>]
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--state":
                    config.stateFile = getArgValue(args, i++);
                    break;
                case "--input":
                    config.inputFile = getArgValue(args, i++);
                    break;
                case "--size":
                    parseFrameSize(getArgValue(args, i++), config);
                    break;
                case "--threads":
                    config.maxThreads = Integer.parseInt(getArgValue(args, i++));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown interactive option: " + args[i]);
            }
        }
    }
    
    private static void parseBuildArgs(String[] args, Config config) {
        // build <input> <output> [--levels <n>] [--no-contours] [--color-error <f>] [--normal-error <f>] [--contour-error <f>] [--threads <n>]
        if (args.length < 3) {
            throw new IllegalArgumentException("Build mode requires input and output files");
        }
        
        config.inputFile = args[1];
        config.outputFile = args[2];
        
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--levels":
                    config.numLevels = Integer.parseInt(getArgValue(args, i++));
                    break;
                case "--no-contours":
                    config.buildContours = false;
                    break;
                case "--color-error":
                    config.colorError = Float.parseFloat(getArgValue(args, i++));
                    break;
                case "--normal-error":
                    config.normalError = Float.parseFloat(getArgValue(args, i++));
                    break;
                case "--contour-error":
                    config.contourError = Float.parseFloat(getArgValue(args, i++));
                    break;
                case "--threads":
                    config.maxThreads = Integer.parseInt(getArgValue(args, i++));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown build option: " + args[i]);
            }
        }
    }
    
    private static void parseInspectArgs(String[] args, Config config) {
        // inspect <input>
        if (args.length < 2) {
            throw new IllegalArgumentException("Inspect mode requires input file");
        }
        config.inputFile = args[1];
    }
    
    private static void parseAmbientArgs(String[] args, Config config) {
        // ambient <input> [--radius <f>] [--flip-normals]
        if (args.length < 2) {
            throw new IllegalArgumentException("Ambient mode requires input file");
        }
        
        config.inputFile = args[1];
        
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--radius":
                    config.aoRadius = Float.parseFloat(getArgValue(args, i++));
                    break;
                case "--flip-normals":
                    config.flipNormals = true;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown ambient option: " + args[i]);
            }
        }
    }
    
    private static void parseOptimizeArgs(String[] args, Config config) {
        // optimize <input> <output> [--levels <n>] [--no-mesh]
        if (args.length < 3) {
            throw new IllegalArgumentException("Optimize mode requires input and output files");
        }
        
        config.inputFile = args[1];
        config.outputFile = args[2];
        
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--levels":
                    config.numLevels = Integer.parseInt(getArgValue(args, i++));
                    break;
                case "--no-mesh":
                    config.includeMesh = false;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown optimize option: " + args[i]);
            }
        }
    }
    
    private static void parseBenchmarkArgs(String[] args, Config config) {
        // benchmark <input> [--levels <n>] [--size <width>x<height>] [--frames <n>] [--warmup <n>] [--measure <n>] [--cameras <file1,file2,...>]
        if (args.length < 2) {
            throw new IllegalArgumentException("Benchmark mode requires input file");
        }
        
        config.inputFile = args[1];
        
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--levels":
                    config.numLevels = Integer.parseInt(getArgValue(args, i++));
                    break;
                case "--size":
                    parseFrameSize(getArgValue(args, i++), config);
                    break;
                case "--frames":
                    config.framesPerLaunch = Integer.parseInt(getArgValue(args, i++));
                    break;
                case "--warmup":
                    config.warmupLaunches = Integer.parseInt(getArgValue(args, i++));
                    break;
                case "--measure":
                    config.measureFrames = Integer.parseInt(getArgValue(args, i++));
                    break;
                case "--cameras":
                    String cameras = getArgValue(args, i++);
                    config.cameraFiles.addAll(Arrays.asList(cameras.split(",")));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown benchmark option: " + args[i]);
            }
        }
    }
    
    private static String getArgValue(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for argument: " + args[index - 1]);
        }
        return args[index];
    }
    
    private static void parseFrameSize(String sizeStr, Config config) {
        String[] parts = sizeStr.split("x");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid frame size format. Use: widthxheight");
        }
        config.frameWidth = Integer.parseInt(parts[0]);
        config.frameHeight = Integer.parseInt(parts[1]);
    }
    
    private static void executeMode(Mode mode, Config config) {
        switch (mode) {
            case INTERACTIVE:
                ESVOInteractiveMode.runInteractive(config);
                break;
            case BUILD:
                ESVOBuildMode.runBuild(config);
                break;
            case INSPECT:
                ESVOInspectMode.runInspect(config);
                break;
            case AMBIENT:
                ESVOAmbientMode.runAmbient(config);
                break;
            case OPTIMIZE:
                ESVOOptimizeMode.runOptimize(config);
                break;
            case BENCHMARK:
                ESVOBenchmarkMode.runBenchmark(config);
                break;
        }
    }
    
    private static void printUsage() {
        System.out.println("ESVO Command Line Interface");
        System.out.println("Usage: java ESVOCommandLine <mode> [options]");
        System.out.println();
        System.out.println("Modes:");
        
        for (Mode mode : Mode.values()) {
            if (mode != Mode.HELP) {
                System.out.printf("  %-12s %s%n", mode.getCommand(), mode.getDescription());
            }
        }
        
        System.out.println();
        System.out.println("Mode-specific options:");
        System.out.println();
        
        System.out.println("interactive [options]");
        System.out.println("  --state <file>       Load state from file");
        System.out.println("  --input <file>       Input octree file");
        System.out.println("  --size <W>x<H>       Frame size (default: 1024x768)");
        System.out.println("  --threads <n>        Max threads (default: CPU cores)");
        System.out.println();
        
        System.out.println("build <input> <output> [options]");
        System.out.println("  --levels <n>         Octree levels (default: 10)");
        System.out.println("  --no-contours        Disable contour generation");
        System.out.println("  --color-error <f>    Color error threshold (default: 0.01)");
        System.out.println("  --normal-error <f>   Normal error threshold (default: 0.05)");
        System.out.println("  --contour-error <f>  Contour error threshold (default: 0.001)");
        System.out.println("  --threads <n>        Max threads (default: CPU cores)");
        System.out.println();
        
        System.out.println("inspect <input>");
        System.out.println("  No additional options");
        System.out.println();
        
        System.out.println("ambient <input> [options]");
        System.out.println("  --radius <f>         AO radius (default: 0.1)");
        System.out.println("  --flip-normals       Flip surface normals");
        System.out.println();
        
        System.out.println("optimize <input> <output> [options]");
        System.out.println("  --levels <n>         Target octree levels (default: 10)");
        System.out.println("  --no-mesh            Exclude original mesh data");
        System.out.println();
        
        System.out.println("benchmark <input> [options]");
        System.out.println("  --levels <n>         Octree levels (default: 10)");
        System.out.println("  --size <W>x<H>       Frame size (default: 1024x768)");
        System.out.println("  --frames <n>         Frames per launch (default: 100)");
        System.out.println("  --warmup <n>         Warmup launches (default: 10)");
        System.out.println("  --measure <n>        Measurement frames (default: 1000)");
        System.out.println("  --cameras <files>    Camera path files (comma-separated)");
        System.out.println();
        
        System.out.println("Examples:");
        System.out.println("  java ESVOCommandLine interactive --input scene.octree");
        System.out.println("  java ESVOCommandLine build model.obj scene.octree --levels 12");
        System.out.println("  java ESVOCommandLine inspect scene.octree");
        System.out.println("  java ESVOCommandLine benchmark scene.octree --size 1920x1080");
    }
}