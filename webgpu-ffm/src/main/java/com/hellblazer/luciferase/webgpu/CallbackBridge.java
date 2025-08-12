package com.hellblazer.luciferase.webgpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Bridge for creating WebGPU callbacks that work with Java 24 FFM sealed interfaces.
 * This approach uses MethodHandles directly instead of lambda expressions or method references.
 */
public class CallbackBridge {
    private static final Logger log = LoggerFactory.getLogger(CallbackBridge.class);
    
    /**
     * Create a buffer map callback using direct method handle approach.
     */
    public static MemorySegment createBufferMapCallback(Arena arena, BufferMapCallbackHandler handler) {
        try {
            // Create a static method handle for the callback
            var lookup = MethodHandles.lookup();
            var callbackMethod = lookup.findStatic(CallbackBridge.class, "bufferMapCallbackImpl",
                MethodType.methodType(void.class, int.class, MemorySegment.class, BufferMapCallbackHandler.class));
            
            // Insert the handler as the last argument using insertArguments
            var boundMethod = MethodHandles.insertArguments(callbackMethod, 2, handler);
            
            // Create the function descriptor
            var descriptor = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_INT,    // status
                ValueLayout.ADDRESS      // userdata
            );
            
            // Create the upcall stub using the method handle directly
            return Linker.nativeLinker().upcallStub(boundMethod, descriptor, arena);
            
        } catch (Exception e) {
            log.error("Failed to create buffer map callback", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Create a buffer map callback V2 with TWO userdatas for Dawn's new API.
     * This is what Dawn expects for the CallbackInfo-based API.
     */
    public static MemorySegment createBufferMapCallbackV2(Arena arena, BufferMapCallbackHandler handler) {
        try {
            log.debug("Creating buffer map callback V2 with TWO userdatas for Dawn");
            
            var lookup = MethodHandles.lookup();
            var callbackMethod = lookup.findStatic(CallbackBridge.class, "bufferMapCallbackImplV2",
                MethodType.methodType(void.class, int.class, MemorySegment.class, MemorySegment.class, BufferMapCallbackHandler.class));
            
            var boundMethod = MethodHandles.insertArguments(callbackMethod, 3, handler);
            
            var descriptor = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_INT,    // status
                ValueLayout.ADDRESS,     // userdata1 (Dawn internal)
                ValueLayout.ADDRESS      // userdata2 (our data)
            );
            
            // Use Arena.global() to ensure callback survives
            var callbackStub = Linker.nativeLinker().upcallStub(boundMethod, descriptor, Arena.global());
            
            log.debug("Created buffer map callback V2 stub at address: 0x{}", Long.toHexString(callbackStub.address()));
            return callbackStub;
            
        } catch (Exception e) {
            log.error("Failed to create buffer map callback V2", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Create an adapter request callback using direct method handle approach.
     * FIXED: Use global arena and better validation.
     */
    public static MemorySegment createAdapterCallback(Arena arena, AdapterCallbackHandler handler) {
        try {
            log.debug("Creating adapter callback with handler: {}", handler.getClass().getName());
            
            var lookup = MethodHandles.lookup();
            var callbackMethod = lookup.findStatic(CallbackBridge.class, "adapterCallbackImpl",
                MethodType.methodType(void.class, int.class, MemorySegment.class, 
                    MemorySegment.class, MemorySegment.class, AdapterCallbackHandler.class));
            
            var boundMethod = MethodHandles.insertArguments(callbackMethod, 4, handler);
            
            var descriptor = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_INT,    // status
                ValueLayout.ADDRESS,     // adapter
                ValueLayout.ADDRESS,     // message
                ValueLayout.ADDRESS      // userdata
            );
            
            // CRITICAL FIX: Use Arena.global() to ensure callback survives beyond method scope
            var callbackStub = Linker.nativeLinker().upcallStub(boundMethod, descriptor, Arena.global());
            
            // Validate the stub
            if (callbackStub == null || callbackStub.equals(MemorySegment.NULL)) {
                log.error("Failed to create adapter callback stub - got NULL");
                return MemorySegment.NULL;
            }
            
            var stubAddress = callbackStub.address();
            log.debug("Created adapter callback stub at address: 0x{}", Long.toHexString(stubAddress));
            
            // Validate address looks reasonable
            if (stubAddress == 0 || stubAddress == 0xa90247f0a9010fe2L) {
                log.error("Invalid adapter callback stub address: 0x{}", Long.toHexString(stubAddress));
                return MemorySegment.NULL;
            }
            
            return callbackStub;
            
        } catch (Exception e) {
            log.error("Failed to create adapter callback", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Create a device request callback using direct method handle approach.
     * FIXED: Use confined arena and better error handling to prevent SIGBUS crash.
     */
    public static MemorySegment createDeviceCallback(Arena arena, DeviceCallbackHandler handler) {
        try {
            log.debug("Creating device callback with handler: {}", handler.getClass().getName());
            
            var lookup = MethodHandles.lookup();
            var callbackMethod = lookup.findStatic(CallbackBridge.class, "deviceCallbackImpl",
                MethodType.methodType(void.class, int.class, MemorySegment.class,
                    MemorySegment.class, MemorySegment.class, DeviceCallbackHandler.class));
            
            var boundMethod = MethodHandles.insertArguments(callbackMethod, 4, handler);
            
            var descriptor = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_INT,    // status
                ValueLayout.ADDRESS,     // device
                ValueLayout.ADDRESS,     // message
                ValueLayout.ADDRESS      // userdata
            );
            
            // CRITICAL FIX: Use Arena.global() to ensure callback survives beyond method scope
            // The callback may be invoked asynchronously from native code
            var callbackStub = Linker.nativeLinker().upcallStub(boundMethod, descriptor, Arena.global());
            
            // Validate the stub address
            if (callbackStub == null || callbackStub.equals(MemorySegment.NULL)) {
                log.error("Failed to create device callback stub - got NULL");
                return MemorySegment.NULL;
            }
            
            var stubAddress = callbackStub.address();
            log.debug("Created device callback stub at address: 0x{}", Long.toHexString(stubAddress));
            
            // Sanity check the address looks valid (not the problematic address we saw)
            if (stubAddress == 0 || stubAddress == 0xa90247f0a9010fe2L) {
                log.error("Invalid device callback stub address: 0x{}", Long.toHexString(stubAddress));
                return MemorySegment.NULL;
            }
            
            return callbackStub;
            
        } catch (Exception e) {
            log.error("Failed to create device callback", e);
            return MemorySegment.NULL;
        }
    }
    
    // Static callback implementation methods
    
    /**
     * Static implementation for buffer map callbacks.
     * OLD VERSION - for backward compatibility with single userdata
     */
    public static void bufferMapCallbackImpl(int status, MemorySegment userdata, BufferMapCallbackHandler handler) {
        try {
            handler.onBufferMapped(status, userdata);
        } catch (Exception e) {
            log.error("Exception in buffer map callback", e);
        }
    }
    
    /**
     * Static implementation for NEW buffer map callbacks with TWO userdatas.
     * This is what Dawn expects for the new CallbackInfo API.
     */
    public static void bufferMapCallbackImplV2(int status, MemorySegment userdata1, MemorySegment userdata2, BufferMapCallbackHandler handler) {
        try {
            // Dawn passes two userdatas - we use the second one for our purposes
            handler.onBufferMapped(status, userdata2);
        } catch (Exception e) {
            log.error("Exception in buffer map callback V2", e);
        }
    }
    
    /**
     * Static implementation for adapter request callbacks.
     */
    public static void adapterCallbackImpl(int status, MemorySegment adapter, 
                                         MemorySegment message, MemorySegment userdata, 
                                         AdapterCallbackHandler handler) {
        try {
            handler.onAdapterReceived(status, adapter, message, userdata);
        } catch (Exception e) {
            log.error("Exception in adapter callback", e);
        }
    }
    
    /**
     * Static implementation for device request callbacks.
     */
    public static void deviceCallbackImpl(int status, MemorySegment device,
                                        MemorySegment message, MemorySegment userdata,
                                        DeviceCallbackHandler handler) {
        try {
            handler.onDeviceReceived(status, device, message, userdata);
        } catch (Exception e) {
            log.error("Exception in device callback", e);
        }
    }
    
    // Handler interfaces
    
    public interface BufferMapCallbackHandler {
        void onBufferMapped(int status, MemorySegment userdata);
    }
    
    public interface AdapterCallbackHandler {
        void onAdapterReceived(int status, MemorySegment adapter, MemorySegment message, MemorySegment userdata);
    }
    
    public interface DeviceCallbackHandler {
        void onDeviceReceived(int status, MemorySegment device, MemorySegment message, MemorySegment userdata);
    }
    
    /**
     * Create an error callback for device uncaptured errors.
     */
    public static MemorySegment createErrorCallback(Arena arena, ErrorCallbackHandler handler) {
        try {
            var lookup = MethodHandles.lookup();
            var callbackMethod = lookup.findStatic(CallbackBridge.class, "errorCallbackImpl",
                MethodType.methodType(void.class, int.class, MemorySegment.class, 
                                     MemorySegment.class, ErrorCallbackHandler.class));
            
            // Use insertArguments instead of bindTo to avoid sealed interface issues
            var boundMethod = MethodHandles.insertArguments(callbackMethod, 3, handler);
            
            var descriptor = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_INT,     // error type
                ValueLayout.ADDRESS,      // message
                ValueLayout.ADDRESS       // userdata
            );
            
            return Linker.nativeLinker().upcallStub(boundMethod, descriptor, arena);
            
        } catch (Exception e) {
            log.error("Failed to create error callback stub", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Static implementation for error callbacks.
     */
    public static void errorCallbackImpl(int errorType, MemorySegment message, 
                                         MemorySegment userdata, ErrorCallbackHandler handler) {
        try {
            String errorMessage = message != null && !message.equals(MemorySegment.NULL) ?
                message.reinterpret(1024).getString(0) : "Unknown error";
            handler.onError(errorType, errorMessage, userdata);
        } catch (Exception e) {
            log.error("Exception in error callback", e);
        }
    }
    
    public interface ErrorCallbackHandler {
        void onError(int errorType, String message, MemorySegment userdata);
    }
    
    /**
     * Request an adapter from an instance using Dawn's working callbacks.
     * 
     * @param instanceHandle the instance handle
     * @param options the adapter options
     * @return a future that completes with the adapter
     */
    public static java.util.concurrent.CompletableFuture<com.hellblazer.luciferase.webgpu.wrapper.Adapter> requestAdapter(
            MemorySegment instanceHandle,
            com.hellblazer.luciferase.webgpu.wrapper.Instance.AdapterOptions options) {
        return requestAdapter(instanceHandle, options, null);
    }
    
    public static java.util.concurrent.CompletableFuture<com.hellblazer.luciferase.webgpu.wrapper.Adapter> requestAdapter(
            MemorySegment instanceHandle,
            com.hellblazer.luciferase.webgpu.wrapper.Instance.AdapterOptions options,
            com.hellblazer.luciferase.webgpu.wrapper.Instance instance) {
        
        var future = new java.util.concurrent.CompletableFuture<com.hellblazer.luciferase.webgpu.wrapper.Adapter>();
        
        // Run the adapter request in a separate thread to avoid blocking
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // Use Dawn's callback-based requestAdapter API
                // For now, pass NULL for options - TODO: convert options to native struct
                var adapterHandle = WebGPU.requestAdapter(instanceHandle, MemorySegment.NULL);
                
                if (adapterHandle != null && !adapterHandle.equals(MemorySegment.NULL)) {
                    log.debug("Adapter request succeeded with Dawn: 0x{}", Long.toHexString(adapterHandle.address()));
                    future.complete(new com.hellblazer.luciferase.webgpu.wrapper.Adapter(adapterHandle, instance));
                } else {
                    log.error("Adapter request returned NULL");
                    future.completeExceptionally(new RuntimeException("Failed to get adapter - returned NULL"));
                }
            } catch (Exception e) {
                log.error("Failed to request adapter", e);
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }
    
    /**
     * Request a device from an adapter using Dawn's working callbacks.
     * 
     * @param adapterHandle the adapter handle
     * @param descriptor the device descriptor
     * @return a future that completes with the device
     */
    public static java.util.concurrent.CompletableFuture<com.hellblazer.luciferase.webgpu.wrapper.Device> requestDevice(
            MemorySegment adapterHandle, 
            com.hellblazer.luciferase.webgpu.wrapper.Adapter.DeviceDescriptor descriptor) {
        return requestDevice(adapterHandle, descriptor, null);
    }
    
    public static java.util.concurrent.CompletableFuture<com.hellblazer.luciferase.webgpu.wrapper.Device> requestDevice(
            MemorySegment adapterHandle, 
            com.hellblazer.luciferase.webgpu.wrapper.Adapter.DeviceDescriptor descriptor,
            com.hellblazer.luciferase.webgpu.wrapper.Instance instance) {
        
        var future = new java.util.concurrent.CompletableFuture<com.hellblazer.luciferase.webgpu.wrapper.Device>();
        
        // Run the device request in a separate thread to avoid blocking
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // Use the existing WebGPU.requestDevice method which already handles callbacks
                var deviceHandle = WebGPU.requestDevice(adapterHandle, MemorySegment.NULL);
                
                if (deviceHandle != null && !deviceHandle.equals(MemorySegment.NULL)) {
                    log.debug("Device request succeeded with Dawn");
                    future.complete(new com.hellblazer.luciferase.webgpu.wrapper.Device(deviceHandle, instance));
                } else {
                    log.error("Device request returned NULL");
                    future.completeExceptionally(new RuntimeException("Failed to create device - returned NULL"));
                }
            } catch (Exception e) {
                log.error("Failed to request device", e);
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }
}