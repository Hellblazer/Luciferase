package com.hellblazer.luciferase.render.webgpu.shaders;

import com.hellblazer.luciferase.webgpu.wrapper.Device;
import com.hellblazer.luciferase.webgpu.wrapper.ShaderModule;
import com.hellblazer.luciferase.webgpu.wrapper.Device.ShaderModuleDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages WGSL shader loading, compilation, and caching.
 */
public class ShaderManager {
    private static final Logger log = LoggerFactory.getLogger(ShaderManager.class);
    
    private final Device device;
    private final Map<String, ShaderModule> shaderCache = new HashMap<>();
    private final Map<String, String> shaderSourceCache = new HashMap<>();
    
    public ShaderManager(Device device) {
        this.device = device;
    }
    
    /**
     * Load a shader from a resource file.
     */
    public ShaderModule loadShaderFromResource(String resourcePath) {
        return loadShaderFromResource(resourcePath, resourcePath);
    }
    
    /**
     * Load a shader from a resource file with a custom name.
     */
    public ShaderModule loadShaderFromResource(String name, String resourcePath) {
        // Check cache first
        ShaderModule cached = shaderCache.get(name);
        if (cached != null) {
            log.debug("Using cached shader: {}", name);
            return cached;
        }
        
        try {
            String source = loadResourceAsString(resourcePath);
            return loadShader(name, source);
        } catch (IOException e) {
            log.error("Failed to load shader resource: {}", resourcePath, e);
            throw new RuntimeException("Failed to load shader: " + resourcePath, e);
        }
    }
    
    /**
     * Load a shader from a string.
     */
    public ShaderModule loadShader(String name, String wgslCode) {
        // Check cache first
        ShaderModule cached = shaderCache.get(name);
        if (cached != null) {
            log.debug("Using cached shader: {}", name);
            return cached;
        }
        
        log.info("Compiling shader: {}", name);
        
        // Preprocess shader
        String processedCode = preprocessShader(wgslCode);
        
        // Create shader module
        ShaderModuleDescriptor desc = new ShaderModuleDescriptor(processedCode)
            .withLabel(name);
        
        ShaderModule module = device.createShaderModule(desc);
        
        // Validation callback not available in current wrapper
        // Shader compilation errors will be reported when creating the module
        log.info("Shader '{}' created", name);
        
        // Cache the module and source
        shaderCache.put(name, module);
        shaderSourceCache.put(name, processedCode);
        
        return module;
    }
    
    /**
     * Get or create the default voxel vertex shader.
     */
    public ShaderModule getVoxelVertexShader() {
        String name = "voxel_vertex";
        ShaderModule cached = shaderCache.get(name);
        if (cached != null) {
            return cached;
        }
        
        String source = """
            struct Uniforms {
                viewProjection: mat4x4<f32>,
                model: mat4x4<f32>,
                lightDirection: vec3<f32>,
                time: f32,
            }
            
            struct VertexInput {
                @location(0) position: vec3<f32>,
                @location(1) normal: vec3<f32>,
                @location(2) texCoord: vec2<f32>,
            }
            
            struct InstanceInput {
                // Instance transform matrix (4x4)
                @location(3) instanceTransform0: vec4<f32>,
                @location(4) instanceTransform1: vec4<f32>,
                @location(5) instanceTransform2: vec4<f32>,
                @location(6) instanceTransform3: vec4<f32>,
                // Instance color
                @location(7) instanceColor: vec4<f32>,
            }
            
            struct VertexOutput {
                @builtin(position) position: vec4<f32>,
                @location(0) worldNormal: vec3<f32>,
                @location(1) color: vec4<f32>,
                @location(2) worldPos: vec3<f32>,
                @location(3) texCoord: vec2<f32>,
            }
            
            @group(0) @binding(0) var<uniform> uniforms: Uniforms;
            
            @vertex
            fn vs_main(vertex: VertexInput, instance: InstanceInput) -> VertexOutput {
                // Reconstruct instance transform matrix
                let instanceTransform = mat4x4<f32>(
                    instance.instanceTransform0,
                    instance.instanceTransform1,
                    instance.instanceTransform2,
                    instance.instanceTransform3
                );
                
                // Transform vertex position
                let worldPos = instanceTransform * vec4<f32>(vertex.position, 1.0);
                let clipPos = uniforms.viewProjection * worldPos;
                
                // Transform normal
                let normalMatrix = mat3x3<f32>(
                    instanceTransform[0].xyz,
                    instanceTransform[1].xyz,
                    instanceTransform[2].xyz
                );
                let worldNormal = normalize(normalMatrix * vertex.normal);
                
                var output: VertexOutput;
                output.position = clipPos;
                output.worldNormal = worldNormal;
                output.color = instance.instanceColor;
                output.worldPos = worldPos.xyz;
                output.texCoord = vertex.texCoord;
                
                return output;
            }
            """;
        
        return loadShader(name, source);
    }
    
    /**
     * Get or create the default voxel fragment shader.
     */
    public ShaderModule getVoxelFragmentShader() {
        String name = "voxel_fragment";
        ShaderModule cached = shaderCache.get(name);
        if (cached != null) {
            return cached;
        }
        
        String source = """
            struct Uniforms {
                viewProjection: mat4x4<f32>,
                model: mat4x4<f32>,
                lightDirection: vec3<f32>,
                time: f32,
            }
            
            struct FragmentInput {
                @location(0) worldNormal: vec3<f32>,
                @location(1) color: vec4<f32>,
                @location(2) worldPos: vec3<f32>,
                @location(3) texCoord: vec2<f32>,
            }
            
            @group(0) @binding(0) var<uniform> uniforms: Uniforms;
            
            @fragment
            fn fs_main(input: FragmentInput) -> @location(0) vec4<f32> {
                // Normalize the normal
                let normal = normalize(input.worldNormal);
                
                // Simple directional lighting
                let lightDir = normalize(uniforms.lightDirection);
                let NdotL = max(dot(normal, lightDir), 0.0);
                
                // Ambient + diffuse lighting
                let ambient = 0.3;
                let diffuse = NdotL * 0.7;
                let lighting = ambient + diffuse;
                
                // Apply lighting to color
                var finalColor = input.color;
                finalColor = vec4<f32>(finalColor.rgb * lighting, finalColor.a);
                
                // Add some edge highlighting for better voxel definition
                let edgeFactor = 1.0 - abs(dot(normal, normalize(input.worldPos)));
                let edgeHighlight = pow(edgeFactor, 2.0) * 0.1;
                finalColor = vec4<f32>(finalColor.rgb + vec3<f32>(edgeHighlight), finalColor.a);
                
                return finalColor;
            }
            """;
        
        return loadShader(name, source);
    }
    
    /**
     * Preprocess shader code to add common definitions.
     */
    private String preprocessShader(String wgslCode) {
        StringBuilder processed = new StringBuilder();
        
        // Add common constants
        processed.append("// Auto-generated definitions\n");
        processed.append("const PI: f32 = 3.14159265359;\n");
        processed.append("const TAU: f32 = 6.28318530718;\n");
        processed.append("const EPSILON: f32 = 0.0001;\n");
        processed.append("\n");
        
        // Add the original shader code
        processed.append(wgslCode);
        
        return processed.toString();
    }
    
    /**
     * Load a resource file as a string.
     */
    private String loadResourceAsString(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    
    /**
     * Clear the shader cache.
     */
    public void clearCache() {
        log.info("Clearing shader cache ({} shaders)", shaderCache.size());
        
        // Destroy method not available in current wrapper
        // Shader modules will be cleaned up when WebGPU context is destroyed
        
        shaderCache.clear();
        shaderSourceCache.clear();
    }
    
    /**
     * Get the source code for a cached shader.
     */
    public String getCachedSource(String name) {
        return shaderSourceCache.get(name);
    }
    
    /**
     * Check if a shader is cached.
     */
    public boolean isCached(String name) {
        return shaderCache.containsKey(name);
    }
}