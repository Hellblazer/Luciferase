/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock GPU context for headless testing.
 * Simulates GPU operations without requiring OpenGL context.
 */
public class MockGPUContext {
    
    private static final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, MockBuffer> buffers = new HashMap<>();
    private final Map<Integer, MockShader> shaders = new HashMap<>();
    private final Map<Integer, MockTexture> textures = new HashMap<>();
    
    // Mock buffer operations
    public int createBuffer() {
        int id = nextId.getAndIncrement();
        buffers.put(id, new MockBuffer(id));
        return id;
    }
    
    public void bufferData(int bufferId, ByteBuffer data, int usage) {
        MockBuffer buffer = buffers.get(bufferId);
        if (buffer != null) {
            buffer.setData(data);
            buffer.setUsage(usage);
        }
    }
    
    public void deleteBuffer(int bufferId) {
        buffers.remove(bufferId);
    }
    
    // Mock shader operations
    public int createShader(int type) {
        int id = nextId.getAndIncrement();
        shaders.put(id, new MockShader(id, type));
        return id;
    }
    
    public void shaderSource(int shaderId, String source) {
        MockShader shader = shaders.get(shaderId);
        if (shader != null) {
            shader.setSource(source);
        }
    }
    
    public boolean compileShader(int shaderId) {
        MockShader shader = shaders.get(shaderId);
        if (shader != null) {
            return shader.compile();
        }
        return false;
    }
    
    public String getShaderInfoLog(int shaderId) {
        MockShader shader = shaders.get(shaderId);
        return shader != null ? shader.getInfoLog() : "";
    }
    
    public void deleteShader(int shaderId) {
        shaders.remove(shaderId);
    }
    
    // Mock compute operations
    public void dispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {
        // Simulate compute dispatch - could add timing or validation
    }
    
    public void memoryBarrier(int barriers) {
        // Simulate memory barrier
    }
    
    // Mock buffer classes
    public static class MockBuffer {
        private final int id;
        private ByteBuffer data;
        private int usage;
        private final long creationTime;
        
        public MockBuffer(int id) {
            this.id = id;
            this.creationTime = System.nanoTime();
        }
        
        public void setData(ByteBuffer data) {
            this.data = data;
        }
        
        public ByteBuffer getData() {
            return data;
        }
        
        public void setUsage(int usage) {
            this.usage = usage;
        }
        
        public int getUsage() {
            return usage;
        }
        
        public int getId() {
            return id;
        }
        
        public int getSize() {
            return data != null ? data.remaining() : 0;
        }
    }
    
    public static class MockShader {
        private final int id;
        private final int type;
        private String source;
        private boolean compiled = false;
        private String infoLog = "";
        
        public MockShader(int id, int type) {
            this.id = id;
            this.type = type;
        }
        
        public void setSource(String source) {
            this.source = source;
            this.compiled = false;
        }
        
        public String getSource() {
            return source;
        }
        
        public boolean compile() {
            if (source == null || source.trim().isEmpty()) {
                infoLog = "ERROR: Empty shader source";
                compiled = false;
                return false;
            }
            
            // Basic validation - check for required keywords
            if (type == 0x91B9 && !source.contains("compute")) { // GL_COMPUTE_SHADER
                infoLog = "ERROR: Compute shader missing 'compute' keyword";
                compiled = false;
                return false;
            }
            
            // Simulate successful compilation
            infoLog = "Compilation successful";
            compiled = true;
            return true;
        }
        
        public boolean isCompiled() {
            return compiled;
        }
        
        public String getInfoLog() {
            return infoLog;
        }
        
        public int getId() {
            return id;
        }
        
        public int getType() {
            return type;
        }
    }
    
    public static class MockTexture {
        private final int id;
        private int width, height, depth;
        private int format;
        private ByteBuffer data;
        
        public MockTexture(int id) {
            this.id = id;
        }
        
        public void setDimensions(int width, int height, int depth) {
            this.width = width;
            this.height = height;
            this.depth = depth;
        }
        
        public void setFormat(int format) {
            this.format = format;
        }
        
        public void setData(ByteBuffer data) {
            this.data = data;
        }
        
        public int getId() {
            return id;
        }
        
        public int getWidth() {
            return width;
        }
        
        public int getHeight() {
            return height;
        }
    }
    
    // Utility methods for testing
    public int getBufferCount() {
        return buffers.size();
    }
    
    public int getShaderCount() {
        return shaders.size();
    }
    
    public MockBuffer getBuffer(int id) {
        return buffers.get(id);
    }
    
    public MockShader getShader(int id) {
        return shaders.get(id);
    }
    
    public void reset() {
        buffers.clear();
        shaders.clear();
        textures.clear();
        nextId.set(1);
    }
}