package com.hellblazer.luciferase.resource.opengl;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RAII wrapper for OpenGL buffer objects (VBO, IBO, UBO, SSBO).
 * Provides automatic resource management and type-safe operations.
 */
public class GLBufferHandle extends ResourceHandle<Integer> {
    private static final Logger log = LoggerFactory.getLogger(GLBufferHandle.class);
    private static final AtomicLong totalBufferMemory = new AtomicLong(0);
    
    private final BufferType type;
    private final long sizeBytes;
    private final int usage;
    
    public enum BufferType {
        VERTEX_BUFFER(GL15.GL_ARRAY_BUFFER, "VBO"),
        INDEX_BUFFER(GL15.GL_ELEMENT_ARRAY_BUFFER, "IBO"),
        UNIFORM_BUFFER(GL31.GL_UNIFORM_BUFFER, "UBO"),
        SHADER_STORAGE_BUFFER(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, "SSBO");
        
        private final int target;
        private final String name;
        
        BufferType(int target, String name) {
            this.target = target;
            this.name = name;
        }
        
        public int getTarget() { return target; }
        public String getName() { return name; }
    }
    
    public enum Usage {
        STATIC_DRAW(GL15.GL_STATIC_DRAW),
        DYNAMIC_DRAW(GL15.GL_DYNAMIC_DRAW),
        STREAM_DRAW(GL15.GL_STREAM_DRAW);
        
        private final int value;
        
        Usage(int value) {
            this.value = value;
        }
        
        public int getValue() { return value; }
    }
    
    /**
     * Creates a new OpenGL buffer with the specified size.
     */
    public GLBufferHandle(BufferType type, long sizeBytes, Usage usage) {
        super(GL15.glGenBuffers(), ResourceTracker.getGlobalTracker());
        this.type = type;
        this.sizeBytes = sizeBytes;
        this.usage = usage.getValue();
        
        // Allocate buffer storage
        GL15.glBindBuffer(type.getTarget(), get());
        GL15.glBufferData(type.getTarget(), sizeBytes, usage.getValue());
        GL15.glBindBuffer(type.getTarget(), 0);
        
        totalBufferMemory.addAndGet(sizeBytes);
        ResourceTracker.getGlobalTracker().track(this);
        
        log.debug("Created {} buffer {} with size {} bytes", type.getName(), get(), sizeBytes);
    }
    
    /**
     * Creates a buffer and uploads data.
     */
    public GLBufferHandle(BufferType type, ByteBuffer data, Usage usage) {
        this(type, data.remaining(), usage);
        upload(data);
    }
    
    /**
     * Creates a buffer and uploads float data.
     */
    public GLBufferHandle(BufferType type, FloatBuffer data, Usage usage) {
        this(type, data.remaining() * Float.BYTES, usage);
        uploadFloats(data);
    }
    
    /**
     * Creates a buffer and uploads int data.
     */
    public GLBufferHandle(BufferType type, IntBuffer data, Usage usage) {
        this(type, data.remaining() * Integer.BYTES, usage);
        uploadInts(data);
    }
    
    /**
     * Binds this buffer for use.
     */
    public void bind() {
        get(); // Ensure not closed
        GL15.glBindBuffer(type.getTarget(), get());
    }
    
    /**
     * Unbinds this buffer type.
     */
    public void unbind() {
        GL15.glBindBuffer(type.getTarget(), 0);
    }
    
    /**
     * Uploads data to the buffer.
     */
    public void upload(ByteBuffer data) {
        upload(data, 0);
    }
    
    /**
     * Uploads data to the buffer at the specified offset.
     */
    public void upload(ByteBuffer data, long offset) {
        get(); // Ensure not closed
        bind();
        GL15.glBufferSubData(type.getTarget(), offset, data);
        unbind();
    }
    
    /**
     * Uploads float data to the buffer.
     */
    public void uploadFloats(FloatBuffer data) {
        uploadFloats(data, 0);
    }
    
    /**
     * Uploads float data to the buffer at the specified offset.
     */
    public void uploadFloats(FloatBuffer data, long offset) {
        get(); // Ensure not closed
        bind();
        GL15.glBufferSubData(type.getTarget(), offset, data);
        unbind();
    }
    
    /**
     * Uploads int data to the buffer.
     */
    public void uploadInts(IntBuffer data) {
        uploadInts(data, 0);
    }
    
    /**
     * Uploads int data to the buffer at the specified offset.
     */
    public void uploadInts(IntBuffer data, long offset) {
        get(); // Ensure not closed
        bind();
        GL15.glBufferSubData(type.getTarget(), offset, data);
        unbind();
    }
    
    /**
     * Maps the buffer for reading.
     */
    public ByteBuffer map(MapAccess access) {
        get(); // Ensure not closed
        bind();
        var buffer = GL15.glMapBuffer(type.getTarget(), access.getValue(), sizeBytes, null);
        unbind();
        return buffer;
    }
    
    /**
     * Unmaps the buffer after mapping.
     */
    public boolean unmap() {
        get(); // Ensure not closed
        bind();
        var result = GL15.glUnmapBuffer(type.getTarget());
        unbind();
        return result;
    }
    
    /**
     * Copies data from this buffer to another.
     */
    public void copyTo(GLBufferHandle destination, long srcOffset, long dstOffset, long size) {
        get(); // Ensure not closed
        destination.get(); // Ensure destination not closed
        
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, get());
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, destination.get());
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 
                                 srcOffset, dstOffset, size);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }
    
    @Override
    protected void doCleanup(Integer buffer) {
        if (buffer != null && buffer != 0) {
            GL15.glDeleteBuffers(buffer);
            totalBufferMemory.addAndGet(-sizeBytes);
            ResourceTracker.getGlobalTracker().untrack(this);
            log.debug("Deleted {} buffer {}", type.getName(), buffer);
        }
    }
    
    // Getters
    public BufferType getType() { return type; }
    public long getSizeBytes() { return sizeBytes; }
    public int getUsage() { return usage; }
    
    /**
     * Gets the total memory used by all GL buffers.
     */
    public static long getTotalBufferMemory() {
        return totalBufferMemory.get();
    }
    
    public enum MapAccess {
        READ_ONLY(GL15.GL_READ_ONLY),
        WRITE_ONLY(GL15.GL_WRITE_ONLY),
        READ_WRITE(GL15.GL_READ_WRITE);
        
        private final int value;
        
        MapAccess(int value) {
            this.value = value;
        }
        
        public int getValue() { return value; }
    }
}