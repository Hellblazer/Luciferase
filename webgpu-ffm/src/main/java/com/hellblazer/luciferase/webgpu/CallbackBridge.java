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
     * Create an adapter request callback using direct method handle approach.
     */
    public static MemorySegment createAdapterCallback(Arena arena, AdapterCallbackHandler handler) {
        try {
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
            
            return Linker.nativeLinker().upcallStub(boundMethod, descriptor, arena);
            
        } catch (Exception e) {
            log.error("Failed to create adapter callback", e);
            return MemorySegment.NULL;
        }
    }
    
    /**
     * Create a device request callback using direct method handle approach.
     */
    public static MemorySegment createDeviceCallback(Arena arena, DeviceCallbackHandler handler) {
        try {
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
            
            return Linker.nativeLinker().upcallStub(boundMethod, descriptor, arena);
            
        } catch (Exception e) {
            log.error("Failed to create device callback", e);
            return MemorySegment.NULL;
        }
    }
    
    // Static callback implementation methods
    
    /**
     * Static implementation for buffer map callbacks.
     */
    public static void bufferMapCallbackImpl(int status, MemorySegment userdata, BufferMapCallbackHandler handler) {
        try {
            handler.onBufferMapped(status, userdata);
        } catch (Exception e) {
            log.error("Exception in buffer map callback", e);
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
}