package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe wrapper for WebGPU Sampler.
 * Controls how textures are sampled in shaders.
 */
public class Sampler implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Sampler.class);
    
    private final MemorySegment handle;
    private final Device device;
    private final SamplerDescriptor descriptor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    /**
     * Create a sampler wrapper.
     * 
     * @param handle the native sampler handle
     * @param device the device that created this sampler
     * @param descriptor the sampler descriptor
     */
    Sampler(MemorySegment handle, Device device, SamplerDescriptor descriptor) {
        this.handle = handle;
        this.device = device;
        this.descriptor = descriptor;
        log.debug("Created sampler with filter modes: min={}, mag={}, mipmap={}", 
            descriptor.minFilter, descriptor.magFilter, descriptor.mipmapFilter);
    }
    
    /**
     * Get the native handle.
     * 
     * @return the native handle
     */
    public MemorySegment getHandle() {
        return handle;
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // TODO: Release native sampler
            log.debug("Released sampler");
        }
    }
    
    /**
     * Sampler descriptor for creating samplers.
     */
    public static class SamplerDescriptor {
        private String label;
        private AddressMode addressModeU = AddressMode.CLAMP_TO_EDGE;
        private AddressMode addressModeV = AddressMode.CLAMP_TO_EDGE;
        private AddressMode addressModeW = AddressMode.CLAMP_TO_EDGE;
        private FilterMode magFilter = FilterMode.NEAREST;
        private FilterMode minFilter = FilterMode.NEAREST;
        private FilterMode mipmapFilter = FilterMode.NEAREST;
        private float lodMinClamp = 0.0f;
        private float lodMaxClamp = 32.0f;
        private CompareFunction compare = null;
        private int maxAnisotropy = 1;
        
        public SamplerDescriptor withLabel(String label) {
            this.label = label;
            return this;
        }
        
        public SamplerDescriptor withAddressMode(AddressMode u, AddressMode v, AddressMode w) {
            this.addressModeU = u;
            this.addressModeV = v;
            this.addressModeW = w;
            return this;
        }
        
        public SamplerDescriptor withFilterMode(FilterMode min, FilterMode mag, FilterMode mipmap) {
            this.minFilter = min;
            this.magFilter = mag;
            this.mipmapFilter = mipmap;
            return this;
        }
        
        public SamplerDescriptor withLodClamp(float min, float max) {
            this.lodMinClamp = min;
            this.lodMaxClamp = max;
            return this;
        }
        
        public SamplerDescriptor withCompare(CompareFunction compare) {
            this.compare = compare;
            return this;
        }
        
        public SamplerDescriptor withMaxAnisotropy(int maxAnisotropy) {
            this.maxAnisotropy = maxAnisotropy;
            return this;
        }
    }
    
    /**
     * Texture addressing mode.
     */
    public enum AddressMode {
        REPEAT(0),
        MIRROR_REPEAT(1),
        CLAMP_TO_EDGE(2);
        
        private final int value;
        
        AddressMode(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
    
    /**
     * Texture filtering mode.
     */
    public enum FilterMode {
        NEAREST(0),
        LINEAR(1);
        
        private final int value;
        
        FilterMode(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
    
    /**
     * Comparison function for depth textures.
     */
    public enum CompareFunction {
        NEVER(0),
        LESS(1),
        EQUAL(2),
        LESS_EQUAL(3),
        GREATER(4),
        NOT_EQUAL(5),
        GREATER_EQUAL(6),
        ALWAYS(7);
        
        private final int value;
        
        CompareFunction(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
}