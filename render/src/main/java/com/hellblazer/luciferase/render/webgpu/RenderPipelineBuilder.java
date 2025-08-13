package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.wrapper.Device.*;
import com.hellblazer.luciferase.webgpu.wrapper.RenderPipeline.*;
import com.hellblazer.luciferase.webgpu.wrapper.Texture.TextureFormat;
import com.hellblazer.luciferase.webgpu.wrapper.Sampler.CompareFunction;
import static com.hellblazer.luciferase.webgpu.wrapper.RenderPipeline.*;
import static com.hellblazer.luciferase.render.webgpu.WebGPUConstants.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder for creating WebGPU render pipelines with a fluent API.
 * Simplifies the complex pipeline creation process.
 */
public class RenderPipelineBuilder {
    private static final Logger log = LoggerFactory.getLogger(RenderPipelineBuilder.class);
    
    private final Device device;
    private String label = "Render Pipeline";
    
    // Shaders
    private ShaderModule vertexShader;
    private String vertexEntryPoint = "vs_main";
    private ShaderModule fragmentShader;
    private String fragmentEntryPoint = "fs_main";
    
    // Vertex state
    private final List<VertexBufferLayout> vertexBufferLayouts = new ArrayList<>();
    private final List<VertexAttribute> currentAttributes = new ArrayList<>();
    private long currentStride = 0;
    private VertexStepMode currentStepMode = VertexStepMode.VERTEX;
    
    // Primitive state
    private PrimitiveTopology topology = PrimitiveTopology.TRIANGLE_LIST;
    private IndexFormat stripIndexFormat = null; // Use null for undefined
    private FrontFace frontFace = FrontFace.CCW;
    private CullMode cullMode = CullMode.BACK;
    private boolean unclippedDepth = false;
    
    // Depth stencil state
    private DepthStencilState depthStencilState;
    private boolean depthEnabled = true;
    private TextureFormat depthFormat = TextureFormat.DEPTH24_PLUS;
    private CompareFunction depthCompare = CompareFunction.LESS;
    private boolean depthWriteEnabled = true;
    private int stencilReadMask = 0xFF;
    private int stencilWriteMask = 0xFF;
    
    // Multisample state
    private int sampleCount = 1;
    private int sampleMask = 0xFFFFFFFF;
    private boolean alphaToCoverageEnabled = false;
    
    // Fragment state
    private final List<ColorTargetState> colorTargets = new ArrayList<>();
    private BlendState currentBlendState;
    private int currentWriteMask = ColorWriteMask.ALL;
    
    // Pipeline layout
    private PipelineLayout pipelineLayout;
    private final List<BindGroupLayout> bindGroupLayouts = new ArrayList<>();
    
    public RenderPipelineBuilder(Device device) {
        this.device = device;
    }
    
    /**
     * Set pipeline label for debugging.
     */
    public RenderPipelineBuilder withLabel(String label) {
        this.label = label;
        return this;
    }
    
    /**
     * Set vertex shader.
     */
    public RenderPipelineBuilder withVertexShader(ShaderModule shader) {
        return withVertexShader(shader, "vs_main");
    }
    
    public RenderPipelineBuilder withVertexShader(ShaderModule shader, String entryPoint) {
        this.vertexShader = shader;
        this.vertexEntryPoint = entryPoint;
        return this;
    }
    
    /**
     * Set fragment shader.
     */
    public RenderPipelineBuilder withFragmentShader(ShaderModule shader) {
        return withFragmentShader(shader, "fs_main");
    }
    
    public RenderPipelineBuilder withFragmentShader(ShaderModule shader, String entryPoint) {
        this.fragmentShader = shader;
        this.fragmentEntryPoint = entryPoint;
        return this;
    }
    
    /**
     * Start defining a vertex buffer layout.
     */
    public RenderPipelineBuilder beginVertexBuffer(long stride, VertexStepMode stepMode) {
        // Save any current attributes as a buffer layout
        if (!currentAttributes.isEmpty()) {
            finalizeCurrentVertexBuffer();
        }
        
        currentStride = stride;
        currentStepMode = stepMode;
        currentAttributes.clear();
        return this;
    }
    
    /**
     * Add a vertex attribute to the current buffer.
     */
    public RenderPipelineBuilder addVertexAttribute(int shaderLocation, VertexFormat format, long offset) {
        VertexAttribute attr = new VertexAttribute(format, offset, shaderLocation);
        currentAttributes.add(attr);
        return this;
    }
    
    /**
     * Finalize the current vertex buffer layout.
     */
    public RenderPipelineBuilder endVertexBuffer() {
        finalizeCurrentVertexBuffer();
        return this;
    }
    
    private void finalizeCurrentVertexBuffer() {
        if (currentAttributes.isEmpty()) {
            return;
        }
        
        VertexBufferLayout layout = new VertexBufferLayout(currentStride);
        layout.stepMode = currentStepMode;
        layout.attributes = currentAttributes.toArray(new VertexAttribute[0]);
        
        vertexBufferLayouts.add(layout);
        currentAttributes.clear();
    }
    
    /**
     * Add a complete vertex buffer layout.
     */
    public RenderPipelineBuilder withVertexBufferLayout(VertexBufferLayout layout) {
        vertexBufferLayouts.add(layout);
        return this;
    }
    
    /**
     * Set primitive topology.
     */
    public RenderPipelineBuilder withTopology(PrimitiveTopology topology) {
        this.topology = topology;
        return this;
    }
    
    /**
     * Set culling mode.
     */
    public RenderPipelineBuilder withCulling(CullMode cullMode) {
        this.cullMode = cullMode;
        return this;
    }
    
    /**
     * Set front face winding.
     */
    public RenderPipelineBuilder withFrontFace(FrontFace frontFace) {
        this.frontFace = frontFace;
        return this;
    }
    
    /**
     * Enable/disable depth testing.
     */
    public RenderPipelineBuilder withDepth(boolean enable) {
        this.depthEnabled = enable;
        return this;
    }
    
    /**
     * Configure depth testing.
     */
    public RenderPipelineBuilder withDepth(TextureFormat format, CompareFunction compare, boolean writeEnabled) {
        this.depthEnabled = true;
        this.depthFormat = format;
        this.depthCompare = compare;
        this.depthWriteEnabled = writeEnabled;
        return this;
    }
    
    /**
     * Disable depth testing completely.
     */
    public RenderPipelineBuilder withoutDepth() {
        this.depthEnabled = false;
        this.depthStencilState = null;
        return this;
    }
    
    /**
     * Set multisample count.
     */
    public RenderPipelineBuilder withMultisample(int sampleCount) {
        this.sampleCount = sampleCount;
        return this;
    }
    
    /**
     * Enable alpha to coverage.
     */
    public RenderPipelineBuilder withAlphaToCoverage(boolean enable) {
        this.alphaToCoverageEnabled = enable;
        return this;
    }
    
    /**
     * Add a color target with format.
     */
    public RenderPipelineBuilder addColorTarget(int format) {
        // Convert int format to TextureFormat enum
        TextureFormat textureFormat = getTextureFormat(format);
        ColorTargetState target = new ColorTargetState(textureFormat);
        target.writeMask = ColorWriteMask.ALL;
        target.blend = null; // No blending
        colorTargets.add(target);
        return this;
    }
    
    /**
     * Add a color target with blending.
     */
    public RenderPipelineBuilder addColorTarget(int format, BlendState blend) {
        // Convert int format to TextureFormat enum
        TextureFormat textureFormat = getTextureFormat(format);
        ColorTargetState target = new ColorTargetState(textureFormat);
        target.writeMask = ColorWriteMask.ALL;
        target.blend = blend;
        colorTargets.add(target);
        return this;
    }
    
    /**
     * Create standard alpha blending.
     */
    public RenderPipelineBuilder withAlphaBlending(int format) {
        BlendState blend = new BlendState();
        blend.color = new BlendState.BlendComponent();
        blend.color.srcFactor = BlendFactor.SRC_ALPHA;
        blend.color.dstFactor = BlendFactor.ONE_MINUS_SRC_ALPHA;
        blend.color.operation = BlendOperation.ADD;
        
        blend.alpha = new BlendState.BlendComponent();
        blend.alpha.srcFactor = BlendFactor.ONE;
        blend.alpha.dstFactor = BlendFactor.ZERO;
        blend.alpha.operation = BlendOperation.ADD;
        
        return addColorTarget(format, blend);
    }
    
    /**
     * Create additive blending.
     */
    public RenderPipelineBuilder withAdditiveBlending(int format) {
        BlendState blend = new BlendState();
        blend.color = new BlendState.BlendComponent();
        blend.color.srcFactor = BlendFactor.ONE;
        blend.color.dstFactor = BlendFactor.ONE;
        blend.color.operation = BlendOperation.ADD;
        
        blend.alpha = new BlendState.BlendComponent();
        blend.alpha.srcFactor = BlendFactor.ONE;
        blend.alpha.dstFactor = BlendFactor.ONE;
        blend.alpha.operation = BlendOperation.ADD;
        
        return addColorTarget(format, blend);
    }
    
    /**
     * Add a bind group layout.
     */
    public RenderPipelineBuilder addBindGroupLayout(BindGroupLayout layout) {
        bindGroupLayouts.add(layout);
        return this;
    }
    
    /**
     * Set custom pipeline layout.
     */
    public RenderPipelineBuilder withPipelineLayout(PipelineLayout layout) {
        this.pipelineLayout = layout;
        return this;
    }
    
    /**
     * Build the render pipeline.
     */
    public RenderPipeline build() {
        validate();
        
        // Finalize any pending vertex buffer
        finalizeCurrentVertexBuffer();
        
        // Create pipeline layout if not provided
        if (pipelineLayout == null && !bindGroupLayouts.isEmpty()) {
            PipelineLayoutDescriptor layoutDesc = new PipelineLayoutDescriptor()
                .withLabel(label + " Layout");
            for (BindGroupLayout layout : bindGroupLayouts) {
                layoutDesc.addBindGroupLayout(layout);
            }
            pipelineLayout = device.createPipelineLayout(layoutDesc);
        }
        
        // Create render pipeline descriptor
        RenderPipelineDescriptor desc = new RenderPipelineDescriptor();
        desc.label = label;
        desc.layout = pipelineLayout;
        
        // Vertex stage
        desc.vertex = new VertexState(vertexShader);
        desc.vertex.entryPoint = vertexEntryPoint;
        desc.vertex.buffers = vertexBufferLayouts.toArray(new VertexBufferLayout[0]);
        
        // Fragment stage
        if (fragmentShader != null) {
            desc.fragment = new FragmentState(fragmentShader);
            desc.fragment.entryPoint = fragmentEntryPoint;
            desc.fragment.targets = colorTargets.toArray(new ColorTargetState[0]);
        }
        
        // Primitive state
        desc.primitive = new PrimitiveState();
        desc.primitive.topology = topology;
        desc.primitive.stripIndexFormat = stripIndexFormat;
        desc.primitive.frontFace = frontFace;
        desc.primitive.cullMode = cullMode;
        desc.primitive.unclippedDepth = unclippedDepth;
        
        // Depth stencil state
        if (depthEnabled) {
            desc.depthStencil = new DepthStencilState();
            desc.depthStencil.format = depthFormat;
            desc.depthStencil.depthWriteEnabled = depthWriteEnabled;
            desc.depthStencil.depthCompare = depthCompare;
            desc.depthStencil.stencilReadMask = stencilReadMask;
            desc.depthStencil.stencilWriteMask = stencilWriteMask;
            
            // Default stencil operations
            desc.depthStencil.stencilFront = new StencilState();
            desc.depthStencil.stencilFront.compare = CompareFunction.ALWAYS;
            desc.depthStencil.stencilFront.failOp = StencilOperation.KEEP;
            desc.depthStencil.stencilFront.depthFailOp = StencilOperation.KEEP;
            desc.depthStencil.stencilFront.passOp = StencilOperation.KEEP;
            
            desc.depthStencil.stencilBack = new StencilState();
            desc.depthStencil.stencilBack.compare = CompareFunction.ALWAYS;
            desc.depthStencil.stencilBack.failOp = StencilOperation.KEEP;
            desc.depthStencil.stencilBack.depthFailOp = StencilOperation.KEEP;
            desc.depthStencil.stencilBack.passOp = StencilOperation.KEEP;
        }
        
        // Multisample state
        desc.multisample = new MultisampleState();
        desc.multisample.count = sampleCount;
        desc.multisample.mask = sampleMask;
        desc.multisample.alphaToCoverageEnabled = alphaToCoverageEnabled;
        
        // Create pipeline
        RenderPipeline pipeline = device.createRenderPipeline(desc);
        log.info("Created render pipeline: {}", label);
        
        return pipeline;
    }
    
    private void validate() {
        if (vertexShader == null) {
            throw new IllegalStateException("Vertex shader is required");
        }
        
        if (fragmentShader != null && colorTargets.isEmpty()) {
            throw new IllegalStateException("Fragment shader requires at least one color target");
        }
        
        if (vertexBufferLayouts.isEmpty() && !currentAttributes.isEmpty()) {
            log.warn("Vertex attributes defined but endVertexBuffer() not called");
        }
    }
    
    /**
     * Create a simple pipeline for debugging.
     */
    public static RenderPipeline createDebugPipeline(Device device, ShaderModule vertexShader, 
                                                     ShaderModule fragmentShader, int surfaceFormat) {
        return new RenderPipelineBuilder(device)
            .withLabel("Debug Pipeline")
            .withVertexShader(vertexShader)
            .withFragmentShader(fragmentShader)
            .withTopology(PrimitiveTopology.TRIANGLE_LIST)
            .withCulling(CullMode.NONE)
            .withoutDepth()
            .addColorTarget(surfaceFormat)
            .build();
    }
    
    /**
     * Convert integer surface format to TextureFormat enum.
     */
    private static TextureFormat getTextureFormat(int formatValue) {
        for (TextureFormat format : TextureFormat.values()) {
            if (format.getValue() == formatValue) {
                return format;
            }
        }
        // Default to BGRA8_UNORM if not found
        return TextureFormat.BGRA8_UNORM;
    }
}