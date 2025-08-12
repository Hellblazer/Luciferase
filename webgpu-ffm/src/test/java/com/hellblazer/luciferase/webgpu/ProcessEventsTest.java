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
 * Test if processing events helps with callback invocation.
 */
public class ProcessEventsTest {
    private static final Logger log = LoggerFactory.getLogger(ProcessEventsTest.class);
    private static final AtomicInteger callbackStatus = new AtomicInteger(-999);
    
    @Test
    @DisplayName("Test if processEvents allows callbacks to fire")
    void testProcessEvents() throws Exception {
        if (!WebGPU.initialize()) {
            log.warn("WebGPU not available - skipping test");
            return;
        }
        
        var arena = Arena.ofConfined();
        
        // Create minimal WebGPU resources
        var instanceHandle = WebGPU.createInstance();
        var adapterHandle = WebGPU.requestAdapter(instanceHandle, MemorySegment.NULL);
        var deviceHandle = WebGPU.requestDevice(adapterHandle, MemorySegment.NULL);
        var queueHandle = WebGPU.getQueue(deviceHandle);
        
        // Create buffer
        MemorySegment bufferHandle;
        try {
            bufferHandle = createTestBuffer(arena, deviceHandle);
        } catch (Throwable t) {
            log.error("Failed to create buffer", t);
            return;
        }
        
        // Write some data to buffer
        byte[] testData = new byte[256];
        for (int i = 0; i < 256; i++) {
            testData[i] = (byte)(i % 256);
        }
        WebGPU.writeBuffer(queueHandle, bufferHandle, 0, testData);
        
        // Create callback
        var callback = createRealCallback(arena);
        var callbackInfo = createCallbackInfo(arena, callback);
        
        // Get wgpuBufferMapAsyncF
        var webgpuClass = WebGPU.class;
        var mapAsyncField = webgpuClass.getDeclaredField("wgpuBufferMapAsyncF");
        mapAsyncField.setAccessible(true);
        var wgpuBufferMapAsyncF = (MethodHandle) mapAsyncField.get(null);
        
        if (wgpuBufferMapAsyncF == null) {
            log.warn("wgpuBufferMapAsyncF not available");
            return;
        }
        
        // Call mapAsyncF
        MemorySegment future;
        try {
            future = (MemorySegment) wgpuBufferMapAsyncF.invoke(
                bufferHandle, 1, 0L, 256L, callbackInfo
            );
        } catch (Throwable t) {
            log.error("Failed to map buffer", t);
            return;
        }
        
        log.info("Got future: 0x{}", Long.toHexString(future.address()));
        
        // Try different event processing strategies
        log.info("\nStrategy 1: Process events on device");
        for (int i = 0; i < 10; i++) {
            WebGPU.processEvents(deviceHandle, false);
            Thread.sleep(10);
            if (callbackStatus.get() != -999) {
                log.info("✅ Callback fired with status: {}", callbackStatus.get());
                break;
            }
        }
        
        if (callbackStatus.get() == -999) {
            log.info("Strategy 1 failed - callback not invoked");
            
            log.info("\nStrategy 2: Process events on instance");
            for (int i = 0; i < 10; i++) {
                WebGPU.instanceProcessEvents(instanceHandle);
                Thread.sleep(10);
                if (callbackStatus.get() != -999) {
                    log.info("✅ Callback fired with status: {}", callbackStatus.get());
                    break;
                }
            }
        }
        
        if (callbackStatus.get() == -999) {
            log.info("Strategy 2 failed - callback not invoked");
            
            log.info("\nStrategy 3: Wait with wgpuInstanceWaitAny");
            var waitAnyField = webgpuClass.getDeclaredField("wgpuInstanceWaitAny");
            waitAnyField.setAccessible(true);
            var wgpuInstanceWaitAny = (MethodHandle) waitAnyField.get(null);
            
            if (wgpuInstanceWaitAny != null) {
                var waitInfo = arena.allocate(16);
                waitInfo.set(ValueLayout.JAVA_LONG, 0, future.address());
                waitInfo.set(ValueLayout.JAVA_BYTE, 8, (byte)0);
                
                try {
                    int waitStatus = (int) wgpuInstanceWaitAny.invoke(
                        instanceHandle, 1L, waitInfo, 5_000_000_000L // 5 seconds
                    );
                    log.info("wgpuInstanceWaitAny returned: {} (1=SUCCESS, 2=TIMEOUT, 3=ERROR)", waitStatus);
                    
                    // Process events after wait
                    for (int i = 0; i < 5; i++) {
                        WebGPU.processEvents(deviceHandle, false);
                        WebGPU.instanceProcessEvents(instanceHandle);
                        Thread.sleep(10);
                        if (callbackStatus.get() != -999) {
                            log.info("✅ Callback fired after wait with status: {}", callbackStatus.get());
                            break;
                        }
                    }
                } catch (Throwable t) {
                    log.error("Failed to wait", t);
                }
            }
        }
        
        if (callbackStatus.get() == -999) {
            log.error("❌ Callback never fired!");
        } else {
            log.info("✅ Final callback status: {}", callbackStatus.get());
            
            // Continue processing events after callback to complete mapping
            log.info("Processing additional events to complete mapping...");
            for (int i = 0; i < 5; i++) {
                WebGPU.instanceProcessEvents(instanceHandle);
                WebGPU.processEvents(deviceHandle, false);
                Thread.sleep(10);
            }
            
            // FIXED: Use bufferGetConstMappedRange for READ-mapped buffers!
            log.info("Using bufferGetConstMappedRange for READ-mapped buffer");
            var mappedRange = WebGPU.bufferGetConstMappedRange(bufferHandle, 0, 256);
            if (mappedRange != null && !mappedRange.equals(MemorySegment.NULL)) {
                log.info("✅ Got mapped range: 0x{}", Long.toHexString(mappedRange.address()));
                
                // Read first few bytes
                byte[] readData = new byte[16];
                for (int i = 0; i < 16; i++) {
                    readData[i] = mappedRange.get(ValueLayout.JAVA_BYTE, i);
                }
                
                // Verify data
                boolean correct = true;
                for (int i = 0; i < 16; i++) {
                    if (readData[i] != testData[i]) {
                        correct = false;
                        break;
                    }
                }
                
                if (correct) {
                    log.info("✅ Data verification SUCCESS!");
                } else {
                    log.error("❌ Data verification failed");
                }
            } else {
                log.error("❌ getMappedRange returned NULL");
            }
        }
        
        // Clean up WebGPU resources
        WebGPU.releaseInstance(instanceHandle);
        arena.close();
    }
    
    private MemorySegment createTestBuffer(Arena arena, MemorySegment device) throws Throwable {
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
        descriptor.set(ValueLayout.JAVA_INT, 16, 0x0001 | 0x0008); // MAP_READ | COPY_DST
        descriptor.set(ValueLayout.JAVA_LONG, 24, 256L);
        descriptor.set(ValueLayout.JAVA_BOOLEAN, 32, false);
        
        return WebGPU.createBuffer(device, descriptor);
    }
    
    private MemorySegment createCallbackInfo(Arena arena, MemorySegment callback) {
        var callbackInfo = arena.allocate(40);
        callbackInfo.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);  // nextInChain
        callbackInfo.set(ValueLayout.JAVA_INT, 8, 0x00000002);         // mode = WGPUCallbackMode_AllowProcessEvents (FIXED)
        // No sType field! Adding 4 bytes padding at offset 12
        callbackInfo.set(ValueLayout.ADDRESS, 16, callback);           // callback
        callbackInfo.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL); // userdata1
        callbackInfo.set(ValueLayout.ADDRESS, 32, MemorySegment.NULL); // userdata2
        return callbackInfo;
    }
    
    private MemorySegment createRealCallback(Arena arena) {
        try {
            var linker = Linker.nativeLinker();
            var descriptor = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS
            );
            
            var lookup = java.lang.invoke.MethodHandles.lookup();
            var method = lookup.findStatic(ProcessEventsTest.class, "realCallback",
                java.lang.invoke.MethodType.methodType(void.class, int.class, MemorySegment.class, MemorySegment.class));
            
            return linker.upcallStub(method, descriptor, Arena.global());
        } catch (Exception e) {
            return MemorySegment.NULL;
        }
    }
    
    public static void realCallback(int status, MemorySegment ud1, MemorySegment ud2) {
        log.info("CALLBACK INVOKED! Status: {}, ud1: {}, ud2: {}", status, ud1, ud2);
        callbackStatus.set(status);
    }
}