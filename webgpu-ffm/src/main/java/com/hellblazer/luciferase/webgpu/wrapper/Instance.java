package com.hellblazer.luciferase.webgpu.wrapper;

import com.hellblazer.luciferase.webgpu.CallbackBridge;
import com.hellblazer.luciferase.webgpu.WebGPU;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe wrapper for WebGPU Instance.
 * Represents a WebGPU instance, which is the entry point to the WebGPU API.
 */
public class Instance implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Instance.class);
    private static final Cleaner cleaner = Cleaner.create();
    
    private final MemorySegment handle;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Cleaner.Cleanable cleanable;
    
    /**
     * State for cleanup when instance is garbage collected.
     */
    private static class State implements Runnable {
        private final MemorySegment handle;
        private final String instanceId;
        private final AtomicBoolean explicitlyClosed;
        
        State(MemorySegment handle, AtomicBoolean explicitlyClosed) {
            this.handle = handle;
            this.instanceId = "0x" + Long.toHexString(handle != null ? handle.address() : 0);
            this.explicitlyClosed = explicitlyClosed;
        }
        
        @Override
        public void run() {
            if (handle != null && !handle.equals(MemorySegment.NULL)) {
                if (!explicitlyClosed.get()) {
                    log.warn("Instance {} not properly closed, releasing in cleanup", instanceId);
                }
                WebGPU.releaseInstance(handle);
            }
        }
    }
    
    /**
     * Create a new WebGPU instance with default configuration.
     */
    public Instance() {
        log.info("Instance constructor called");
        
        if (!WebGPU.isInitialized()) {
            log.info("WebGPU not initialized, initializing now");
            if (!WebGPU.initialize()) {
                throw new IllegalStateException("Failed to initialize WebGPU");
            }
        }
        
        log.info("About to create WebGPU instance handle");
        this.handle = WebGPU.createInstance();
        if (this.handle == null || this.handle.equals(MemorySegment.NULL)) {
            throw new RuntimeException("Failed to create WebGPU instance");
        }
        
        // Register cleanup for garbage collection
        this.cleanable = cleaner.register(this, new State(this.handle, this.closed));
        
        log.info("Created WebGPU instance wrapper: 0x{}", Long.toHexString(handle.address()));
    }
    
    /**
     * Create an instance wrapper from an existing handle.
     * 
     * @param handle the native instance handle
     */
    public Instance(MemorySegment handle) {
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Invalid instance handle");
        }
        this.handle = handle;
        // Register cleanup for garbage collection
        this.cleanable = cleaner.register(this, new State(this.handle, this.closed));
    }
    
    /**
     * Request an adapter asynchronously.
     * 
     * @param options the adapter request options
     * @return a future that completes with the adapter
     */
    public CompletableFuture<Adapter> requestAdapter(AdapterOptions options) {
        if (closed.get()) {
            throw new IllegalStateException("Instance is closed");
        }
        
        log.debug("Requesting adapter with options: {}", options);
        
        // Dawn uses callback-based API, not enumerate
        // Use CallbackBridge to handle the adapter request with Dawn's callback mechanism
        // Pass 'this' so adapter and device can reference the instance for event processing
        return CallbackBridge.requestAdapter(handle, options, this);
    }
    
    /**
     * Request an adapter with default options.
     * 
     * @return a future that completes with the adapter
     */
    public CompletableFuture<Adapter> requestAdapter() {
        return requestAdapter(new AdapterOptions());
    }
    
    /**
     * Get the native handle for this instance.
     * 
     * @return the native memory segment
     */
    public MemorySegment getHandle() {
        return handle;
    }
    
    /**
     * Check if this instance is valid and not closed.
     * 
     * @return true if the instance is valid
     */
    public boolean isValid() {
        return !closed.get() && handle != null && !handle.equals(MemorySegment.NULL);
    }
    
    /**
     * Process pending events for this instance.
     * This should be called regularly to process buffer mapping callbacks and other async operations.
     */
    public void processEvents() {
        if (!isValid()) {
            log.debug("Cannot process events - instance is not valid");
            return;
        }
        WebGPU.instanceProcessEvents(handle);
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Clean up the cleaner registration to prevent unnecessary cleanup
            if (cleanable != null) {
                cleanable.clean();
            }
            // Note: cleanable.clean() already called WebGPU.releaseInstance(handle)
            // Don't call it again to avoid double-release
            log.debug("Released WebGPU instance");
        }
    }
    
    /**
     * Create a surface from a platform-specific descriptor.
     * 
     * @param descriptor platform-specific surface descriptor
     * @return new Surface instance
     */
    public Surface createSurface(MemorySegment descriptor) {
        if (closed.get()) {
            throw new IllegalStateException("Instance is closed");
        }
        
        // Call wgpuInstanceCreateSurface
        var surfaceHandle = WebGPU.createSurface(handle, descriptor);
        if (surfaceHandle == MemorySegment.NULL) {
            throw new RuntimeException("Failed to create surface");
        }
        
        return new Surface(surfaceHandle);
    }
    
    /**
     * Options for requesting an adapter.
     */
    public static class AdapterOptions {
        private PowerPreference powerPreference = PowerPreference.UNDEFINED;
        private boolean forceFallbackAdapter = false;
        
        public AdapterOptions withPowerPreference(PowerPreference preference) {
            this.powerPreference = preference;
            return this;
        }
        
        public AdapterOptions withForceFallbackAdapter(boolean force) {
            this.forceFallbackAdapter = force;
            return this;
        }
        
        public PowerPreference getPowerPreference() {
            return powerPreference;
        }
        
        public boolean isForceFallbackAdapter() {
            return forceFallbackAdapter;
        }
        
        @Override
        public String toString() {
            return String.format("AdapterOptions{power=%s, fallback=%s}", 
                powerPreference, forceFallbackAdapter);
        }
    }
    
    /**
     * Power preference for adapter selection.
     */
    public enum PowerPreference {
        UNDEFINED(WebGPUNative.POWER_PREFERENCE_UNDEFINED),
        LOW_POWER(WebGPUNative.POWER_PREFERENCE_LOW_POWER),
        HIGH_PERFORMANCE(WebGPUNative.POWER_PREFERENCE_HIGH_PERFORMANCE);
        
        private final int value;
        
        PowerPreference(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
}