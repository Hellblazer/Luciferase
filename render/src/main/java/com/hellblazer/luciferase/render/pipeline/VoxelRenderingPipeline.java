/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.render.pipeline;

import com.hellblazer.luciferase.render.bridge.SpatialIndexRenderBridge;
import com.hellblazer.luciferase.render.memory.GPUMemoryManager;
import com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode;
import com.hellblazer.luciferase.render.voxel.quality.QualityController;
import com.hellblazer.luciferase.render.lwjgl.Shader;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import javax.vecmath.Point3f;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL45.*;

/**
 * Main voxel rendering pipeline that orchestrates the entire rendering process.
 * Manages LOD, culling, GPU resources, and render passes.
 * 
 * @param <ID> The type of EntityID used
 * @param <Content> The type of content stored
 */
public class VoxelRenderingPipeline<ID extends EntityID, Content> {
    
    private static final Logger log = Logger.getLogger(VoxelRenderingPipeline.class.getName());
    
    // Pipeline configuration
    private static final int MAX_VISIBLE_NODES = 10000;
    private static final int GPU_MEMORY_POOL_SIZE = 256 * 1024 * 1024; // 256MB
    private static final int MAX_GPU_BUFFER_SIZE = 64 * 1024 * 1024; // 64MB per buffer
    
    // Pipeline components
    private final SpatialIndexRenderBridge<ID, Content> renderBridge;
    private final GPUMemoryManager gpuMemoryManager;
    private final QualityController qualityController;
    private final Map<String, Shader> shaders;
    
    // Render state
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicLong frameNumber = new AtomicLong(0);
    private EnhancedVoxelOctreeNode rootNode;
    private int nodeCount;
    
    // Render targets and buffers
    private int framebuffer;
    private int colorTexture;
    private int depthTexture;
    private int vao; // Vertex Array Object for full-screen quad
    
    // Camera and viewport
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Vector3f cameraPosition = new Vector3f();
    private int viewportWidth = 1920;
    private int viewportHeight = 1080;
    
    // Performance metrics
    private final Map<String, Long> timings = new ConcurrentHashMap<>();
    
    public VoxelRenderingPipeline(Octree<ID, Content> spatialIndex, float worldSize) {
        this.renderBridge = new SpatialIndexRenderBridge<>(spatialIndex, worldSize);
        this.gpuMemoryManager = new GPUMemoryManager(GPU_MEMORY_POOL_SIZE, MAX_GPU_BUFFER_SIZE);
        this.qualityController = new QualityController();
        this.shaders = new HashMap<>();
    }
    
    /**
     * Initialize the rendering pipeline.
     */
    public void initialize() {
        if (initialized.getAndSet(true)) {
            return;
        }
        
        log.info("Initializing voxel rendering pipeline");
        
        // Load shaders
        loadShaders();
        
        // Create render targets
        createRenderTargets();
        
        // Setup VAO for full-screen rendering
        setupFullScreenQuad();
        
        // Build initial voxel octree
        rebuildVoxelOctree();
        
        log.info("Voxel rendering pipeline initialized");
    }
    
    /**
     * Render a frame.
     * 
     * @param deltaTime Time since last frame in seconds
     */
    public void render(float deltaTime) {
        if (!initialized.get()) {
            throw new IllegalStateException("Pipeline not initialized");
        }
        
        var frame = frameNumber.incrementAndGet();
        var frameStart = System.nanoTime();
        
        // Update quality settings based on performance
        // TODO: Implement adaptive quality based on frame time
        
        // Frustum culling
        var cullingStart = System.nanoTime();
        var visibleNodes = performFrustumCulling();
        timings.put("frustumCulling", System.nanoTime() - cullingStart);
        
        // LOD selection
        var lodStart = System.nanoTime();
        var lodNodes = selectLOD(visibleNodes);
        timings.put("lodSelection", System.nanoTime() - lodStart);
        
        // Update GPU buffers if needed
        var gpuUpdateStart = System.nanoTime();
        updateGPUBuffers(lodNodes);
        timings.put("gpuUpdate", System.nanoTime() - gpuUpdateStart);
        
        // Render passes
        var renderStart = System.nanoTime();
        
        // Bind framebuffer
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, viewportWidth, viewportHeight);
        
        // Clear
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        
        // Voxel rendering pass
        renderVoxels(lodNodes);
        
        // Post-processing pass
        // TODO: Make post-processing configurable
        renderPostProcessing();
        
        // Unbind framebuffer
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        
        timings.put("rendering", System.nanoTime() - renderStart);
        timings.put("frameTotal", System.nanoTime() - frameStart);
        
        // Log performance every 60 frames
        if (frame % 60 == 0) {
            logPerformanceMetrics();
        }
    }
    
    /**
     * Update camera matrices.
     * 
     * @param position Camera position
     * @param target Look-at target
     * @param up Up vector
     * @param fov Field of view in radians
     * @param aspectRatio Aspect ratio
     * @param near Near plane
     * @param far Far plane
     */
    public void updateCamera(Vector3f position, Vector3f target, Vector3f up,
                            float fov, float aspectRatio, float near, float far) {
        cameraPosition.set(position);
        viewMatrix.lookAt(position, target, up);
        projectionMatrix.perspective(fov, aspectRatio, near, far);
    }
    
    /**
     * Update viewport size.
     * 
     * @param width New width
     * @param height New height
     */
    public void updateViewport(int width, int height) {
        if (width != viewportWidth || height != viewportHeight) {
            viewportWidth = width;
            viewportHeight = height;
            
            // Recreate render targets
            deleteRenderTargets();
            createRenderTargets();
        }
    }
    
    /**
     * Rebuild the voxel octree from the spatial index.
     */
    public void rebuildVoxelOctree() {
        log.info("Rebuilding voxel octree");
        
        // Build octree from spatial index
        rootNode = renderBridge.buildVoxelOctree();
        
        // Upload to GPU
        nodeCount = gpuMemoryManager.uploadOctree(rootNode);
        
        log.info("Voxel octree rebuilt with " + nodeCount + " nodes");
    }
    
    /**
     * Update a specific region of the voxel octree.
     * 
     * @param minX Minimum X coordinate
     * @param minY Minimum Y coordinate
     * @param minZ Minimum Z coordinate
     * @param maxX Maximum X coordinate
     * @param maxY Maximum Y coordinate
     * @param maxZ Maximum Z coordinate
     */
    public void updateRegion(float minX, float minY, float minZ,
                           float maxX, float maxY, float maxZ) {
        var region = new SpatialIndexRenderBridge.BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        var updatedNodes = renderBridge.updateRegion(region);
        
        // Update GPU buffers for changed nodes
        for (var node : updatedNodes) {
            updateNodeGPUBuffer(node);
        }
    }
    
    /**
     * Set material mapping function.
     * 
     * @param materialMapper Function to map content to material ID
     */
    public void setMaterialMapper(java.util.function.Function<Content, Integer> materialMapper) {
        renderBridge.setMaterialMapper(materialMapper);
    }
    
    /**
     * Get current performance metrics.
     * 
     * @return Map of metric names to values in nanoseconds
     */
    public Map<String, Long> getPerformanceMetrics() {
        return new HashMap<>(timings);
    }
    
    /**
     * Cleanup resources.
     */
    public void dispose() {
        log.info("Disposing voxel rendering pipeline");
        
        // Delete shaders
        for (var shader : shaders.values()) {
            shader.cleanup();
        }
        shaders.clear();
        
        // Delete render targets
        deleteRenderTargets();
        
        // Delete VAO
        glDeleteVertexArrays(vao);
        
        // Cleanup GPU memory
        gpuMemoryManager.dispose();
        
        initialized.set(false);
    }
    
    // ===== Private Helper Methods =====
    
    private void loadShaders() {
        // Voxel rendering shader
        var voxelVertexShader = """
            #version 450 core
            
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aTexCoord;
            layout(location = 3) in int aVoxelData;
            
            uniform mat4 uProjection;
            uniform mat4 uView;
            uniform mat4 uModel;
            
            out vec3 FragPos;
            out vec3 Normal;
            out vec3 VoxelColor;
            out float VoxelDensity;
            
            void main() {
                FragPos = vec3(uModel * vec4(aPos, 1.0));
                Normal = mat3(transpose(inverse(uModel))) * aNormal;
                
                // Unpack voxel data
                VoxelColor = vec3(
                    float((aVoxelData >> 16) & 0xFF) / 255.0,
                    float((aVoxelData >> 8) & 0xFF) / 255.0,
                    float(aVoxelData & 0xFF) / 255.0
                );
                VoxelDensity = float((aVoxelData >> 24) & 0xFF) / 255.0;
                
                gl_Position = uProjection * uView * vec4(FragPos, 1.0);
            }
            """;
        
        var voxelFragmentShader = """
            #version 450 core
            
            in vec3 FragPos;
            in vec3 Normal;
            in vec3 VoxelColor;
            in float VoxelDensity;
            
            uniform vec3 uCameraPos;
            uniform vec3 uLightPos;
            uniform vec3 uLightColor;
            
            out vec4 FragColor;
            
            void main() {
                // Ambient
                vec3 ambient = 0.15 * VoxelColor;
                
                // Diffuse
                vec3 norm = normalize(Normal);
                vec3 lightDir = normalize(uLightPos - FragPos);
                float diff = max(dot(norm, lightDir), 0.0);
                vec3 diffuse = diff * uLightColor * VoxelColor;
                
                // Specular
                vec3 viewDir = normalize(uCameraPos - FragPos);
                vec3 reflectDir = reflect(-lightDir, norm);
                float spec = pow(max(dot(viewDir, reflectDir), 0.0), 32);
                vec3 specular = spec * uLightColor * 0.5;
                
                vec3 result = ambient + diffuse + specular;
                FragColor = vec4(result, VoxelDensity);
            }
            """;
        
        shaders.put("voxel", new Shader(voxelVertexShader, voxelFragmentShader));
        
        // Post-processing shader
        var postVertexShader = """
            #version 450 core
            
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aTexCoord;
            
            out vec2 TexCoord;
            
            void main() {
                TexCoord = aTexCoord;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """;
        
        var postFragmentShader = """
            #version 450 core
            
            in vec2 TexCoord;
            
            uniform sampler2D uColorTexture;
            uniform float uExposure;
            uniform float uGamma;
            
            out vec4 FragColor;
            
            void main() {
                vec3 color = texture(uColorTexture, TexCoord).rgb;
                
                // Tone mapping
                color = vec3(1.0) - exp(-color * uExposure);
                
                // Gamma correction
                color = pow(color, vec3(1.0 / uGamma));
                
                FragColor = vec4(color, 1.0);
            }
            """;
        
        shaders.put("postprocess", new Shader(postVertexShader, postFragmentShader));
    }
    
    private void createRenderTargets() {
        // Create framebuffer
        framebuffer = glCreateFramebuffers();
        
        // Create color texture
        colorTexture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(colorTexture, 1, GL_RGBA16F, viewportWidth, viewportHeight);
        glTextureParameteri(colorTexture, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTextureParameteri(colorTexture, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, colorTexture, 0);
        
        // Create depth texture
        depthTexture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(depthTexture, 1, GL_DEPTH_COMPONENT32F, viewportWidth, viewportHeight);
        glNamedFramebufferTexture(framebuffer, GL_DEPTH_ATTACHMENT, depthTexture, 0);
        
        // Check framebuffer completeness
        if (glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Framebuffer not complete");
        }
    }
    
    private void deleteRenderTargets() {
        glDeleteTextures(colorTexture);
        glDeleteTextures(depthTexture);
        glDeleteFramebuffers(framebuffer);
    }
    
    private void setupFullScreenQuad() {
        // Create VAO for full-screen quad
        vao = glCreateVertexArrays();
        
        // Vertex data for full-screen quad
        float[] vertices = {
            // Positions    // TexCoords
            -1.0f,  1.0f,   0.0f, 1.0f,
            -1.0f, -1.0f,   0.0f, 0.0f,
             1.0f, -1.0f,   1.0f, 0.0f,
             1.0f,  1.0f,   1.0f, 1.0f
        };
        
        int[] indices = {
            0, 1, 2,
            0, 2, 3
        };
        
        // Create and upload buffers
        int vbo = glCreateBuffers();
        glNamedBufferData(vbo, vertices, GL_STATIC_DRAW);
        
        int ebo = glCreateBuffers();
        glNamedBufferData(ebo, indices, GL_STATIC_DRAW);
        
        // Setup vertex attributes
        glVertexArrayVertexBuffer(vao, 0, vbo, 0, 4 * Float.BYTES);
        glVertexArrayElementBuffer(vao, ebo);
        
        glEnableVertexArrayAttrib(vao, 0);
        glEnableVertexArrayAttrib(vao, 1);
        
        glVertexArrayAttribFormat(vao, 0, 2, GL_FLOAT, false, 0);
        glVertexArrayAttribFormat(vao, 1, 2, GL_FLOAT, false, 2 * Float.BYTES);
        
        glVertexArrayAttribBinding(vao, 0, 0);
        glVertexArrayAttribBinding(vao, 1, 0);
    }
    
    private List<EnhancedVoxelOctreeNode> performFrustumCulling() {
        // Create frustum from camera matrices
        var frustumPlanes = extractFrustumPlanes();
        var frustum = new SpatialIndexRenderBridge.Frustum(
            frustumPlanes,
            new Point3f(cameraPosition.x, cameraPosition.y, cameraPosition.z)
        );
        
        // Get visible nodes
        return renderBridge.getVisibleNodes(frustum, MAX_VISIBLE_NODES);
    }
    
    private float[][] extractFrustumPlanes() {
        // Extract frustum planes from projection * view matrix
        var pvMatrix = new Matrix4f();
        projectionMatrix.mul(viewMatrix, pvMatrix);
        
        var planes = new float[6][4];
        
        // Left plane
        planes[0][0] = pvMatrix.m03() + pvMatrix.m00();
        planes[0][1] = pvMatrix.m13() + pvMatrix.m10();
        planes[0][2] = pvMatrix.m23() + pvMatrix.m20();
        planes[0][3] = pvMatrix.m33() + pvMatrix.m30();
        
        // Right plane
        planes[1][0] = pvMatrix.m03() - pvMatrix.m00();
        planes[1][1] = pvMatrix.m13() - pvMatrix.m10();
        planes[1][2] = pvMatrix.m23() - pvMatrix.m20();
        planes[1][3] = pvMatrix.m33() - pvMatrix.m30();
        
        // Bottom plane
        planes[2][0] = pvMatrix.m03() + pvMatrix.m01();
        planes[2][1] = pvMatrix.m13() + pvMatrix.m11();
        planes[2][2] = pvMatrix.m23() + pvMatrix.m21();
        planes[2][3] = pvMatrix.m33() + pvMatrix.m31();
        
        // Top plane
        planes[3][0] = pvMatrix.m03() - pvMatrix.m01();
        planes[3][1] = pvMatrix.m13() - pvMatrix.m11();
        planes[3][2] = pvMatrix.m23() - pvMatrix.m21();
        planes[3][3] = pvMatrix.m33() - pvMatrix.m31();
        
        // Near plane
        planes[4][0] = pvMatrix.m03() + pvMatrix.m02();
        planes[4][1] = pvMatrix.m13() + pvMatrix.m12();
        planes[4][2] = pvMatrix.m23() + pvMatrix.m22();
        planes[4][3] = pvMatrix.m33() + pvMatrix.m32();
        
        // Far plane
        planes[5][0] = pvMatrix.m03() - pvMatrix.m02();
        planes[5][1] = pvMatrix.m13() - pvMatrix.m12();
        planes[5][2] = pvMatrix.m23() - pvMatrix.m22();
        planes[5][3] = pvMatrix.m33() - pvMatrix.m32();
        
        // Normalize planes
        for (var plane : planes) {
            float length = (float)Math.sqrt(plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2]);
            if (length > 0) {
                plane[0] /= length;
                plane[1] /= length;
                plane[2] /= length;
                plane[3] /= length;
            }
        }
        
        return planes;
    }
    
    private List<EnhancedVoxelOctreeNode> selectLOD(List<EnhancedVoxelOctreeNode> nodes) {
        var lodNodes = new ArrayList<EnhancedVoxelOctreeNode>();
        
        for (var node : nodes) {
            // Calculate distance to camera
            var bounds = node.getBounds();
            float centerX = (bounds[0] + bounds[3]) * 0.5f;
            float centerY = (bounds[1] + bounds[4]) * 0.5f;
            float centerZ = (bounds[2] + bounds[5]) * 0.5f;
            
            float dx = centerX - cameraPosition.x;
            float dy = centerY - cameraPosition.y;
            float dz = centerZ - cameraPosition.z;
            float distance = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
            
            // Select LOD based on distance
            int targetDepth = calculateTargetLOD(distance);
            
            if (node.getDepth() <= targetDepth) {
                lodNodes.add(node);
            }
        }
        
        return lodNodes;
    }
    
    private int calculateTargetLOD(float distance) {
        // Simple LOD selection based on distance
        if (distance < 10.0f) return 8;  // Maximum detail
        if (distance < 25.0f) return 6;
        if (distance < 50.0f) return 4;
        if (distance < 100.0f) return 2;
        return 0; // Minimum detail
    }
    
    private void updateGPUBuffers(List<EnhancedVoxelOctreeNode> nodes) {
        // Update GPU buffers for visible nodes if needed
        // This is a simplified version - a real implementation would
        // track dirty nodes and update only changed data
        
        if (!nodes.isEmpty()) {
            // For now, re-upload the entire octree if there are changes
            // In production, this would be optimized to update only changed nodes
            if (rootNode != null && nodeCount > 0) {
                // Buffer updates handled by GPUMemoryManager
            }
        }
    }
    
    private void updateNodeGPUBuffer(EnhancedVoxelOctreeNode node) {
        // Update a specific node in GPU memory
        // This would calculate the offset in the SSBO and update just that node
        log.fine("Updating GPU buffer for node at depth " + node.getDepth());
    }
    
    private void renderVoxels(List<EnhancedVoxelOctreeNode> nodes) {
        var shader = shaders.get("voxel");
        shader.use();
        
        // Set uniforms
        var projArray = new float[16];
        var viewArray = new float[16];
        
        projectionMatrix.get(projArray);
        viewMatrix.get(viewArray);
        
        shader.setMat4("uProjection", projArray);
        shader.setMat4("uView", viewArray);
        shader.setVec3("uCameraPos", cameraPosition.x, cameraPosition.y, cameraPosition.z);
        shader.setVec3("uLightPos", 100.0f, 100.0f, 100.0f);
        shader.setVec3("uLightColor", 1.0f, 1.0f, 1.0f);
        
        // Bind voxel SSBO
        gpuMemoryManager.bindBuffer("voxel_octree", 0);
        
        // Render voxel geometry
        // This is simplified - actual implementation would generate
        // geometry from voxel data or use compute shaders
        for (var node : nodes) {
            renderVoxelNode(node, shader);
        }
    }
    
    private void renderVoxelNode(EnhancedVoxelOctreeNode node, Shader shader) {
        // Render a single voxel node
        // In a real implementation, this would generate cube geometry
        // or use instanced rendering for efficiency
    }
    
    private void renderPostProcessing() {
        // Bind default framebuffer
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, viewportWidth, viewportHeight);
        
        var shader = shaders.get("postprocess");
        shader.use();
        
        // Bind color texture
        glBindTextureUnit(0, colorTexture);
        shader.setInt("uColorTexture", 0);
        shader.setFloat("uExposure", 1.0f);
        shader.setFloat("uGamma", 2.2f);
        
        // Render full-screen quad
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }
    
    private void logPerformanceMetrics() {
        var sb = new StringBuilder("Performance metrics:\n");
        timings.forEach((name, time) -> {
            sb.append("  ").append(name).append(": ").append(time / 1_000_000.0).append(" ms\n");
        });
        log.fine(sb.toString());
    }
}