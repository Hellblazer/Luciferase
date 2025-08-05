package com.hellblazer.luciferase.render.voxel.gpu;

import com.myworldllc.webgpu.WebGPU;
import com.myworldllc.webgpu.WebGPUTypes.*;
import static com.myworldllc.webgpu.WebGPUTypes.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebGPU context management for ESVO rendering.
 * Handles device initialization, feature detection, and lifecycle management.
 */
public class WebGPUContext {
    private static final Logger log = LoggerFactory.getLogger(WebGPUContext.class);
    
    private Instance instance;
    private Adapter adapter;
    private Device device;
    private Queue queue;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    
    /**
     * Initialize WebGPU context with high-performance adapter selection
     */
    public CompletableFuture<Void> initialize() {
        if (initialized.get()) {
            return CompletableFuture.completedFuture(null);
        }
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        try {
            // Create WebGPU instance
            InstanceDescriptor instanceDesc = new InstanceDescriptor();
            instanceDesc.setBackends(BackendType.D3D12, BackendType.METAL, BackendType.VULKAN);
            instance = WebGPU.createInstance(instanceDesc);
            
            if (instance == null) {
                throw new RuntimeException("Failed to create WebGPU instance");
            }
            
            // Request high-performance adapter
            RequestAdapterOptions adapterOptions = new RequestAdapterOptions();
            adapterOptions.setPowerPreference(PowerPreference.HIGH_PERFORMANCE);
            adapterOptions.setCompatibleSurface(null); // Compute-only, no surface needed
            
            instance.requestAdapter(adapterOptions, (status, adapter, message) -> {
                if (status != RequestAdapterStatus.SUCCESS || adapter == null) {
                    future.completeExceptionally(new RuntimeException(
                        "Failed to get WebGPU adapter: " + message));
                    return;
                }
                
                this.adapter = adapter;
                logAdapterInfo();
                
                // Request device with required features
                requestDevice(future);
            });
            
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    private void requestDevice(CompletableFuture<Void> future) {
        DeviceDescriptor deviceDesc = new DeviceDescriptor();
        deviceDesc.setLabel("ESVO Compute Device");
        
        // Request required features for ESVO
        RequiredFeatures features = new RequiredFeatures();
        if (adapter.hasFeature(FeatureName.FLOAT32_FILTERABLE)) {
            features.add(FeatureName.FLOAT32_FILTERABLE);
        }
        if (adapter.hasFeature(FeatureName.TEXTURE_COMPRESSION_BC)) {
            features.add(FeatureName.TEXTURE_COMPRESSION_BC);
        }
        if (adapter.hasFeature(FeatureName.TIMESTAMP_QUERY)) {
            features.add(FeatureName.TIMESTAMP_QUERY);
        }
        deviceDesc.setRequiredFeatures(features);
        
        // Set required limits for large voxel octrees
        RequiredLimits limits = new RequiredLimits();
        Limits requiredLimits = new Limits();
        requiredLimits.setMaxBufferSize(1024L * 1024L * 1024L); // 1GB
        requiredLimits.setMaxStorageBufferBindingSize(256 * 1024 * 1024); // 256MB
        requiredLimits.setMaxComputeWorkgroupStorageSize(32 * 1024); // 32KB
        requiredLimits.setMaxComputeInvocationsPerWorkgroup(256);
        requiredLimits.setMaxComputeWorkgroupSizeX(256);
        requiredLimits.setMaxComputeWorkgroupSizeY(256);
        requiredLimits.setMaxComputeWorkgroupSizeZ(64);
        requiredLimits.setMaxComputeWorkgroupsPerDimension(65535);
        limits.setLimits(requiredLimits);
        deviceDesc.setRequiredLimits(limits);
        
        // Set up device lost callback
        deviceDesc.setDeviceLostCallback((reason, message) -> {
            log.error("WebGPU device lost - Reason: {}, Message: {}", reason, message);
            initialized.set(false);
        });
        
        adapter.requestDevice(deviceDesc, (status, device, message) -> {
            if (status != RequestDeviceStatus.SUCCESS || device == null) {
                future.completeExceptionally(new RuntimeException(
                    "Failed to create WebGPU device: " + message));
                return;
            }
            
            this.device = device;
            this.queue = device.getQueue();
            
            // Set up error handling
            device.setUncapturedErrorCallback((errorType, errorMessage) -> {
                log.error("WebGPU Uncaptured Error [{}]: {}", errorType, errorMessage);
            });
            
            // Enable debug markers if available
            if (device.hasFeature(FeatureName.PUSH_CONSTANTS)) {
                queue.setLabel("ESVO Compute Queue");
            }
            
            initialized.set(true);
            log.info("WebGPU device initialized successfully");
            logDeviceInfo();
            
            future.complete(null);
        });
    }
    
    private void logAdapterInfo() {
        AdapterProperties props = adapter.getProperties();
        log.info("WebGPU Adapter Information:");
        log.info("  Name: {}", props.getName());
        log.info("  Vendor: {} (0x{:04X})", props.getVendorName(), props.getVendorID());
        log.info("  Device ID: 0x{:04X}", props.getDeviceID());
        log.info("  Backend: {}", props.getBackendType());
        log.info("  Driver: {}", props.getDriverDescription());
        log.info("  Type: {}", props.getAdapterType());
    }
    
    private void logDeviceInfo() {
        SupportedLimits supportedLimits = device.getLimits();
        Limits limits = supportedLimits.getLimits();
        
        log.info("WebGPU Device Limits:");
        log.info("  Max Buffer Size: {} MB", limits.getMaxBufferSize() / (1024 * 1024));
        log.info("  Max Storage Buffer: {} MB", limits.getMaxStorageBufferBindingSize() / (1024 * 1024));
        log.info("  Max Compute Workgroup Size: {} x {} x {}", 
                 limits.getMaxComputeWorkgroupSizeX(),
                 limits.getMaxComputeWorkgroupSizeY(),
                 limits.getMaxComputeWorkgroupSizeZ());
        log.info("  Max Compute Workgroups Per Dimension: {}", 
                 limits.getMaxComputeWorkgroupsPerDimension());
    }
    
    /**
     * Check if a specific feature is supported
     */
    public boolean hasFeature(FeatureName feature) {
        return device != null && device.hasFeature(feature);
    }
    
    /**
     * Create a command encoder for recording GPU commands
     */
    public CommandEncoder createCommandEncoder() {
        if (!initialized.get()) {
            throw new IllegalStateException("WebGPU context not initialized");
        }
        
        CommandEncoderDescriptor desc = new CommandEncoderDescriptor();
        desc.setLabel("ESVO Command Encoder");
        return device.createCommandEncoder(desc);
    }
    
    /**
     * Submit command buffer to GPU queue
     */
    public void submit(CommandBuffer commandBuffer) {
        if (!initialized.get()) {
            throw new IllegalStateException("WebGPU context not initialized");
        }
        queue.submit(commandBuffer);
    }
    
    /**
     * Wait for all queued operations to complete
     */
    public CompletableFuture<Void> waitIdle() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        queue.onSubmittedWorkDone((status) -> {
            if (status == QueueWorkDoneStatus.SUCCESS) {
                future.complete(null);
            } else {
                future.completeExceptionally(new RuntimeException("Queue work failed"));
            }
        });
        return future;
    }
    
    /**
     * Shutdown and release all WebGPU resources
     */
    public void shutdown() {
        initialized.set(false);
        
        if (queue != null) {
            queue.release();
            queue = null;
        }
        
        if (device != null) {
            device.release();
            device = null;
        }
        
        if (adapter != null) {
            adapter.release();
            adapter = null;
        }
        
        if (instance != null) {
            instance.release();
            instance = null;
        }
        
        log.info("WebGPU context shutdown complete");
    }
    
    // Getters
    public Device getDevice() {
        return device;
    }
    
    public Queue getQueue() {
        return queue;
    }
    
    public Adapter getAdapter() {
        return adapter;
    }
    
    public boolean isInitialized() {
        return initialized.get();
    }
}