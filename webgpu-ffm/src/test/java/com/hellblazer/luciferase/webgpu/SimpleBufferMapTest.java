package com.hellblazer.luciferase.webgpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simplified test to debug buffer mapping.
 */
public class SimpleBufferMapTest {
    private static final Logger log = LoggerFactory.getLogger(SimpleBufferMapTest.class);
    private static final AtomicInteger callbackStatus = new AtomicInteger(-999);
    
    @Test
    @DisplayName("Simplest possible buffer mapping test")
    void testSimpleBufferMap() throws Exception {
        if (!WebGPU.initialize()) {
            log.warn("WebGPU not available - skipping test");
            return;
        }
        
        var arena = Arena.ofConfined();
        
        // Create WebGPU resources
        var instanceHandle = WebGPU.createInstance();
        var adapterHandle = WebGPU.requestAdapter(instanceHandle, MemorySegment.NULL);
        var deviceHandle = WebGPU.requestDevice(adapterHandle, MemorySegment.NULL);
        var queueHandle = WebGPU.getQueue(deviceHandle);
        
        log.info("Created WebGPU resources");
        
        // Create buffer with MAP_READ and COPY_DST
        MemorySegment bufferHandle;
        try {
            bufferHandle = createBuffer(arena, deviceHandle, 256, 0x0001 | 0x0008);
        } catch (Throwable t) {
            log.error("Failed to create buffer", t);
            return;
        }
        log.info("Created buffer: 0x{}", Long.toHexString(bufferHandle.address()));
        
        // Write data to buffer
        byte[] testData = new byte[256];
        for (int i = 0; i < 256; i++) {
            testData[i] = (byte)i;
        }
        WebGPU.writeBuffer(queueHandle, bufferHandle, 0, testData);
        log.info("Wrote test data to buffer");
        
        // Create callback
        var callback = createCallback(arena);
        log.info("Created callback: 0x{}", Long.toHexString(callback.address()));
        
        // Create CallbackInfo struct (official layout)
        var callbackInfo = arena.allocate(40);
        callbackInfo.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);  // nextInChain
        callbackInfo.set(ValueLayout.JAVA_INT, 8, 0x00000002);         // mode = WGPUCallbackMode_AllowProcessEvents (FIXED)
        // padding at offset 12
        callbackInfo.set(ValueLayout.ADDRESS, 16, callback);           // callback
        callbackInfo.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL); // userdata1
        callbackInfo.set(ValueLayout.ADDRESS, 32, MemorySegment.NULL); // userdata2
        
        log.info("Created CallbackInfo struct");
        
        // Get wgpuBufferMapAsyncF
        var webgpuClass = WebGPU.class;
        var mapAsyncField = webgpuClass.getDeclaredField("wgpuBufferMapAsyncF");
        mapAsyncField.setAccessible(true);
        var wgpuBufferMapAsyncF = (MethodHandle) mapAsyncField.get(null);
        
        if (wgpuBufferMapAsyncF == null) {
            log.error("wgpuBufferMapAsyncF not available");
            return;
        }
        
        // Call mapAsyncF
        log.info("Calling wgpuBufferMapAsyncF...");
        MemorySegment future;
        try {
            future = (MemorySegment) wgpuBufferMapAsyncF.invoke(
                bufferHandle,
                1,        // MapMode READ
                0L,       // offset
                256L,     // size
                callbackInfo
            );
        } catch (Throwable t) {
            log.error("Failed to call wgpuBufferMapAsyncF", t);
            return;
        }
        
        log.info("Got future: 0x{}", Long.toHexString(future.address()));
        
        // Get wgpuInstanceWaitAny
        var waitAnyField = webgpuClass.getDeclaredField("wgpuInstanceWaitAny");
        waitAnyField.setAccessible(true);
        var wgpuInstanceWaitAny = (MethodHandle) waitAnyField.get(null);
        
        if (wgpuInstanceWaitAny == null) {
            log.error("wgpuInstanceWaitAny not available");
            return;
        }
        
        // Create WGPUFutureWaitInfo
        var waitInfo = arena.allocate(16);
        waitInfo.set(ValueLayout.JAVA_LONG, 0, future.address()); // future.id
        waitInfo.set(ValueLayout.JAVA_BYTE, 8, (byte)0);          // completed = false
        
        log.info("Calling wgpuInstanceWaitAny with 10 second timeout...");
        
        try {
            int waitStatus = (int) wgpuInstanceWaitAny.invoke(
                instanceHandle,
                1L,                      // futureCount
                waitInfo,                // futures array
                10_000_000_000L         // 10 seconds in nanos
            );
            
            log.info("wgpuInstanceWaitAny returned: {} (1=SUCCESS, 2=TIMEOUT, 3=ERROR)", waitStatus);
            
            // Check if callback was invoked
            if (callbackStatus.get() != -999) {
                log.info("✅ Callback was invoked with status: {}", callbackStatus.get());
                
                // FIXED: Use bufferGetConstMappedRange for READ-mapped buffers!
                var mappedRange = WebGPU.bufferGetConstMappedRange(bufferHandle, 0, 256);
                if (mappedRange != null && !mappedRange.equals(MemorySegment.NULL)) {
                    log.info("✅ Got mapped range: 0x{}", Long.toHexString(mappedRange.address()));
                    
                    // Verify first few bytes
                    boolean correct = true;
                    for (int i = 0; i < 16; i++) {
                        byte b = mappedRange.get(ValueLayout.JAVA_BYTE, i);
                        if (b != (byte)i) {
                            correct = false;
                            log.error("Data mismatch at index {}: expected {}, got {}", i, (byte)i, b);
                            break;
                        }
                    }
                    
                    if (correct) {
                        log.info("✅ Data verification successful!");
                    }
                    
                    // Unmap buffer
                    WebGPU.unmapBuffer(bufferHandle);
                } else {
                    log.error("❌ getMappedRange returned NULL");
                }
            } else {
                log.error("❌ Callback was never invoked");
                
                // Try processEvents
                log.info("Trying processEvents...");
                for (int i = 0; i < 10; i++) {
                    WebGPU.processEvents(deviceHandle, false);
                    WebGPU.instanceProcessEvents(instanceHandle);
                    Thread.sleep(100);
                    if (callbackStatus.get() != -999) {
                        log.info("✅ Callback invoked after processEvents with status: {}", callbackStatus.get());
                        break;
                    }
                }
            }
            
        } catch (Throwable t) {
            log.error("Failed to wait", t);
        }
        
        // Clean up WebGPU resources
        WebGPU.releaseInstance(instanceHandle);
        arena.close();
    }
    
    private MemorySegment createBuffer(Arena arena, MemorySegment device, int size, int usage) throws Throwable {
        var descriptorLayout = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),
            ValueLayout.ADDRESS.withName("label"),
            ValueLayout.JAVA_INT.withName("usage"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.JAVA_LONG.withName("size"),
            ValueLayout.JAVA_BOOLEAN.withName("mappedAtCreation"),
            MemoryLayout.paddingLayout(7)
        );
        
        var descriptor = arena.allocate(descriptorLayout);
        descriptor.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
        descriptor.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
        descriptor.set(ValueLayout.JAVA_INT, 16, usage);
        descriptor.set(ValueLayout.JAVA_LONG, 24, (long)size);
        descriptor.set(ValueLayout.JAVA_BOOLEAN, 32, false);
        
        return WebGPU.createBuffer(device, descriptor);
    }
    
    private MemorySegment createCallback(Arena arena) {
        try {
            var linker = Linker.nativeLinker();
            var descriptor = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS
            );
            
            var lookup = java.lang.invoke.MethodHandles.lookup();
            var method = lookup.findStatic(SimpleBufferMapTest.class, "callbackImpl",
                java.lang.invoke.MethodType.methodType(void.class, int.class, MemorySegment.class, MemorySegment.class));
            
            return linker.upcallStub(method, descriptor, Arena.global());
        } catch (Exception e) {
            log.error("Failed to create callback", e);
            return MemorySegment.NULL;
        }
    }
    
    public static void callbackImpl(int status, MemorySegment ud1, MemorySegment ud2) {
        log.info("*** CALLBACK INVOKED! Status: {} (0=SUCCESS, 1=ERROR, 2=ABORTED, 3=UNKNOWN)", status);
        callbackStatus.set(status);
    }
}