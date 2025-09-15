package com.hellblazer.luciferase.esvo.app;

import com.hellblazer.luciferase.esvo.core.ESVONode;
import com.hellblazer.luciferase.esvo.core.ESVORay;
import com.hellblazer.luciferase.esvo.core.ESVOTraversal;
import com.hellblazer.luciferase.esvo.core.ESVOResult;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ESVO Benchmark Mode - Performance benchmarking with camera paths.
 * 
 * This is the Java port of the runBenchmark() function from App.hpp:
 * void runBenchmark(const String& inFile, int numLevels, const Vec2i& frameSize, 
 *                   int framesPerLaunch, int warmupLaunches, int measureFrames, 
 *                   const Array<String>& cameras);
 * 
 * Functionality:
 * - Load octree and camera path files
 * - Execute warmup iterations to stabilize performance
 * - Measure ray casting performance across multiple camera positions
 * - Generate detailed performance statistics and reports
 * - Test various ray patterns (coherent, random, worst-case)
 * - Validate performance against expected benchmarks
 * - Output results in machine-readable format for analysis
 */
public class ESVOBenchmarkMode {
    
    private static final int DEFAULT_RAYS_PER_PIXEL = 1;
    private static final float RAY_EPSILON = 1e-6f;
    
    public static void runBenchmark(ESVOCommandLine.Config config) {
        System.out.println("=== ESVO Benchmark Mode ===");
        System.out.println("Input file: " + config.inputFile);
        System.out.println("Frame size: " + config.frameWidth + "x" + config.frameHeight);
        System.out.println("Frames per launch: " + config.framesPerLaunch);
        System.out.println("Warmup launches: " + config.warmupLaunches);
        System.out.println("Measurement frames: " + config.measureFrames);
        System.out.println("Camera files: " + config.cameraFiles.size());
        System.out.println();
        
        validateInputs(config);
        
        try {
            // Phase 1: Load octree
            System.out.println("Phase 1: Loading octree...");
            var octreeNodes = loadOctree(config.inputFile);
            System.out.printf("Loaded octree: %,d nodes%n", octreeNodes.length);
            
            // Phase 2: Load camera paths
            System.out.println("\nPhase 2: Loading camera paths...");
            var cameraPaths = loadCameraPaths(config.cameraFiles, config);
            System.out.printf("Loaded %d camera paths with %d total positions%n", 
                cameraPaths.size(), getTotalCameraPositions(cameraPaths));
            
            // Phase 3: Initialize benchmark framework
            System.out.println("\nPhase 3: Initializing benchmark framework...");
            var benchmark = new BenchmarkFramework(config, octreeNodes);
            
            // Phase 4: Warmup phase
            System.out.println("\nPhase 4: Warmup phase...");
            executeWarmup(benchmark, cameraPaths, config);
            
            // Phase 5: Primary benchmark
            System.out.println("\nPhase 5: Primary benchmark...");
            var primaryResults = executePrimaryBenchmark(benchmark, cameraPaths, config);
            
            // Phase 6: Stress tests
            System.out.println("\nPhase 6: Stress tests...");
            var stressResults = executeStressTests(benchmark, octreeNodes, config);
            
            // Phase 7: Performance analysis
            System.out.println("\nPhase 7: Performance analysis...");
            analyzePerformance(primaryResults, stressResults, config);
            
            // Phase 8: Generate report
            System.out.println("\nPhase 8: Generating report...");
            generateBenchmarkReport(primaryResults, stressResults, config);
            
            System.out.println("\n✓ Benchmark completed successfully!");
            
        } catch (Exception e) {
            System.err.println("\n✗ Benchmark failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void validateInputs(ESVOCommandLine.Config config) {
        var inputFile = new File(config.inputFile);
        if (!inputFile.exists()) {
            throw new IllegalArgumentException("Input file does not exist: " + config.inputFile);
        }
        
        if (config.frameWidth <= 0 || config.frameHeight <= 0) {
            throw new IllegalArgumentException("Invalid frame size: " + config.frameWidth + "x" + config.frameHeight);
        }
        
        if (config.framesPerLaunch <= 0) {
            throw new IllegalArgumentException("Frames per launch must be positive: " + config.framesPerLaunch);
        }
        
        if (config.warmupLaunches < 0) {
            throw new IllegalArgumentException("Warmup launches cannot be negative: " + config.warmupLaunches);
        }
        
        if (config.measureFrames <= 0) {
            throw new IllegalArgumentException("Measurement frames must be positive: " + config.measureFrames);
        }
        
        for (String cameraFile : config.cameraFiles) {
            if (!new File(cameraFile).exists()) {
                throw new IllegalArgumentException("Camera file does not exist: " + cameraFile);
            }
        }
    }
    
    private static ESVONode[] loadOctree(String inputFile) throws IOException {
        var reader = new OctreeReader();
        return reader.readOctree(inputFile);
    }
    
    private static List<CameraPath> loadCameraPaths(List<String> cameraFiles, ESVOCommandLine.Config config) 
            throws IOException {
        var paths = new ArrayList<CameraPath>();
        var loader = new CameraPathLoader();
        
        if (cameraFiles.isEmpty()) {
            System.out.println("No camera files specified, generating default camera path...");
            paths.add(generateDefaultCameraPath(config));
        } else {
            for (String cameraFile : cameraFiles) {
                System.out.printf("Loading camera path: %s%n", cameraFile);
                var path = loader.loadCameraPath(cameraFile);
                paths.add(path);
            }
        }
        
        return paths;
    }
    
    private static CameraPath generateDefaultCameraPath(ESVOCommandLine.Config config) {
        // Generate a default circular camera path around the octree
        var positions = new ArrayList<CameraPosition>();
        
        int numPositions = Math.max(10, config.measureFrames / 10);
        float radius = 3.0f;
        float height = 1.5f;
        
        for (int i = 0; i < numPositions; i++) {
            float angle = (float)(2.0 * Math.PI * i / numPositions);
            float x = radius * (float)Math.cos(angle);
            float z = radius * (float)Math.sin(angle);
            float y = height;
            
            // Look towards center
            float dirX = -x / radius;
            float dirY = -0.2f;
            float dirZ = -z / radius;
            
            positions.add(new CameraPosition(x, y, z, dirX, dirY, dirZ));
        }
        
        return new CameraPath("default", positions);
    }
    
    private static int getTotalCameraPositions(List<CameraPath> cameraPaths) {
        return cameraPaths.stream().mapToInt(path -> path.positions.size()).sum();
    }
    
    private static void executeWarmup(BenchmarkFramework benchmark, List<CameraPath> cameraPaths, 
                                    ESVOCommandLine.Config config) {
        System.out.printf("Executing %d warmup launches...%n", config.warmupLaunches);
        
        var startTime = Instant.now();
        
        for (int launch = 0; launch < config.warmupLaunches; launch++) {
            System.out.printf("  Warmup launch %d/%d%n", launch + 1, config.warmupLaunches);
            
            // Use random camera positions for warmup
            for (var cameraPath : cameraPaths) {
                for (int frame = 0; frame < Math.min(config.framesPerLaunch, cameraPath.positions.size()); frame++) {
                    var position = cameraPath.positions.get(frame % cameraPath.positions.size());
                    benchmark.renderFrame(position, config.frameWidth, config.frameHeight);
                }
            }
        }
        
        var warmupTime = Duration.between(startTime, Instant.now());
        System.out.printf("Warmup completed in %d.%03ds%n", 
            warmupTime.toSeconds(), warmupTime.toMillis() % 1000);
    }
    
    private static BenchmarkResults executePrimaryBenchmark(BenchmarkFramework benchmark, 
                                                          List<CameraPath> cameraPaths, 
                                                          ESVOCommandLine.Config config) {
        System.out.printf("Executing primary benchmark (%d measurement frames)...%n", config.measureFrames);
        
        var results = new BenchmarkResults();
        var startTime = Instant.now();
        
        int framesRendered = 0;
        int totalRays = 0;
        long totalTraversalTime = 0;
        
        while (framesRendered < config.measureFrames) {
            for (var cameraPath : cameraPaths) {
                if (framesRendered >= config.measureFrames) break;
                
                for (var position : cameraPath.positions) {
                    if (framesRendered >= config.measureFrames) break;
                    
                    var frameStartTime = Instant.now();
                    var frameMetrics = benchmark.renderFrame(position, config.frameWidth, config.frameHeight);
                    var frameTime = Duration.between(frameStartTime, Instant.now());
                    
                    // Accumulate statistics
                    framesRendered++;
                    totalRays += frameMetrics.raysCast;
                    totalTraversalTime += frameTime.toNanos();
                    
                    results.frameMetrics.add(frameMetrics);
                    
                    if (framesRendered % 100 == 0) {
                        System.out.printf("  Progress: %d/%d frames (%.1f%%)%n", 
                            framesRendered, config.measureFrames, 
                            100.0 * framesRendered / config.measureFrames);
                    }
                }
            }
        }
        
        var totalTime = Duration.between(startTime, Instant.now());
        
        // Calculate summary statistics
        results.totalFrames = framesRendered;
        results.totalRays = totalRays;
        results.totalTime = totalTime;
        results.averageFrameTime = totalTraversalTime / (double)framesRendered / 1_000_000.0; // ms
        results.averageRayThroughput = totalRays / (totalTime.toMillis() / 1000.0); // rays/sec
        results.averageFPS = framesRendered / (totalTime.toMillis() / 1000.0);
        
        System.out.printf("Primary benchmark completed: %,d frames, %,d rays in %d.%03ds%n", 
            framesRendered, totalRays, totalTime.toSeconds(), totalTime.toMillis() % 1000);
        System.out.printf("Average performance: %.2f FPS, %,.0f rays/sec%n", 
            results.averageFPS, results.averageRayThroughput);
        
        return results;
    }
    
    private static StressTestResults executeStressTests(BenchmarkFramework benchmark, ESVONode[] octreeNodes, 
                                                      ESVOCommandLine.Config config) {
        var results = new StressTestResults();
        
        // Test 1: Random ray stress test
        System.out.println("  Test 1: Random ray stress test...");
        results.randomRayTest = executeRandomRayTest(octreeNodes, 100000);
        
        // Test 2: Coherent ray stress test
        System.out.println("  Test 2: Coherent ray stress test...");
        results.coherentRayTest = executeCoherentRayTest(octreeNodes, 100000);
        
        // Test 3: Worst-case ray test
        System.out.println("  Test 3: Worst-case ray test...");
        results.worstCaseTest = executeWorstCaseTest(octreeNodes, 50000);
        
        // Test 4: Memory bandwidth test
        System.out.println("  Test 4: Memory bandwidth test...");
        results.memoryBandwidthTest = executeMemoryBandwidthTest(octreeNodes);
        
        return results;
    }
    
    private static StressTestResult executeRandomRayTest(ESVONode[] octreeNodes, int numRays) {
        var random = ThreadLocalRandom.current();
        var startTime = Instant.now();
        
        int hits = 0;
        long totalIterations = 0;
        
        for (int i = 0; i < numRays; i++) {
            // Generate random ray
            var ray = new ESVORay(
                random.nextFloat() * 2.0f + 0.5f, // Origin in [0.5, 2.5]
                random.nextFloat() * 2.0f + 0.5f,
                random.nextFloat() * 2.0f + 0.5f,
                random.nextFloat() * 2.0f - 1.0f, // Direction in [-1, 1]
                random.nextFloat() * 2.0f - 1.0f,
                random.nextFloat() * 2.0f - 1.0f
            );
            
            var result = ESVOTraversal.castRay(ray, octreeNodes, 0);
            if (result.hit) {
                hits++;
            }
            totalIterations += result.iterations;
        }
        
        var duration = Duration.between(startTime, Instant.now());
        
        return new StressTestResult(
            "Random Ray Test",
            numRays,
            duration,
            hits,
            (double)totalIterations / numRays,
            numRays / (duration.toMillis() / 1000.0)
        );
    }
    
    private static StressTestResult executeCoherentRayTest(ESVONode[] octreeNodes, int numRays) {
        var startTime = Instant.now();
        
        int hits = 0;
        long totalIterations = 0;
        
        // Generate coherent rays (parallel rays in a grid)
        int gridSize = (int)Math.sqrt(numRays);
        float step = 2.0f / gridSize;
        
        int rayCount = 0;
        for (int x = 0; x < gridSize && rayCount < numRays; x++) {
            for (int y = 0; y < gridSize && rayCount < numRays; y++) {
                var ray = new ESVORay(
                    x * step + 0.5f, // Grid origin
                    y * step + 0.5f,
                    0.0f,
                    0.0f, // Parallel direction
                    0.0f,
                    1.0f
                );
                
                var result = ESVOTraversal.castRay(ray, octreeNodes, 0);
                if (result.hit) {
                    hits++;
                }
                totalIterations += result.iterations;
                rayCount++;
            }
        }
        
        var duration = Duration.between(startTime, Instant.now());
        
        return new StressTestResult(
            "Coherent Ray Test",
            rayCount,
            duration,
            hits,
            (double)totalIterations / rayCount,
            rayCount / (duration.toMillis() / 1000.0)
        );
    }
    
    private static StressTestResult executeWorstCaseTest(ESVONode[] octreeNodes, int numRays) {
        var startTime = Instant.now();
        
        int hits = 0;
        long totalIterations = 0;
        
        // Generate worst-case rays (grazing rays that traverse many voxels)
        for (int i = 0; i < numRays; i++) {
            var ray = new ESVORay(
                0.1f, 1.5f, 1.5f, // Start outside
                0.999f, 0.001f, 0.001f // Nearly horizontal
            );
            
            var result = ESVOTraversal.castRay(ray, octreeNodes, 0);
            if (result.hit) {
                hits++;
            }
            totalIterations += result.iterations;
        }
        
        var duration = Duration.between(startTime, Instant.now());
        
        return new StressTestResult(
            "Worst Case Test",
            numRays,
            duration,
            hits,
            (double)totalIterations / numRays,
            numRays / (duration.toMillis() / 1000.0)
        );
    }
    
    private static StressTestResult executeMemoryBandwidthTest(ESVONode[] octreeNodes) {
        var startTime = Instant.now();
        
        long totalReads = 0;
        
        // Sequential memory access pattern
        for (int pass = 0; pass < 10; pass++) {
            for (var node : octreeNodes) {
                // Simulate memory reads
                int validMask = node.getValidMask();
                int nonLeafMask = node.getNonLeafMask();
                int childPointer = node.getChildPointer();
                totalReads += 3; // Simulate 3 memory reads per node
            }
        }
        
        var duration = Duration.between(startTime, Instant.now());
        double bandwidth = (totalReads * 4.0) / (1024.0 * 1024.0) / (duration.toMillis() / 1000.0); // MB/s
        
        return new StressTestResult(
            "Memory Bandwidth Test",
            (int)totalReads,
            duration,
            0, // Not applicable
            0.0, // Not applicable
            bandwidth
        );
    }
    
    private static void analyzePerformance(BenchmarkResults primaryResults, StressTestResults stressResults, 
                                         ESVOCommandLine.Config config) {
        System.out.println("=== Performance Analysis ===");
        
        // Frame rate analysis
        System.out.printf("Frame Rate Performance:%n");
        System.out.printf("  Average FPS: %.2f%n", primaryResults.averageFPS);
        System.out.printf("  Frame time: %.3f ms%n", primaryResults.averageFrameTime);
        
        // Ray throughput analysis
        System.out.printf("Ray Throughput Performance:%n");
        System.out.printf("  Total rays: %,d%n", primaryResults.totalRays);
        System.out.printf("  Average throughput: %,.0f rays/sec%n", primaryResults.averageRayThroughput);
        
        // Stress test comparison
        System.out.printf("Stress Test Comparison:%n");
        System.out.printf("  Random rays: %,.0f rays/sec%n", stressResults.randomRayTest.raysPerSecond);
        System.out.printf("  Coherent rays: %,.0f rays/sec%n", stressResults.coherentRayTest.raysPerSecond);
        System.out.printf("  Worst-case rays: %,.0f rays/sec%n", stressResults.worstCaseTest.raysPerSecond);
        
        // Performance rating
        double performanceScore = calculatePerformanceScore(primaryResults, stressResults);
        System.out.printf("Overall Performance Score: %.1f/100 (%s)%n", 
            performanceScore, getPerformanceRating(performanceScore));
    }
    
    private static void generateBenchmarkReport(BenchmarkResults primaryResults, StressTestResults stressResults, 
                                              ESVOCommandLine.Config config) {
        System.out.println("=== Benchmark Report ===");
        
        // Configuration summary
        System.out.printf("Configuration: %dx%d, %d frames%n", 
            config.frameWidth, config.frameHeight, config.measureFrames);
        
        // Primary results
        System.out.printf("Primary Benchmark Results:%n");
        System.out.printf("  Duration: %d.%03ds%n", 
            primaryResults.totalTime.toSeconds(), primaryResults.totalTime.toMillis() % 1000);
        System.out.printf("  Frames: %,d%n", primaryResults.totalFrames);
        System.out.printf("  Rays: %,d%n", primaryResults.totalRays);
        System.out.printf("  Average FPS: %.2f%n", primaryResults.averageFPS);
        System.out.printf("  Average rays/sec: %,.0f%n", primaryResults.averageRayThroughput);
        
        // CSV output for analysis
        System.out.println("\nCSV Data (for spreadsheet analysis):");
        System.out.println("Metric,Value");
        System.out.printf("FPS,%.2f%n", primaryResults.averageFPS);
        System.out.printf("RaysPerSecond,%.0f%n", primaryResults.averageRayThroughput);
        System.out.printf("FrameTimeMs,%.3f%n", primaryResults.averageFrameTime);
        System.out.printf("RandomRayThroughput,%.0f%n", stressResults.randomRayTest.raysPerSecond);
        System.out.printf("CoherentRayThroughput,%.0f%n", stressResults.coherentRayTest.raysPerSecond);
        System.out.printf("WorstCaseRayThroughput,%.0f%n", stressResults.worstCaseTest.raysPerSecond);
    }
    
    private static double calculatePerformanceScore(BenchmarkResults primaryResults, StressTestResults stressResults) {
        double score = 0.0;
        
        // FPS contribution (0-40 points)
        score += Math.min(40.0, primaryResults.averageFPS * 2.0);
        
        // Ray throughput contribution (0-30 points)  
        score += Math.min(30.0, primaryResults.averageRayThroughput / 100000.0 * 30.0);
        
        // Consistency contribution (0-30 points)
        double coherentRatio = stressResults.coherentRayTest.raysPerSecond / stressResults.randomRayTest.raysPerSecond;
        score += Math.min(30.0, coherentRatio * 15.0);
        
        return Math.min(100.0, score);
    }
    
    private static String getPerformanceRating(double score) {
        if (score >= 80) return "Excellent";
        if (score >= 60) return "Good";  
        if (score >= 40) return "Fair";
        return "Poor";
    }
    
    // Data structures
    private static class CameraPosition {
        final float x, y, z;
        final float dirX, dirY, dirZ;
        
        CameraPosition(float x, float y, float z, float dirX, float dirY, float dirZ) {
            this.x = x; this.y = y; this.z = z;
            this.dirX = dirX; this.dirY = dirY; this.dirZ = dirZ;
        }
    }
    
    private static class CameraPath {
        final String name;
        final List<CameraPosition> positions;
        
        CameraPath(String name, List<CameraPosition> positions) {
            this.name = name;
            this.positions = positions;
        }
    }
    
    private static class BenchmarkResults {
        int totalFrames;
        int totalRays;
        Duration totalTime;
        double averageFrameTime;
        double averageRayThroughput;
        double averageFPS;
        List<FrameMetrics> frameMetrics = new ArrayList<>();
    }
    
    private static class StressTestResults {
        StressTestResult randomRayTest;
        StressTestResult coherentRayTest;
        StressTestResult worstCaseTest;
        StressTestResult memoryBandwidthTest;
    }
    
    private static class StressTestResult {
        final String name;
        final int numRays;
        final Duration duration;
        final int hits;
        final double avgIterations;
        final double raysPerSecond;
        
        StressTestResult(String name, int numRays, Duration duration, int hits, 
                        double avgIterations, double raysPerSecond) {
            this.name = name;
            this.numRays = numRays;
            this.duration = duration;
            this.hits = hits;
            this.avgIterations = avgIterations;
            this.raysPerSecond = raysPerSecond;
        }
    }
    
    private static ESVONode[] convertFromOctreeData(com.hellblazer.luciferase.esvo.core.ESVOOctreeData octreeData) {
        int nodeCount = octreeData.getNodeCount();
        var nodes = new ESVONode[nodeCount];
        
        int[] indices = octreeData.getNodeIndices();
        for (int i = 0; i < indices.length; i++) {
            var octreeNode = octreeData.getNode(indices[i]);
            if (octreeNode != null) {
                // Convert ESVOOctreeNode to ESVONode
                var esvoNode = new ESVONode();
                esvoNode.setNonLeafMask(octreeNode.childMask & 0xFF);
                esvoNode.setContourMask(octreeNode.contour);
                esvoNode.setChildPointer(octreeNode.farPointer);
                nodes[i] = esvoNode;
            } else {
                nodes[i] = new ESVONode(); // Create empty node
            }
        }
        
        System.out.printf("Converted octree data to %d ESVONodes for benchmarking%n", nodeCount);
        return nodes;
    }
    
    // Placeholder classes that would be implemented elsewhere
    private static class OctreeReader {
        ESVONode[] readOctree(String filename) throws IOException {
            // Use real deserializer to read octree file
            var deserializer = new com.hellblazer.luciferase.esvo.io.ESVODeserializer();
            var octreeData = deserializer.deserialize(new File(filename).toPath());
            
            // Convert ESVOOctreeData to ESVONode array
            return convertFromOctreeData(octreeData);
        }
    }
    
    private static class CameraPathLoader {
        CameraPath loadCameraPath(String filename) throws IOException {
            // TODO: Implement actual camera path loading
            // Mock camera path with some test positions
            var positions = new ArrayList<CameraPosition>();
            for (int i = 0; i < 100; i++) {
                positions.add(new CameraPosition(
                    i * 0.1f, i * 0.05f, 5.0f,  // x, y, z
                    0.0f, 0.0f, -1.0f           // direction
                ));
            }
            System.out.printf("Mock loaded camera path with %d positions%n", positions.size());
            return new CameraPath("mock_path", positions);
        }
    }
    
    private static class BenchmarkFramework {
        private final ESVOCommandLine.Config config;
        private final ESVONode[] nodes;
        
        BenchmarkFramework(ESVOCommandLine.Config config, ESVONode[] nodes) {
            this.config = config;
            this.nodes = nodes;
        }
        
        FrameMetrics renderFrame(CameraPosition position, int width, int height) {
            var metrics = new FrameMetrics();
            metrics.raysCast = width * height;
            
            long startTime = System.nanoTime();
            int hits = 0;
            
            // Generate rays for each pixel and perform traversal
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // Generate ray from camera position through pixel
                    var ray = generateRayThroughPixel(position, x, y, width, height);
                    
                    // Perform ray traversal on octree using ESVOTraversal
                    var result = ESVOTraversal.castRay(ray, nodes, 0);
                    
                    if (result.hit) {
                        hits++;
                    }
                }
            }
            
            metrics.traversalTime = System.nanoTime() - startTime;
            metrics.hits = hits;
            
            return metrics;
        }
        
        private ESVORay generateRayThroughPixel(CameraPosition position, int x, int y, int width, int height) {
            // Convert pixel coordinates to normalized device coordinates [-1, 1]
            float ndcX = (2.0f * x) / width - 1.0f;
            float ndcY = 1.0f - (2.0f * y) / height;
            
            // Simple perspective projection (assuming 45-degree FOV)
            float aspect = (float) width / height;
            float fov = (float) Math.toRadians(45.0);
            float tanHalfFov = (float) Math.tan(fov / 2.0);
            
            // Ray direction in camera space
            float rayDirX = ndcX * aspect * tanHalfFov;
            float rayDirY = ndcY * tanHalfFov;
            float rayDirZ = -1.0f; // Looking down negative Z
            
            // Normalize ray direction
            float length = (float) Math.sqrt(rayDirX * rayDirX + rayDirY * rayDirY + rayDirZ * rayDirZ);
            rayDirX /= length;
            rayDirY /= length;
            rayDirZ /= length;
            
            // Transform ray to ESVO coordinate space [1, 2]
            // Camera position is already provided, so use it directly but ensure it's in [1,2] space
            float originX = Math.max(1.0f, Math.min(2.0f, 1.5f + position.x * 0.1f));
            float originY = Math.max(1.0f, Math.min(2.0f, 1.5f + position.y * 0.1f));
            float originZ = Math.max(1.0f, Math.min(2.0f, 1.5f + position.z * 0.1f));
            
            return new ESVORay(originX, originY, originZ, rayDirX, rayDirY, rayDirZ);
        }
    }
    
    private static class FrameMetrics {
        int raysCast;
        int hits;
        long traversalTime;
    }
}