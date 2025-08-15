package com.hellblazer.luciferase.render.lwjgl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL46.*;

/**
 * Pure LWJGL shader management.
 */
public class Shader {
    private static final Logger log = LoggerFactory.getLogger(Shader.class);
    
    private final int programId;
    
    public Shader(String vertexSource, String fragmentSource) {
        // Compile vertex shader
        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexSource);
        
        // Compile fragment shader
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
        
        // Link program
        programId = glCreateProgram();
        glAttachShader(programId, vertexShader);
        glAttachShader(programId, fragmentShader);
        glLinkProgram(programId);
        
        if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
            String error = glGetProgramInfoLog(programId);
            throw new RuntimeException("Failed to link shader program: " + error);
        }
        
        // Clean up shaders
        glDetachShader(programId, vertexShader);
        glDetachShader(programId, fragmentShader);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
    }
    
    private int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String error = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new RuntimeException("Failed to compile shader: " + error);
        }
        
        return shader;
    }
    
    public void use() {
        glUseProgram(programId);
    }
    
    public void setInt(String name, int value) {
        glUniform1i(glGetUniformLocation(programId, name), value);
    }
    
    public void setFloat(String name, float value) {
        glUniform1f(glGetUniformLocation(programId, name), value);
    }
    
    public void setVec3(String name, float x, float y, float z) {
        glUniform3f(glGetUniformLocation(programId, name), x, y, z);
    }
    
    public void setMat4(String name, float[] matrix) {
        glUniformMatrix4fv(glGetUniformLocation(programId, name), false, matrix);
    }
    
    public void cleanup() {
        glDeleteProgram(programId);
    }
    
    public static Shader loadFromFiles(String vertexPath, String fragmentPath) throws IOException {
        String vertexSource = Files.readString(Path.of(vertexPath));
        String fragmentSource = Files.readString(Path.of(fragmentPath));
        return new Shader(vertexSource, fragmentSource);
    }
}