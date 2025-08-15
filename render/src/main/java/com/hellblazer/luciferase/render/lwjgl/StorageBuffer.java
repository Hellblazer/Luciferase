package com.hellblazer.luciferase.render.lwjgl;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL46.*;

/**
 * OpenGL Storage Buffer (SSBO) for compute shaders.
 */
public class StorageBuffer {
    private final int bufferId;
    private final long size;
    
    public StorageBuffer(long size) {
        this.size = size;
        this.bufferId = glGenBuffers();
        
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, bufferId);
        glBufferData(GL_SHADER_STORAGE_BUFFER, size, GL_DYNAMIC_COPY);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }
    
    public void bind(int binding) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, bufferId);
    }
    
    public void write(ByteBuffer data) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, bufferId);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, data);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }
    
    public ByteBuffer read() {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, bufferId);
        ByteBuffer buffer = MemoryUtil.memAlloc((int)size);
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, buffer);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        return buffer;
    }
    
    public void cleanup() {
        glDeleteBuffers(bufferId);
    }
    
    public int getId() {
        return bufferId;
    }
}