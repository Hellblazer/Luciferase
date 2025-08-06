package com.hellblazer.luciferase.webgpu.wrapper;

import com.hellblazer.luciferase.webgpu.WebGPU;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.*;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tests for WebGPU wrapper classes.
 */
public class WrapperTest {
    
    private boolean skipTests = false;
    
    @BeforeEach
    void setUp() {
        // Check if we should skip tests (no native library)
        skipTests = !WebGPU.isAvailable();
        if (skipTests) {
            System.out.println("Skipping wrapper tests - WebGPU native library not available");
        }
    }
    
    @AfterEach
    void tearDown() {
        WebGPU.shutdown();
    }
    
    @Test
    void testInstanceCreation() {
        // Test instance creation
        // Since we now have native libraries packaged, this should work
        assertDoesNotThrow(() -> {
            WebGPU.initialize();
        }, "WebGPU should initialize successfully");
        
        // Test that we can check availability
        assertTrue(WebGPU.isAvailable(), "WebGPU should be available after initialization");
    }
    
    @Test
    void testInstanceWithMockHandle() {
        // Test with a mock handle
        try (var arena = Arena.ofConfined()) {
            var mockHandle = arena.allocate(8);
            mockHandle.set(ValueLayout.JAVA_LONG, 0, 0x12345678L);
            
            var instance = new Instance(mockHandle);
            assertNotNull(instance);
            assertTrue(instance.isValid());
            assertEquals(mockHandle, instance.getHandle());
            
            // Test adapter request
            var future = instance.requestAdapter();
            assertNotNull(future);
            
            // Test with options
            var options = new Instance.AdapterOptions()
                .withPowerPreference(Instance.PowerPreference.HIGH_PERFORMANCE)
                .withForceFallbackAdapter(true);
            
            assertEquals(Instance.PowerPreference.HIGH_PERFORMANCE, options.getPowerPreference());
            assertTrue(options.isForceFallbackAdapter());
            
            var futureWithOptions = instance.requestAdapter(options);
            assertNotNull(futureWithOptions);
            
            // Close instance
            instance.close();
            assertFalse(instance.isValid());
        }
    }
    
    @Test
    void testAdapterWrapper() {
        try (var arena = Arena.ofConfined()) {
            var mockHandle = arena.allocate(8);
            var adapter = new Adapter(mockHandle);
            
            assertNotNull(adapter);
            assertTrue(adapter.isValid());
            
            // Test properties
            var properties = adapter.getProperties();
            assertNotNull(properties);
            
            // Test device descriptor
            var descriptor = new Adapter.DeviceDescriptor()
                .withLabel("TestDevice")
                .withRequiredFeatures(1L, 2L, 3L);
            
            assertEquals("TestDevice", descriptor.getLabel());
            assertEquals(3, descriptor.getRequiredFeatures().length);
            
            // Test device request
            var future = adapter.requestDevice(descriptor);
            assertNotNull(future);
            
            // Close adapter
            adapter.close();
            assertFalse(adapter.isValid());
        }
    }
    
    @Test
    void testDeviceWrapper() {
        try (var arena = Arena.ofConfined()) {
            var deviceHandle = arena.allocate(8);
            var queueHandle = arena.allocate(8);
            var device = new Device(deviceHandle, queueHandle);
            
            assertNotNull(device);
            assertTrue(device.isValid());
            assertNotNull(device.getQueue());
            
            // Test buffer creation
            var bufferDesc = new Device.BufferDescriptor(1024, 0x80) // STORAGE
                .withLabel("TestBuffer")
                .withMappedAtCreation(false);
            
            assertEquals(1024, bufferDesc.getSize());
            assertEquals(0x80, bufferDesc.getUsage());
            assertEquals("TestBuffer", bufferDesc.getLabel());
            
            var buffer = device.createBuffer(bufferDesc);
            assertNotNull(buffer);
            assertEquals(1024, buffer.getSize());
            assertEquals(0x80, buffer.getUsage());
            
            // Test shader module creation
            var shaderDesc = new Device.ShaderModuleDescriptor(
                "@compute @workgroup_size(64) fn main() {}"
            ).withLabel("TestShader");
            
            var shader = device.createShaderModule(shaderDesc);
            assertNotNull(shader);
            assertTrue(shader.getCode().contains("@compute"));
            
            // Test compute pipeline creation
            var pipelineDesc = new Device.ComputePipelineDescriptor(shader)
                .withLabel("TestPipeline")
                .withEntryPoint("main");
            
            var pipeline = device.createComputePipeline(pipelineDesc);
            assertNotNull(pipeline);
            
            // Clean up
            buffer.close();
            shader.close();
            pipeline.close();
            device.close();
            assertFalse(device.isValid());
        }
    }
    
    @Test
    void testBufferWrapper() {
        try (var arena = Arena.ofConfined()) {
            var deviceHandle = arena.allocate(8);
            var queueHandle = arena.allocate(8);
            var device = new Device(deviceHandle, queueHandle);
            
            var buffer = new Buffer(1, 2048, 0x88, device); // STORAGE | COPY_DST
            
            assertEquals(1, buffer.getId());
            assertEquals(2048, buffer.getSize());
            assertEquals(0x88, buffer.getUsage());
            
            // Test map async
            var mapFuture = buffer.mapAsync(Buffer.MapMode.READ, 0, 1024);
            assertNotNull(mapFuture);
            
            // Test get mapped range
            var mappedRange = buffer.getMappedRange(0, 512);
            assertNotNull(mappedRange);
            
            // Test unmap
            assertDoesNotThrow(() -> buffer.unmap());
            
            // Close buffer
            buffer.close();
            
            // Should throw after close
            assertThrows(IllegalStateException.class, () -> {
                buffer.mapAsync(Buffer.MapMode.READ, 0, 1024);
            });
        }
    }
    
    @Test
    void testQueueWrapper() {
        try (var arena = Arena.ofConfined()) {
            var deviceHandle = arena.allocate(8);
            var queueHandle = arena.allocate(8);
            var device = new Device(deviceHandle, queueHandle);
            var queue = device.getQueue();
            
            assertNotNull(queue);
            
            // Create a buffer for testing
            var buffer = device.createBuffer(
                new Device.BufferDescriptor(1024, 0x08) // COPY_DST
            );
            
            // Test write buffer with byte array
            byte[] data = new byte[256];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) i;
            }
            
            assertDoesNotThrow(() -> {
                queue.writeBuffer(buffer, 0, data);
            });
            
            // Test write buffer with ByteBuffer
            ByteBuffer byteBuffer = ByteBuffer.allocate(128);
            for (int i = 0; i < 128; i++) {
                byteBuffer.put((byte) i);
            }
            byteBuffer.flip();
            
            assertDoesNotThrow(() -> {
                queue.writeBuffer(buffer, 256, byteBuffer);
            });
            
            // Test command submission
            var commandBuffer = new CommandBuffer(1);
            assertDoesNotThrow(() -> {
                queue.submit(commandBuffer);
            });
            
            // Test work done callback
            assertDoesNotThrow(() -> {
                queue.onSubmittedWorkDone();
            });
            
            // Clean up
            buffer.close();
            queue.close();
        }
    }
    
    @Test
    void testShaderModuleWrapper() {
        try (var arena = Arena.ofConfined()) {
            var deviceHandle = arena.allocate(8);
            var queueHandle = arena.allocate(8);
            var device = new Device(deviceHandle, queueHandle);
            
            String wgslCode = """
                @compute @workgroup_size(64, 1, 1)
                fn main(@builtin(global_invocation_id) global_id: vec3<u32>) {
                    // Compute shader code
                }
                """;
            
            var shader = new ShaderModule(1, wgslCode, device);
            
            assertEquals(1, shader.getId());
            assertEquals(wgslCode, shader.getCode());
            
            // Close shader
            shader.close();
        }
    }
    
    @Test
    void testComputePipelineWrapper() {
        try (var arena = Arena.ofConfined()) {
            var deviceHandle = arena.allocate(8);
            var queueHandle = arena.allocate(8);
            var device = new Device(deviceHandle, queueHandle);
            
            var pipeline = new ComputePipeline(1, device);
            
            assertEquals(1, pipeline.getId());
            
            // Close pipeline
            pipeline.close();
        }
    }
    
    @Test
    void testPowerPreferenceEnum() {
        assertEquals(0, Instance.PowerPreference.UNDEFINED.getValue());
        assertEquals(1, Instance.PowerPreference.LOW_POWER.getValue());
        assertEquals(2, Instance.PowerPreference.HIGH_PERFORMANCE.getValue());
    }
    
    @Test
    void testAdapterTypeEnum() {
        // Just verify enum values exist
        assertNotNull(Adapter.AdapterType.UNKNOWN);
        assertNotNull(Adapter.AdapterType.INTEGRATED_GPU);
        assertNotNull(Adapter.AdapterType.DISCRETE_GPU);
        assertNotNull(Adapter.AdapterType.VIRTUAL_GPU);
        assertNotNull(Adapter.AdapterType.CPU);
    }
    
    @Test
    void testBackendTypeEnum() {
        // Verify backend types exist
        assertNotNull(Adapter.BackendType.UNKNOWN);
        assertNotNull(Adapter.BackendType.VULKAN);
        assertNotNull(Adapter.BackendType.METAL);
        assertNotNull(Adapter.BackendType.D3D12);
        assertNotNull(Adapter.BackendType.OPENGL);
    }
    
    @Test
    void testMapModeEnum() {
        assertEquals(1, Buffer.MapMode.READ.getValue());
        assertEquals(2, Buffer.MapMode.WRITE.getValue());
    }
    
    @Test
    void testCommandBuffer() {
        var cmdBuffer = new CommandBuffer(42);
        assertEquals(42, cmdBuffer.getId());
    }
}