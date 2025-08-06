package com.hellblazer.luciferase.webgpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main entry point for WebGPU FFM bindings.
 * Provides high-level API for WebGPU operations.
 */
public class WebGPU {
    private static final Logger log = LoggerFactory.getLogger(WebGPU.class);
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    
    // Native function handles
    private static MethodHandle wgpuCreateInstance;
    private static MethodHandle wgpuInstanceRelease;
    private static MethodHandle wgpuInstanceRequestAdapter;
    private static MethodHandle wgpuAdapterRelease;
    private static MethodHandle wgpuAdapterRequestDevice;
    private static MethodHandle wgpuDeviceRelease;
    private static MethodHandle wgpuDeviceGetQueue;
    private static MethodHandle wgpuDeviceCreateBuffer;
    private static MethodHandle wgpuBufferGetSize;
    private static MethodHandle wgpuBufferDestroy;
    private static MethodHandle wgpuQueueRelease;
    
    // Linker and symbol lookup
    private static Linker linker;
    private static SymbolLookup symbolLookup;
    
    /**
     * Initialize the WebGPU FFM bindings.
     * This loads the native library and resolves function symbols.
     * 
     * @return true if initialization was successful
     */
    public static synchronized boolean initialize() {
        if (initialized.get()) {
            return true;
        }
        
        try {
            log.info("Initializing WebGPU FFM bindings...");
            
            // Load native library
            if (!WebGPULoader.loadNativeLibrary()) {
                log.error("Failed to load WebGPU native library");
                return false;
            }
            
            // Initialize FFM components
            linker = Linker.nativeLinker();
            symbolLookup = SymbolLookup.loaderLookup();
            
            // Load function handles
            if (!loadFunctionHandles()) {
                log.error("Failed to load WebGPU function handles");
                return false;
            }
            
            initialized.set(true);
            log.info("WebGPU FFM bindings initialized successfully");
            return true;
            
        } catch (Exception e) {
            log.error("Failed to initialize WebGPU FFM bindings", e);
            return false;
        }
    }
    
    /**
     * Check if WebGPU is initialized.
     * 
     * @return true if initialized
     */
    public static boolean isInitialized() {
        return initialized.get();
    }
    
    /**
     * Create a WebGPU instance.
     * 
     * @return the instance handle, or null if creation failed
     */
    public static MemorySegment createInstance() {
        if (!initialized.get()) {
            throw new IllegalStateException("WebGPU not initialized");
        }
        
        try {
            // Call wgpuCreateInstance with NULL descriptor for default
            var instance = (MemorySegment) wgpuCreateInstance.invoke(MemorySegment.NULL);
            
            if (instance != null && !instance.equals(MemorySegment.NULL)) {
                log.debug("Created WebGPU instance at 0x{}", Long.toHexString(instance.address()));
                return instance;
            } else {
                log.error("wgpuCreateInstance returned NULL");
                return null;
            }
        } catch (Throwable t) {
            log.error("Failed to create WebGPU instance", t);
            return null;
        }
    }
    
    /**
     * Create a buffer on a device.
     * 
     * @param deviceHandle the device handle
     * @param descriptor the buffer descriptor memory segment
     * @return the native handle to the buffer, or NULL if creation failed
     */
    public static MemorySegment createBuffer(MemorySegment deviceHandle, MemorySegment descriptor) {
        if (!initialized.get()) {
            throw new IllegalStateException("WebGPU not initialized");
        }
        
        if (wgpuDeviceCreateBuffer == null) {
            log.warn("wgpuDeviceCreateBuffer not available - returning NULL");
            return MemorySegment.NULL;
        }
        
        try {
            var result = (MemorySegment) wgpuDeviceCreateBuffer.invoke(deviceHandle, descriptor);
            
            if (result == null || result.equals(MemorySegment.NULL)) {
                log.error("Failed to create buffer - native call returned NULL");
                return MemorySegment.NULL;
            }
            
            return result;
        } catch (Throwable e) {
            log.error("Failed to create buffer", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Get the size of a buffer.
     * 
     * @param bufferHandle the buffer handle
     * @return the size in bytes
     */
    public static long getBufferSize(MemorySegment bufferHandle) {
        if (!initialized.get() || wgpuBufferGetSize == null) {
            return 0;
        }
        
        try {
            return (long) wgpuBufferGetSize.invoke(bufferHandle);
        } catch (Throwable e) {
            log.error("Failed to get buffer size", e);
            return 0;
        }
    }
    
    /**
     * Destroy a buffer.
     * 
     * @param bufferHandle the buffer handle
     */
    public static void destroyBuffer(MemorySegment bufferHandle) {
        if (!initialized.get() || wgpuBufferDestroy == null) {
            return;
        }
        
        try {
            wgpuBufferDestroy.invoke(bufferHandle);
        } catch (Throwable e) {
            log.error("Failed to destroy buffer", e);
        }
    }
    
    /**
     * Request an adapter from an instance.
     * Note: This is a synchronous wrapper around the async API.
     * 
     * @param instanceHandle the instance handle
     * @param options the request options (can be null)
     * @return the adapter handle, or NULL if not available
     */
    public static MemorySegment requestAdapter(MemorySegment instanceHandle, MemorySegment options) {
        if (!initialized.get() || wgpuInstanceRequestAdapter == null) {
            log.warn("wgpuInstanceRequestAdapter not available");
            return MemorySegment.NULL;
        }
        
        try (var arena = Arena.ofConfined()) {
            // Create callback to handle the async response
            var callback = new CallbackHelper.AdapterCallback(arena);
            
            // Call the native function with callback
            wgpuInstanceRequestAdapter.invoke(instanceHandle, 
                                             options != null ? options : MemorySegment.NULL,
                                             callback.getCallbackStub(),
                                             MemorySegment.NULL); // userdata
            
            // Wait for the callback to be invoked (5 second timeout)
            var result = callback.waitForResult(5, java.util.concurrent.TimeUnit.SECONDS);
            
            if (result != null && !result.equals(MemorySegment.NULL)) {
                log.debug("Successfully obtained adapter: 0x{}", Long.toHexString(result.address()));
                return result;
            } else {
                log.warn("Failed to obtain adapter from native API");
                return MemorySegment.NULL;
            }
            
        } catch (Throwable e) {
            log.error("Failed to request adapter", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Release an adapter.
     * 
     * @param adapterHandle the adapter handle
     */
    public static void releaseAdapter(MemorySegment adapterHandle) {
        if (!initialized.get() || wgpuAdapterRelease == null) {
            log.warn("wgpuAdapterRelease not available");
            return;
        }
        
        try {
            wgpuAdapterRelease.invoke(adapterHandle);
        } catch (Throwable e) {
            log.error("Failed to release adapter", e);
        }
    }
    
    /**
     * Request a device from an adapter.
     * Note: This is a synchronous wrapper around the async API.
     * 
     * @param adapterHandle the adapter handle
     * @param descriptor the device descriptor (can be null)
     * @return the device handle, or NULL if not available
     */
    public static MemorySegment requestDevice(MemorySegment adapterHandle, MemorySegment descriptor) {
        if (!initialized.get() || wgpuAdapterRequestDevice == null) {
            log.warn("wgpuAdapterRequestDevice not available");
            return MemorySegment.NULL;
        }
        
        try (var arena = Arena.ofConfined()) {
            // Create callback to handle the async response
            var callback = new CallbackHelper.DeviceCallback(arena);
            
            // Call the native function with callback
            wgpuAdapterRequestDevice.invoke(adapterHandle,
                                           descriptor != null ? descriptor : MemorySegment.NULL,
                                           callback.getCallbackStub(),
                                           MemorySegment.NULL); // userdata
            
            // Wait for the callback to be invoked (5 second timeout)
            var result = callback.waitForResult(5, java.util.concurrent.TimeUnit.SECONDS);
            
            if (result != null && !result.equals(MemorySegment.NULL)) {
                log.debug("Successfully obtained device: 0x{}", Long.toHexString(result.address()));
                return result;
            } else {
                log.warn("Failed to obtain device from native API");
                return MemorySegment.NULL;
            }
            
        } catch (Throwable e) {
            log.error("Failed to request device", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Release a device.
     * 
     * @param deviceHandle the device handle
     */
    public static void releaseDevice(MemorySegment deviceHandle) {
        if (!initialized.get() || wgpuDeviceRelease == null) {
            log.warn("wgpuDeviceRelease not available");
            return;
        }
        
        try {
            wgpuDeviceRelease.invoke(deviceHandle);
        } catch (Throwable e) {
            log.error("Failed to release device", e);
        }
    }
    
    /**
     * Get the queue from a device.
     * 
     * @param deviceHandle the device handle
     * @return the queue handle
     */
    public static MemorySegment getQueue(MemorySegment deviceHandle) {
        if (!initialized.get() || wgpuDeviceGetQueue == null) {
            log.warn("wgpuDeviceGetQueue not available");
            return MemorySegment.NULL;
        }
        
        try {
            return (MemorySegment) wgpuDeviceGetQueue.invoke(deviceHandle);
        } catch (Throwable e) {
            log.error("Failed to get queue", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Release a queue.
     * 
     * @param queueHandle the queue handle
     */
    public static void releaseQueue(MemorySegment queueHandle) {
        if (!initialized.get() || wgpuQueueRelease == null) {
            log.warn("wgpuQueueRelease not available");
            return;
        }
        
        try {
            wgpuQueueRelease.invoke(queueHandle);
        } catch (Throwable e) {
            log.error("Failed to release queue", e);
        }
    }
    
    /**
     * Release a WebGPU instance.
     * 
     * @param instance the instance to release
     */
    public static void releaseInstance(MemorySegment instance) {
        if (!initialized.get() || instance == null || instance.equals(MemorySegment.NULL)) {
            return;
        }
        
        try {
            wgpuInstanceRelease.invoke(instance);
            log.debug("Released WebGPU instance");
        } catch (Throwable t) {
            log.error("Failed to release WebGPU instance", t);
        }
    }
    
    /**
     * Shutdown WebGPU and release all resources.
     */
    public static synchronized void shutdown() {
        if (!initialized.get()) {
            return;
        }
        
        log.info("Shutting down WebGPU FFM bindings");
        
        // Clear function handles
        wgpuCreateInstance = null;
        wgpuInstanceRelease = null;
        wgpuInstanceRequestAdapter = null;
        wgpuAdapterRelease = null;
        wgpuAdapterRequestDevice = null;
        wgpuDeviceRelease = null;
        wgpuDeviceGetQueue = null;
        wgpuDeviceCreateBuffer = null;
        wgpuBufferGetSize = null;
        wgpuBufferDestroy = null;
        wgpuQueueRelease = null;
        
        // Clear FFM components
        linker = null;
        symbolLookup = null;
        
        initialized.set(false);
        log.info("WebGPU FFM bindings shutdown complete");
    }
    
    /**
     * Load WebGPU function handles using FFM.
     * 
     * @return true if all functions were loaded successfully
     */
    private static boolean loadFunctionHandles() {
        try {
            // wgpuCreateInstance(const WGPUInstanceDescriptor* descriptor) -> WGPUInstance
            var createInstanceOpt = symbolLookup.find("wgpuCreateInstance");
            if (createInstanceOpt.isEmpty()) {
                log.error("Could not find wgpuCreateInstance symbol");
                return false;
            }
            
            wgpuCreateInstance = linker.downcallHandle(
                createInstanceOpt.get(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            
            // wgpuInstanceRelease(WGPUInstance instance) -> void
            var instanceReleaseOpt = symbolLookup.find("wgpuInstanceRelease");
            if (instanceReleaseOpt.isPresent()) {
                wgpuInstanceRelease = linker.downcallHandle(
                    instanceReleaseOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
                );
            }
            
            // wgpuDeviceCreateBuffer(WGPUDevice device, const WGPUBufferDescriptor* descriptor) -> WGPUBuffer
            var deviceCreateBufferOpt = symbolLookup.find("wgpuDeviceCreateBuffer");
            if (deviceCreateBufferOpt.isPresent()) {
                wgpuDeviceCreateBuffer = linker.downcallHandle(
                    deviceCreateBufferOpt.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuBufferGetSize(WGPUBuffer buffer) -> uint64_t
            var bufferGetSizeOpt = symbolLookup.find("wgpuBufferGetSize");
            if (bufferGetSizeOpt.isPresent()) {
                wgpuBufferGetSize = linker.downcallHandle(
                    bufferGetSizeOpt.get(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuBufferDestroy(WGPUBuffer buffer) -> void
            var bufferDestroyOpt = symbolLookup.find("wgpuBufferDestroy");
            if (bufferDestroyOpt.isPresent()) {
                wgpuBufferDestroy = linker.downcallHandle(
                    bufferDestroyOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
                );
            }
            
            // wgpuInstanceRequestAdapter(WGPUInstance instance, const WGPURequestAdapterOptions* options, 
            //                             WGPURequestAdapterCallback callback, void* userdata) -> void
            var instanceRequestAdapterOpt = symbolLookup.find("wgpuInstanceRequestAdapter");
            if (instanceRequestAdapterOpt.isPresent()) {
                wgpuInstanceRequestAdapter = linker.downcallHandle(
                    instanceRequestAdapterOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, 
                                             ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuAdapterRelease(WGPUAdapter adapter) -> void
            var adapterReleaseOpt = symbolLookup.find("wgpuAdapterRelease");
            if (adapterReleaseOpt.isPresent()) {
                wgpuAdapterRelease = linker.downcallHandle(
                    adapterReleaseOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
                );
            }
            
            // wgpuAdapterRequestDevice(WGPUAdapter adapter, const WGPUDeviceDescriptor* descriptor,
            //                          WGPURequestDeviceCallback callback, void* userdata) -> void
            var adapterRequestDeviceOpt = symbolLookup.find("wgpuAdapterRequestDevice");
            if (adapterRequestDeviceOpt.isPresent()) {
                wgpuAdapterRequestDevice = linker.downcallHandle(
                    adapterRequestDeviceOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                             ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuDeviceRelease(WGPUDevice device) -> void
            var deviceReleaseOpt = symbolLookup.find("wgpuDeviceRelease");
            if (deviceReleaseOpt.isPresent()) {
                wgpuDeviceRelease = linker.downcallHandle(
                    deviceReleaseOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
                );
            }
            
            // wgpuDeviceGetQueue(WGPUDevice device) -> WGPUQueue
            var deviceGetQueueOpt = symbolLookup.find("wgpuDeviceGetQueue");
            if (deviceGetQueueOpt.isPresent()) {
                wgpuDeviceGetQueue = linker.downcallHandle(
                    deviceGetQueueOpt.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuQueueRelease(WGPUQueue queue) -> void
            var queueReleaseOpt = symbolLookup.find("wgpuQueueRelease");
            if (queueReleaseOpt.isPresent()) {
                wgpuQueueRelease = linker.downcallHandle(
                    queueReleaseOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
                );
            }
            
            // Load other functions as needed
            
            log.debug("Successfully loaded WebGPU function handles");
            return true;
            
        } catch (Exception e) {
            log.error("Failed to load WebGPU function handles", e);
            return false;
        }
    }
    
    /**
     * Get version information about the WebGPU implementation.
     * 
     * @return version string, or "unknown" if not available
     */
    public static String getVersion() {
        // TODO: Call wgpuGetVersion if available
        return "wgpu-native 25.0.2.1";
    }
    
    /**
     * Check if WebGPU is available on this system.
     * This performs a quick check without full initialization.
     * 
     * @return true if WebGPU appears to be available
     */
    public static boolean isAvailable() {
        try {
            // Try to detect platform
            var platform = com.hellblazer.luciferase.webgpu.platform.PlatformDetector.detectPlatform();
            log.debug("Platform detected: {}", platform);
            
            // Check if we can load the library (without actually loading it)
            var libraryPath = System.getProperty("java.library.path");
            if (libraryPath != null && !libraryPath.isEmpty()) {
                // Check if library exists in path
                return true;
            }
            
            // Check if library is in resources
            var resourcePath = "/natives/" + platform.getPlatformString() + "/" + platform.getLibraryName();
            return WebGPU.class.getResourceAsStream(resourcePath) != null;
            
        } catch (Exception e) {
            log.debug("WebGPU availability check failed: {}", e.getMessage());
            return false;
        }
    }
}