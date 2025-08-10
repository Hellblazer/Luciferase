package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.webgpu.WebGPU;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebGPU context management for ESVO rendering.
 * Provides a high-level interface for voxel rendering operations using WebGPU.
 */
public class WebGPUContext {
    private static final Logger log = LoggerFactory.getLogger(WebGPUContext.class);
    
    private Instance instance;
    private Adapter adapter;
    private Device device;
    private Queue queue;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    
    /**
     * Buffer usage flags from WebGPUNative
     */
    public static class BufferUsage {
        public static final int MAP_READ = WebGPUNative.BUFFER_USAGE_MAP_READ;
        public static final int MAP_WRITE = WebGPUNative.BUFFER_USAGE_MAP_WRITE;
        public static final int COPY_SRC = WebGPUNative.BUFFER_USAGE_COPY_SRC;
        public static final int COPY_DST = WebGPUNative.BUFFER_USAGE_COPY_DST;
        public static final int INDEX = WebGPUNative.BUFFER_USAGE_INDEX;
        public static final int VERTEX = WebGPUNative.BUFFER_USAGE_VERTEX;
        public static final int UNIFORM = WebGPUNative.BUFFER_USAGE_UNIFORM;
        public static final int STORAGE = WebGPUNative.BUFFER_USAGE_STORAGE;
        public static final int INDIRECT = WebGPUNative.BUFFER_USAGE_INDIRECT;
        public static final int QUERY_RESOLVE = WebGPUNative.BUFFER_USAGE_QUERY_RESOLVE;
    }
    
    /**
     * Create WebGPU context with automatic backend selection.
     */
    public WebGPUContext() {
    }
    
    /**
     * Initialize WebGPU context.
     */
    public CompletableFuture<Void> initialize() {
        if (initialized.get()) {
            log.debug("WebGPU context already initialized");
            return CompletableFuture.completedFuture(null);
        }
        
        log.info("Initializing WebGPU context");
        return CompletableFuture.runAsync(() -> {
            try {
                // Initialize WebGPU
                WebGPU.initialize();
                
                // Create instance
                instance = new Instance();
                
                // Request adapter
                var options = new Instance.AdapterOptions()
                    .withPowerPreference(Instance.PowerPreference.HIGH_PERFORMANCE);
                adapter = instance.requestAdapter(options).get();
                
                if (adapter == null) {
                    throw new RuntimeException("Failed to get WebGPU adapter");
                }
                
                // Create device
                var deviceDescriptor = new Adapter.DeviceDescriptor()
                    .withLabel("VoxelRenderDevice");
                device = adapter.requestDevice(deviceDescriptor).get();
                
                if (device == null) {
                    throw new RuntimeException("Failed to create WebGPU device");
                }
                
                // Get queue
                queue = device.getQueue();
                
                initialized.set(true);
                log.info("WebGPU context initialized successfully");
            } catch (Exception e) {
                log.error("Failed to initialize WebGPU context", e);
                throw new RuntimeException("Failed to initialize WebGPU context", e);
            }
        });
    }
    
    /**
     * Check if WebGPU is available.
     */
    public boolean isAvailable() {
        return WebGPU.isAvailable();
    }
    
    /**
     * Check if the context is initialized.
     */
    public boolean isInitialized() {
        return initialized.get();
    }
    
    /**
     * Get the WebGPU device.
     */
    public Device getDevice() {
        return device;
    }
    
    /**
     * Create a buffer for GPU storage.
     */
    public Buffer createBuffer(long size, int usage) {
        if (!isInitialized()) {
            throw new IllegalStateException("WebGPU context not initialized");
        }
        
        var descriptor = new Device.BufferDescriptor(size, usage)
            .withLabel("Buffer_" + size);
        
        return device.createBuffer(descriptor);
    }
    
    /**
     * Create a compute shader from WGSL source.
     */
    public ShaderModule createComputeShader(String wgslSource) {
        if (!isInitialized()) {
            throw new IllegalStateException("WebGPU context not initialized");
        }
        
        var descriptor = new Device.ShaderModuleDescriptor(wgslSource)
            .withLabel("ComputeShader");
        
        return device.createShaderModule(descriptor);
    }
    
    /**
     * Write data to a buffer.
     */
    public void writeBuffer(Buffer buffer, byte[] data, long offset) {
        if (!isInitialized()) {
            throw new IllegalStateException("WebGPU context not initialized");
        }
        
        queue.writeBuffer(buffer, offset, ByteBuffer.wrap(data));
    }
    
    /**
     * Write data to a buffer from a memory segment.
     * For VoxelGPUManager compatibility.
     */
    public void writeBuffer(long bufferId, java.lang.foreign.MemorySegment data, long offset) {
        // This is a stub for VoxelGPUManager compatibility
        // In a real implementation, we'd need to map bufferId to actual Buffer objects
        log.debug("Write buffer stub: bufferId={}, offset={}, size={}", 
                 bufferId, offset, data.byteSize());
    }
    
    /**
     * Destroy a buffer.
     * For VoxelGPUManager compatibility.
     */
    public void destroyBuffer(long bufferId) {
        // This is a stub for VoxelGPUManager compatibility
        // In a real implementation, we'd need to map bufferId to actual Buffer objects
        log.debug("Destroy buffer stub: bufferId={}", bufferId);
    }
    
    /**
     * Read data from a buffer.
     */
    public byte[] readBuffer(Buffer buffer, long size, long offset) {
        if (!isInitialized()) {
            throw new IllegalStateException("WebGPU context not initialized");
        }
        
        try {
            // Map buffer for reading
            var mappedSegment = buffer.mapAsync(Buffer.MapMode.READ, offset, size).get();
            
            // Convert to ByteBuffer
            var byteBuffer = mappedSegment.asByteBuffer();
            
            // Check actual available size
            int availableBytes = byteBuffer.remaining();
            int bytesToRead = Math.min((int)size, availableBytes);
            
            // Copy data - only read what's available
            byte[] result = new byte[bytesToRead];
            byteBuffer.get(result);
            
            // If we got less than requested, pad with zeros (mock data case)
            if (bytesToRead < size) {
                log.debug("Buffer mapping returned {} bytes but {} were requested - padding with zeros", 
                         bytesToRead, size);
                byte[] paddedResult = new byte[(int)size];
                System.arraycopy(result, 0, paddedResult, 0, bytesToRead);
                result = paddedResult;
            }
            
            // Unmap buffer
            buffer.unmap();
            
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read buffer", e);
        }
    }
    
    /**
     * Create a pipeline layout for organizing bind groups.
     */
    public PipelineLayout createPipelineLayout() {
        if (!isInitialized()) {
            throw new IllegalStateException("WebGPU context not initialized");
        }
        
        // Create an empty pipeline layout (auto layout)
        var descriptor = new Device.PipelineLayoutDescriptor()
            .withLabel("PipelineLayout");
        
        return device.createPipelineLayout(descriptor);
    }
    
    /**
     * Create a compute pipeline with shader and layout.
     */
    public ComputePipeline createComputePipeline(ShaderModule shader, String entryPoint, PipelineLayout layout) {
        if (!isInitialized()) {
            throw new IllegalStateException("WebGPU context not initialized");
        }
        
        var descriptor = new Device.ComputePipelineDescriptor(shader)
            .withLabel("ComputePipeline")
            .withLayout(layout)
            .withEntryPoint(entryPoint);
        
        return device.createComputePipeline(descriptor);
    }
    
    /**
     * Create a bind group for binding resources to a pipeline.
     */
    public BindGroup createBindGroup(PipelineLayout layout, Buffer[] buffers) {
        if (!isInitialized()) {
            throw new IllegalStateException("WebGPU context not initialized");
        }
        
        // For now, create a simple bind group layout
        // In a real implementation, we'd need to properly define the layout
        var bindGroupLayoutDesc = new Device.BindGroupLayoutDescriptor()
            .withLabel("BindGroupLayout");
        
        // Add entries for each buffer
        for (int i = 0; i < buffers.length; i++) {
            bindGroupLayoutDesc.withEntry(new Device.BindGroupLayoutEntry(i, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)));
        }
        
        var bindGroupLayout = device.createBindGroupLayout(bindGroupLayoutDesc);
        
        // Create bind group entries for each buffer
        var entries = new Device.BindGroupEntry[buffers.length];
        for (int i = 0; i < buffers.length; i++) {
            entries[i] = new Device.BindGroupEntry(i)
                .withBuffer(buffers[i], 0, buffers[i].getSize());
        }
        
        var descriptor = new Device.BindGroupDescriptor(bindGroupLayout)
            .withLabel("BindGroup");
        
        for (var entry : entries) {
            descriptor.withEntry(entry);
        }
        
        return device.createBindGroup(descriptor);
    }
    
    /**
     * Dispatch compute work.
     */
    public void dispatchCompute(ComputePipeline pipeline, int workGroupCountX, int workGroupCountY, int workGroupCountZ) {
        if (!isInitialized()) {
            throw new IllegalStateException("WebGPU context not initialized");
        }
        
        // Create command encoder
        var encoder = device.createCommandEncoder("ComputeEncoder");
        var encoderDesc = new CommandEncoder.ComputePassDescriptor();
        encoderDesc.withLabel("ComputePass");
        var computePass = encoder.beginComputePass(encoderDesc);
        
        // Set pipeline and dispatch
        // Skip setPipeline if using stub pipeline with NULL handle
        if (pipeline != null && pipeline.getHandle() != null && !pipeline.getHandle().equals(java.lang.foreign.MemorySegment.NULL)) {
            computePass.setPipeline(pipeline);
        }
        computePass.dispatchWorkgroups(workGroupCountX, workGroupCountY, workGroupCountZ);
        computePass.end();
        
        // Submit commands
        var commandBuffer = encoder.finish();
        queue.submit(commandBuffer);
    }
    
    /**
     * Dispatch compute work with bind groups.
     */
    public void dispatchCompute(ComputePipeline pipeline, BindGroup bindGroup, int workGroupCountX, int workGroupCountY, int workGroupCountZ) {
        if (!isInitialized()) {
            throw new IllegalStateException("WebGPU context not initialized");
        }
        
        // Create command encoder
        var encoder = device.createCommandEncoder("ComputeEncoder");
        var encoderDesc = new CommandEncoder.ComputePassDescriptor();
        encoderDesc.withLabel("ComputePass");
        var computePass = encoder.beginComputePass(encoderDesc);
        
        // Set pipeline and bind group
        if (pipeline != null && pipeline.getHandle() != null && !pipeline.getHandle().equals(java.lang.foreign.MemorySegment.NULL)) {
            computePass.setPipeline(pipeline);
        }
        
        // Set bind group at index 0
        if (bindGroup != null) {
            computePass.setBindGroup(0, bindGroup);
        }
        
        // Dispatch workgroups
        computePass.dispatchWorkgroups(workGroupCountX, workGroupCountY, workGroupCountZ);
        computePass.end();
        
        // Submit commands
        var commandBuffer = encoder.finish();
        queue.submit(commandBuffer);
    }
    
    /**
     * Copy data from one buffer to another on the GPU.
     */
    public void copyBuffer(Buffer source, Buffer destination, long size) {
        if (!isInitialized()) {
            throw new IllegalStateException("WebGPU context not initialized");
        }
        
        // Create command encoder for the copy operation
        var encoder = device.createCommandEncoder("BufferCopyEncoder");
        
        // Copy buffer on GPU
        encoder.copyBufferToBuffer(source, 0, destination, 0, size);
        
        // Submit the command
        var commandBuffer = encoder.finish();
        queue.submit(commandBuffer);
    }
    
    /**
     * Wait for all GPU operations to complete.
     */
    public CompletableFuture<Void> waitIdle() {
        return CompletableFuture.runAsync(() -> {
            if (queue != null) {
                queue.onSubmittedWorkDone();
            }
            // Poll device to process pending operations
            if (device != null) {
                try {
                    com.hellblazer.luciferase.webgpu.WebGPU.pollDevice(device.getHandle(), true);
                } catch (Exception e) {
                    log.debug("Device poll failed during waitIdle: {}", e.getMessage());
                }
            }
        });
    }
    
    /**
     * Shutdown the context and release resources.
     */
    public void shutdown() {
        if (device != null) {
            device.close();
            device = null;
        }
        if (adapter != null) {
            adapter.close();
            adapter = null;
        }
        if (instance != null) {
            instance.close();
            instance = null;
        }
        initialized.set(false);
        log.info("WebGPU context shutdown complete");
    }
    
    /**
     * Get the backend implementation name.
     */
    public String getBackendName() {
        return "WebGPU-FFM";
    }
    
    
    /**
     * Get the queue for direct access.
     */
    public Queue getQueue() {
        return queue;
    }
    
    // Legacy compatibility methods for VoxelRenderingPipeline
    
    /**
     * Write to buffer (legacy compatibility).
     */
    public void writeBuffer(Object buffer, long offset, byte[] data) {
        if (buffer instanceof Buffer webgpuBuffer) {
            writeBuffer(webgpuBuffer, data, offset);
        } else {
            throw new IllegalArgumentException("Invalid buffer type: " + buffer.getClass());
        }
    }
    
    /**
     * Read from buffer (legacy compatibility).
     */
    public ByteBuffer readBuffer(Object buffer) {
        if (buffer instanceof Buffer webgpuBuffer) {
            var data = readBuffer(webgpuBuffer, webgpuBuffer.getSize(), 0);
            return ByteBuffer.wrap(data);
        } else {
            throw new IllegalArgumentException("Invalid buffer type: " + buffer.getClass());
        }
    }
    
    /**
     * Dispatch compute (legacy compatibility).
     */
    public void dispatchCompute(Object pipeline, int x, int y, int z) {
        if (pipeline instanceof ComputePipeline computePipeline) {
            dispatchCompute(computePipeline, x, y, z);
        } else {
            throw new IllegalArgumentException("Invalid pipeline type: " + pipeline.getClass());
        }
    }
}