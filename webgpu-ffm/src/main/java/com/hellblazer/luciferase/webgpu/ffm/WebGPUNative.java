package com.hellblazer.luciferase.webgpu.ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Manual FFM bindings for WebGPU native functions.
 * This is a hand-written binding until jextract supports Java 24.
 * 
 * Based on wgpu-native v25.0.2.1
 */
public class WebGPUNative {
    
    // Function descriptors for WebGPU functions
    
    /**
     * wgpuCreateInstance(const WGPUInstanceDescriptor* descriptor) -> WGPUInstance
     */
    public static final FunctionDescriptor DESC_wgpuCreateInstance = 
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    
    /**
     * wgpuInstanceRelease(WGPUInstance instance) -> void
     */
    public static final FunctionDescriptor DESC_wgpuInstanceRelease = 
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    
    /**
     * wgpuInstanceRequestAdapter(
     *     WGPUInstance instance,
     *     const WGPURequestAdapterOptions* options,
     *     WGPUInstanceRequestAdapterCallback callback,
     *     void* userdata
     * ) -> void
     */
    public static final FunctionDescriptor DESC_wgpuInstanceRequestAdapter = 
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS,  // instance
            ValueLayout.ADDRESS,  // options
            ValueLayout.ADDRESS,  // callback
            ValueLayout.ADDRESS   // userdata
        );
    
    /**
     * wgpuAdapterRelease(WGPUAdapter adapter) -> void
     */
    public static final FunctionDescriptor DESC_wgpuAdapterRelease = 
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    
    /**
     * wgpuAdapterRequestDevice(
     *     WGPUAdapter adapter,
     *     const WGPUDeviceDescriptor* descriptor,
     *     WGPUAdapterRequestDeviceCallback callback,
     *     void* userdata
     * ) -> void
     */
    public static final FunctionDescriptor DESC_wgpuAdapterRequestDevice = 
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS,  // adapter
            ValueLayout.ADDRESS,  // descriptor
            ValueLayout.ADDRESS,  // callback
            ValueLayout.ADDRESS   // userdata
        );
    
    /**
     * wgpuDeviceRelease(WGPUDevice device) -> void
     */
    public static final FunctionDescriptor DESC_wgpuDeviceRelease = 
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    
    /**
     * wgpuDeviceGetQueue(WGPUDevice device) -> WGPUQueue
     */
    public static final FunctionDescriptor DESC_wgpuDeviceGetQueue = 
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    
    /**
     * wgpuQueueRelease(WGPUQueue queue) -> void
     */
    public static final FunctionDescriptor DESC_wgpuQueueRelease = 
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    
    /**
     * wgpuDeviceCreateBuffer(
     *     WGPUDevice device,
     *     const WGPUBufferDescriptor* descriptor
     * ) -> WGPUBuffer
     */
    public static final FunctionDescriptor DESC_wgpuDeviceCreateBuffer = 
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,  // return: WGPUBuffer
            ValueLayout.ADDRESS,  // device
            ValueLayout.ADDRESS   // descriptor
        );
    
    /**
     * wgpuBufferRelease(WGPUBuffer buffer) -> void
     */
    public static final FunctionDescriptor DESC_wgpuBufferRelease = 
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    
    /**
     * wgpuQueueWriteBuffer(
     *     WGPUQueue queue,
     *     WGPUBuffer buffer,
     *     uint64_t bufferOffset,
     *     const void* data,
     *     size_t size
     * ) -> void
     */
    public static final FunctionDescriptor DESC_wgpuQueueWriteBuffer = 
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS,     // queue
            ValueLayout.ADDRESS,     // buffer
            ValueLayout.JAVA_LONG,   // bufferOffset
            ValueLayout.ADDRESS,     // data
            ValueLayout.JAVA_LONG    // size
        );
    
    /**
     * wgpuBufferMapAsync(
     *     WGPUBuffer buffer,
     *     WGPUMapModeFlags mode,
     *     size_t offset,
     *     size_t size,
     *     WGPUBufferMapAsyncCallback callback,
     *     void* userdata
     * ) -> void
     */
    public static final FunctionDescriptor DESC_wgpuBufferMapAsync = 
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS,     // buffer
            ValueLayout.JAVA_INT,    // mode
            ValueLayout.JAVA_LONG,   // offset
            ValueLayout.JAVA_LONG,   // size
            ValueLayout.ADDRESS,     // callback
            ValueLayout.ADDRESS      // userdata
        );
    
    /**
     * wgpuBufferGetMappedRange(
     *     WGPUBuffer buffer,
     *     size_t offset,
     *     size_t size
     * ) -> void*
     */
    public static final FunctionDescriptor DESC_wgpuBufferGetMappedRange = 
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,     // return: void*
            ValueLayout.ADDRESS,     // buffer
            ValueLayout.JAVA_LONG,   // offset
            ValueLayout.JAVA_LONG    // size
        );
    
    /**
     * wgpuBufferUnmap(WGPUBuffer buffer) -> void
     */
    public static final FunctionDescriptor DESC_wgpuBufferUnmap = 
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    
    /**
     * wgpuDeviceCreateShaderModule(
     *     WGPUDevice device,
     *     const WGPUShaderModuleDescriptor* descriptor
     * ) -> WGPUShaderModule
     */
    public static final FunctionDescriptor DESC_wgpuDeviceCreateShaderModule = 
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,  // return: WGPUShaderModule
            ValueLayout.ADDRESS,  // device
            ValueLayout.ADDRESS   // descriptor
        );
    
    /**
     * wgpuShaderModuleRelease(WGPUShaderModule shaderModule) -> void
     */
    public static final FunctionDescriptor DESC_wgpuShaderModuleRelease = 
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    
    /**
     * wgpuDeviceCreateComputePipeline(
     *     WGPUDevice device,
     *     const WGPUComputePipelineDescriptor* descriptor
     * ) -> WGPUComputePipeline
     */
    public static final FunctionDescriptor DESC_wgpuDeviceCreateComputePipeline = 
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,  // return: WGPUComputePipeline
            ValueLayout.ADDRESS,  // device
            ValueLayout.ADDRESS   // descriptor
        );
    
    /**
     * wgpuComputePipelineRelease(WGPUComputePipeline computePipeline) -> void
     */
    public static final FunctionDescriptor DESC_wgpuComputePipelineRelease = 
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    
    // Buffer usage flags
    public static final int BUFFER_USAGE_MAP_READ = 0x00000001;
    public static final int BUFFER_USAGE_MAP_WRITE = 0x00000002;
    public static final int BUFFER_USAGE_COPY_SRC = 0x00000004;
    public static final int BUFFER_USAGE_COPY_DST = 0x00000008;
    public static final int BUFFER_USAGE_INDEX = 0x00000010;
    public static final int BUFFER_USAGE_VERTEX = 0x00000020;
    public static final int BUFFER_USAGE_UNIFORM = 0x00000040;
    public static final int BUFFER_USAGE_STORAGE = 0x00000080;
    public static final int BUFFER_USAGE_INDIRECT = 0x00000100;
    public static final int BUFFER_USAGE_QUERY_RESOLVE = 0x00000200;
    
    // Map mode flags
    public static final int MAP_MODE_READ = 0x00000001;
    public static final int MAP_MODE_WRITE = 0x00000002;
    
    // Power preference
    public static final int POWER_PREFERENCE_UNDEFINED = 0x00000000;
    public static final int POWER_PREFERENCE_LOW_POWER = 0x00000001;
    public static final int POWER_PREFERENCE_HIGH_PERFORMANCE = 0x00000002;
    
    /**
     * Helper class to build WebGPU descriptors using FFM.
     */
    public static class Descriptors {
        
        /**
         * Layout for WGPUInstanceDescriptor
         */
        public static final StructLayout INSTANCE_DESCRIPTOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain")
        ).withByteAlignment(8);
        
        /**
         * Layout for WGPURequestAdapterOptions
         */
        public static final StructLayout REQUEST_ADAPTER_OPTIONS = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("compatibleSurface"),
            ValueLayout.JAVA_INT.withName("powerPreference"),
            ValueLayout.JAVA_INT.withName("forceFallbackAdapter")
        ).withByteAlignment(8);
        
        /**
         * Layout for WGPUDeviceDescriptor
         */
        public static final StructLayout DEVICE_DESCRIPTOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("label"),
            ValueLayout.JAVA_LONG.withName("requiredFeaturesCount"),
            ValueLayout.ADDRESS.withName("requiredFeatures"),
            ValueLayout.ADDRESS.withName("requiredLimits"),
            ValueLayout.ADDRESS.withName("defaultQueue")
        ).withByteAlignment(8);
        
        /**
         * Layout for WGPUBufferDescriptor
         */
        public static final StructLayout BUFFER_DESCRIPTOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("label"),
            ValueLayout.JAVA_INT.withName("usage"),
            ValueLayout.JAVA_INT.withName("padding"),  // Padding for alignment
            ValueLayout.JAVA_LONG.withName("size"),
            ValueLayout.JAVA_INT.withName("mappedAtCreation"),
            ValueLayout.JAVA_INT.withName("padding2")  // Padding for alignment
        ).withByteAlignment(8);
        
        /**
         * Layout for WGPUShaderModuleWGSLDescriptor
         */
        public static final StructLayout SHADER_MODULE_WGSL_DESCRIPTOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("chain"),
            ValueLayout.ADDRESS.withName("code")
        ).withByteAlignment(8);
        
        /**
         * Layout for WGPUShaderModuleDescriptor
         */
        public static final StructLayout SHADER_MODULE_DESCRIPTOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("label"),
            ValueLayout.JAVA_LONG.withName("hintCount"),
            ValueLayout.ADDRESS.withName("hints")
        ).withByteAlignment(8);
    }
    
    /**
     * Helper to create a null-terminated C string in native memory.
     */
    public static MemorySegment toCString(String str, Arena arena) {
        if (str == null) {
            return MemorySegment.NULL;
        }
        byte[] bytes = (str + "\0").getBytes();
        var segment = arena.allocate(bytes.length);
        segment.copyFrom(MemorySegment.ofArray(bytes));
        return segment;
    }
    
    /**
     * Load a function from the symbol lookup.
     */
    public static MethodHandle loadFunction(
        Linker linker, 
        SymbolLookup lookup, 
        String name, 
        FunctionDescriptor descriptor
    ) throws NoSuchMethodException {
        var symbol = lookup.find(name)
            .orElseThrow(() -> new NoSuchMethodException("Symbol not found: " + name));
        return linker.downcallHandle(symbol, descriptor);
    }
}