package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.webgpu.WebGPU;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebGPU backend implementation using the high-level wrapper API from webgpu-ffm module.
 * This ensures proper FFM handle management and avoids direct handle invocation issues.
 */
public class FFMWebGPUBackend implements WebGPUBackend {
    private static final Logger log = LoggerFactory.getLogger(FFMWebGPUBackend.class);
    
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private Instance instance;
    private Adapter adapter;
    private Device device;
    private Queue queue;
    
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, BufferHandleImpl> buffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ShaderHandleImpl> shaders = new ConcurrentHashMap<>();

    @Override
    public boolean isAvailable() {
        return WebGPUCapabilities.isWebGPUPotentiallyAvailable();
    }

    @Override
    public CompletableFuture<Boolean> initialize() {
        if (initialized.get()) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Initializing FFM WebGPU backend using wrapper API");
                
                // Initialize WebGPU library
                if (!WebGPU.initialize()) {
                    log.error("Failed to initialize WebGPU library");
                    return false;
                }
                
                // Create instance using wrapper API
                instance = new Instance();
                log.debug("Created WebGPU instance");
                
                // Request adapter
                CompletableFuture<Adapter> adapterFuture = instance.requestAdapter();
                adapter = adapterFuture.get();
                if (adapter == null) {
                    log.error("Failed to get WebGPU adapter");
                    return false;
                }
                log.debug("Got WebGPU adapter");
                
                // Request device
                CompletableFuture<Device> deviceFuture = adapter.requestDevice();
                device = deviceFuture.get();
                if (device == null) {
                    log.error("Failed to get WebGPU device");
                    return false;
                }
                log.debug("Got WebGPU device");
                
                // Get queue
                queue = device.getQueue();
                if (queue == null) {
                    log.error("Failed to get WebGPU queue");
                    return false;
                }
                log.debug("Got WebGPU queue");
                
                initialized.set(true);
                log.info("FFM WebGPU backend initialized successfully");
                return true;
                
            } catch (Exception e) {
                log.error("Failed to initialize FFM WebGPU backend", e);
                return false;
            }
        });
    }

    @Override
    public boolean isInitialized() {
        return initialized.get();
    }

    @Override
    public BufferHandle createBuffer(long size, int usage) {
        if (!initialized.get()) {
            throw new IllegalStateException("WebGPU not initialized");
        }
        
        try {
            // Add COPY_DST for writing data
            int usageFlags = usage | 0x0008;
            
            var descriptor = new Device.BufferDescriptor(size, usageFlags);
            
            Buffer buffer = device.createBuffer(descriptor);
            if (buffer == null) {
                throw new RuntimeException("Failed to create buffer");
            }
            
            long id = nextId.getAndIncrement();
            var handle = new BufferHandleImpl(id, buffer, size, usage);
            buffers.put(id, handle);
            
            return handle;
            
        } catch (Exception e) {
            log.error("Failed to create buffer", e);
            throw new RuntimeException("Failed to create buffer", e);
        }
    }

    @Override
    public void writeBuffer(BufferHandle buffer, byte[] data, long offset) {
        if (!initialized.get()) {
            throw new IllegalStateException("WebGPU not initialized");
        }
        
        var bufferImpl = buffers.get(((BufferHandleImpl)buffer).id);
        if (bufferImpl == null) {
            throw new IllegalArgumentException("Invalid buffer handle");
        }
        
        try {
            var byteBuffer = ByteBuffer.allocateDirect(data.length);
            byteBuffer.put(data);
            byteBuffer.flip();
            queue.writeBuffer(bufferImpl.buffer, offset, byteBuffer);
        } catch (Exception e) {
            log.error("Failed to write buffer", e);
            throw new RuntimeException("Failed to write buffer", e);
        }
    }

    @Override
    public byte[] readBuffer(BufferHandle buffer, long size, long offset) {
        if (!initialized.get()) {
            throw new IllegalStateException("WebGPU not initialized");
        }
        
        var bufferImpl = buffers.get(((BufferHandleImpl)buffer).id);
        if (bufferImpl == null) {
            throw new IllegalArgumentException("Invalid buffer handle");
        }
        
        try {
            // Map buffer for reading
            var mapFuture = bufferImpl.buffer.mapAsync(Buffer.MapMode.READ, offset, size);
            mapFuture.get();
            
            // Get mapped range
            MemorySegment mappedRange = bufferImpl.buffer.getMappedRange(offset, size);
            
            // Copy data to byte array
            byte[] result = new byte[(int)size];
            mappedRange.asByteBuffer().get(result);
            
            // Unmap buffer
            bufferImpl.buffer.unmap();
            
            return result;
            
        } catch (Exception e) {
            log.error("Failed to read buffer", e);
            throw new RuntimeException("Failed to read buffer", e);
        }
    }

    @Override
    public ShaderHandle createComputeShader(String wgslSource) {
        if (!initialized.get()) {
            throw new IllegalStateException("WebGPU not initialized");
        }
        
        try {
            var descriptor = new Device.ShaderModuleDescriptor(wgslSource);
            
            ShaderModule shader = device.createShaderModule(descriptor);
            if (shader == null) {
                throw new RuntimeException("Failed to create shader");
            }
            
            long id = nextId.getAndIncrement();
            var handle = new ShaderHandleImpl(id, shader, wgslSource);
            shaders.put(id, handle);
            
            return handle;
            
        } catch (Exception e) {
            log.error("Failed to create shader", e);
            throw new RuntimeException("Failed to create shader", e);
        }
    }

    @Override
    public void dispatchCompute(ShaderHandle shader, int workGroupCountX, int workGroupCountY, int workGroupCountZ) {
        if (!initialized.get()) {
            throw new IllegalStateException("WebGPU not initialized");
        }
        
        // TODO: Implement compute dispatch
        // This requires creating compute pipelines and command encoders
        log.warn("dispatchCompute not yet implemented");
    }
    
    @Override
    public void waitIdle() {
        if (!initialized.get()) {
            return;
        }
        
        // TODO: Implement GPU synchronization
        log.warn("waitIdle not yet implemented");
    }

    @Override
    public void shutdown() {
        if (!initialized.get()) {
            return;
        }
        
        initialized.set(false);
        
        // Clean up buffers
        for (BufferHandleImpl handle : buffers.values()) {
            try {
                handle.release();
            } catch (Exception e) {
                log.error("Error destroying buffer", e);
            }
        }
        buffers.clear();
        
        // Clean up shaders
        for (ShaderHandleImpl handle : shaders.values()) {
            try {
                handle.release();
            } catch (Exception e) {
                log.error("Error destroying shader", e);
            }
        }
        shaders.clear();
        
        // Clean up WebGPU resources
        if (queue != null) {
            queue.close();
            queue = null;
        }
        
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
        
        log.info("FFM WebGPU backend shut down");
    }

    @Override
    public String getBackendName() {
        return "FFM WebGPU Backend (using wrapper API)";
    }
    
    /**
     * Internal implementation of BufferHandle.
     */
    private static class BufferHandleImpl implements BufferHandle {
        private final long id;
        private final Buffer buffer;
        private final long size;
        private final int usage;
        private volatile boolean valid = true;
        
        BufferHandleImpl(long id, Buffer buffer, long size, int usage) {
            this.id = id;
            this.buffer = buffer;
            this.size = size;
            this.usage = usage;
        }
        
        @Override
        public long getSize() {
            return size;
        }
        
        @Override
        public int getUsage() {
            return usage;
        }
        
        @Override
        public boolean isValid() {
            return valid;
        }
        
        @Override
        public void release() {
            if (valid) {
                valid = false;
                buffer.close();
            }
        }
        
        @Override
        public Object getNativeHandle() {
            return buffer;
        }
    }
    
    /**
     * Internal implementation of ShaderHandle.
     */
    private static class ShaderHandleImpl implements ShaderHandle {
        private final long id;
        private final ShaderModule shader;
        private final String wgslSource;
        private volatile boolean valid = true;
        
        ShaderHandleImpl(long id, ShaderModule shader, String wgslSource) {
            this.id = id;
            this.shader = shader;
            this.wgslSource = wgslSource;
        }
        
        @Override
        public String getWgslSource() {
            return wgslSource;
        }
        
        @Override
        public boolean isValid() {
            return valid;
        }
        
        @Override
        public void release() {
            if (valid) {
                valid = false;
                shader.close();
            }
        }
        
        @Override
        public Object getNativeHandle() {
            return shader;
        }
    }
}