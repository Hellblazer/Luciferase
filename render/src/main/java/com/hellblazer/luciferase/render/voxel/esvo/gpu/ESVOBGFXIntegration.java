/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * Licensed under the AGPL License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import com.hellblazer.luciferase.render.gpu.*;
import com.hellblazer.luciferase.render.gpu.bgfx.BGFXGPUContext;
import com.hellblazer.luciferase.render.gpu.bgfx.BGFXBufferManager;
import com.hellblazer.luciferase.render.voxel.esvo.*;
import com.hellblazer.luciferase.render.voxel.esvo.voxelization.Octree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BGFX Metal backend integration for ESVO pipeline.
 * Replaces OpenGL-based ESVOGPUIntegration to eliminate -XstartOnFirstThread requirement.
 * 
 * <p>This integration provides:</p>
 * <ul>
 * <li>ESVO octree upload to Metal compute buffers</li>
 * <li>GPU memory management using BGFX buffer objects</li>
 * <li>Compute shader execution for ESVO traversal</li>
 * <li>Cross-platform compatibility without threading restrictions</li>
 * </ul>
 */
public class ESVOBGFXIntegration {
    
    private static final Logger log = LoggerFactory.getLogger(ESVOBGFXIntegration.class);
    
    // ESVO node size in bytes (matching the 8-byte format)
    private static final int ESVO_NODE_SIZE = 8;
    
    // Maximum nodes per buffer (2MB / 8 bytes = 256K nodes)
    private static final int MAX_NODES_PER_BUFFER = 256 * 1024;
    
    // Buffer binding points for ESVO compute shaders
    private static final int NODE_BUFFER_BINDING = 0;
    private static final int PAGE_BUFFER_BINDING = 1;
    private static final int METADATA_BUFFER_BINDING = 2;
    private static final int TRAVERSAL_BUFFER_BINDING = 3;
    
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicLong totalNodesUploaded = new AtomicLong(0);
    private final AtomicLong totalPagesUploaded = new AtomicLong(0);
    private final AtomicLong uploadTimeMs = new AtomicLong(0);
    
    private IGPUContext gpuContext;
    private IShaderFactory shaderFactory;
    private ESVOShaderManager shaderManager;
    private BGFXBufferManager bufferManager;
    
    // BGFX buffers for ESVO data
    private IGPUBuffer nodeBuffer;
    private IGPUBuffer pageBuffer;
    private IGPUBuffer metadataBuffer;
    private IGPUBuffer traversalResultBuffer;
    
    // Compute shaders
    private IGPUShader traverseShader;
    private IGPUShader beamShader;
    
    /**
     * Creates a new ESVO BGFX integration.
     * 
     * @param config GPU configuration for backend selection
     */
    public ESVOBGFXIntegration(GPUConfig config) {
        initialize(config);
    }
    
    /**
     * Initialize the BGFX Metal backend for ESVO operations.
     */
    private void initialize(GPUConfig config) {
        if (initialized.get()) {
            return;
        }
        
        try {
            // Create BGFX GPU context
            gpuContext = new BGFXGPUContext();
            
            // Configure for Metal backend on macOS
            var metalConfig = GPUConfig.builder()
                .withBackend(GPUConfig.Backend.BGFX_METAL)
                .withHeadless(config.isHeadless())
                .withDebugEnabled(config.isDebugEnabled())
                .withWidth(config.getWidth())
                .withHeight(config.getHeight())
                .build();
            
            boolean success = gpuContext.initialize(metalConfig);
            if (!success) {
                throw new RuntimeException("Failed to initialize BGFX Metal context");
            }
            
            // Get shader factory for Metal shaders
            shaderFactory = gpuContext.getShaderFactory();
            
            // Initialize ESVO shader manager with BGFX backend
            shaderManager = new ESVOShaderManager("shaders/esvo/", false);
            
            // Initialize buffer manager for BGFX
            bufferManager = new BGFXBufferManager((BGFXGPUContext) gpuContext);
            
            // Create GPU buffers
            createBuffers();
            
            // Load and compile shaders
            loadShaders();
            
            initialized.set(true);
            log.info("ESVO BGFX Metal integration initialized successfully");
            
        } catch (Exception e) {
            log.error("Failed to initialize ESVO BGFX integration", e);
            cleanup();
            throw new RuntimeException("ESVO BGFX initialization failed", e);
        }
    }
    
    /**
     * Create GPU buffers for ESVO data storage.
     */
    private void createBuffers() {
        // Node buffer for octree nodes
        nodeBuffer = gpuContext.createBuffer(
            BufferType.STORAGE, 
            MAX_NODES_PER_BUFFER * ESVO_NODE_SIZE, 
            BufferUsage.DYNAMIC_READ
        );
        
        // Page buffer for ESVO pages
        pageBuffer = gpuContext.createBuffer(
            BufferType.STORAGE,
            4 * 1024 * 1024, // 4MB for page data
            BufferUsage.DYNAMIC_READ
        );
        
        // Metadata buffer for octree metadata
        metadataBuffer = gpuContext.createBuffer(
            BufferType.UNIFORM,
            1024, // 1KB for metadata
            BufferUsage.DYNAMIC_READ
        );
        
        // Traversal result buffer
        traversalResultBuffer = gpuContext.createBuffer(
            BufferType.STORAGE,
            1024 * 1024, // 1MB for traversal results
            BufferUsage.DYNAMIC_READ
        );
        
        log.debug("Created ESVO GPU buffers: node={}({} bytes), page={}({} bytes), metadata={}({} bytes), traversal={}({} bytes)",
                 nodeBuffer.getType(), nodeBuffer.getSize(), pageBuffer.getType(), pageBuffer.getSize(),
                 metadataBuffer.getType(), metadataBuffer.getSize(), traversalResultBuffer.getType(), traversalResultBuffer.getSize());
    }
    
    /**
     * Load and compile ESVO compute shaders for Metal backend.
     */
    private void loadShaders() {
        try {
            // Load the actual Metal traverse shader
            Optional<String> traverseSource = shaderFactory.loadShaderSource("traverse.metal");
            if (traverseSource.isEmpty()) {
                log.warn("traverse.metal not found, using traverse.comp fallback");
                traverseSource = shaderFactory.loadShaderSource("traverse.comp");
            }
            
            if (traverseSource.isPresent()) {
                traverseShader = gpuContext.createComputeShader(traverseSource.get(), Map.of(
                    "MAX_STACK_DEPTH", "23",
                    "ENABLE_STATISTICS", "1",
                    "ENABLE_LOD", "1"
                ));
                log.debug("Created ESVO traverse shader successfully");
            } else {
                throw new RuntimeException("ESVO traverse shader source not found");
            }
            
            // Load the actual Metal beam shader
            Optional<String> beamSource = shaderFactory.loadShaderSource("beam.metal");
            if (beamSource.isEmpty()) {
                log.warn("beam.metal not found, using beam.comp fallback");
                beamSource = shaderFactory.loadShaderSource("beam.comp");
            }
            
            if (beamSource.isPresent()) {
                beamShader = gpuContext.createComputeShader(beamSource.get(), Map.of(
                    "LOCAL_SIZE_X", "8",
                    "LOCAL_SIZE_Y", "8",
                    "BEAM_COHERENCE_THRESHOLD", "0.85",
                    "MAX_BEAM_STACK_DEPTH", "20",
                    "MAX_RAYS_PER_BEAM", "64"
                ));
                log.debug("Created ESVO beam shader successfully");
            } else {
                throw new RuntimeException("ESVO beam shader source not found");
            }
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to create ESVO shaders", e);
        }
    }
    
    /**
     * Upload an ESVO octree to GPU memory using BGFX Metal backend.
     * 
     * @param octree The octree to upload
     * @return true if upload successful
     */
    public boolean uploadOctree(Octree octree) {
        if (!initialized.get()) {
            log.error("ESVO BGFX integration not initialized");
            return false;
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Convert octree to ESVO format
            var nodes = octree.getESVONodes();
            if (nodes.size() > MAX_NODES_PER_BUFFER) {
                log.warn("Octree has {} nodes, exceeding buffer capacity {}", 
                        nodes.size(), MAX_NODES_PER_BUFFER);
                // Could implement chunking here for very large octrees
            }
            
            // Upload node data
            var nodeData = serializeNodes(nodes);
            nodeBuffer.upload(nodeData);
            
            // Upload metadata
            var metadata = createMetadata(octree);
            metadataBuffer.upload(metadata);
            
            // Update statistics
            totalNodesUploaded.addAndGet(nodes.size());
            long uploadTime = System.currentTimeMillis() - startTime;
            uploadTimeMs.addAndGet(uploadTime);
            
            log.debug("Uploaded octree with {} nodes in {}ms", nodes.size(), uploadTime);
            return true;
            
        } catch (Exception e) {
            log.error("Failed to upload octree to GPU", e);
            return false;
        }
    }
    
    /**
     * Execute ESVO traversal using Metal compute shaders.
     * 
     * @param rays List of rays to traverse
     * @return Traversal results
     */
    public ESVOTraversalResult executeTraversal(List<ESVORay> rays) {
        if (!initialized.get()) {
            throw new IllegalStateException("ESVO BGFX integration not initialized");
        }
        
        try {
            // Bind buffers using buffer manager
            bufferManager.bindBuffer(BufferSlot.NODE_BUFFER, nodeBuffer, AccessType.READ_ONLY);
            bufferManager.bindBuffer(BufferSlot.TRAVERSAL_UNIFORMS, metadataBuffer, AccessType.READ_ONLY);
            bufferManager.bindBuffer(BufferSlot.STATISTICS_BUFFER, traversalResultBuffer, AccessType.WRITE_ONLY);
            
            // Dispatch compute shader using available API
            int workGroupsX = (rays.size() + 31) / 32; // 32 threads per work group
            gpuContext.dispatch(traverseShader, workGroupsX, 1, 1);
            
            // Wait for completion
            gpuContext.memoryBarrier(BarrierType.SHADER_STORAGE_BARRIER);
            
            // Read back results
            var resultData = traversalResultBuffer.download();
            return parseTraversalResults(resultData, rays.size());
            
        } catch (Exception e) {
            log.error("Failed to execute ESVO traversal", e);
            throw new RuntimeException("ESVO traversal execution failed", e);
        }
    }
    
    /**
     * Execute beam optimization using Metal compute shaders.
     * 
     * @param beams List of coherent ray beams
     * @return Optimized beam configuration
     */
    public ESVOBeamResult executeBeamOptimization(List<ESVOBeam> beams) {
        if (!initialized.get()) {
            throw new IllegalStateException("ESVO BGFX integration not initialized");
        }
        
        try {
            // Bind buffers using buffer manager
            bufferManager.bindBuffer(BufferSlot.NODE_BUFFER, nodeBuffer, AccessType.READ_ONLY);
            bufferManager.bindBuffer(BufferSlot.STATISTICS_BUFFER, traversalResultBuffer, AccessType.READ_WRITE);
            
            // Dispatch beam shader using available API
            int workGroupsX = (beams.size() + 63) / 64; // 8x8 = 64 threads per work group
            gpuContext.dispatch(beamShader, workGroupsX, 1, 1);
            
            // Wait for completion
            gpuContext.memoryBarrier(BarrierType.SHADER_STORAGE_BARRIER);
            
            // Read back optimized results
            var resultData = traversalResultBuffer.download();
            return parseBeamResults(resultData, beams.size());
            
        } catch (Exception e) {
            log.error("Failed to execute ESVO beam optimization", e);
            throw new RuntimeException("ESVO beam optimization failed", e);
        }
    }
    
    /**
     * Get performance statistics for the ESVO GPU integration.
     */
    public ESVOPerformanceStats getPerformanceStats() {
        return new ESVOPerformanceStats(
            totalNodesUploaded.get(),
            totalPagesUploaded.get(),
            uploadTimeMs.get(),
            0L, // Memory usage - would need specific BGFX memory tracking
            "BGFX Metal backend compilation stats" // Simplified compilation stats
        );
    }
    
    /**
     * Check if the integration is properly initialized.
     */
    public boolean isInitialized() {
        return initialized.get() && gpuContext.isValid();
    }
    
    /**
     * Release all GPU resources.
     */
    public void cleanup() {
        log.debug("Cleaning up ESVO BGFX integration");
        
        // Destroy shaders
        if (traverseShader != null) {
            traverseShader.destroy();
            traverseShader = null;
        }
        if (beamShader != null) {
            beamShader.destroy();
            beamShader = null;
        }
        
        // Destroy buffers
        if (nodeBuffer != null) {
            nodeBuffer.destroy();
            nodeBuffer = null;
        }
        if (pageBuffer != null) {
            pageBuffer.destroy();
            pageBuffer = null;
        }
        if (metadataBuffer != null) {
            metadataBuffer.destroy();
            metadataBuffer = null;
        }
        if (traversalResultBuffer != null) {
            traversalResultBuffer.destroy();
            traversalResultBuffer = null;
        }
        
        // Cleanup shader manager
        if (shaderManager != null) {
            shaderManager.shutdown();
            shaderManager = null;
        }
        
        // Cleanup GPU context
        if (gpuContext != null) {
            gpuContext.cleanup();
            gpuContext = null;
        }
        
        initialized.set(false);
        log.info("ESVO BGFX integration cleanup completed");
    }
    
    // Helper methods for data serialization and parsing
    
    private ByteBuffer serializeNodes(List<ESVONode> nodes) {
        var buffer = ByteBuffer.allocateDirect(nodes.size() * ESVO_NODE_SIZE);
        for (var node : nodes) {
            var nodeBytes = node.toBytes();
            buffer.put(nodeBytes);
        }
        buffer.flip();
        return buffer;
    }
    
    private ByteBuffer createMetadata(Octree octree) {
        var buffer = ByteBuffer.allocateDirect(1024);
        buffer.putInt(octree.getMaxDepth());
        buffer.putInt(octree.getESVONodes().size());
        // Use default bounding box for now - could be configurable
        buffer.putFloat(-1.0f); // minX
        buffer.putFloat(-1.0f); // minY
        buffer.putFloat(-1.0f); // minZ
        buffer.putFloat(1.0f);  // maxX
        buffer.putFloat(1.0f);  // maxY
        buffer.putFloat(1.0f);  // maxZ
        buffer.flip();
        return buffer;
    }
    
    private ESVOTraversalResult parseTraversalResults(ByteBuffer data, int numRays) {
        // Parse traversal results from GPU buffer
        // Implementation would depend on the specific result format
        return new ESVOTraversalResult(numRays, data);
    }
    
    private ESVOBeamResult parseBeamResults(ByteBuffer data, int numBeams) {
        // Parse beam optimization results from GPU buffer
        // Implementation would depend on the specific result format
        return new ESVOBeamResult(numBeams, data);
    }

    /**
     * Execute traversal computation on GPU using Metal compute shaders.
     * This method actually runs the traverse.metal shader on the GPU.
     * 
     * @param numRays Number of rays to process
     * @return true if execution succeeded
     */
    public boolean executeTraversal(int numRays) {
        if (!initialized.get() || traverseShader == null) {
            log.error("ESVO BGFX integration not properly initialized for traversal");
            return false;
        }

        try {
            log.debug("Executing traversal for {} rays on Metal GPU", numRays);
            
            // Create ray buffer for GPU computation
            var rayBuffer = bufferManager.createBuffer(
                BufferType.STORAGE, 
                numRays * 32, // Assuming 32 bytes per ray (origin + direction + metadata)
                BufferUsage.DYNAMIC_READ
            );
            
            // Create result buffer
            var resultSize = numRays * 16; // Assuming 16 bytes per result
            if (traversalResultBuffer == null || traversalResultBuffer.getSize() < resultSize) {
                if (traversalResultBuffer != null) {
                    traversalResultBuffer.destroy();
                }
                traversalResultBuffer = bufferManager.createBuffer(
                    BufferType.STORAGE,
                    resultSize,
                    BufferUsage.DYNAMIC_READ
                );
            }

            // Initialize ray data
            var rayData = createTestRayData(numRays);
            rayBuffer.upload(rayData);

            // Bind buffers to compute shader
            nodeBuffer.bind(NODE_BUFFER_BINDING, AccessType.READ_ONLY);
            rayBuffer.bind(1, AccessType.READ_ONLY); // Ray buffer
            traversalResultBuffer.bind(2, AccessType.WRITE_ONLY); // Result buffer
            metadataBuffer.bind(3, AccessType.READ_ONLY); // Metadata buffer

            // Dispatch compute shader
            long startTime = System.nanoTime();
            
            // Calculate dispatch groups (assuming 64 threads per group)
            int groupSize = 64;
            int numGroups = (numRays + groupSize - 1) / groupSize;
            
            boolean success = gpuContext.dispatchCompute(traverseShader, numGroups, 1, 1);
            
            if (success) {
                // Wait for GPU completion
                gpuContext.waitForCompletion();
                
                long computeTime = (System.nanoTime() - startTime) / 1_000_000;
                log.debug("GPU traversal completed in {} ms", computeTime);
                
                // Read back results (optional for validation)
                var resultData = traversalResultBuffer.download();
                var results = parseTraversalResults(resultData, numRays);
                
                log.info("Traversal executed successfully: {} rays processed", results.getNumRays());
                return true;
            } else {
                log.error("GPU compute dispatch failed");
                return false;
            }
            
        } catch (Exception e) {
            log.error("Failed to execute traversal on GPU", e);
            return false;
        }
    }

    /**
     * Execute beam optimization computation on GPU using Metal compute shaders.
     * This method actually runs the beam.metal shader on the GPU.
     * 
     * @param numBeams Number of beam groups to process
     * @return true if execution succeeded
     */
    public boolean executeBeamOptimization(int numBeams) {
        if (!initialized.get() || beamShader == null) {
            log.warn("Beam optimization shader not available");
            return false;
        }

        try {
            log.debug("Executing beam optimization for {} beam groups on Metal GPU", numBeams);
            
            // Create beam buffer for GPU computation
            var beamBuffer = bufferManager.createBuffer(
                BufferType.STORAGE,
                numBeams * 64, // Assuming 64 bytes per beam group
                BufferUsage.DYNAMIC_READ
            );
            
            // Create beam result buffer
            var beamResultBuffer = bufferManager.createBuffer(
                BufferType.STORAGE,
                numBeams * 32, // Assuming 32 bytes per beam result
                BufferUsage.DYNAMIC_READ
            );

            // Initialize beam data
            var beamData = createTestBeamData(numBeams);
            beamBuffer.upload(beamData);

            // Bind buffers to beam compute shader
            nodeBuffer.bind(NODE_BUFFER_BINDING, AccessType.READ_ONLY);
            beamBuffer.bind(1, AccessType.READ_ONLY); // Beam buffer
            beamResultBuffer.bind(2, AccessType.WRITE_ONLY); // Beam result buffer
            metadataBuffer.bind(3, AccessType.READ_ONLY); // Metadata buffer

            // Dispatch beam compute shader
            long startTime = System.nanoTime();
            
            // Calculate dispatch groups for beam processing
            int groupSize = 32; // Smaller groups for beam optimization
            int numGroups = (numBeams + groupSize - 1) / groupSize;
            
            boolean success = gpuContext.dispatchCompute(beamShader, numGroups, 1, 1);
            
            if (success) {
                // Wait for GPU completion
                gpuContext.waitForCompletion();
                
                long computeTime = (System.nanoTime() - startTime) / 1_000_000;
                log.debug("GPU beam optimization completed in {} ms", computeTime);
                
                // Read back results
                var resultData = beamResultBuffer.download();
                var results = parseBeamResults(resultData, numBeams);
                
                log.info("Beam optimization executed successfully: {} beams processed", results.getNumBeams());
                return true;
            } else {
                log.error("GPU beam compute dispatch failed");
                return false;
            }
            
        } catch (Exception e) {
            log.error("Failed to execute beam optimization on GPU", e);
            return false;
        }
    }

    /**
     * Create test ray data for GPU computation.
     * In a real implementation, this would come from the rendering pipeline.
     */
    private ByteBuffer createTestRayData(int numRays) {
        var buffer = ByteBuffer.allocateDirect(numRays * 32);
        
        for (int i = 0; i < numRays; i++) {
            // Ray origin (3 floats)
            buffer.putFloat(-1.0f + (i % 100) * 0.02f); // X
            buffer.putFloat(-1.0f + ((i / 100) % 100) * 0.02f); // Y
            buffer.putFloat(-1.0f); // Z
            buffer.putFloat(1.0f); // W padding
            
            // Ray direction (3 floats + 1 padding)
            buffer.putFloat(0.0f); // dx
            buffer.putFloat(0.0f); // dy
            buffer.putFloat(1.0f); // dz (forward)
            buffer.putFloat(0.0f); // padding
            
            // Ray metadata (2 floats)
            buffer.putFloat(Float.MAX_VALUE); // tMax
            buffer.putFloat(i); // ray ID
            buffer.putFloat(0.0f); // padding
            buffer.putFloat(0.0f); // padding
        }
        
        buffer.flip();
        return buffer;
    }

    /**
     * Create test beam data for GPU computation.
     */
    private ByteBuffer createTestBeamData(int numBeams) {
        var buffer = ByteBuffer.allocateDirect(numBeams * 64);
        
        for (int i = 0; i < numBeams; i++) {
            // Beam origin (4 floats)
            buffer.putFloat(-1.0f + (i % 10) * 0.2f);
            buffer.putFloat(-1.0f + ((i / 10) % 10) * 0.2f);
            buffer.putFloat(-1.0f);
            buffer.putFloat(1.0f);
            
            // Beam direction (4 floats)
            buffer.putFloat(0.0f);
            buffer.putFloat(0.0f);
            buffer.putFloat(1.0f);
            buffer.putFloat(0.0f);
            
            // Beam parameters (8 floats)
            buffer.putFloat(0.1f); // beam width
            buffer.putFloat(Float.MAX_VALUE); // tMax
            buffer.putInt(4); // rays per beam
            buffer.putInt(i); // beam ID
            
            // Padding to 64 bytes
            for (int j = 0; j < 8; j++) {
                buffer.putFloat(0.0f);
            }
        }
        
        buffer.flip();
        return buffer;
    }
}