package com.hellblazer.luciferase.webgpu.integration;

import com.hellblazer.luciferase.webgpu.WebGPU;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.junit.jupiter.api.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests that use actual GPU hardware.
 * These tests are conditional on GPU availability.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GPUIntegrationTest {
    
    private static Instance instance;
    private static Adapter adapter;
    private static Device device;
    private static boolean gpuAvailable = false;
    
    @BeforeAll
    static void setupGPU() {
        try {
            // Initialize WebGPU
            WebGPU.initialize();
            
            // Create instance
            instance = new Instance();
            assertNotNull(instance, "Failed to create WebGPU instance");
            
            // Request adapter (async)
            var adapterFuture = instance.requestAdapter(null);
            adapter = adapterFuture.get();
            gpuAvailable = (adapter != null);
            
            if (gpuAvailable) {
                // Request device (async)
                var deviceFuture = adapter.requestDevice(null);
                device = deviceFuture.get();
                assertNotNull(device, "Failed to create WebGPU device");
                
                System.out.println("GPU Integration Tests - Environment:");
                System.out.println("  Instance created: " + (!instance.getHandle().equals(MemorySegment.NULL)));
                System.out.println("  Adapter found: " + (!adapter.getHandle().equals(MemorySegment.NULL)));
                System.out.println("  Device created: " + (!device.getHandle().equals(MemorySegment.NULL)));
            }
        } catch (Exception e) {
            System.err.println("GPU not available for testing: " + e.getMessage());
            gpuAvailable = false;
        }
    }
    
    @AfterAll
    static void cleanupGPU() {
        if (device != null) {
            device.close();
        }
        if (adapter != null) {
            adapter.close();
        }
        if (instance != null) {
            instance.close();
        }
    }
    
    @Test
    @Order(1)
    void testGPUDetection() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        assertNotNull(instance, "Instance should be created");
        assertNotNull(adapter, "Adapter should be found");
        assertNotNull(device, "Device should be created");
        
        assertFalse(instance.getHandle().equals(MemorySegment.NULL), "Instance handle should be valid");
        assertFalse(adapter.getHandle().equals(MemorySegment.NULL), "Adapter handle should be valid");
        assertFalse(device.getHandle().equals(MemorySegment.NULL), "Device handle should be valid");
    }
    
    @Test
    @Order(2)
    void testAdapterInfo() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Skip adapter info test - not implemented yet
        System.out.println("Adapter Information: (not yet implemented)");
    }
    
    @Test
    @Order(3)
    void testDeviceLimits() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Skip device limits test - not implemented yet
        System.out.println("Device Limits: (not yet implemented)");
    }
    
    @Test
    @Order(4)
    void testBufferCreationAndMapping() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Create a buffer
        int bufferSize = 256;
        var bufferDesc = new Device.BufferDescriptor(bufferSize, 
            WebGPUNative.BUFFER_USAGE_MAP_READ | WebGPUNative.BUFFER_USAGE_COPY_DST);
        var buffer = device.createBuffer(bufferDesc);
        
        assertNotNull(buffer, "Buffer should be created");
        assertEquals(bufferSize, buffer.getSize(), "Buffer size should match");
        
        // Clean up
        buffer.close();
    }
    
    @Test
    @Order(5)
    void testCommandEncoderAndQueue() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Get queue
        var queue = device.getQueue();
        assertNotNull(queue, "Queue should be available");
        
        // Skip command encoder test - not fully implemented yet
        System.out.println("Command Encoder and Queue: (not yet fully implemented)");
    }
    
    @Test
    @Order(6)
    void testSimpleComputeShader() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Create compute shader that doubles values
        String shaderCode = """
            @group(0) @binding(0)
            var<storage, read> input: array<f32>;
            
            @group(0) @binding(1)
            var<storage, read_write> output: array<f32>;
            
            @compute @workgroup_size(64)
            fn main(@builtin(global_invocation_id) global_id: vec3<u32>) {
                let index = global_id.x;
                if (index < arrayLength(&input)) {
                    output[index] = input[index] * 2.0;
                }
            }
            """;
        
        // Create shader module
        var shaderDesc = new Device.ShaderModuleDescriptor(shaderCode)
            .withLabel("compute");
        var shaderModule = device.createShaderModule(shaderDesc);
        assertNotNull(shaderModule, "Shader module should be created");
        
        // Skip the rest of compute shader test - not fully implemented yet
        System.out.println("Compute Shader: Created shader module (pipeline creation not yet implemented)");
        
        // Clean up
        shaderModule.close();
    }
    
    private byte[] floatArrayToBytes(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }
}