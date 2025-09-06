package com.hellblazer.luciferase.gpu.test;

import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.*;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opencl.CL11.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.junit.jupiter.api.Assertions.*;

import org.lwjgl.PointerBuffer;

/**
 * Base class for GPU compute testing in headless environments.
 * Tests both OpenCL compute capabilities without display dependencies.
 * 
 * Focuses on practical compute operations that would be used in ART neural network processing.
 */
public abstract class GPUComputeHeadlessTest extends OpenCLHeadlessTest {
    
    private static final Logger log = LoggerFactory.getLogger(GPUComputeHeadlessTest.class);
    
    /**
     * Information about an available OpenCL platform.
     */
    public record PlatformInfo(long platformId, String name, String vendor, String version) {}
    
    /**
     * Information about an available OpenCL device.
     */
    public record DeviceInfo(long deviceId, String name, long deviceType, 
                           int computeUnits, long maxMemAllocSize, long globalMemSize) {
        
        public boolean isGPU() {
            return (deviceType & CL_DEVICE_TYPE_GPU) != 0;
        }
        
        public boolean isCPU() {
            return (deviceType & CL_DEVICE_TYPE_CPU) != 0;
        }
        
        @Override
        public String toString() {
            var typeStr = isGPU() ? "GPU" : isCPU() ? "CPU" : "Other";
            return String.format("%s [%s] - %d CUs, %.1f MB mem", 
                               name, typeStr, computeUnits, maxMemAllocSize / 1024.0 / 1024.0);
        }
    }
    
    /**
     * Discover available OpenCL platforms and their capabilities.
     * 
     * @return list of available platforms with their information
     */
    protected List<PlatformInfo> discoverPlatforms() {
        // Check if we should use mock platform (CI environment or explicit request)
        if (MockPlatform.shouldUseMockPlatform()) {
            log.info("*****   Using mock platform for CI environment");
            return MockPlatform.getMockPlatforms();
        }
        
        var platforms = new ArrayList<PlatformInfo>();
        
        try {
            testMemoryAllocation(() -> {
                try (MemoryStack stack = stackPush()) {
                    IntBuffer pi = stack.mallocInt(1);
                    
                    int result = clGetPlatformIDs(null, pi);
                    if (result != CL_SUCCESS || pi.get(0) == 0) {
                        log.warn("No OpenCL platforms found");
                        return;
                    }
                    
                    int numPlatforms = pi.get(0);
                    PointerBuffer platformIds = stack.mallocPointer(numPlatforms);
                    checkCLError(clGetPlatformIDs(platformIds, (IntBuffer)null));
                    
                    for (int i = 0; i < numPlatforms; i++) {
                        long platformId = platformIds.get(i);
                        
                        var name = getPlatformInfoString(stack, platformId, CL_PLATFORM_NAME);
                        var vendor = getPlatformInfoString(stack, platformId, CL_PLATFORM_VENDOR);
                        var version = getPlatformInfoString(stack, platformId, CL_PLATFORM_VERSION);
                        
                        platforms.add(new PlatformInfo(platformId, name, vendor, version));
                        log.debug("Found platform: {} - {} ({})", name, vendor, version);
                    }
                }
            });
        } catch (Exception e) {
            log.warn("OpenCL platform discovery failed: {}", e.getMessage());
            log.info("Falling back to mock platform for CI compatibility");
            return MockPlatform.getMockPlatforms();
        }
        
        // If no platforms found, return mock platform
        if (platforms.isEmpty()) {
            log.info("No real platforms found, using mock platform");
            return MockPlatform.getMockPlatforms();
        }
        
        return platforms;
    }
    
    /**
     * Discover compute devices for a given platform.
     * 
     * @param platformId the OpenCL platform ID
     * @param deviceType the device type filter (CL_DEVICE_TYPE_GPU, CL_DEVICE_TYPE_CPU, etc.)
     * @return list of devices matching the criteria
     */
    protected List<DeviceInfo> discoverDevices(long platformId, long deviceType) {
        var devices = new ArrayList<DeviceInfo>();
        
        // If this is a mock platform (platformId = 0), return mock devices
        if (platformId == 0L) {
            log.info("Returning mock devices for mock platform");
            return MockPlatform.getMockDevices(deviceType);
        }
        
        testMemoryAllocation(() -> {
            try (MemoryStack stack = stackPush()) {
                IntBuffer pi = stack.mallocInt(1);
                
                int result = clGetDeviceIDs(platformId, deviceType, null, pi);
                if (result != CL_SUCCESS || pi.get(0) == 0) {
                    log.debug("No devices found for platform {} with type {}", platformId, deviceType);
                    return;
                }
                
                int numDevices = pi.get(0);
                PointerBuffer deviceIds = stack.mallocPointer(numDevices);
                checkCLError(clGetDeviceIDs(platformId, deviceType, deviceIds, (IntBuffer)null));
                
                for (int i = 0; i < numDevices; i++) {
                    long deviceId = deviceIds.get(i);
                    
                    var name = getDeviceInfoString(stack, deviceId, CL_DEVICE_NAME);
                    var type = getDeviceInfoLong(stack, deviceId, CL_DEVICE_TYPE);
                    var computeUnits = getDeviceInfoInt(stack, deviceId, CL_DEVICE_MAX_COMPUTE_UNITS);
                    var maxMemAlloc = getDeviceInfoLong(stack, deviceId, CL_DEVICE_MAX_MEM_ALLOC_SIZE);
                    var globalMem = getDeviceInfoLong(stack, deviceId, CL_DEVICE_GLOBAL_MEM_SIZE);
                    
                    devices.add(new DeviceInfo(deviceId, name, type, computeUnits, maxMemAlloc, globalMem));
                    log.debug("Found device: {}", devices.get(devices.size() - 1));
                }
            }
        });
        
        return devices;
    }
    
    /**
     * Test basic GPU compute capabilities with a simple vector addition kernel.
     * 
     * @param platformId the OpenCL platform to use
     * @param deviceId the GPU device to use
     */
    protected void testGPUVectorAddition(long platformId, long deviceId) {
        log.info("Testing GPU vector addition on device {}", deviceId);
        
        var kernelSource = """
            __kernel void vector_add(__global const float* A, __global const float* B, __global float* C, int N) {
                int i = get_global_id(0);
                if (i < N) {
                    C[i] = A[i] + B[i];
                }
            }
            """;
        
        testMemoryAllocation(() -> {
            try (MemoryStack stack = stackPush()) {
                // Test data
                int N = 1024;
                var hostA = stack.mallocFloat(N);
                var hostB = stack.mallocFloat(N);
                var hostC = stack.mallocFloat(N);
                
                // Initialize test data
                for (int i = 0; i < N; i++) {
                    hostA.put(i, i * 1.0f);
                    hostB.put(i, i * 2.0f);
                }
                
                IntBuffer errcode_ret = stack.mallocInt(1);
                PointerBuffer devices = stack.mallocPointer(1);
                devices.put(0, deviceId);
                
                // Create context and command queue
                long context = clCreateContext(null, devices, null, NULL, errcode_ret);
                checkCLError(errcode_ret.get(0));
                
                try {
                    long queue = clCreateCommandQueue(context, deviceId, 0, errcode_ret);
                    checkCLError(errcode_ret.get(0));
                    
                    try {
                        // Create and build program
                        PointerBuffer strings = stack.mallocPointer(1);
                        PointerBuffer lengths = stack.mallocPointer(1);
                        strings.put(0, stack.UTF8(kernelSource));
                        lengths.put(0, kernelSource.length());
                        
                        long program = clCreateProgramWithSource(context, strings, lengths, errcode_ret);
                        checkCLError(errcode_ret.get(0));
                        
                        try {
                            int buildResult = clBuildProgram(program, devices, "", null, NULL);
                            if (buildResult != CL_SUCCESS) {
                                // Get build log for debugging
                                PointerBuffer logSize = stack.mallocPointer(1);
                                clGetProgramBuildInfo(program, deviceId, CL_PROGRAM_BUILD_LOG, (ByteBuffer)null, logSize);
                                
                                if (logSize.get(0) > 0) {
                                    ByteBuffer buildLog = stack.malloc((int)logSize.get(0));
                                    clGetProgramBuildInfo(program, deviceId, CL_PROGRAM_BUILD_LOG, buildLog, null);
                                    log.warn("Kernel build failed: {}", memUTF8(buildLog, (int)logSize.get(0) - 1));
                                }
                                checkCLError(buildResult);
                            }
                            
                            // Create kernel
                            long kernel = clCreateKernel(program, "vector_add", errcode_ret);
                            checkCLError(errcode_ret.get(0));
                            
                            try {
                                // Create buffers
                                long bufferA = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, hostA, errcode_ret);
                                checkCLError(errcode_ret.get(0));
                                
                                long bufferB = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, hostB, errcode_ret);
                                checkCLError(errcode_ret.get(0));
                                
                                long bufferC = clCreateBuffer(context, CL_MEM_WRITE_ONLY, N * Float.BYTES, errcode_ret);
                                checkCLError(errcode_ret.get(0));
                                
                                try {
                                    // Set kernel arguments
                                    checkCLError(clSetKernelArg1p(kernel, 0, bufferA));
                                    checkCLError(clSetKernelArg1p(kernel, 1, bufferB));
                                    checkCLError(clSetKernelArg1p(kernel, 2, bufferC));
                                    checkCLError(clSetKernelArg1i(kernel, 3, N));
                                    
                                    // Execute kernel
                                    PointerBuffer globalWorkSize = stack.mallocPointer(1);
                                    globalWorkSize.put(0, N);
                                    
                                    checkCLError(clEnqueueNDRangeKernel(queue, kernel, 1, null, globalWorkSize, null, null, null));
                                    checkCLError(clFinish(queue));
                                    
                                    // Read results
                                    checkCLError(clEnqueueReadBuffer(queue, bufferC, true, 0, hostC, null, null));
                                    
                                    // Verify results
                                    for (int i = 0; i < N; i++) {
                                        float expected = hostA.get(i) + hostB.get(i);
                                        float actual = hostC.get(i);
                                        assertEquals(expected, actual, 0.001f, 
                                                   String.format("Mismatch at index %d: expected %f, got %f", i, expected, actual));
                                    }
                                    
                                    log.info("✅ GPU vector addition test PASSED - {} elements processed", N);
                                    
                                } finally {
                                    clReleaseMemObject(bufferA);
                                    clReleaseMemObject(bufferB);
                                    clReleaseMemObject(bufferC);
                                }
                            } finally {
                                clReleaseKernel(kernel);
                            }
                        } finally {
                            clReleaseProgram(program);
                        }
                    } finally {
                        clReleaseCommandQueue(queue);
                    }
                } finally {
                    clReleaseContext(context);
                }
            }
        });
    }
    
    // Helper methods for getting platform and device info
    
    private String getPlatformInfoString(MemoryStack stack, long platform, int paramName) {
        PointerBuffer pp = stack.mallocPointer(1);
        checkCLError(clGetPlatformInfo(platform, paramName, (ByteBuffer)null, pp));
        
        int bytes = (int)pp.get(0);
        ByteBuffer buffer = stack.malloc(bytes);
        checkCLError(clGetPlatformInfo(platform, paramName, buffer, null));
        
        return memUTF8(buffer, bytes - 1);
    }
    
    private String getDeviceInfoString(MemoryStack stack, long device, int paramName) {
        PointerBuffer pp = stack.mallocPointer(1);
        checkCLError(clGetDeviceInfo(device, paramName, (ByteBuffer)null, pp));
        
        int bytes = (int)pp.get(0);
        ByteBuffer buffer = stack.malloc(bytes);
        checkCLError(clGetDeviceInfo(device, paramName, buffer, null));
        
        return memUTF8(buffer, bytes - 1);
    }
    
    private int getDeviceInfoInt(MemoryStack stack, long device, int paramName) {
        IntBuffer ib = stack.mallocInt(1);
        checkCLError(clGetDeviceInfo(device, paramName, ib, null));
        return ib.get(0);
    }
    
    private long getDeviceInfoLong(MemoryStack stack, long device, int paramName) {
        LongBuffer lb = stack.mallocLong(1);
        checkCLError(clGetDeviceInfo(device, paramName, lb, null));
        return lb.get(0);
    }
}
