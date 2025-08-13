package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.render.webgpu.resources.BufferPool;
import com.hellblazer.luciferase.render.webgpu.shaders.ShaderManager;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.wrapper.Device.*;
import com.hellblazer.luciferase.webgpu.wrapper.RenderPipeline.*;
import com.hellblazer.luciferase.webgpu.wrapper.RenderPassEncoder;
import com.hellblazer.luciferase.webgpu.wrapper.Texture;
import com.hellblazer.luciferase.webgpu.wrapper.Sampler;
import static com.hellblazer.luciferase.render.webgpu.WebGPUConstants.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Efficient instanced renderer for voxels using WebGPU.
 * Renders thousands of voxels in a single draw call using instancing.
 */
public class InstancedVoxelRenderer {
    private static final Logger log = LoggerFactory.getLogger(InstancedVoxelRenderer.class);
    
    // Vertex data for a unit cube
    private static final float[] CUBE_VERTICES = {
        // Position (3), Normal (3), TexCoord (2)
        // Front face
        -0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  0.0f, 0.0f,
         0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  1.0f, 0.0f,
         0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  1.0f, 1.0f,
        -0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  0.0f, 1.0f,
        
        // Back face
        -0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  0.0f, 0.0f,
        -0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  0.0f, 1.0f,
         0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  1.0f, 1.0f,
         0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  1.0f, 0.0f,
        
        // Top face
        -0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,  0.0f, 0.0f,
        -0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,  0.0f, 1.0f,
         0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,  1.0f, 1.0f,
         0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,  1.0f, 0.0f,
        
        // Bottom face
        -0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,  0.0f, 0.0f,
         0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,  1.0f, 0.0f,
         0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,  1.0f, 1.0f,
        -0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,  0.0f, 1.0f,
        
        // Right face
         0.5f, -0.5f, -0.5f,  1.0f,  0.0f,  0.0f,  0.0f, 0.0f,
         0.5f,  0.5f, -0.5f,  1.0f,  0.0f,  0.0f,  0.0f, 1.0f,
         0.5f,  0.5f,  0.5f,  1.0f,  0.0f,  0.0f,  1.0f, 1.0f,
         0.5f, -0.5f,  0.5f,  1.0f,  0.0f,  0.0f,  1.0f, 0.0f,
        
        // Left face
        -0.5f, -0.5f, -0.5f, -1.0f,  0.0f,  0.0f,  0.0f, 0.0f,
        -0.5f, -0.5f,  0.5f, -1.0f,  0.0f,  0.0f,  1.0f, 0.0f,
        -0.5f,  0.5f,  0.5f, -1.0f,  0.0f,  0.0f,  1.0f, 1.0f,
        -0.5f,  0.5f, -0.5f, -1.0f,  0.0f,  0.0f,  0.0f, 1.0f
    };
    
    // Indices for the cube
    private static final short[] CUBE_INDICES = {
        0,  1,  2,  2,  3,  0,   // Front
        4,  5,  6,  6,  7,  4,   // Back
        8,  9, 10, 10, 11,  8,   // Top
        12, 13, 14, 14, 15, 12,  // Bottom
        16, 17, 18, 18, 19, 16,  // Right
        20, 21, 22, 22, 23, 20   // Left
    };
    
    /**
     * Represents a single voxel instance.
     */
    public static class VoxelInstance {
        public Vector3f position = new Vector3f();
        public Vector3f scale = new Vector3f(1, 1, 1);
        public Vector4f color = new Vector4f(1, 1, 1, 1);
        public float rotation = 0;
        public int materialId = 0;
        
        public Matrix4f getTransformMatrix() {
            Matrix4f transform = new Matrix4f();
            transform.translate(position);
            if (rotation != 0) {
                transform.rotateY(rotation);
            }
            transform.scale(scale);
            return transform;
        }
    }
    
    private final WebGPUContext context;
    private final BufferPool bufferPool;
    private final ShaderManager shaderManager;
    
    // Pipeline objects
    private RenderPipeline pipeline;
    private BindGroupLayout bindGroupLayout;
    private BindGroup uniformBindGroup;
    private BindGroup lightingBindGroup;
    
    // Buffers
    private Buffer vertexBuffer;
    private Buffer indexBuffer;
    private Buffer instanceBuffer;
    private Buffer uniformBuffer;
    private Buffer lightingBuffer;
    
    // Instance data
    private final List<VoxelInstance> voxelInstances = new ArrayList<>();
    private boolean instanceDataDirty = true;
    private int maxInstances = 10000;
    
    public InstancedVoxelRenderer(WebGPUContext context) {
        this.context = context;
        this.bufferPool = new BufferPool(context.getDevice());
        this.shaderManager = new ShaderManager(context.getDevice());
    }
    
    /**
     * Initialize the renderer with pipeline and resources.
     */
    public void initialize() {
        log.info("Initializing InstancedVoxelRenderer");
        
        createBuffers();
        createPipeline();
        createBindGroups();
        
        log.info("InstancedVoxelRenderer initialized");
    }
    
    private void createBuffers() {
        Device device = context.getDevice();
        
        // Create vertex buffer for cube geometry
        vertexBuffer = bufferPool.createVertexBuffer(CUBE_VERTICES.length * Float.BYTES);
        ByteBuffer vertexData = ByteBuffer.allocateDirect(CUBE_VERTICES.length * Float.BYTES)
            .order(ByteOrder.nativeOrder());
        vertexData.asFloatBuffer().put(CUBE_VERTICES);
        context.getQueue().writeBuffer(vertexBuffer, 0, vertexData);
        
        // Create index buffer
        indexBuffer = bufferPool.createIndexBuffer(CUBE_INDICES.length * Short.BYTES);
        ByteBuffer indexData = ByteBuffer.allocateDirect(CUBE_INDICES.length * Short.BYTES)
            .order(ByteOrder.nativeOrder());
        indexData.asShortBuffer().put(CUBE_INDICES);
        context.getQueue().writeBuffer(indexBuffer, 0, indexData);
        
        // Create instance buffer (will be updated per frame)
        int instanceStride = 24 * Float.BYTES; // 4x4 matrix + color + data
        instanceBuffer = bufferPool.createVertexBuffer(maxInstances * instanceStride);
        
        // Create uniform buffer
        uniformBuffer = bufferPool.createUniformBuffer(256); // Aligned size
        
        // Create lighting parameters buffer
        lightingBuffer = bufferPool.createUniformBuffer(128);
        updateLightingParams();
    }
    
    private void createPipeline() {
        Device device = context.getDevice();
        
        // Load shaders
        ShaderModule vertexShader = shaderManager.getVoxelVertexShader();
        ShaderModule fragmentShader = shaderManager.getVoxelFragmentShader();
        
        // Create pipeline layout
        PipelineLayoutDescriptor layoutDesc = new PipelineLayoutDescriptor()
            .withLabel("Voxel Pipeline Layout");
        
        // Create bind group layout for uniforms
        bindGroupLayout = createBindGroupLayout();
        layoutDesc.addBindGroupLayout(bindGroupLayout);
        
        PipelineLayout pipelineLayout = device.createPipelineLayout(layoutDesc);
        
        // Create render pipeline
        RenderPipelineDescriptor pipelineDesc = new RenderPipelineDescriptor();
        pipelineDesc.label = "Voxel Render Pipeline";
        pipelineDesc.layout = pipelineLayout;
        
        // Vertex stage
        pipelineDesc.vertex = new VertexState(vertexShader)
            .withEntryPoint("vs_main")
            .withBuffers(createVertexBufferLayouts());
        
        // Fragment stage
        pipelineDesc.fragment = new FragmentState(fragmentShader)
            .withEntryPoint("fs_main");
        
        // Color target
        // Convert int surface format to TextureFormat enum
        Texture.TextureFormat surfaceFormat = getTextureFormat(context.getSurfaceFormat());
        ColorTargetState colorTarget = new ColorTargetState(surfaceFormat);
        
        // Blending
        BlendState blend = new BlendState();
        blend.color = new BlendState.BlendComponent();
        blend.color.srcFactor = BlendFactor.SRC_ALPHA;
        blend.color.dstFactor = BlendFactor.ONE_MINUS_SRC_ALPHA;
        blend.color.operation = BlendOperation.ADD;
        blend.alpha = new BlendState.BlendComponent();
        blend.alpha.srcFactor = BlendFactor.ONE;
        blend.alpha.dstFactor = BlendFactor.ZERO;
        blend.alpha.operation = BlendOperation.ADD;
        colorTarget.blend = blend;
        
        pipelineDesc.fragment.targets = new ColorTargetState[] { colorTarget };
        
        // Primitive state
        pipelineDesc.primitive = new PrimitiveState();
        pipelineDesc.primitive.topology = PrimitiveTopology.TRIANGLE_LIST;
        pipelineDesc.primitive.stripIndexFormat = null; // null for non-strip topologies
        pipelineDesc.primitive.frontFace = FrontFace.CCW;
        pipelineDesc.primitive.cullMode = CullMode.BACK;
        
        // Depth stencil
        pipelineDesc.depthStencil = new DepthStencilState();
        pipelineDesc.depthStencil.format = Texture.TextureFormat.DEPTH24_PLUS;
        pipelineDesc.depthStencil.depthWriteEnabled = true;
        pipelineDesc.depthStencil.depthCompare = Sampler.CompareFunction.LESS;
        
        // Multisample
        pipelineDesc.multisample = new MultisampleState();
        pipelineDesc.multisample.count = 1;
        pipelineDesc.multisample.mask = 0xFFFFFFFF;
        
        pipeline = device.createRenderPipeline(pipelineDesc);
        log.info("Voxel render pipeline created");
    }
    
    private VertexBufferLayout[] createVertexBufferLayouts() {
        // Layout for cube geometry
        VertexBufferLayout cubeLayout = new VertexBufferLayout(8 * Float.BYTES); // position(3) + normal(3) + texCoord(2)
        cubeLayout.stepMode = VertexStepMode.VERTEX;
        cubeLayout.attributes = new VertexAttribute[] {
            new VertexAttribute(VertexFormat.FLOAT32x3, 0, 0),  // position
            new VertexAttribute(VertexFormat.FLOAT32x3, 3 * Float.BYTES, 1),  // normal
            new VertexAttribute(VertexFormat.FLOAT32x2, 6 * Float.BYTES, 2)   // texCoord
        };
        
        // Layout for instance data
        VertexBufferLayout instanceLayout = new VertexBufferLayout(24 * Float.BYTES); // 4x4 matrix + color + data
        instanceLayout.stepMode = VertexStepMode.INSTANCE;
        instanceLayout.attributes = new VertexAttribute[] {
            // Transform matrix (4 vec4s)
            new VertexAttribute(VertexFormat.FLOAT32x4, 0, 3),
            new VertexAttribute(VertexFormat.FLOAT32x4, 4 * Float.BYTES, 4),
            new VertexAttribute(VertexFormat.FLOAT32x4, 8 * Float.BYTES, 5),
            new VertexAttribute(VertexFormat.FLOAT32x4, 12 * Float.BYTES, 6),
            // Color
            new VertexAttribute(VertexFormat.FLOAT32x4, 16 * Float.BYTES, 7),
            // Instance data (scale, materialId, flags, reserved)
            new VertexAttribute(VertexFormat.FLOAT32x4, 20 * Float.BYTES, 8)
        };
        
        return new VertexBufferLayout[] { cubeLayout, instanceLayout };
    }
    
    private BindGroupLayout createBindGroupLayout() {
        Device device = context.getDevice();
        
        BindGroupLayoutDescriptor desc = new BindGroupLayoutDescriptor()
            .withLabel("Voxel Bind Group Layout");
        
        // Uniform buffer binding
        BindGroupLayoutEntry uniformEntry = new BindGroupLayoutEntry(0, ShaderStage.VERTEX | ShaderStage.FRAGMENT);
        uniformEntry.buffer = new BufferBindingLayout(0x00000001); // BUFFER_BINDING_TYPE_UNIFORM
        desc.withEntry(uniformEntry);
        
        // Lighting parameters binding
        BindGroupLayoutEntry lightingEntry = new BindGroupLayoutEntry(1, ShaderStage.FRAGMENT);
        lightingEntry.buffer = new BufferBindingLayout(0x00000001); // BUFFER_BINDING_TYPE_UNIFORM
        desc.withEntry(lightingEntry);
        
        return device.createBindGroupLayout(desc);
    }
    
    private void createBindGroups() {
        Device device = context.getDevice();
        
        BindGroupDescriptor desc = new BindGroupDescriptor(bindGroupLayout)
            .withLabel("Voxel Bind Group");
        
        // Uniform buffer binding
        BindGroupEntry uniformEntry = new BindGroupEntry(0);
        uniformEntry.buffer = uniformBuffer;
        uniformEntry.offset = 0;
        uniformEntry.size = 256;
        desc.withEntry(uniformEntry);
        
        // Lighting buffer binding
        BindGroupEntry lightingEntry = new BindGroupEntry(1);
        lightingEntry.buffer = lightingBuffer;
        lightingEntry.offset = 0;
        lightingEntry.size = 128;
        desc.withEntry(lightingEntry);
        
        uniformBindGroup = device.createBindGroup(desc);
    }
    
    /**
     * Update voxel instances.
     */
    public void setVoxelInstances(List<VoxelInstance> instances) {
        voxelInstances.clear();
        voxelInstances.addAll(instances);
        instanceDataDirty = true;
    }
    
    /**
     * Add a single voxel instance.
     */
    public void addVoxel(VoxelInstance voxel) {
        voxelInstances.add(voxel);
        instanceDataDirty = true;
    }
    
    /**
     * Clear all voxels.
     */
    public void clearVoxels() {
        voxelInstances.clear();
        instanceDataDirty = true;
    }
    
    /**
     * Update uniform buffer with camera matrices.
     */
    public void updateUniforms(Matrix4f viewProjection, Matrix4f model, 
                              Vector3f lightDirection, Vector3f cameraPosition, float time) {
        ByteBuffer data = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        FloatBuffer fb = data.asFloatBuffer();
        
        // View-projection matrix
        viewProjection.get(fb);
        fb.position(16);
        
        // Model matrix
        model.get(fb);
        fb.position(32);
        
        // Light direction
        fb.put(lightDirection.x).put(lightDirection.y).put(lightDirection.z).put(0);
        
        // Time
        fb.put(time);
        
        // Camera position
        fb.put(cameraPosition.x).put(cameraPosition.y).put(cameraPosition.z).put(0);
        
        context.getQueue().writeBuffer(uniformBuffer, 0, data);
    }
    
    /**
     * Update lighting parameters.
     */
    private void updateLightingParams() {
        ByteBuffer data = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        FloatBuffer fb = data.asFloatBuffer();
        
        // Lighting strengths
        fb.put(0.3f);  // ambient
        fb.put(0.7f);  // diffuse
        fb.put(0.5f);  // specular
        fb.put(32.0f); // shininess
        
        // Fog parameters
        fb.put(50.0f);  // fog start
        fb.put(200.0f); // fog end
        fb.put(0.7f).put(0.8f).put(0.9f); // fog color
        fb.put(0); // padding
        
        context.getQueue().writeBuffer(lightingBuffer, 0, data);
    }
    
    /**
     * Update instance buffer with current voxel data.
     */
    private void updateInstanceBuffer() {
        if (!instanceDataDirty || voxelInstances.isEmpty()) {
            return;
        }
        
        int instanceCount = Math.min(voxelInstances.size(), maxInstances);
        int stride = 24; // floats per instance
        ByteBuffer data = ByteBuffer.allocateDirect(instanceCount * stride * Float.BYTES)
            .order(ByteOrder.nativeOrder());
        FloatBuffer fb = data.asFloatBuffer();
        
        for (int i = 0; i < instanceCount; i++) {
            VoxelInstance voxel = voxelInstances.get(i);
            
            // Transform matrix (16 floats)
            Matrix4f transform = voxel.getTransformMatrix();
            float[] matrix = new float[16];
            transform.get(matrix);
            fb.put(matrix);
            
            // Color (4 floats)
            fb.put(voxel.color.x).put(voxel.color.y).put(voxel.color.z).put(voxel.color.w);
            
            // Instance data (4 floats: scale, materialId, flags, reserved)
            float avgScale = (voxel.scale.x + voxel.scale.y + voxel.scale.z) / 3.0f;
            fb.put(avgScale).put(voxel.materialId).put(0).put(0);
        }
        
        context.getQueue().writeBuffer(instanceBuffer, 0, data);
        instanceDataDirty = false;
    }
    
    /**
     * Render the voxels.
     */
    public void render(RenderPassEncoder renderPass) {
        if (voxelInstances.isEmpty()) {
            return;
        }
        
        // Update instance buffer if needed
        updateInstanceBuffer();
        
        // Set pipeline
        renderPass.setPipeline(pipeline);
        
        // Set bind groups
        renderPass.setBindGroup(0, uniformBindGroup, null);
        
        // Set vertex buffers
        renderPass.setVertexBuffer(0, vertexBuffer, 0, CUBE_VERTICES.length * Float.BYTES);
        renderPass.setVertexBuffer(1, instanceBuffer, 0, 
            voxelInstances.size() * 24 * Float.BYTES);
        
        // Set index buffer
        renderPass.setIndexBuffer(indexBuffer, RenderPassEncoder.IndexFormat.UINT16, 0, 
            CUBE_INDICES.length * Short.BYTES);
        
        // Draw instanced
        renderPass.drawIndexed(CUBE_INDICES.length, voxelInstances.size(), 0, 0, 0);
        
        log.debug("Rendered {} voxel instances", voxelInstances.size());
    }
    
    /**
     * Clean up resources.
     */
    public void cleanup() {
        log.info("Cleaning up InstancedVoxelRenderer");
        
        bufferPool.clear();
        shaderManager.clearCache();
        
        if (pipeline != null) {
            // Pipeline cleanup - no destroy method in current wrapper
            pipeline = null;
        }
    }
    
    /**
     * Convert integer surface format to TextureFormat enum.
     * Common formats: BGRA8_UNORM=23, RGBA8_UNORM=18
     */
    private static Texture.TextureFormat getTextureFormat(int formatValue) {
        for (Texture.TextureFormat format : Texture.TextureFormat.values()) {
            if (format.getValue() == formatValue) {
                return format;
            }
        }
        // Default to BGRA8_UNORM if not found
        return Texture.TextureFormat.BGRA8_UNORM;
    }
}