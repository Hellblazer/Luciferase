package com.hellblazer.luciferase.render.gpu;

import java.util.Map;

/**
 * Abstract GPU compute shader interface for both OpenGL and BGFX backends.
 * Provides unified shader compilation, uniform management, and execution.
 */
public interface IGPUShader {
    
    /**
     * Compile the shader from GLSL source code.
     * @param source GLSL compute shader source
     * @param defines Preprocessor defines for compilation
     * @return true if compilation successful, false otherwise
     */
    boolean compile(String source, Map<String, String> defines);
    
    /**
     * Set a uniform value for the shader.
     * @param name Uniform variable name
     * @param value Uniform value (supports various types)
     */
    void setUniform(String name, Object value);
    
    /**
     * Set a vector uniform (vec2, vec3, vec4).
     * @param name Uniform variable name
     * @param values Vector components
     */
    void setUniformVector(String name, float... values);
    
    /**
     * Set a matrix uniform (mat3, mat4).
     * @param name Uniform variable name
     * @param matrix Matrix values in column-major order
     */
    void setUniformMatrix(String name, float[] matrix);
    
    /**
     * Set an integer uniform.
     * @param name Uniform variable name
     * @param value Integer value
     */
    void setUniformInt(String name, int value);
    
    /**
     * Set a float uniform.
     * @param name Uniform variable name
     * @param value Float value
     */
    void setUniformFloat(String name, float value);
    
    /**
     * Check if the shader compiled successfully and is ready for use.
     * @return true if shader is valid
     */
    boolean isValid();
    
    /**
     * Get the compilation log (errors, warnings, info).
     * @return Compilation log string
     */
    String getCompilationLog();
    
    /**
     * Get the work group size specified in the shader.
     * @return Work group size as [x, y, z]
     */
    int[] getWorkGroupSize();
    
    /**
     * Get the backend-specific shader handle.
     * For debugging and advanced use cases.
     * @return Native shader handle (implementation-specific)
     */
    Object getNativeHandle();
    
    /**
     * Check if a uniform exists in the shader.
     * @param name Uniform variable name
     * @return true if uniform exists
     */
    boolean hasUniform(String name);
    
    /**
     * Get all uniform names defined in the shader.
     * @return Map of uniform names to their types
     */
    Map<String, String> getUniforms();
    
    /**
     * Destroy the shader and release GPU resources.
     * Shader becomes invalid after this call.
     */
    void destroy();
}