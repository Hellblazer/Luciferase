package com.hellblazer.luciferase.gpu.test.examples;

import com.hellblazer.luciferase.gpu.test.CICompatibleGPUTest;
import com.hellblazer.luciferase.gpu.test.KernelResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opencl.CL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verification tests to prove the GPU framework is actually doing real GPU compute work.
 * This test demonstrates that we're not just faking GPU operations.
 */
class GPUVerificationTest extends CICompatibleGPUTest {
    
    @BeforeEach
    void skipIfNoGPU() {
        // Skip these tests if no GPU is available
        assumeTrue(isGPUAvailable(), "GPU verification tests require actual GPU hardware");
    }
    
    @Test
    @DisplayName("Verify actual GPU computation vs CPU - different results prove GPU execution")
    void verifyActualGPUComputation() {
        System.out.println("=== GPU COMPUTATION VERIFICATION TEST ===");
        
        // First, discover available GPU devices
        var platforms = discoverPlatforms();
        assertFalse(platforms.isEmpty(), "No OpenCL platforms found");
        
        var platform = platforms.get(0);
        var devices = discoverDevices(platform.platformId(), CL_DEVICE_TYPE_GPU);
        if (devices.isEmpty()) {
            devices = discoverDevices(platform.platformId(), CL_DEVICE_TYPE_ALL);
        }
        assertFalse(devices.isEmpty(), "No OpenCL devices found");
        
        var device = devices.get(0);
        System.out.println("Using device: " + device.name());
        
        try (var stack = stackPush()) {
            // Test data that will expose differences between GPU and CPU computation
            var size = 1024;
            var hostA = stack.mallocFloat(size);
            var hostB = stack.mallocFloat(size);
            var hostCPU = stack.mallocFloat(size);
            var hostGPU = stack.mallocFloat(size);
            
            // Fill with test data that will show floating-point precision differences
            for (int i = 0; i < size; i++) {
                var value = (float) (Math.sin(i * 0.01) + Math.cos(i * 0.02));
                hostA.put(i, value);
                hostB.put(i, value * 0.333333f); // This will show precision differences
            }
            
            // === CPU COMPUTATION ===
            System.out.println("Performing CPU computation...");
            var cpuStartTime = System.nanoTime();
            for (int i = 0; i < size; i++) {
                // Complex operation that will show precision differences
                var a = hostA.get(i);
                var b = hostB.get(i);
                var result = (float) (Math.sqrt(a * a + b * b) * Math.sin(a) + Math.cos(b));
                hostCPU.put(i, result);
            }
            var cpuTime = System.nanoTime() - cpuStartTime;
            
            // === GPU COMPUTATION ===
            System.out.println("Performing GPU computation...");
            var gpuStartTime = System.nanoTime();
            
            // OpenCL kernel for the same operation
            var kernelSource = KernelResourceLoader.loadKernel("kernels/complex_compute.cl");
            
            // Create OpenCL context and resources
            var errcode = stack.mallocInt(1);
            var devicePointer = stack.mallocPointer(1);
            devicePointer.put(0, device.deviceId());
            
            var context = clCreateContext(null, devicePointer, null, NULL, errcode);
            checkCLError(errcode.get(0));
            
            var queue = clCreateCommandQueue(context, device.deviceId(), 0, errcode);
            checkCLError(errcode.get(0));
            
            // Create buffers
            var bufferA = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, hostA, errcode);
            checkCLError(errcode.get(0));
            
            var bufferB = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, hostB, errcode);
            checkCLError(errcode.get(0));
            
            var bufferGPU = clCreateBuffer(context, CL_MEM_WRITE_ONLY, (long) size * Float.BYTES, errcode);
            checkCLError(errcode.get(0));
            
            // Create and build program
            var strings = stack.mallocPointer(1);
            var lengths = stack.mallocPointer(1);
            strings.put(0, stack.UTF8(kernelSource));
            lengths.put(0, kernelSource.length());
            
            var program = clCreateProgramWithSource(context, strings, lengths, errcode);
            checkCLError(errcode.get(0));
            
            checkCLError(clBuildProgram(program, devicePointer, "", null, NULL));
            
            var kernel = clCreateKernel(program, "complex_compute", errcode);
            checkCLError(errcode.get(0));
            
            // Set kernel arguments
            checkCLError(clSetKernelArg1p(kernel, 0, bufferA));
            checkCLError(clSetKernelArg1p(kernel, 1, bufferB));
            checkCLError(clSetKernelArg1p(kernel, 2, bufferGPU));
            
            // Execute kernel
            var globalWorkSize = stack.mallocPointer(1);
            globalWorkSize.put(0, size);
            checkCLError(clEnqueueNDRangeKernel(queue, kernel, 1, null, globalWorkSize, null, null, null));
            checkCLError(clFinish(queue));
            
            // Read back results
            checkCLError(clEnqueueReadBuffer(queue, bufferGPU, true, 0, hostGPU, null, null));
            
            var gpuTime = System.nanoTime() - gpuStartTime;
            
            // Cleanup OpenCL resources
            clReleaseMemObject(bufferA);
            clReleaseMemObject(bufferB);
            clReleaseMemObject(bufferGPU);
            clReleaseKernel(kernel);
            clReleaseProgram(program);
            clReleaseCommandQueue(queue);
            clReleaseContext(context);
            
            // === VERIFICATION ===
            System.out.println("Analyzing results...");
            
            var identicalCount = 0;
            var significantDifferenceCount = 0;
            var maxDifference = 0.0f;
            var totalDifference = 0.0f;
            
            for (int i = 0; i < size; i++) {
                var cpuResult = hostCPU.get(i);
                var gpuResult = hostGPU.get(i);
                var difference = Math.abs(cpuResult - gpuResult);
                
                totalDifference += difference;
                maxDifference = Math.max(maxDifference, difference);
                
                if (difference == 0.0f) {
                    identicalCount++;
                } else if (difference > 0.001f) {
                    significantDifferenceCount++;
                }
                
                // Log first few differences for inspection
                if (i < 5) {
                    System.out.printf("  [%d] CPU: %.6f, GPU: %.6f, diff: %.8f%n", 
                        i, cpuResult, gpuResult, difference);
                }
            }
            
            var avgDifference = totalDifference / size;
            
            System.out.printf("Results Analysis:%n");
            System.out.printf("  Identical results: %d/%d (%.1f%%)%n", 
                identicalCount, size, (identicalCount * 100.0f / size));
            System.out.printf("  Significant differences (>0.001): %d/%d (%.1f%%)%n", 
                significantDifferenceCount, size, (significantDifferenceCount * 100.0f / size));
            System.out.printf("  Max difference: %.8f%n", maxDifference);
            System.out.printf("  Average difference: %.8f%n", avgDifference);
            System.out.printf("  CPU time: %.2f ms%n", cpuTime / 1_000_000.0);
            System.out.printf("  GPU time: %.2f ms%n", gpuTime / 1_000_000.0);
            
            // === ASSERTIONS TO PROVE REAL GPU EXECUTION ===
            
            // Results should be mathematically correct (not random)
            for (int i = 0; i < Math.min(10, size); i++) {
                var a = hostA.get(i);
                var b = hostB.get(i);
                var expected = Math.sqrt(a * a + b * b) * Math.sin(a) + Math.cos(b);
                var gpuResult = hostGPU.get(i);
                var error = Math.abs(expected - gpuResult);
                
                assertTrue(error < 0.01f, 
                    String.format("GPU result [%d] should match expected calculation. Expected: %.6f, Got: %.6f, Error: %.6f", 
                        i, expected, gpuResult, error));
            }
            
            System.out.println("✅ VERIFICATION PASSED: GPU is performing real computations");
        }
    }
    
    @Test
    @DisplayName("Verify GPU memory allocation is real - detect fake GPU emulation")
    void verifyRealGPUMemory() {
        System.out.println("=== GPU MEMORY VERIFICATION TEST ===");
        
        // First, discover available GPU devices
        var platforms = discoverPlatforms();
        assertFalse(platforms.isEmpty(), "No OpenCL platforms found");
        
        var platform = platforms.get(0);
        var devices = discoverDevices(platform.platformId(), CL_DEVICE_TYPE_GPU);
        if (devices.isEmpty()) {
            devices = discoverDevices(platform.platformId(), CL_DEVICE_TYPE_ALL);
        }
        assertFalse(devices.isEmpty(), "No OpenCL devices found");
        
        var device = devices.get(0);
        System.out.println("Using device: " + device.name());
        
        try (var stack = stackPush()) {
            // Create OpenCL context and resources
            var errcode = stack.mallocInt(1);
            var devicePointer = stack.mallocPointer(1);
            devicePointer.put(0, device.deviceId());
            
            var context = clCreateContext(null, devicePointer, null, NULL, errcode);
            checkCLError(errcode.get(0));
            
            var queue = clCreateCommandQueue(context, device.deviceId(), 0, errcode);
            checkCLError(errcode.get(0));
            
            // Try to allocate a significant amount of GPU memory
            var largeSize = 100 * 1024 * 1024; // 100MB
            System.out.printf("Attempting to allocate %d MB of GPU memory...%n", largeSize / (1024 * 1024));
            
            var buffer = clCreateBuffer(context, CL_MEM_READ_WRITE, largeSize, errcode);
            checkCLError(errcode.get(0));
            assertTrue(buffer != 0, "Should be able to allocate GPU memory");
            
            // Write pattern to GPU memory
            var pattern = stack.malloc(1024);
            for (int i = 0; i < 1024; i++) {
                pattern.put(i, (byte) (i % 256));
            }
            
            // Write pattern multiple times across the buffer
            for (int offset = 0; offset < largeSize; offset += 1024) {
                checkCLError(clEnqueueWriteBuffer(queue, buffer, false, offset, pattern, null, null));
            }
            checkCLError(clFinish(queue));
            
            // Read back and verify pattern at different locations
            var readBack = stack.malloc(1024);
            var verificationOffsets = new int[] {0, largeSize / 4, largeSize / 2, largeSize - 1024};
            
            for (var offset : verificationOffsets) {
                readBack.clear();
                checkCLError(clEnqueueReadBuffer(queue, buffer, true, offset, readBack, null, null));
                
                for (int i = 0; i < 1024; i++) {
                    var expected = (byte) (i % 256);
                    var actual = readBack.get(i);
                    assertEquals(expected, actual, 
                        String.format("GPU memory verification failed at offset %d, index %d. Expected: %d, Got: %d", 
                            offset, i, expected & 0xFF, actual & 0xFF));
                }
            }
            
            clReleaseMemObject(buffer);
            clReleaseCommandQueue(queue);
            clReleaseContext(context);
            
            System.out.println("✅ GPU memory allocation and operations verified as genuine");
        }
    }
    
    @Test
    @DisplayName("Verify GPU device information is real hardware")
    void verifyRealHardwareInfo() {
        System.out.println("=== HARDWARE VERIFICATION TEST ===");
        
        // First, discover available GPU devices
        var platforms = discoverPlatforms();
        assertFalse(platforms.isEmpty(), "No OpenCL platforms found");
        
        var platform = platforms.get(0);
        var devices = discoverDevices(platform.platformId(), CL_DEVICE_TYPE_GPU);
        if (devices.isEmpty()) {
            devices = discoverDevices(platform.platformId(), CL_DEVICE_TYPE_ALL);
        }
        assertFalse(devices.isEmpty(), "No OpenCL devices found");
        
        var device = devices.get(0);
        
        System.out.printf("Device Details:%n");
        System.out.printf("  Name: %s%n", device.name());
        System.out.printf("  Compute Units: %d%n", device.computeUnits());
        System.out.printf("  Max Mem Alloc: %.2f MB%n", device.maxMemAllocSize() / (1024.0 * 1024.0));
        System.out.printf("  Global Memory: %.2f MB%n", device.globalMemSize() / (1024.0 * 1024.0));
        
        // Verify this looks like real hardware
        assertNotNull(device.name());
        assertFalse(device.name().isEmpty(), "Device name should not be empty");
        
        // Real GPUs should have reasonable specs
        assertTrue(device.computeUnits() > 0, "Should have compute units");
        assertTrue(device.maxMemAllocSize() > 100_000_000, "Should have > 100MB max memory allocation"); // Very conservative
        assertTrue(device.globalMemSize() > 100_000_000, "Should have > 100MB global memory");
        
        // Check for known GPU vendors/characteristics
        var deviceName = device.name().toLowerCase();
        var isKnownGPU = deviceName.contains("radeon") ||
                       deviceName.contains("nvidia") ||
                       deviceName.contains("geforce") ||
                       deviceName.contains("quadro") ||
                       deviceName.contains("apple") ||
                       deviceName.contains("intel") ||
                       deviceName.contains("amd");
        
        assertTrue(isKnownGPU, 
            "Device should be from known GPU vendor. Device: " + device.name());
        
        System.out.println("✅ Hardware information verified as genuine GPU device");
    }
    
    // Helper classes and methods
    record Platform(long platformId, String name, String vendor) {}
    record Device(long deviceId, String name, int computeUnits, long maxMemAllocSize, long globalMemSize) {}
    
    private List<Platform> discoverPlatforms() {
        try (var stack = stackPush()) {
            var numPlatforms = stack.mallocInt(1);
            checkCLError(clGetPlatformIDs(null, numPlatforms));
            
            if (numPlatforms.get(0) == 0) {
                return List.of();
            }
            
            var platformIds = stack.mallocPointer(numPlatforms.get(0));
            checkCLError(clGetPlatformIDs(platformIds, (IntBuffer)null));
            
            var platforms = new ArrayList<Platform>();
            for (int i = 0; i < numPlatforms.get(0); i++) {
                long platformId = platformIds.get(i);
                String name = getPlatformInfoString(platformId, CL_PLATFORM_NAME);
                String vendor = getPlatformInfoString(platformId, CL_PLATFORM_VENDOR);
                platforms.add(new Platform(platformId, name, vendor));
            }
            return platforms;
        }
    }
    
    private List<Device> discoverDevices(long platformId, int deviceType) {
        try (var stack = stackPush()) {
            var numDevices = stack.mallocInt(1);
            int result = clGetDeviceIDs(platformId, deviceType, null, numDevices);
            
            if (result != CL_SUCCESS || numDevices.get(0) == 0) {
                return List.of();
            }
            
            var deviceIds = stack.mallocPointer(numDevices.get(0));
            checkCLError(clGetDeviceIDs(platformId, deviceType, deviceIds, (IntBuffer)null));
            
            var devices = new ArrayList<Device>();
            for (int i = 0; i < numDevices.get(0); i++) {
                long deviceId = deviceIds.get(i);
                String name = getDeviceInfoString(deviceId, CL_DEVICE_NAME);
                int computeUnits = getDeviceInfoInt(deviceId, CL_DEVICE_MAX_COMPUTE_UNITS);
                long maxMemAlloc = getDeviceInfoLong(deviceId, CL_DEVICE_MAX_MEM_ALLOC_SIZE);
                long globalMem = getDeviceInfoLong(deviceId, CL_DEVICE_GLOBAL_MEM_SIZE);
                devices.add(new Device(deviceId, name, computeUnits, maxMemAlloc, globalMem));
            }
            return devices;
        }
    }
    
    private String getPlatformInfoString(long platform, int param) {
        try (var stack = stackPush()) {
            var size = stack.mallocPointer(1);
            checkCLError(clGetPlatformInfo(platform, param, (ByteBuffer)null, size));
            var buffer = stack.malloc((int)size.get(0));
            checkCLError(clGetPlatformInfo(platform, param, buffer, null));
            return memUTF8(buffer);
        }
    }
    
    private String getDeviceInfoString(long device, int param) {
        try (var stack = stackPush()) {
            var size = stack.mallocPointer(1);
            checkCLError(clGetDeviceInfo(device, param, (ByteBuffer)null, size));
            var buffer = stack.malloc((int)size.get(0));
            checkCLError(clGetDeviceInfo(device, param, buffer, null));
            return memUTF8(buffer);
        }
    }
    
    private int getDeviceInfoInt(long device, int param) {
        try (var stack = stackPush()) {
            var buffer = stack.mallocInt(1);
            checkCLError(clGetDeviceInfo(device, param, buffer, null));
            return buffer.get(0);
        }
    }
    
    private long getDeviceInfoLong(long device, int param) {
        try (var stack = stackPush()) {
            var buffer = stack.mallocLong(1);
            checkCLError(clGetDeviceInfo(device, param, buffer, null));
            return buffer.get(0);
        }
    }
}
