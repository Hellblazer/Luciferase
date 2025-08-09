package com.hellblazer.luciferase.webgpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Helper class for handling WebGPU callbacks with FFM.
 * WebGPU uses callbacks extensively for async operations.
 */
public class CallbackHelper {
    private static final Logger log = LoggerFactory.getLogger(CallbackHelper.class);
    
    /**
     * Buffer mapping callback implementation.
     */
    public static class BufferMapCallback {
        private final Arena arena;
        private final MemorySegment callbackStub;
        private volatile int status = -1; // -1 = pending, 0 = success, >0 = error
        private final CountDownLatch latch = new CountDownLatch(1);
        
        
        public BufferMapCallback(Arena arena) {
            this.arena = arena;
            
            // Use the new CallbackBridge approach to work around Java 24 FFM sealed interface constraints
            this.callbackStub = CallbackBridge.createBufferMapCallback(arena, this::handleCallback);
        }
        
        private void handleCallback(int status, MemorySegment userdata) {
            log.debug("Buffer mapping callback invoked - status: {}", status);
            this.status = status;
            latch.countDown();
        }
        
        public MemorySegment getCallbackStub() {
            return callbackStub;
        }
        
        public int waitForResult(long timeout, TimeUnit unit) throws InterruptedException {
            log.debug("BufferMapCallback waiting for latch with timeout {} {}", timeout, unit);
            if (latch.await(timeout, unit)) {
                log.debug("BufferMapCallback latch completed, returning status: {}", status);
                return status;
            } else {
                log.warn("Buffer mapping callback timed out after {} {}", timeout, unit);
                return -2; // Timeout
            }
        }
    }
    
    /**
     * Create a callback for adapter request.
     * Signature: void (*WGPURequestAdapterCallback)(WGPURequestAdapterStatus status, 
     *                                                WGPUAdapter adapter, 
     *                                                char const * message, 
     *                                                void * userdata)
     */
    public static class AdapterCallback {
        private final AtomicReference<MemorySegment> result = new AtomicReference<>();
        private final CountDownLatch latch = new CountDownLatch(1);
        private final Arena arena;
        private final MemorySegment callbackStub;
        
        public AdapterCallback(Arena arena) {
            this.arena = arena;
            
            // Use the new CallbackBridge approach to work around Java 24 FFM sealed interface constraints
            this.callbackStub = CallbackBridge.createAdapterCallback(arena, this::invoke);
            
            log.debug("Created adapter callback stub: {}", callbackStub);
        }
        
        public void invoke(int status, MemorySegment adapter, MemorySegment message, MemorySegment userdata) {
            log.debug("Adapter callback invoked - status: {}, adapter: 0x{}", 
                     status, adapter != null ? Long.toHexString(adapter.address()) : "null");
            
            if (status == 0 && adapter != null && !adapter.equals(MemorySegment.NULL)) { // Success
                result.set(adapter);
            } else {
                String errorMsg = message != null && !message.equals(MemorySegment.NULL) 
                    ? message.getString(0) : "Unknown error";
                log.error("Failed to get adapter - status: {}, message: {}", status, errorMsg);
            }
            
            latch.countDown();
        }
        
        public MemorySegment getCallbackStub() {
            return callbackStub;
        }
        
        public MemorySegment waitForResult(long timeout, TimeUnit unit) {
            try {
                if (latch.await(timeout, unit)) {
                    return result.get();
                } else {
                    log.warn("Timeout waiting for adapter callback");
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for adapter", e);
                return null;
            }
        }
    }
    
    /**
     * Create a callback for device request.
     * Signature: void (*WGPURequestDeviceCallback)(WGPURequestDeviceStatus status,
     *                                               WGPUDevice device,
     *                                               char const * message,
     *                                               void * userdata)
     */
    public static class DeviceCallback {
        private final AtomicReference<MemorySegment> result = new AtomicReference<>();
        private final CountDownLatch latch = new CountDownLatch(1);
        private final Arena arena;
        private final MemorySegment callbackStub;
        
        public DeviceCallback(Arena arena) {
            this.arena = arena;
            
            // Use the new CallbackBridge approach to work around Java 24 FFM sealed interface constraints
            this.callbackStub = CallbackBridge.createDeviceCallback(arena, this::invoke);
        }
        
        public void invoke(int status, MemorySegment device, MemorySegment message, MemorySegment userdata) {
            log.debug("Device callback invoked - status: {}, device: 0x{}",
                     status, device != null ? Long.toHexString(device.address()) : "null");
            
            if (status == 0 && device != null && !device.equals(MemorySegment.NULL)) { // Success
                result.set(device);
            } else {
                String errorMsg = message != null && !message.equals(MemorySegment.NULL)
                    ? message.getString(0) : "Unknown error";
                log.error("Failed to get device - status: {}, message: {}", status, errorMsg);
            }
            
            latch.countDown();
        }
        
        public MemorySegment getCallbackStub() {
            return callbackStub;
        }
        
        public MemorySegment waitForResult(long timeout, TimeUnit unit) {
            try {
                if (latch.await(timeout, unit)) {
                    return result.get();
                } else {
                    log.warn("Timeout waiting for device callback");
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for device", e);
                return null;
            }
        }
    }
}