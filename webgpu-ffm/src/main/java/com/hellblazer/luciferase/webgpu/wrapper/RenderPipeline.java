package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe wrapper for WebGPU Render Pipeline.
 * Defines the complete rendering state including shaders, vertex layout, and rasterization.
 */
public class RenderPipeline implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RenderPipeline.class);
    
    private final MemorySegment handle;
    private final Device device;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    /**
     * Create a render pipeline wrapper.
     * 
     * @param handle the native render pipeline handle
     * @param device the device that created this pipeline
     */
    RenderPipeline(MemorySegment handle, Device device) {
        this.handle = handle;
        this.device = device;
        log.debug("Created render pipeline");
    }
    
    /**
     * Get the native handle.
     * 
     * @return the native handle
     */
    public MemorySegment getHandle() {
        return handle;
    }
    
    /**
     * Get the bind group layout at the specified index.
     * 
     * @param index the layout index
     * @return the bind group layout
     */
    public BindGroupLayout getBindGroupLayout(int index) {
        // TODO: Implement native getBindGroupLayout
        log.debug("Getting bind group layout at index {}", index);
        return new BindGroupLayout(device, MemorySegment.NULL);
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // TODO: Release native render pipeline
            log.debug("Released render pipeline");
        }
    }
    
    /**
     * Render pipeline descriptor for creating render pipelines.
     */
    public static class RenderPipelineDescriptor {
        private String label;
        private PipelineLayout layout;
        private VertexState vertex;
        private PrimitiveState primitive = new PrimitiveState();
        private DepthStencilState depthStencil;
        private MultisampleState multisample = new MultisampleState();
        private FragmentState fragment;
        
        public RenderPipelineDescriptor withLabel(String label) {
            this.label = label;
            return this;
        }
        
        public RenderPipelineDescriptor withLayout(PipelineLayout layout) {
            this.layout = layout;
            return this;
        }
        
        public RenderPipelineDescriptor withVertex(VertexState vertex) {
            this.vertex = vertex;
            return this;
        }
        
        public RenderPipelineDescriptor withPrimitive(PrimitiveState primitive) {
            this.primitive = primitive;
            return this;
        }
        
        public RenderPipelineDescriptor withDepthStencil(DepthStencilState depthStencil) {
            this.depthStencil = depthStencil;
            return this;
        }
        
        public RenderPipelineDescriptor withMultisample(MultisampleState multisample) {
            this.multisample = multisample;
            return this;
        }
        
        public RenderPipelineDescriptor withFragment(FragmentState fragment) {
            this.fragment = fragment;
            return this;
        }
    }
    
    /**
     * Vertex state configuration.
     */
    public static class VertexState {
        public ShaderModule module;
        public String entryPoint = "main";
        public VertexBufferLayout[] buffers;
        
        public VertexState(ShaderModule module) {
            this.module = module;
        }
        
        public VertexState withEntryPoint(String entryPoint) {
            this.entryPoint = entryPoint;
            return this;
        }
        
        public VertexState withBuffers(VertexBufferLayout... buffers) {
            this.buffers = buffers;
            return this;
        }
    }
    
    /**
     * Fragment state configuration.
     */
    public static class FragmentState {
        public ShaderModule module;
        public String entryPoint = "main";
        public ColorTargetState[] targets;
        
        public FragmentState(ShaderModule module) {
            this.module = module;
        }
        
        public FragmentState withEntryPoint(String entryPoint) {
            this.entryPoint = entryPoint;
            return this;
        }
        
        public FragmentState withTargets(ColorTargetState... targets) {
            this.targets = targets;
            return this;
        }
    }
    
    /**
     * Primitive state configuration.
     */
    public static class PrimitiveState {
        public PrimitiveTopology topology = PrimitiveTopology.TRIANGLE_LIST;
        public IndexFormat stripIndexFormat;
        public FrontFace frontFace = FrontFace.CCW;
        public CullMode cullMode = CullMode.NONE;
        public boolean unclippedDepth = false;
    }
    
    /**
     * Depth/stencil state configuration.
     */
    public static class DepthStencilState {
        public Texture.TextureFormat format;
        public boolean depthWriteEnabled;
        public Sampler.CompareFunction depthCompare;
        public StencilState stencilFront;
        public StencilState stencilBack;
        public int stencilReadMask = 0xFF;
        public int stencilWriteMask = 0xFF;
        public float depthBias = 0;
        public float depthBiasSlopeScale = 0;
        public float depthBiasClamp = 0;
    }
    
    /**
     * Multisample state configuration.
     */
    public static class MultisampleState {
        public int count = 1;
        public int mask = 0xFFFFFFFF;
        public boolean alphaToCoverageEnabled = false;
    }
    
    /**
     * Vertex buffer layout.
     */
    public static class VertexBufferLayout {
        public long arrayStride;
        public VertexStepMode stepMode = VertexStepMode.VERTEX;
        public VertexAttribute[] attributes;
        
        public VertexBufferLayout(long arrayStride) {
            this.arrayStride = arrayStride;
        }
        
        public VertexBufferLayout withStepMode(VertexStepMode stepMode) {
            this.stepMode = stepMode;
            return this;
        }
        
        public VertexBufferLayout withAttributes(VertexAttribute... attributes) {
            this.attributes = attributes;
            return this;
        }
    }
    
    /**
     * Vertex attribute.
     */
    public static class VertexAttribute {
        public VertexFormat format;
        public long offset;
        public int shaderLocation;
        
        public VertexAttribute(VertexFormat format, long offset, int shaderLocation) {
            this.format = format;
            this.offset = offset;
            this.shaderLocation = shaderLocation;
        }
    }
    
    /**
     * Color target state.
     */
    public static class ColorTargetState {
        public Texture.TextureFormat format;
        public BlendState blend;
        public int writeMask = 0xF; // COLOR_WRITE_ALL
        
        public ColorTargetState(Texture.TextureFormat format) {
            this.format = format;
        }
        
        public ColorTargetState withBlend(BlendState blend) {
            this.blend = blend;
            return this;
        }
        
        public ColorTargetState withWriteMask(int writeMask) {
            this.writeMask = writeMask;
            return this;
        }
    }
    
    /**
     * Blend state.
     */
    public static class BlendState {
        public BlendComponent color = new BlendComponent();
        public BlendComponent alpha = new BlendComponent();
        
        public static class BlendComponent {
            public BlendOperation operation = BlendOperation.ADD;
            public BlendFactor srcFactor = BlendFactor.ONE;
            public BlendFactor dstFactor = BlendFactor.ZERO;
        }
    }
    
    /**
     * Stencil state.
     */
    public static class StencilState {
        public Sampler.CompareFunction compare = Sampler.CompareFunction.ALWAYS;
        public StencilOperation failOp = StencilOperation.KEEP;
        public StencilOperation depthFailOp = StencilOperation.KEEP;
        public StencilOperation passOp = StencilOperation.KEEP;
    }
    
    // Enums
    
    public enum PrimitiveTopology {
        POINT_LIST(0),
        LINE_LIST(1),
        LINE_STRIP(2),
        TRIANGLE_LIST(3),
        TRIANGLE_STRIP(4);
        
        private final int value;
        PrimitiveTopology(int value) { this.value = value; }
        public int getValue() { return value; }
    }
    
    public enum FrontFace {
        CCW(0),
        CW(1);
        
        private final int value;
        FrontFace(int value) { this.value = value; }
        public int getValue() { return value; }
    }
    
    public enum CullMode {
        NONE(0),
        FRONT(1),
        BACK(2);
        
        private final int value;
        CullMode(int value) { this.value = value; }
        public int getValue() { return value; }
    }
    
    public enum VertexStepMode {
        VERTEX(0),
        INSTANCE(1);
        
        private final int value;
        VertexStepMode(int value) { this.value = value; }
        public int getValue() { return value; }
    }
    
    public enum VertexFormat {
        UINT8x2(0),
        UINT8x4(1),
        SINT8x2(2),
        SINT8x4(3),
        UNORM8x2(4),
        UNORM8x4(5),
        SNORM8x2(6),
        SNORM8x4(7),
        UINT16x2(8),
        UINT16x4(9),
        SINT16x2(10),
        SINT16x4(11),
        UNORM16x2(12),
        UNORM16x4(13),
        SNORM16x2(14),
        SNORM16x4(15),
        FLOAT16x2(16),
        FLOAT16x4(17),
        FLOAT32(18),
        FLOAT32x2(19),
        FLOAT32x3(20),
        FLOAT32x4(21),
        UINT32(22),
        UINT32x2(23),
        UINT32x3(24),
        UINT32x4(25),
        SINT32(26),
        SINT32x2(27),
        SINT32x3(28),
        SINT32x4(29);
        
        private final int value;
        VertexFormat(int value) { this.value = value; }
        public int getValue() { return value; }
    }
    
    public enum BlendOperation {
        ADD(0),
        SUBTRACT(1),
        REVERSE_SUBTRACT(2),
        MIN(3),
        MAX(4);
        
        private final int value;
        BlendOperation(int value) { this.value = value; }
        public int getValue() { return value; }
    }
    
    public enum BlendFactor {
        ZERO(0),
        ONE(1),
        SRC(2),
        ONE_MINUS_SRC(3),
        SRC_ALPHA(4),
        ONE_MINUS_SRC_ALPHA(5),
        DST(6),
        ONE_MINUS_DST(7),
        DST_ALPHA(8),
        ONE_MINUS_DST_ALPHA(9),
        SRC_ALPHA_SATURATED(10),
        CONSTANT(11),
        ONE_MINUS_CONSTANT(12);
        
        private final int value;
        BlendFactor(int value) { this.value = value; }
        public int getValue() { return value; }
    }
    
    public enum StencilOperation {
        KEEP(0),
        ZERO(1),
        REPLACE(2),
        INVERT(3),
        INCREMENT_CLAMP(4),
        DECREMENT_CLAMP(5),
        INCREMENT_WRAP(6),
        DECREMENT_WRAP(7);
        
        private final int value;
        StencilOperation(int value) { this.value = value; }
        public int getValue() { return value; }
    }
    
    public enum IndexFormat {
        UINT16(0),
        UINT32(1);
        
        private final int value;
        IndexFormat(int value) { this.value = value; }
        public int getValue() { return value; }
    }
}