package com.hellblazer.luciferase.webgpu.builder;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * High-level builder API for WebGPU.
 * Provides a fluent interface for creating WebGPU resources.
 */
public class WebGPUBuilder {
    private static final Logger log = LoggerFactory.getLogger(WebGPUBuilder.class);
    
    /**
     * Start building a WebGPU instance.
     */
    public static InstanceBuilder createInstance() {
        return new InstanceBuilder();
    }
    
    /**
     * Builder for WebGPU Instance.
     */
    public static class InstanceBuilder {
        private boolean validation = false;
        private String label = null;
        
        /**
         * Enable validation layers.
         */
        public InstanceBuilder withValidation(boolean enable) {
            this.validation = enable;
            return this;
        }
        
        /**
         * Set instance label for debugging.
         */
        public InstanceBuilder withLabel(String label) {
            this.label = label;
            return this;
        }
        
        /**
         * Build the instance.
         */
        public Instance build() {
            log.debug("Building WebGPU instance with validation={}, label={}", validation, label);
            return new Instance();
        }
    }
    
    /**
     * Builder for adapter request.
     */
    public static class AdapterRequestBuilder {
        private final Instance instance;
        private Instance.PowerPreference powerPreference = Instance.PowerPreference.UNDEFINED;
        private boolean forceFallback = false;
        
        public AdapterRequestBuilder(Instance instance) {
            this.instance = instance;
        }
        
        /**
         * Set power preference.
         */
        public AdapterRequestBuilder powerPreference(Instance.PowerPreference preference) {
            this.powerPreference = preference;
            return this;
        }
        
        /**
         * Force fallback adapter.
         */
        public AdapterRequestBuilder forceFallback(boolean force) {
            this.forceFallback = force;
            return this;
        }
        
        /**
         * Request adapter asynchronously.
         */
        public CompletableFuture<Adapter> requestAsync() {
            var options = new Instance.AdapterOptions()
                .withPowerPreference(powerPreference)
                .withForceFallbackAdapter(forceFallback);
            return instance.requestAdapter(options);
        }
        
        /**
         * Request adapter synchronously with timeout.
         */
        public Adapter request(long timeout, TimeUnit unit) throws Exception {
            return requestAsync().get(timeout, unit);
        }
    }
    
    /**
     * Builder for device request.
     */
    public static class DeviceRequestBuilder {
        private final Adapter adapter;
        private String label = null;
        private long[] requiredFeatures = new long[0];
        private Adapter.DeviceLimits limits = null;
        
        public DeviceRequestBuilder(Adapter adapter) {
            this.adapter = adapter;
        }
        
        /**
         * Set device label.
         */
        public DeviceRequestBuilder withLabel(String label) {
            this.label = label;
            return this;
        }
        
        /**
         * Set required features.
         */
        public DeviceRequestBuilder withFeatures(long... features) {
            this.requiredFeatures = features;
            return this;
        }
        
        /**
         * Set required limits.
         */
        public DeviceRequestBuilder withLimits(Adapter.DeviceLimits limits) {
            this.limits = limits;
            return this;
        }
        
        /**
         * Request device asynchronously.
         */
        public CompletableFuture<Device> requestAsync() {
            var descriptor = new Adapter.DeviceDescriptor()
                .withLabel(label)
                .withRequiredFeatures(requiredFeatures)
                .withRequiredLimits(limits);
            return adapter.requestDevice(descriptor);
        }
        
        /**
         * Request device synchronously with timeout.
         */
        public Device request(long timeout, TimeUnit unit) throws Exception {
            return requestAsync().get(timeout, unit);
        }
    }
    
    /**
     * Builder for buffers.
     */
    public static class BufferBuilder {
        private final Device device;
        private long size;
        private int usage = 0;
        private String label = null;
        private boolean mappedAtCreation = false;
        
        public BufferBuilder(Device device, long size) {
            this.device = device;
            this.size = size;
        }
        
        /**
         * Set buffer label.
         */
        public BufferBuilder withLabel(String label) {
            this.label = label;
            return this;
        }
        
        /**
         * Add buffer usage flags.
         */
        public BufferBuilder withUsage(BufferUsage... usages) {
            for (var usage : usages) {
                this.usage |= usage.getValue();
            }
            return this;
        }
        
        /**
         * Map buffer at creation.
         */
        public BufferBuilder mappedAtCreation(boolean mapped) {
            this.mappedAtCreation = mapped;
            return this;
        }
        
        /**
         * Build the buffer.
         */
        public Buffer build() {
            var descriptor = new Device.BufferDescriptor(size, usage)
                .withLabel(label)
                .withMappedAtCreation(mappedAtCreation);
            return device.createBuffer(descriptor);
        }
    }
    
    /**
     * Builder for compute shaders.
     */
    public static class ComputeShaderBuilder {
        private final Device device;
        private String code;
        private String label = null;
        private String entryPoint = "main";
        
        public ComputeShaderBuilder(Device device, String code) {
            this.device = device;
            this.code = code;
        }
        
        /**
         * Set shader label.
         */
        public ComputeShaderBuilder withLabel(String label) {
            this.label = label;
            return this;
        }
        
        /**
         * Set entry point function name.
         */
        public ComputeShaderBuilder withEntryPoint(String entryPoint) {
            this.entryPoint = entryPoint;
            return this;
        }
        
        /**
         * Build shader module.
         */
        public ShaderModule buildModule() {
            var descriptor = new Device.ShaderModuleDescriptor(code)
                .withLabel(label);
            return device.createShaderModule(descriptor);
        }
        
        /**
         * Build compute pipeline.
         */
        public ComputePipeline buildPipeline() {
            var module = buildModule();
            var descriptor = new Device.ComputePipelineDescriptor(module)
                .withLabel(label)
                .withEntryPoint(entryPoint);
            return device.createComputePipeline(descriptor);
        }
    }
    
    /**
     * Buffer usage flags.
     */
    public enum BufferUsage {
        MAP_READ(WebGPUNative.BUFFER_USAGE_MAP_READ),
        MAP_WRITE(WebGPUNative.BUFFER_USAGE_MAP_WRITE),
        COPY_SRC(WebGPUNative.BUFFER_USAGE_COPY_SRC),
        COPY_DST(WebGPUNative.BUFFER_USAGE_COPY_DST),
        INDEX(WebGPUNative.BUFFER_USAGE_INDEX),
        VERTEX(WebGPUNative.BUFFER_USAGE_VERTEX),
        UNIFORM(WebGPUNative.BUFFER_USAGE_UNIFORM),
        STORAGE(WebGPUNative.BUFFER_USAGE_STORAGE),
        INDIRECT(WebGPUNative.BUFFER_USAGE_INDIRECT),
        QUERY_RESOLVE(WebGPUNative.BUFFER_USAGE_QUERY_RESOLVE);
        
        private final int value;
        
        BufferUsage(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
    
    /**
     * Example usage of the builder API.
     */
    public static void example() throws Exception {
        // Create instance with validation
        var instance = WebGPUBuilder.createInstance()
            .withValidation(true)
            .withLabel("MyApp")
            .build();
        
        // Request high-performance adapter
        var adapter = new AdapterRequestBuilder(instance)
            .powerPreference(Instance.PowerPreference.HIGH_PERFORMANCE)
            .request(5, TimeUnit.SECONDS);
        
        // Request device with features
        var device = new DeviceRequestBuilder(adapter)
            .withLabel("MainDevice")
            .withFeatures(/* feature flags */)
            .request(5, TimeUnit.SECONDS);
        
        // Create storage buffer
        var buffer = new BufferBuilder(device, 1024 * 1024)
            .withLabel("StorageBuffer")
            .withUsage(BufferUsage.STORAGE, BufferUsage.COPY_DST)
            .build();
        
        // Create compute shader
        var pipeline = new ComputeShaderBuilder(device, 
                "@compute @workgroup_size(64) fn main() { /* ... */ }")
            .withLabel("ComputeShader")
            .withEntryPoint("main")
            .buildPipeline();
        
        // Clean up
        buffer.close();
        pipeline.close();
        device.close();
        adapter.close();
        instance.close();
    }
}