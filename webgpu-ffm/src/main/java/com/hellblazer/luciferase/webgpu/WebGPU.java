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
    private static MethodHandle wgpuInstanceEnumerateAdapters;
    private static MethodHandle wgpuInstanceRequestAdapter;
    private static MethodHandle wgpuAdapterRelease;
    private static MethodHandle wgpuAdapterRequestDevice;
    private static MethodHandle wgpuDeviceRelease;
    private static MethodHandle wgpuDeviceGetQueue;
    private static MethodHandle wgpuDeviceCreateBuffer;
    private static MethodHandle wgpuBufferGetSize;
    private static MethodHandle wgpuBufferDestroy;
    private static MethodHandle wgpuQueueRelease;
    private static MethodHandle wgpuQueueSubmit;
    private static MethodHandle wgpuQueueWriteBuffer;
    private static MethodHandle wgpuDeviceCreateCommandEncoder;
    private static MethodHandle wgpuCommandEncoderBeginComputePass;
    private static MethodHandle wgpuCommandEncoderFinish;
    private static MethodHandle wgpuCommandEncoderRelease;
    private static MethodHandle wgpuComputePassEncoderSetPipeline;
    private static MethodHandle wgpuComputePassEncoderSetBindGroup;
    private static MethodHandle wgpuComputePassEncoderDispatchWorkgroups;
    private static MethodHandle wgpuComputePassEncoderEnd;
    private static MethodHandle wgpuComputePassEncoderRelease;
    private static MethodHandle wgpuDeviceCreateShaderModule;
    private static MethodHandle wgpuDeviceCreateComputePipeline;
    private static MethodHandle wgpuDeviceCreateBindGroupLayout;
    private static MethodHandle wgpuDeviceCreateBindGroup;
    private static MethodHandle wgpuDeviceCreatePipelineLayout;
    private static MethodHandle wgpuDeviceCreateTexture;
    private static MethodHandle wgpuDeviceCreateSampler;
    private static MethodHandle wgpuTextureRelease;
    private static MethodHandle wgpuSamplerRelease;
    private static MethodHandle wgpuTextureCreateView;
    
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
        // Check if already initialized AND handles are valid
        // This handles the case where we're in a forked JVM process
        // where the static flag is true but handles are null
        if (initialized.get() && wgpuCreateInstance != null) {
            log.debug("WebGPU already initialized with valid handles");
            return true;
        }
        
        // If we get here, either not initialized or handles are invalid
        // Force re-initialization in case we're in a forked process
        if (initialized.get() && wgpuCreateInstance == null) {
            log.info("WebGPU initialized flag set but handles invalid - re-initializing (likely forked JVM)");
            initialized.set(false);
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
     * @return true if initialized with valid handles
     */
    public static boolean isInitialized() {
        // Check both the flag AND that critical handles are valid
        // This prevents false positives in forked JVM processes
        return initialized.get() && wgpuCreateInstance != null;
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
        
        log.info("About to call wgpuCreateInstance");
        
        try {
            // Call wgpuCreateInstance with NULL descriptor for default
            var instance = (MemorySegment) wgpuCreateInstance.invoke(MemorySegment.NULL);
            
            if (instance != null && !instance.equals(MemorySegment.NULL)) {
                log.info("Created WebGPU instance at 0x{}", Long.toHexString(instance.address()));
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
     * Enumerate adapters synchronously (preferred over requestAdapter).
     * @param instanceHandle the instance handle
     * @param options the request options (can be null)
     * @return array of adapter handles, or empty array if none available
     */
    public static MemorySegment[] enumerateAdapters(MemorySegment instanceHandle, MemorySegment options) {
        log.debug("enumerateAdapters called with instance: 0x{}", 
                 instanceHandle != null ? Long.toHexString(instanceHandle.address()) : "null");
        
        if (!initialized.get() || wgpuInstanceEnumerateAdapters == null) {
            log.warn("wgpuInstanceEnumerateAdapters not available");
            return new MemorySegment[0];
        }
        
        // Validate instance handle
        if (instanceHandle == null || instanceHandle.equals(MemorySegment.NULL)) {
            log.error("Invalid instance handle for enumerateAdapters");
            return new MemorySegment[0];
        }
        
        try (var arena = Arena.ofConfined()) {
            // Check if handle is still available
            if (wgpuInstanceEnumerateAdapters == null) {
                log.error("wgpuInstanceEnumerateAdapters handle is null during invocation");
                return new MemorySegment[0];
            }
            
            // Convert null options to MemorySegment.NULL to avoid NPE
            MemorySegment optionsSegment = (options != null) ? options : MemorySegment.NULL;
            
            // First call to get the count of adapters
            long adapterCount = (long) wgpuInstanceEnumerateAdapters.invoke(
                instanceHandle, optionsSegment, MemorySegment.NULL, 0L
            );
            
            log.debug("Found {} adapters", adapterCount);
            
            if (adapterCount <= 0) {
                return new MemorySegment[0];
            }
            
            // Allocate memory for adapter handles
            var adapterArray = arena.allocate(ValueLayout.ADDRESS, adapterCount);
            
            // Second call to get the actual adapters
            long actualCount = (long) wgpuInstanceEnumerateAdapters.invoke(
                instanceHandle, optionsSegment, adapterArray, adapterCount
            );
            
            log.debug("Retrieved {} adapters", actualCount);
            
            // Convert to Java array
            MemorySegment[] adapters = new MemorySegment[(int) actualCount];
            for (int i = 0; i < actualCount; i++) {
                var adapterHandle = adapterArray.getAtIndex(ValueLayout.ADDRESS, i);
                adapters[i] = adapterHandle;
                log.debug("Adapter {}: 0x{}", i, Long.toHexString(adapterHandle.address()));
            }
            
            return adapters;
            
        } catch (Throwable e) {
            log.error("Failed to enumerate adapters", e);
            return new MemorySegment[0];
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
        log.debug("requestAdapter called with instance: 0x{}", 
                 instanceHandle != null ? Long.toHexString(instanceHandle.address()) : "null");
        
        if (!initialized.get() || wgpuInstanceRequestAdapter == null) {
            log.warn("wgpuInstanceRequestAdapter not available");
            return MemorySegment.NULL;
        }
        
        // Validate instance handle
        if (instanceHandle == null || instanceHandle.equals(MemorySegment.NULL)) {
            log.error("Invalid instance handle for requestAdapter");
            return MemorySegment.NULL;
        }
        
        log.debug("About to create callback for adapter request");
        
        // CRITICAL FIX: Use Arena.global() to ensure callback stub remains valid
        // The callback will be invoked asynchronously from a native thread
        // and needs to remain valid beyond the scope of this method
        var arena = Arena.global();
        
        // Create callback to handle the async response
        var callback = new CallbackHelper.AdapterCallback(arena);
        
        // Log the callback stub address for debugging
        var stubAddress = callback.getCallbackStub().address();
        log.info("Created callback stub at address: 0x{}", Long.toHexString(stubAddress));
        
        // Validate the stub address looks reasonable
        if (stubAddress == 0 || stubAddress == 0xa90247f0a9010fe2L) {
            log.error("Invalid callback stub address: 0x{}", Long.toHexString(stubAddress));
            return MemorySegment.NULL;
        }
        
        try {
            log.info("About to invoke wgpuInstanceRequestAdapter with:");
            log.info("  Instance: 0x{}", Long.toHexString(instanceHandle.address()));
            log.info("  Options: {}", options != null ? "0x" + Long.toHexString(options.address()) : "NULL");
            log.info("  Callback: 0x{}", Long.toHexString(callback.getCallbackStub().address()));
            log.info("  Userdata: NULL");
            
            // Call the native function with callback
            // The callback must stay alive until it's invoked by the native code
            try {
                wgpuInstanceRequestAdapter.invokeExact(
                    instanceHandle, 
                    options != null ? options : MemorySegment.NULL,
                    callback.getCallbackStub(),
                    MemorySegment.NULL  // userdata
                );
            } catch (Throwable t) {
                log.error("Failed to invoke wgpuInstanceRequestAdapter", t);
                return MemorySegment.NULL;
            }
            
            // Wait for the callback to be invoked (5 second timeout)
            var result = callback.waitForResult(5, java.util.concurrent.TimeUnit.SECONDS);
            
            if (result != null && !result.equals(MemorySegment.NULL)) {
                log.debug("Successfully obtained adapter: 0x{}", Long.toHexString(result.address()));
                return result;
            } else {
                log.warn("Failed to obtain adapter from native API - callback returned null");
                return MemorySegment.NULL;
            }
            
        } catch (Throwable e) {
            log.error("Failed to invoke wgpuInstanceRequestAdapter", e);
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
        
        // Use global arena to keep callback alive
        var arena = Arena.global();
        try {
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
     * Create a texture on the specified device.
     * 
     * @param device the device handle
     * @param descriptor the texture descriptor memory segment
     * @return the texture handle
     */
    public static MemorySegment createTexture(MemorySegment device, MemorySegment descriptor) {
        if (!initialized.get() || wgpuDeviceCreateTexture == null) {
            log.warn("wgpuDeviceCreateTexture not available");
            return MemorySegment.NULL;
        }
        
        try {
            return (MemorySegment) wgpuDeviceCreateTexture.invoke(device, descriptor);
        } catch (Throwable e) {
            log.error("Failed to create texture", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Create a sampler on the specified device.
     * 
     * @param device the device handle
     * @param descriptor the sampler descriptor memory segment (can be null for default)
     * @return the sampler handle
     */
    public static MemorySegment createSampler(MemorySegment device, MemorySegment descriptor) {
        if (!initialized.get() || wgpuDeviceCreateSampler == null) {
            log.warn("wgpuDeviceCreateSampler not available");
            return MemorySegment.NULL;
        }
        
        try {
            MemorySegment descriptorSegment = (descriptor != null) ? descriptor : MemorySegment.NULL;
            return (MemorySegment) wgpuDeviceCreateSampler.invoke(device, descriptorSegment);
        } catch (Throwable e) {
            log.error("Failed to create sampler", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Create a texture view from a texture.
     * 
     * @param texture the texture handle
     * @param descriptor the texture view descriptor memory segment (can be null for default)
     * @return the texture view handle
     */
    public static MemorySegment createTextureView(MemorySegment texture, MemorySegment descriptor) {
        if (!initialized.get() || wgpuTextureCreateView == null) {
            log.warn("wgpuTextureCreateView not available");
            return MemorySegment.NULL;
        }
        
        try {
            MemorySegment descriptorSegment = (descriptor != null) ? descriptor : MemorySegment.NULL;
            return (MemorySegment) wgpuTextureCreateView.invoke(texture, descriptorSegment);
        } catch (Throwable e) {
            log.error("Failed to create texture view", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Release a texture.
     * 
     * @param texture the texture to release
     */
    public static void releaseTexture(MemorySegment texture) {
        if (!initialized.get() || wgpuTextureRelease == null) {
            return;
        }
        
        if (texture == null || texture.equals(MemorySegment.NULL)) {
            return;
        }
        
        try {
            wgpuTextureRelease.invoke(texture);
            log.debug("Released WebGPU texture");
        } catch (Throwable e) {
            log.error("Failed to release texture", e);
        }
    }
    
    /**
     * Release a sampler.
     * 
     * @param sampler the sampler to release
     */
    public static void releaseSampler(MemorySegment sampler) {
        if (!initialized.get() || wgpuSamplerRelease == null) {
            return;
        }
        
        if (sampler == null || sampler.equals(MemorySegment.NULL)) {
            return;
        }
        
        try {
            wgpuSamplerRelease.invoke(sampler);
            log.debug("Released WebGPU sampler");
        } catch (Throwable e) {
            log.error("Failed to release sampler", e);
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
        wgpuInstanceEnumerateAdapters = null;
        wgpuInstanceRequestAdapter = null;
        wgpuAdapterRelease = null;
        wgpuAdapterRequestDevice = null;
        wgpuDeviceRelease = null;
        wgpuDeviceGetQueue = null;
        wgpuDeviceCreateBuffer = null;
        wgpuBufferGetSize = null;
        wgpuBufferDestroy = null;
        wgpuQueueRelease = null;
        wgpuDeviceCreateTexture = null;
        wgpuDeviceCreateSampler = null;
        wgpuTextureRelease = null;
        wgpuSamplerRelease = null;
        wgpuTextureCreateView = null;
        
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
            
            // wgpuInstanceEnumerateAdapters(WGPUInstance instance, const WGPURequestAdapterOptions* options,
            //                               WGPUAdapter* adapters, size_t adapterCount) -> size_t
            var instanceEnumerateAdaptersOpt = symbolLookup.find("wgpuInstanceEnumerateAdapters");
            if (instanceEnumerateAdaptersOpt.isPresent()) {
                wgpuInstanceEnumerateAdapters = linker.downcallHandle(
                    instanceEnumerateAdaptersOpt.get(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                         ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
                );
                log.debug("Successfully loaded wgpuInstanceEnumerateAdapters");
            } else {
                log.warn("Could not find wgpuInstanceEnumerateAdapters symbol");
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
            
            // wgpuQueueSubmit(WGPUQueue queue, size_t commandCount, const WGPUCommandBuffer* commands) -> void
            var queueSubmitOpt = symbolLookup.find("wgpuQueueSubmit");
            if (queueSubmitOpt.isPresent()) {
                wgpuQueueSubmit = linker.downcallHandle(
                    queueSubmitOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuQueueWriteBuffer(WGPUQueue queue, WGPUBuffer buffer, uint64_t bufferOffset, const void* data, size_t size) -> void
            var queueWriteBufferOpt = symbolLookup.find("wgpuQueueWriteBuffer");
            if (queueWriteBufferOpt.isPresent()) {
                wgpuQueueWriteBuffer = linker.downcallHandle(
                    queueWriteBufferOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, 
                        ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
                );
            }
            
            // wgpuDeviceCreateCommandEncoder(WGPUDevice device, const WGPUCommandEncoderDescriptor* descriptor) -> WGPUCommandEncoder
            var deviceCreateCommandEncoderOpt = symbolLookup.find("wgpuDeviceCreateCommandEncoder");
            if (deviceCreateCommandEncoderOpt.isPresent()) {
                wgpuDeviceCreateCommandEncoder = linker.downcallHandle(
                    deviceCreateCommandEncoderOpt.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuCommandEncoderBeginComputePass(WGPUCommandEncoder encoder, const WGPUComputePassDescriptor* descriptor) -> WGPUComputePassEncoder
            var commandEncoderBeginComputePassOpt = symbolLookup.find("wgpuCommandEncoderBeginComputePass");
            if (commandEncoderBeginComputePassOpt.isPresent()) {
                wgpuCommandEncoderBeginComputePass = linker.downcallHandle(
                    commandEncoderBeginComputePassOpt.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuCommandEncoderFinish(WGPUCommandEncoder encoder, const WGPUCommandBufferDescriptor* descriptor) -> WGPUCommandBuffer
            var commandEncoderFinishOpt = symbolLookup.find("wgpuCommandEncoderFinish");
            if (commandEncoderFinishOpt.isPresent()) {
                wgpuCommandEncoderFinish = linker.downcallHandle(
                    commandEncoderFinishOpt.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuCommandEncoderRelease(WGPUCommandEncoder encoder) -> void
            var commandEncoderReleaseOpt = symbolLookup.find("wgpuCommandEncoderRelease");
            if (commandEncoderReleaseOpt.isPresent()) {
                wgpuCommandEncoderRelease = linker.downcallHandle(
                    commandEncoderReleaseOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
                );
            }
            
            // wgpuComputePassEncoderSetPipeline(WGPUComputePassEncoder encoder, WGPUComputePipeline pipeline) -> void
            var computePassEncoderSetPipelineOpt = symbolLookup.find("wgpuComputePassEncoderSetPipeline");
            if (computePassEncoderSetPipelineOpt.isPresent()) {
                wgpuComputePassEncoderSetPipeline = linker.downcallHandle(
                    computePassEncoderSetPipelineOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuComputePassEncoderSetBindGroup(WGPUComputePassEncoder encoder, uint32_t groupIndex, WGPUBindGroup group, size_t dynamicOffsetCount, const uint32_t* dynamicOffsets) -> void
            var computePassEncoderSetBindGroupOpt = symbolLookup.find("wgpuComputePassEncoderSetBindGroup");
            if (computePassEncoderSetBindGroupOpt.isPresent()) {
                wgpuComputePassEncoderSetBindGroup = linker.downcallHandle(
                    computePassEncoderSetBindGroupOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuComputePassEncoderDispatchWorkgroups(WGPUComputePassEncoder encoder, uint32_t x, uint32_t y, uint32_t z) -> void
            var computePassEncoderDispatchWorkgroupsOpt = symbolLookup.find("wgpuComputePassEncoderDispatchWorkgroups");
            if (computePassEncoderDispatchWorkgroupsOpt.isPresent()) {
                wgpuComputePassEncoderDispatchWorkgroups = linker.downcallHandle(
                    computePassEncoderDispatchWorkgroupsOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
                );
            }
            
            // wgpuComputePassEncoderEnd(WGPUComputePassEncoder encoder) -> void
            var computePassEncoderEndOpt = symbolLookup.find("wgpuComputePassEncoderEnd");
            if (computePassEncoderEndOpt.isPresent()) {
                wgpuComputePassEncoderEnd = linker.downcallHandle(
                    computePassEncoderEndOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
                );
            }
            
            // wgpuDeviceCreateShaderModule(WGPUDevice device, const WGPUShaderModuleDescriptor* descriptor) -> WGPUShaderModule
            var deviceCreateShaderModuleOpt = symbolLookup.find("wgpuDeviceCreateShaderModule");
            if (deviceCreateShaderModuleOpt.isPresent()) {
                wgpuDeviceCreateShaderModule = linker.downcallHandle(
                    deviceCreateShaderModuleOpt.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuDeviceCreateComputePipeline(WGPUDevice device, const WGPUComputePipelineDescriptor* descriptor) -> WGPUComputePipeline
            var deviceCreateComputePipelineOpt = symbolLookup.find("wgpuDeviceCreateComputePipeline");
            if (deviceCreateComputePipelineOpt.isPresent()) {
                wgpuDeviceCreateComputePipeline = linker.downcallHandle(
                    deviceCreateComputePipelineOpt.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuDeviceCreateTexture(WGPUDevice device, const WGPUTextureDescriptor* descriptor) -> WGPUTexture
            var deviceCreateTextureOpt = symbolLookup.find("wgpuDeviceCreateTexture");
            if (deviceCreateTextureOpt.isPresent()) {
                wgpuDeviceCreateTexture = linker.downcallHandle(
                    deviceCreateTextureOpt.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuDeviceCreateSampler(WGPUDevice device, const WGPUSamplerDescriptor* descriptor) -> WGPUSampler
            var deviceCreateSamplerOpt = symbolLookup.find("wgpuDeviceCreateSampler");
            if (deviceCreateSamplerOpt.isPresent()) {
                wgpuDeviceCreateSampler = linker.downcallHandle(
                    deviceCreateSamplerOpt.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
            // wgpuTextureRelease(WGPUTexture texture) -> void
            var textureReleaseOpt = symbolLookup.find("wgpuTextureRelease");
            if (textureReleaseOpt.isPresent()) {
                wgpuTextureRelease = linker.downcallHandle(
                    textureReleaseOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
                );
            }
            
            // wgpuSamplerRelease(WGPUSampler sampler) -> void
            var samplerReleaseOpt = symbolLookup.find("wgpuSamplerRelease");
            if (samplerReleaseOpt.isPresent()) {
                wgpuSamplerRelease = linker.downcallHandle(
                    samplerReleaseOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
                );
            }
            
            // wgpuTextureCreateView(WGPUTexture texture, const WGPUTextureViewDescriptor* descriptor) -> WGPUTextureView
            var textureCreateViewOpt = symbolLookup.find("wgpuTextureCreateView");
            if (textureCreateViewOpt.isPresent()) {
                wgpuTextureCreateView = linker.downcallHandle(
                    textureCreateViewOpt.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }
            
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
    
    /**
     * Create a command encoder on a device.
     * 
     * @param deviceHandle the device handle
     * @param descriptor the command encoder descriptor (can be null)
     * @return the command encoder handle, or NULL if creation failed
     */
    public static MemorySegment createCommandEncoder(MemorySegment deviceHandle, MemorySegment descriptor) {
        if (!initialized.get() || wgpuDeviceCreateCommandEncoder == null) {
            log.warn("wgpuDeviceCreateCommandEncoder not available");
            return MemorySegment.NULL;
        }
        
        try {
            var encoder = (MemorySegment) wgpuDeviceCreateCommandEncoder.invoke(
                deviceHandle, 
                descriptor != null ? descriptor : MemorySegment.NULL
            );
            
            if (encoder != null && !encoder.equals(MemorySegment.NULL)) {
                log.debug("Created command encoder: 0x{}", Long.toHexString(encoder.address()));
                return encoder;
            }
            
            return MemorySegment.NULL;
        } catch (Throwable e) {
            log.error("Failed to create command encoder", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Begin a compute pass on a command encoder.
     * 
     * @param encoderHandle the command encoder handle
     * @param descriptor the compute pass descriptor (can be null)
     * @return the compute pass encoder handle, or NULL if creation failed
     */
    public static MemorySegment beginComputePass(MemorySegment encoderHandle, MemorySegment descriptor) {
        if (!initialized.get() || wgpuCommandEncoderBeginComputePass == null) {
            log.warn("wgpuCommandEncoderBeginComputePass not available");
            return MemorySegment.NULL;
        }
        
        try {
            var pass = (MemorySegment) wgpuCommandEncoderBeginComputePass.invoke(
                encoderHandle,
                descriptor != null ? descriptor : MemorySegment.NULL
            );
            
            if (pass != null && !pass.equals(MemorySegment.NULL)) {
                log.debug("Created compute pass encoder: 0x{}", Long.toHexString(pass.address()));
                return pass;
            }
            
            return MemorySegment.NULL;
        } catch (Throwable e) {
            log.error("Failed to begin compute pass", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Finish recording commands and create a command buffer.
     * 
     * @param encoderHandle the command encoder handle
     * @param descriptor the command buffer descriptor (can be null)
     * @return the command buffer handle, or NULL if creation failed
     */
    public static MemorySegment finishCommandEncoder(MemorySegment encoderHandle, MemorySegment descriptor) {
        if (!initialized.get() || wgpuCommandEncoderFinish == null) {
            log.warn("wgpuCommandEncoderFinish not available");
            return MemorySegment.NULL;
        }
        
        try {
            var commandBuffer = (MemorySegment) wgpuCommandEncoderFinish.invoke(
                encoderHandle,
                descriptor != null ? descriptor : MemorySegment.NULL
            );
            
            if (commandBuffer != null && !commandBuffer.equals(MemorySegment.NULL)) {
                log.debug("Finished command encoder, created command buffer: 0x{}", 
                         Long.toHexString(commandBuffer.address()));
                return commandBuffer;
            }
            
            return MemorySegment.NULL;
        } catch (Throwable e) {
            log.error("Failed to finish command encoder", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Submit command buffers to a queue.
     * 
     * @param queueHandle the queue handle
     * @param commandBuffers array of command buffer handles
     */
    public static void submitToQueue(MemorySegment queueHandle, MemorySegment[] commandBuffers) {
        if (!initialized.get() || wgpuQueueSubmit == null) {
            log.warn("wgpuQueueSubmit not available");
            return;
        }
        
        try (var arena = Arena.ofConfined()) {
            // Allocate array of command buffer handles
            var bufferArray = arena.allocate(ValueLayout.ADDRESS, commandBuffers.length);
            for (int i = 0; i < commandBuffers.length; i++) {
                bufferArray.setAtIndex(ValueLayout.ADDRESS, i, commandBuffers[i]);
            }
            
            wgpuQueueSubmit.invoke(queueHandle, (long) commandBuffers.length, bufferArray);
            log.debug("Submitted {} command buffers to queue", commandBuffers.length);
            
        } catch (Throwable e) {
            log.error("Failed to submit command buffers", e);
        }
    }
    
    /**
     * Write data to a buffer via queue.
     * 
     * @param queueHandle the queue handle
     * @param bufferHandle the buffer handle  
     * @param bufferOffset offset in the buffer
     * @param data the data to write
     */
    public static void writeBuffer(MemorySegment queueHandle, MemorySegment bufferHandle,
                                   long bufferOffset, byte[] data) {
        if (!initialized.get() || wgpuQueueWriteBuffer == null) {
            log.warn("wgpuQueueWriteBuffer not available");
            return;
        }
        
        try (var arena = Arena.ofConfined()) {
            // Allocate and copy data to native memory
            var dataSegment = arena.allocate(data.length);
            dataSegment.copyFrom(MemorySegment.ofArray(data));
            
            wgpuQueueWriteBuffer.invoke(queueHandle, bufferHandle, bufferOffset,
                                        dataSegment, (long) data.length);
            log.debug("Wrote {} bytes to buffer at offset {}", data.length, bufferOffset);
            
        } catch (Throwable e) {
            log.error("Failed to write buffer data", e);
        }
    }
    
    /**
     * Create a shader module on a device.
     * 
     * @param deviceHandle the device handle
     * @param descriptor the shader module descriptor
     * @return the shader module handle, or NULL if creation failed
     */
    public static MemorySegment createShaderModule(MemorySegment deviceHandle, MemorySegment descriptor) {
        if (!initialized.get() || wgpuDeviceCreateShaderModule == null) {
            log.warn("wgpuDeviceCreateShaderModule not available");
            return MemorySegment.NULL;
        }
        
        try {
            var module = (MemorySegment) wgpuDeviceCreateShaderModule.invoke(deviceHandle, descriptor);
            
            if (module != null && !module.equals(MemorySegment.NULL)) {
                log.debug("Created shader module: 0x{}", Long.toHexString(module.address()));
                return module;
            }
            
            return MemorySegment.NULL;
        } catch (Throwable e) {
            log.error("Failed to create shader module", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Create a compute pipeline on a device.
     * 
     * @param deviceHandle the device handle
     * @param descriptor the compute pipeline descriptor
     * @return the compute pipeline handle, or NULL if creation failed
     */
    public static MemorySegment createComputePipeline(MemorySegment deviceHandle, MemorySegment descriptor) {
        if (!initialized.get() || wgpuDeviceCreateComputePipeline == null) {
            log.warn("wgpuDeviceCreateComputePipeline not available");
            return MemorySegment.NULL;
        }
        
        try {
            var pipeline = (MemorySegment) wgpuDeviceCreateComputePipeline.invoke(deviceHandle, descriptor);
            
            if (pipeline != null && !pipeline.equals(MemorySegment.NULL)) {
                log.debug("Created compute pipeline: 0x{}", Long.toHexString(pipeline.address()));
                return pipeline;
            }
            
            return MemorySegment.NULL;
        } catch (Throwable e) {
            log.error("Failed to create compute pipeline", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Set the pipeline for a compute pass encoder.
     * 
     * @param encoderHandle the compute pass encoder handle
     * @param pipelineHandle the compute pipeline handle
     */
    public static void setComputePipeline(MemorySegment encoderHandle, MemorySegment pipelineHandle) {
        if (!initialized.get() || wgpuComputePassEncoderSetPipeline == null) {
            log.warn("wgpuComputePassEncoderSetPipeline not available");
            return;
        }
        
        try {
            wgpuComputePassEncoderSetPipeline.invoke(encoderHandle, pipelineHandle);
            log.debug("Set compute pipeline for encoder");
        } catch (Throwable e) {
            log.error("Failed to set compute pipeline", e);
        }
    }
    
    /**
     * Dispatch workgroups in a compute pass.
     * 
     * @param encoderHandle the compute pass encoder handle
     * @param x workgroups in X dimension
     * @param y workgroups in Y dimension
     * @param z workgroups in Z dimension
     */
    public static void dispatchWorkgroups(MemorySegment encoderHandle, int x, int y, int z) {
        if (!initialized.get() || wgpuComputePassEncoderDispatchWorkgroups == null) {
            log.warn("wgpuComputePassEncoderDispatchWorkgroups not available");
            return;
        }
        
        try {
            wgpuComputePassEncoderDispatchWorkgroups.invoke(encoderHandle, x, y, z);
            log.debug("Dispatched workgroups: {}x{}x{}", x, y, z);
        } catch (Throwable e) {
            log.error("Failed to dispatch workgroups", e);
        }
    }
    
    /**
     * End a compute pass.
     * 
     * @param encoderHandle the compute pass encoder handle
     */
    public static void endComputePass(MemorySegment encoderHandle) {
        if (!initialized.get() || wgpuComputePassEncoderEnd == null) {
            log.warn("wgpuComputePassEncoderEnd not available");
            return;
        }
        
        try {
            wgpuComputePassEncoderEnd.invoke(encoderHandle);
            log.debug("Ended compute pass");
        } catch (Throwable e) {
            log.error("Failed to end compute pass", e);
        }
    }
}