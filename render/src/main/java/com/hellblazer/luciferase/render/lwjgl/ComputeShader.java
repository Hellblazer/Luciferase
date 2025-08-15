package com.hellblazer.luciferase.render.lwjgl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL46.*;

/**
 * Pure LWJGL compute shader.
 */
public class ComputeShader {
    private static final Logger log = LoggerFactory.getLogger(ComputeShader.class);
    
    private final int programId;
    
    public ComputeShader(String source) {
        // Compile compute shader
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, source);
        glCompileShader(shader);
        
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String error = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new RuntimeException("Failed to compile compute shader: " + error);
        }
        
        // Link program
        programId = glCreateProgram();
        glAttachShader(programId, shader);
        glLinkProgram(programId);
        
        if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
            String error = glGetProgramInfoLog(programId);
            glDeleteProgram(programId);
            glDeleteShader(shader);
            throw new RuntimeException("Failed to link compute shader: " + error);
        }
        
        // Clean up shader
        glDetachShader(programId, shader);
        glDeleteShader(shader);
        
        log.debug("Created compute shader program {}", programId);
    }
    
    public void use() {
        glUseProgram(programId);
    }
    
    public void dispatch(int x, int y, int z) {
        glUseProgram(programId);
        glDispatchCompute(x, y, z);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }
    
    public void setInt(String name, int value) {
        glUniform1i(glGetUniformLocation(programId, name), value);
    }
    
    public void cleanup() {
        glDeleteProgram(programId);
    }
}