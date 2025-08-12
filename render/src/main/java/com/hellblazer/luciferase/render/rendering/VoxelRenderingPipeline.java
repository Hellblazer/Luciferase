package com.hellblazer.luciferase.render.rendering;

import com.hellblazer.luciferase.render.compression.SparseVoxelCompressor;
import com.hellblazer.luciferase.render.io.VoxelStreamingIO;
import com.hellblazer.luciferase.render.voxel.core.VoxelOctreeNode;
import com.hellblazer.luciferase.render.voxel.gpu.GPUBufferManager;
import com.hellblazer.luciferase.render.voxel.gpu.WebGPUContext;
import com.hellblazer.luciferase.render.voxel.gpu.ComputeShaderManager;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import com.hellblazer.luciferase.webgpu.wrapper.Buffer;
import com.hellblazer.luciferase.webgpu.wrapper.ComputePipeline;
import com.hellblazer.luciferase.webgpu.wrapper.Device;
import com.hellblazer.luciferase.webgpu.wrapper.BindGroup;
import com.hellblazer.luciferase.webgpu.wrapper.BindGroupLayout;
import com.hellblazer.luciferase.webgpu.wrapper.PipelineLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-level voxel rendering pipeline that coordinates WebGPU rendering,
 * octree management, streaming I/O, and adaptive quality control.
 * 
 * This class provides the main rendering interface for the ESVO system,
 * handling frame rendering, resource management, and performance monitoring.
 */
public class VoxelRenderingPipeline implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(VoxelRenderingPipeline.class);
    
    // Core components
    private final WebGPUContext webgpuContext;
    private final VoxelStreamingIO streamingIO;
    private final SparseVoxelCompressor compressor;
    private final RenderingConfiguration config;
    private final StreamingController streamingController;
    
    // GPU resources
    private Buffer octreeBuffer;
    private Buffer voxelDataBuffer;
    private Buffer raysBuffer;
    private Buffer hitsBuffer;
    private Buffer frameBuffer;
    private Buffer readbackBuffer;  // Separate buffer for reading GPU data
    private Buffer uniformBuffer;
    private ComputePipeline computePipeline;
    private BindGroupLayout bindGroupLayout;
    private BindGroup bindGroup;
    
    // Rendering state
    private final AtomicBoolean isRendering = new AtomicBoolean(false);
    private final AtomicInteger currentQualityLevel;
    private volatile VoxelOctreeNode currentOctree;
    
    // Performance tracking
    private final AtomicLong totalFramesRendered = new AtomicLong(0);
    private final AtomicLong totalRenderTimeNanos = new AtomicLong(0);
    private final AtomicInteger octreeUpdates = new AtomicInteger(0);
    private final ConcurrentLinkedQueue<Long> frameTimes = new ConcurrentLinkedQueue<>();
    
    // Async execution
    private final ExecutorService renderExecutor;
    private final CompletionService<RenderedFrame> renderService;
    
    /**
     * Configuration for the rendering pipeline.
     */
    public static class RenderingConfiguration {
        public int screenWidth = 1920;
        public int screenHeight = 1080;
        public boolean enableAdaptiveQuality = true;
        public boolean enableAsyncStreaming = true;
        public int initialQualityLevel = 3;
        public int maxQualityLevel = 5;
        public int minQualityLevel = 1;
        public long targetFrameTimeMs = 16; // 60 FPS target
        public int maxConcurrentFrames = 2;
    }
    
    /**
     * Represents the current rendering state for a frame.
     */
    public static class RenderingState {
        public final float[] viewMatrix;
        public final float[] projectionMatrix;
        public final float[] cameraPosition;
        public final float[] lightDirection;
        public final float ambientLight;
        public final int currentLOD;
        public final long frameNumber;
        
        public RenderingState(float[] viewMatrix, float[] projectionMatrix,
                            float[] cameraPosition, float[] lightDirection,
                            float ambientLight, int currentLOD, long frameNumber) {
            this.viewMatrix = viewMatrix;
            this.projectionMatrix = projectionMatrix;
            this.cameraPosition = cameraPosition;
            this.lightDirection = lightDirection;
            this.ambientLight = ambientLight;
            this.currentLOD = currentLOD;
            this.frameNumber = frameNumber;
        }
    }
    
    /**
     * Represents a rendered frame.
     */
    public static class RenderedFrame {
        public final long frameNumber;
        public final int width;
        public final int height;
        public final byte[] imageData;
        public final long renderTimeNanos;
        public final int qualityLevel;
        
        public RenderedFrame(long frameNumber, int width, int height,
                           byte[] imageData, long renderTimeNanos, int qualityLevel) {
            this.frameNumber = frameNumber;
            this.width = width;
            this.height = height;
            this.imageData = imageData;
            this.renderTimeNanos = renderTimeNanos;
            this.qualityLevel = qualityLevel;
        }
    }
    
    /**
     * Performance metrics for the rendering pipeline.
     */
    public static class PerformanceMetrics {
        public final long totalFramesRendered;
        public final double averageFrameTimeMs;
        public final int octreeUpdates;
        public final int currentQualityLevel;
        public final double frameTimeStdDev;
        
        public PerformanceMetrics(long totalFramesRendered, double averageFrameTimeMs,
                                int octreeUpdates, int currentQualityLevel, double frameTimeStdDev) {
            this.totalFramesRendered = totalFramesRendered;
            this.averageFrameTimeMs = averageFrameTimeMs;
            this.octreeUpdates = octreeUpdates;
            this.currentQualityLevel = currentQualityLevel;
            this.frameTimeStdDev = frameTimeStdDev;
        }
    }
    
    /**
     * Create a new voxel rendering pipeline.
     */
    public VoxelRenderingPipeline(WebGPUContext webgpuContext, 
                                 VoxelStreamingIO streamingIO,
                                 SparseVoxelCompressor compressor,
                                 RenderingConfiguration config) {
        this.webgpuContext = webgpuContext;
        this.streamingIO = streamingIO;
        this.compressor = compressor;
        this.config = config;
        this.currentQualityLevel = new AtomicInteger(config.initialQualityLevel);
        
        // Initialize executor for async rendering
        this.renderExecutor = Executors.newFixedThreadPool(config.maxConcurrentFrames);
        this.renderService = new ExecutorCompletionService<>(renderExecutor);
        
        // Initialize streaming controller
        var streamingConfig = new StreamingController.StreamingConfig();
        streamingConfig.maxConcurrentLoads = 4;
        streamingConfig.prefetchDistance = 3;
        this.streamingController = new StreamingController(streamingIO, streamingConfig);
        
        // Set up octree update callback
        streamingController.setOctreeUpdateCallback(new StreamingController.OctreeUpdateCallback() {
            @Override
            public void onNodeUpdated(long nodeId, int newLOD, ByteBuffer data) {
                // Update octree node with new LOD data
                updateOctreeNode(nodeId, newLOD, data);
            }
            
            @Override
            public void onNodeEvicted(long nodeId) {
                // Remove node from octree
                evictOctreeNode(nodeId);
            }
        });
        
        // Initialize GPU resources
        initializeGPUResources();
        
        log.info("VoxelRenderingPipeline initialized with {}x{} resolution, quality level {}",
                config.screenWidth, config.screenHeight, config.initialQualityLevel);
    }
    
    /**
     * Initialize GPU resources for rendering.
     */
    private void initializeGPUResources() {
        log.debug("Initializing GPU resources...");
        
        // Calculate buffer sizes
        long octreeBufferSize = 128 * 1024 * 1024; // 128MB for octree (max allowed by WebGPU)
        long voxelDataBufferSize = 64 * 1024 * 1024; // 64MB for voxel data
        long frameBufferSize = (long)config.screenWidth * config.screenHeight * 4; // RGBA
        long uniformBufferSize = 256; // Small uniform buffer for matrices
        
        // Calculate rays/hits buffer size (one ray and hit per pixel)
        int numRays = config.screenWidth * config.screenHeight;
        // Ray struct: origin (3xf32) + direction (3xf32) + tmin (f32) + tmax (f32) = 32 bytes
        long raysBufferSize = numRays * 32L;
        // Hit struct: hit + distance + position + normal + voxel_value + material_id = 48 bytes
        long hitsBufferSize = numRays * 48L;
        
        // Create buffers matching shader expectations
        octreeBuffer = webgpuContext.createBuffer(octreeBufferSize, 
            GPUBufferManager.BUFFER_USAGE_STORAGE | GPUBufferManager.BUFFER_USAGE_COPY_DST | GPUBufferManager.BUFFER_USAGE_COPY_SRC);
        
        voxelDataBuffer = webgpuContext.createBuffer(voxelDataBufferSize,
            GPUBufferManager.BUFFER_USAGE_STORAGE | GPUBufferManager.BUFFER_USAGE_COPY_DST | GPUBufferManager.BUFFER_USAGE_COPY_SRC);
        
        raysBuffer = webgpuContext.createBuffer(raysBufferSize,
            GPUBufferManager.BUFFER_USAGE_STORAGE | GPUBufferManager.BUFFER_USAGE_COPY_DST | GPUBufferManager.BUFFER_USAGE_COPY_SRC);
        
        hitsBuffer = webgpuContext.createBuffer(hitsBufferSize,
            GPUBufferManager.BUFFER_USAGE_STORAGE | GPUBufferManager.BUFFER_USAGE_COPY_SRC);
        
        // Frame buffer for final output (can be derived from hits buffer)
        frameBuffer = webgpuContext.createBuffer(frameBufferSize,
            GPUBufferManager.BUFFER_USAGE_STORAGE | GPUBufferManager.BUFFER_USAGE_COPY_SRC);
        
        // Separate readback buffer for CPU access (MAP_READ + COPY_DST + COPY_SRC for copy-based reads)
        readbackBuffer = webgpuContext.createBuffer(frameBufferSize,
            GPUBufferManager.BUFFER_USAGE_MAP_READ | GPUBufferManager.BUFFER_USAGE_COPY_DST);
            
        uniformBuffer = webgpuContext.createBuffer(uniformBufferSize,
            GPUBufferManager.BUFFER_USAGE_UNIFORM | GPUBufferManager.BUFFER_USAGE_COPY_DST);
        
        // Initialize rays with screen-space rays
        initializeRays();
        
        // Create compute shader for ray marching using ComputeShaderManager
        var shaderManager = new ComputeShaderManager(webgpuContext);
        try {
            var rayTraversalShader = shaderManager.loadShaderFromResource("/shaders/rendering/ray_traversal.wgsl").get();
            
            // Create proper bind group layout matching the shader
            bindGroupLayout = createBindGroupLayout();
            
            // Create pipeline with the bind group layout
            var pipelineLayout = webgpuContext.getDevice().createPipelineLayout(
                new Device.PipelineLayoutDescriptor()
                    .withLabel("RayTraversalPipelineLayout")
                    .addBindGroupLayout(bindGroupLayout)
            );
            
            computePipeline = webgpuContext.createComputePipeline(rayTraversalShader, "main", pipelineLayout);
            
            // Create bind group with all required buffers
            bindGroup = createBindGroup();
            
        } catch (Exception e) {
            log.error("Failed to create ray traversal compute pipeline", e);
            throw new RuntimeException("Pipeline creation failed", e);
        }
        
        log.debug("GPU resources initialized: {} buffers, 1 compute shader, 1 bind group", 7);
    }
    
    /**
     * Create bind group layout matching the shader's expectations.
     */
    private BindGroupLayout createBindGroupLayout() {
        var device = webgpuContext.getDevice();
        
        return device.createBindGroupLayout(
            new Device.BindGroupLayoutDescriptor()
                .withLabel("RayTraversalBindGroupLayout")
                // @binding(0) rays array - storage buffer (read-only)
                .withEntry(new Device.BindGroupLayoutEntry(0, WebGPUNative.SHADER_STAGE_COMPUTE)
                    .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_READ_ONLY_STORAGE)))
                // @binding(1) hits array - storage buffer (read-write)
                .withEntry(new Device.BindGroupLayoutEntry(1, WebGPUNative.SHADER_STAGE_COMPUTE)
                    .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)))
                // @binding(2) voxel_octree array - storage buffer (read-only)
                .withEntry(new Device.BindGroupLayoutEntry(2, WebGPUNative.SHADER_STAGE_COMPUTE)
                    .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_READ_ONLY_STORAGE)))
                // @binding(3) voxel_data array - storage buffer (read-only)
                .withEntry(new Device.BindGroupLayoutEntry(3, WebGPUNative.SHADER_STAGE_COMPUTE)
                    .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_READ_ONLY_STORAGE)))
                // @binding(4) config uniform - uniform buffer
                .withEntry(new Device.BindGroupLayoutEntry(4, WebGPUNative.SHADER_STAGE_COMPUTE)
                    .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_UNIFORM)))
        );
    }
    
    /**
     * Create bind group with actual buffers.
     */
    private BindGroup createBindGroup() {
        var device = webgpuContext.getDevice();
        
        return device.createBindGroup(
            new Device.BindGroupDescriptor(bindGroupLayout)
                .withLabel("RayTraversalBindGroup")
                .withEntry(new Device.BindGroupEntry(0).withBuffer(raysBuffer, 0, raysBuffer.getSize()))
                .withEntry(new Device.BindGroupEntry(1).withBuffer(hitsBuffer, 0, hitsBuffer.getSize()))
                .withEntry(new Device.BindGroupEntry(2).withBuffer(octreeBuffer, 0, octreeBuffer.getSize()))
                .withEntry(new Device.BindGroupEntry(3).withBuffer(voxelDataBuffer, 0, voxelDataBuffer.getSize()))
                .withEntry(new Device.BindGroupEntry(4).withBuffer(uniformBuffer, 0, 256))
        );
    }
    
    /**
     * Initialize rays buffer with screen-space rays.
     */
    private void initializeRays() {
        int numRays = config.screenWidth * config.screenHeight;
        ByteBuffer raysData = ByteBuffer.allocateDirect(numRays * 32);
        raysData.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        
        // Generate a ray for each pixel
        for (int y = 0; y < config.screenHeight; y++) {
            for (int x = 0; x < config.screenWidth; x++) {
                // Normalize screen coordinates to [-1, 1]
                float ndcX = (2.0f * x / config.screenWidth) - 1.0f;
                float ndcY = 1.0f - (2.0f * y / config.screenHeight); // Flip Y
                
                // Simple orthographic rays for now (can be upgraded to perspective)
                // Origin
                raysData.putFloat(ndcX * 10.0f);  // x
                raysData.putFloat(ndcY * 10.0f);  // y
                raysData.putFloat(-50.0f);        // z (camera distance)
                
                // Direction (pointing forward)
                raysData.putFloat(0.0f);   // x
                raysData.putFloat(0.0f);   // y
                raysData.putFloat(1.0f);   // z
                
                // tmin and tmax
                raysData.putFloat(0.1f);   // tmin
                raysData.putFloat(1000.0f); // tmax
            }
        }
        
        raysData.rewind();
        byte[] raysBytes = new byte[raysData.remaining()];
        raysData.get(raysBytes);
        
        // Upload rays to GPU
        webgpuContext.writeBuffer(raysBuffer, raysBytes, 0);
    }
    
    /**
     * Update the octree data for rendering.
     */
    public CompletableFuture<Void> updateOctreeData(VoxelOctreeNode rootNode) {
        return CompletableFuture.runAsync(() -> {
            try {
                log.debug("Updating octree data...");
                
                // Compress octree
                byte[] compressedData = compressor.compressOctree(rootNode);
                
                // Upload to GPU
                webgpuContext.writeBuffer(octreeBuffer, compressedData, 0);
                
                // Update current octree reference
                currentOctree = rootNode;
                octreeUpdates.incrementAndGet();
                
                log.debug("Octree data updated: {} bytes compressed", compressedData.length);
            } catch (Exception e) {
                log.error("Failed to update octree data", e);
                throw new RuntimeException("Octree update failed", e);
            }
        }, renderExecutor);
    }
    
    /**
     * Render a frame with the current pipeline state.
     */
    public CompletableFuture<RenderedFrame> renderFrame(RenderingState state) {
        // Check if we should skip this frame due to concurrent rendering
        if (config.maxConcurrentFrames > 0 && isRendering.get()) {
            log.debug("Skipping frame {} due to concurrent rendering", state.frameNumber);
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            isRendering.set(true);
            try {
                long startTime = System.nanoTime();
                
                // Update uniforms
                updateUniforms(state);
                
                // Dispatch compute shader for ray marching with bind groups
                // Calculate total rays and workgroups for 1D dispatch
                int totalRays = config.screenWidth * config.screenHeight;
                int workgroupsX = (totalRays + 63) / 64;  // 64 is WORKGROUP_SIZE from shader
                int workgroupsY = 1;  // 1D dispatch since shader uses 1D workgroup
                int workgroupsZ = 1;
                
                // Use the overload that accepts bind groups
                webgpuContext.dispatchCompute(computePipeline, bindGroup, workgroupsX, workgroupsY, workgroupsZ);
                
                // Copy frameBuffer to readbackBuffer for CPU access
                long bufferSize = (long)config.screenWidth * config.screenHeight * 4;
                webgpuContext.copyBuffer(frameBuffer, readbackBuffer, bufferSize);
                
                // Wait for GPU operations to complete before reading buffer
                try {
                    webgpuContext.waitIdle().get(2, TimeUnit.SECONDS);
                } catch (InterruptedException | java.util.concurrent.ExecutionException | TimeoutException e) {
                    log.warn("GPU synchronization timeout or error", e);
                }
                
                // Read back rendered frame from readback buffer using direct mapping
                // Since readbackBuffer has MAP_READ, use direct mapping instead of copy-based read
                byte[] imageData;
                try {
                    var mappedSegment = readbackBuffer.mapAsync(Buffer.MapMode.READ, 0, bufferSize).get();
                    var byteBuffer = mappedSegment.asByteBuffer();
                    imageData = new byte[(int) bufferSize];
                    byteBuffer.get(imageData);
                    readbackBuffer.unmap();
                } catch (Exception e) {
                    log.warn("Failed to map readback buffer, using fallback", e);
                    imageData = webgpuContext.readBuffer(readbackBuffer, bufferSize, 0);
                }
                
                long renderTime = System.nanoTime() - startTime;
                
                // Update performance tracking
                totalFramesRendered.incrementAndGet();
                totalRenderTimeNanos.addAndGet(renderTime);
                frameTimes.offer(renderTime);
                while (frameTimes.size() > 100) { // Keep last 100 frame times
                    frameTimes.poll();
                }
                
                // Adaptive quality control
                if (config.enableAdaptiveQuality) {
                    adjustQualityLevel(renderTime);
                }
                
                // Check for async streaming and update camera state
                if (config.enableAsyncStreaming && streamingIO.isStreamingEnabled()) {
                    // Update streaming controller with camera state
                    streamingController.updateCameraState(state.cameraPosition, 
                        calculateCameraVelocity(state));
                    
                    // Trigger streaming for visible nodes based on quality requirements
                    triggerStreamingForVisibleNodes(state);
                    
                    log.trace("Async streaming updated for frame {}", state.frameNumber);
                }
                
                log.trace("Frame {} rendered in {:.2f}ms at quality level {}",
                        state.frameNumber, renderTime / 1_000_000.0, currentQualityLevel.get());
                
                return new RenderedFrame(
                    state.frameNumber,
                    config.screenWidth,
                    config.screenHeight,
                    imageData,
                    renderTime,
                    currentQualityLevel.get()
                );
                
            } finally {
                isRendering.set(false);
            }
        }, renderExecutor);
    }
    
    /**
     * Get current performance metrics.
     */
    public PerformanceMetrics getPerformanceMetrics() {
        long frames = totalFramesRendered.get();
        if (frames == 0) {
            return new PerformanceMetrics(0, 0, 0, currentQualityLevel.get(), 0);
        }
        
        double avgTimeNanos = (double)totalRenderTimeNanos.get() / frames;
        double avgTimeMs = avgTimeNanos / 1_000_000.0;
        
        // Calculate standard deviation
        double variance = 0;
        int count = 0;
        for (Long time : frameTimes) {
            double diff = (time - avgTimeNanos) / 1_000_000.0;
            variance += diff * diff;
            count++;
        }
        double stdDev = count > 0 ? Math.sqrt(variance / count) : 0;
        
        return new PerformanceMetrics(
            frames,
            avgTimeMs,
            octreeUpdates.get(),
            currentQualityLevel.get(),
            stdDev
        );
    }
    
    /**
     * Set the quality level directly.
     */
    public void setQualityLevel(int level) {
        level = Math.max(config.minQualityLevel, Math.min(config.maxQualityLevel, level));
        currentQualityLevel.set(level);
        log.info("Quality level set to {}", level);
    }
    
    /**
     * Enable or disable adaptive quality control.
     */
    public void setAdaptiveQualityEnabled(boolean enabled) {
        config.enableAdaptiveQuality = enabled;
        log.debug("Adaptive quality control {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Initialize the pipeline (for compatibility).
     */
    public void initialize() {
        // Already initialized in constructor
        log.debug("Pipeline already initialized");
    }
    
    @Override
    public void close() {
        log.info("Closing VoxelRenderingPipeline...");
        
        // Shutdown streaming controller
        if (streamingController != null) {
            try {
                streamingController.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down streaming controller", e);
            }
        }
        
        // Shutdown executor
        renderExecutor.shutdown();
        try {
            if (!renderExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                renderExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            renderExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Release GPU resources
        try {
            if (octreeBuffer != null) {
                octreeBuffer.close();
            }
            if (voxelDataBuffer != null) {
                voxelDataBuffer.close();
            }
            if (raysBuffer != null) {
                raysBuffer.close();
            }
            if (hitsBuffer != null) {
                hitsBuffer.close();
            }
            if (frameBuffer != null) {
                frameBuffer.close();
            }
            if (readbackBuffer != null) {
                readbackBuffer.close();
            }
            if (uniformBuffer != null) {
                uniformBuffer.close();
            }
            if (bindGroup != null) {
                bindGroup.close();
            }
            if (bindGroupLayout != null) {
                bindGroupLayout.close();
            }
            if (computePipeline != null) {
                computePipeline.close();
            }
        } catch (Exception e) {
            log.warn("Error releasing GPU resources", e);
        }
        
        // Shutdown WebGPU backend if it has a shutdown method
        if (webgpuContext != null) {
            try {
                // Check if WebGPUContext has a close or shutdown method
                webgpuContext.waitIdle().get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("Could not wait for WebGPU idle", e);
            }
        }
        
        log.info("VoxelRenderingPipeline closed");
    }
    
    /**
     * Update uniform buffer with rendering state.
     */
    private void updateUniforms(RenderingState state) {
        // Pack uniforms into buffer
        ByteBuffer uniforms = ByteBuffer.allocateDirect(256);
        uniforms.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        
        // Matrices and vectors (floats)
        uniforms.asFloatBuffer()
            .put(state.viewMatrix)
            .put(state.projectionMatrix)
            .put(state.cameraPosition)
            .put(state.lightDirection)
            .put(state.ambientLight);
        
        // Move to position after the floats (4x4 + 4x4 + 3 + 3 + 1 = 39 floats = 156 bytes)
        uniforms.position(156);
        
        // LOD as i32 and screen dimensions as u32
        uniforms.putInt(state.currentLOD);
        uniforms.putInt(config.screenWidth);
        uniforms.putInt(config.screenHeight);
        
        uniforms.rewind();
        
        // Convert ByteBuffer to byte array for writeBuffer
        byte[] uniformBytes = new byte[uniforms.remaining()];
        uniforms.get(uniformBytes);
        webgpuContext.writeBuffer(uniformBuffer, uniformBytes, 0);
    }
    
    /**
     * Adjust quality level based on frame time.
     */
    private void adjustQualityLevel(long frameTimeNanos) {
        long frameTimeMs = frameTimeNanos / 1_000_000;
        
        if (frameTimeMs > config.targetFrameTimeMs * 1.5 && 
            currentQualityLevel.get() > config.minQualityLevel) {
            // Decrease quality if frame time is too high
            currentQualityLevel.decrementAndGet();
            log.debug("Decreased quality level to {}", currentQualityLevel.get());
        } else if (frameTimeMs < config.targetFrameTimeMs * 0.8 && 
                   currentQualityLevel.get() < config.maxQualityLevel) {
            // Increase quality if we have headroom
            currentQualityLevel.incrementAndGet();
            log.debug("Increased quality level to {}", currentQualityLevel.get());
        }
    }
    
    /**
     * Update a specific octree node with new LOD data.
     */
    private void updateOctreeNode(long nodeId, int newLOD, ByteBuffer data) {
        // In a real implementation, this would:
        // 1. Find the node in the octree
        // 2. Update its data with the new LOD
        // 3. Mark GPU buffer regions for update
        log.debug("Updated octree node {} to LOD {}", nodeId, newLOD);
    }
    
    /**
     * Evict a node from the octree.
     */
    private void evictOctreeNode(long nodeId) {
        // In a real implementation, this would:
        // 1. Remove the node data
        // 2. Replace with lower LOD or placeholder
        log.debug("Evicted octree node {}", nodeId);
    }
    
    /**
     * Calculate camera velocity from rendering state.
     */
    private float[] calculateCameraVelocity(RenderingState state) {
        // In a real implementation, this would track position changes
        // For now, return zero velocity
        return new float[]{0, 0, 0};
    }
    
    /**
     * Trigger streaming for visible nodes.
     */
    private void triggerStreamingForVisibleNodes(RenderingState state) {
        if (currentOctree == null) {
            return;
        }
        
        // In a real implementation, this would:
        // 1. Perform frustum culling to find visible nodes
        // 2. Calculate required LOD for each visible node
        // 3. Request streaming for nodes that need higher LOD
        
        // For now, just demonstrate the streaming request pattern
        int targetLOD = currentQualityLevel.get();
        
        // Example: Request streaming for root node
        streamingController.requestNodeStreaming(
            0, // nodeId
            new float[]{0, 0, 0}, // node position
            targetLOD
        ).thenAccept(result -> {
            log.trace("Streaming completed for node {} at LOD {}", 
                     result.nodeId, result.loadedLOD);
        });
    }
    
}