/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.gpu;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Interface for abstracting shader compilation across different GPU backends.
 * Provides unified shader creation, variant management, and compilation capabilities
 * for both OpenGL compute shaders and BGFX Metal shaders.
 * 
 * <p>This abstraction enables seamless transition between rendering backends while
 * maintaining consistent shader management capabilities including preprocessor
 * defines, hot-reload support, and error handling.</p>
 */
public interface IShaderFactory {
    
    /**
     * Shader compilation result containing the compiled shader and any errors.
     */
    public static class CompilationResult {
        private final IGPUShader shader;
        private final boolean success;
        private final String errorMessage;
        private final Map<String, String> preprocessorLog;
        
        public CompilationResult(IGPUShader shader, Map<String, String> preprocessorLog) {
            this.shader = shader;
            this.success = true;
            this.errorMessage = null;
            this.preprocessorLog = preprocessorLog;
        }
        
        public CompilationResult(String errorMessage, Map<String, String> preprocessorLog) {
            this.shader = null;
            this.success = false;
            this.errorMessage = errorMessage;
            this.preprocessorLog = preprocessorLog;
        }
        
        public Optional<IGPUShader> getShader() {
            return Optional.ofNullable(shader);
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public Optional<String> getErrorMessage() {
            return Optional.ofNullable(errorMessage);
        }
        
        public Map<String, String> getPreprocessorLog() {
            return preprocessorLog;
        }
    }
    
    /**
     * Shader variant specification for preprocessor-based shader generation.
     */
    public static class ShaderVariant {
        private final String shaderName;
        private final Map<String, String> defines;
        private final Set<String> flags;
        private final String sourceOverride;
        
        public ShaderVariant(String shaderName, Map<String, String> defines, Set<String> flags) {
            this(shaderName, defines, flags, null);
        }
        
        public ShaderVariant(String shaderName, Map<String, String> defines, Set<String> flags, String sourceOverride) {
            this.shaderName = shaderName;
            this.defines = Map.copyOf(defines);
            this.flags = Set.copyOf(flags);
            this.sourceOverride = sourceOverride;
        }
        
        public String getShaderName() {
            return shaderName;
        }
        
        public Map<String, String> getDefines() {
            return defines;
        }
        
        public Set<String> getFlags() {
            return flags;
        }
        
        public Optional<String> getSourceOverride() {
            return Optional.ofNullable(sourceOverride);
        }
        
        /**
         * Generates a unique key for this shader variant based on name, defines, and flags.
         */
        public String getVariantKey() {
            StringBuilder key = new StringBuilder(shaderName);
            
            flags.stream().sorted().forEach(flag -> key.append("_").append(flag));
            
            defines.entrySet().stream()
                   .sorted(Map.Entry.comparingByKey())
                   .forEach(entry -> key.append("_").append(entry.getKey()).append("=").append(entry.getValue()));
            
            return key.toString();
        }
    }
    
    /**
     * Compiles a compute shader from source code with the specified preprocessor defines.
     * 
     * @param shaderName the name identifier for the shader
     * @param source the GLSL/MSL shader source code
     * @param defines map of preprocessor defines to apply
     * @return compilation result containing the shader or error information
     */
    CompilationResult compileComputeShader(String shaderName, String source, Map<String, String> defines);
    
    /**
     * Compiles a shader variant using the factory's shader loading system.
     * 
     * @param variant the shader variant specification
     * @return compilation result containing the shader or error information
     */
    CompilationResult compileShaderVariant(ShaderVariant variant);
    
    /**
     * Loads a shader source from the configured shader directory.
     * 
     * @param shaderName the name of the shader file (with extension)
     * @return the shader source code if found
     */
    Optional<String> loadShaderSource(String shaderName);
    
    /**
     * Checks if a shader with the given name exists in the factory.
     * 
     * @param shaderName the name of the shader to check
     * @return true if the shader exists and can be loaded
     */
    boolean hasShader(String shaderName);
    
    /**
     * Gets the set of all available shader names in this factory.
     * 
     * @return set of shader names (typically filenames)
     */
    Set<String> getAvailableShaders();
    
    /**
     * Validates that a shader source is compatible with this factory's backend.
     * 
     * @param source the shader source code to validate
     * @return true if the shader appears to be compatible
     */
    boolean validateShaderCompatibility(String source);
    
    /**
     * Gets information about the rendering backend this factory targets.
     * 
     * @return backend information (e.g., "OpenGL", "BGFX_Metal", "BGFX_Vulkan")
     */
    String getBackendInfo();
    
    /**
     * Gets the shader language this factory expects (e.g., "GLSL", "MSL", "HLSL").
     * 
     * @return the shader language name
     */
    String getShaderLanguage();
    
    /**
     * Preprocesses shader source with the given defines and flags.
     * This method can be used independently of compilation for debugging or validation.
     * 
     * @param source the original shader source
     * @param defines map of preprocessor defines to apply
     * @param flags set of preprocessor flags to define
     * @return the preprocessed shader source
     */
    String preprocessShader(String source, Map<String, String> defines, Set<String> flags);
    
    /**
     * Clears any cached compiled shaders and forces recompilation on next access.
     * Useful for hot-reload scenarios or debugging.
     */
    void clearCache();
    
    /**
     * Releases all resources associated with this shader factory.
     * Should be called when the factory is no longer needed.
     */
    void cleanup();
}