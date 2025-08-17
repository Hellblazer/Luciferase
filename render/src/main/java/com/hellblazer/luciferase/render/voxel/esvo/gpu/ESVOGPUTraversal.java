package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import com.hellblazer.luciferase.render.voxel.esvo.ESVONode;
import com.hellblazer.luciferase.render.voxel.esvo.ESVOPage;

/**
 * GPU-accelerated ray traversal for ESVO using GLSL compute shaders.
 * Manages shader compilation, buffer uploads, and dispatch.
 * Now integrated with ESVOShaderManager for advanced shader variants.
 */
public class ESVOGPUTraversal {
    private static final Logger log = LoggerFactory.getLogger(ESVOGPUTraversal.class);
    
    // Shader management
    private final ESVOShaderManager shaderManager;
    private int currentProgram;
    private String currentVariant = "basic";
    
    // Buffer objects
    private int nodeBufferSSBO;
    private int pageBufferSSBO;
    
    // Texture objects for ray data
    private int rayOriginTexture;
    private int rayDirectionTexture;
    private int hitResultTexture;
    
    // Uniform locations
    private int octreeMinLoc;
    private int octreeMaxLoc;
    private int octreeScaleLoc;
    private int rootNodeLoc;
    
    // Dimensions
    private final int width;
    private final int height;
    
    // Node and page data
    private final List<ESVONode> nodes = new ArrayList<>();
    private final List<ESVOPage> pages = new ArrayList<>();
    
    public ESVOGPUTraversal(int width, int height) {
        this(width, height, new ESVOShaderManager("shaders/esvo/", true));
    }
    
    public ESVOGPUTraversal(int width, int height, ESVOShaderManager shaderManager) {
        this.width = width;
        this.height = height;
        this.shaderManager = shaderManager;
        initialize();
    }
    
    private void initialize() {
        // Compile compute shader
        compileShader();
        
        // Create buffers
        createBuffers();
        
        // Create textures
        createTextures();
        
        // Get uniform locations
        getUniformLocations();
    }
    
    private void compileShader() {
        // Use shader manager to compile basic traversal shader
        currentProgram = ESVOShaderManager.Presets.basicTraversal(shaderManager).compile();
        if (currentProgram == 0) {
            throw new RuntimeException("Failed to compile basic traversal shader");
        }
        log.info("Compiled basic traversal shader: {}", currentProgram);
    }
    
    private void createBuffers() {
        // Create node buffer SSBO
        nodeBufferSSBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, nodeBufferSSBO);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, nodeBufferSSBO);
        
        // Create page buffer SSBO
        pageBufferSSBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, pageBufferSSBO);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, pageBufferSSBO);
    }
    
    private void createTextures() {
        // Ray origin texture (RGBA32F)
        rayOriginTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, rayOriginTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 
                         width, height, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer)null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        
        // Ray direction texture (RGBA32F)
        rayDirectionTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, rayDirectionTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F,
                         width, height, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer)null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        
        // Hit result texture (RGBA32F)
        hitResultTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, hitResultTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F,
                         width, height, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer)null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    }
    
    private void getUniformLocations() {
        GL20.glUseProgram(currentProgram);
        octreeMinLoc = GL20.glGetUniformLocation(currentProgram, "voxelOrigin");
        octreeMaxLoc = GL20.glGetUniformLocation(currentProgram, "voxelSize");
        octreeScaleLoc = GL20.glGetUniformLocation(currentProgram, "worldToVoxel");
        rootNodeLoc = GL20.glGetUniformLocation(currentProgram, "rootNodeIndex");
    }
    
    /**
     * Upload node data to GPU
     */
    public void uploadNodes(List<ESVONode> nodeList) {
        nodes.clear();
        nodes.addAll(nodeList);
        
        // Pack nodes into buffer
        ByteBuffer buffer = MemoryUtil.memAlloc(nodes.size() * 8);
        for (ESVONode node : nodes) {
            byte[] data = node.toBytes();
            buffer.put(data);
        }
        buffer.flip();
        
        // Upload to GPU
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, nodeBufferSSBO);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, buffer, GL15.GL_STATIC_DRAW);
        
        MemoryUtil.memFree(buffer);
    }
    
    /**
     * Upload page data to GPU
     */
    public void uploadPages(List<ESVOPage> pageList) {
        pages.clear();
        pages.addAll(pageList);
        
        // Pack pages into buffer
        int totalSize = pages.size() * ESVOPage.PAGE_BYTES;
        ByteBuffer buffer = MemoryUtil.memAlloc(totalSize);
        
        for (ESVOPage page : pages) {
            byte[] data = page.serialize();
            buffer.put(data);
        }
        buffer.flip();
        
        // Upload to GPU
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, pageBufferSSBO);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, buffer, GL15.GL_STATIC_DRAW);
        
        MemoryUtil.memFree(buffer);
    }
    
    /**
     * Set ray data for traversal
     */
    public void setRays(float[] origins, float[] directions) {
        if (origins.length != width * height * 4 || 
            directions.length != width * height * 4) {
            throw new IllegalArgumentException("Invalid ray data size");
        }
        
        // Upload ray origins
        FloatBuffer originBuffer = MemoryUtil.memAllocFloat(origins.length);
        originBuffer.put(origins).flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, rayOriginTexture);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                            GL11.GL_RGBA, GL11.GL_FLOAT, originBuffer);
        MemoryUtil.memFree(originBuffer);
        
        // Upload ray directions
        FloatBuffer directionBuffer = MemoryUtil.memAllocFloat(directions.length);
        directionBuffer.put(directions).flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, rayDirectionTexture);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                            GL11.GL_RGBA, GL11.GL_FLOAT, directionBuffer);
        MemoryUtil.memFree(directionBuffer);
    }
    
    /**
     * Set octree bounds
     */
    public void setOctreeBounds(float minX, float minY, float minZ,
                                float maxX, float maxY, float maxZ) {
        GL20.glUseProgram(currentProgram);
        GL20.glUniform3f(octreeMinLoc, minX, minY, minZ);
        
        float scale = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        GL20.glUniform1f(octreeMaxLoc, scale);
        
        // Set world to voxel transformation matrix (identity for now)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            // Identity matrix
            matrix.put(new float[]{
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
            });
            matrix.flip();
            GL20.glUniformMatrix4fv(octreeScaleLoc, false, matrix);
        }
    }
    
    /**
     * Execute ray traversal on GPU
     */
    public void traverse(int rootNodeIndex) {
        GL20.glUseProgram(currentProgram);
        
        // Set root node (using glUniform1i for unsigned int)
        GL20.glUniform1i(rootNodeLoc, rootNodeIndex);
        
        // Bind textures to image units
        GL42.glBindImageTexture(2, rayOriginTexture, 0, false, 0, 
                               GL15.GL_READ_ONLY, GL30.GL_RGBA32F);
        GL42.glBindImageTexture(3, rayDirectionTexture, 0, false, 0,
                               GL15.GL_READ_ONLY, GL30.GL_RGBA32F);
        GL42.glBindImageTexture(4, hitResultTexture, 0, false, 0,
                               GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
        
        // Dispatch compute shader
        int workGroupsX = (width + 7) / 8;  // 8x8 local work groups
        int workGroupsY = (height + 7) / 8;
        GL43.glDispatchCompute(workGroupsX, workGroupsY, 1);
        
        // Memory barrier to ensure writes complete
        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
    }
    
    /**
     * Read back hit results from GPU
     */
    public float[] getHitResults() {
        float[] results = new float[width * height * 4];
        
        FloatBuffer buffer = MemoryUtil.memAllocFloat(results.length);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, hitResultTexture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
        buffer.get(results);
        MemoryUtil.memFree(buffer);
        
        return results;
    }
    
    /**
     * Switches to a different shader variant.
     */
    public void useVariant(String variantName) {
        int newProgram = 0;
        
        switch (variantName) {
            case "basic":
                newProgram = ESVOShaderManager.Presets.basicTraversal(shaderManager).compile();
                break;
            case "shadow":
                newProgram = ESVOShaderManager.Presets.shadowTraversal(shaderManager).compile();
                break;
            case "lod":
                newProgram = ESVOShaderManager.Presets.lodTraversal(shaderManager, 1.0f, 100.0f).compile();
                break;
            case "statistics":
                newProgram = ESVOShaderManager.Presets.statisticsTraversal(shaderManager).compile();
                break;
            default:
                log.warn("Unknown shader variant: {}", variantName);
                return;
        }
        
        if (newProgram != 0) {
            currentProgram = newProgram;
            currentVariant = variantName;
            getUniformLocations();
            log.info("Switched to shader variant: {}", variantName);
        } else {
            log.error("Failed to compile shader variant: {}", variantName);
        }
    }
    
    /**
     * Gets the current shader variant name.
     */
    public String getCurrentVariant() {
        return currentVariant;
    }
    
    /**
     * Clean up GPU resources
     */
    public void dispose() {
        // Shaders are managed by ESVOShaderManager, no need to delete here
        if (nodeBufferSSBO != 0) {
            GL15.glDeleteBuffers(nodeBufferSSBO);
        }
        if (pageBufferSSBO != 0) {
            GL15.glDeleteBuffers(pageBufferSSBO);
        }
        if (rayOriginTexture != 0) {
            GL11.glDeleteTextures(rayOriginTexture);
        }
        if (rayDirectionTexture != 0) {
            GL11.glDeleteTextures(rayDirectionTexture);
        }
        if (hitResultTexture != 0) {
            GL11.glDeleteTextures(hitResultTexture);
        }
        
        // Shutdown shader manager if we own it
        shaderManager.shutdown();
    }
    
    /**
     * Creates a custom shader variant with specific defines.
     */
    public void useCustomVariant(String name, ESVOShaderManager.ShaderVariant variant) {
        int program = variant.compile();
        if (program != 0) {
            currentProgram = program;
            currentVariant = name;
            getUniformLocations();
            log.info("Using custom shader variant: {}", name);
        } else {
            log.error("Failed to compile custom shader variant: {}", name);
        }
    }
}