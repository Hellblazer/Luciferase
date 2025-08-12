package com.hellblazer.luciferase.webgpu.wrapper;

import com.hellblazer.luciferase.webgpu.CallbackBridge;
import com.hellblazer.luciferase.webgpu.WebGPU;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe wrapper for WebGPU Adapter.
 * Represents a physical graphics/compute device.
 * 
 * Now uses Dawn library which properly handles callbacks without crashing.
 */
public class Adapter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Adapter.class);
    
    private final MemorySegment handle;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Instance instance; // Reference to the instance for event processing
    
    /**
     * Create an adapter wrapper from a native handle.
     * 
     * @param handle the native adapter handle
     */
    public Adapter(MemorySegment handle) {
        this(handle, null);
    }
    
    /**
     * Create an adapter wrapper from a native handle with an instance reference.
     * 
     * @param handle the native adapter handle
     * @param instance the WebGPU instance for event processing
     */
    public Adapter(MemorySegment handle, Instance instance) {
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Invalid adapter handle");
        }
        this.handle = handle;
        this.instance = instance;
        log.debug("Created adapter wrapper: 0x{}", Long.toHexString(handle.address()));
    }
    
    /**
     * Request a device from this adapter.
     * Uses Dawn's working callback mechanism.
     * 
     * @param descriptor the device descriptor  
     * @return a future that completes with the device
     */
    public CompletableFuture<Device> requestDevice(DeviceDescriptor descriptor) {
        if (closed.get()) {
            throw new IllegalStateException("Adapter is closed");
        }
        
        log.debug("Requesting device with descriptor: {}", descriptor);
        
        // Use the callback bridge to request device
        // Dawn handles callbacks correctly without crashing
        return CallbackBridge.requestDevice(handle, descriptor, instance);
    }
    
    /**
     * Request a device with default configuration.
     * 
     * @return a future that completes with the device
     */
    public CompletableFuture<Device> requestDevice() {
        return requestDevice(new DeviceDescriptor());
    }
    
    /**
     * Get adapter properties.
     * 
     * @return the adapter properties
     */
    public AdapterProperties getProperties() {
        if (closed.get()) {
            throw new IllegalStateException("Adapter is closed");
        }
        
        // TODO: Implement wgpuAdapterGetProperties
        return new AdapterProperties();
    }
    
    /**
     * Get the native handle for this adapter.
     * 
     * @return the native memory segment
     */
    public MemorySegment getHandle() {
        return handle;
    }
    
    /**
     * Set the instance reference for event processing.
     * 
     * @param instance the WebGPU instance
     */
    public void setInstance(Instance instance) {
        this.instance = instance;
    }
    
    /**
     * Get the instance reference.
     * 
     * @return the instance, or null if not set
     */
    public Instance getInstance() {
        return instance;
    }
    
    /**
     * Check if this adapter is valid and not closed.
     * 
     * @return true if the adapter is valid
     */
    public boolean isValid() {
        return !closed.get() && handle != null && !handle.equals(MemorySegment.NULL);
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            com.hellblazer.luciferase.webgpu.WebGPU.releaseAdapter(handle);
            log.debug("Released adapter");
        }
    }
    
    /**
     * Device descriptor for requesting a device.
     */
    public static class DeviceDescriptor {
        private String label;
        private long[] requiredFeatures = new long[0];
        private DeviceLimits requiredLimits;
        
        public DeviceDescriptor withLabel(String label) {
            this.label = label;
            return this;
        }
        
        public DeviceDescriptor withRequiredFeatures(long... features) {
            this.requiredFeatures = features;
            return this;
        }
        
        public DeviceDescriptor withRequiredLimits(DeviceLimits limits) {
            this.requiredLimits = limits;
            return this;
        }
        
        public String getLabel() {
            return label;
        }
        
        public long[] getRequiredFeatures() {
            return requiredFeatures;
        }
        
        public DeviceLimits getRequiredLimits() {
            return requiredLimits;
        }
        
        @Override
        public String toString() {
            return String.format("DeviceDescriptor{label='%s', features=%d}", 
                label, requiredFeatures.length);
        }
    }
    
    /**
     * Device limits configuration.
     */
    public static class DeviceLimits {
        private int maxTextureDimension1D = 8192;
        private int maxTextureDimension2D = 8192;
        private int maxTextureDimension3D = 2048;
        private int maxTextureArrayLayers = 256;
        private int maxBindGroups = 4;
        private long maxBufferSize = 268435456; // 256 MB
        private int maxVertexBuffers = 8;
        private int maxVertexAttributes = 16;
        private int maxVertexBufferArrayStride = 2048;
        private int maxInterStageShaderComponents = 60;
        private int maxComputeWorkgroupStorageSize = 16384;
        private int maxComputeInvocationsPerWorkgroup = 256;
        private int maxComputeWorkgroupSizeX = 256;
        private int maxComputeWorkgroupSizeY = 256;
        private int maxComputeWorkgroupSizeZ = 64;
        private int maxComputeWorkgroupsPerDimension = 65535;
        
        // Getters and setters omitted for brevity
        // In a full implementation, add all getters/setters
    }
    
    /**
     * Adapter properties.
     */
    public static class AdapterProperties {
        private String vendorName = "Unknown";
        private String architecture = "Unknown";
        private String deviceName = "Unknown";
        private String driverDescription = "Unknown";
        private AdapterType adapterType = AdapterType.UNKNOWN;
        private BackendType backendType = BackendType.UNKNOWN;
        
        // Getters omitted for brevity
        
        @Override
        public String toString() {
            return String.format("AdapterProperties{vendor='%s', device='%s', type=%s, backend=%s}",
                vendorName, deviceName, adapterType, backendType);
        }
    }
    
    /**
     * Adapter type enumeration.
     */
    public enum AdapterType {
        UNKNOWN,
        INTEGRATED_GPU,
        DISCRETE_GPU,
        VIRTUAL_GPU,
        CPU
    }
    
    /**
     * Backend type enumeration.
     */
    public enum BackendType {
        UNKNOWN,
        NULL,
        WEBGPU,
        D3D11,
        D3D12,
        METAL,
        VULKAN,
        OPENGL,
        OPENGLES
    }
}