package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.hellblazer.luciferase.render.voxel.esvo.ESVONode;
import com.hellblazer.luciferase.render.voxel.esvo.ESVOPage;

/**
 * GPU-accelerated ray traversal for ESVO using GLSL compute shaders.
 * Manages shader compilation, buffer uploads, and dispatch.
 */
public class ESVOGPUTraversal {
    
    // Shader handles
    private int computeProgram;
    private int computeShader;
    
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
        this.width = width;
        this.height = height;
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
        try {
            // Load shader source
            String shaderSource = Files.readString(
                Path.of("src/main/resources/shaders/esvo/ray_traversal.comp")
            );
            
            // Create and compile shader
            computeShader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
            GL20.glShaderSource(computeShader, shaderSource);
            GL20.glCompileShader(computeShader);
            
            // Check compilation
            if (GL20.glGetShaderi(computeShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetShaderInfoLog(computeShader);
                throw new RuntimeException("Shader compilation failed: " + log);
            }
            
            // Create and link program
            computeProgram = GL20.glCreateProgram();
            GL20.glAttachShader(computeProgram, computeShader);
            GL20.glLinkProgram(computeProgram);
            
            // Check linking
            if (GL20.glGetProgrami(computeProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(computeProgram);
                throw new RuntimeException("Program linking failed: " + log);
            }
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to load shader", e);
        }
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
        GL20.glUseProgram(computeProgram);
        octreeMinLoc = GL20.glGetUniformLocation(computeProgram, "octreeMin");
        octreeMaxLoc = GL20.glGetUniformLocation(computeProgram, "octreeMax");
        octreeScaleLoc = GL20.glGetUniformLocation(computeProgram, "octreeScale");
        rootNodeLoc = GL20.glGetUniformLocation(computeProgram, "rootNode");
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
        GL20.glUseProgram(computeProgram);
        GL20.glUniform3f(octreeMinLoc, minX, minY, minZ);
        GL20.glUniform3f(octreeMaxLoc, maxX, maxY, maxZ);
        
        float scale = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        GL20.glUniform1f(octreeScaleLoc, scale);
    }
    
    /**
     * Execute ray traversal on GPU
     */
    public void traverse(int rootNodeIndex) {
        GL20.glUseProgram(computeProgram);
        
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
     * Clean up GPU resources
     */
    public void dispose() {
        if (computeProgram != 0) {
            GL20.glDeleteProgram(computeProgram);
        }
        if (computeShader != 0) {
            GL20.glDeleteShader(computeShader);
        }
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
    }
}