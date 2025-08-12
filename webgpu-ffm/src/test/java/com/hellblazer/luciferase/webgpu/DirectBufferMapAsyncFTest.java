package com.hellblazer.luciferase.webgpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct, focused test for wgpuBufferMapAsyncF functionality.
 * Tests the raw FFM binding without going through wrapper classes.
 */
public class DirectBufferMapAsyncFTest {
    private static final Logger log = LoggerFactory.getLogger(DirectBufferMapAsyncFTest.class);
    
    private MemorySegment instanceHandle;
    private MemorySegment adapterHandle;
    private MemorySegment deviceHandle;
    private MemorySegment queueHandle;
    private Arena arena;
    
    @BeforeEach
    void setUp() throws Exception {
        arena = Arena.ofConfined();
        
        if (!WebGPU.initialize()) {
            log.warn("WebGPU not available - skipping test");
            return;
        }
        
        // Create instance directly
        instanceHandle = WebGPU.createInstance();
        assertNotNull(instanceHandle);
        assertNotEquals(0L, instanceHandle.address());
        log.info("Created instance: 0x{}", Long.toHexString(instanceHandle.address()));
        
        // Request adapter directly
        adapterHandle = WebGPU.requestAdapter(instanceHandle, MemorySegment.NULL);
        assertNotNull(adapterHandle);
        assertNotEquals(0L, adapterHandle.address());
        log.info("Got adapter: 0x{}", Long.toHexString(adapterHandle.address()));
        
        // Request device directly
        deviceHandle = WebGPU.requestDevice(adapterHandle, MemorySegment.NULL);
        assertNotNull(deviceHandle);
        assertNotEquals(0L, deviceHandle.address());
        log.info("Got device: 0x{}", Long.toHexString(deviceHandle.address()));
        
        // Get queue
        queueHandle = WebGPU.getQueue(deviceHandle);
        assertNotNull(queueHandle);
        assertNotEquals(0L, queueHandle.address());
        log.info("Got queue: 0x{}", Long.toHexString(queueHandle.address()));
    }
    
    @AfterEach
    void tearDown() {
        // Clean up WebGPU resources
        if (instanceHandle != null) {
            WebGPU.releaseInstance(instanceHandle);
        }
        if (arena != null) {
            arena.close();
        }
    }
    
    @Test
    @DisplayName("Test raw wgpuBufferMapAsyncF function availability and invocation")
    void testRawBufferMapAsyncF() throws Exception {
        if (instanceHandle == null) {
            log.info("WebGPU not available - skipping test");
            return;
        }
        
        log.info("=== Testing Raw wgpuBufferMapAsyncF ===");
        
        // Check if wgpuBufferMapAsyncF is loaded
        var webgpuClass = WebGPU.class;
        var field = webgpuClass.getDeclaredField("wgpuBufferMapAsyncF");
        field.setAccessible(true);
        var wgpuBufferMapAsyncF = field.get(null);
        
        if (wgpuBufferMapAsyncF == null) {
            log.warn("wgpuBufferMapAsyncF not loaded - might not be available in this Dawn version");
            return;
        }
        
        log.info("wgpuBufferMapAsyncF is loaded: {}", wgpuBufferMapAsyncF);
        
        // Create a buffer with MAP_READ usage
        int bufferSize = 256;
        MemorySegment bufferHandle;
        try {
            bufferHandle = createBuffer(bufferSize, 0x0001 | 0x0008); // MAP_READ | COPY_DST
            assertNotNull(bufferHandle);
            assertNotEquals(0L, bufferHandle.address());
            log.info("Created buffer: 0x{}", Long.toHexString(bufferHandle.address()));
            
            // Write test data to buffer
            byte[] testData = new byte[bufferSize];
            for (int i = 0; i < bufferSize; i++) {
                testData[i] = (byte)(i % 256);
            }
            writeBuffer(bufferHandle, testData);
            log.info("Wrote {} bytes of test data", bufferSize);
        } catch (Throwable e) {
            log.error("Failed to create/write buffer", e);
            fail("Failed to setup buffer: " + e.getMessage());
            return;
        }
        
        // Create a simple V2 callback
        var callbackStub = createSimpleV2Callback();
        log.info("Created V2 callback stub: 0x{}", Long.toHexString(callbackStub.address()));
        
        // Create CallbackInfo struct
        var callbackInfo = createMinimalCallbackInfo(callbackStub);
        log.info("Created CallbackInfo struct: 0x{}", Long.toHexString(callbackInfo.address()));
        
        // Debug: Print struct contents
        debugPrintCallbackInfo(callbackInfo);
        
        // Call wgpuBufferMapAsyncF
        log.info("Calling wgpuBufferMapAsyncF...");
        log.info("  Buffer: 0x{}", Long.toHexString(bufferHandle.address()));
        log.info("  Mode: 1 (READ)");
        log.info("  Offset: 0");
        log.info("  Size: {}", bufferSize);
        log.info("  CallbackInfo: 0x{}", Long.toHexString(callbackInfo.address()));
        
        try {
            var methodHandle = (MethodHandle) wgpuBufferMapAsyncF;
            var futureResult = (MemorySegment) methodHandle.invoke(
                bufferHandle,
                1,              // MapMode.READ
                0L,             // offset
                (long)bufferSize, // size
                callbackInfo
            );
            
            log.info("wgpuBufferMapAsyncF returned: {}", futureResult);
            
            if (futureResult != null && !futureResult.equals(MemorySegment.NULL)) {
                log.info("SUCCESS: Got future handle: 0x{}", Long.toHexString(futureResult.address()));
                
                // Try to wait for it
                if (instanceHandle != null) {
                    int waitResult = waitForFuture(instanceHandle, futureResult, 1000);
                    log.info("Wait result: {}", waitResult);
                }
            } else {
                log.warn("wgpuBufferMapAsyncF returned NULL - API might not be fully implemented");
            }
            
        } catch (Throwable e) {
            log.error("Error calling wgpuBufferMapAsyncF", e);
            fail("Failed to call wgpuBufferMapAsyncF: " + e.getMessage());
        }
    }
    
    private MemorySegment createBuffer(int size, int usage) throws Throwable {
        // Create BufferDescriptor struct
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
        descriptor.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // nextInChain
        descriptor.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL); // label
        descriptor.set(ValueLayout.JAVA_INT, 16, usage);
        descriptor.set(ValueLayout.JAVA_LONG, 24, size);
        descriptor.set(ValueLayout.JAVA_BOOLEAN, 32, false);
        
        return WebGPU.createBuffer(deviceHandle, descriptor);
    }
    
    private void writeBuffer(MemorySegment buffer, byte[] data) throws Throwable {
        WebGPU.writeBuffer(queueHandle, buffer, 0, data);
    }
    
    private MemorySegment createSimpleV2Callback() {
        try {
            // Create a V2 callback that expects (status, userdata1, userdata2)
            var linker = Linker.nativeLinker();
            var descriptor = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_INT,    // status
                ValueLayout.ADDRESS,     // userdata1
                ValueLayout.ADDRESS      // userdata2
            );
            
            var lookup = java.lang.invoke.MethodHandles.lookup();
            var callbackMethod = lookup.findStatic(DirectBufferMapAsyncFTest.class, "v2CallbackImpl",
                java.lang.invoke.MethodType.methodType(void.class, int.class, MemorySegment.class, MemorySegment.class));
            
            return linker.upcallStub(callbackMethod, descriptor, Arena.global());
            
        } catch (Exception e) {
            log.error("Failed to create V2 callback", e);
            return MemorySegment.NULL;
        }
    }
    
    public static void v2CallbackImpl(int status, MemorySegment userdata1, MemorySegment userdata2) {
        log.info("V2 Callback invoked! Status: {}, Userdata1: {}, Userdata2: {}", 
                 status, userdata1, userdata2);
    }
    
    private MemorySegment createMinimalCallbackInfo(MemorySegment callback) {
        // Create a minimal but correct CallbackInfo struct (matches official WebGPU headers)
        var layout = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("nextInChain"),  // 0: nextInChain (8 bytes)
            ValueLayout.JAVA_INT.withName("mode"),       // 8: mode (4 bytes)
            MemoryLayout.paddingLayout(4),               // 12: padding (4 bytes)
            ValueLayout.ADDRESS.withName("callback"),    // 16: callback (8 bytes)
            ValueLayout.ADDRESS.withName("userdata1"),   // 24: userdata1 (8 bytes)
            ValueLayout.ADDRESS.withName("userdata2")    // 32: userdata2 (8 bytes)
        );
        
        var callbackInfo = arena.allocate(layout);
        
        // Set fields (no sType field!)
        callbackInfo.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);  // nextInChain
        callbackInfo.set(ValueLayout.JAVA_INT, 8, 0x00000002);         // mode = WGPUCallbackMode_AllowProcessEvents (FIXED)
        callbackInfo.set(ValueLayout.ADDRESS, 16, callback);           // callback
        callbackInfo.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL); // userdata1
        callbackInfo.set(ValueLayout.ADDRESS, 32, MemorySegment.NULL); // userdata2
        
        return callbackInfo;
    }
    
    private void debugPrintCallbackInfo(MemorySegment callbackInfo) {
        log.info("CallbackInfo contents (official WebGPU struct layout):");
        log.info("  nextInChain: 0x{}", Long.toHexString(callbackInfo.get(ValueLayout.ADDRESS, 0).address()));
        log.info("  mode: {} (at offset 8)", callbackInfo.get(ValueLayout.JAVA_INT, 8));
        log.info("  callback: 0x{} (at offset 16)", Long.toHexString(callbackInfo.get(ValueLayout.ADDRESS, 16).address()));
        log.info("  userdata1: 0x{} (at offset 24)", Long.toHexString(callbackInfo.get(ValueLayout.ADDRESS, 24).address()));
        log.info("  userdata2: 0x{} (at offset 32)", Long.toHexString(callbackInfo.get(ValueLayout.ADDRESS, 32).address()));
    }
    
    private int waitForFuture(MemorySegment instance, MemorySegment future, long timeoutMs) {
        try {
            // Try to use wgpuInstanceWaitAny if available
            return FutureWaitHelper.waitForFuture(instance, future, timeoutMs * 1_000_000L);
        } catch (Exception e) {
            log.warn("Could not wait for future: {}", e.getMessage());
            return -1;
        }
    }
}