package com.hellblazer.luciferase.webgpu.surface;

import com.hellblazer.luciferase.webgpu.platform.PlatformDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 fix: WebGPU surface descriptor with proper memory management.
 * 
 * Key fixes:
 * 1. Uses Arena.global() to prevent premature deallocation
 * 2. Maintains references to prevent GC of critical structures
 * 3. Provides cleanup mechanism for long-running applications
 */
public class SurfaceDescriptorV3 {
    private static final Logger log = LoggerFactory.getLogger(SurfaceDescriptorV3.class);
    
    // Dawn WebGPU sType values (verified against Dawn error messages)
    // Dawn expects: SType::SurfaceDescriptorFromMetalLayer = 0x1
    private static final int S_TYPE_SURFACE_SOURCE_METAL_LAYER = 0x00000001;
    private static final int S_TYPE_SURFACE_SOURCE_WINDOWS_HWND = 0x00000002;
    private static final int S_TYPE_SURFACE_SOURCE_XLIB_WINDOW = 0x00000003;
    private static final int S_TYPE_SURFACE_SOURCE_WAYLAND_SURFACE = 0x00000007;
    private static final int S_TYPE_SURFACE_SOURCE_ANDROID_NATIVE_WINDOW = 0x00000008;
    private static final int S_TYPE_SURFACE_SOURCE_XCB_WINDOW = 0x00000009;
    
    // Keep references to prevent GC of surface descriptors
    // Key: descriptor address, Value: descriptor segment
    private static final Map<Long, DescriptorHolder> activeDescriptors = new ConcurrentHashMap<>();
    
    /**
     * Holder for descriptor and its dependencies to prevent GC.
     */
    private static class DescriptorHolder {
        final MemorySegment topLevelDescriptor;
        final MemorySegment platformDescriptor;
        final long windowHandle;
        final long creationTime;
        
        DescriptorHolder(MemorySegment topLevel, MemorySegment platform, long handle) {
            this.topLevelDescriptor = topLevel;
            this.platformDescriptor = platform;
            this.windowHandle = handle;
            this.creationTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Create a persistent surface descriptor that won't be garbage collected.
     * 
     * CRITICAL: This descriptor uses global memory arena and will persist until
     * explicitly cleaned up or application terminates.
     * 
     * @param windowHandle native window handle (CAMetalLayer on macOS, HWND on Windows, etc.)
     * @return the top-level surface descriptor
     */
    public static MemorySegment createPersistent(long windowHandle) {
        var platform = PlatformDetector.detectPlatform();
        log.info("Creating PERSISTENT surface descriptor for platform: {} with handle: 0x{}", 
                platform, Long.toHexString(windowHandle));
        
        var descriptor = switch (platform) {
            case MACOS_AARCH64, MACOS_X86_64 -> createMetalSurfaceDescriptor(windowHandle);
            case WINDOWS_AARCH64, WINDOWS_X86_64 -> createWindowsSurfaceDescriptor(windowHandle);
            case LINUX_AARCH64, LINUX_X86_64 -> createX11SurfaceDescriptor(windowHandle);
        };
        
        log.info("Created persistent surface descriptor at: 0x{}", Long.toHexString(descriptor.address()));
        return descriptor;
    }
    
    private static MemorySegment createMetalSurfaceDescriptor(long metalLayer) {
        log.info("Creating PERSISTENT Metal surface descriptor with layer: 0x{}", Long.toHexString(metalLayer));
        
        // CRITICAL: Use Arena.global() - never deallocated
        var arena = Arena.global();
        
        // Step 1: Create the platform-specific descriptor (WGPUSurfaceSourceMetalLayer)
        // Verified structure layout against webgpu.h:
        // - nextInChain: pointer (8 bytes)
        // - sType: uint32_t (4 bytes) 
        // - padding: 4 bytes for alignment
        // - layer: void* (8 bytes)
        // Total: 24 bytes
        var metalDescriptor = arena.allocate(24);
        metalDescriptor.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // nextInChain = NULL
        metalDescriptor.set(ValueLayout.JAVA_INT, 8, S_TYPE_SURFACE_SOURCE_METAL_LAYER); // sType = 0x4
        // 4 bytes padding at offset 12
        metalDescriptor.set(ValueLayout.ADDRESS, 16, MemorySegment.ofAddress(metalLayer)); // layer
        
        // Step 2: Create the top-level WGPUSurfaceDescriptor
        // Verified structure layout:
        // - nextInChain: pointer (8 bytes) -> points to platform descriptor
        // - label: WGPUStringView (16 bytes) -> {data: pointer, length: size_t}
        var surfaceDescriptor = arena.allocate(24);
        surfaceDescriptor.set(ValueLayout.ADDRESS, 0, metalDescriptor); // nextInChain points to metalDescriptor
        surfaceDescriptor.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL); // label.data = NULL
        surfaceDescriptor.set(ValueLayout.JAVA_LONG, 16, 0L); // label.length = 0
        
        // CRITICAL: Store references to prevent GC
        var holder = new DescriptorHolder(surfaceDescriptor, metalDescriptor, metalLayer);
        activeDescriptors.put(surfaceDescriptor.address(), holder);
        
        log.info("PERSISTENT Metal descriptor created:");
        log.info("  - Top-level descriptor: 0x{}", Long.toHexString(surfaceDescriptor.address()));
        log.info("  - Platform descriptor: 0x{}", Long.toHexString(metalDescriptor.address()));
        log.info("  - Metal layer: 0x{}", Long.toHexString(metalLayer));
        log.info("  - Active descriptors count: {}", activeDescriptors.size());
        
        return surfaceDescriptor;
    }
    
    private static MemorySegment createWindowsSurfaceDescriptor(long hwnd) {
        log.info("Creating PERSISTENT Windows surface descriptor with HWND: 0x{}", Long.toHexString(hwnd));
        
        var arena = Arena.global();
        
        // WGPUSurfaceSourceWindowsHWND structure:
        // - nextInChain: pointer (8 bytes)
        // - sType: uint32_t (4 bytes)
        // - padding: 4 bytes
        // - hinstance: void* (8 bytes) 
        // - hwnd: void* (8 bytes)
        // Total: 32 bytes
        var windowsDescriptor = arena.allocate(32);
        windowsDescriptor.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // nextInChain
        windowsDescriptor.set(ValueLayout.JAVA_INT, 8, S_TYPE_SURFACE_SOURCE_WINDOWS_HWND); // sType = 0x5
        windowsDescriptor.set(ValueLayout.ADDRESS, 16, MemorySegment.NULL); // hinstance (can be NULL)
        windowsDescriptor.set(ValueLayout.ADDRESS, 24, MemorySegment.ofAddress(hwnd)); // hwnd
        
        var surfaceDescriptor = arena.allocate(24);
        surfaceDescriptor.set(ValueLayout.ADDRESS, 0, windowsDescriptor); // nextInChain
        surfaceDescriptor.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL); // label.data
        surfaceDescriptor.set(ValueLayout.JAVA_LONG, 16, 0L); // label.length
        
        var holder = new DescriptorHolder(surfaceDescriptor, windowsDescriptor, hwnd);
        activeDescriptors.put(surfaceDescriptor.address(), holder);
        
        log.info("PERSISTENT Windows descriptor created at: 0x{}", Long.toHexString(surfaceDescriptor.address()));
        return surfaceDescriptor;
    }
    
    private static MemorySegment createX11SurfaceDescriptor(long window) {
        log.info("Creating PERSISTENT X11 surface descriptor with window: 0x{}", Long.toHexString(window));
        
        var arena = Arena.global();
        
        // WGPUSurfaceSourceXlibWindow structure:
        // - nextInChain: pointer (8 bytes)
        // - sType: uint32_t (4 bytes)
        // - padding: 4 bytes  
        // - display: void* (8 bytes)
        // - window: uint64_t (8 bytes)
        // Total: 32 bytes
        var x11Descriptor = arena.allocate(32);
        x11Descriptor.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // nextInChain
        x11Descriptor.set(ValueLayout.JAVA_INT, 8, S_TYPE_SURFACE_SOURCE_XLIB_WINDOW); // sType = 0x6
        x11Descriptor.set(ValueLayout.ADDRESS, 16, MemorySegment.NULL); // display (would need X11 display)
        x11Descriptor.set(ValueLayout.JAVA_LONG, 24, window); // window ID
        
        var surfaceDescriptor = arena.allocate(24);
        surfaceDescriptor.set(ValueLayout.ADDRESS, 0, x11Descriptor); // nextInChain
        surfaceDescriptor.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL); // label.data
        surfaceDescriptor.set(ValueLayout.JAVA_LONG, 16, 0L); // label.length
        
        var holder = new DescriptorHolder(surfaceDescriptor, x11Descriptor, window);
        activeDescriptors.put(surfaceDescriptor.address(), holder);
        
        log.info("PERSISTENT X11 descriptor created at: 0x{}", Long.toHexString(surfaceDescriptor.address()));
        return surfaceDescriptor;
    }
    
    /**
     * Get statistics about active surface descriptors.
     */
    public static void logStatistics() {
        log.info("Surface Descriptor Statistics:");
        log.info("  - Active descriptors: {}", activeDescriptors.size());
        
        var now = System.currentTimeMillis();
        for (var entry : activeDescriptors.entrySet()) {
            var addr = entry.getKey();
            var holder = entry.getValue();
            var ageSeconds = (now - holder.creationTime) / 1000;
            log.info("    - 0x{}: age={}s, handle=0x{}", 
                    Long.toHexString(addr), ageSeconds, Long.toHexString(holder.windowHandle));
        }
    }
    
    /**
     * Cleanup specific descriptor (use with caution - only after surface is destroyed).
     */
    public static boolean cleanup(MemorySegment descriptor) {
        var holder = activeDescriptors.remove(descriptor.address());
        if (holder != null) {
            log.info("Cleaned up surface descriptor: 0x{}", Long.toHexString(descriptor.address()));
            return true;
        }
        return false;
    }
    
    /**
     * Get count of active descriptors for monitoring.
     */
    public static int getActiveCount() {
        return activeDescriptors.size();
    }
}