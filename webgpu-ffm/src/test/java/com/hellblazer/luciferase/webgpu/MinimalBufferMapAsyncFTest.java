package com.hellblazer.luciferase.webgpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal test to figure out the exact struct layout Dawn expects.
 */
public class MinimalBufferMapAsyncFTest {
    private static final Logger log = LoggerFactory.getLogger(MinimalBufferMapAsyncFTest.class);
    
    @Test
    @DisplayName("Test struct layouts to find correct CallbackInfo format")
    void testCallbackInfoStructLayouts() throws Exception {
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
        } catch (Throwable e) {
            log.error("Failed to create buffer", e);
            fail("Failed to create buffer: " + e.getMessage());
            return;
        }
        
        // Get wgpuBufferMapAsyncF handle
        var webgpuClass = WebGPU.class;
        var field = webgpuClass.getDeclaredField("wgpuBufferMapAsyncF");
        field.setAccessible(true);
        var wgpuBufferMapAsyncF = (MethodHandle) field.get(null);
        
        if (wgpuBufferMapAsyncF == null) {
            log.warn("wgpuBufferMapAsyncF not available");
            return;
        }
        
        // Create a dummy callback
        var callback = createDummyCallback(arena);
        
        log.info("Testing different struct layouts to find the correct one...");
        
        // Try Layout 1: Standard C struct with natural alignment
        testLayout1(arena, wgpuBufferMapAsyncF, bufferHandle, callback);
        
        // Try Layout 2: Packed struct (no padding)
        testLayout2(arena, wgpuBufferMapAsyncF, bufferHandle, callback);
        
        // Try Layout 3: Different field order
        testLayout3(arena, wgpuBufferMapAsyncF, bufferHandle, callback);
        
        // Clean up WebGPU resources
        WebGPU.releaseInstance(instanceHandle);
        arena.close();
    }
    
    private void testLayout1(Arena arena, MethodHandle mapAsync, MemorySegment buffer, MemorySegment callback) {
        log.info("\n=== Testing Layout 1: Standard C struct ===");
        
        // Standard C struct with natural alignment
        var layout = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("next"),        // 0: 8 bytes
            ValueLayout.JAVA_INT.withName("sType"),      // 8: 4 bytes
            MemoryLayout.paddingLayout(4),               // 12: padding
            ValueLayout.JAVA_INT.withName("mode"),       // 16: 4 bytes
            MemoryLayout.paddingLayout(4),               // 20: padding
            ValueLayout.ADDRESS.withName("callback"),    // 24: 8 bytes
            ValueLayout.ADDRESS.withName("userdata1"),   // 32: 8 bytes
            ValueLayout.ADDRESS.withName("userdata2")    // 40: 8 bytes
        );
        
        var struct = arena.allocate(layout);
        struct.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
        struct.set(ValueLayout.JAVA_INT, 8, 0x00050003);
        struct.set(ValueLayout.JAVA_INT, 16, 1);
        struct.set(ValueLayout.ADDRESS, 24, callback);
        struct.set(ValueLayout.ADDRESS, 32, MemorySegment.NULL);
        struct.set(ValueLayout.ADDRESS, 40, MemorySegment.NULL);
        
        log.info("Struct size: {} bytes", layout.byteSize());
        invokeAndCheck(mapAsync, buffer, struct, "Layout 1");
    }
    
    private void testLayout2(Arena arena, MethodHandle mapAsync, MemorySegment buffer, MemorySegment callback) {
        log.info("\n=== Testing Layout 2: Packed struct ===");
        
        // Packed struct (no padding)
        var layout = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("next"),        // 0: 8 bytes
            ValueLayout.JAVA_INT.withName("sType"),      // 8: 4 bytes
            ValueLayout.JAVA_INT.withName("mode"),       // 12: 4 bytes
            ValueLayout.ADDRESS.withName("callback"),    // 16: 8 bytes
            ValueLayout.ADDRESS.withName("userdata1"),   // 24: 8 bytes
            ValueLayout.ADDRESS.withName("userdata2")    // 32: 8 bytes
        );
        
        var struct = arena.allocate(layout);
        struct.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
        struct.set(ValueLayout.JAVA_INT, 8, 0x00050003);
        struct.set(ValueLayout.JAVA_INT, 12, 1);
        struct.set(ValueLayout.ADDRESS, 16, callback);
        struct.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL);
        struct.set(ValueLayout.ADDRESS, 32, MemorySegment.NULL);
        
        log.info("Struct size: {} bytes", layout.byteSize());
        invokeAndCheck(mapAsync, buffer, struct, "Layout 2");
    }
    
    private void testLayout3(Arena arena, MethodHandle mapAsync, MemorySegment buffer, MemorySegment callback) {
        log.info("\n=== Testing Layout 3: Mode before sType ===");
        
        // Maybe mode comes before sType?
        var layout = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("next"),        // 0: 8 bytes
            ValueLayout.JAVA_INT.withName("mode"),       // 8: 4 bytes
            ValueLayout.JAVA_INT.withName("sType"),      // 12: 4 bytes
            ValueLayout.ADDRESS.withName("callback"),    // 16: 8 bytes
            ValueLayout.ADDRESS.withName("userdata1"),   // 24: 8 bytes
            ValueLayout.ADDRESS.withName("userdata2")    // 32: 8 bytes
        );
        
        var struct = arena.allocate(layout);
        struct.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
        struct.set(ValueLayout.JAVA_INT, 8, 1);  // mode first
        struct.set(ValueLayout.JAVA_INT, 12, 0x00050003);  // sType second
        struct.set(ValueLayout.ADDRESS, 16, callback);
        struct.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL);
        struct.set(ValueLayout.ADDRESS, 32, MemorySegment.NULL);
        
        log.info("Struct size: {} bytes", layout.byteSize());
        invokeAndCheck(mapAsync, buffer, struct, "Layout 3");
    }
    
    private void invokeAndCheck(MethodHandle mapAsync, MemorySegment buffer, MemorySegment struct, String layoutName) {
        try {
            log.info("Invoking wgpuBufferMapAsyncF with {}...", layoutName);
            var result = (MemorySegment) mapAsync.invoke(buffer, 1, 0L, 256L, struct);
            
            if (result != null && !result.equals(MemorySegment.NULL)) {
                log.info("✅ {} SUCCESS: Got future 0x{}", layoutName, Long.toHexString(result.address()));
            } else {
                log.info("❌ {} returned NULL", layoutName);
            }
        } catch (Throwable e) {
            log.error("❌ {} failed with exception", layoutName, e);
        }
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
    
    private MemorySegment createDummyCallback(Arena arena) {
        try {
            var linker = Linker.nativeLinker();
            var descriptor = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS
            );
            
            var lookup = java.lang.invoke.MethodHandles.lookup();
            var method = lookup.findStatic(MinimalBufferMapAsyncFTest.class, "dummyCallback",
                java.lang.invoke.MethodType.methodType(void.class, int.class, MemorySegment.class, MemorySegment.class));
            
            return linker.upcallStub(method, descriptor, Arena.global());
        } catch (Exception e) {
            return MemorySegment.NULL;
        }
    }
    
    public static void dummyCallback(int status, MemorySegment ud1, MemorySegment ud2) {
        log.info("Callback invoked with status: {}", status);
    }
}