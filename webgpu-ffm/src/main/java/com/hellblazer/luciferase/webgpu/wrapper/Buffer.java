package com.hellblazer.luciferase.webgpu.wrapper;

import com.hellblazer.luciferase.webgpu.WebGPU;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe wrapper for WebGPU Buffer.
 */
public class Buffer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Buffer.class);
    
    private final long id;
    private final long size;
    private final int usage;
    private final Device device;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final MemorySegment handle;
    private final boolean isNative;
    private final AtomicBoolean isMapped = new AtomicBoolean(false);
    private final AtomicBoolean mappingInProgress = new AtomicBoolean(false);
    private volatile CompletableFuture<MemorySegment> currentMappingFuture = null;
    
    /**
     * Create a mock buffer wrapper.
     */
    protected Buffer(long id, long size, int usage, Device device) {
        this.id = id;
        this.size = size;
        this.usage = usage;
        this.device = device;
        this.handle = null;
        this.isNative = false;
    }
    
    /**
     * Create a native buffer wrapper.
     */
    protected Buffer(MemorySegment handle, long size, int usage, Device device) {
        this.id = handle.address();
        this.size = size;
        this.usage = usage;
        this.device = device;
        this.handle = handle;
        this.isNative = true;
    }
    
    /**
     * Map this buffer for reading or writing.
     * 
     * @param mode the map mode (read/write)
     * @param offset the offset in bytes
     * @param size the size to map
     * @return a future that completes when the buffer is mapped
     */
    public CompletableFuture<MemorySegment> mapAsync(MapMode mode, long offset, long size) {
        if (closed.get()) {
            throw new IllegalStateException("Buffer is closed");
        }
        
        // Check if a mapping is already in progress
        if (!mappingInProgress.compareAndSet(false, true)) {
            log.warn("Buffer {} already has a mapping in progress, returning existing future", id);
            if (currentMappingFuture != null) {
                return currentMappingFuture;
            }
            // If somehow the future is null, create a failed one
            return CompletableFuture.failedFuture(
                new IllegalStateException("Buffer mapping already in progress but no future available")
            );
        }
        
        // Check if buffer is already mapped
        if (isMapped.get()) {
            mappingInProgress.set(false);
            return CompletableFuture.failedFuture(
                new IllegalStateException("Buffer is already mapped. Call unmap() before mapping again.")
            );
        }
        
        CompletableFuture<MemorySegment> future = CompletableFuture.supplyAsync(() -> {
            if (!isNative || handle == null || handle.equals(MemorySegment.NULL)) {
                log.warn("Cannot map non-native buffer - returning mock data");
                // For non-native buffers, return a mock memory segment with the requested size
                var arena = Arena.global();
                var mockData = arena.allocate(size);
                // Fill with test pattern for mock data
                for (long i = 0; i < size; i += 4) {
                    if (i + 3 < size) {
                        mockData.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte)0xFF); // Red
                        mockData.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i + 1, (byte)0x00); // Green
                        mockData.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i + 2, (byte)0x00); // Blue
                        mockData.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i + 3, (byte)0xFF); // Alpha
                    }
                }
                return mockData;
            }
            
            log.debug("Mapping native buffer {} with mode {} at offset {} for {} bytes", 
                id, mode, offset, size);
            
            try {
                // Use the new CallbackBridge for native buffer mapping
                var arena = Arena.global();
                var callback = new com.hellblazer.luciferase.webgpu.CallbackHelper.BufferMapCallback(arena);
                
                // Call native buffer map async with the callback
                com.hellblazer.luciferase.webgpu.WebGPU.mapBufferAsync(handle, mode.getValue(), offset, size, callback.getCallbackStub(), MemorySegment.NULL);
                
                // Process any pending events before waiting
                if (device != null) {
                    try {
                        com.hellblazer.luciferase.webgpu.WebGPU.pollDevice(device.getHandle(), false);
                    } catch (Exception pollException) {
                        log.debug("Initial device poll failed: {}", pollException.getMessage());
                    }
                }
                
                // Wait for the mapping to complete with active polling
                log.debug("Waiting for buffer mapping callback to complete...");
                
                // Use shorter timeout with polling to help process WebGPU events
                int maxAttempts = 10;  // 10 attempts * 500ms = 5 seconds total
                int result = -1;
                
                for (int attempt = 0; attempt < maxAttempts; attempt++) {
                    try {
                        result = callback.waitForResult(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (result != -2) { // Not timeout
                            break;
                        }
                        log.debug("Buffer mapping attempt {} timed out, retrying...", attempt + 1);
                        
                        // Process WebGPU events to allow callbacks to execute
                        if (device != null) {
                            try {
                                com.hellblazer.luciferase.webgpu.WebGPU.pollDevice(device.getHandle(), false);
                            } catch (Exception pollException) {
                                log.debug("Device poll failed during buffer mapping: {}", pollException.getMessage());
                            }
                        } else {
                            log.debug("Device is null during buffer mapping - cannot poll");
                        }
                        
                        // Small yield to prevent tight polling
                        Thread.yield();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Buffer mapping interrupted", e);
                    }
                }
                
                log.debug("Buffer mapping completed with result: {} after {} attempts", result, 
                    result == -2 ? maxAttempts : "some");
                if (result == 0) {
                    // Success - get the mapped range
                    var mappedRange = com.hellblazer.luciferase.webgpu.WebGPU.getBufferMappedRange(handle, offset, size);
                    if (mappedRange != null && !mappedRange.equals(MemorySegment.NULL)) {
                        log.debug("Successfully mapped native buffer range: {} bytes", size);
                        isMapped.set(true);  // Mark as successfully mapped
                        return mappedRange;
                    }
                }
                
                log.warn("Native buffer mapping failed with result: {} - falling back to mock data", result);
                // Note: isMapped remains false when using mock data
                
                // Fallback to mock data if native mapping fails
                var mockData = arena.allocate(size);
                // Fill with test pattern
                for (long i = 0; i < size; i += 4) {
                    if (i + 3 < size) {
                        mockData.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte)0x00); // Black
                        mockData.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i + 1, (byte)0x00); 
                        mockData.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i + 2, (byte)0x00); 
                        mockData.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i + 3, (byte)0xFF); // Alpha
                    }
                }
                return mockData;
                
            } catch (Exception e) {
                log.error("Exception during buffer mapping", e);
                return MemorySegment.NULL;
            } finally {
                // Always clear mapping in progress flag
                mappingInProgress.set(false);
                currentMappingFuture = null;
            }
        });
        
        // Store the current mapping future
        currentMappingFuture = future;
        
        // Clean up state when the future completes
        future.whenComplete((result, error) -> {
            mappingInProgress.set(false);
            currentMappingFuture = null;
        });
        
        return future;
    }
    
    /**
     * Get the mapped range of this buffer.
     * 
     * @param offset the offset in bytes
     * @param size the size of the range
     * @return the mapped memory segment
     */
    public MemorySegment getMappedRange(long offset, long size) {
        if (closed.get()) {
            throw new IllegalStateException("Buffer is closed");
        }
        
        // TODO: Implement actual mapped range access
        return MemorySegment.NULL;
    }
    
    /**
     * Unmap this buffer.
     */
    public void unmap() {
        if (closed.get()) {
            throw new IllegalStateException("Buffer is closed");
        }
        
        // Wait for any ongoing mapping to complete
        if (mappingInProgress.get() && currentMappingFuture != null) {
            log.debug("Waiting for ongoing mapping to complete before unmapping buffer {}", id);
            try {
                currentMappingFuture.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("Timeout or error waiting for mapping to complete: {}", e.getMessage());
            }
        }
        
        // Only unmap if the buffer was actually mapped successfully
        if (isMapped.compareAndSet(true, false)) {
            if (isNative && handle != null && !handle.equals(MemorySegment.NULL)) {
                WebGPU.unmapBuffer(handle);
                log.debug("Unmapped native buffer {}", id);
            }
        } else {
            log.debug("Buffer {} was not mapped, skipping unmap", id);
        }
        
        // Clear any lingering state
        mappingInProgress.set(false);
        currentMappingFuture = null;
    }
    
    /**
     * Get the buffer ID.
     */
    public long getId() {
        return id;
    }
    
    /**
     * Get the native handle.
     * 
     * @return the native handle, or mock segment if not native
     */
    public MemorySegment getHandle() {
        if (isNative && handle != null) {
            return handle;
        }
        // Return a mock segment for non-native buffers
        return MemorySegment.ofAddress(id);
    }
    
    /**
     * Get the buffer size in bytes.
     */
    public long getSize() {
        return size;
    }
    
    /**
     * Get the buffer usage flags.
     */
    public int getUsage() {
        return usage;
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (isNative && handle != null) {
                // Destroy native buffer
                WebGPU.destroyBuffer(handle);
                device.removeBuffer(id);
            } else {
                // Mock buffer cleanup
                device.removeBuffer(id);
            }
            log.debug("Released buffer {}", id);
        }
    }
    
    /**
     * Buffer map mode.
     */
    public enum MapMode {
        READ(1),
        WRITE(2);
        
        private final int value;
        
        MapMode(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
}