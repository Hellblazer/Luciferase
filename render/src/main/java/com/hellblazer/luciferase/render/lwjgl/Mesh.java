package com.hellblazer.luciferase.render.lwjgl;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL46.*;

/**
 * Simple mesh class for LWJGL rendering.
 */
public class Mesh {
    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int vertexCount;
    private final int indexCount;
    
    public Mesh(float[] vertices, int[] indices) {
        this.vertexCount = vertices.length / 8; // 3 pos + 3 normal + 2 uv
        this.indexCount = indices.length;
        
        // Create VAO
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        
        // Create VBO
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        
        FloatBuffer vertexBuffer = MemoryUtil.memAllocFloat(vertices.length);
        vertexBuffer.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);
        MemoryUtil.memFree(vertexBuffer);
        
        // Create EBO
        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        
        IntBuffer indexBuffer = MemoryUtil.memAllocInt(indices.length);
        indexBuffer.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);
        MemoryUtil.memFree(indexBuffer);
        
        // Setup vertex attributes
        int stride = 8 * Float.BYTES;
        
        // Position attribute
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        
        // Normal attribute
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        
        // UV attribute
        glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 6 * Float.BYTES);
        glEnableVertexAttribArray(2);
        
        // Unbind
        glBindVertexArray(0);
    }
    
    public void render() {
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }
    
    public void cleanup() {
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteVertexArrays(vao);
    }
}