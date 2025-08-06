package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.render.webgpu.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Manages WebGPU compute shaders for ESVO operations.
 * Handles shader compilation, caching, and pipeline creation.
 */
public class ComputeShaderManager {
    private static final Logger log = LoggerFactory.getLogger(ComputeShaderManager.class);
    
    private final WebGPUContext context;
    private final Map<String, ShaderHandle> shaderCache = new HashMap<>();
    private final Map<String, ShaderHandle> pipelineCache = new HashMap<>();
    
    public ComputeShaderManager(WebGPUContext context) {
        this.context = context;
    }
    
    /**
     * Load shader from string source
     */
    public CompletableFuture<ShaderHandle> loadShader(String name, String wgslCode) {
        // Check cache first
        ShaderHandle cached = shaderCache.get(name);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                ShaderHandle shader = context.createComputeShader(wgslCode);
                shaderCache.put(name, shader);
                log.debug("Loaded shader: {}", name);
                return shader;
            } catch (Exception e) {
                log.error("Failed to load shader: {}", name, e);
                throw new RuntimeException("Shader compilation failed: " + name, e);
            }
        });
    }
    
    /**
     * Load shader from resource file
     */
    public CompletableFuture<ShaderHandle> loadShaderFromResource(String resourcePath) {
        String name = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        
        // Check cache first
        ShaderHandle cached = shaderCache.get(name);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    throw new IOException("Resource not found: " + resourcePath);
                }
                
                String wgslCode = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                ShaderHandle shader = context.createComputeShader(wgslCode);
                shaderCache.put(name, shader);
                log.debug("Loaded shader from resource: {}", resourcePath);
                return shader;
            } catch (IOException e) {
                log.error("Failed to load shader resource: {}", resourcePath, e);
                throw new RuntimeException("Failed to load shader resource: " + resourcePath, e);
            }
        });
    }
    
    /**
     * Create compute pipeline
     */
    public ShaderHandle createComputePipeline(String name, ShaderHandle shader, String entryPoint) {
        String cacheKey = name + ":" + entryPoint;
        
        ShaderHandle cached = pipelineCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        // For now, return the shader handle directly
        // In a full implementation, this would create a pipeline with the shader
        pipelineCache.put(cacheKey, shader);
        log.debug("Created compute pipeline: {}", name);
        return shader;
    }
    
    /**
     * Load all ESVO shaders
     */
    public CompletableFuture<Void> loadESVOShaders() {
        return CompletableFuture.allOf(
            loadShaderFromResource("/shaders/esvo/visibility.wgsl"),
            loadShaderFromResource("/shaders/esvo/shading.wgsl"),
            loadShaderFromResource("/shaders/esvo/sparse_octree.wgsl"),
            loadShaderFromResource("/shaders/esvo/ray_marching.wgsl")
        );
    }
    
    /**
     * Calculate workgroup dispatch dimensions
     */
    public int[] calculateWorkgroupDispatch(int totalThreads, int workgroupSize) {
        int x = (totalThreads + workgroupSize - 1) / workgroupSize;
        
        // Handle large dispatches that exceed max dimension
        if (x > 65535) {
            int y = (x + 65534) / 65535;
            x = (x + y - 1) / y;
            return new int[]{x, y, 1};
        }
        
        return new int[]{x, 1, 1};
    }
    
    /**
     * Cleanup resources
     */
    public void cleanup() {
        // Release all cached shaders
        shaderCache.values().forEach(ShaderHandle::release);
        shaderCache.clear();
        
        pipelineCache.clear();
        
        log.debug("ComputeShaderManager cleaned up");
    }
}