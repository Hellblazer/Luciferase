package com.hellblazer.luciferase.webgpu.wrapper;

import com.hellblazer.luciferase.webgpu.WebGPU;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe wrapper for WebGPU Instance.
 * Represents a WebGPU instance, which is the entry point to the WebGPU API.
 */
public class Instance implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Instance.class);
    
    private final MemorySegment handle;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
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
        
        return CompletableFuture.supplyAsync(() -> {
            log.debug("Requesting adapter with options: {}", options);
            
            // Use the synchronous enumerateAdapters API to avoid callback issues
            var adapterHandles = WebGPU.enumerateAdapters(handle, null); // TODO: convert options to native struct
            
            if (adapterHandles.length > 0) {
                // Return the first available adapter
                var adapterHandle = adapterHandles[0];
                log.debug("Successfully obtained adapter from enumerateAdapters: 0x{}", 
                         Long.toHexString(adapterHandle.address()));
                return new Adapter(adapterHandle);
            } else {
                log.warn("No adapters available from enumerateAdapters");
                return null;
            }
        });
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
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            WebGPU.releaseInstance(handle);
            log.debug("Released WebGPU instance");
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        if (!closed.get()) {
            log.warn("Instance not properly closed, releasing in finalizer");
            close();
        }
        super.finalize();
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