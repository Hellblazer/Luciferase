package com.hellblazer.luciferase.webgpu;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for handling WGPUFuture-based operations and waiting.
 * This implements the new future-based API for proper async operation handling.
 */
public class FutureWaitHelper {
    private static final Logger log = LoggerFactory.getLogger(FutureWaitHelper.class);
    
    // WGPUFuture struct layout - simple opaque ID
    public static final MemoryLayout WGPU_FUTURE_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("id")
    ).withName("WGPUFuture");
    
    // WGPUFutureWaitInfo struct layout 
    public static final MemoryLayout WGPU_FUTURE_WAIT_INFO_LAYOUT = MemoryLayout.structLayout(
        WGPU_FUTURE_LAYOUT.withName("future"),
        ValueLayout.JAVA_BYTE.withName("completed"),
        MemoryLayout.paddingLayout(7) // padding to 8-byte alignment
    ).withName("WGPUFutureWaitInfo");
    
    // VarHandle accessors for WGPUFuture
    private static final VarHandle FUTURE_ID_HANDLE = WGPU_FUTURE_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("id"));
        
    // VarHandle accessors for WGPUFutureWaitInfo  
    private static final VarHandle WAIT_INFO_FUTURE_ID_HANDLE = WGPU_FUTURE_WAIT_INFO_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("future"), 
        MemoryLayout.PathElement.groupElement("id"));
    private static final VarHandle WAIT_INFO_COMPLETED_HANDLE = WGPU_FUTURE_WAIT_INFO_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("completed"));
    
    // WGPUWaitStatus constants (from official WebGPU headers)
    public static final int WGPU_WAIT_STATUS_SUCCESS = 0x00000001;  // 1
    public static final int WGPU_WAIT_STATUS_TIMED_OUT = 0x00000002;  // 2
    public static final int WGPU_WAIT_STATUS_ERROR = 0x00000003;  // 3
    
    /**
     * Create a WGPUFutureWaitInfo struct from a WGPUFuture.
     * 
     * @param arena the memory arena for allocation
     * @param futureHandle the WGPUFuture handle/MemorySegment
     * @return allocated WGPUFutureWaitInfo struct
     */
    public static MemorySegment createFutureWaitInfo(Arena arena, MemorySegment futureHandle) {
        var waitInfo = arena.allocate(WGPU_FUTURE_WAIT_INFO_LAYOUT);
        
        // CRITICAL FIX: The future returned by wgpuBufferMapAsyncF is already an ID value
        // stored as the address of a MemorySegment (e.g., 0x4), not a pointer to a struct
        // We need to use this value directly as the future.id
        long futureId = futureHandle != null && !futureHandle.equals(MemorySegment.NULL) 
            ? futureHandle.address() : 0L;
            
        WAIT_INFO_FUTURE_ID_HANDLE.set(waitInfo, 0L, futureId);
        WAIT_INFO_COMPLETED_HANDLE.set(waitInfo, 0L, (byte) 0); // not completed initially
        
        log.debug("Created WGPUFutureWaitInfo with future.id = 0x{}", Long.toHexString(futureId));
        
        return waitInfo;
    }
    
    /**
     * Wait for any of the given futures to complete using wgpuInstanceWaitAny.
     * 
     * @param instanceHandle the WebGPU instance handle
     * @param futures array of WGPUFuture handles to wait on
     * @param timeoutNanos timeout in nanoseconds (0 = no timeout)
     * @return WGPUWaitStatus result code
     */
    public static int waitForAnyFuture(MemorySegment instanceHandle, MemorySegment[] futures, long timeoutNanos) {
        if (futures == null || futures.length == 0) {
            log.warn("No futures provided to waitForAnyFuture");
            return WGPU_WAIT_STATUS_ERROR;
        }
        
        try (var arena = Arena.ofConfined()) {
            // Create WGPUFutureWaitInfo array
            var futureWaitInfos = arena.allocate(WGPU_FUTURE_WAIT_INFO_LAYOUT, futures.length);
            
            for (int i = 0; i < futures.length; i++) {
                var waitInfo = createFutureWaitInfo(arena, futures[i]);
                long structSize = WGPU_FUTURE_WAIT_INFO_LAYOUT.byteSize();
                MemorySegment.copy(waitInfo, 0, futureWaitInfos, 
                                 i * structSize, 
                                 structSize);
            }
            
            // Call wgpuInstanceWaitAny through reflection to access private method handle
            try {
                var webgpuClass = WebGPU.class;
                var field = webgpuClass.getDeclaredField("wgpuInstanceWaitAny");
                field.setAccessible(true);
                var wgpuInstanceWaitAny = field.get(null);
                
                if (wgpuInstanceWaitAny == null) {
                    log.warn("wgpuInstanceWaitAny not available - falling back to processEvents");
                    // Fallback to processEvents and return timeout
                    WebGPU.instanceProcessEvents(instanceHandle);
                    return WGPU_WAIT_STATUS_TIMED_OUT;
                }
                
                var methodHandle = (java.lang.invoke.MethodHandle) wgpuInstanceWaitAny;
                int status = (int) methodHandle.invoke(
                    instanceHandle,       // WGPUInstance
                    (long) futures.length, // size_t futureCount  
                    futureWaitInfos,      // WGPUFutureWaitInfo* futures
                    timeoutNanos          // uint64_t timeoutNS
                );
                
                log.debug("wgpuInstanceWaitAny returned status: {}", status);
                return status;
                
            } catch (Throwable e) {
                log.error("Failed to call wgpuInstanceWaitAny", e);
                return WGPU_WAIT_STATUS_ERROR;
            }
        }
    }
    
    /**
     * Wait for a single future to complete with timeout.
     * 
     * @param instanceHandle the WebGPU instance handle
     * @param future the WGPUFuture handle to wait on
     * @param timeoutNanos timeout in nanoseconds (0 = no timeout)
     * @return WGPUWaitStatus result code
     */
    public static int waitForFuture(MemorySegment instanceHandle, MemorySegment future, long timeoutNanos) {
        return waitForAnyFuture(instanceHandle, new MemorySegment[]{future}, timeoutNanos);
    }
    
    /**
     * Convert milliseconds to nanoseconds for timeout values.
     * 
     * @param millis timeout in milliseconds
     * @return timeout in nanoseconds
     */
    public static long millisToNanos(long millis) {
        return millis * 1_000_000L;
    }
    
    /**
     * Check if the wgpuInstanceWaitAny API is available.
     * @return true if the next-generation future-based waiting API is loaded
     */
    public static boolean isInstanceWaitAnyAvailable() {
        try {
            var webgpuClass = Class.forName("com.hellblazer.luciferase.webgpu.WebGPU");
            var field = webgpuClass.getDeclaredField("wgpuInstanceWaitAny");
            field.setAccessible(true);
            var wgpuInstanceWaitAny = field.get(null);
            return wgpuInstanceWaitAny != null;
        } catch (Exception e) {
            return false;
        }
    }
}