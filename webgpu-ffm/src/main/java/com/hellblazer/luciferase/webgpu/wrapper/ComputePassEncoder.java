package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encoder for compute pass commands.
 * Used to record compute operations within a command encoder.
 */
public class ComputePassEncoder implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ComputePassEncoder.class);
    
    private final CommandEncoder commandEncoder;
    private final MemorySegment handle;
    private final AtomicBoolean ended = new AtomicBoolean(false);
    
    /**
     * Create a new compute pass encoder.
     * 
     * @param commandEncoder the parent command encoder
     * @param handle the native handle
     */
    ComputePassEncoder(CommandEncoder commandEncoder, MemorySegment handle) {
        this.commandEncoder = commandEncoder;
        this.handle = handle;
        log.debug("Created compute pass encoder");
    }
    
    /**
     * Set the compute pipeline to use.
     * 
     * @param pipeline the compute pipeline
     */
    public void setPipeline(ComputePipeline pipeline) {
        if (ended.get()) {
            throw new IllegalStateException("Compute pass already ended");
        }
        
        // Only call native method if we have valid handles (not NULL stubs)
        boolean encoderValid = !handle.equals(MemorySegment.NULL);
        boolean pipelineValid = pipeline != null && 
                               pipeline.getHandle() != null && 
                               !pipeline.getHandle().equals(MemorySegment.NULL);
        
        if (encoderValid && pipelineValid) {
            // Set the native pipeline
            com.hellblazer.luciferase.webgpu.WebGPU.computePassEncoderSetPipeline(handle, pipeline.getHandle());
            log.debug("Called native setPipeline");
        } else {
            log.debug("Skipped native setPipeline - encoder valid: {}, pipeline valid: {}", 
                     encoderValid, pipelineValid);
        }
    }
    
    /**
     * Set a bind group at the specified index.
     * 
     * @param index the bind group index
     * @param bindGroup the bind group
     * @param dynamicOffsets optional dynamic offsets
     */
    public void setBindGroup(int index, BindGroup bindGroup, int... dynamicOffsets) {
        if (ended.get()) {
            throw new IllegalStateException("Compute pass already ended");
        }
        
        // TODO: Implement native setBindGroup
        log.debug("Setting bind group {} at index {}", bindGroup, index);
    }
    
    /**
     * Dispatch a compute workgroup.
     * 
     * @param workgroupCountX number of workgroups in X dimension
     * @param workgroupCountY number of workgroups in Y dimension
     * @param workgroupCountZ number of workgroups in Z dimension
     */
    public void dispatchWorkgroups(int workgroupCountX, int workgroupCountY, int workgroupCountZ) {
        if (ended.get()) {
            throw new IllegalStateException("Compute pass already ended");
        }
        
        // Dispatch native workgroups
        com.hellblazer.luciferase.webgpu.WebGPU.dispatchWorkgroups(
            handle, workgroupCountX, workgroupCountY, workgroupCountZ);
        
        log.debug("Dispatched native workgroups: {}x{}x{}", 
                 workgroupCountX, workgroupCountY, workgroupCountZ);
    }
    
    /**
     * Set a bind group for this compute pass.
     * 
     * @param index the bind group index
     * @param bindGroup the bind group to set
     */
    public void setBindGroup(int index, BindGroup bindGroup) {
        if (ended.get()) {
            throw new IllegalStateException("Compute pass already ended");
        }
        
        if (bindGroup == null) {
            throw new IllegalArgumentException("Bind group cannot be null");
        }
        
        // Call native method to set bind group
        com.hellblazer.luciferase.webgpu.WebGPU.computePassEncoderSetBindGroup(
            handle, 
            index, 
            bindGroup.getHandle()
        );
        
        log.debug("Set bind group at index {}", index);
    }
    
    /**
     * Dispatch a compute workgroup using indirect parameters.
     * 
     * @param indirectBuffer buffer containing dispatch parameters
     * @param indirectOffset offset in the buffer
     */
    public void dispatchWorkgroupsIndirect(Buffer indirectBuffer, long indirectOffset) {
        if (ended.get()) {
            throw new IllegalStateException("Compute pass already ended");
        }
        
        // TODO: Implement native indirect dispatch
        log.debug("Dispatching workgroups indirect from buffer at offset {}", indirectOffset);
    }
    
    /**
     * Insert a debug marker.
     * 
     * @param label the marker label
     */
    public void insertDebugMarker(String label) {
        if (ended.get()) {
            throw new IllegalStateException("Compute pass already ended");
        }
        
        // TODO: Implement native debug marker
        log.debug("Debug marker: {}", label);
    }
    
    /**
     * Push a debug group.
     * 
     * @param label the group label
     */
    public void pushDebugGroup(String label) {
        if (ended.get()) {
            throw new IllegalStateException("Compute pass already ended");
        }
        
        // TODO: Implement native debug group push
        log.debug("Push debug group: {}", label);
    }
    
    /**
     * Pop a debug group.
     */
    public void popDebugGroup() {
        if (ended.get()) {
            throw new IllegalStateException("Compute pass already ended");
        }
        
        // TODO: Implement native debug group pop
        log.debug("Pop debug group");
    }
    
    /**
     * End the compute pass.
     * Must be called before the command encoder can be finished.
     */
    public void end() {
        if (ended.compareAndSet(false, true)) {
            // End native compute pass
            com.hellblazer.luciferase.webgpu.WebGPU.endComputePass(handle);
            log.debug("Ended native compute pass");
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