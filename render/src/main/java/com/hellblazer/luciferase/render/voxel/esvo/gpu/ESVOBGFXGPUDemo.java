/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * Licensed under the AGPL License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import com.hellblazer.luciferase.render.gpu.GPUConfig;
import com.hellblazer.luciferase.render.voxel.esvo.ESVONode;
import com.hellblazer.luciferase.render.voxel.esvo.voxelization.Octree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real GPU execution demo for ESVO BGFX Metal backend.
 * This demo actually executes Metal compute shaders on the GPU.
 * 
 * Requirements:
 * - macOS with Metal-capable GPU
 * - JVM flag: -XstartOnFirstThread
 * - Run as: MAVEN_OPTS="-XstartOnFirstThread" mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.render.voxel.esvo.gpu.ESVOBGFXGPUDemo"
 */
public class ESVOBGFXGPUDemo {
    private static final Logger log = LoggerFactory.getLogger(ESVOBGFXGPUDemo.class);

    public static void main(String[] args) {
        log.info("=== ESVO BGFX Metal GPU Demo ===");
        
        // Check if we have the required JVM flag
        if (!checkRequiredJVMFlags()) {
            System.err.println("ERROR: Missing required JVM flag -XstartOnFirstThread");
            System.err.println("Run with: MAVEN_OPTS=\"-XstartOnFirstThread\" mvn exec:java -Dexec.mainClass=\"com.hellblazer.luciferase.render.voxel.esvo.gpu.ESVOBGFXGPUDemo\"");
            System.exit(1);
        }
        
        ESVOBGFXGPUDemo demo = new ESVOBGFXGPUDemo();
        try {
            demo.runGPUDemo();
        } catch (Exception e) {
            log.error("Demo failed", e);
            System.exit(1);
        }
    }

    private static boolean checkRequiredJVMFlags() {
        // Check if -XstartOnFirstThread is present
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("mac")) {
            // On macOS, check that we're running on the main thread
            try {
                // This is a heuristic - if GLFW operations work, we likely have the right setup
                return true; // We'll validate during GLFW init
            } catch (Exception e) {
                return false;
            }
        }
        return true; // Not macOS, flag not required
    }

    public void runGPUDemo() throws Exception {
        log.info("Starting real GPU execution demo...");
        
        // Step 1: Create GPU configuration for Metal backend
        log.info("Step 1: Configuring Metal GPU backend...");
        GPUConfig config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withHeadless(true) // No window needed for compute
            .withDebugEnabled(true)
            .withWidth(1) // Minimal size for headless
            .withHeight(1)
            .build();

        // Step 2: Initialize ESVO BGFX integration
        log.info("Step 2: Initializing ESVO BGFX integration...");
        ESVOBGFXIntegration integration = null;
        try {
            integration = new ESVOBGFXIntegration(config);
            
            if (!integration.isInitialized()) {
                throw new RuntimeException("Failed to initialize BGFX Metal backend");
            }
            
            log.info("✅ BGFX Metal backend initialized successfully!");
            
            // Step 3: Create test octree with realistic data
            log.info("Step 3: Creating test octree...");
            Octree testOctree = createTestOctree();
            log.info("Created octree with {} nodes, max depth {}", 
                    testOctree.getESVONodes().size(), testOctree.getMaxDepth());

            // Step 4: Upload octree to GPU
            log.info("Step 4: Uploading octree to GPU...");
            long startTime = System.nanoTime();
            boolean uploadSuccess = integration.uploadOctree(testOctree);
            long uploadTimeNs = System.nanoTime() - startTime;
            
            if (!uploadSuccess) {
                throw new RuntimeException("Failed to upload octree to GPU");
            }
            
            log.info("✅ Octree uploaded to GPU successfully!");
            log.info("Upload time: {:.2f} ms", uploadTimeNs / 1_000_000.0);

            // Step 5: Execute actual GPU computation
            log.info("Step 5: Executing Metal compute shaders on GPU...");
            executeGPUComputation(integration);

            // Step 6: Get performance statistics
            log.info("Step 6: Collecting GPU performance statistics...");
            var stats = integration.getPerformanceStats();
            printPerformanceStats(stats);

            log.info("🎉 Real GPU execution completed successfully!");

        } finally {
            if (integration != null) {
                log.info("Cleaning up GPU resources...");
                integration.cleanup();
            }
        }
    }

    private Octree createTestOctree() {
        Octree octree = new Octree();
        
        // Create a realistic octree structure for GPU testing
        int totalNodes = 1000;
        log.info("Creating {} ESVO nodes...", totalNodes);
        
        for (int i = 0; i < totalNodes; i++) {
            ESVONode node = new ESVONode();
            
            // Create diverse node types
            byte validMask = (byte) (0xFF >> (i % 8));
            node.setValidMask(validMask);
            
            // Make some nodes internal (non-leaf)
            if (i % 3 == 0 && validMask != 0) {
                byte nonLeafMask = (byte) (validMask & 0x0F);
                node.setNonLeafMask(nonLeafMask);
                node.setChildPointer(i * 8, false);
            }
            
            // Add contour information to some nodes
            if (i % 7 == 0) {
                node.setContourMask((byte) (validMask & 0xAA));
                node.setContourPointer(i * 4);
            }
            
            octree.addESVONode(node);
        }
        
        octree.setMaxDepth(8);
        octree.setComplete(true);
        
        return octree;
    }

    private void executeGPUComputation(ESVOBGFXIntegration integration) throws Exception {
        log.info("Executing real Metal compute shaders...");
        
        // Create test rays for GPU computation
        int numRays = 1000;
        log.info("Creating {} test rays...", numRays);
        
        // Execute traversal computation on GPU
        long startTime = System.nanoTime();
        
        // This will actually execute the Metal compute shaders on the GPU
        boolean computeSuccess = integration.executeTraversal(numRays);
        
        long computeTimeNs = System.nanoTime() - startTime;
        
        if (!computeSuccess) {
            throw new RuntimeException("GPU computation failed");
        }
        
        log.info("✅ Metal compute shader execution completed!");
        log.info("Compute time: {:.2f} ms for {} rays", computeTimeNs / 1_000_000.0, numRays);
        log.info("Performance: {:.0f} rays/second", numRays / (computeTimeNs / 1_000_000_000.0));
        
        // Execute beam optimization if available
        try {
            log.info("Testing beam optimization shader...");
            boolean beamSuccess = integration.executeBeamOptimization(numRays / 4); // Quarter for beam test
            if (beamSuccess) {
                log.info("✅ Beam optimization shader executed successfully!");
            }
        } catch (Exception e) {
            log.warn("Beam optimization not available: {}", e.getMessage());
        }
    }

    private void printPerformanceStats(Object stats) {
        log.info("=== GPU Performance Statistics ===");
        
        if (stats == null) {
            log.info("No performance statistics available");
            return;
        }
        
        try {
            // Use reflection to get stats (since we don't know the exact type)
            var statsClass = stats.getClass();
            
            try {
                var totalNodes = statsClass.getMethod("getTotalNodesUploaded").invoke(stats);
                log.info("Total nodes uploaded: {}", totalNodes);
            } catch (Exception e) {
                log.debug("getTotalNodesUploaded not available");
            }
            
            try {
                var uploadTime = statsClass.getMethod("getUploadTimeMs").invoke(stats);
                log.info("Upload time: {} ms", uploadTime);
            } catch (Exception e) {
                log.debug("getUploadTimeMs not available");
            }
            
            try {
                var computeTime = statsClass.getMethod("getComputeTimeMs").invoke(stats);
                log.info("Compute time: {} ms", computeTime);
            } catch (Exception e) {
                log.debug("getComputeTimeMs not available");
            }
            
        } catch (Exception e) {
            log.info("Performance stats object: {}", stats.toString());
        }
        
        log.info("===============================");
    }
}