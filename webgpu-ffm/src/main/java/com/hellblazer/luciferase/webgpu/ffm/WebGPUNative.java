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
    
    /**
     * wgpuDeviceCreateBindGroupLayout(
     *     WGPUDevice device,
     *     const WGPUBindGroupLayoutDescriptor* descriptor
     * ) -> WGPUBindGroupLayout
     */
    public static final FunctionDescriptor DESC_wgpuDeviceCreateBindGroupLayout = 
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,  // return: WGPUBindGroupLayout
            ValueLayout.ADDRESS,  // device
            ValueLayout.ADDRESS   // descriptor
        );
    
    /**
     * wgpuBindGroupLayoutRelease(WGPUBindGroupLayout bindGroupLayout) -> void
     */
    public static final FunctionDescriptor DESC_wgpuBindGroupLayoutRelease = 
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    
    /**
     * wgpuDeviceCreateBindGroup(
     *     WGPUDevice device,
     *     const WGPUBindGroupDescriptor* descriptor
     * ) -> WGPUBindGroup
     */
    public static final FunctionDescriptor DESC_wgpuDeviceCreateBindGroup = 
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,  // return: WGPUBindGroup
            ValueLayout.ADDRESS,  // device
            ValueLayout.ADDRESS   // descriptor
        );
    
    /**
     * wgpuBindGroupRelease(WGPUBindGroup bindGroup) -> void
     */
    public static final FunctionDescriptor DESC_wgpuBindGroupRelease = 
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    
    /**
     * wgpuComputePassEncoderSetBindGroup(
     *     WGPUComputePassEncoder computePassEncoder,
     *     uint32_t groupIndex,
     *     WGPUBindGroup group,
     *     size_t dynamicOffsetCount,
     *     const uint32_t* dynamicOffsets
     * ) -> void
     */
    public static final FunctionDescriptor DESC_wgpuComputePassEncoderSetBindGroup = 
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS,     // computePassEncoder
            ValueLayout.JAVA_INT,    // groupIndex
            ValueLayout.ADDRESS,     // group
            ValueLayout.JAVA_LONG,   // dynamicOffsetCount
            ValueLayout.ADDRESS      // dynamicOffsets
        );
    
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
    
    // Shader stage flags
    public static final int SHADER_STAGE_VERTEX = 0x00000001;
    public static final int SHADER_STAGE_FRAGMENT = 0x00000002;
    public static final int SHADER_STAGE_COMPUTE = 0x00000004;
    
    // Buffer binding types
    public static final int BUFFER_BINDING_TYPE_UNIFORM = 0x00000001;
    public static final int BUFFER_BINDING_TYPE_STORAGE = 0x00000002;
    public static final int BUFFER_BINDING_TYPE_READ_ONLY_STORAGE = 0x00000003;
    
    // Texture formats (common ones)
    public static final int TEXTURE_FORMAT_UNDEFINED = 0x00000000;
    public static final int TEXTURE_FORMAT_R8_UNORM = 0x00000001;
    public static final int TEXTURE_FORMAT_R8_SNORM = 0x00000002;
    public static final int TEXTURE_FORMAT_R8_UINT = 0x00000003;
    public static final int TEXTURE_FORMAT_R8_SINT = 0x00000004;
    public static final int TEXTURE_FORMAT_RGBA8_UNORM = 0x00000018;
    public static final int TEXTURE_FORMAT_RGBA8_UNORM_SRGB = 0x00000019;
    public static final int TEXTURE_FORMAT_BGRA8_UNORM = 0x00000017; // Dawn: BGRA8Unorm
    public static final int TEXTURE_FORMAT_BGRA8_UNORM_SRGB = 0x00000018; // Dawn: BGRA8UnormSrgb
    
    // Texture usage flags
    public static final int TEXTURE_USAGE_COPY_SRC = 0x00000001;
    public static final int TEXTURE_USAGE_COPY_DST = 0x00000002;
    public static final int TEXTURE_USAGE_TEXTURE_BINDING = 0x00000004;
    public static final int TEXTURE_USAGE_STORAGE_BINDING = 0x00000008;
    public static final int TEXTURE_USAGE_RENDER_ATTACHMENT = 0x00000010;
    
    // Present modes
    public static final int PRESENT_MODE_FIFO = 0x00000000;         // VSync (default)
    public static final int PRESENT_MODE_FIFO_RELAXED = 0x00000001; // Adaptive VSync
    public static final int PRESENT_MODE_IMMEDIATE = 0x00000002;    // No VSync
    public static final int PRESENT_MODE_MAILBOX = 0x00000003;      // Triple buffering
    
    // Composite alpha modes
    public static final int COMPOSITE_ALPHA_MODE_AUTO = 0x00000000;
    public static final int COMPOSITE_ALPHA_MODE_OPAQUE = 0x00000001;
    public static final int COMPOSITE_ALPHA_MODE_PREMULTIPLIED = 0x00000002;
    public static final int COMPOSITE_ALPHA_MODE_UNPREMULTIPLIED = 0x00000003;
    public static final int COMPOSITE_ALPHA_MODE_INHERIT = 0x00000004;
    
    // Surface get current texture status
    public static final int SURFACE_GET_CURRENT_TEXTURE_STATUS_SUCCESS = 0x00000000;
    public static final int SURFACE_GET_CURRENT_TEXTURE_STATUS_TIMEOUT = 0x00000001;
    public static final int SURFACE_GET_CURRENT_TEXTURE_STATUS_OUTDATED = 0x00000002;
    public static final int SURFACE_GET_CURRENT_TEXTURE_STATUS_LOST = 0x00000003;
    
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
         * Has an embedded ChainedStruct at the beginning
         */
        public static final StructLayout SHADER_MODULE_WGSL_DESCRIPTOR = MemoryLayout.structLayout(
            // Embedded ChainedStruct
            ValueLayout.ADDRESS.withName("chain_next"),
            ValueLayout.JAVA_INT.withName("chain_sType"),
            ValueLayout.JAVA_INT.withName("padding"),  // Padding for alignment
            // WGSL-specific field
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
        
        /**
         * Layout for WGPUProgrammableStageDescriptor
         */
        public static final StructLayout PROGRAMMABLE_STAGE_DESCRIPTOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("module"),
            ValueLayout.ADDRESS.withName("entryPoint"),
            ValueLayout.JAVA_LONG.withName("constantCount"),
            ValueLayout.ADDRESS.withName("constants")
        ).withByteAlignment(8);
        
        /**
         * Layout for WGPUComputePipelineDescriptor
         * Contains an embedded ProgrammableStageDescriptor for the compute stage
         */
        public static final StructLayout COMPUTE_PIPELINE_DESCRIPTOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("label"),
            ValueLayout.ADDRESS.withName("layout"),
            // Embedded compute stage (ProgrammableStageDescriptor)
            ValueLayout.ADDRESS.withName("compute_nextInChain"),
            ValueLayout.ADDRESS.withName("compute_module"),
            ValueLayout.ADDRESS.withName("compute_entryPoint"),
            ValueLayout.JAVA_LONG.withName("compute_constantCount"),
            ValueLayout.ADDRESS.withName("compute_constants")
        ).withByteAlignment(8);
        
        /**
         * Layout for WGPUBindGroupLayoutEntry
         */
        public static final StructLayout BIND_GROUP_LAYOUT_ENTRY = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.JAVA_INT.withName("binding"),
            ValueLayout.JAVA_INT.withName("visibility"),
            // Buffer binding
            ValueLayout.ADDRESS.withName("buffer_nextInChain"),
            ValueLayout.JAVA_INT.withName("buffer_type"),
            ValueLayout.JAVA_INT.withName("buffer_hasDynamicOffset"),
            ValueLayout.JAVA_LONG.withName("buffer_minBindingSize"),
            // Sampler binding
            ValueLayout.ADDRESS.withName("sampler_nextInChain"),
            ValueLayout.JAVA_INT.withName("sampler_type"),
            ValueLayout.JAVA_INT.withName("padding1"),
            // Texture binding  
            ValueLayout.ADDRESS.withName("texture_nextInChain"),
            ValueLayout.JAVA_INT.withName("texture_sampleType"),
            ValueLayout.JAVA_INT.withName("texture_viewDimension"),
            ValueLayout.JAVA_INT.withName("texture_multisampled"),
            ValueLayout.JAVA_INT.withName("padding2"),
            // Storage texture binding
            ValueLayout.ADDRESS.withName("storageTexture_nextInChain"),
            ValueLayout.JAVA_INT.withName("storageTexture_access"),
            ValueLayout.JAVA_INT.withName("storageTexture_format"),
            ValueLayout.JAVA_INT.withName("storageTexture_viewDimension"),
            ValueLayout.JAVA_INT.withName("padding3")
        ).withByteAlignment(8);
        
        /**
         * Layout for WGPUPipelineLayoutDescriptor
         */
        public static final StructLayout PIPELINE_LAYOUT_DESCRIPTOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("label"),
            ValueLayout.JAVA_LONG.withName("bindGroupLayoutCount"),
            ValueLayout.ADDRESS.withName("bindGroupLayouts")
        ).withByteAlignment(8);
        
        /**
         * Layout for WGPUBindGroupLayoutDescriptor
         */
        public static final StructLayout BIND_GROUP_LAYOUT_DESCRIPTOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("label"),
            ValueLayout.JAVA_LONG.withName("entryCount"),
            ValueLayout.ADDRESS.withName("entries")
        ).withByteAlignment(8);
        
        /**
         * Layout for WGPUBindGroupEntry
         */
        public static final StructLayout BIND_GROUP_ENTRY = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.JAVA_INT.withName("binding"),
            ValueLayout.JAVA_INT.withName("padding"),
            ValueLayout.ADDRESS.withName("buffer"),
            ValueLayout.JAVA_LONG.withName("offset"),
            ValueLayout.JAVA_LONG.withName("size"),
            ValueLayout.ADDRESS.withName("sampler"),
            ValueLayout.ADDRESS.withName("textureView")
        ).withByteAlignment(8);
        
        /**
         * Layout for WGPUBindGroupDescriptor
         */
        public static final StructLayout BIND_GROUP_DESCRIPTOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("label"),
            ValueLayout.ADDRESS.withName("layout"),
            ValueLayout.JAVA_LONG.withName("entryCount"),
            ValueLayout.ADDRESS.withName("entries")
        ).withByteAlignment(8);
        
        /**
         * Surface configuration structure for swap chain setup.
         * typedef struct WGPUSurfaceConfiguration {
         *     WGPUChainedStruct const * nextInChain;
         *     WGPUDevice device;
         *     WGPUTextureFormat format;
         *     WGPUTextureUsageFlags usage;
         *     size_t viewFormatCount;
         *     WGPUTextureFormat const * viewFormats;
         *     WGPUCompositeAlphaMode alphaMode;
         *     uint32_t width;
         *     uint32_t height;
         *     WGPUPresentMode presentMode;
         * } WGPUSurfaceConfiguration;
         */
        public static final StructLayout SURFACE_CONFIGURATION = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),             // 0-7
            ValueLayout.ADDRESS.withName("device"),                  // 8-15
            ValueLayout.JAVA_INT.withName("format"),                 // 16-19
            ValueLayout.JAVA_INT.withName("usage"),                  // 20-23
            MemoryLayout.paddingLayout(8),                          // 24-31 (padding for 8-byte alignment)
            ValueLayout.JAVA_LONG.withName("viewFormatCount"),      // 32-39 (8-byte aligned)
            ValueLayout.ADDRESS.withName("viewFormats"),            // 40-47
            ValueLayout.JAVA_INT.withName("alphaMode"),             // 48-51
            ValueLayout.JAVA_INT.withName("width"),                 // 52-55
            ValueLayout.JAVA_INT.withName("height"),                // 56-59
            ValueLayout.JAVA_INT.withName("presentMode")            // 60-63
        ).withName("WGPUSurfaceConfiguration");
        
        /**
         * Surface texture structure returned by getCurrentTexture.
         * typedef struct WGPUSurfaceTexture {
         *     WGPUTexture texture;
         *     WGPUBool suboptimal;
         *     WGPUSurfaceGetCurrentTextureStatus status;
         * } WGPUSurfaceTexture;
         */
        public static final StructLayout SURFACE_TEXTURE = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("texture"),                 // 0
            ValueLayout.JAVA_INT.withName("status"),                 // 8
            ValueLayout.JAVA_BOOLEAN.withName("suboptimal"),        // 12
            MemoryLayout.paddingLayout(3)                           // 13 (padding)
        ).withName("WGPUSurfaceTexture");
        
        /**
         * Surface descriptor for Metal on macOS.
         */
        public static final StructLayout SURFACE_DESCRIPTOR_FROM_METAL_LAYER = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("next"),
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("layer")
        ).withName("WGPUSurfaceDescriptorFromMetalLayer");
        
        /**
         * Surface descriptor for Windows HWND.
         */
        public static final StructLayout SURFACE_DESCRIPTOR_FROM_WINDOWS_HWND = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("next"),
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("hinstance"),
            ValueLayout.ADDRESS.withName("hwnd")
        ).withName("WGPUSurfaceDescriptorFromWindowsHWND");
        
        /**
         * Surface descriptor for X11 Window.
         */
        public static final StructLayout SURFACE_DESCRIPTOR_FROM_XLIB_WINDOW = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("next"),
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("display"),
            ValueLayout.JAVA_LONG.withName("window")
        ).withName("WGPUSurfaceDescriptorFromXlibWindow");
        
        /**
         * Surface descriptor for Wayland Surface.
         */
        public static final StructLayout SURFACE_DESCRIPTOR_FROM_WAYLAND_SURFACE = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("next"),
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("display"),
            ValueLayout.ADDRESS.withName("surface")
        ).withName("WGPUSurfaceDescriptorFromWaylandSurface");
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