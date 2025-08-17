package com.hellblazer.luciferase.render.gpu.bgfx;

import com.hellblazer.luciferase.render.gpu.*;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BGFX implementation of IGPUBuffer for compute buffer operations.
 * Wraps BGFX compute buffers for use in compute shaders.
 */
public class BGFXGPUBuffer implements IGPUBuffer {
    
    private final int id;
    private final BufferType type;
    private final int size;
    private final BufferUsage usage;
    private final AtomicBoolean valid = new AtomicBoolean(false);
    private final AtomicBoolean mapped = new AtomicBoolean(false);
    
    private short bgfxHandle = BGFX.BGFX_INVALID_HANDLE;
    private ByteBuffer mappedBuffer = null;
    private int currentSlot = -1;
    private AccessType currentAccess = null;
    
    public BGFXGPUBuffer(int id, BufferType type, int size, BufferUsage usage) {
        this.id = id;
        this.type = type;
        this.size = size;
        this.usage = usage;
    }
    
    /**
     * Initialize the BGFX buffer.
     * @return true if initialization successful
     */
    public boolean initialize() {
        if (valid.get()) {
            return true;
        }
        
        try (var stack = MemoryStack.stackPush()) {
            // Create initial data buffer (can be null for dynamic buffers)
            var initialData = MemoryUtil.memAlloc(size);
            try {
                // Clear initial data
                MemoryUtil.memSet(initialData, 0);
                
                // Create BGFX dynamic vertex buffer as compute buffer substitute
                bgfxHandle = BGFX.bgfx_create_dynamic_vertex_buffer(
                    size / 4, // Number of vertices (assuming 4 bytes per vertex)
                    null, // No vertex layout for compute  
                    getBGFXBufferFlags()
                );
                
                if (bgfxHandle == BGFX.BGFX_INVALID_HANDLE) {
                    return false;
                }
                
                valid.set(true);
                return true;
                
            } finally {
                MemoryUtil.memFree(initialData);
            }
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public void upload(ByteBuffer data, int offset) {
        if (!valid.get()) {
            throw new IllegalStateException("Buffer not initialized");
        }
        
        if (mapped.get()) {
            throw new IllegalStateException("Buffer is currently mapped");
        }
        
        if (data == null || data.remaining() == 0) {
            return;
        }
        
        if (offset + data.remaining() > size) {
            throw new IllegalArgumentException("Data exceeds buffer size");
        }
        
        // Update buffer data
        BGFX.bgfx_update_dynamic_vertex_buffer(bgfxHandle, offset, BGFX.bgfx_make_ref(data));
    }
    
    @Override
    public ByteBuffer download(int offset, int downloadSize) {
        if (!valid.get()) {
            throw new IllegalStateException("Buffer not initialized");
        }
        
        if (offset + downloadSize > size) {
            throw new IllegalArgumentException("Download range exceeds buffer size");
        }
        
        // BGFX doesn't directly support buffer readback
        // This would require a staging buffer and GPU->CPU copy
        // For now, return null to indicate unsupported operation
        return null;
    }
    
    @Override
    public void bind(int slot, AccessType access) {
        if (!valid.get()) {
            throw new IllegalStateException("Buffer not initialized");
        }
        
        // BGFX buffer binding for compute shaders
        // Note: BGFX doesn't have direct SSBO equivalents, but we can use vertex buffers
        // with compute shader access for storage operations
        
        int accessFlags = getBGFXAccessFlags(access);
        
        // For BGFX, we bind the buffer using the vertex buffer interface
        // This is a workaround since LWJGL BGFX doesn't expose compute buffer APIs
        // In production, this would need platform-specific compute buffer binding
        
        // Store current binding info for unbind operation
        this.currentSlot = slot;
        this.currentAccess = access;
        
        // TODO: Implement actual BGFX compute buffer binding when API available
        // For now, this serves as a placeholder that tracks binding state
    }
    
    @Override
    public void unbind() {
        // BGFX doesn't require explicit unbinding
        // Resources are automatically unbound between dispatches
    }
    
    @Override
    public ByteBuffer map(AccessType access) {
        if (!valid.get()) {
            throw new IllegalStateException("Buffer not initialized");
        }
        
        if (mapped.compareAndSet(false, true)) {
            // BGFX doesn't support direct buffer mapping
            // We would need to create a staging buffer for CPU access
            // For now, return null to indicate unsupported operation
            return null;
        }
        
        throw new IllegalStateException("Buffer is already mapped");
    }
    
    @Override
    public void unmap() {
        if (mapped.compareAndSet(true, false)) {
            if (mappedBuffer != null) {
                // Free any staging buffer
                mappedBuffer = null;
            }
        }
    }
    
    @Override
    public int getSize() {
        return size;
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
    public boolean isMapped() {
        return mapped.get();
    }
    
    @Override
    public boolean isValid() {
        return valid.get();
    }
    
    @Override
    public Object getNativeHandle() {
        return bgfxHandle;
    }
    
    @Override
    public void destroy() {
        if (!valid.compareAndSet(true, false)) {
            return; // Already destroyed or never initialized
        }
        
        // Unmap if currently mapped
        unmap();
        
        // Destroy BGFX buffer
        if (bgfxHandle != BGFX.BGFX_INVALID_HANDLE) {
            BGFX.bgfx_destroy_dynamic_vertex_buffer(bgfxHandle);
            bgfxHandle = BGFX.BGFX_INVALID_HANDLE;
        }
    }
    
    /**
     * Get the internal buffer ID.
     */
    public int getId() {
        return id;
    }
    
    /**
     * Get the BGFX buffer handle.
     */
    public short getHandle() {
        return bgfxHandle;
    }
    
    /**
     * Convert BufferUsage to BGFX buffer flags.
     */
    private int getBGFXBufferFlags() {
        return switch (usage) {
            case STATIC_READ -> BGFX.BGFX_BUFFER_NONE;
            case STATIC_WRITE -> BGFX.BGFX_BUFFER_NONE;
            case DYNAMIC_READ -> BGFX.BGFX_BUFFER_NONE;
            case DYNAMIC_WRITE -> BGFX.BGFX_BUFFER_NONE;
            case READ_WRITE -> BGFX.BGFX_BUFFER_NONE;
            case STREAM_TO_GPU -> BGFX.BGFX_BUFFER_NONE;
            case STREAM_FROM_GPU -> BGFX.BGFX_BUFFER_NONE;
        };
    }
    
    /**
     * Convert AccessType to BGFX access flags.
     */
    private int getBGFXAccessFlags(AccessType access) {
        return switch (access) {
            case READ_ONLY -> BGFX.BGFX_ACCESS_READ;
            case WRITE_ONLY -> BGFX.BGFX_ACCESS_WRITE;
            case READ_WRITE -> BGFX.BGFX_ACCESS_READWRITE;
        };
    }
}