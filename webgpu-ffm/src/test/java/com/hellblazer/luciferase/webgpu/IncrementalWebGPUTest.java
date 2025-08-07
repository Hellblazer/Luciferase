package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.WebGPU;
import com.hellblazer.luciferase.webgpu.CallbackHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Incremental test building from simplest to complex to identify failure point.
 * 
 * DISABLED: This test is specifically designed to test FFM callback functionality
 * which crashes with SIGBUS at 0xa90247f0a9010fe2. The issue is documented in
 * FFM_CALLBACK_CRASH_ANALYSIS.md and affects any test that uses FFM callbacks.
 * 
 * We now use the synchronous enumerateAdapters API as a workaround.
 */
@Disabled("FFM callback functionality crashes - see FFM_CALLBACK_CRASH_ANALYSIS.md")
public class IncrementalWebGPUTest {
    
    @Test
    void test01_InitializeWebGPU() {
        System.out.println("\n=== Test 01: Initialize WebGPU ===");
        boolean result = WebGPU.initialize();
        assertTrue(result);
        System.out.println("✓ WebGPU initialized");
    }
    
    @Test
    void test02_CreateInstance() {
        System.out.println("\n=== Test 02: Create Instance ===");
        WebGPU.initialize();
        var instance = WebGPU.createInstance();
        assertNotNull(instance);
        assertNotEquals(MemorySegment.NULL, instance);
        System.out.println("✓ Instance created: 0x" + Long.toHexString(instance.address()));
        WebGPU.releaseInstance(instance);
    }
    
    @Test
    void test03_LoadRequestAdapterFunction() {
        System.out.println("\n=== Test 03: Load RequestAdapter Function ===");
        WebGPU.initialize();
        
        // Use reflection to check if the function handle was loaded
        try {
            var field = WebGPU.class.getDeclaredField("wgpuInstanceRequestAdapter");
            field.setAccessible(true);
            var handle = field.get(null);
            assertNotNull(handle, "wgpuInstanceRequestAdapter handle should be loaded");
            System.out.println("✓ RequestAdapter function handle loaded: " + handle);
        } catch (Exception e) {
            fail("Failed to check function handle: " + e.getMessage());
        }
    }
    
    @Test
    void test04_CreateCallback() {
        System.out.println("\n=== Test 04: Create Callback ===");
        try (var arena = Arena.ofConfined()) {
            var callback = new CallbackHelper.AdapterCallback(arena);
            var stub = callback.getCallbackStub();
            assertNotNull(stub);
            
            var addr = stub.address();
            System.out.println("✓ Callback created at: 0x" + Long.toHexString(addr));
            
            // Validate it's not the problematic address
            assertNotEquals(0xa90247f0a9010fe2L, addr);
            // Validate it looks like a code address
            assertTrue(addr > 0x100000000L && addr < 0x800000000000L);
        }
    }
    
    @Test
    void test05_CreateSimpleCallback() {
        System.out.println("\n=== Test 05: Create Simple Test Callback ===");
        try (var arena = Arena.ofConfined()) {
            // Create a simple callback that just prints
            var callbackHandle = MethodHandles.lookup()
                .findStatic(IncrementalWebGPUTest.class, "simpleCallback",
                    MethodType.methodType(void.class, int.class, MemorySegment.class, 
                                         MemorySegment.class, MemorySegment.class));
            
            var descriptor = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS
            );
            
            var stub = Linker.nativeLinker().upcallStub(callbackHandle, descriptor, arena);
            assertNotNull(stub);
            
            var addr = stub.address();
            System.out.println("✓ Simple callback created at: 0x" + Long.toHexString(addr));
            assertNotEquals(0xa90247f0a9010fe2L, addr);
        } catch (Exception e) {
            fail("Failed to create callback: " + e.getMessage());
        }
    }
    
    @Test
    void test06_CallRequestAdapterNoCallback() {
        System.out.println("\n=== Test 06: Call RequestAdapter with NULL Callback ===");
        System.out.println("NOTE: This might fail/timeout as adapter requires callback");
        
        WebGPU.initialize();
        var instance = WebGPU.createInstance();
        
        try {
            // Try calling with NULL callback - this will likely fail but let's see how
            var linker = Linker.nativeLinker();
            var lookup = SymbolLookup.loaderLookup();
            var funcOpt = lookup.find("wgpuInstanceRequestAdapter");
            
            if (funcOpt.isPresent()) {
                var func = linker.downcallHandle(
                    funcOpt.get(),
                    FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS,  // instance
                        ValueLayout.ADDRESS,  // options
                        ValueLayout.ADDRESS,  // callback
                        ValueLayout.ADDRESS   // userdata
                    )
                );
                
                System.out.println("Calling with NULL callback...");
                func.invokeExact(instance, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
                System.out.println("✓ Call completed (no crash)");
            }
        } catch (Throwable e) {
            System.out.println("Expected failure: " + e.getMessage());
        }
        
        WebGPU.releaseInstance(instance);
    }
    
    @Test
    void test07_CallRequestAdapterWithCallback() {
        System.out.println("\n=== Test 07: Call RequestAdapter with Our Callback ===");
        
        WebGPU.initialize();
        var instance = WebGPU.createInstance();
        
        try (var arena = Arena.global()) {  // Use global arena so callback survives
            var callback = new CallbackHelper.AdapterCallback(arena);
            var stub = callback.getCallbackStub();
            
            System.out.println("Instance: 0x" + Long.toHexString(instance.address()));
            System.out.println("Callback: 0x" + Long.toHexString(stub.address()));
            
            // Get the function directly
            var linker = Linker.nativeLinker();
            var lookup = SymbolLookup.loaderLookup();
            var funcOpt = lookup.find("wgpuInstanceRequestAdapter");
            
            if (funcOpt.isPresent()) {
                var func = linker.downcallHandle(
                    funcOpt.get(),
                    FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS,  // instance
                        ValueLayout.ADDRESS,  // options
                        ValueLayout.ADDRESS,  // callback
                        ValueLayout.ADDRESS   // userdata
                    )
                );
                
                System.out.println("About to call wgpuInstanceRequestAdapter...");
                func.invokeExact(instance, MemorySegment.NULL, stub, MemorySegment.NULL);
                System.out.println("✓ Call completed");
                
                // Wait a bit for callback
                Thread.sleep(100);
                
                var result = callback.waitForResult(1, java.util.concurrent.TimeUnit.SECONDS);
                if (result != null) {
                    System.out.println("✓ Got adapter: 0x" + Long.toHexString(result.address()));
                } else {
                    System.out.println("⚠ No adapter returned");
                }
            }
        } catch (Throwable e) {
            System.err.println("✗ CRASH AT THIS POINT: " + e.getMessage());
            e.printStackTrace();
        }
        
        WebGPU.releaseInstance(instance);
    }
    
    public static void simpleCallback(int status, MemorySegment adapter, 
                                     MemorySegment message, MemorySegment userdata) {
        System.out.println("Simple callback invoked! Status: " + status);
    }
}