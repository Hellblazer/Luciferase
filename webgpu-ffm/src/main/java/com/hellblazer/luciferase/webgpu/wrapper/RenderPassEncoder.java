package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encoder for render pass commands.
 * Used to record rendering operations within a command encoder.
 */
public class RenderPassEncoder implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RenderPassEncoder.class);
    
    private final CommandEncoder commandEncoder;
    private final MemorySegment handle;
    private final AtomicBoolean ended = new AtomicBoolean(false);
    
    /**
     * Create a new render pass encoder.
     * 
     * @param commandEncoder the parent command encoder
     * @param handle the native handle
     */
    RenderPassEncoder(CommandEncoder commandEncoder, MemorySegment handle) {
        this.commandEncoder = commandEncoder;
        this.handle = handle;
        log.debug("Created render pass encoder");
    }
    
    /**
     * End the render pass.
     * Must be called before the command encoder can be finished.
     */
    public void end() {
        if (ended.compareAndSet(false, true)) {
            // TODO: Implement native end
            log.debug("Ended render pass");
        }
    }
    
    /**
     * Get the native handle.
     * 
     * @return the native handle
     */
    public MemorySegment getHandle() {
        return handle;
    }
    
    @Override
    public void close() {
        end();
    }
}