package com.hellblazer.luciferase.render.demo;

import com.hellblazer.luciferase.render.compression.SparseVoxelCompressor;
import com.hellblazer.luciferase.render.io.VoxelFileFormat;
import com.hellblazer.luciferase.render.io.VoxelStreamingIO;
import com.hellblazer.luciferase.render.rendering.StreamingController;
import com.hellblazer.luciferase.render.rendering.VoxelRenderingPipeline;
import com.hellblazer.luciferase.render.voxel.core.VoxelOctreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple demonstration of the render module's streaming and rendering capabilities.
 */
public class SimpleRenderDemo {
    private static final Logger log = LoggerFactory.getLogger(SimpleRenderDemo.class);
    
    private VoxelRenderingPipeline pipeline;
    private StreamingController streamingController;
    private VoxelStreamingIO streamingIO;
    private VoxelRenderingPipeline.RenderingConfiguration config;
    private Path tempDir;
    private final AtomicLong frameNumber = new AtomicLong(0);
    
    public static void main(String[] args) {
        var demo = new SimpleRenderDemo();
        demo.run();
    }
    
    public void run() {
        printHeader();
        
        try {
            initialize();
            runInteractiveDemo();
        } catch (Exception e) {
            log.error("Demo failed", e);
        } finally {
            cleanup();
        }
    }
    
    private void printHeader() {
        System.out.println("=" .repeat(80));
        System.out.println("LUCIFERASE RENDER MODULE DEMONSTRATION");
        System.out.println("=" .repeat(80));
        System.out.println();
    }
    
    private void initialize() throws Exception {
        log.info("Initializing render systems...");
        
        // Create temp directory for streaming
        tempDir = Files.createTempDirectory("render-demo");
        streamingIO = new VoxelStreamingIO(tempDir);
        streamingIO.open();
        
        // Initialize WebGPU context first
        var webgpuContext = new com.hellblazer.luciferase.render.voxel.gpu.WebGPUContext();
        log.info("Initializing WebGPU context...");
        try {
            webgpuContext.initialize().get(); // Wait for initialization to complete
            log.info("WebGPU context initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize WebGPU context", e);
            throw new RuntimeException("WebGPU initialization failed", e);
        }
        
        // Initialize rendering pipeline
        config = new VoxelRenderingPipeline.RenderingConfiguration();
        config.screenWidth = 1920;
        config.screenHeight = 1080;
        config.initialQualityLevel = 3;
        
        var compressor = new SparseVoxelCompressor();
        
        pipeline = new VoxelRenderingPipeline(webgpuContext, streamingIO, compressor, config);
        pipeline.initialize();
        
        // Initialize streaming controller
        var streamConfig = new StreamingController.StreamingConfig();
        streamConfig.maxConcurrentLoads = 4;
        streamConfig.maxMemoryBytes = 256 * 1024 * 1024; // 256MB
        
        streamingController = new StreamingController(streamingIO, streamConfig);
        streamingController.setOctreeUpdateCallback(new StreamingController.OctreeUpdateCallback() {
            @Override
            public void onNodeUpdated(long nodeId, int newLOD, ByteBuffer data) {
                log.info("Node {} updated to LOD {} ({} bytes)", nodeId, newLOD, data.remaining());
            }
            
            @Override
            public void onNodeEvicted(long nodeId) {
                log.info("Node {} evicted from cache", nodeId);
            }
        });
        
        log.info("Initialization complete!");
        displayStatus();
    }
    
    private void runInteractiveDemo() {
        var scanner = new Scanner(System.in);
        boolean running = true;
        
        while (running) {
            displayMenu();
            var choice = scanner.nextLine().trim().toLowerCase();
            
            switch (choice) {
                case "1" -> demonstrateStreaming();
                case "2" -> demonstrateRendering();
                case "3" -> demonstrateAdaptiveQuality();
                case "4" -> demonstrateCompression();
                case "5" -> showPerformanceMetrics();
                case "6" -> displayStatus();
                case "7" -> runStressTest();
                case "q" -> running = false;
                default -> System.out.println("Invalid choice. Please try again.");
            }
        }
    }
    
    private void displayMenu() {
        System.out.println("\n=== RENDER DEMO MENU ===");
        System.out.println("1. Demonstrate Async Streaming");
        System.out.println("2. Demonstrate Rendering Pipeline");
        System.out.println("3. Demonstrate Adaptive Quality");
        System.out.println("4. Demonstrate Voxel Compression");
        System.out.println("5. Show Performance Metrics");
        System.out.println("6. Display System Status");
        System.out.println("7. Run Stress Test");
        System.out.println("Q. Quit");
        System.out.print("\nEnter choice: ");
    }
    
    private void demonstrateStreaming() {
        System.out.println("\n=== ASYNC STREAMING DEMONSTRATION ===");
        
        try {
            // Create test data
            System.out.println("Creating test voxel data...");
            var testData = createTestVoxelData(128);
            
            // Write test chunks
            System.out.println("Writing test chunks to stream...");
            for (int i = 0; i < 5; i++) {
                streamingIO.writeChunk(ByteBuffer.wrap(testData), i);
            }
            
            // Simulate camera movement and streaming
            float[] cameraPos = {0, 0, 0};
            float[] cameraVel = {1, 0, 0};
            
            System.out.println("\nSimulating camera movement and LOD streaming:");
            for (int frame = 0; frame < 10; frame++) {
                // Update camera
                cameraPos[0] += cameraVel[0];
                streamingController.updateCameraState(cameraPos, cameraVel);
                
                // Request streaming for nearby nodes
                for (int nodeId = 0; nodeId < 3; nodeId++) {
                    float[] nodePos = {nodeId * 10f, 0, 0};
                    int targetLOD = calculateTargetLOD(cameraPos, nodePos);
                    
                    CompletableFuture<StreamingController.StreamingResult> future = 
                        streamingController.requestNodeStreaming(nodeId, nodePos, targetLOD);
                    
                    // Don't wait for result, just log when complete
                    future.thenAccept(result -> {
                        System.out.printf("  Node %d loaded at LOD %d in %.2fms%n", 
                            result.nodeId, result.loadedLOD, 
                            result.loadTimeNanos / 1_000_000.0);
                    });
                }
                
                Thread.sleep(100); // Simulate frame time
                System.out.printf("Frame %d: Camera at [%.1f, %.1f, %.1f]%n", 
                    frame, cameraPos[0], cameraPos[1], cameraPos[2]);
            }
            
            // Wait for pending loads
            Thread.sleep(500);
            
            // Show streaming stats
            var stats = streamingController.getStats();
            System.out.println("\nStreaming Statistics:");
            System.out.printf("  Pending requests: %d%n", stats.pendingRequests);
            System.out.printf("  Active loads: %d%n", stats.activeLoads);
            System.out.printf("  Cached nodes: %d%n", stats.cachedNodes);
            System.out.printf("  Memory used: %.1f MB / %.1f MB%n", 
                stats.memoryUsed / (1024.0 * 1024.0),
                stats.maxMemory / (1024.0 * 1024.0));
            
        } catch (Exception e) {
            log.error("Streaming demonstration failed", e);
        }
    }
    
    private void demonstrateRendering() {
        System.out.println("\n=== RENDERING PIPELINE DEMONSTRATION ===");
        
        try {
            System.out.println("Starting async rendering...");
            
            // Create a test octree
            var testOctree = new VoxelOctreeNode(0, 0, 0, 100);
            pipeline.updateOctreeData(testOctree);
            
            // Start rendering frames
            for (int i = 0; i < 5; i++) {
                long frameStart = System.nanoTime();
                
                // Create rendering state for this frame
                var state = new VoxelRenderingPipeline.RenderingState(
                    createViewMatrix(i * 10f),
                    createProjectionMatrix(),
                    new float[]{0, 0, -5},  // camera position
                    new float[]{0.57f, 0.57f, -0.57f},  // light direction
                    0.2f,  // ambient light
                    3,     // current LOD
                    frameNumber.getAndIncrement()
                );
                
                // Render frame asynchronously
                var frameFuture = pipeline.renderFrame(state);
                
                // Do other work while rendering...
                System.out.printf("Frame %d rendering...%n", i);
                
                // Wait for frame completion with longer timeout
                try {
                    var frame = frameFuture.get(1000, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        long frameTime = (System.nanoTime() - frameStart) / 1_000_000;
                        
                        System.out.printf("  Frame %d complete: %dx%d, %.2fms, quality level %d%n",
                            frame.frameNumber, frame.width, frame.height,
                            frame.renderTimeNanos / 1_000_000.0, frame.qualityLevel);
                    }
                } catch (TimeoutException e) {
                    System.out.println("  Frame " + i + " timed out (this is expected for now)");
                }
                
                Thread.sleep(16); // Simulate 60 FPS timing
            }
            
            // Show performance metrics
            var metrics = pipeline.getPerformanceMetrics();
            System.out.println("\nRendering Performance:");
            System.out.printf("  Total frames: %d%n", metrics.totalFramesRendered);
            System.out.printf("  Average frame time: %.2fms%n", metrics.averageFrameTimeMs);
            System.out.printf("  Current quality level: %d%n", metrics.currentQualityLevel);
            System.out.printf("  Octree updates: %d%n", metrics.octreeUpdates);
            
        } catch (Exception e) {
            log.error("Rendering demonstration failed", e);
        }
    }
    
    private void demonstrateAdaptiveQuality() {
        System.out.println("\n=== ADAPTIVE QUALITY DEMONSTRATION ===");
        
        System.out.println("Current quality level: " + config.initialQualityLevel);
        System.out.println("\nSimulating performance variations:");
        
        try {
            // Test different quality levels
            for (int quality = 1; quality <= 5; quality++) {
                pipeline.setQualityLevel(quality);
                System.out.printf("\nQuality Level %d:%n", quality);
                
                // Render a frame at this quality
                var state = new VoxelRenderingPipeline.RenderingState(
                    createViewMatrix(0),
                    createProjectionMatrix(),
                    new float[]{0, 0, -5},
                    new float[]{0.57f, 0.57f, -0.57f},
                    0.2f,
                    quality,
                    frameNumber.getAndIncrement()
                );
                
                var frameFuture = pipeline.renderFrame(state);
                var frame = frameFuture.get(100, TimeUnit.MILLISECONDS);
                
                if (frame != null) {
                    System.out.printf("  Resolution: %dx%d%n", frame.width, frame.height);
                    System.out.printf("  Render time: %.2fms%n", frame.renderTimeNanos / 1_000_000.0);
                    System.out.printf("  Quality level: %d%n", frame.qualityLevel);
                    System.out.printf("  Expected FPS: %.1f%n", 1000_000_000.0 / frame.renderTimeNanos);
                }
            }
            
            // Enable adaptive quality
            System.out.println("\nEnabling adaptive quality control...");
            pipeline.setAdaptiveQualityEnabled(true);
            config.targetFrameTimeMs = 16; // Target 60 FPS
            
            // Simulate varying load
            for (int i = 0; i < 10; i++) {
                // Add artificial delay to simulate load
                Thread.sleep(i * 5);
                
                var state = new VoxelRenderingPipeline.RenderingState(
                    createViewMatrix(i * 10f),
                    createProjectionMatrix(),
                    new float[]{0, 0, -5},
                    new float[]{0.57f, 0.57f, -0.57f},
                    0.2f,
                    3,
                    frameNumber.getAndIncrement()
                );
                
                var frameFuture = pipeline.renderFrame(state);
                var frame = frameFuture.get(100, TimeUnit.MILLISECONDS);
                
                if (frame != null) {
                    System.out.printf("Frame %d: Quality=%d, Time=%.2fms%n",
                        i, frame.qualityLevel, frame.renderTimeNanos / 1_000_000.0);
                }
            }
            
        } catch (Exception e) {
            log.error("Adaptive quality demonstration failed", e);
        }
    }
    
    private void demonstrateCompression() {
        System.out.println("\n=== VOXEL COMPRESSION DEMONSTRATION ===");
        
        var compressor = new SparseVoxelCompressor();
        int[] sizes = {32, 64, 128};
        
        for (int size : sizes) {
            System.out.printf("\nTesting %d³ voxel grid:%n", size);
            
            // Create test voxel data
            var voxelData = createTestVoxelData(size);
            var octreeNode = createTestOctreeNode(voxelData);
            
            // Compress using the octree
            long startTime = System.nanoTime();
            var compressed = compressor.compressOctree(
                new VoxelOctreeNode(0, 0, 0, size));
            long compressTime = (System.nanoTime() - startTime) / 1_000_000;
            
            // Calculate compression ratio (approximation)
            float ratio = (float)voxelData.length / compressed.length;
            
            System.out.printf("  Original size: %d bytes%n", voxelData.length);
            System.out.printf("  Compressed size: %d bytes%n", compressed.length);
            System.out.printf("  Compression ratio: %.2f:1%n", ratio);
            System.out.printf("  Compression time: %dms%n", compressTime);
        }
    }
    
    private void showPerformanceMetrics() {
        System.out.println("\n=== PERFORMANCE METRICS ===");
        
        var metrics = pipeline.getPerformanceMetrics();
        
        System.out.println("\nFrame Statistics:");
        System.out.printf("  Total frames: %d%n", metrics.totalFramesRendered);
        System.out.printf("  Average frame time: %.2fms%n", metrics.averageFrameTimeMs);
        System.out.printf("  Frame time std dev: %.2fms%n", metrics.frameTimeStdDev);
        System.out.printf("  Current quality level: %d%n", metrics.currentQualityLevel);
        
        System.out.println("\nResource Usage:");
        System.out.printf("  Octree updates: %d%n", metrics.octreeUpdates);
        
        System.out.println("\nStreaming Performance:");
        var streamStats = streamingController.getStats();
        System.out.printf("  Cached nodes: %d%n", streamStats.cachedNodes);
        System.out.printf("  Memory usage: %.1f MB%n", 
            streamStats.memoryUsed / (1024.0 * 1024.0));
        System.out.printf("  Active loads: %d%n", streamStats.activeLoads);
    }
    
    private void displayStatus() {
        System.out.println("\n=== SYSTEM STATUS ===");
        
        System.out.println("\nRendering Pipeline:");
        System.out.println("  Initialized: true");
        System.out.printf("  Resolution: %dx%d%n", 
            config.screenWidth, 
            config.screenHeight);
        System.out.printf("  Quality level: %d%n", config.initialQualityLevel);
        System.out.printf("  Adaptive quality: %s%n", 
            config.enableAdaptiveQuality ? "Enabled" : "Disabled");
        
        System.out.println("\nStreaming System:");
        var streamStats = streamingController.getStats();
        System.out.printf("  Streaming enabled: %s%n", 
            streamingIO.isStreamingEnabled() ? "Yes" : "No");
        System.out.printf("  Pending requests: %d%n", streamStats.pendingRequests);
        System.out.printf("  Active loads: %d%n", streamStats.activeLoads);
        System.out.printf("  Cache size: %d nodes%n", streamStats.cachedNodes);
        System.out.printf("  Memory: %.1f / %.1f MB%n",
            streamStats.memoryUsed / (1024.0 * 1024.0),
            streamStats.maxMemory / (1024.0 * 1024.0));
        
        System.out.println("\nJVM Memory:");
        var runtime = Runtime.getRuntime();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        long usedMem = totalMem - freeMem;
        System.out.printf("  Used: %.1f MB%n", usedMem / (1024.0 * 1024.0));
        System.out.printf("  Free: %.1f MB%n", freeMem / (1024.0 * 1024.0));
        System.out.printf("  Total: %.1f MB%n", totalMem / (1024.0 * 1024.0));
    }
    
    private void runStressTest() {
        System.out.println("\n=== STRESS TEST ===");
        System.out.println("Running 100 frames with maximum load...");
        
        try {
            long totalTime = 0;
            int frameCount = 100;
            
            // Create a test octree for stress testing
            var testOctree = new VoxelOctreeNode(0, 0, 0, 256);
            pipeline.updateOctreeData(testOctree);
            
            for (int i = 0; i < frameCount; i++) {
                long frameStart = System.nanoTime();
                
                // Create rendering state
                var state = new VoxelRenderingPipeline.RenderingState(
                    createViewMatrix(i * 5f),
                    createProjectionMatrix(),
                    new float[]{0, 0, -5},
                    new float[]{0.57f, 0.57f, -0.57f},
                    0.2f,
                    3,
                    frameNumber.getAndIncrement()
                );
                
                // Render frame
                var frameFuture = pipeline.renderFrame(state);
                
                // Request streaming for multiple nodes
                for (int node = 0; node < 10; node++) {
                    float[] pos = {node * 10f, 0, 0};
                    streamingController.requestNodeStreaming(node, pos, 3);
                }
                
                // Wait for frame
                var frame = frameFuture.get(100, TimeUnit.MILLISECONDS);
                
                long frameTime = (System.nanoTime() - frameStart) / 1_000_000;
                totalTime += frameTime;
                
                if ((i + 1) % 10 == 0) {
                    System.out.printf("  %d frames complete (avg %.2fms)%n", 
                        i + 1, (float)totalTime / (i + 1));
                }
            }
            
            System.out.println("\nStress Test Results:");
            System.out.printf("  Total frames: %d%n", frameCount);
            System.out.printf("  Average frame time: %.2fms%n", (float)totalTime / frameCount);
            System.out.printf("  Average FPS: %.1f%n", 1000.0 * frameCount / totalTime);
            
            // Show final metrics
            var metrics = pipeline.getPerformanceMetrics();
            System.out.printf("  Current quality level: %d%n", metrics.currentQualityLevel);
            System.out.printf("  Peak memory: %.1f MB%n", 
                streamingController.getStats().memoryUsed / (1024.0 * 1024.0));
            
        } catch (Exception e) {
            log.error("Stress test failed", e);
        }
    }
    
    private void cleanup() {
        log.info("Cleaning up...");
        
        try {
            // Shutdown components in reverse order of initialization
            if (streamingController != null) {
                try {
                    streamingController.shutdown();
                    log.debug("Streaming controller shut down");
                } catch (Exception e) {
                    log.warn("Error shutting down streaming controller", e);
                }
            }
            
            if (pipeline != null) {
                try {
                    pipeline.close();
                    log.debug("Pipeline closed");
                } catch (Exception e) {
                    log.warn("Error closing pipeline", e);
                }
            }
            
            if (streamingIO != null) {
                try {
                    streamingIO.close();
                    log.debug("Streaming IO closed");
                } catch (Exception e) {
                    log.warn("Error closing streaming IO", e);
                }
            }
            
            // Clean up temp directory after all resources are closed
            if (tempDir != null && Files.exists(tempDir)) {
                try {
                    // Give a small delay to ensure all file handles are released
                    Thread.sleep(100);
                    
                    Files.walk(tempDir)
                        .sorted((a, b) -> -a.compareTo(b))
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (Exception e) {
                                log.debug("Could not delete {}: {}", path, e.getMessage());
                            }
                        });
                    log.debug("Temp directory cleaned up");
                } catch (Exception e) {
                    log.warn("Could not clean up temp directory", e);
                }
            }
            
            log.info("Cleanup completed");
        } catch (Exception e) {
            log.error("Cleanup failed", e);
        }
    }
    
    // Helper methods
    
    private byte[] createTestVoxelData(int size) {
        byte[] data = new byte[size * size * size];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte)(Math.random() * 256);
        }
        return data;
    }
    
    private SparseVoxelCompressor.OctreeNode createTestOctreeNode(byte[] voxelData) {
        var node = new SparseVoxelCompressor.OctreeNode(
            SparseVoxelCompressor.NodeType.INTERNAL, 0);
        node.level = 0;
        node.childMask = 0xFF;
        node.dataValue = 42;  // Example data value
        return node;
    }
    
    private int calculateTargetLOD(float[] cameraPos, float[] nodePos) {
        float dx = cameraPos[0] - nodePos[0];
        float dy = cameraPos[1] - nodePos[1];
        float dz = cameraPos[2] - nodePos[2];
        float distance = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
        
        if (distance < 10) return 4;
        if (distance < 20) return 3;
        if (distance < 40) return 2;
        if (distance < 80) return 1;
        return 0;
    }
    
    private float[] createViewMatrix(float angle) {
        // Simple rotation matrix for demo
        float cos = (float)Math.cos(Math.toRadians(angle));
        float sin = (float)Math.sin(Math.toRadians(angle));
        
        return new float[] {
            cos, 0, sin, 0,
            0, 1, 0, 0,
            -sin, 0, cos, 0,
            0, 0, -5, 1
        };
    }
    
    private float[] createProjectionMatrix() {
        // Simple perspective projection
        return new float[] {
            1.0f, 0, 0, 0,
            0, 1.333f, 0, 0,
            0, 0, -1.02f, -1,
            0, 0, -0.202f, 0
        };
    }
}
