package com.hellblazer.luciferase.resource.opengl;

import com.hellblazer.luciferase.resource.GPUResource.ResourceStatistics;
import com.hellblazer.luciferase.resource.GPUResourceType;
import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL43.*;

/**
 * OpenGL Buffer Resource (VBO, UBO, SSBO, etc.)
 * Wraps OpenGL buffer objects with automatic lifecycle management.
 */
public class BufferResource extends ResourceHandle<Integer> implements OpenGLResource {
    private static final Logger log = LoggerFactory.getLogger(BufferResource.class);
    
    private final int target;
    private final int usage;
    private final long sizeBytes;
    private final String debugName;
    private volatile boolean isBound = false;
    
    /**
     * Create a buffer resource with the specified parameters
     * 
     * @param bufferId OpenGL buffer ID from glGenBuffers()
     * @param target OpenGL buffer target (GL_ARRAY_BUFFER, GL_UNIFORM_BUFFER, etc.)
     * @param usage OpenGL usage hint (GL_STATIC_DRAW, GL_DYNAMIC_DRAW, etc.)
     * @param sizeBytes Buffer size in bytes
     * @param debugName Human-readable name for debugging
     * @param tracker Resource tracker for lifecycle management
     */
    public BufferResource(int bufferId, int target, int usage, long sizeBytes, String debugName, ResourceTracker tracker) {
        super(bufferId, tracker);
        this.target = target;
        this.usage = usage;
        this.sizeBytes = sizeBytes;
        this.debugName = debugName != null ? debugName : "Buffer" + bufferId;
        
        log.debug("Created buffer resource: {} (ID={}, target=0x{}, size={})", 
                 this.debugName, bufferId, Integer.toHexString(target), sizeBytes);
    }
    
    @Override
    protected void doCleanup(Integer bufferId) {
        if (bufferId != null && bufferId != 0) {
            log.debug("Deleting OpenGL buffer: {} (ID={})", debugName, bufferId);
            glDeleteBuffers(bufferId);
            
            // Check for OpenGL errors
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                log.error("OpenGL error while deleting buffer {}: 0x{}", debugName, Integer.toHexString(error));
            }
        }
    }
    
    @Override
    public GPUResourceType getType() {
        return switch (target) {
            case GL_UNIFORM_BUFFER -> GPUResourceType.UNIFORM_BUFFER;
            case GL_SHADER_STORAGE_BUFFER -> GPUResourceType.STORAGE_BUFFER;
            case GL_ARRAY_BUFFER, GL_ELEMENT_ARRAY_BUFFER -> GPUResourceType.VERTEX_BUFFER;
            default -> GPUResourceType.BUFFER;
        };
    }
    
    @Override
    public int getOpenGLId() {
        return get();
    }
    
    @Override
    public Object getNativeHandle() {
        return get();
    }
    
    @Override
    public String getDescription() {
        return String.format("%s (target=0x%s, size=%d bytes)", debugName, 
                           Integer.toHexString(target), sizeBytes);
    }
    
    @Override
    public boolean isBound() {
        return isBound;
    }
    
    @Override
    public void bind() {
        if (!isValid()) {
            throw new IllegalStateException("Cannot bind disposed buffer: " + debugName);
        }
        
        glBindBuffer(target, get());
        isBound = true;
        
        log.trace("Bound buffer: {} to target 0x{}", debugName, Integer.toHexString(target));
    }
    
    @Override
    public void unbind() {
        if (isBound) {
            glBindBuffer(target, 0);
            isBound = false;
            log.trace("Unbound buffer: {}", debugName);
        }
    }
    
    @Override
    public int getOpenGLTarget() {
        return target;
    }
    
    /**
     * Get buffer usage hint
     */
    public int getUsage() {
        return usage;
    }
    
    /**
     * Get buffer size in bytes
     */
    public long getSizeBytes() {
        return sizeBytes;
    }
    
    /**
     * Get debug name
     */
    public String getDebugName() {
        return debugName;
    }
    
    @Override
    public ResourceStatistics getStatistics() {
        if (!isValid()) {
            return new ResourceStatistics(0, 0, 0, 0, 0.0);
        }
        
        // For OpenGL buffers, we assume full utilization since we can't query actual usage
        return new ResourceStatistics(
            sizeBytes,           // allocatedBytes
            sizeBytes,           // usedBytes (assume full usage)
            0,                   // accessCount (not tracked for OpenGL)
            System.currentTimeMillis(), // lastAccessTime
            100.0                // utilizationPercent (assume full)
        );
    }
    
    /**
     * Update buffer data
     * 
     * @param data New buffer data
     * @param offset Offset in bytes
     */
    public void updateData(ByteBuffer data, long offset) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot update disposed buffer: " + debugName);
        }
        
        boolean wasBound = isBound;
        if (!wasBound) {
            bind();
        }
        
        try {
            glBufferSubData(target, offset, data);
            
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                throw new RuntimeException(String.format(
                    "Failed to update buffer %s: OpenGL error 0x%X", debugName, error));
            }
            
            log.trace("Updated buffer: {} ({} bytes at offset {})", debugName, data.remaining(), offset);
            
        } finally {
            if (!wasBound) {
                unbind();
            }
        }
    }
    
    /**
     * Update entire buffer data
     */
    public void updateData(ByteBuffer data) {
        updateData(data, 0);
    }
    
    /**
     * Bind buffer to a binding point (for UBOs and SSBOs)
     * 
     * @param bindingPoint Binding point index
     */
    public void bindToBindingPoint(int bindingPoint) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot bind disposed buffer: " + debugName);
        }
        
        if (target == GL_UNIFORM_BUFFER) {
            glBindBufferBase(GL_UNIFORM_BUFFER, bindingPoint, get());
            log.trace("Bound UBO {} to binding point {}", debugName, bindingPoint);
        } else if (target == GL_SHADER_STORAGE_BUFFER) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, bindingPoint, get());
            log.trace("Bound SSBO {} to binding point {}", debugName, bindingPoint);
        } else {
            throw new UnsupportedOperationException(
                "Binding points only supported for UBOs and SSBOs, not target: 0x" + Integer.toHexString(target));
        }
    }
    
    /**
     * Static factory methods for common buffer types
     */
    public static class Factory {
        
        /**
         * Create a Shader Storage Buffer Object (SSBO)
         */
        public static BufferResource createSSBO(ByteBuffer data, String debugName) {
            int bufferId = glGenBuffers();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, bufferId);
            glBufferData(GL_SHADER_STORAGE_BUFFER, data, GL_STATIC_DRAW);
            
            return new BufferResource(bufferId, GL_SHADER_STORAGE_BUFFER, GL_STATIC_DRAW, 
                                    data.remaining(), debugName, null);
        }
        
        /**
         * Create a Shader Storage Buffer Object (SSBO) with specified size
         */
        public static BufferResource createSSBO(long sizeBytes, String debugName) {
            int bufferId = glGenBuffers();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, bufferId);
            glBufferData(GL_SHADER_STORAGE_BUFFER, sizeBytes, GL_DYNAMIC_DRAW);
            
            return new BufferResource(bufferId, GL_SHADER_STORAGE_BUFFER, GL_DYNAMIC_DRAW, 
                                    sizeBytes, debugName, null);
        }
        
        /**
         * Create a Uniform Buffer Object (UBO)
         */
        public static BufferResource createUBO(long sizeBytes, String debugName) {
            int bufferId = glGenBuffers();
            glBindBuffer(GL_UNIFORM_BUFFER, bufferId);
            glBufferData(GL_UNIFORM_BUFFER, sizeBytes, GL_DYNAMIC_DRAW);
            
            return new BufferResource(bufferId, GL_UNIFORM_BUFFER, GL_DYNAMIC_DRAW, 
                                    sizeBytes, debugName, null);
        }
        
        /**
         * Create a Vertex Buffer Object (VBO)
         */
        public static BufferResource createVBO(ByteBuffer data, String debugName) {
            int bufferId = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, bufferId);
            glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
            
            return new BufferResource(bufferId, GL_ARRAY_BUFFER, GL_STATIC_DRAW, 
                                    data.remaining(), debugName, null);
        }
    }
}