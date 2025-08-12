package com.hellblazer.luciferase.webgpu.wrapper;

import com.hellblazer.luciferase.webgpu.CallbackBridge;
import com.hellblazer.luciferase.webgpu.WebGPU;
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
    private Instance instance; // Reference to the instance for event processing
    
    /**
     * Create a device wrapper from a native handle.
     * Automatically gets the queue from the device.
     * 
     * @param handle the native device handle
     */
    public Device(MemorySegment handle) {
        this(handle, (Instance) null);
    }
    
    /**
     * Create a device wrapper from a native handle with an instance reference.
     * 
     * @param handle the native device handle
     * @param instance the WebGPU instance for event processing
     */
    public Device(MemorySegment handle, Instance instance) {
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Invalid device handle");
        }
        this.handle = handle;
        this.instance = instance;
        
        // Set up error callback to capture validation errors
        setupErrorCallback();
        
        // Get the queue from the native API
        var queueHandle = WebGPU.getQueue(handle);
        if (queueHandle != null && !queueHandle.equals(MemorySegment.NULL)) {
            this.defaultQueue = new Queue(queueHandle, this);
        } else {
            // Fall back to mock queue
            log.warn("Failed to get queue from device - creating mock");
            this.defaultQueue = new Queue(MemorySegment.ofAddress(System.nanoTime()), this);
        }
        
        log.debug("Created device wrapper: 0x{}", Long.toHexString(handle.address()));
    }
    
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
            
            // Try to call native wgpuDeviceCreateBuffer if WebGPU is initialized
            MemorySegment bufferHandle = null;
            if (WebGPU.isInitialized()) {
                try {
                    bufferHandle = WebGPU.createBuffer(handle, nativeDesc);
                } catch (Exception e) {
                    log.debug("Native buffer creation failed: {}", e.getMessage());
                }
            }
            
            if (bufferHandle == null || bufferHandle.equals(MemorySegment.NULL)) {
                // Fall back to mock if native call fails or WebGPU not initialized
                log.debug("Using mock buffer (native not available)");
                var bufferId = nextId.getAndIncrement();
                var buffer = new Buffer(bufferId, descriptor.getSize(), descriptor.getUsage(), this);
                buffers.put(bufferId, buffer);
                return buffer;
            }
            
            // Create wrapper for native buffer
            var buffer = new Buffer(bufferHandle, descriptor.getSize(), descriptor.getUsage(), this);
            buffers.put(bufferHandle.address(), buffer);
            
            log.debug("Created native buffer with size {} and usage {}", 
                descriptor.getSize(), descriptor.getUsage());
            
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
        
        try (var arena = Arena.ofConfined()) {
            // Create the WGSL descriptor with embedded chain header
            // The WGSL descriptor has the chain struct embedded at the beginning
            var wgslDesc = arena.allocate(24); // 16 bytes for chain + 8 bytes for code pointer
            
            // Set the embedded chain header
            wgslDesc.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // chain.next
            wgslDesc.set(ValueLayout.JAVA_INT, 8, 0x00000006); // chain.sType for WGSLDescriptor
            wgslDesc.set(ValueLayout.JAVA_INT, 12, 0); // padding for alignment
            
            // Set the code pointer
            var codeStr = WebGPUNative.toCString(descriptor.getCode(), arena);
            wgslDesc.set(ValueLayout.ADDRESS, 16, codeStr); // code
            
            // Create the main shader module descriptor
            var nativeDesc = arena.allocate(WebGPUNative.Descriptors.SHADER_MODULE_DESCRIPTOR);
            nativeDesc.set(ValueLayout.ADDRESS, 0, wgslDesc); // nextInChain points to WGSL descriptor
            var label = descriptor.getLabel() != null ? 
                WebGPUNative.toCString(descriptor.getLabel(), arena) : MemorySegment.NULL;
            nativeDesc.set(ValueLayout.ADDRESS, 8, label); // label
            nativeDesc.set(ValueLayout.JAVA_LONG, 16, 0L); // hintCount
            nativeDesc.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL); // hints
            
            // Try to create native shader module
            MemorySegment moduleHandle = null;
            if (WebGPU.isInitialized()) {
                log.debug("Attempting to create native shader module with {} chars of WGSL", descriptor.getCode().length());
                log.debug("WGSL Code:\n{}", descriptor.getCode());
                moduleHandle = WebGPU.createShaderModule(handle, nativeDesc);
            } else {
                log.debug("WebGPU not initialized, using stub shader module");
            }
            
            if (moduleHandle == null || moduleHandle.equals(MemorySegment.NULL)) {
                // Fall back to stub for testing
                log.warn("Using stub shader module (native creation failed or not available)");
                moduleHandle = MemorySegment.NULL;
            } else {
                log.info("Created native shader module: 0x{}", Long.toHexString(moduleHandle.address()));
            }
            
            var module = new ShaderModule(moduleHandle, descriptor.getCode(), this);
            
            log.debug("Created shader module with {} characters of WGSL", 
                descriptor.getCode().length());
            
            return module;
        }
    }
    
    /**
     * Create a bind group layout on this device.
     * 
     * @param descriptor the bind group layout descriptor
     * @return the created bind group layout
     */
    public BindGroupLayout createBindGroupLayout(BindGroupLayoutDescriptor descriptor) {
        if (closed.get()) {
            throw new IllegalStateException("Device is closed");
        }
        
        try (var arena = Arena.ofConfined()) {
            // Create native descriptor
            var nativeDesc = arena.allocate(WebGPUNative.Descriptors.BIND_GROUP_LAYOUT_DESCRIPTOR);
            
            // Set basic fields
            nativeDesc.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // nextInChain
            var label = descriptor.getLabel() != null ? 
                WebGPUNative.toCString(descriptor.getLabel(), arena) : MemorySegment.NULL;
            nativeDesc.set(ValueLayout.ADDRESS, 8, label); // label
            
            // Create entries array
            var entries = descriptor.getEntries();
            nativeDesc.set(ValueLayout.JAVA_LONG, 16, entries.size()); // entryCount
            
            if (!entries.isEmpty()) {
                var entriesArray = arena.allocate(
                    WebGPUNative.Descriptors.BIND_GROUP_LAYOUT_ENTRY.byteSize() * entries.size()
                );
                
                for (int i = 0; i < entries.size(); i++) {
                    var entry = entries.get(i);
                    var entryOffset = i * WebGPUNative.Descriptors.BIND_GROUP_LAYOUT_ENTRY.byteSize();
                    var entrySegment = entriesArray.asSlice(entryOffset);
                    
                    // Set entry fields
                    entrySegment.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // nextInChain
                    entrySegment.set(ValueLayout.JAVA_INT, 8, entry.binding); // binding
                    entrySegment.set(ValueLayout.JAVA_INT, 12, entry.visibility); // visibility
                    
                    // Set buffer binding if present
                    if (entry.buffer != null) {
                        entrySegment.set(ValueLayout.ADDRESS, 16, MemorySegment.NULL); // buffer.nextInChain
                        entrySegment.set(ValueLayout.JAVA_INT, 24, entry.buffer.type); // buffer.type
                        entrySegment.set(ValueLayout.JAVA_INT, 28, entry.buffer.hasDynamicOffset ? 1 : 0);
                        entrySegment.set(ValueLayout.JAVA_LONG, 32, entry.buffer.minBindingSize);
                    }
                    // TODO: Add sampler, texture, and storage texture bindings
                }
                
                nativeDesc.set(ValueLayout.ADDRESS, 24, entriesArray); // entries
            } else {
                nativeDesc.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL); // entries
            }
            
            // Create native bind group layout
            var layoutHandle = WebGPU.createBindGroupLayout(handle, nativeDesc);
            
            if (layoutHandle == null || layoutHandle.equals(MemorySegment.NULL)) {
                log.error("Failed to create bind group layout");
                throw new RuntimeException("Failed to create bind group layout");
            }
            
            return new BindGroupLayout(this, layoutHandle);
        }
    }
    
    /**
     * Create a bind group on this device.
     * 
     * @param descriptor the bind group descriptor
     * @return the created bind group
     */
    public BindGroup createBindGroup(BindGroupDescriptor descriptor) {
        if (closed.get()) {
            throw new IllegalStateException("Device is closed");
        }
        
        try (var arena = Arena.ofConfined()) {
            // Create native descriptor
            var nativeDesc = arena.allocate(WebGPUNative.Descriptors.BIND_GROUP_DESCRIPTOR);
            
            // Set basic fields
            nativeDesc.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // nextInChain
            var label = descriptor.getLabel() != null ? 
                WebGPUNative.toCString(descriptor.getLabel(), arena) : MemorySegment.NULL;
            nativeDesc.set(ValueLayout.ADDRESS, 8, label); // label
            nativeDesc.set(ValueLayout.ADDRESS, 16, descriptor.getLayout().getHandle()); // layout
            
            // Create entries array
            var entries = descriptor.getEntries();
            nativeDesc.set(ValueLayout.JAVA_LONG, 24, entries.size()); // entryCount
            
            if (!entries.isEmpty()) {
                var entriesArray = arena.allocate(
                    WebGPUNative.Descriptors.BIND_GROUP_ENTRY.byteSize() * entries.size()
                );
                
                for (int i = 0; i < entries.size(); i++) {
                    var entry = entries.get(i);
                    var entryOffset = i * WebGPUNative.Descriptors.BIND_GROUP_ENTRY.byteSize();
                    var entrySegment = entriesArray.asSlice(entryOffset);
                    
                    // Set entry fields
                    entrySegment.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // nextInChain
                    entrySegment.set(ValueLayout.JAVA_INT, 8, entry.binding); // binding
                    entrySegment.set(ValueLayout.JAVA_INT, 12, 0); // padding
                    
                    // Set resource handles
                    if (entry.buffer != null) {
                        entrySegment.set(ValueLayout.ADDRESS, 16, entry.buffer.getHandle()); // buffer
                        entrySegment.set(ValueLayout.JAVA_LONG, 24, entry.offset); // offset
                        entrySegment.set(ValueLayout.JAVA_LONG, 32, entry.size); // size
                    } else {
                        entrySegment.set(ValueLayout.ADDRESS, 16, MemorySegment.NULL); // buffer
                        entrySegment.set(ValueLayout.JAVA_LONG, 24, 0L); // offset
                        entrySegment.set(ValueLayout.JAVA_LONG, 32, 0L); // size
                    }
                    
                    // TODO: Add sampler and texture view handles
                    entrySegment.set(ValueLayout.ADDRESS, 40, MemorySegment.NULL); // sampler
                    entrySegment.set(ValueLayout.ADDRESS, 48, MemorySegment.NULL); // textureView
                }
                
                nativeDesc.set(ValueLayout.ADDRESS, 32, entriesArray); // entries
            } else {
                nativeDesc.set(ValueLayout.ADDRESS, 32, MemorySegment.NULL); // entries
            }
            
            // Create native bind group
            var bindGroupHandle = WebGPU.createBindGroup(handle, nativeDesc);
            
            if (bindGroupHandle == null || bindGroupHandle.equals(MemorySegment.NULL)) {
                log.error("Failed to create bind group");
                throw new RuntimeException("Failed to create bind group");
            }
            
            return new BindGroup(this, bindGroupHandle);
        }
    }
    
    /**
     * Create a command encoder on this device.
     * 
     * @param label optional label for debugging
     * @return the created command encoder
     */
    public CommandEncoder createCommandEncoder(String label) {
        if (closed.get()) {
            throw new IllegalStateException("Device is closed");
        }
        
        // Create native command encoder
        var encoderHandle = WebGPU.createCommandEncoder(handle, null);
        
        if (encoderHandle == null || encoderHandle.equals(MemorySegment.NULL)) {
            log.error("Failed to create native command encoder");
            throw new RuntimeException("Failed to create command encoder");
        }
        
        log.debug("Created native command encoder with label: {}", label);
        return new CommandEncoder(this, encoderHandle);
    }
    
    /**
     * Create a pipeline layout on this device.
     * 
     * @param descriptor the pipeline layout descriptor
     * @return the created pipeline layout
     */
    public PipelineLayout createPipelineLayout(PipelineLayoutDescriptor descriptor) {
        if (closed.get()) {
            throw new IllegalStateException("Device is closed");
        }
        
        try (var arena = Arena.ofConfined()) {
            // Create native descriptor
            var nativeDesc = arena.allocate(WebGPUNative.Descriptors.PIPELINE_LAYOUT_DESCRIPTOR);
            
            // Set basic fields
            nativeDesc.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // nextInChain
            var label = descriptor.getLabel() != null ? 
                WebGPUNative.toCString(descriptor.getLabel(), arena) : MemorySegment.NULL;
            nativeDesc.set(ValueLayout.ADDRESS, 8, label); // label
            
            // Set bind group layouts
            var layouts = descriptor.getBindGroupLayouts();
            nativeDesc.set(ValueLayout.JAVA_LONG, 16, layouts.size()); // bindGroupLayoutCount
            
            if (!layouts.isEmpty()) {
                var layoutsArray = arena.allocate(ValueLayout.ADDRESS, layouts.size());
                for (int i = 0; i < layouts.size(); i++) {
                    layoutsArray.setAtIndex(ValueLayout.ADDRESS, i, layouts.get(i).getHandle());
                }
                nativeDesc.set(ValueLayout.ADDRESS, 24, layoutsArray); // bindGroupLayouts
            } else {
                nativeDesc.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL);
            }
            
            // Create native pipeline layout
            var layoutHandle = WebGPU.createPipelineLayout(handle, nativeDesc);
            
            if (layoutHandle == null || layoutHandle.equals(MemorySegment.NULL)) {
                log.error("Failed to create pipeline layout");
                throw new RuntimeException("Failed to create pipeline layout");
            }
            
            return new PipelineLayout(this, layoutHandle);
        }
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
        
        try (var arena = Arena.ofConfined()) {
            // Create the compute pipeline descriptor
            var nativeDesc = arena.allocate(WebGPUNative.Descriptors.COMPUTE_PIPELINE_DESCRIPTOR);
            
            // Set basic fields
            nativeDesc.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // nextInChain
            var label = descriptor.getLabel() != null ? 
                WebGPUNative.toCString(descriptor.getLabel(), arena) : MemorySegment.NULL;
            nativeDesc.set(ValueLayout.ADDRESS, 8, label); // label
            
            // Set layout (explicit or auto)
            var layout = descriptor.getLayout();
            if (layout != null) {
                nativeDesc.set(ValueLayout.ADDRESS, 16, layout.getHandle()); // explicit layout
            } else {
                nativeDesc.set(ValueLayout.ADDRESS, 16, MemorySegment.NULL); // auto layout
            }
            
            // Set compute stage (embedded struct)
            nativeDesc.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL); // compute.nextInChain
            
            // Get the shader module handle
            var shaderModule = descriptor.getComputeModule();
            var moduleHandle = shaderModule != null ? shaderModule.getHandle() : MemorySegment.NULL;
            nativeDesc.set(ValueLayout.ADDRESS, 32, moduleHandle); // compute.module
            
            // Set entry point
            var entryPoint = WebGPUNative.toCString(descriptor.getEntryPoint(), arena);
            nativeDesc.set(ValueLayout.ADDRESS, 40, entryPoint); // compute.entryPoint
            
            // No constants for now
            nativeDesc.set(ValueLayout.JAVA_LONG, 48, 0L); // compute.constantCount
            nativeDesc.set(ValueLayout.ADDRESS, 56, MemorySegment.NULL); // compute.constants
            
            // Try to create native compute pipeline
            MemorySegment pipelineHandle = null;
            if (WebGPU.isInitialized() && !moduleHandle.equals(MemorySegment.NULL)) {
                log.debug("Attempting to create native compute pipeline with module: 0x{}", 
                    moduleHandle.address());
                pipelineHandle = WebGPU.createComputePipeline(handle, nativeDesc);
            } else {
                log.debug("Cannot create native pipeline - WebGPU: {}, module: {}", 
                    WebGPU.isInitialized(), moduleHandle);
            }
            
            if (pipelineHandle == null || pipelineHandle.equals(MemorySegment.NULL)) {
                // Fall back to stub for testing
                log.warn("Using stub compute pipeline (native creation failed or module is stub)");
                pipelineHandle = MemorySegment.NULL;
            } else {
                log.info("Created native compute pipeline: 0x{}", Long.toHexString(pipelineHandle.address()));
            }
            
            var pipeline = new ComputePipeline(pipelineHandle, this);
            log.debug("Created compute pipeline with module: {}", descriptor.getComputeModule());
            
            return pipeline;
        }
    }
    
    /**
     * Set up error callback for the device.
     */
    private void setupErrorCallback() {
        try {
            var errorCallback = CallbackBridge.createErrorCallback(Arena.global(), 
                (errorType, message, userdata) -> {
                    log.error("WebGPU Device Error [type={}]: {}", errorType, message);
                });
            
            if (errorCallback != null && !errorCallback.equals(MemorySegment.NULL)) {
                WebGPU.setDeviceErrorCallback(handle, errorCallback, MemorySegment.NULL);
                log.debug("Set up device error callback");
            }
        } catch (Exception e) {
            log.warn("Failed to set up error callback", e);
        }
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
     * Poll the device to process completed operations.
     * 
     * @param wait whether to wait for operations to complete
     * @return true if polling succeeded
     */
    public boolean poll(boolean wait) {
        return WebGPU.processEvents(handle, wait);
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
     * Create a texture.
     * 
     * @param descriptor the texture descriptor
     * @return the texture
     */
    public Texture createTexture(Texture.TextureDescriptor descriptor) {
        if (closed.get()) {
            throw new IllegalStateException("Device is closed");
        }
        
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            log.debug("Creating mock texture");
            return new Texture(MemorySegment.NULL, this, descriptor);
        }
        
        // TODO: Implement native texture creation with WebGPU
        log.debug("Creating native texture");
        
        // For now, return a mock texture
        return new Texture(MemorySegment.NULL, this, descriptor);
    }
    
    /**
     * Create a sampler.
     * 
     * @param descriptor the sampler descriptor
     * @return the sampler
     */
    public Sampler createSampler(Sampler.SamplerDescriptor descriptor) {
        if (closed.get()) {
            throw new IllegalStateException("Device is closed");
        }
        
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            log.debug("Creating mock sampler");
            return new Sampler(MemorySegment.NULL, this, descriptor);
        }
        
        // TODO: Implement native sampler creation with WebGPU
        log.debug("Creating native sampler");
        
        // For now, return a mock sampler
        return new Sampler(MemorySegment.NULL, this, descriptor);
    }
    
    /**
     * Create a render pipeline.
     * 
     * @param descriptor the render pipeline descriptor
     * @return the render pipeline
     */
    public RenderPipeline createRenderPipeline(RenderPipeline.RenderPipelineDescriptor descriptor) {
        if (closed.get()) {
            throw new IllegalStateException("Device is closed");
        }
        
        try (var arena = Arena.ofConfined()) {
            // Allocate render pipeline descriptor
            var nativeDesc = arena.allocate(256); // Estimated size for render pipeline descriptor
            
            // Set basic fields
            nativeDesc.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // nextInChain
            var label = descriptor.label != null ? 
                WebGPUNative.toCString(descriptor.label, arena) : MemorySegment.NULL;
            nativeDesc.set(ValueLayout.ADDRESS, 8, label); // label
            
            // Set layout
            if (descriptor.layout != null) {
                nativeDesc.set(ValueLayout.ADDRESS, 16, descriptor.layout.getHandle());
            } else {
                nativeDesc.set(ValueLayout.ADDRESS, 16, MemorySegment.NULL); // auto layout
            }
            
            // Set vertex state
            if (descriptor.vertex != null) {
                var vertexOffset = 24;
                nativeDesc.set(ValueLayout.ADDRESS, vertexOffset, MemorySegment.NULL); // nextInChain
                nativeDesc.set(ValueLayout.ADDRESS, vertexOffset + 8, descriptor.vertex.module.getHandle());
                var entryPoint = WebGPUNative.toCString(descriptor.vertex.entryPoint, arena);
                nativeDesc.set(ValueLayout.ADDRESS, vertexOffset + 16, entryPoint);
                
                // Set vertex buffers if present
                if (descriptor.vertex.buffers != null && descriptor.vertex.buffers.length > 0) {
                    var buffersArray = createVertexBufferLayouts(descriptor.vertex.buffers, arena);
                    nativeDesc.set(ValueLayout.JAVA_LONG, vertexOffset + 24, descriptor.vertex.buffers.length);
                    nativeDesc.set(ValueLayout.ADDRESS, vertexOffset + 32, buffersArray);
                } else {
                    nativeDesc.set(ValueLayout.JAVA_LONG, vertexOffset + 24, 0L);
                    nativeDesc.set(ValueLayout.ADDRESS, vertexOffset + 32, MemorySegment.NULL);
                }
            }
            
            // Set primitive state
            if (descriptor.primitive != null) {
                var primitiveOffset = 64;
                nativeDesc.set(ValueLayout.ADDRESS, primitiveOffset, MemorySegment.NULL); // nextInChain
                nativeDesc.set(ValueLayout.JAVA_INT, primitiveOffset + 8, descriptor.primitive.topology.getValue());
                if (descriptor.primitive.stripIndexFormat != null) {
                    nativeDesc.set(ValueLayout.JAVA_INT, primitiveOffset + 12, descriptor.primitive.stripIndexFormat.getValue());
                }
                nativeDesc.set(ValueLayout.JAVA_INT, primitiveOffset + 16, descriptor.primitive.frontFace.getValue());
                nativeDesc.set(ValueLayout.JAVA_INT, primitiveOffset + 20, descriptor.primitive.cullMode.getValue());
            }
            
            // Set fragment state if present
            if (descriptor.fragment != null) {
                var fragmentOffset = 88;
                nativeDesc.set(ValueLayout.ADDRESS, fragmentOffset, MemorySegment.NULL); // nextInChain
                nativeDesc.set(ValueLayout.ADDRESS, fragmentOffset + 8, descriptor.fragment.module.getHandle());
                var entryPoint = WebGPUNative.toCString(descriptor.fragment.entryPoint, arena);
                nativeDesc.set(ValueLayout.ADDRESS, fragmentOffset + 16, entryPoint);
                
                // Set color targets
                if (descriptor.fragment.targets != null && descriptor.fragment.targets.length > 0) {
                    var targetsArray = createColorTargets(descriptor.fragment.targets, arena);
                    nativeDesc.set(ValueLayout.JAVA_LONG, fragmentOffset + 24, descriptor.fragment.targets.length);
                    nativeDesc.set(ValueLayout.ADDRESS, fragmentOffset + 32, targetsArray);
                }
            }
            
            // TODO: Call native wgpuDeviceCreateRenderPipeline when available
            log.debug("Creating render pipeline (stub implementation)");
            return new RenderPipeline(MemorySegment.NULL, this);
        }
    }
    
    /**
     * Create vertex buffer layouts for native API.
     */
    private MemorySegment createVertexBufferLayouts(RenderPipeline.VertexBufferLayout[] buffers, Arena arena) {
        var layoutSize = 32; // Estimated size per vertex buffer layout
        var array = arena.allocate(layoutSize * buffers.length);
        
        for (int i = 0; i < buffers.length; i++) {
            var buffer = buffers[i];
            var offset = i * layoutSize;
            
            array.set(ValueLayout.JAVA_LONG, offset, buffer.arrayStride);
            array.set(ValueLayout.JAVA_INT, offset + 8, buffer.stepMode.getValue());
            
            // Set attributes
            if (buffer.attributes != null && buffer.attributes.length > 0) {
                var attrsArray = createVertexAttributes(buffer.attributes, arena);
                array.set(ValueLayout.JAVA_LONG, offset + 12, buffer.attributes.length);
                array.set(ValueLayout.ADDRESS, offset + 20, attrsArray);
            }
        }
        
        return array;
    }
    
    /**
     * Create vertex attributes for native API.
     */
    private MemorySegment createVertexAttributes(RenderPipeline.VertexAttribute[] attributes, Arena arena) {
        var attrSize = 16; // Size per vertex attribute
        var array = arena.allocate(attrSize * attributes.length);
        
        for (int i = 0; i < attributes.length; i++) {
            var attr = attributes[i];
            var offset = i * attrSize;
            
            array.set(ValueLayout.JAVA_INT, offset, attr.format.getValue());
            array.set(ValueLayout.JAVA_LONG, offset + 4, attr.offset);
            array.set(ValueLayout.JAVA_INT, offset + 12, attr.shaderLocation);
        }
        
        return array;
    }
    
    /**
     * Create color targets for native API.
     */
    private MemorySegment createColorTargets(RenderPipeline.ColorTargetState[] targets, Arena arena) {
        var targetSize = 32; // Estimated size per color target
        var array = arena.allocate(targetSize * targets.length);
        
        for (int i = 0; i < targets.length; i++) {
            var target = targets[i];
            var offset = i * targetSize;
            
            array.set(ValueLayout.ADDRESS, offset, MemorySegment.NULL); // nextInChain
            array.set(ValueLayout.JAVA_INT, offset + 8, target.format.getValue());
            
            // Set blend state if present
            if (target.blend != null) {
                // Color blend
                array.set(ValueLayout.JAVA_INT, offset + 12, target.blend.color.operation.getValue());
                array.set(ValueLayout.JAVA_INT, offset + 16, target.blend.color.srcFactor.getValue());
                array.set(ValueLayout.JAVA_INT, offset + 20, target.blend.color.dstFactor.getValue());
                
                // Alpha blend
                array.set(ValueLayout.JAVA_INT, offset + 24, target.blend.alpha.operation.getValue());
                array.set(ValueLayout.JAVA_INT, offset + 28, target.blend.alpha.srcFactor.getValue());
                array.set(ValueLayout.JAVA_INT, offset + 32, target.blend.alpha.dstFactor.getValue());
            }
            
            array.set(ValueLayout.JAVA_INT, offset + 36, target.writeMask);
        }
        
        return array;
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
    
    /**
     * Set the instance reference for event processing.
     * 
     * @param instance the WebGPU instance
     */
    public void setInstance(Instance instance) {
        this.instance = instance;
    }
    
    /**
     * Get the instance reference.
     * 
     * @return the instance, or null if not set
     */
    public Instance getInstance() {
        return instance;
    }
    
    /**
     * Process events for this device.
     * This calls wgpuInstanceProcessEvents which is required for async operations.
     * 
     * @return true if events were processed successfully
     */
    public boolean processEvents() {
        if (instance != null) {
            instance.processEvents();
            return true;
        } else {
            log.warn("No instance set for device - cannot process events. " +
                    "Set instance with setInstance() for proper async operation support.");
            return false;
        }
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
            
            // Release the device through native API
            WebGPU.releaseDevice(handle);
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
     * Bind group layout descriptor.
     */
    public static class BindGroupLayoutDescriptor {
        private String label;
        private java.util.List<BindGroupLayoutEntry> entries = new java.util.ArrayList<>();
        
        public BindGroupLayoutDescriptor withLabel(String label) {
            this.label = label;
            return this;
        }
        
        public BindGroupLayoutDescriptor withEntry(BindGroupLayoutEntry entry) {
            this.entries.add(entry);
            return this;
        }
        
        public String getLabel() {
            return label;
        }
        
        public java.util.List<BindGroupLayoutEntry> getEntries() {
            return entries;
        }
    }
    
    /**
     * Bind group layout entry.
     */
    public static class BindGroupLayoutEntry {
        public int binding;
        public int visibility;
        public BufferBindingLayout buffer;
        // TODO: Add sampler, texture, storage texture
        
        public BindGroupLayoutEntry(int binding, int visibility) {
            this.binding = binding;
            this.visibility = visibility;
        }
        
        public BindGroupLayoutEntry withBuffer(BufferBindingLayout buffer) {
            this.buffer = buffer;
            return this;
        }
    }
    
    /**
     * Buffer binding layout.
     */
    public static class BufferBindingLayout {
        public int type;
        public boolean hasDynamicOffset;
        public long minBindingSize;
        
        public BufferBindingLayout(int type) {
            this.type = type;
        }
        
        public BufferBindingLayout withDynamicOffset(boolean dynamic) {
            this.hasDynamicOffset = dynamic;
            return this;
        }
        
        public BufferBindingLayout withMinBindingSize(long size) {
            this.minBindingSize = size;
            return this;
        }
    }
    
    /**
     * Bind group descriptor.
     */
    public static class BindGroupDescriptor {
        private String label;
        private BindGroupLayout layout;
        private java.util.List<BindGroupEntry> entries = new java.util.ArrayList<>();
        
        public BindGroupDescriptor(BindGroupLayout layout) {
            this.layout = layout;
        }
        
        public BindGroupDescriptor withLabel(String label) {
            this.label = label;
            return this;
        }
        
        public BindGroupDescriptor withEntry(BindGroupEntry entry) {
            this.entries.add(entry);
            return this;
        }
        
        public String getLabel() {
            return label;
        }
        
        public BindGroupLayout getLayout() {
            return layout;
        }
        
        public java.util.List<BindGroupEntry> getEntries() {
            return entries;
        }
    }
    
    /**
     * Bind group entry.
     */
    public static class BindGroupEntry {
        public int binding;
        public Buffer buffer;
        public long offset;
        public long size;
        public Texture.TextureView textureView;
        public Sampler sampler;
        
        public BindGroupEntry(int binding) {
            this.binding = binding;
        }
        
        public BindGroupEntry withBuffer(Buffer buffer, long offset, long size) {
            this.buffer = buffer;
            this.offset = offset;
            this.size = size;
            return this;
        }
    }
    
    /**
     * Pipeline layout descriptor.
     */
    public static class PipelineLayoutDescriptor {
        private String label;
        private java.util.List<BindGroupLayout> bindGroupLayouts = new java.util.ArrayList<>();
        
        public PipelineLayoutDescriptor withLabel(String label) {
            this.label = label;
            return this;
        }
        
        public PipelineLayoutDescriptor addBindGroupLayout(BindGroupLayout layout) {
            this.bindGroupLayouts.add(layout);
            return this;
        }
        
        public String getLabel() {
            return label;
        }
        
        public java.util.List<BindGroupLayout> getBindGroupLayouts() {
            return bindGroupLayouts;
        }
    }
    
    /**
     * Compute pipeline descriptor.
     */
    public static class ComputePipelineDescriptor {
        private String label;
        private ShaderModule computeModule;
        private String entryPoint = "main";
        private PipelineLayout layout;
        
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
        
        public ComputePipelineDescriptor withLayout(PipelineLayout layout) {
            this.layout = layout;
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
        
        public PipelineLayout getLayout() {
            return layout;
        }
    }
}