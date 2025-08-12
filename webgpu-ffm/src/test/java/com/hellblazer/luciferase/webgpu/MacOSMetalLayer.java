package com.hellblazer.luciferase.webgpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Native macOS Metal layer creation for WebGPU surface.
 * Uses Foreign Function & Memory API to call macOS APIs directly.
 */
public class MacOSMetalLayer {
    private static final Logger log = LoggerFactory.getLogger(MacOSMetalLayer.class);
    
    // macOS framework libraries
    private static final SymbolLookup COCOA;
    private static final SymbolLookup QUARTZCORE;
    
    // Objective-C runtime functions
    private static final MethodHandle objc_getClass;
    private static final MethodHandle objc_msgSend;
    private static final MethodHandle sel_registerName;
    
    // CAMetalLayer methods
    private static final MemorySegment CAMetalLayer_class;
    private static final MemorySegment alloc_selector;
    private static final MemorySegment init_selector;
    private static final MemorySegment layer_selector;
    
    // NSView methods for layer attachment
    private static final MemorySegment setLayer_selector;
    private static final MemorySegment setWantsLayer_selector;
    private static final MemorySegment contentView_selector;
    
    static {
        try {
            // Load Objective-C runtime
            var objcLib = SymbolLookup.libraryLookup("libobjc.dylib", Arena.global());
            
            // Load frameworks
            COCOA = SymbolLookup.libraryLookup("/System/Library/Frameworks/Cocoa.framework/Cocoa", Arena.global());
            QUARTZCORE = SymbolLookup.libraryLookup("/System/Library/Frameworks/QuartzCore.framework/QuartzCore", Arena.global());
            
            // Get Objective-C runtime functions
            var linker = Linker.nativeLinker();
            
            objc_getClass = linker.downcallHandle(
                objcLib.find("objc_getClass").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                
            sel_registerName = linker.downcallHandle(
                objcLib.find("sel_registerName").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                
            objc_msgSend = linker.downcallHandle(
                objcLib.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            
            // Get CAMetalLayer class
            try (var arena = Arena.ofConfined()) {
                var className = arena.allocateFrom("CAMetalLayer");
                CAMetalLayer_class = (MemorySegment) objc_getClass.invoke(className);
                
                // Register selectors
                alloc_selector = (MemorySegment) sel_registerName.invoke(arena.allocateFrom("alloc"));
                init_selector = (MemorySegment) sel_registerName.invoke(arena.allocateFrom("init"));
                layer_selector = (MemorySegment) sel_registerName.invoke(arena.allocateFrom("layer"));
                
                // Register NSView selectors for layer attachment
                setLayer_selector = (MemorySegment) sel_registerName.invoke(arena.allocateFrom("setLayer:"));
                setWantsLayer_selector = (MemorySegment) sel_registerName.invoke(arena.allocateFrom("setWantsLayer:"));
                contentView_selector = (MemorySegment) sel_registerName.invoke(arena.allocateFrom("contentView"));
            }
            
            log.debug("macOS Metal layer support initialized");
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize macOS Metal layer support", e);
        }
    }
    
    /**
     * Create a CAMetalLayer for WebGPU rendering.
     * 
     * @return pointer to the CAMetalLayer, or 0 on failure
     */
    public static long createMetalLayer() {
        try {
            // Create CAMetalLayer using [CAMetalLayer layer]
            var metalLayer = (MemorySegment) objc_msgSend.invoke(CAMetalLayer_class, layer_selector);
            
            if (metalLayer == null || metalLayer.address() == 0) {
                log.error("Failed to create CAMetalLayer");
                return 0;
            }
            
            long layerPtr = metalLayer.address();
            log.info("Created CAMetalLayer at 0x{}", Long.toHexString(layerPtr));
            return layerPtr;
            
        } catch (Throwable e) {
            log.error("Failed to create Metal layer", e);
            return 0;
        }
    }
    
    /**
     * Associate a Metal layer with a GLFW window.
     * Gets the NSView from the NSWindow and attaches the Metal layer.
     * 
     * @param metalLayer the Metal layer pointer
     * @param nsWindow the NSWindow pointer from GLFW
     * @return true if successful
     */
    public static boolean attachToWindow(long metalLayer, long nsWindow) {
        if (metalLayer == 0 || nsWindow == 0) {
            log.error("Invalid parameters: metalLayer={}, nsWindow={}", metalLayer, nsWindow);
            return false;
        }
        
        try {
            var linker = Linker.nativeLinker();
            
            // Create method handles for objc_msgSend with different signatures
            var msgSendPtr = linker.downcallHandle(
                SymbolLookup.loaderLookup().find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                
            var msgSendBool = linker.downcallHandle(
                SymbolLookup.loaderLookup().find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));
                
            var msgSendSetLayer = linker.downcallHandle(
                SymbolLookup.loaderLookup().find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            
            // Get the content view from the window: [nsWindow contentView]
            var nsWindowSegment = MemorySegment.ofAddress(nsWindow);
            var contentView = (MemorySegment) msgSendPtr.invoke(nsWindowSegment, contentView_selector);
            
            if (contentView == null || contentView.address() == 0) {
                log.error("Failed to get content view from window");
                return false;
            }
            
            log.debug("Got content view: 0x{}", Long.toHexString(contentView.address()));
            
            // Set wantsLayer to YES: [contentView setWantsLayer:YES]
            msgSendBool.invoke(contentView, setWantsLayer_selector, (byte)1);
            log.debug("Set wantsLayer to YES");
            
            // Set the Metal layer: [contentView setLayer:metalLayer]
            var metalLayerSegment = MemorySegment.ofAddress(metalLayer);
            msgSendSetLayer.invoke(contentView, setLayer_selector, metalLayerSegment);
            
            log.info("Attached Metal layer 0x{} to content view 0x{}", 
                Long.toHexString(metalLayer), Long.toHexString(contentView.address()));
            return true;
            
        } catch (Throwable e) {
            log.error("Failed to attach Metal layer to window", e);
            return false;
        }
    }
}