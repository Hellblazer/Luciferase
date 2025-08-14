package com.hellblazer.luciferase.webgpu.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;

import static com.hellblazer.luciferase.webgpu.core.WebGPUTypes.*;

/**
 * Minimal WebGPU native function bindings using FFM.
 * 
 * Based on patterns from webgpu-java and wgpu-panama references.
 * Only includes essential functions for basic initialization.
 */
public class WebGPUNative {
    private static final Logger log = LoggerFactory.getLogger(WebGPUNative.class);
    
    // Native library handle
    private static SymbolLookup LIBRARY;
    private static final Linker LINKER = Linker.nativeLinker();
    
    // Function handles - only the essentials
    private static MethodHandle wgpuCreateInstance;
    private static MethodHandle wgpuInstanceRequestAdapter;
    private static MethodHandle wgpuAdapterRequestDevice;
    private static MethodHandle wgpuInstanceCreateSurface;
    private static MethodHandle wgpuSurfaceConfigure;
    private static MethodHandle wgpuSurfaceGetCapabilities;
    private static MethodHandle wgpuSurfaceGetCurrentTexture;
    private static MethodHandle wgpuSurfacePresent;
    private static MethodHandle wgpuDeviceGetQueue;
    
    // Cleanup functions
    private static MethodHandle wgpuInstanceRelease;
    private static MethodHandle wgpuAdapterRelease;
    private static MethodHandle wgpuDeviceRelease;
    private static MethodHandle wgpuSurfaceRelease;
    private static MethodHandle wgpuQueueRelease;
    
    // Initialize flag
    private static boolean initialized = false;
    private static String initError = null;
    
    /**
     * Initialize the WebGPU native library and function handles.
     * Must be called before any other functions.
     */
    public static synchronized boolean initialize() {
        if (initialized) {
            return true;
        }
        
        try {
            // Load the library
            if (!loadLibrary()) {
                return false;
            }
            
            // Bind essential functions
            bindFunctions();
            
            initialized = true;
            log.info("WebGPU native functions initialized successfully");
            return true;
            
        } catch (Exception e) {
            initError = e.getMessage();
            log.error("Failed to initialize WebGPU native functions", e);
            return false;
        }
    }
    
    private static boolean loadLibrary() {
        try {
            // Try system library first
            try {
                System.loadLibrary("wgpu_native");
                LIBRARY = SymbolLookup.loaderLookup();
                log.info("Loaded wgpu_native from system path");
                return true;
            } catch (UnsatisfiedLinkError e) {
                log.debug("Could not load wgpu_native from system, trying alternatives");
            }
            
            // Try common paths for macOS
            var paths = new String[] {
                "/usr/local/lib/libwgpu_native.dylib",
                "/opt/homebrew/lib/libwgpu_native.dylib",
                "./libwgpu_native.dylib"
            };
            
            for (var path : paths) {
                try {
                    System.load(path);
                    LIBRARY = SymbolLookup.loaderLookup();
                    log.info("Loaded wgpu_native from: {}", path);
                    return true;
                } catch (UnsatisfiedLinkError e) {
                    log.debug("Not found at: {}", path);
                }
            }
            
            log.error("Could not find wgpu_native library");
            return false;
            
        } catch (Exception e) {
            log.error("Failed to load native library", e);
            return false;
        }
    }
    
    private static void bindFunctions() {
        // Instance creation
        wgpuCreateInstance = bindFunction("wgpuCreateInstance",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        
        // Adapter request - with callback
        wgpuInstanceRequestAdapter = bindFunction("wgpuInstanceRequestAdapter",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,  // instance
                ValueLayout.ADDRESS,  // options
                ValueLayout.ADDRESS,  // callback
                ValueLayout.ADDRESS   // userdata
            ));
        
        // Device request - with callback
        wgpuAdapterRequestDevice = bindFunction("wgpuAdapterRequestDevice",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,  // adapter
                ValueLayout.ADDRESS,  // descriptor
                ValueLayout.ADDRESS,  // callback
                ValueLayout.ADDRESS   // userdata
            ));
        
        // Surface operations
        wgpuInstanceCreateSurface = bindFunction("wgpuInstanceCreateSurface",
            FunctionDescriptor.of(ValueLayout.ADDRESS, 
                ValueLayout.ADDRESS,  // instance
                ValueLayout.ADDRESS   // descriptor
            ));
        
        wgpuSurfaceConfigure = bindFunction("wgpuSurfaceConfigure",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,  // surface
                ValueLayout.ADDRESS   // config
            ));
        
        wgpuSurfaceGetCapabilities = bindFunction("wgpuSurfaceGetCapabilities",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,  // surface
                ValueLayout.ADDRESS,  // adapter
                ValueLayout.ADDRESS   // capabilities
            ));
        
        wgpuSurfaceGetCurrentTexture = bindFunction("wgpuSurfaceGetCurrentTexture",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,  // surface
                ValueLayout.ADDRESS   // surfaceTexture
            ));
        
        wgpuSurfacePresent = bindFunction("wgpuSurfacePresent",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        
        // Queue
        wgpuDeviceGetQueue = bindFunction("wgpuDeviceGetQueue",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        
        // Release functions
        wgpuInstanceRelease = bindFunction("wgpuInstanceRelease",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        wgpuAdapterRelease = bindFunction("wgpuAdapterRelease",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        wgpuDeviceRelease = bindFunction("wgpuDeviceRelease",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        wgpuSurfaceRelease = bindFunction("wgpuSurfaceRelease",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        wgpuQueueRelease = bindFunction("wgpuQueueRelease",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }
    
    private static MethodHandle bindFunction(String name, FunctionDescriptor descriptor) {
        Optional<MemorySegment> symbol = LIBRARY.find(name);
        if (symbol.isEmpty()) {
            throw new RuntimeException("Failed to find symbol: " + name);
        }
        return LINKER.downcallHandle(symbol.get(), descriptor);
    }
    
    // Public API - minimal set of functions
    
    public static WGPUInstance createInstance(Arena arena) {
        if (!initialized) {
            throw new IllegalStateException("WebGPU not initialized");
        }
        
        try {
            // Pass NULL for descriptor - wgpu accepts nullable descriptor
            var handle = (MemorySegment) wgpuCreateInstance.invoke(MemorySegment.NULL);
            return new WGPUInstance(handle);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create instance", e);
        }
    }
    
    public static void requestAdapter(WGPUInstance instance, WGPUSurface compatibleSurface,
                                     Arena arena, AdapterCallback callback) {
        if (!initialized) {
            throw new IllegalStateException("WebGPU not initialized");
        }
        
        try {
            // Create options
            var options = arena.allocate(Layouts.REQUEST_ADAPTER_OPTIONS);
            options.fill((byte) 0);
            
            if (compatibleSurface != null) {
                Handles.setCompatibleSurface(options, compatibleSurface.handle());
            }
            Handles.setPowerPreference(options, PowerPreference.HighPerformance);
            Handles.setBackendType(options, BackendType.Metal); // For macOS
            
            // Create callback stub
            var callbackStub = createAdapterCallback(arena, callback);
            
            wgpuInstanceRequestAdapter.invoke(
                instance.handle(),
                options,
                callbackStub,
                MemorySegment.NULL
            );
        } catch (Throwable e) {
            throw new RuntimeException("Failed to request adapter", e);
        }
    }
    
    public static void requestDevice(WGPUAdapter adapter, Arena arena, DeviceCallback callback) {
        if (!initialized) {
            throw new IllegalStateException("WebGPU not initialized");
        }
        
        try {
            // Create empty descriptor
            var descriptor = arena.allocate(Layouts.DEVICE_DESCRIPTOR);
            descriptor.fill((byte) 0);
            
            // Create callback stub
            var callbackStub = createDeviceCallback(arena, callback);
            
            wgpuAdapterRequestDevice.invoke(
                adapter.handle(),
                descriptor,
                callbackStub,
                MemorySegment.NULL
            );
        } catch (Throwable e) {
            throw new RuntimeException("Failed to request device", e);
        }
    }
    
    public static WGPUQueue getQueue(WGPUDevice device) {
        if (!initialized) {
            throw new IllegalStateException("WebGPU not initialized");
        }
        
        try {
            var handle = (MemorySegment) wgpuDeviceGetQueue.invoke(device.handle());
            return new WGPUQueue(handle);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get queue", e);
        }
    }
    
    // Callback interfaces
    public interface AdapterCallback {
        void onAdapterReceived(int status, WGPUAdapter adapter);
    }
    
    public interface DeviceCallback {
        void onDeviceReceived(int status, WGPUDevice device);
    }
    
    // Callback stub creation
    private static MemorySegment createAdapterCallback(Arena arena, AdapterCallback callback) {
        var descriptor = FunctionDescriptor.ofVoid(
            ValueLayout.JAVA_INT,     // status
            ValueLayout.ADDRESS,      // adapter
            ValueLayout.ADDRESS,      // message
            ValueLayout.ADDRESS       // userdata
        );
        
        try {
            var methodHandle = MethodHandles.lookup()
                .findVirtual(AdapterCallbackImpl.class, "invoke",
                    MethodType.methodType(void.class, int.class, MemorySegment.class, 
                                         MemorySegment.class, MemorySegment.class));
            
            var impl = new AdapterCallbackImpl(callback);
            var bound = methodHandle.bindTo(impl);
            
            return LINKER.upcallStub(bound, descriptor, arena);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create adapter callback", e);
        }
    }
    
    private static MemorySegment createDeviceCallback(Arena arena, DeviceCallback callback) {
        var descriptor = FunctionDescriptor.ofVoid(
            ValueLayout.JAVA_INT,     // status
            ValueLayout.ADDRESS,      // device
            ValueLayout.ADDRESS,      // message
            ValueLayout.ADDRESS       // userdata
        );
        
        try {
            var methodHandle = MethodHandles.lookup()
                .findVirtual(DeviceCallbackImpl.class, "invoke",
                    MethodType.methodType(void.class, int.class, MemorySegment.class,
                                         MemorySegment.class, MemorySegment.class));
            
            var impl = new DeviceCallbackImpl(callback);
            var bound = methodHandle.bindTo(impl);
            
            return LINKER.upcallStub(bound, descriptor, arena);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create device callback", e);
        }
    }
    
    // Callback implementation classes
    private static class AdapterCallbackImpl {
        private final AdapterCallback callback;
        
        AdapterCallbackImpl(AdapterCallback callback) {
            this.callback = callback;
        }
        
        public void invoke(int status, MemorySegment adapter, MemorySegment message, MemorySegment userdata) {
            callback.onAdapterReceived(status, new WGPUAdapter(adapter));
        }
    }
    
    private static class DeviceCallbackImpl {
        private final DeviceCallback callback;
        
        DeviceCallbackImpl(DeviceCallback callback) {
            this.callback = callback;
        }
        
        public void invoke(int status, MemorySegment device, MemorySegment message, MemorySegment userdata) {
            callback.onDeviceReceived(status, new WGPUDevice(device));
        }
    }
    
    // Cleanup methods
    public static void release(WGPUInstance instance) {
        if (instance != null && !instance.isNull()) {
            try {
                wgpuInstanceRelease.invoke(instance.handle());
            } catch (Throwable e) {
                log.error("Failed to release instance", e);
            }
        }
    }
    
    public static void release(WGPUAdapter adapter) {
        if (adapter != null && !adapter.isNull()) {
            try {
                wgpuAdapterRelease.invoke(adapter.handle());
            } catch (Throwable e) {
                log.error("Failed to release adapter", e);
            }
        }
    }
    
    public static void release(WGPUDevice device) {
        if (device != null && !device.isNull()) {
            try {
                wgpuDeviceRelease.invoke(device.handle());
            } catch (Throwable e) {
                log.error("Failed to release device", e);
            }
        }
    }
    
    public static void release(WGPUSurface surface) {
        if (surface != null && !surface.isNull()) {
            try {
                wgpuSurfaceRelease.invoke(surface.handle());
            } catch (Throwable e) {
                log.error("Failed to release surface", e);
            }
        }
    }
    
    public static void release(WGPUQueue queue) {
        if (queue != null && !queue.isNull()) {
            try {
                wgpuQueueRelease.invoke(queue.handle());
            } catch (Throwable e) {
                log.error("Failed to release queue", e);
            }
        }
    }
    
    public static boolean isInitialized() {
        return initialized;
    }
    
    public static String getInitError() {
        return initError;
    }
}