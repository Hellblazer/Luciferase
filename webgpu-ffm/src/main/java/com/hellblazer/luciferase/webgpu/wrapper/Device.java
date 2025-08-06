package com.hellblazer.luciferase.webgpu.wrapper;

import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Type-safe wrapper for WebGPU Device.
 * Represents a logical connection to a physical device.
 */
public class Device implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Device.class);
    
    private final MemorySegment handle;
    private final Queue defaultQueue;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ConcurrentHashMap<Long, Buffer> buffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ShaderModule> shaderModules = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    
    /**
     * Create a device wrapper from a native handle.
     * 
     * @param handle the native device handle
     * @param queueHandle the native queue handle
     */
    public Device(MemorySegment handle, MemorySegment queueHandle) {
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Invalid device handle");
        }
        this.handle = handle;
        this.defaultQueue = new Queue(queueHandle, this);
        log.debug("Created device wrapper: 0x{}", Long.toHexString(handle.address()));
    }
    
    /**
     * Create a buffer on this device.
     * 
     * @param descriptor the buffer descriptor
     * @return the created buffer
     */
    public Buffer createBuffer(BufferDescriptor descriptor) {
        if (closed.get()) {
            throw new IllegalStateException("Device is closed");
        }
        
        try (var arena = Arena.ofConfined()) {
            // Allocate descriptor in native memory
            var nativeDesc = arena.allocate(WebGPUNative.Descriptors.BUFFER_DESCRIPTOR);
            
            // Set descriptor fields
            nativeDesc.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // nextInChain
            var label = descriptor.getLabel() != null ? 
                WebGPUNative.toCString(descriptor.getLabel(), arena) : MemorySegment.NULL;
            nativeDesc.set(ValueLayout.ADDRESS, 8, label); // label
            nativeDesc.set(ValueLayout.JAVA_INT, 16, descriptor.getUsage()); // usage
            nativeDesc.set(ValueLayout.JAVA_LONG, 24, descriptor.getSize()); // size
            nativeDesc.set(ValueLayout.JAVA_INT, 32, descriptor.isMappedAtCreation() ? 1 : 0);
            
            // TODO: Call wgpuDeviceCreateBuffer when function handle is available
            // For now, create a mock buffer
            var bufferId = nextId.getAndIncrement();
            var buffer = new Buffer(bufferId, descriptor.getSize(), descriptor.getUsage(), this);
            buffers.put(bufferId, buffer);
            
            log.debug("Created buffer {} with size {} and usage {}", 
                bufferId, descriptor.getSize(), descriptor.getUsage());
            
            return buffer;
        }
    }
    
    /**
     * Create a shader module on this device.
     * 
     * @param descriptor the shader module descriptor
     * @return the created shader module
     */
    public ShaderModule createShaderModule(ShaderModuleDescriptor descriptor) {
        if (closed.get()) {
            throw new IllegalStateException("Device is closed");
        }
        
        // TODO: Implement actual shader module creation
        var moduleId = nextId.getAndIncrement();
        var module = new ShaderModule(moduleId, descriptor.getCode(), this);
        shaderModules.put(moduleId, module);
        
        log.debug("Created shader module {} with {} characters of WGSL", 
            moduleId, descriptor.getCode().length());
        
        return module;
    }
    
    /**
     * Create a compute pipeline on this device.
     * 
     * @param descriptor the compute pipeline descriptor
     * @return the created compute pipeline
     */
    public ComputePipeline createComputePipeline(ComputePipelineDescriptor descriptor) {
        if (closed.get()) {
            throw new IllegalStateException("Device is closed");
        }
        
        // TODO: Implement actual compute pipeline creation
        log.debug("Creating compute pipeline with module: {}", descriptor.getComputeModule());
        
        return new ComputePipeline(nextId.getAndIncrement(), this);
    }
    
    /**
     * Get the default queue for this device.
     * 
     * @return the default queue
     */
    public Queue getQueue() {
        return defaultQueue;
    }
    
    /**
     * Get the native handle for this device.
     * 
     * @return the native memory segment
     */
    public MemorySegment getHandle() {
        return handle;
    }
    
    /**
     * Check if this device is valid and not closed.
     * 
     * @return true if the device is valid
     */
    public boolean isValid() {
        return !closed.get() && handle != null && !handle.equals(MemorySegment.NULL);
    }
    
    /**
     * Remove a buffer from tracking.
     */
    void removeBuffer(long bufferId) {
        buffers.remove(bufferId);
    }
    
    /**
     * Remove a shader module from tracking.
     */
    void removeShaderModule(long moduleId) {
        shaderModules.remove(moduleId);
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Close all resources
            buffers.values().forEach(Buffer::close);
            buffers.clear();
            
            shaderModules.values().forEach(ShaderModule::close);
            shaderModules.clear();
            
            defaultQueue.close();
            
            // TODO: Call wgpuDeviceRelease when function handle is available
            log.debug("Released device");
        }
    }
    
    /**
     * Buffer descriptor for creating buffers.
     */
    public static class BufferDescriptor {
        private String label;
        private long size;
        private int usage;
        private boolean mappedAtCreation = false;
        
        public BufferDescriptor(long size, int usage) {
            this.size = size;
            this.usage = usage;
        }
        
        public BufferDescriptor withLabel(String label) {
            this.label = label;
            return this;
        }
        
        public BufferDescriptor withMappedAtCreation(boolean mapped) {
            this.mappedAtCreation = mapped;
            return this;
        }
        
        public String getLabel() {
            return label;
        }
        
        public long getSize() {
            return size;
        }
        
        public int getUsage() {
            return usage;
        }
        
        public boolean isMappedAtCreation() {
            return mappedAtCreation;
        }
    }
    
    /**
     * Shader module descriptor for creating shader modules.
     */
    public static class ShaderModuleDescriptor {
        private String label;
        private String code;
        
        public ShaderModuleDescriptor(String code) {
            this.code = code;
        }
        
        public ShaderModuleDescriptor withLabel(String label) {
            this.label = label;
            return this;
        }
        
        public String getLabel() {
            return label;
        }
        
        public String getCode() {
            return code;
        }
    }
    
    /**
     * Compute pipeline descriptor.
     */
    public static class ComputePipelineDescriptor {
        private String label;
        private ShaderModule computeModule;
        private String entryPoint = "main";
        
        public ComputePipelineDescriptor(ShaderModule module) {
            this.computeModule = module;
        }
        
        public ComputePipelineDescriptor withLabel(String label) {
            this.label = label;
            return this;
        }
        
        public ComputePipelineDescriptor withEntryPoint(String entryPoint) {
            this.entryPoint = entryPoint;
            return this;
        }
        
        public String getLabel() {
            return label;
        }
        
        public ShaderModule getComputeModule() {
            return computeModule;
        }
        
        public String getEntryPoint() {
            return entryPoint;
        }
    }
}