package com.hellblazer.luciferase.render.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.glfw.GLFWNativeCocoa.*;

/**
 * Phase 1 fix: Improved Metal layer helper with proper memory management.
 * 
 * Key improvements:
 * 1. Uses global arena for Objective-C string allocations 
 * 2. Enhanced validation of created objects
 * 3. Better error handling and logging
 * 4. Tracks created layers for debugging
 */
public class GLFWMetalHelperV2 {
    private static final Logger log = LoggerFactory.getLogger(GLFWMetalHelperV2.class);
    
    // Track created metal layers for debugging
    private static final Map<Long, LayerInfo> createdLayers = new ConcurrentHashMap<>();
    
    private static class LayerInfo {
        final long glfwWindow;
        final long nsWindow;
        final long creationTime;
        
        LayerInfo(long glfwWindow, long nsWindow) {
            this.glfwWindow = glfwWindow;
            this.nsWindow = nsWindow;
            this.creationTime = System.currentTimeMillis();
        }
    }
    
    // Objective-C runtime handles
    private static final SymbolLookup OBJC_LIB;
    private static final Linker LINKER;
    private static final MethodHandle objc_msgSend;
    private static final MethodHandle objc_msgSend_void;
    private static final MethodHandle objc_msgSend_bool;
    private static final MethodHandle objc_msgSend_uint;
    private static final MethodHandle objc_getClass;
    private static final MethodHandle sel_registerName;
    private static final MethodHandle object_getClass;
    
    static {
        try {
            OBJC_LIB = SymbolLookup.libraryLookup("libobjc.dylib", Arena.global());
            LINKER = Linker.nativeLinker();
            
            objc_getClass = LINKER.downcallHandle(
                OBJC_LIB.find("objc_getClass").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                
            sel_registerName = LINKER.downcallHandle(
                OBJC_LIB.find("sel_registerName").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            
            objc_msgSend = LINKER.downcallHandle(
                OBJC_LIB.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            
            objc_msgSend_void = LINKER.downcallHandle(
                OBJC_LIB.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            
            objc_msgSend_bool = LINKER.downcallHandle(
                OBJC_LIB.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));
            
            objc_msgSend_uint = LINKER.downcallHandle(
                OBJC_LIB.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
                
            object_getClass = LINKER.downcallHandle(
                OBJC_LIB.find("object_getClass").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                
            log.info("Objective-C runtime initialized successfully");
                
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize Objective-C runtime", e);
        }
    }
    
    /**
     * Create a CAMetalLayer and attach it to a GLFW window with enhanced validation.
     * 
     * This version uses global arena for string allocations to prevent premature deallocation.
     * 
     * @param glfwWindow the GLFW window handle
     * @return the CAMetalLayer pointer, or 0 on failure
     */
    public static long createMetalLayerForWindow(long glfwWindow) {
        log.info("Creating CAMetalLayer for GLFW window: 0x{}", Long.toHexString(glfwWindow));
        
        try {
            // Get the NSWindow from GLFW
            long nsWindow = glfwGetCocoaWindow(glfwWindow);
            if (nsWindow == 0) {
                log.error("Failed to get NSWindow from GLFW window");
                return 0;
            }
            
            log.info("Got NSWindow: 0x{}", Long.toHexString(nsWindow));
            
            // CRITICAL: Use global arena for Objective-C string allocations
            // This prevents strings from being deallocated during Objective-C calls
            var arena = Arena.global();
            
            // Get NSWindow's contentView
            var contentViewSel = (MemorySegment) sel_registerName.invoke(
                arena.allocateFrom("contentView"));
            var nsView = (MemorySegment) objc_msgSend.invoke(
                MemorySegment.ofAddress(nsWindow), contentViewSel);
            
            if (nsView == null || nsView.address() == 0) {
                log.error("Failed to get contentView from NSWindow");
                return 0;
            }
            
            log.debug("Got contentView: 0x{}", Long.toHexString(nsView.address()));
            
            // Create CAMetalLayer - equivalent to [CAMetalLayer layer]
            var caMetalLayerClass = (MemorySegment) objc_getClass.invoke(
                arena.allocateFrom("CAMetalLayer"));
            if (caMetalLayerClass == null || caMetalLayerClass.address() == 0) {
                log.error("Failed to get CAMetalLayer class");
                return 0;
            }
            
            var layerSel = (MemorySegment) sel_registerName.invoke(
                arena.allocateFrom("layer"));
            var metalLayer = (MemorySegment) objc_msgSend.invoke(
                caMetalLayerClass, layerSel);
            
            if (metalLayer == null || metalLayer.address() == 0) {
                log.error("Failed to create CAMetalLayer");
                return 0;
            }
            
            long layerPtr = metalLayer.address();
            log.info("Created CAMetalLayer: 0x{}", Long.toHexString(layerPtr));
            
            // Validate the created layer is actually a CAMetalLayer
            if (!validateMetalLayer(layerPtr)) {
                log.error("Created object is not a valid CAMetalLayer");
                return 0;
            }
            
            // Step 1: Set wantsLayer to YES first (CRITICAL ORDER)
            var setWantsLayerSel = (MemorySegment) sel_registerName.invoke(
                arena.allocateFrom("setWantsLayer:"));
            objc_msgSend_bool.invoke(nsView, setWantsLayerSel, (byte) 1); // YES = 1
            log.debug("Set wantsLayer to YES");
            
            // Step 2: Set the layer on the view  
            var setLayerSel = (MemorySegment) sel_registerName.invoke(
                arena.allocateFrom("setLayer:"));
            objc_msgSend_void.invoke(nsView, setLayerSel, metalLayer);
            log.debug("Attached layer to contentView");
            
            // Step 3: Configure the Metal layer for WebGPU compatibility
            configureMetalLayerForWebGPU(metalLayer, arena);
            
            // Track the created layer
            createdLayers.put(layerPtr, new LayerInfo(glfwWindow, nsWindow));
            
            log.info("Successfully created and configured CAMetalLayer: 0x{}", Long.toHexString(layerPtr));
            log.info("Active metal layers: {}", createdLayers.size());
            
            return layerPtr;
            
        } catch (Throwable e) {
            log.error("Failed to create Metal layer for window", e);
            return 0;
        }
    }
    
    /**
     * Validate that a pointer actually points to a CAMetalLayer.
     */
    private static boolean validateMetalLayer(long layerPtr) {
        try {
            var arena = Arena.global();
            
            // Get the class of the object
            var objectClass = (MemorySegment) object_getClass.invoke(
                MemorySegment.ofAddress(layerPtr));
            
            // Get the expected CAMetalLayer class
            var expectedClass = (MemorySegment) objc_getClass.invoke(
                arena.allocateFrom("CAMetalLayer"));
            
            boolean isMetalLayer = objectClass.address() == expectedClass.address();
            log.debug("Layer validation - object class: 0x{}, expected: 0x{}, valid: {}",
                    Long.toHexString(objectClass.address()),
                    Long.toHexString(expectedClass.address()),
                    isMetalLayer);
            
            return isMetalLayer;
            
        } catch (Throwable e) {
            log.error("Failed to validate metal layer", e);
            return false;
        }
    }
    
    /**
     * Configure CAMetalLayer for WebGPU compatibility.
     */
    private static void configureMetalLayerForWebGPU(MemorySegment metalLayer, Arena arena) {
        try {
            // Set pixelFormat to MTLPixelFormatBGRA8Unorm (80)
            // This matches the format expected by WebGPU
            var setPixelFormatSel = (MemorySegment) sel_registerName.invoke(
                arena.allocateFrom("setPixelFormat:"));
            objc_msgSend_uint.invoke(metalLayer, setPixelFormatSel, 80L); // MTLPixelFormatBGRA8Unorm
            log.debug("Set pixel format to BGRA8Unorm (80)");
            
            // Set framebufferOnly to NO for WebGPU compatibility
            // This allows WebGPU to read back from the surface
            var setFramebufferOnlySel = (MemorySegment) sel_registerName.invoke(
                arena.allocateFrom("setFramebufferOnly:"));
            objc_msgSend_bool.invoke(metalLayer, setFramebufferOnlySel, (byte) 0); // NO = 0
            log.debug("Set framebufferOnly to NO");
            
            // TODO: May need additional configuration based on WebGPU requirements:
            // - setDisplaySyncEnabled:
            // - setMaximumDrawableCount:
            // - setDrawableSize:
            
        } catch (Throwable e) {
            log.error("Failed to configure metal layer for WebGPU", e);
        }
    }
    
    /**
     * Log statistics about created metal layers.
     */
    public static void logStatistics() {
        log.info("Metal Layer Statistics:");
        log.info("  - Created layers: {}", createdLayers.size());
        
        var now = System.currentTimeMillis();
        for (var entry : createdLayers.entrySet()) {
            var layerPtr = entry.getKey();
            var info = entry.getValue();
            var ageSeconds = (now - info.creationTime) / 1000;
            log.info("    - Layer 0x{}: age={}s, GLFW=0x{}, NSWindow=0x{}", 
                    Long.toHexString(layerPtr), ageSeconds, 
                    Long.toHexString(info.glfwWindow), Long.toHexString(info.nsWindow));
        }
    }
    
    /**
     * Get count of created layers for monitoring.
     */
    public static int getCreatedLayerCount() {
        return createdLayers.size();
    }
}