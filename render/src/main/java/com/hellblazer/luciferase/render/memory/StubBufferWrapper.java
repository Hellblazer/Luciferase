package com.hellblazer.luciferase.render.memory;

import com.hellblazer.luciferase.render.voxel.gpu.WebGPUStubs.Buffer;
import com.hellblazer.luciferase.render.voxel.gpu.WebGPUStubs.BufferMapCallback;
import com.hellblazer.luciferase.render.webgpu.BufferHandle;

/**
 * Wrapper to adapt BufferHandle to legacy Buffer interface.
 * This allows GPUMemoryManager to work with the new WebGPU abstraction layer.
 */
public class StubBufferWrapper extends Buffer {
    private final BufferHandle handle;
    private final long bufferSize;
    
    public StubBufferWrapper(BufferHandle handle, long size) {
        this.handle = handle;
        this.bufferSize = size;
    }
    
    @Override
    public void release() {
        handle.release();
    }
    
    @Override
    public long getSize() {
        return bufferSize;
    }
    
    @Override
    public void mapAsync(int mode, long offset, long size, BufferMapCallback callback) {
        // Not implemented in abstraction layer
        throw new UnsupportedOperationException("mapAsync not supported in abstraction layer");
    }
    
    @Override
    public java.nio.ByteBuffer getMappedRange(long offset, long size) {
        // Not implemented in abstraction layer
        throw new UnsupportedOperationException("getMappedRange not supported in abstraction layer");
    }
    
    @Override
    public void unmap() {
        // Not implemented in abstraction layer
        throw new UnsupportedOperationException("unmap not supported in abstraction layer");
    }
    
    public BufferHandle getHandle() {
        return handle;
    }
}