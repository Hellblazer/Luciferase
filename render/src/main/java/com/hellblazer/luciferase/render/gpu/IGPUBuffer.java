package com.hellblazer.luciferase.render.gpu;

import java.nio.ByteBuffer;

/**
 * Abstract GPU buffer interface for both OpenGL SSBO and BGFX compute buffers.
 * Provides unified buffer operations across different GPU backends.
 */
public interface IGPUBuffer {
    
    /**
     * Upload data to the GPU buffer.
     * @param data Source data to upload
     * @param offset Offset in bytes within the buffer
     */
    void upload(ByteBuffer data, int offset);
    
    /**
     * Upload data to the GPU buffer starting at offset 0.
     * @param data Source data to upload
     */
    default void upload(ByteBuffer data) {
        upload(data, 0);
    }
    
    /**
     * Download data from the GPU buffer to CPU memory.
     * @param offset Offset in bytes within the buffer
     * @param size Number of bytes to download
     * @return Downloaded data
     */
    ByteBuffer download(int offset, int size);
    
    /**
     * Download entire buffer contents to CPU memory.
     * @return Downloaded data
     */
    default ByteBuffer download() {
        return download(0, getSize());
    }
    
    /**
     * Bind the buffer to a specific slot for shader access.
     * @param slot Binding slot (corresponds to GLSL binding point)
     * @param access Access pattern (read-only, write-only, read-write)
     */
    void bind(int slot, AccessType access);
    
    /**
     * Unbind the buffer from its current slot.
     */
    void unbind();
    
    /**
     * Map the buffer for direct CPU access (if supported).
     * @param access Access pattern for mapping
     * @return Mapped memory buffer, or null if mapping not supported
     */
    ByteBuffer map(AccessType access);
    
    /**
     * Unmap a previously mapped buffer.
     */
    void unmap();
    
    /**
     * Get the size of the buffer in bytes.
     * @return Buffer size
     */
    int getSize();
    
    /**
     * Get the buffer type.
     * @return Buffer type
     */
    BufferType getType();
    
    /**
     * Get the buffer usage pattern.
     * @return Buffer usage
     */
    BufferUsage getUsage();
    
    /**
     * Check if the buffer is currently mapped.
     * @return true if buffer is mapped
     */
    boolean isMapped();
    
    /**
     * Check if the buffer is valid and can be used.
     * @return true if buffer is valid
     */
    boolean isValid();
    
    /**
     * Get the backend-specific buffer handle.
     * For debugging and advanced use cases.
     * @return Native buffer handle (implementation-specific)
     */
    Object getNativeHandle();
    
    /**
     * Destroy the buffer and release GPU memory.
     * Buffer becomes invalid after this call.
     */
    void destroy();
}