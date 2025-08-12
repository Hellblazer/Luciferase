package com.hellblazer.luciferase.webgpu.surface;

import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import com.hellblazer.luciferase.webgpu.platform.Platform;
import com.hellblazer.luciferase.webgpu.platform.PlatformDetector;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform-specific surface descriptors for WebGPU.
 * Provides factory methods for creating surface descriptors from native window handles.
 */
public abstract class SurfaceDescriptor {
    private static final Logger log = LoggerFactory.getLogger(SurfaceDescriptor.class);
    
    // Dawn S_TYPE constants for surface descriptors (verified against Dawn)
    private static final int S_TYPE_SURFACE_DESCRIPTOR_FROM_METAL_LAYER = 0x00000001;
    private static final int S_TYPE_SURFACE_DESCRIPTOR_FROM_WINDOWS_HWND = 0x00000002;
    private static final int S_TYPE_SURFACE_DESCRIPTOR_FROM_XLIB_WINDOW = 0x00000003;
    private static final int S_TYPE_SURFACE_DESCRIPTOR_FROM_WAYLAND_SURFACE = 0x00000007;
    
    protected final Arena arena;
    protected MemorySegment descriptor;
    
    protected SurfaceDescriptor(Arena arena) {
        this.arena = arena;
        // Don't call createDescriptor() here - let subclasses do it after their fields are initialized
    }
    
    /**
     * Create the platform-specific descriptor structure.
     */
    protected abstract MemorySegment createDescriptor();
    
    /**
     * Get the descriptor memory segment.
     */
    public MemorySegment getDescriptor() {
        if (descriptor == null) {
            descriptor = createDescriptor();
        }
        return descriptor;
    }
    
    /**
     * Create a surface descriptor for the current platform.
     * 
     * @param arena memory arena for allocation
     * @param windowHandle native window handle
     * @return platform-specific surface descriptor
     */
    public static SurfaceDescriptor create(Arena arena, long windowHandle) {
        var platform = PlatformDetector.detectPlatform();
        log.debug("Creating surface descriptor for platform: {}", platform);
        
        return switch (platform) {
            case MACOS_AARCH64, MACOS_X86_64 -> new MetalSurfaceDescriptor(arena, windowHandle);
            case WINDOWS_AARCH64, WINDOWS_X86_64 -> new WindowsSurfaceDescriptor(arena, windowHandle);
            case LINUX_AARCH64, LINUX_X86_64 -> new X11SurfaceDescriptor(arena, windowHandle);
        };
    }
    
    /**
     * Metal surface descriptor for macOS.
     */
    public static class MetalSurfaceDescriptor extends SurfaceDescriptor {
        private final long metalLayer;
        
        public MetalSurfaceDescriptor(Arena arena, long metalLayer) {
            super(arena);
            this.metalLayer = metalLayer;
        }
        
        @Override
        protected MemorySegment createDescriptor() {
            log.debug("Creating Metal surface descriptor with layer: 0x{}", Long.toHexString(metalLayer));
            
            // Allocate SURFACE_DESCRIPTOR_FROM_METAL_LAYER structure
            var descriptor = arena.allocate(WebGPUNative.Descriptors.SURFACE_DESCRIPTOR_FROM_METAL_LAYER);
            
            // Set the chain type using offsets
            descriptor.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // next
            descriptor.set(ValueLayout.JAVA_INT, 8, S_TYPE_SURFACE_DESCRIPTOR_FROM_METAL_LAYER); // sType
            descriptor.set(ValueLayout.ADDRESS, 16, MemorySegment.ofAddress(metalLayer)); // layer
            
            // Debug: verify the values were set correctly
            var nextPtr = descriptor.get(ValueLayout.ADDRESS, 0);
            var sType = descriptor.get(ValueLayout.JAVA_INT, 8);
            var layerPtr = descriptor.get(ValueLayout.ADDRESS, 16);
            
            log.debug("Metal surface descriptor created:");
            log.debug("  - next: {}", nextPtr);
            log.debug("  - sType: 0x{} (expected: 0x{})", Integer.toHexString(sType), Integer.toHexString(S_TYPE_SURFACE_DESCRIPTOR_FROM_METAL_LAYER));
            log.debug("  - layer: 0x{} at offset 16", Long.toHexString(layerPtr.address()));
            return descriptor;
        }
    }
    
    /**
     * Windows surface descriptor for D3D12/Vulkan.
     */
    public static class WindowsSurfaceDescriptor extends SurfaceDescriptor {
        private final long hinstance;
        private final long hwnd;
        
        public WindowsSurfaceDescriptor(Arena arena, long hwnd) {
            super(arena);
            this.hinstance = getHInstance();
            this.hwnd = hwnd;
        }
        
        @Override
        protected MemorySegment createDescriptor() {
            // Allocate SURFACE_DESCRIPTOR_FROM_WINDOWS_HWND structure
            var descriptor = arena.allocate(WebGPUNative.Descriptors.SURFACE_DESCRIPTOR_FROM_WINDOWS_HWND);
            
            // Set the chain type using offsets
            descriptor.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // next
            descriptor.set(ValueLayout.JAVA_INT, 8, S_TYPE_SURFACE_DESCRIPTOR_FROM_WINDOWS_HWND); // sType
            descriptor.set(ValueLayout.ADDRESS, 16, MemorySegment.ofAddress(hinstance)); // hinstance
            descriptor.set(ValueLayout.ADDRESS, 24, MemorySegment.ofAddress(hwnd)); // hwnd
            
            log.debug("Created Windows surface descriptor with HWND: 0x{}", Long.toHexString(hwnd));
            return descriptor;
        }
        
        private long getHInstance() {
            // TODO: Get HINSTANCE from current process
            // This would require JNI or additional FFM bindings to Windows API
            return 0;
        }
    }
    
    /**
     * X11 surface descriptor for Linux.
     */
    public static class X11SurfaceDescriptor extends SurfaceDescriptor {
        private final long display;
        private final long window;
        
        public X11SurfaceDescriptor(Arena arena, long window) {
            super(arena);
            this.display = getX11Display();
            this.window = window;
        }
        
        @Override
        protected MemorySegment createDescriptor() {
            // Allocate SURFACE_DESCRIPTOR_FROM_XLIB_WINDOW structure
            var descriptor = arena.allocate(WebGPUNative.Descriptors.SURFACE_DESCRIPTOR_FROM_XLIB_WINDOW);
            
            // Set the chain type using offsets
            descriptor.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // next
            descriptor.set(ValueLayout.JAVA_INT, 8, S_TYPE_SURFACE_DESCRIPTOR_FROM_XLIB_WINDOW); // sType
            descriptor.set(ValueLayout.ADDRESS, 16, MemorySegment.ofAddress(display)); // display
            descriptor.set(ValueLayout.JAVA_LONG, 24, window); // window
            
            log.debug("Created X11 surface descriptor with window: 0x{}", Long.toHexString(window));
            return descriptor;
        }
        
        private long getX11Display() {
            // TODO: Get X11 Display pointer
            // This would require JNI or additional FFM bindings to X11
            return 0;
        }
    }
    
    /**
     * Wayland surface descriptor for Linux.
     */
    public static class WaylandSurfaceDescriptor extends SurfaceDescriptor {
        private final long display;
        private final long surface;
        
        public WaylandSurfaceDescriptor(Arena arena, long display, long surface) {
            super(arena);
            this.display = display;
            this.surface = surface;
        }
        
        @Override
        protected MemorySegment createDescriptor() {
            // Allocate SURFACE_DESCRIPTOR_FROM_WAYLAND_SURFACE structure
            var descriptor = arena.allocate(WebGPUNative.Descriptors.SURFACE_DESCRIPTOR_FROM_WAYLAND_SURFACE);
            
            // Set the chain type using offsets
            descriptor.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // next
            descriptor.set(ValueLayout.JAVA_INT, 8, S_TYPE_SURFACE_DESCRIPTOR_FROM_WAYLAND_SURFACE); // sType
            descriptor.set(ValueLayout.ADDRESS, 16, MemorySegment.ofAddress(display)); // display
            descriptor.set(ValueLayout.ADDRESS, 24, MemorySegment.ofAddress(surface)); // surface
            
            log.debug("Created Wayland surface descriptor");
            return descriptor;
        }
    }
}