package com.hellblazer.luciferase.render.voxel;

import com.hellblazer.luciferase.render.voxel.gpu.ComputeShaderManager;
import com.hellblazer.luciferase.render.voxel.gpu.WebGPUContext;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import com.hellblazer.luciferase.webgpu.wrapper.Buffer;
import com.hellblazer.luciferase.webgpu.wrapper.BindGroup;
import com.hellblazer.luciferase.webgpu.wrapper.BindGroupLayout;
import com.hellblazer.luciferase.webgpu.wrapper.ComputePipeline;
import com.hellblazer.luciferase.webgpu.wrapper.Device;
import com.hellblazer.luciferase.webgpu.wrapper.ShaderModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Voxel rendering pipeline using WebGPU FFM.
 * Handles voxelization, octree traversal, and rendering.
 */
public class VoxelRenderPipeline {
    private static final Logger log = LoggerFactory.getLogger(VoxelRenderPipeline.class);
    
    private final WebGPUContext context;
    private ShaderModule voxelizeShader;
    private ShaderModule traversalShader;
    private ShaderModule renderShader;
    private Buffer voxelBuffer;
    private Buffer octreeBuffer;
    private Buffer uniformBuffer;
    
    // Compute pipelines
    private ComputePipeline voxelizationPipeline;
    private ComputePipeline octreePipeline;
    
    // Bind groups
    private BindGroupLayout bindGroupLayout;
    private BindGroup bindGroup;
    
    // Pipeline configuration
    private int voxelResolution = 256;
    private int maxOctreeDepth = 8;
    private long voxelBufferSize;
    private long octreeBufferSize;
    
    public VoxelRenderPipeline(WebGPUContext context) {
        this.context = context;
        calculateBufferSizes();
    }
    
    /**
     * Initialize the voxel rendering pipeline.
     */
    public CompletableFuture<Boolean> initialize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Initializing voxel render pipeline...");
                
                // Create shaders
                createShaders();
                
                // Create buffers
                createBuffers();
                
                // Create pipeline layouts
                createPipelineLayouts();
                
                log.info("Voxel render pipeline initialized successfully");
                return true;
                
            } catch (Exception e) {
                log.error("Failed to initialize voxel render pipeline", e);
                return false;
            }
        });
    }
    
    private void calculateBufferSizes() {
        // Voxel buffer: resolution^3 * 4 bytes per voxel
        voxelBufferSize = (long) Math.pow(voxelResolution, 3) * 4;
        
        // Octree buffer: estimate based on sparse representation
        // Typically 10-20% of full voxel grid
        octreeBufferSize = voxelBufferSize / 5;
        
        log.debug("Voxel buffer size: {} MB", voxelBufferSize / (1024 * 1024));
        log.debug("Octree buffer size: {} MB", octreeBufferSize / (1024 * 1024));
    }
    
    private void createShaders() {
        log.debug("Creating voxel shaders from resources...");
        
        try {
            // Create shader manager
            var shaderManager = new ComputeShaderManager(context);
            
            // Load shaders from resources - these have proper implementations
            var voxelizeFuture = shaderManager.loadShaderFromResource("/shaders/esvo/voxelization.wgsl");
            var octreeFuture = shaderManager.loadShaderFromResource("/shaders/esvo/sparse_octree.wgsl");
            var rayMarchFuture = shaderManager.loadShaderFromResource("/shaders/esvo/ray_marching.wgsl");
            
            // Wait for all shaders to load
            voxelizeShader = voxelizeFuture.get();
            traversalShader = octreeFuture.get();
            renderShader = rayMarchFuture.get();
            
            log.debug("Loaded {} shaders from resources", 3);
        } catch (Exception e) {
            log.error("Failed to load shaders from resources", e);
            throw new RuntimeException("Shader loading failed", e);
        }
    }
    
    private void createBuffers() {
        log.debug("Creating voxel buffers...");
        
        // Voxel storage buffer
        voxelBuffer = context.createBuffer(
            voxelBufferSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | 
            WebGPUNative.BUFFER_USAGE_COPY_DST
        );
        
        // Octree storage buffer
        octreeBuffer = context.createBuffer(
            octreeBufferSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | 
            WebGPUNative.BUFFER_USAGE_COPY_SRC
        );
        
        // Uniform buffer for parameters
        uniformBuffer = context.createBuffer(
            256, // Size for uniform data
            WebGPUNative.BUFFER_USAGE_UNIFORM | 
            WebGPUNative.BUFFER_USAGE_COPY_DST
        );
        
        log.debug("Created {} buffers", 3);
    }
    
    private void createPipelineLayouts() {
        log.debug("Creating pipeline layouts...");
        
        // Create compute shader manager
        var shaderManager = new ComputeShaderManager(context);
        
        try {
            // Create compute pipelines using the already loaded shaders
            voxelizationPipeline = shaderManager.createComputePipeline(
                "voxelization_pipeline",
                voxelizeShader,
                "main"
            );
            
            octreePipeline = shaderManager.createComputePipeline(
                "octree_pipeline",
                traversalShader,
                "main"
            );
            
            // Create bind group layout for buffers
            createBindGroups();
            
            log.debug("Pipeline layouts created with compute pipelines");
        } catch (Exception e) {
            log.error("Failed to create pipeline layouts", e);
            throw new RuntimeException("Pipeline creation failed", e);
        }
    }
    
    private void createBindGroups() {
        // Create bind group layout descriptor
        var layoutDesc = new Device.BindGroupLayoutDescriptor()
            .withLabel("VoxelBindGroupLayout");
        
        // Add buffer bindings using proper API
        // Create bind group layout entries for each buffer
        var entry0 = new Device.BindGroupLayoutEntry(0, WebGPUNative.SHADER_STAGE_COMPUTE)
            .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE));
        var entry1 = new Device.BindGroupLayoutEntry(1, WebGPUNative.SHADER_STAGE_COMPUTE)
            .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE));
        var entry2 = new Device.BindGroupLayoutEntry(2, WebGPUNative.SHADER_STAGE_COMPUTE)
            .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_UNIFORM));
        
        layoutDesc.withEntry(entry0);
        layoutDesc.withEntry(entry1);
        layoutDesc.withEntry(entry2);
        
        // Create bind group layout
        bindGroupLayout = context.getDevice().createBindGroupLayout(layoutDesc);
        
        // Create bind group with proper constructor
        var bindGroupDesc = new Device.BindGroupDescriptor(bindGroupLayout)
            .withLabel("VoxelBindGroup");
        
        // Add buffer entries with proper API
        bindGroupDesc.withEntry(new Device.BindGroupEntry(0).withBuffer(voxelBuffer, 0, voxelBuffer.getSize()));
        bindGroupDesc.withEntry(new Device.BindGroupEntry(1).withBuffer(octreeBuffer, 0, octreeBuffer.getSize()));
        bindGroupDesc.withEntry(new Device.BindGroupEntry(2).withBuffer(uniformBuffer, 0, uniformBuffer.getSize()));
        
        bindGroup = context.getDevice().createBindGroup(bindGroupDesc);
    }
    
    
    /**
     * Voxelize a mesh into the voxel grid.
     * 
     * @param vertices the mesh vertices
     * @return a future that completes when voxelization is done
     */
    public CompletableFuture<Void> voxelizeMesh(FloatBuffer vertices) {
        return CompletableFuture.runAsync(() -> {
            log.debug("Voxelizing mesh with {} vertices", vertices.remaining() / 3);
            
            // Upload vertex data
            ByteBuffer vertexData = ByteBuffer.allocateDirect(vertices.remaining() * 4);
            vertexData.asFloatBuffer().put(vertices);
            vertexData.flip();
            
            // Create temporary vertex buffer
            var vertexBuffer = context.createBuffer(
                vertexData.remaining(),
                WebGPUNative.BUFFER_USAGE_STORAGE | 
                WebGPUNative.BUFFER_USAGE_COPY_DST
            );
            
            // Upload data - handle both direct and array-backed buffers
            byte[] data;
            if (vertexData.hasArray()) {
                data = vertexData.array();
            } else {
                data = new byte[vertexData.remaining()];
                vertexData.get(data);
                vertexData.rewind();
            }
            context.writeBuffer(vertexBuffer, data, 0);
            
            // Execute voxelization compute shader
            int workgroups = (vertices.remaining() / 3 + 63) / 64;
            
            // Dispatch compute shader with bind group
            context.dispatchCompute(voxelizationPipeline, bindGroup, workgroups, 1, 1);
            
            // Submit commands and wait for completion
            context.waitIdle().join();
            
            // Clean up temporary buffer
            vertexBuffer.close();
            
            log.debug("Voxelization complete");
        });
    }
    
    /**
     * Build octree from voxel data.
     * 
     * @return a future that completes when octree is built
     */
    public CompletableFuture<Void> buildOctree() {
        return CompletableFuture.runAsync(() -> {
            log.debug("Building octree from voxel data...");
            
            // Execute octree construction compute shader
            int nodeCount = (int) (voxelBufferSize / 4 / 8); // Estimate
            int workgroups = (nodeCount + 255) / 256;
            
            // Dispatch octree construction compute shader
            context.dispatchCompute(octreePipeline, bindGroup, workgroups, 1, 1);
            
            // Wait for completion
            context.waitIdle().join();
            
            log.debug("Octree construction complete");
        });
    }
    
    /**
     * Render the voxel scene.
     */
    public void render() {
        if (!context.isInitialized()) {
            log.warn("WebGPU not ready for rendering");
            return;
        }
        
        // TODO: Implement render pass with command encoding
        log.trace("Rendering voxel scene");
    }
    
    /**
     * Update rendering parameters.
     * 
     * @param params the uniform parameters
     */
    public void updateUniforms(ByteBuffer params) {
        // Convert ByteBuffer to byte array, handling both direct and array-backed buffers
        byte[] data;
        if (params.hasArray()) {
            data = params.array();
        } else {
            // Handle direct buffers that don't have backing arrays
            data = new byte[params.remaining()];
            params.get(data);
            params.rewind(); // Reset position for potential reuse
        }
        context.writeBuffer(uniformBuffer, data, 0);
    }
    
    /**
     * Set voxel resolution.
     * 
     * @param resolution the resolution (must be power of 2)
     */
    public void setVoxelResolution(int resolution) {
        if (Integer.bitCount(resolution) != 1) {
            throw new IllegalArgumentException("Resolution must be power of 2");
        }
        this.voxelResolution = resolution;
        calculateBufferSizes();
    }
    
    /**
     * Check if pipeline is ready.
     */
    public boolean isReady() {
        return voxelBuffer != null && octreeBuffer != null;
    }
    
    /**
     * Shutdown and release resources.
     */
    public void shutdown() {
        log.info("Shutting down voxel render pipeline...");
        
        if (voxelBuffer != null) voxelBuffer.close();
        if (octreeBuffer != null) octreeBuffer.close();
        if (uniformBuffer != null) uniformBuffer.close();
        if (voxelizeShader != null) voxelizeShader.close();
        if (traversalShader != null) traversalShader.close();
        if (renderShader != null) renderShader.close();
        
        log.info("Voxel render pipeline shutdown complete");
    }
}