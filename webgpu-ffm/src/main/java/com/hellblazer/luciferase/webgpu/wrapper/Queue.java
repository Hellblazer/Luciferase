package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe wrapper for WebGPU Queue.
 * Used for submitting commands to the GPU.
 */
public class Queue implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Queue.class);
    
    private final MemorySegment handle;
    private final Device device;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    /**
     * Create a queue wrapper.
     */
    protected Queue(MemorySegment handle, Device device) {
        this.handle = handle;
        this.device = device;
    }
    
    /**
     * Write data to a buffer.
     * 
     * @param buffer the target buffer
     * @param bufferOffset offset in the buffer
     * @param data the data to write
     */
    public void writeBuffer(Buffer buffer, long bufferOffset, byte[] data) {
        if (closed.get()) {
            throw new IllegalStateException("Queue is closed");
        }
        
        // TODO: Implement actual buffer writing with wgpuQueueWriteBuffer
        log.debug("Writing {} bytes to buffer {} at offset {}", 
            data.length, buffer.getId(), bufferOffset);
    }
    
    /**
     * Write data to a buffer from a ByteBuffer.
     * 
     * @param buffer the target buffer
     * @param bufferOffset offset in the buffer
     * @param data the data to write
     */
    public void writeBuffer(Buffer buffer, long bufferOffset, ByteBuffer data) {
        if (closed.get()) {
            throw new IllegalStateException("Queue is closed");
        }
        
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        writeBuffer(buffer, bufferOffset, bytes);
    }
    
    /**
     * Submit command buffers to the queue.
     * 
     * @param commandBuffers the command buffers to submit
     */
    public void submit(CommandBuffer... commandBuffers) {
        if (closed.get()) {
            throw new IllegalStateException("Queue is closed");
        }
        
        // TODO: Implement actual command submission
        log.debug("Submitting {} command buffers", commandBuffers.length);
    }
    
    /**
     * Signal that the queue has finished processing.
     */
    public void onSubmittedWorkDone() {
        if (closed.get()) {
            throw new IllegalStateException("Queue is closed");
        }
        
        // TODO: Implement work done callback
        log.debug("Waiting for submitted work to complete");
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            com.hellblazer.luciferase.webgpu.WebGPU.releaseQueue(handle);
            log.debug("Released queue");
        }
    }
}