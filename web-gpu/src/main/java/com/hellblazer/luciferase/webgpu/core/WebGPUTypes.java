package com.hellblazer.luciferase.webgpu.core;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;

/**
 * Minimal WebGPU type definitions for FFM interop.
 * Based on WebGPU native header definitions.
 * 
 * Following the pattern from webgpu-java reference implementation:
 * - Simple handle types as MemorySegment
 * - Essential enums only
 * - Minimal descriptor structures
 */
public class WebGPUTypes {
    
    // Prevent instantiation
    private WebGPUTypes() {}
    
    // Native handle types - opaque pointers
    public record WGPUInstance(MemorySegment handle) {
        public boolean isNull() {
            return handle == null || handle.equals(MemorySegment.NULL);
        }
    }
    
    public record WGPUAdapter(MemorySegment handle) {
        public boolean isNull() {
            return handle == null || handle.equals(MemorySegment.NULL);
        }
    }
    
    public record WGPUDevice(MemorySegment handle) {
        public boolean isNull() {
            return handle == null || handle.equals(MemorySegment.NULL);
        }
    }
    
    public record WGPUSurface(MemorySegment handle) {
        public boolean isNull() {
            return handle == null || handle.equals(MemorySegment.NULL);
        }
    }
    
    public record WGPUQueue(MemorySegment handle) {
        public boolean isNull() {
            return handle == null || handle.equals(MemorySegment.NULL);
        }
    }
    
    public record WGPUCommandEncoder(MemorySegment handle) {
        public boolean isNull() {
            return handle == null || handle.equals(MemorySegment.NULL);
        }
    }
    
    public record WGPURenderPassEncoder(MemorySegment handle) {
        public boolean isNull() {
            return handle == null || handle.equals(MemorySegment.NULL);
        }
    }
    
    public record WGPUTexture(MemorySegment handle) {
        public boolean isNull() {
            return handle == null || handle.equals(MemorySegment.NULL);
        }
    }
    
    public record WGPUTextureView(MemorySegment handle) {
        public boolean isNull() {
            return handle == null || handle.equals(MemorySegment.NULL);
        }
    }
    
    // Essential enums
    public static class TextureFormat {
        public static final int Undefined = 0;
        public static final int BGRA8Unorm = 23;
        public static final int BGRA8UnormSrgb = 24;
        public static final int RGBA8Unorm = 18;
        public static final int RGBA8UnormSrgb = 19;
    }
    
    public static class PresentMode {
        public static final int Fifo = 0;        // VSync
        public static final int Immediate = 1;    // No VSync
        public static final int Mailbox = 2;      // Triple buffering
    }
    
    public static class TextureUsage {
        public static final int None = 0x00000000;
        public static final int CopySrc = 0x00000001;
        public static final int CopyDst = 0x00000002;
        public static final int TextureBinding = 0x00000004;
        public static final int StorageBinding = 0x00000008;
        public static final int RenderAttachment = 0x00000010;
    }
    
    public static class BackendType {
        public static final int Null = 0;
        public static final int WebGPU = 1;
        public static final int D3D11 = 2;
        public static final int D3D12 = 3;
        public static final int Metal = 4;
        public static final int Vulkan = 5;
        public static final int OpenGL = 6;
        public static final int OpenGLES = 7;
    }
    
    public static class PowerPreference {
        public static final int Undefined = 0;
        public static final int LowPower = 1;
        public static final int HighPerformance = 2;
    }
    
    // Request status for callbacks
    public static class RequestAdapterStatus {
        public static final int Success = 0;
        public static final int Unavailable = 1;
        public static final int Error = 2;
        public static final int Unknown = 3;
    }
    
    public static class RequestDeviceStatus {
        public static final int Success = 0;
        public static final int Error = 1;
        public static final int Unknown = 2;
    }
    
    // Minimal descriptor structures using MemoryLayout
    public static class Layouts {
        public static final MemoryLayout INSTANCE_DESCRIPTOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain")
        ).withName("WGPUInstanceDescriptor");
        
        public static final MemoryLayout REQUEST_ADAPTER_OPTIONS = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("compatibleSurface"),
            ValueLayout.JAVA_INT.withName("powerPreference"),
            ValueLayout.JAVA_INT.withName("backendType"),
            ValueLayout.JAVA_BOOLEAN.withName("forceFallbackAdapter"),
            MemoryLayout.paddingLayout(3) // Alignment padding
        ).withName("WGPURequestAdapterOptions");
        
        public static final MemoryLayout DEVICE_DESCRIPTOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("label"),
            ValueLayout.JAVA_INT.withName("requiredFeaturesCount"),
            MemoryLayout.paddingLayout(4), // Alignment padding
            ValueLayout.ADDRESS.withName("requiredFeatures"),
            ValueLayout.ADDRESS.withName("requiredLimits"),
            ValueLayout.ADDRESS.withName("defaultQueue"),
            ValueLayout.ADDRESS.withName("deviceLostCallback"),
            ValueLayout.ADDRESS.withName("deviceLostUserdata")
        ).withName("WGPUDeviceDescriptor");
        
        public static final MemoryLayout SURFACE_CONFIGURATION = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("device"),
            ValueLayout.JAVA_INT.withName("format"),
            ValueLayout.JAVA_INT.withName("usage"),
            ValueLayout.JAVA_INT.withName("viewFormatCount"),
            MemoryLayout.paddingLayout(4), // Alignment padding
            ValueLayout.ADDRESS.withName("viewFormats"),
            ValueLayout.JAVA_INT.withName("alphaMode"),
            ValueLayout.JAVA_INT.withName("width"),
            ValueLayout.JAVA_INT.withName("height"),
            ValueLayout.JAVA_INT.withName("presentMode")
        ).withName("WGPUSurfaceConfiguration");
        
        // Surface capabilities for querying supported formats
        public static final MemoryLayout SURFACE_CAPABILITIES = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.JAVA_INT.withName("formatCount"),
            MemoryLayout.paddingLayout(4), // Alignment padding
            ValueLayout.ADDRESS.withName("formats"),
            ValueLayout.JAVA_INT.withName("presentModeCount"),
            MemoryLayout.paddingLayout(4), // Alignment padding
            ValueLayout.ADDRESS.withName("presentModes"),
            ValueLayout.JAVA_INT.withName("alphaModeCount"),
            MemoryLayout.paddingLayout(4), // Alignment padding
            ValueLayout.ADDRESS.withName("alphaModes")
        ).withName("WGPUSurfaceCapabilities");
    }
    
    // VarHandles for efficient field access
    public static class Handles {
        private static final VarHandle NEXT_IN_CHAIN;
        private static final VarHandle COMPATIBLE_SURFACE;
        private static final VarHandle POWER_PREFERENCE;
        private static final VarHandle BACKEND_TYPE;
        private static final VarHandle FORCE_FALLBACK;
        
        static {
            NEXT_IN_CHAIN = Layouts.REQUEST_ADAPTER_OPTIONS.varHandle(
                MemoryLayout.PathElement.groupElement("nextInChain"));
            COMPATIBLE_SURFACE = Layouts.REQUEST_ADAPTER_OPTIONS.varHandle(
                MemoryLayout.PathElement.groupElement("compatibleSurface"));
            POWER_PREFERENCE = Layouts.REQUEST_ADAPTER_OPTIONS.varHandle(
                MemoryLayout.PathElement.groupElement("powerPreference"));
            BACKEND_TYPE = Layouts.REQUEST_ADAPTER_OPTIONS.varHandle(
                MemoryLayout.PathElement.groupElement("backendType"));
            FORCE_FALLBACK = Layouts.REQUEST_ADAPTER_OPTIONS.varHandle(
                MemoryLayout.PathElement.groupElement("forceFallbackAdapter"));
        }
        
        public static void setNextInChain(MemorySegment struct, MemorySegment value) {
            NEXT_IN_CHAIN.set(struct, 0L, value);
        }
        
        public static void setCompatibleSurface(MemorySegment struct, MemorySegment value) {
            COMPATIBLE_SURFACE.set(struct, 0L, value);
        }
        
        public static void setPowerPreference(MemorySegment struct, int value) {
            POWER_PREFERENCE.set(struct, 0L, value);
        }
        
        public static void setBackendType(MemorySegment struct, int value) {
            BACKEND_TYPE.set(struct, 0L, value);
        }
        
        public static void setForceFallback(MemorySegment struct, boolean value) {
            FORCE_FALLBACK.set(struct, 0L, value);
        }
    }
}