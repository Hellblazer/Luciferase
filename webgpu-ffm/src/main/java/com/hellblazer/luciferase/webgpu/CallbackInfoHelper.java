package com.hellblazer.luciferase.webgpu;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;

/**
 * Helper class for creating and managing WGPUBufferMapCallbackInfo structs
 * for the new Dawn CallbackInfo-based buffer mapping API.
 */
public class CallbackInfoHelper {
    
    // WGPUChainedStruct layout for nextInChain
    public static final MemoryLayout WGPU_CHAINED_STRUCT_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("next"),            // next chain element (8 bytes)
        ValueLayout.JAVA_INT.withName("sType")           // struct type identifier (4 bytes)
    ).withName("WGPUChainedStruct");
    
    // WGPUBufferMapCallbackInfo struct layout (matches official WebGPU headers)
    // IMPORTANT: There is NO sType field in this struct! Only nextInChain has sType if needed.
    public static final MemoryLayout WGPU_BUFFER_MAP_CALLBACK_INFO_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("nextInChain"),     // 0: WGPUChainedStruct* (8 bytes)
        ValueLayout.JAVA_INT.withName("mode"),           // 8: WGPUCallbackMode (4 bytes)
        MemoryLayout.paddingLayout(4),                   // 12: padding for alignment
        ValueLayout.ADDRESS.withName("callback"),        // 16: WGPUBufferMapCallback (8 bytes)
        ValueLayout.ADDRESS.withName("userdata1"),       // 24: void* (8 bytes)
        ValueLayout.ADDRESS.withName("userdata2")        // 32: void* (8 bytes)
    ).withName("WGPUBufferMapCallbackInfo");
    
    // VarHandle accessors for CallbackInfo struct fields
    private static final VarHandle NEXT_IN_CHAIN_HANDLE = WGPU_BUFFER_MAP_CALLBACK_INFO_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("nextInChain"));
    private static final VarHandle MODE_HANDLE = WGPU_BUFFER_MAP_CALLBACK_INFO_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("mode"));
    private static final VarHandle CALLBACK_HANDLE = WGPU_BUFFER_MAP_CALLBACK_INFO_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("callback"));
    private static final VarHandle USERDATA1_HANDLE = WGPU_BUFFER_MAP_CALLBACK_INFO_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("userdata1"));
    private static final VarHandle USERDATA2_HANDLE = WGPU_BUFFER_MAP_CALLBACK_INFO_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("userdata2"));
    
    /**
     * Create a WGPUBufferMapCallbackInfo struct with the specified parameters.
     * 
     * @param arena the memory arena for allocation
     * @param callback the callback function address
     * @param userdata1 first user data pointer (can be NULL)
     * @param userdata2 second user data pointer (can be NULL)
     * @return allocated and initialized CallbackInfo struct
     */
    public static MemorySegment createCallbackInfo(Arena arena, MemorySegment callback, 
                                                   MemorySegment userdata1, MemorySegment userdata2) {
        var callbackInfo = arena.allocate(WGPU_BUFFER_MAP_CALLBACK_INFO_LAYOUT);
        
        // Initialize the struct fields (matches official WebGPU headers)
        NEXT_IN_CHAIN_HANDLE.set(callbackInfo, 0L, MemorySegment.NULL); // nextInChain = NULL (no extensions)
        MODE_HANDLE.set(callbackInfo, 0L, 0x00000002); // mode = WGPUCallbackMode_AllowProcessEvents (FIXED)
        CALLBACK_HANDLE.set(callbackInfo, 0L, callback); // callback function
        USERDATA1_HANDLE.set(callbackInfo, 0L, userdata1 != null ? userdata1 : MemorySegment.NULL); 
        USERDATA2_HANDLE.set(callbackInfo, 0L, userdata2 != null ? userdata2 : MemorySegment.NULL);
        
        return callbackInfo;
    }
    
    /**
     * Create a CallbackInfo struct using a BufferMapCallback helper with one userdata.
     * 
     * @param arena the memory arena for allocation
     * @param callback the BufferMapCallback instance
     * @param userdata additional user data pointer (optional)
     * @return allocated and initialized CallbackInfo struct
     */
    public static MemorySegment createCallbackInfo(Arena arena, CallbackHelper.BufferMapCallback callback, 
                                                   MemorySegment userdata) {
        return createCallbackInfo(arena, callback.getCallbackStub(), MemorySegment.NULL, userdata);
    }
    
    /**
     * Create a CallbackInfo struct using a BufferMapCallback helper with no additional userdata.
     * 
     * @param arena the memory arena for allocation  
     * @param callback the BufferMapCallback instance
     * @return allocated and initialized CallbackInfo struct
     */
    public static MemorySegment createCallbackInfo(Arena arena, CallbackHelper.BufferMapCallback callback) {
        return createCallbackInfo(arena, callback.getCallbackStub(), MemorySegment.NULL, MemorySegment.NULL);
    }
    
    /**
     * Call wgpuBufferMapAsyncF using the CallbackInfo-based API.
     * This should resolve the getMappedRange NULL issue with Dawn.
     * 
     * @param arena the memory arena for struct allocation
     * @param bufferHandle the buffer handle
     * @param mode the map mode (read/write)
     * @param offset the offset in bytes
     * @param size the size to map
     * @param callback the callback instance
     * @return WGPUFuture handle (as MemorySegment), or NULL if not available
     */
    public static MemorySegment mapBufferAsyncF(Arena arena, MemorySegment bufferHandle, int mode, 
                                                long offset, long size, CallbackHelper.BufferMapCallback callback) {
        try {
            // Access the wgpuBufferMapAsyncF method handle via reflection
            var webgpuClass = WebGPU.class;
            var field = webgpuClass.getDeclaredField("wgpuBufferMapAsyncF");
            field.setAccessible(true);
            var wgpuBufferMapAsyncF = field.get(null);
            
            if (wgpuBufferMapAsyncF == null) {
                return MemorySegment.NULL; // Not available, caller should fallback to old API
            }
            
            // CRITICAL FIX: Dawn requires TWO userdatas for the new API
            // userdata1: Used by Dawn internally for tracking
            // userdata2: Available for our callback data
            
            // IMPORTANT: The callback parameter passed in is already a V2 callback from Buffer.mapAsync
            // We just need to provide the two userdatas that Dawn expects
            
            // Allocate userdata1 for Dawn's internal use
            var userdata1 = arena.allocate(8); // 8 bytes for a pointer
            userdata1.setAtIndex(ValueLayout.JAVA_LONG, 0, System.nanoTime()); // Unique identifier
            
            // Allocate userdata2 for our callback context 
            var userdata2 = arena.allocate(8); // 8 bytes for a pointer
            userdata2.setAtIndex(ValueLayout.JAVA_LONG, 0, callback.hashCode()); // Store callback reference
            
            // Create CallbackInfo struct WITH BOTH USERDATAS using the passed V2 callback
            var callbackInfo = createCallbackInfo(arena, callback.getCallbackStub(), userdata1, userdata2);
            
            // Call wgpuBufferMapAsyncF
            var methodHandle = (java.lang.invoke.MethodHandle) wgpuBufferMapAsyncF;
            var result = (MemorySegment) methodHandle.invoke(bufferHandle, mode, offset, size, callbackInfo);
            return result;
            
        } catch (Throwable e) {
            throw new RuntimeException("Failed to call wgpuBufferMapAsyncF with CallbackInfo", e);
        }
    }
    
    /**
     * Check if the wgpuBufferMapAsyncF API is available.
     * @return true if the next-generation future-based buffer mapping API is loaded
     */
    public static boolean isBufferMapAsyncFAvailable() {
        try {
            var webgpuClass = Class.forName("com.hellblazer.luciferase.webgpu.WebGPU");
            var field = webgpuClass.getDeclaredField("wgpuBufferMapAsyncF");
            field.setAccessible(true);
            var wgpuBufferMapAsyncF = field.get(null);
            return wgpuBufferMapAsyncF != null;
        } catch (Exception e) {
            return false;
        }
    }
}