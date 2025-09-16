package com.hellblazer.luciferase.resource.opencl;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL11;
import org.lwjgl.opencl.CL12;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * RAII handle for OpenCL buffers
 */
public class CLBufferHandle extends ResourceHandle<Long> {
    private static final Logger log = LoggerFactory.getLogger(CLBufferHandle.class);
    
    private final long context;
    private final long size;
    private final int flags;
    private final BufferType type;
    
    public enum BufferType {
        READ_ONLY(CL10.CL_MEM_READ_ONLY),
        WRITE_ONLY(CL10.CL_MEM_WRITE_ONLY),
        READ_WRITE(CL10.CL_MEM_READ_WRITE),
        HOST_READ_ONLY(CL12.CL_MEM_HOST_READ_ONLY),
        HOST_WRITE_ONLY(CL12.CL_MEM_HOST_WRITE_ONLY),
        HOST_NO_ACCESS(CL12.CL_MEM_HOST_NO_ACCESS),
        USE_HOST_PTR(CL10.CL_MEM_USE_HOST_PTR),
        ALLOC_HOST_PTR(CL10.CL_MEM_ALLOC_HOST_PTR),
        COPY_HOST_PTR(CL10.CL_MEM_COPY_HOST_PTR);
        
        private final int flag;
        
        BufferType(int flag) {
            this.flag = flag;
        }
        
        public int getFlag() {
            return flag;
        }
    }
    
    /**
     * Create a new OpenCL buffer
     */
    public static CLBufferHandle create(long context, long size, BufferType type) {
        return create(context, size, type.getFlag(), null);
    }
    
    /**
     * Create a new OpenCL buffer with combined flags
     */
    public static CLBufferHandle create(long context, long size, int flags) {
        return create(context, size, flags, null);
    }
    
    /**
     * Create a new OpenCL buffer with host data
     */
    public static CLBufferHandle createWithData(long context, ByteBuffer hostData, BufferType type) {
        int flags = type.getFlag() | CL10.CL_MEM_COPY_HOST_PTR;
        return create(context, hostData.remaining(), flags, hostData);
    }
    
    private static CLBufferHandle create(long context, long size, int flags, ByteBuffer hostData) {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);
            
            long buffer = CL10.clCreateBuffer(
                context,
                flags,
                size,
                errcode
            );
            
            checkError(errcode.get(0));
            
            if (buffer == 0) {
                throw new IllegalStateException("Failed to create OpenCL buffer");
            }
            
            var handle = new CLBufferHandle(buffer, context, size, flags);
            var tracker = ResourceTracker.getGlobalTracker();
            if (tracker != null) {
                tracker.track(handle);
            }
            log.debug("Created OpenCL buffer: {} (size: {} bytes)", buffer, size);
            
            // Copy host data if provided
            if (hostData != null && (flags & CL10.CL_MEM_COPY_HOST_PTR) != 0) {
                // Data is already copied by clCreateBuffer when CL_MEM_COPY_HOST_PTR is set
                log.debug("Initialized buffer with {} bytes of host data", hostData.remaining());
            }
            
            return handle;
        }
    }
    
    /**
     * Wrap an existing OpenCL buffer handle
     */
    public static CLBufferHandle wrap(long buffer, long context, long size, int flags) {
        if (buffer == 0) {
            throw new IllegalArgumentException("Invalid buffer handle");
        }
        
        var handle = new CLBufferHandle(buffer, context, size, flags);
        var tracker = ResourceTracker.getGlobalTracker();
        if (tracker != null) {
            tracker.track(handle);
        }
        return handle;
    }
    
    private CLBufferHandle(long buffer, long context, long size, int flags) {
        super(buffer, ResourceTracker.getGlobalTracker());
        this.context = context;
        this.size = size;
        this.flags = flags;
        
        // Determine buffer type from flags
        if ((flags & CL10.CL_MEM_READ_ONLY) != 0) {
            this.type = BufferType.READ_ONLY;
        } else if ((flags & CL10.CL_MEM_WRITE_ONLY) != 0) {
            this.type = BufferType.WRITE_ONLY;
        } else {
            this.type = BufferType.READ_WRITE;
        }
    }
    
    /**
     * Get buffer size
     */
    public long getSize() {
        return size;
    }
    
    /**
     * Get buffer flags
     */
    public int getFlags() {
        return flags;
    }
    
    /**
     * Get buffer type
     */
    public BufferType getType() {
        return type;
    }
    
    /**
     * Get context
     */
    public long getContext() {
        return context;
    }
    
    /**
     * Get actual buffer size from OpenCL
     */
    public long getActualSize() {
        Long buffer = get(); // Ensure not closed
        
        try (var stack = MemoryStack.stackPush()) {
            var sizeBuffer = stack.mallocPointer(1);
            
            int error = CL10.clGetMemObjectInfo(
                buffer.longValue(),
                CL10.CL_MEM_SIZE,
                sizeBuffer,
                null
            );
            checkError(error);
            
            return sizeBuffer.get(0);
        }
    }
    
    /**
     * Get reference count
     */
    public int getReferenceCount() {
        Long buffer = get(); // Ensure not closed
        
        try (var stack = MemoryStack.stackPush()) {
            var countBuffer = stack.mallocInt(1);
            
            int error = CL10.clGetMemObjectInfo(
                buffer.longValue(),
                CL10.CL_MEM_REFERENCE_COUNT,
                countBuffer,
                null
            );
            checkError(error);
            
            return countBuffer.get(0);
        }
    }
    
    /**
     * Check if buffer uses host pointer
     */
    public boolean usesHostPointer() {
        return (flags & CL10.CL_MEM_USE_HOST_PTR) != 0;
    }
    
    /**
     * Check if buffer is allocated on host
     */
    public boolean isHostAllocated() {
        return (flags & CL10.CL_MEM_ALLOC_HOST_PTR) != 0;
    }
    
    /**
     * Enqueue read from buffer to host memory
     */
    public void enqueueRead(long queue, boolean blocking, long offset, ByteBuffer data, 
                           PointerBuffer events, PointerBuffer event) {
        Long buffer = get(); // Ensure not closed
        
        if (offset + data.remaining() > size) {
            throw new IllegalArgumentException("Read exceeds buffer bounds");
        }
        
        int error = CL10.clEnqueueReadBuffer(
            queue,
            buffer.longValue(),
            blocking,
            offset,
            data,
            events,
            event
        );
        checkError(error);
        
        log.debug("Enqueued read of {} bytes from buffer at offset {}", data.remaining(), offset);
    }
    
    /**
     * Enqueue write from host memory to buffer
     */
    public void enqueueWrite(long queue, boolean blocking, long offset, ByteBuffer data,
                            PointerBuffer events, PointerBuffer event) {
        Long buffer = get(); // Ensure not closed
        
        if (offset + data.remaining() > size) {
            throw new IllegalArgumentException("Write exceeds buffer bounds");
        }
        
        int error = CL10.clEnqueueWriteBuffer(
            queue,
            buffer.longValue(),
            blocking,
            offset,
            data,
            events,
            event
        );
        checkError(error);
        
        log.debug("Enqueued write of {} bytes to buffer at offset {}", data.remaining(), offset);
    }
    
    /**
     * Enqueue copy from this buffer to another
     */
    public void enqueueCopyTo(long queue, CLBufferHandle dest, long srcOffset, long destOffset,
                             long size, PointerBuffer events, PointerBuffer event) {
        Long srcBuffer = get(); // Ensure not closed
        Long destBuffer = dest.get(); // Ensure dest not closed
        
        if (srcOffset + size > this.size) {
            throw new IllegalArgumentException("Copy exceeds source buffer bounds");
        }
        if (destOffset + size > dest.size) {
            throw new IllegalArgumentException("Copy exceeds destination buffer bounds");
        }
        
        int error = CL10.clEnqueueCopyBuffer(
            queue,
            srcBuffer.longValue(),
            destBuffer.longValue(),
            srcOffset,
            destOffset,
            size,
            events,
            event
        );
        checkError(error);
        
        log.debug("Enqueued copy of {} bytes from buffer to buffer", size);
    }
    
    /**
     * Map buffer for host access
     */
    public ByteBuffer enqueueMap(long queue, boolean blocking, int mapFlags, long offset, long size,
                                 PointerBuffer events, PointerBuffer event, IntBuffer errorCode) {
        Long buffer = get(); // Ensure not closed
        
        if (offset + size > this.size) {
            throw new IllegalArgumentException("Map exceeds buffer bounds");
        }
        
        ByteBuffer mapped = CL10.clEnqueueMapBuffer(
            queue,
            buffer.longValue(),
            blocking,
            mapFlags,
            offset,
            size,
            events,
            event,
            errorCode,
            null
        );
        
        if (errorCode != null && errorCode.get(0) != CL10.CL_SUCCESS) {
            checkError(errorCode.get(0));
        }
        
        log.debug("Mapped {} bytes of buffer at offset {}", size, offset);
        return mapped;
    }
    
    /**
     * Unmap previously mapped buffer
     */
    public void enqueueUnmap(long queue, ByteBuffer mappedPtr, PointerBuffer events, PointerBuffer event) {
        Long buffer = get(); // Ensure not closed
        
        int error = CL10.clEnqueueUnmapMemObject(queue, buffer.longValue(), mappedPtr, events, event);
        checkError(error);
        
        log.debug("Unmapped buffer");
    }
    
    /**
     * Fill buffer with pattern
     */
    public void enqueueFill(long queue, ByteBuffer pattern, long offset, long size,
                           PointerBuffer events, PointerBuffer event) {
        Long buffer = get(); // Ensure not closed
        
        if (offset + size > this.size) {
            throw new IllegalArgumentException("Fill exceeds buffer bounds");
        }
        
        // Use the standard clEnqueueFillBuffer from CL12
        int error = CL12.clEnqueueFillBuffer(
            queue,
            buffer.longValue(),
            pattern,
            offset,
            size,
            events,
            event
        );
        checkError(error);
        
        log.debug("Enqueued fill of {} bytes at offset {} with pattern", size, offset);
    }
    
    @Override
    protected void doCleanup(Long buffer) {
        int error = CL10.clReleaseMemObject(buffer);
        if (error != CL10.CL_SUCCESS) {
            log.error("Failed to release OpenCL buffer {}: {}", buffer, error);
        } else {
            log.debug("Released OpenCL buffer: {}", buffer);
        }
        var tracker = ResourceTracker.getGlobalTracker();
        if (tracker != null) {
            tracker.untrack(this);
        }
    }
    
    private static void checkError(int error) {
        if (error != CL10.CL_SUCCESS) {
            throw new RuntimeException("OpenCL error: " + error);
        }
    }
}