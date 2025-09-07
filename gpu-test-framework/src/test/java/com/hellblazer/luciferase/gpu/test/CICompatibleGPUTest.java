package com.hellblazer.luciferase.gpu.test;

import org.lwjgl.opencl.*;
import org.lwjgl.system.MemoryStack;
import org.junit.jupiter.api.BeforeAll;
import java.nio.IntBuffer;
import static org.lwjgl.opencl.CL10.*;

/**
 * Base class for GPU tests that work in CI environments without GPU hardware.
 * Provides automatic detection and graceful handling of GPU availability.
 */
public abstract class CICompatibleGPUTest {
    
    protected static boolean gpuAvailable = false;
    
    /**
     * GPU context wrapper for OpenCL operations
     */
    public static class GPUContext implements AutoCloseable {
        public final long platform;
        public final long device;
        public final long context;
        public final long queue;
        
        public GPUContext(long platform, long device, long context, long queue) {
            this.platform = platform;
            this.device = device;
            this.context = context;
            this.queue = queue;
        }
        
        @Override
        public void close() {
            if (queue != 0) clReleaseCommandQueue(queue);
            if (context != 0) clReleaseContext(context);
        }
    }
    
    @BeforeAll
    public static void checkGPUAvailability() {
        try {
            // Initialize OpenCL
            CL.create();
            
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer numPlatforms = stack.mallocInt(1);
                int result = clGetPlatformIDs(null, numPlatforms);
                
                if (result == CL_SUCCESS && numPlatforms.get(0) > 0) {
                    gpuAvailable = true;
                    System.out.println("GPU detected - running hardware tests");
                } else {
                    gpuAvailable = false;
                    System.out.println("No GPU detected - using CPU fallback");
                }
            }
        } catch (UnsatisfiedLinkError | Exception e) {
            gpuAvailable = false;
            System.out.println("OpenCL not available - using CPU fallback: " + e.getMessage());
        }
    }
    
    /**
     * Check if GPU is available for testing
     */
    protected static boolean isGPUAvailable() {
        return gpuAvailable;
    }
    
    /**
     * Execute code with GPU context if available, otherwise skip
     */
    protected void withGPUContext(GPUContextConsumer consumer) throws Exception {
        if (!gpuAvailable) {
            System.out.println("Skipping GPU test - no hardware available");
            return;
        }
        
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Get platform
            IntBuffer numPlatforms = stack.mallocInt(1);
            clGetPlatformIDs(null, numPlatforms);
            
            var platforms = stack.mallocPointer(numPlatforms.get(0));
            clGetPlatformIDs(platforms, (IntBuffer)null);
            long platform = platforms.get(0);
            
            // Get device
            IntBuffer numDevices = stack.mallocInt(1);
            clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, null, numDevices);
            
            if (numDevices.get(0) == 0) {
                // Try CPU fallback
                clGetDeviceIDs(platform, CL_DEVICE_TYPE_CPU, null, numDevices);
            }
            
            var devices = stack.mallocPointer(numDevices.get(0));
            clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices, (IntBuffer)null);
            long device = devices.get(0);
            
            // Create context
            IntBuffer errcode = stack.mallocInt(1);
            long context = clCreateContext(null, device, null, 0, errcode);
            checkCLError(errcode.get(0));
            
            // Create command queue
            long queue = clCreateCommandQueue(context, device, 0, errcode);
            checkCLError(errcode.get(0));
            
            // Execute test with context
            try (GPUContext gpuContext = new GPUContext(platform, device, context, queue)) {
                consumer.accept(gpuContext);
            }
        }
    }
    
    @FunctionalInterface
    protected interface GPUContextConsumer {
        void accept(GPUContext context) throws Exception;
    }
    
    protected void checkCLError(int errcode) {
        if (errcode != CL_SUCCESS) {
            throw new RuntimeException("OpenCL error: " + errcode);
        }
    }
}