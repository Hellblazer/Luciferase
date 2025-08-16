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
package com.hellblazer.luciferase.render.demo;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.render.bridge.SpatialIndexRenderBridge;
import com.hellblazer.luciferase.render.pipeline.VoxelRenderingPipeline;
import com.hellblazer.luciferase.render.memory.GPUMemoryManager;
import com.hellblazer.luciferase.render.profiler.RenderProfiler;
import com.hellblazer.luciferase.render.voxel.compression.SVOCompressor;
import com.hellblazer.luciferase.render.voxel.voxelizer.MeshVoxelizer;

import org.joml.Vector3f;
import javax.vecmath.Point3f;

import java.util.Random;
import java.util.logging.Logger;

/**
 * Demonstration of the complete render pipeline with all components integrated.
 * This demo shows:
 * 1. Creating a spatial index with entities
 * 2. Converting to voxel representation via the bridge
 * 3. Setting up the rendering pipeline
 * 4. Profiling render performance
 * 5. Compressing voxel data
 */
public class RenderPipelineDemo {
    
    private static final Logger log = Logger.getLogger(RenderPipelineDemo.class.getName());
    
    // Configuration
    private static final float WORLD_SIZE = 1000.0f;
    private static final int ENTITY_COUNT = 1000;
    private static final int FRAME_COUNT = 60;
    
    // Components
    private Octree<LongEntityID, String> spatialIndex;
    private SpatialIndexRenderBridge<LongEntityID, String> bridge;
    private VoxelRenderingPipeline<LongEntityID, String> pipeline;
    private RenderProfiler profiler;
    private SVOCompressor compressor;
    private MeshVoxelizer voxelizer;
    
    public void run() {
        log.info("Starting Render Pipeline Demo");
        
        // Initialize components
        initializeSpatialIndex();
        populateSpatialIndex();
        
        // Create render bridge
        bridge = new SpatialIndexRenderBridge<>(spatialIndex, WORLD_SIZE);
        
        // Initialize rendering pipeline
        // NOTE: Skipping actual pipeline initialization as it requires OpenGL context
        // pipeline = new VoxelRenderingPipeline<>(spatialIndex, WORLD_SIZE);
        pipeline = null;
        
        // Initialize supporting components
        profiler = new RenderProfiler(false, 60, 10);
        compressor = new SVOCompressor();
        voxelizer = new MeshVoxelizer(128, MeshVoxelizer.Algorithm.RAY_BASED, false); // 128x128x128 voxel grid
        
        // Build voxel octree from spatial index
        log.info("Building voxel octree from spatial index...");
        var rootNode = bridge.buildVoxelOctree();
        log.info("Voxel octree built with " + rootNode.getNodeCount() + " nodes");
        
        // Test compression
        testCompression(rootNode);
        
        // Test voxelization of a simple mesh
        testVoxelization();
        
        // Simulate rendering loop
        simulateRendering();
        
        // Report performance metrics
        reportMetrics();
        
        log.info("Render Pipeline Demo completed");
    }
    
    private void initializeSpatialIndex() {
        log.info("Initializing spatial index...");
        var idGenerator = new SequentialLongIDGenerator();
        spatialIndex = new Octree<>(idGenerator);
    }
    
    private void populateSpatialIndex() {
        log.info("Populating spatial index with " + ENTITY_COUNT + " entities...");
        var random = new Random(42);
        
        for (int i = 0; i < ENTITY_COUNT; i++) {
            var id = new LongEntityID(i);
            var x = random.nextFloat() * WORLD_SIZE;
            var y = random.nextFloat() * WORLD_SIZE;
            var z = random.nextFloat() * WORLD_SIZE;
            
            String content = "Entity_" + i;
            if (i % 3 == 0) content = "TypeA";
            else if (i % 3 == 1) content = "TypeB";
            else content = "TypeC";
            
            spatialIndex.insert(id, new Point3f(x, y, z), (byte)5, content);
        }
        
        log.info("Spatial index populated with " + spatialIndex.size() + " entities");
    }
    
    private void testCompression(com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode rootNode) {
        log.info("Testing SVO compression...");
        
        try {
            // Compress the octree
            long startTime = System.currentTimeMillis();
            var compressed = compressor.compress(rootNode);
            long compressionTime = System.currentTimeMillis() - startTime;
            
            // Calculate compression ratio
            int originalSize = rootNode.getSubtreeSize();
            int compressedSize = compressed.length;
            float ratio = (float)originalSize / compressedSize;
            
            log.info(String.format("Compression: %d bytes -> %d bytes (%.2fx ratio) in %d ms",
                originalSize, compressedSize, ratio, compressionTime));
            
            // Test decompression
            startTime = System.currentTimeMillis();
            var decompressed = compressor.decompress(compressed);
            long decompressionTime = System.currentTimeMillis() - startTime;
            
            log.info("Decompression completed in " + decompressionTime + " ms");
            
        } catch (Exception e) {
            log.warning("Compression test failed: " + e.getMessage());
        }
    }
    
    private void testVoxelization() {
        log.info("Testing mesh voxelization...");
        
        // Note: MeshVoxelizer expects a Mesh object, not raw vertices
        // For this demo, we'll skip the actual voxelization since we don't have a Mesh class
        // In a real application, you would load a mesh from a file or create one programmatically
        
        log.info("Voxelization test skipped (requires Mesh object implementation)");
        
        // Example of what it would look like:
        // Mesh mesh = loadMeshFromFile("model.obj");
        // var voxelNode = voxelizer.voxelize(mesh, WORLD_SIZE);
        // log.info("Voxelized mesh into " + voxelNode.getNodeCount() + " nodes");
    }
    
    private void simulateRendering() {
        log.info("Simulating rendering for " + FRAME_COUNT + " frames...");
        
        // Note: This is a simulation since we don't have an actual OpenGL context
        // In a real application, this would be inside the GLFW/LWJGL render loop
        
        // Set up camera
        var cameraPos = new Vector3f(500, 500, 1500);
        var target = new Vector3f(500, 500, 500);
        var up = new Vector3f(0, 1, 0);
        
        // Simulate frames
        for (int frame = 0; frame < FRAME_COUNT; frame++) {
            profiler.beginFrame();
            
            // Update camera (simulate movement)
            float angle = (float)(frame * Math.PI / 30);
            cameraPos.x = 500 + (float)Math.cos(angle) * 1000;
            cameraPos.z = 500 + (float)Math.sin(angle) * 1000;
            
            // Update region (simulate dynamic updates)
            if (frame % 10 == 0) {
                profiler.beginTimer(RenderProfiler.Category.GPU_UPLOAD);
                var region = bridge.updateRegion(
                    new SpatialIndexRenderBridge.BoundingBox(
                        400, 400, 400, 600, 600, 600
                    )
                );
                profiler.endTimer(RenderProfiler.Category.GPU_UPLOAD);
            }
            
            // Simulate frustum culling
            profiler.beginTimer(RenderProfiler.Category.FRUSTUM_CULLING);
            var frustum = createSimpleFrustum(cameraPos);
            var visibleNodes = bridge.getVisibleNodes(frustum, 1000);
            profiler.endTimer(RenderProfiler.Category.FRUSTUM_CULLING);
            
            // Simulate render time (would be actual GPU rendering)
            profiler.beginTimer(RenderProfiler.Category.VOXEL_RENDER);
            try {
                Thread.sleep(16); // Simulate 60 FPS target
            } catch (InterruptedException e) {
                // Ignore
            }
            profiler.endTimer(RenderProfiler.Category.VOXEL_RENDER);
            
            profiler.endFrame();
        }
    }
    
    private SpatialIndexRenderBridge.Frustum createSimpleFrustum(Vector3f cameraPos) {
        // Create a simple frustum for testing
        float[][] planes = new float[6][4];
        
        // Simple frustum planes (not geometrically accurate, just for demo)
        for (int i = 0; i < 6; i++) {
            planes[i] = new float[]{0, 0, 1, 1000};
        }
        
        return new SpatialIndexRenderBridge.Frustum(
            planes,
            new Point3f(cameraPos.x, cameraPos.y, cameraPos.z)
        );
    }
    
    private void reportMetrics() {
        log.info("Performance Metrics:");
        log.info("  Average Frame Time: " + profiler.getAverageFrameTime() + " ms");
        log.info("  Current FPS: " + profiler.getCurrentFPS());
        log.info("  Performance Report:\n" + profiler.getPerformanceReport());
        
        // Pipeline metrics
        if (pipeline != null) {
            var pipelineMetrics = pipeline.getPerformanceMetrics();
            log.info("Pipeline Metrics:");
            for (var entry : pipelineMetrics.entrySet()) {
                log.info("  " + entry.getKey() + ": " + (entry.getValue() / 1_000_000.0) + " ms");
            }
        }
    }
    
    public static void main(String[] args) {
        var demo = new RenderPipelineDemo();
        demo.run();
    }
}