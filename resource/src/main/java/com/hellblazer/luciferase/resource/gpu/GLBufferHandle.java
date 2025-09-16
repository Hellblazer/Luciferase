package com.hellblazer.luciferase.resource.gpu;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.lwjgl.opengl.GL45;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * RAII handle for OpenGL buffer objects (VBO, IBO, UBO, SSBO).
 * Ensures proper cleanup of GPU buffer resources.
 */
public class GLBufferHandle extends ResourceHandle<Integer> {
    private static final Logger log = LoggerFactory.getLogger(GLBufferHandle.class);
    
    public enum BufferType {
        ARRAY_BUFFER(GL45.GL_ARRAY_BUFFER, "VBO"),
        ELEMENT_ARRAY_BUFFER(GL45.GL_ELEMENT_ARRAY_BUFFER, "IBO"),
        UNIFORM_BUFFER(GL45.GL_UNIFORM_BUFFER, "UBO"),
        SHADER_STORAGE_BUFFER(GL45.GL_SHADER_STORAGE_BUFFER, "SSBO"),
        DRAW_INDIRECT_BUFFER(GL45.GL_DRAW_INDIRECT_BUFFER, "Indirect"),
        PIXEL_PACK_BUFFER(GL45.GL_PIXEL_PACK_BUFFER, "PBO Pack"),
        PIXEL_UNPACK_BUFFER(GL45.GL_PIXEL_UNPACK_BUFFER, "PBO Unpack"),
        TRANSFORM_FEEDBACK_BUFFER(GL45.GL_TRANSFORM_FEEDBACK_BUFFER, "Transform Feedback");
        
        public final int glTarget;
        public final String displayName;
        
        BufferType(int glTarget, String displayName) {
            this.glTarget = glTarget;
            this.displayName = displayName;
        }
    }
    
    public enum Usage {
        STATIC_DRAW(GL45.GL_STATIC_DRAW),
        DYNAMIC_DRAW(GL45.GL_DYNAMIC_DRAW),
        STREAM_DRAW(GL45.GL_STREAM_DRAW),
        STATIC_READ(GL45.GL_STATIC_READ),
        DYNAMIC_READ(GL45.GL_DYNAMIC_READ),
        STREAM_READ(GL45.GL_STREAM_READ),
        STATIC_COPY(GL45.GL_STATIC_COPY),
        DYNAMIC_COPY(GL45.GL_DYNAMIC_COPY),
        STREAM_COPY(GL45.GL_STREAM_COPY);
        
        public final int glUsage;
        
        Usage(int glUsage) {
            this.glUsage = glUsage;
        }
    }
    
    private final BufferType type;
    private final long size;
    private volatile boolean bound;
    
    /**
     * Create a new OpenGL buffer.
     * 
     * @param type Buffer type (VBO, IBO, etc.)
     * @param size Size in bytes
     * @param usage Usage hint
     * @param tracker Optional resource tracker
     * @return New buffer handle
     */
    public static GLBufferHandle create(BufferType type, long size, Usage usage, ResourceTracker tracker) {
        int buffer = GL45.glCreateBuffers();
        GL45.glNamedBufferData(buffer, size, usage.glUsage);
        
        log.trace("Created {} buffer {} with {} bytes", type.displayName, buffer, size);
        
        return new GLBufferHandle(buffer, type, size, tracker);
    }
    
    /**
     * Create a buffer with initial data.
     * 
     * @param type Buffer type
     * @param data Initial data
     * @param usage Usage hint
     * @param tracker Optional resource tracker
     * @return New buffer handle
     */
    public static GLBufferHandle create(BufferType type, ByteBuffer data, Usage usage, ResourceTracker tracker) {
        int buffer = GL45.glCreateBuffers();
        GL45.glNamedBufferData(buffer, data, usage.glUsage);
        
        long size = data.remaining();
        log.trace("Created {} buffer {} with {} bytes of data", type.displayName, buffer, size);
        
        return new GLBufferHandle(buffer, type, size, tracker);
    }
    
    /**
     * Create a buffer with float data.
     * 
     * @param type Buffer type
     * @param data Float data
     * @param usage Usage hint
     * @param tracker Optional resource tracker
     * @return New buffer handle
     */
    public static GLBufferHandle create(BufferType type, FloatBuffer data, Usage usage, ResourceTracker tracker) {
        int buffer = GL45.glCreateBuffers();
        GL45.glNamedBufferData(buffer, data, usage.glUsage);
        
        long size = data.remaining() * Float.BYTES;
        log.trace("Created {} buffer {} with {} floats ({} bytes)", type.displayName, buffer, data.remaining(), size);
        
        return new GLBufferHandle(buffer, type, size, tracker);
    }
    
    /**
     * Create a buffer with int data.
     * 
     * @param type Buffer type
     * @param data Int data
     * @param usage Usage hint
     * @param tracker Optional resource tracker
     * @return New buffer handle
     */
    public static GLBufferHandle create(BufferType type, IntBuffer data, Usage usage, ResourceTracker tracker) {
        int buffer = GL45.glCreateBuffers();
        GL45.glNamedBufferData(buffer, data, usage.glUsage);
        
        long size = data.remaining() * Integer.BYTES;
        log.trace("Created {} buffer {} with {} ints ({} bytes)", type.displayName, buffer, data.remaining(), size);
        
        return new GLBufferHandle(buffer, type, size, tracker);
    }
    
    private GLBufferHandle(Integer buffer, BufferType type, long size, ResourceTracker tracker) {
        super(buffer, tracker);
        this.type = type;
        this.size = size;
        this.bound = false;
    }
    
    /**
     * Get the OpenGL buffer ID.
     */
    public int getBufferId() {
        return get();
    }
    
    /**
     * Get the buffer type.
     */
    public BufferType getType() {
        return type;
    }
    
    /**
     * Get the buffer size in bytes.
     */
    public long getSize() {
        return size;
    }
    
    /**
     * Bind this buffer to its target.
     */
    public void bind() {
        if (!isValid()) {
            throw new IllegalStateException("Cannot bind closed buffer");
        }
        GL45.glBindBuffer(type.glTarget, get());
        bound = true;
    }
    
    /**
     * Unbind this buffer from its target.
     */
    public void unbind() {
        if (bound) {
            GL45.glBindBuffer(type.glTarget, 0);
            bound = false;
        }
    }
    
    /**
     * Update buffer data.
     * 
     * @param offset Offset in bytes
     * @param data New data
     */
    public void update(long offset, ByteBuffer data) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot update closed buffer");
        }
        GL45.glNamedBufferSubData(get(), offset, data);
    }
    
    /**
     * Update buffer with float data.
     * 
     * @param offset Offset in bytes
     * @param data New float data
     */
    public void update(long offset, FloatBuffer data) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot update closed buffer");
        }
        GL45.glNamedBufferSubData(get(), offset, data);
    }
    
    /**
     * Update buffer with int data.
     * 
     * @param offset Offset in bytes
     * @param data New int data
     */
    public void update(long offset, IntBuffer data) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot update closed buffer");
        }
        GL45.glNamedBufferSubData(get(), offset, data);
    }
    
    /**
     * Map the buffer for direct access.
     * 
     * @param access Access flags (GL_READ_ONLY, GL_WRITE_ONLY, GL_READ_WRITE)
     * @return Mapped ByteBuffer
     */
    public ByteBuffer map(int access) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot map closed buffer");
        }
        return GL45.glMapNamedBuffer(get(), access);
    }
    
    /**
     * Unmap the buffer after direct access.
     * 
     * @return true if successful
     */
    public boolean unmap() {
        if (!isValid()) {
            throw new IllegalStateException("Cannot unmap closed buffer");
        }
        return GL45.glUnmapNamedBuffer(get());
    }
    
    /**
     * Bind to an indexed binding point (for UBO/SSBO).
     * 
     * @param index Binding index
     */
    public void bindBase(int index) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot bind closed buffer");
        }
        if (type != BufferType.UNIFORM_BUFFER && type != BufferType.SHADER_STORAGE_BUFFER) {
            throw new IllegalStateException("bindBase only valid for UBO/SSBO");
        }
        GL45.glBindBufferBase(type.glTarget, index, get());
    }
    
    /**
     * Bind a range to an indexed binding point.
     * 
     * @param index Binding index
     * @param offset Offset in bytes
     * @param size Size in bytes
     */
    public void bindRange(int index, long offset, long size) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot bind closed buffer");
        }
        if (type != BufferType.UNIFORM_BUFFER && type != BufferType.SHADER_STORAGE_BUFFER) {
            throw new IllegalStateException("bindRange only valid for UBO/SSBO");
        }
        GL45.glBindBufferRange(type.glTarget, index, get(), offset, size);
    }
    
    @Override
    protected void doCleanup(Integer buffer) {
        unbind();
        GL45.glDeleteBuffers(buffer);
        log.trace("Deleted {} buffer {} ({} bytes)", type.displayName, buffer, size);
    }
    
    @Override
    public String toString() {
        return String.format("GLBufferHandle[id=%d, type=%s, size=%d, state=%s]",
            get(), type.displayName, size, getState());
    }
}