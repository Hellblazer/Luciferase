package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic test to determine WebGPU hardware/driver issues.
 */
public class WebGPUHardwareDiagnosticTest {
    private static final Logger log = LoggerFactory.getLogger(WebGPUHardwareDiagnosticTest.class);
    
    @Test
    void diagnoseWebGPUSetup() {
        log.info("=== WebGPU Hardware/Driver Diagnostic ===");
        log.info("Java version: {}", System.getProperty("java.version"));
        log.info("OS: {} {}", System.getProperty("os.name"), System.getProperty("os.arch"));
        
        try {
            log.info("1. Initializing WebGPU...");
            boolean initialized = WebGPU.initialize();
            log.info("WebGPU initialization: {}", initialized ? "SUCCESS" : "FAILED");
            
            if (!initialized) {
                log.error("ERROR: WebGPU failed to initialize!");
                throw new RuntimeException("WebGPU initialization failed - check native library");
            }
            
            log.info("2. Creating WebGPU instance...");
            Instance instance = new Instance();
            log.info("Instance created: 0x{}", Long.toHexString(instance.getHandle().address()));
            
            log.info("3. Requesting adapter...");
            var adapterFuture = instance.requestAdapter();
            Adapter adapter = adapterFuture.get();
            log.info("Adapter created: 0x{}", Long.toHexString(adapter.getHandle().address()));
            
            log.info("4. Getting adapter info...");
            log.info("Adapter: {}", adapter.toString());
            
            log.info("5. Requesting device...");
            var deviceFuture = adapter.requestDevice();
            Device device = deviceFuture.get();
            log.info("Device created: 0x{}", Long.toHexString(device.getHandle().address()));
            
            log.info("6. Getting queue...");
            Queue queue = device.getQueue();
            log.info("Queue created successfully");
            
            log.info("7. Creating test buffer...");
            Device.BufferDescriptor desc = new Device.BufferDescriptor(1024, 
                Buffer.Usage.combine(Buffer.Usage.STORAGE, Buffer.Usage.COPY_DST));
            Buffer buffer = device.createBuffer(desc);
            log.info("Buffer created: 0x{}", Long.toHexString(buffer.getHandle().address()));
            
            log.info("8. Testing buffer write...");
            byte[] testData = new byte[256];
            for (int i = 0; i < testData.length; i++) {
                testData[i] = (byte) (i % 256);
            }
            queue.writeBuffer(buffer, 0, testData);
            log.info("Buffer write completed successfully");
            
            log.info("=== WebGPU HARDWARE/DRIVER IS FULLY FUNCTIONAL! ===");
            log.info("No mock fallbacks should occur - hardware acceleration available");
            
            // Cleanup
            buffer.close();
            device.close();
            adapter.close();
            instance.close();
            
        } catch (Exception e) {
            log.error("WebGPU diagnostic failed at step: {}", e.getMessage());
            log.error("Stack trace:", e);
            throw new RuntimeException("WebGPU setup is broken: " + e.getMessage(), e);
        }
    }
}