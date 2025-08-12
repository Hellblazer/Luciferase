package com.hellblazer.luciferase.webgpu.surface;

import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import com.hellblazer.luciferase.webgpu.platform.PlatformDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;

/**
 * Corrected WebGPU surface descriptor that uses proper chaining.
 * 
 * The structure is:
 * - WGPUSurfaceDescriptor (top level)
 *   - nextInChain -> platform-specific descriptor (e.g., WGPUSurfaceDescriptorFromMetalLayer)
 */
public class SurfaceDescriptorV2 {
    private static final Logger log = LoggerFactory.getLogger(SurfaceDescriptorV2.class);
    
    // Dawn WebGPU sType values (verified against Dawn error messages)
    private static final int S_TYPE_SURFACE_SOURCE_METAL_LAYER = 0x00000001;
    private static final int S_TYPE_SURFACE_SOURCE_WINDOWS_HWND = 0x00000002;
    private static final int S_TYPE_SURFACE_SOURCE_XLIB_WINDOW = 0x00000003;
    private static final int S_TYPE_SURFACE_SOURCE_CANVAS_HTML_SELECTOR = 0x00000004;
    private static final int S_TYPE_SURFACE_SOURCE_WAYLAND_SURFACE = 0x00000007;
    private static final int S_TYPE_SURFACE_SOURCE_ANDROID_NATIVE_WINDOW = 0x00000008;
    private static final int S_TYPE_SURFACE_SOURCE_WINDOWS_CORE_WINDOW = 0x00000005;
    private static final int S_TYPE_SURFACE_SOURCE_WINDOWS_SWAP_CHAIN_PANEL = 0x00000006;
    
    /**
     * Create a properly chained surface descriptor for the current platform.
     * 
     * @param arena memory arena for allocation
     * @param windowHandle native window handle (CAMetalLayer on macOS, HWND on Windows, etc.)
     * @return the top-level surface descriptor
     */
    public static MemorySegment create(Arena arena, long windowHandle) {
        var platform = PlatformDetector.detectPlatform();
        log.debug("Creating chained surface descriptor for platform: {}", platform);
        
        return switch (platform) {
            case MACOS_AARCH64, MACOS_X86_64 -> createMetalSurfaceDescriptor(arena, windowHandle);
            case WINDOWS_AARCH64, WINDOWS_X86_64 -> createWindowsSurfaceDescriptor(arena, windowHandle);
            case LINUX_AARCH64, LINUX_X86_64 -> createX11SurfaceDescriptor(arena, windowHandle);
        };
    }
    
    private static MemorySegment createMetalSurfaceDescriptor(Arena arena, long metalLayer) {
        log.debug("Creating Metal surface descriptor with layer: 0x{}", Long.toHexString(metalLayer));
        
        // Step 1: Create the platform-specific descriptor (WGPUSurfaceSourceMetalLayer)
        // Structure: next (8 bytes) + sType (4 bytes) + padding (4 bytes) + layer (8 bytes) = 24 bytes
        var metalDescriptor = arena.allocate(24);
        metalDescriptor.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // next
        metalDescriptor.set(ValueLayout.JAVA_INT, 8, S_TYPE_SURFACE_SOURCE_METAL_LAYER); // sType = 0x4
        metalDescriptor.set(ValueLayout.ADDRESS, 16, MemorySegment.ofAddress(metalLayer)); // layer
        
        // Step 2: Create the top-level WGPUSurfaceDescriptor
        // Structure: nextInChain (8 bytes) + label (16 bytes for string view)
        var surfaceDescriptor = arena.allocate(24);
        surfaceDescriptor.set(ValueLayout.ADDRESS, 0, metalDescriptor); // nextInChain points to metalDescriptor
        surfaceDescriptor.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL); // label.data = NULL
        surfaceDescriptor.set(ValueLayout.JAVA_LONG, 16, 0L); // label.length = 0
        
        log.debug("Created chained surface descriptor:");
        log.debug("  - Top-level descriptor at: 0x{}", Long.toHexString(surfaceDescriptor.address()));
        log.debug("  - Metal descriptor chained at: 0x{}", Long.toHexString(metalDescriptor.address()));
        log.debug("  - Metal layer: 0x{}", Long.toHexString(metalLayer));
        
        return surfaceDescriptor;
    }
    
    private static MemorySegment createWindowsSurfaceDescriptor(Arena arena, long hwnd) {
        log.debug("Creating Windows surface descriptor with HWND: 0x{}", Long.toHexString(hwnd));
        
        // Step 1: Create WGPUSurfaceSourceWindowsHWND
        var windowsDescriptor = arena.allocate(32); // next + sType + padding + hinstance + hwnd
        windowsDescriptor.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // next
        windowsDescriptor.set(ValueLayout.JAVA_INT, 8, S_TYPE_SURFACE_SOURCE_WINDOWS_HWND); // sType = 0x5
        windowsDescriptor.set(ValueLayout.ADDRESS, 16, MemorySegment.NULL); // hinstance (could get from process)
        windowsDescriptor.set(ValueLayout.ADDRESS, 24, MemorySegment.ofAddress(hwnd)); // hwnd
        
        // Step 2: Create top-level descriptor
        var surfaceDescriptor = arena.allocate(24);
        surfaceDescriptor.set(ValueLayout.ADDRESS, 0, windowsDescriptor); // nextInChain
        surfaceDescriptor.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL); // label.data
        surfaceDescriptor.set(ValueLayout.JAVA_LONG, 16, 0L); // label.length
        
        return surfaceDescriptor;
    }
    
    private static MemorySegment createX11SurfaceDescriptor(Arena arena, long window) {
        log.debug("Creating X11 surface descriptor with window: 0x{}", Long.toHexString(window));
        
        // Step 1: Create WGPUSurfaceSourceXlibWindow
        var x11Descriptor = arena.allocate(32); // next + sType + padding + display + window
        x11Descriptor.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // next
        x11Descriptor.set(ValueLayout.JAVA_INT, 8, S_TYPE_SURFACE_SOURCE_XLIB_WINDOW); // sType = 0x6
        x11Descriptor.set(ValueLayout.ADDRESS, 16, MemorySegment.NULL); // display (would need to get from X11)
        x11Descriptor.set(ValueLayout.JAVA_LONG, 24, window); // window
        
        // Step 2: Create top-level descriptor
        var surfaceDescriptor = arena.allocate(24);
        surfaceDescriptor.set(ValueLayout.ADDRESS, 0, x11Descriptor); // nextInChain
        surfaceDescriptor.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL); // label.data
        surfaceDescriptor.set(ValueLayout.JAVA_LONG, 16, 0L); // label.length
        
        return surfaceDescriptor;
    }
}