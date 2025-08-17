/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.gpu;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mock GPU buffer implementation for testing.
 * Simulates buffer operations across different GPU backends.
 */
public class MockBuffer implements IGPUBuffer {
    
    private final int id;
    private final BufferType type;
    private final int initialSize;
    private final BufferUsage usage;
    private final GPUConfig.Backend backend;
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final long creationTime;
    
    private ByteBuffer data;
    private int currentSize;
    private AccessType lastAccessType = AccessType.READ_WRITE;
    private long lastModified;
    private long accessCount = 0;
    
    public MockBuffer(int id, BufferType type, int size, BufferUsage usage, GPUConfig.Backend backend) {
        this.id = id;
        this.type = type;
        this.initialSize = size;
        this.currentSize = size;
        this.usage = usage;
        this.backend = backend;
        this.creationTime = System.nanoTime();
        this.lastModified = creationTime;
        
        // Allocate initial buffer
        if (size > 0) {
            this.data = ByteBuffer.allocateDirect(size);
        }
    }
    
    @Override
    public void upload(ByteBuffer data, int offset) {
        if (!valid.get()) {
            throw new IllegalStateException("Buffer has been destroyed");
        }
        
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
        
        if (offset < 0 || offset + data.remaining() > currentSize) {
            throw new IllegalArgumentException("Write operation out of bounds");
        }
        
        // Simulate write operation
        if (this.data != null) {
            int oldPosition = this.data.position();
            this.data.position(offset);
            this.data.put(data);
            this.data.position(oldPosition);
        }
        
        lastAccessType = AccessType.WRITE_ONLY;
        lastModified = System.nanoTime();
        accessCount++;
    }
    
    @Override
    public ByteBuffer download(int offset, int size) {
        if (!valid.get()) {
            throw new IllegalStateException("Buffer has been destroyed");
        }
        
        if (offset < 0 || offset + size > currentSize) {
            throw new IllegalArgumentException("Download operation out of bounds");
        }
        
        // Simulate download operation
        ByteBuffer result = ByteBuffer.allocate(size);
        
        if (this.data != null) {
            int oldPosition = this.data.position();
            this.data.position(offset);
            
            for (int i = 0; i < size && this.data.hasRemaining(); i++) {
                result.put(this.data.get());
            }
            
            this.data.position(oldPosition);
        }
        
        result.flip();
        lastAccessType = AccessType.READ_ONLY;
        accessCount++;
        
        return result;
    }
    
    @Override
    public ByteBuffer map(AccessType access) {
        if (!valid.get()) {
            throw new IllegalStateException("Buffer has been destroyed");
        }
        
        if (access == null) {
            throw new IllegalArgumentException("Access type cannot be null");
        }
        
        // Simulate buffer mapping - return a view of the internal buffer
        if (data != null) {
            lastAccessType = access;
            accessCount++;
            return data.duplicate();  // Return a duplicate view
        }
        
        return null;  // Mapping not supported for this mock
    }
    
    @Override
    public void unmap() {
        if (!valid.get()) {
            throw new IllegalStateException("Buffer has been destroyed");
        }
        
        // Simulate buffer unmapping
        accessCount++;
    }
    
    @Override
    public boolean isMapped() {
        // For this mock, we'll always return false since we don't track mapping state
        return false;
    }
    
    @Override
    public void bind(int slot, AccessType access) {
        if (!valid.get()) {
            throw new IllegalStateException("Buffer has been destroyed");
        }
        
        if (slot < 0) {
            throw new IllegalArgumentException("Binding slot must be non-negative");
        }
        
        if (access == null) {
            throw new IllegalArgumentException("Access type cannot be null");
        }
        
        // Simulate binding - different backends may have different binding mechanisms
        switch (backend) {
            case OPENGL -> {
                // Simulate OpenGL buffer binding
                // glBindBufferBase(GL_SHADER_STORAGE_BUFFER, slot, bufferId);
            }
            case BGFX_METAL -> {
                // Simulate Metal buffer binding
                // [encoder setBuffer:buffer offset:0 atIndex:slot];
            }
            case BGFX_VULKAN -> {
                // Simulate Vulkan descriptor set binding
                // vkCmdBindDescriptorSets(...);
            }
        }
        
        lastAccessType = access;
        accessCount++;
    }
    
    @Override
    public void unbind() {
        if (!valid.get()) {
            throw new IllegalStateException("Buffer has been destroyed");
        }
        
        // Simulate unbinding operation
        accessCount++;
    }
    
    @Override
    public int getSize() {
        return currentSize;
    }
    
    @Override
    public BufferType getType() {
        return type;
    }
    
    @Override
    public BufferUsage getUsage() {
        return usage;
    }
    
    @Override
    public boolean isValid() {
        return valid.get();
    }
    
    @Override
    public Object getNativeHandle() {
        return switch (backend) {
            case OPENGL -> id; // OpenGL buffer ID
            case BGFX_METAL -> "metal_buffer_" + id; // Metal buffer handle
            case BGFX_VULKAN -> "vk_buffer_" + id; // Vulkan buffer handle
            default -> id;
        };
    }
    
    @Override
    public void destroy() {
        if (valid.compareAndSet(true, false)) {
            // Clean up resources
            if (data != null) {
                // In a real implementation, this would free GPU memory
                data = null;
            }
            currentSize = 0;
        }
    }
    
    public void cleanup() {
        destroy();
    }
    
    // Testing utilities
    
    public int getId() {
        return id;
    }
    
    public GPUConfig.Backend getBackend() {
        return backend;
    }
    
    public long getCreationTime() {
        return creationTime;
    }
    
    public long getLastModified() {
        return lastModified;
    }
    
    public AccessType getLastAccessType() {
        return lastAccessType;
    }
    
    public long getAccessCount() {
        return accessCount;
    }
    
    public int getInitialSize() {
        return initialSize;
    }
    
    public ByteBuffer getData() {
        return data != null ? data.asReadOnlyBuffer() : null;
    }
    
    public boolean hasData() {
        return data != null;
    }
    
    public void resetAccessTracking() {
        accessCount = 0;
        lastAccessType = AccessType.READ_WRITE;
    }
    
    @Override
    public String toString() {
        return "MockBuffer{" +
                "id=" + id +
                ", type=" + type +
                ", size=" + currentSize +
                ", usage=" + usage +
                ", backend=" + backend +
                ", valid=" + valid.get() +
                ", accessCount=" + accessCount +
                '}';
    }
}