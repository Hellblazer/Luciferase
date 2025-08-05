package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.render.voxel.gpu.WebGPUStubs.*;
import static com.hellblazer.luciferase.render.voxel.gpu.WebGPUStubs.*;
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
    
    private final Device device;
    private final Map<String, ShaderModule> shaderCache = new HashMap<>();
    private final Map<String, ComputePipeline> pipelineCache = new HashMap<>();
    private final Map<String, BindGroupLayout> layoutCache = new HashMap<>();
    
    public ComputeShaderManager(Device device) {
        this.device = device;
    }
    
    /**
     * Load shader from string source
     */
    public CompletableFuture<ShaderModule> loadShader(String name, String wgslCode) {
        // Check cache first
        ShaderModule cached = shaderCache.get(name);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        CompletableFuture<ShaderModule> future = new CompletableFuture<>();
        
        ShaderModuleDescriptor desc = new ShaderModuleDescriptor();
        desc.setLabel(name);
        
        // Create WGSL descriptor
        ShaderModuleWGSLDescriptor wgslDesc = new ShaderModuleWGSLDescriptor();
        wgslDesc.setCode(wgslCode);
        desc.setNextInChain(wgslDesc);
        
        ShaderModule module = device.createShaderModule(desc);
        
        if (module == null) {
            future.completeExceptionally(new RuntimeException("Failed to create shader module: " + name));
            return future;
        }
        
        // Get compilation info for validation
        module.getCompilationInfo((info) -> {
            boolean hasErrors = false;
            for (CompilationMessage msg : info.getMessages()) {
                String location = String.format("%s:%d:%d", 
                    name, msg.getLineNum(), msg.getLinePos());
                
                switch (msg.getType()) {
                    case ERROR:
                        log.error("Shader compilation error at {}: {}", location, msg.getMessage());
                        hasErrors = true;
                        break;
                    case WARNING:
                        log.warn("Shader compilation warning at {}: {}", location, msg.getMessage());
                        break;
                    case INFO:
                        log.debug("Shader compilation info at {}: {}", location, msg.getMessage());
                        break;
                }
            }
            
            if (hasErrors) {
                module.release();
                future.completeExceptionally(new RuntimeException(
                    "Shader compilation failed for: " + name));
            } else {
                shaderCache.put(name, module);
                log.info("Successfully compiled shader: {}", name);
                future.complete(module);
            }
        });
        
        return future;
    }
    
    /**
     * Load shader from resources
     */
    public CompletableFuture<ShaderModule> loadShaderFromResource(String resourcePath) {
        try {
            String code = loadResourceAsString(resourcePath);
            String name = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            return loadShader(name, code);
        } catch (IOException e) {
            CompletableFuture<ShaderModule> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }
    
    /**
     * Create a compute pipeline with auto-layout
     */
    public ComputePipeline createComputePipeline(String name, 
                                                ShaderModule shader,
                                                String entryPoint) {
        // Check cache
        ComputePipeline cached = pipelineCache.get(name);
        if (cached != null) {
            return cached;
        }
        
        ComputePipelineDescriptor desc = new ComputePipelineDescriptor();
        desc.setLabel(name);
        
        // Set up compute stage
        ProgrammableStage computeStage = new ProgrammableStage();
        computeStage.setModule(shader);
        computeStage.setEntryPoint(entryPoint);
        desc.setCompute(computeStage);
        
        // Use auto layout for simplicity
        desc.setLayout(null);
        
        ComputePipeline pipeline = device.createComputePipeline(desc);
        if (pipeline == null) {
            throw new RuntimeException("Failed to create compute pipeline: " + name);
        }
        
        pipelineCache.put(name, pipeline);
        log.info("Created compute pipeline: {}", name);
        
        return pipeline;
    }
    
    /**
     * Create a compute pipeline with explicit layout
     */
    public ComputePipeline createComputePipeline(String name,
                                                ShaderModule shader,
                                                String entryPoint,
                                                PipelineLayout layout) {
        // Check cache
        ComputePipeline cached = pipelineCache.get(name);
        if (cached != null) {
            return cached;
        }
        
        ComputePipelineDescriptor desc = new ComputePipelineDescriptor();
        desc.setLabel(name);
        desc.setLayout(layout);
        
        ProgrammableStage computeStage = new ProgrammableStage();
        computeStage.setModule(shader);
        computeStage.setEntryPoint(entryPoint);
        desc.setCompute(computeStage);
        
        ComputePipeline pipeline = device.createComputePipeline(desc);
        if (pipeline == null) {
            throw new RuntimeException("Failed to create compute pipeline: " + name);
        }
        
        pipelineCache.put(name, pipeline);
        log.info("Created compute pipeline with layout: {}", name);
        
        return pipeline;
    }
    
    /**
     * Create a bind group layout for octree traversal
     */
    public BindGroupLayout createOctreeTraversalLayout() {
        String layoutName = "octree_traversal_layout";
        BindGroupLayout cached = layoutCache.get(layoutName);
        if (cached != null) {
            return cached;
        }
        
        BindGroupLayoutDescriptor desc = new BindGroupLayoutDescriptor();
        desc.setLabel(layoutName);
        
        // Entry 0: Octree nodes (read-only storage buffer)
        BindGroupLayoutEntry octreeEntry = new BindGroupLayoutEntry();
        octreeEntry.setBinding(0);
        octreeEntry.setVisibility(ShaderStage.COMPUTE);
        BufferBindingLayout octreeBuffer = new BufferBindingLayout();
        octreeBuffer.setType(BufferBindingType.READ_ONLY_STORAGE);
        octreeBuffer.setHasDynamicOffset(false);
        octreeBuffer.setMinBindingSize(0);
        octreeEntry.setBuffer(octreeBuffer);
        
        // Entry 1: Rays (read-only storage buffer)
        BindGroupLayoutEntry rayEntry = new BindGroupLayoutEntry();
        rayEntry.setBinding(1);
        rayEntry.setVisibility(ShaderStage.COMPUTE);
        BufferBindingLayout rayBuffer = new BufferBindingLayout();
        rayBuffer.setType(BufferBindingType.READ_ONLY_STORAGE);
        rayBuffer.setHasDynamicOffset(false);
        rayBuffer.setMinBindingSize(0);
        rayEntry.setBuffer(rayBuffer);
        
        // Entry 2: Results (storage buffer)
        BindGroupLayoutEntry resultEntry = new BindGroupLayoutEntry();
        resultEntry.setBinding(2);
        resultEntry.setVisibility(ShaderStage.COMPUTE);
        BufferBindingLayout resultBuffer = new BufferBindingLayout();
        resultBuffer.setType(BufferBindingType.STORAGE);
        resultBuffer.setHasDynamicOffset(false);
        resultBuffer.setMinBindingSize(0);
        resultEntry.setBuffer(resultBuffer);
        
        // Entry 3: Octree info (uniform buffer)
        BindGroupLayoutEntry infoEntry = new BindGroupLayoutEntry();
        infoEntry.setBinding(3);
        infoEntry.setVisibility(ShaderStage.COMPUTE);
        BufferBindingLayout infoBuffer = new BufferBindingLayout();
        infoBuffer.setType(BufferBindingType.UNIFORM);
        infoBuffer.setHasDynamicOffset(false);
        infoBuffer.setMinBindingSize(16); // 4 u32 values
        infoEntry.setBuffer(infoBuffer);
        
        desc.setEntries(new BindGroupLayoutEntry[] {octreeEntry, rayEntry, resultEntry, infoEntry});
        
        BindGroupLayout layout = device.createBindGroupLayout(desc);
        if (layout == null) {
            throw new RuntimeException("Failed to create bind group layout");
        }
        
        layoutCache.put(layoutName, layout);
        return layout;
    }
    
    /**
     * Create a pipeline layout
     */
    public PipelineLayout createPipelineLayout(String name, BindGroupLayout... layouts) {
        PipelineLayoutDescriptor desc = new PipelineLayoutDescriptor();
        desc.setLabel(name);
        desc.setBindGroupLayouts(layouts);
        
        PipelineLayout layout = device.createPipelineLayout(desc);
        if (layout == null) {
            throw new RuntimeException("Failed to create pipeline layout: " + name);
        }
        
        return layout;
    }
    
    /**
     * Load all ESVO shaders
     */
    public CompletableFuture<Void> loadESVOShaders() {
        CompletableFuture<?>[] futures = new CompletableFuture[] {
            loadShaderFromResource("/shaders/octree_traversal.wgsl"),
            loadShaderFromResource("/shaders/voxelize.wgsl"),
            loadShaderFromResource("/shaders/filter_mipmap.wgsl")
        };
        
        return CompletableFuture.allOf(futures);
    }
    
    /**
     * Get optimal workgroup size for dispatch
     */
    public int[] calculateWorkgroupDispatch(int totalItems, int workgroupSize) {
        int numWorkgroups = (totalItems + workgroupSize - 1) / workgroupSize;
        
        // Get device limits
        SupportedLimits limits = device.getLimits();
        int maxWorkgroups = (int) limits.getLimits().getMaxComputeWorkgroupsPerDimension();
        
        if (numWorkgroups <= maxWorkgroups) {
            return new int[] { numWorkgroups, 1, 1 };
        }
        
        // Split into 2D dispatch if needed
        int sqrtGroups = (int) Math.ceil(Math.sqrt(numWorkgroups));
        if (sqrtGroups <= maxWorkgroups) {
            return new int[] { sqrtGroups, sqrtGroups, 1 };
        }
        
        // Split into 3D dispatch for very large workloads
        int cbrtGroups = (int) Math.ceil(Math.cbrt(numWorkgroups));
        return new int[] { cbrtGroups, cbrtGroups, cbrtGroups };
    }
    
    /**
     * Release all cached resources
     */
    public void cleanup() {
        pipelineCache.values().forEach(ComputePipeline::release);
        pipelineCache.clear();
        
        shaderCache.values().forEach(ShaderModule::release);
        shaderCache.clear();
        
        layoutCache.values().forEach(BindGroupLayout::release);
        layoutCache.clear();
        
        log.info("Shader manager cleanup complete");
    }
    
    private String loadResourceAsString(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
