package com.hellblazer.luciferase.render.webgpu.resources;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.wrapper.CommandEncoder.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages command buffer recording and submission for WebGPU.
 * Provides efficient command recording with automatic cleanup.
 */
public class CommandBufferManager {
    private static final Logger log = LoggerFactory.getLogger(CommandBufferManager.class);
    
    private final Device device;
    private final Queue queue;
    private CommandEncoder currentEncoder;
    private final List<CommandBuffer> pendingBuffers = new ArrayList<>();
    private final List<CommandBuffer> submittedBuffers = new ArrayList<>();
    
    // Statistics
    private long totalCommandBuffers = 0;
    private long totalSubmissions = 0;
    
    public CommandBufferManager(Device device, Queue queue) {
        this.device = device;
        this.queue = queue;
    }
    
    /**
     * Begin recording commands for a new frame.
     */
    public CommandEncoder beginFrame() {
        return beginFrame(null);
    }
    
    /**
     * Begin recording commands for a new frame with a label.
     */
    public CommandEncoder beginFrame(String label) {
        if (currentEncoder != null) {
            log.warn("Previous encoder not finished before beginning new frame");
            finishEncoder();
        }
        
        // Clean up submitted buffers from previous frame
        cleanupSubmittedBuffers();
        
        String encoderLabel = label != null ? label : "Frame_" + totalCommandBuffers;
        currentEncoder = device.createCommandEncoder(encoderLabel);
        return currentEncoder;
    }
    
    /**
     * Begin a render pass.
     */
    public RenderPassEncoder beginRenderPass(RenderPassDescriptor descriptor) {
        if (currentEncoder == null) {
            throw new IllegalStateException("No active command encoder. Call beginFrame() first.");
        }
        
        return currentEncoder.beginRenderPass(descriptor);
    }
    
    /**
     * Begin a compute pass.
     */
    public ComputePassEncoder beginComputePass(ComputePassDescriptor descriptor) {
        if (currentEncoder == null) {
            throw new IllegalStateException("No active command encoder. Call beginFrame() first.");
        }
        
        return currentEncoder.beginComputePass(descriptor);
    }
    
    /**
     * Copy buffer to buffer.
     */
    public void copyBufferToBuffer(Buffer source, long sourceOffset, 
                                  Buffer destination, long destinationOffset, 
                                  long size) {
        if (currentEncoder == null) {
            throw new IllegalStateException("No active command encoder");
        }
        
        currentEncoder.copyBufferToBuffer(source, sourceOffset, 
                                         destination, destinationOffset, size);
    }
    
    /**
     * Copy buffer to texture.
     */
    public void copyBufferToTexture(Buffer source, BufferCopyView sourceLayout,
                                   Texture destination, Origin3D destinationOrigin, 
                                   Extent3D copySize) {
        if (currentEncoder == null) {
            throw new IllegalStateException("No active command encoder");
        }
        
        currentEncoder.copyBufferToTexture(source, sourceLayout, destination, destinationOrigin, copySize);
    }
    
    /**
     * Finish the current encoder and add to pending buffers.
     */
    public CommandBuffer finishEncoder() {
        if (currentEncoder == null) {
            return null;
        }
        
        totalCommandBuffers++;
        CommandBuffer buffer = currentEncoder.finish();
        pendingBuffers.add(buffer);
        
        currentEncoder = null;
        return buffer;
    }
    
    /**
     * Submit all pending command buffers to the queue.
     */
    public void submit() {
        // Finish current encoder if active
        if (currentEncoder != null) {
            finishEncoder();
        }
        
        if (pendingBuffers.isEmpty()) {
            log.debug("No command buffers to submit");
            return;
        }
        
        // Submit all pending buffers
        CommandBuffer[] buffers = pendingBuffers.toArray(new CommandBuffer[0]);
        queue.submit(buffers);
        
        log.debug("Submitted {} command buffers", buffers.length);
        totalSubmissions++;
        
        // Move to submitted list for cleanup next frame
        submittedBuffers.addAll(pendingBuffers);
        pendingBuffers.clear();
    }
    
    /**
     * Submit a single command buffer immediately.
     */
    public void submitImmediate(CommandBuffer buffer) {
        queue.submit(new CommandBuffer[] { buffer });
        submittedBuffers.add(buffer);
        totalSubmissions++;
    }
    
    /**
     * Create and submit a simple copy operation.
     */
    public void submitCopy(Buffer source, long sourceOffset,
                          Buffer destination, long destinationOffset,
                          long size) {
        CommandEncoder encoder = device.createCommandEncoder("CopyBuffer");
        encoder.copyBufferToBuffer(source, sourceOffset, destination, destinationOffset, size);
        
        CommandBuffer buffer = encoder.finish();
        submitImmediate(buffer);
    }
    
    /**
     * Write timestamp for profiling (if timestamp queries are enabled).
     * TODO: Implement when QuerySet and timestamp queries are available
     */
    public void writeTimestamp(/*QuerySet querySet, int queryIndex*/) {
        // if (currentEncoder != null) {
        //     currentEncoder.writeTimestamp(querySet, queryIndex);
        // }
        log.debug("Timestamp queries not yet implemented");
    }
    
    /**
     * Insert debug marker.
     */
    public void insertDebugMarker(String markerLabel) {
        if (currentEncoder != null) {
            currentEncoder.insertDebugMarker(markerLabel);
        }
    }
    
    /**
     * Push debug group.
     */
    public void pushDebugGroup(String groupLabel) {
        if (currentEncoder != null) {
            currentEncoder.pushDebugGroup(groupLabel);
        }
    }
    
    /**
     * Pop debug group.
     */
    public void popDebugGroup() {
        if (currentEncoder != null) {
            currentEncoder.popDebugGroup();
        }
    }
    
    /**
     * Clean up submitted buffers from previous frames.
     */
    private void cleanupSubmittedBuffers() {
        for (CommandBuffer buffer : submittedBuffers) {
            // Command buffers are automatically released after submission
            // but we can explicitly release if needed
            // buffer.release();
        }
        submittedBuffers.clear();
    }
    
    /**
     * Clean up all resources.
     */
    public void cleanup() {
        if (currentEncoder != null) {
            log.warn("Cleaning up with active encoder");
            currentEncoder = null;
        }
        
        pendingBuffers.clear();
        submittedBuffers.clear();
        
        log.info("CommandBufferManager cleaned up (total buffers: {}, submissions: {})",
            totalCommandBuffers, totalSubmissions);
    }
    
    /**
     * Get statistics.
     */
    public String getStatistics() {
        return String.format(
            "CommandBufferManager[buffers=%d, submissions=%d, pending=%d]",
            totalCommandBuffers, totalSubmissions, pendingBuffers.size()
        );
    }
}