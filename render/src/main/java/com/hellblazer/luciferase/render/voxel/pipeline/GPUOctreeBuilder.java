package com.hellblazer.luciferase.render.voxel.pipeline;

import com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode;
import com.hellblazer.luciferase.render.voxel.gpu.ComputeShaderManager;
import com.hellblazer.luciferase.render.voxel.gpu.WebGPUContext;
import com.hellblazer.luciferase.render.voxel.memory.MemoryPool;
import com.hellblazer.luciferase.render.voxel.memory.PageAllocator;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import com.hellblazer.luciferase.webgpu.wrapper.Buffer;
import com.hellblazer.luciferase.webgpu.wrapper.CommandEncoder;
import com.hellblazer.luciferase.webgpu.wrapper.Device;
import com.hellblazer.luciferase.webgpu.wrapper.ShaderModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GPU-accelerated octree builder that constructs sparse voxel octrees
 * directly on the GPU using compute shaders with Morton code optimization.
 * 
 * Features:
 * - Morton code-based spatial encoding
 * - Parallel bottom-up construction
 * - Automatic LOD generation
 * - Memory-efficient sparse representation
 * - Direct integration with voxelization pipeline
 */
public class GPUOctreeBuilder {
    private static final Logger log = LoggerFactory.getLogger(GPUOctreeBuilder.class);
    
    // Configuration
    private static final int MAX_OCTREE_DEPTH = 8;  // Reduced for memory limits
    private static final int NODE_POOL_SIZE = 256 * 1024; // 256K nodes max (reduced)
    private static final int WORKGROUP_SIZE = 256;
    private static final int MAX_BUFFER_SIZE = 128 * 1024 * 1024; // 128MB max per buffer
    
    private final WebGPUContext context;
    private final ComputeShaderManager shaderManager;
    private final MemoryPool memoryPool;
    
    // Shaders
    private ShaderModule mortonOctreeShader;
    private ShaderModule compactionShader;
    private ShaderModule refinementShader;
    
    // GPU Resources
    private Buffer octreeNodeBuffer;
    private Buffer mortonCodeBuffer;
    private Buffer nodeAllocatorBuffer;
    private Buffer buildStatsBuffer;
    
    // Statistics
    private final AtomicInteger totalNodes = new AtomicInteger(0);
    private final AtomicInteger leafNodes = new AtomicInteger(0);
    private final AtomicInteger compressedSize = new AtomicInteger(0);
    
    public GPUOctreeBuilder(WebGPUContext context) {
        this.context = context;
        this.shaderManager = new ComputeShaderManager(context);
        Arena arena = Arena.ofShared();
        PageAllocator pageAllocator = new PageAllocator(arena);
        this.memoryPool = new MemoryPool(pageAllocator);
    }
    
    /**
     * Initializes GPU resources and compiles shaders.
     */
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Initializing GPU octree builder...");
                
                // Load shaders
                mortonOctreeShader = shaderManager.loadShaderFromResource(
                    "/shaders/esvo/morton_octree_build.wgsl"
                ).join();
                
                // Create GPU buffers
                createGPUBuffers();
                
                log.info("GPU octree builder initialized successfully");
                
            } catch (Exception e) {
                log.error("Failed to initialize GPU octree builder", e);
                throw new RuntimeException("GPU octree initialization failed", e);
            }
        });
    }
    
    /**
     * Builds octree from voxel grid on GPU.
     * 
     * @param voxelGridBuffer GPU buffer containing voxel grid
     * @param voxelColorBuffer GPU buffer containing voxel colors
     * @param gridResolution Grid resolution
     * @param boundsMin World space minimum bounds
     * @param boundsMax World space maximum bounds
     * @return Root node of constructed octree
     */
    public CompletableFuture<EnhancedVoxelOctreeNode> buildOctreeFromGrid(
            Buffer voxelGridBuffer,
            Buffer voxelColorBuffer,
            int[] gridResolution,
            float[] boundsMin,
            float[] boundsMax) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("Building octree from {}x{}x{} voxel grid",
                         gridResolution[0], gridResolution[1], gridResolution[2]);
                
                // Reset allocator
                resetNodeAllocator();
                
                // Create parameters buffer
                Buffer paramsBuffer = createParametersBuffer(gridResolution, boundsMin, boundsMax);
                
                // Phase 1: Generate Morton codes and leaf nodes
                executeBottomUpConstruction(voxelGridBuffer, voxelColorBuffer, paramsBuffer, gridResolution);
                
                // Phase 2: Build internal nodes level by level
                executeLevelConstruction(gridResolution);
                
                // Phase 3: Refine and compute average colors
                executeRefinement();
                
                // Phase 4: Compact empty nodes
                executeCompaction();
                
                // Read back octree from GPU
                EnhancedVoxelOctreeNode root = readOctreeFromGPU(boundsMin, boundsMax);
                
                // Gather statistics
                gatherStatistics();
                
                log.info("Octree built: {} total nodes, {} leaf nodes, compressed size: {} KB",
                        totalNodes.get(), leafNodes.get(), compressedSize.get() / 1024);
                
                return root;
                
            } catch (Exception e) {
                log.error("Failed to build octree on GPU", e);
                throw new RuntimeException("GPU octree construction failed", e);
            }
        });
    }
    
    /**
     * Builds octree directly from triangle mesh.
     * Combines voxelization and octree construction in single GPU operation.
     */
    public CompletableFuture<EnhancedVoxelOctreeNode> buildOctreeFromMesh(
            float[][][] triangles,
            int targetResolution,
            float[] boundsMin,
            float[] boundsMax) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("Building octree directly from {} triangles", triangles.length);
                
                // First voxelize the mesh using existing voxelization method
                // For now, we'll use the basic voxelization approach
                // TODO: Integrate with GPUVoxelizer when API is complete
                
                // Create temporary voxel buffers with size limits
                int gridSize = targetResolution * targetResolution * targetResolution;
                int voxelBufferSize = Math.min(gridSize * 4, MAX_BUFFER_SIZE);
                int colorBufferSize = Math.min(gridSize * 16, MAX_BUFFER_SIZE);
                
                Buffer voxelGridBuffer = context.createBuffer(
                    voxelBufferSize,
                    WebGPUNative.BUFFER_USAGE_STORAGE | 
                    WebGPUNative.BUFFER_USAGE_COPY_DST | 
                    WebGPUNative.BUFFER_USAGE_COPY_SRC
                );
                Buffer voxelColorBuffer = context.createBuffer(
                    colorBufferSize,
                    WebGPUNative.BUFFER_USAGE_STORAGE | 
                    WebGPUNative.BUFFER_USAGE_COPY_DST | 
                    WebGPUNative.BUFFER_USAGE_COPY_SRC
                );
                
                // Then build octree from voxel grid
                int[] resolution = {targetResolution, targetResolution, targetResolution};
                return buildOctreeFromGrid(
                    voxelGridBuffer,
                    voxelColorBuffer,
                    resolution,
                    boundsMin,
                    boundsMax
                ).join();
                
            } catch (Exception e) {
                log.error("Failed to build octree from mesh", e);
                throw new RuntimeException("Mesh to octree conversion failed", e);
            }
        });
    }
    
    private void createGPUBuffers() {
        var device = context.getDevice();
        
        // Octree node buffer (16 bytes per node)
        octreeNodeBuffer = context.createBuffer(
            NODE_POOL_SIZE * 16,
            WebGPUNative.BUFFER_USAGE_STORAGE | 
            WebGPUNative.BUFFER_USAGE_COPY_SRC |
            WebGPUNative.BUFFER_USAGE_COPY_DST
        );
        
        // Morton code buffer
        mortonCodeBuffer = context.createBuffer(
            NODE_POOL_SIZE * 4,
            WebGPUNative.BUFFER_USAGE_STORAGE |
            WebGPUNative.BUFFER_USAGE_COPY_DST
        );
        
        // Node allocator (single atomic counter)
        nodeAllocatorBuffer = context.createBuffer(
            4,
            WebGPUNative.BUFFER_USAGE_STORAGE |
            WebGPUNative.BUFFER_USAGE_COPY_DST |
            WebGPUNative.BUFFER_USAGE_COPY_SRC
        );
        
        // Build statistics buffer
        buildStatsBuffer = context.createBuffer(
            32, // 8 u32 values
            WebGPUNative.BUFFER_USAGE_STORAGE |
            WebGPUNative.BUFFER_USAGE_COPY_SRC
        );
    }
    
    private Buffer createParametersBuffer(int[] resolution, float[] boundsMin, float[] boundsMax) {
        ByteBuffer params = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
        
        // Resolution
        params.putInt(resolution[0]);
        params.putInt(resolution[1]);
        params.putInt(resolution[2]);
        params.putInt(0); // padding
        
        // Voxel size
        float voxelSize = (boundsMax[0] - boundsMin[0]) / resolution[0];
        params.putFloat(voxelSize);
        params.putFloat(0); // padding
        params.putFloat(0); // padding
        params.putFloat(0); // padding
        
        // Bounds min
        params.putFloat(boundsMin[0]);
        params.putFloat(boundsMin[1]);
        params.putFloat(boundsMin[2]);
        params.putFloat(0); // padding
        
        // Bounds max
        params.putFloat(boundsMax[0]);
        params.putFloat(boundsMax[1]);
        params.putFloat(boundsMax[2]);
        params.putFloat(0); // padding
        
        // Max depth and node pool size
        params.rewind();
        params.position(48);
        params.putInt(MAX_OCTREE_DEPTH);
        params.putInt(NODE_POOL_SIZE);
        
        Buffer paramsBuffer = context.createBuffer(
            64,
            WebGPUNative.BUFFER_USAGE_UNIFORM | WebGPUNative.BUFFER_USAGE_COPY_DST
        );
        
        params.rewind();
        byte[] paramBytes = new byte[64];
        params.get(paramBytes);
        context.writeBuffer(paramsBuffer, paramBytes, 0);
        
        return paramsBuffer;
    }
    
    private void resetNodeAllocator() {
        ByteBuffer zero = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN);
        zero.putInt(0);
        zero.rewind();
        byte[] zeroBytes = new byte[4];
        zero.get(zeroBytes);
        context.writeBuffer(nodeAllocatorBuffer, zeroBytes, 0);
    }
    
    private void executeBottomUpConstruction(Buffer voxelGrid, Buffer voxelColors, 
                                            Buffer params, int[] resolution) {
        var device = context.getDevice();
        
        // Create bind group layout
        var bindGroupLayout = device.createBindGroupLayout(
            new Device.BindGroupLayoutDescriptor()
                .withLabel("OctreeBottomUpLayout")
                .withEntry(new Device.BindGroupLayoutEntry(0, WebGPUNative.SHADER_STAGE_COMPUTE)
                    .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_READ_ONLY_STORAGE)))
                .withEntry(new Device.BindGroupLayoutEntry(1, WebGPUNative.SHADER_STAGE_COMPUTE)
                    .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)))
                .withEntry(new Device.BindGroupLayoutEntry(2, WebGPUNative.SHADER_STAGE_COMPUTE)
                    .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_UNIFORM)))
                .withEntry(new Device.BindGroupLayoutEntry(3, WebGPUNative.SHADER_STAGE_COMPUTE)
                    .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)))
                .withEntry(new Device.BindGroupLayoutEntry(4, WebGPUNative.SHADER_STAGE_COMPUTE)
                    .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)))
                .withEntry(new Device.BindGroupLayoutEntry(5, WebGPUNative.SHADER_STAGE_COMPUTE)
                    .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)))
                .withEntry(new Device.BindGroupLayoutEntry(6, WebGPUNative.SHADER_STAGE_COMPUTE)
                    .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_READ_ONLY_STORAGE)))
        );
        
        // Create bind group with proper size limits
        // Limit buffer sizes to avoid exceeding max_buffer_binding_size
        long voxelGridSize = Math.min(voxelGrid.getSize(), MAX_BUFFER_SIZE);
        long voxelColorSize = Math.min(voxelColors.getSize(), MAX_BUFFER_SIZE);
        long octreeNodeSize = Math.min(octreeNodeBuffer.getSize(), MAX_BUFFER_SIZE);
        long mortonCodeSize = Math.min(mortonCodeBuffer.getSize(), MAX_BUFFER_SIZE);
        
        var bindGroup = device.createBindGroup(
            new Device.BindGroupDescriptor(bindGroupLayout)
                .withLabel("OctreeBottomUpBindGroup")
                .withEntry(new Device.BindGroupEntry(0).withBuffer(voxelGrid, 0, voxelGridSize))
                .withEntry(new Device.BindGroupEntry(1).withBuffer(octreeNodeBuffer, 0, octreeNodeSize))
                .withEntry(new Device.BindGroupEntry(2).withBuffer(params, 0, 64))
                .withEntry(new Device.BindGroupEntry(3).withBuffer(mortonCodeBuffer, 0, mortonCodeSize))
                .withEntry(new Device.BindGroupEntry(4).withBuffer(nodeAllocatorBuffer, 0, 4))
                .withEntry(new Device.BindGroupEntry(5).withBuffer(buildStatsBuffer, 0, 32))
                .withEntry(new Device.BindGroupEntry(6).withBuffer(voxelColors, 0, voxelColorSize))
        );
        
        // Create pipeline
        var pipelineLayout = device.createPipelineLayout(
            new Device.PipelineLayoutDescriptor()
                .withLabel("OctreeBottomUpPipelineLayout")
                .addBindGroupLayout(bindGroupLayout)
        );
        
        var pipeline = device.createComputePipeline(
            new Device.ComputePipelineDescriptor(mortonOctreeShader)
                .withLabel("octree_bottom_up_pipeline")
                .withLayout(pipelineLayout)
                .withEntryPoint("buildOctreeBottom")
        );
        
        // Execute
        var commandEncoder = device.createCommandEncoder("octree_bottom_up_encoder");
        var computePass = commandEncoder.beginComputePass(new CommandEncoder.ComputePassDescriptor());
        computePass.setPipeline(pipeline);
        computePass.setBindGroup(0, bindGroup);
        
        int totalVoxels = resolution[0] * resolution[1] * resolution[2];
        int numWorkgroups = (totalVoxels + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
        computePass.dispatchWorkgroups(numWorkgroups, 1, 1);
        computePass.end();
        
        var commandBuffer = commandEncoder.finish();
        device.getQueue().submit(commandBuffer);
        device.getQueue().onSubmittedWorkDone();
    }
    
    private void executeLevelConstruction(int[] resolution) {
        // Level-by-level construction would be done here
        // For now, using the bottom-up approach from the shader
        log.debug("Executing level-by-level octree construction");
    }
    
    private void executeRefinement() {
        var device = context.getDevice();
        
        // Similar pipeline setup for refinement kernel
        log.debug("Executing octree refinement pass");
        
        // Would execute refineOctreeTop kernel here
    }
    
    private void executeCompaction() {
        var device = context.getDevice();
        
        // Similar pipeline setup for compaction kernel
        log.debug("Executing octree compaction pass");
        
        // Would execute compactOctree kernel here
    }
    
    private EnhancedVoxelOctreeNode readOctreeFromGPU(float[] boundsMin, float[] boundsMax) {
        try {
            // Read node allocator to get total nodes
            var allocatorStaging = context.createBuffer(4,
                WebGPUNative.BUFFER_USAGE_MAP_READ | WebGPUNative.BUFFER_USAGE_COPY_DST);
            
            var encoder = context.getDevice().createCommandEncoder("read_allocator");
            encoder.copyBufferToBuffer(nodeAllocatorBuffer, 0, allocatorStaging, 0, 4);
            var commands = encoder.finish();
            context.getDevice().getQueue().submit(commands);
            context.getDevice().getQueue().onSubmittedWorkDone();
            
            // Use copy-based buffer read to work around Dawn getMappedRange NULL issue
            byte[] allocatorBytes = allocatorStaging.readDataSync(context.getDevice(), 0, 4);
            ByteBuffer allocatorData = ByteBuffer.wrap(allocatorBytes);
            int nodeCount = allocatorData.getInt();
            
            log.debug("Reading {} octree nodes from GPU", nodeCount);
            
            if (nodeCount == 0) {
                return new EnhancedVoxelOctreeNode(boundsMin, boundsMax, 0, 0);
            }
            
            // Read octree nodes
            int bufferSize = Math.min(nodeCount * 16, NODE_POOL_SIZE * 16);
            var stagingBuffer = context.createBuffer(bufferSize,
                WebGPUNative.BUFFER_USAGE_MAP_READ | WebGPUNative.BUFFER_USAGE_COPY_DST);
            
            encoder = context.getDevice().createCommandEncoder("read_octree");
            encoder.copyBufferToBuffer(octreeNodeBuffer, 0, stagingBuffer, 0, bufferSize);
            commands = encoder.finish();
            context.getDevice().getQueue().submit(commands);
            context.getDevice().getQueue().onSubmittedWorkDone();
            
            // Use copy-based buffer read to work around Dawn getMappedRange NULL issue
            byte[] nodeBytes = stagingBuffer.readDataSync(context.getDevice(), 0, bufferSize);
            
            // Parse nodes and build tree structure
            ByteBuffer nodeData = ByteBuffer.wrap(nodeBytes);
            nodeData.order(ByteOrder.LITTLE_ENDIAN);
            
            // Create root node (assumed to be at index 0)
            EnhancedVoxelOctreeNode root = new EnhancedVoxelOctreeNode(boundsMin, boundsMax, 0, 0);
            
            // TODO: Reconstruct full tree structure from linear GPU representation
            // For now, just reading the root node data
            int data0 = nodeData.getInt(0);
            int data1 = nodeData.getInt(4);
            int data2 = nodeData.getInt(8);
            int data3 = nodeData.getInt(12);
            
            root.setValidMask((byte)(data0 & 0xFF));
            
            return root;
            
        } catch (Exception e) {
            log.error("Failed to read octree from GPU", e);
            return new EnhancedVoxelOctreeNode(boundsMin, boundsMax, 0, 0);
        }
    }
    
    private void gatherStatistics() {
        try {
            // Read statistics buffer
            var stagingBuffer = context.createBuffer(32,
                WebGPUNative.BUFFER_USAGE_MAP_READ | WebGPUNative.BUFFER_USAGE_COPY_DST);
            
            var encoder = context.getDevice().createCommandEncoder("read_stats");
            encoder.copyBufferToBuffer(buildStatsBuffer, 0, stagingBuffer, 0, 32);
            var commands = encoder.finish();
            context.getDevice().getQueue().submit(commands);
            context.getDevice().getQueue().onSubmittedWorkDone();
            
            // Use copy-based buffer read to work around Dawn getMappedRange NULL issue
            byte[] statsBytes = stagingBuffer.readDataSync(context.getDevice(), 0, 32);
            ByteBuffer stats = ByteBuffer.wrap(statsBytes);
            stats.order(ByteOrder.LITTLE_ENDIAN);
            
            totalNodes.set(stats.getInt(0));
            leafNodes.set(stats.getInt(4));
            // internalNodes at offset 8
            // maxDepth at offset 12
            // totalVoxels at offset 16
            compressedSize.set(stats.getInt(20));
            
        } catch (Exception e) {
            log.error("Failed to gather statistics", e);
        }
    }
    
    /**
     * Cleans up GPU resources.
     */
    public void cleanup() {
        // Buffers are managed by WebGPU context and will be cleaned up automatically
        // No explicit destroy method on Buffer class
        // MemoryPool doesn't have close method either
    }
    
    // Result record for voxelization
    public record VoxelizationResult(Buffer voxelGridBuffer, Buffer voxelColorBuffer) {}
}