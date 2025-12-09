package com.hellblazer.luciferase.gpu.test;

import com.hellblazer.luciferase.gpu.test.GPUDeviceDetector.GPUDevice;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.*;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * GPU Benchmark Runner
 * 
 * Runs simple GPU benchmarks to measure performance characteristics.
 * Used for validating GPU functionality and measuring baseline performance.
 */
public final class GPUBenchmarkRunner {
    private static final Logger log = LoggerFactory.getLogger(GPUBenchmarkRunner.class);
    
    /**
     * Benchmark result containing timing and throughput information
     */
    public static class BenchmarkResult {
        private final String name;
        private final long durationNs;
        private final long operations;
        private final boolean success;
        private final String errorMessage;
        
        BenchmarkResult(String name, long durationNs, long operations, boolean success, String errorMessage) {
            this.name = name;
            this.durationNs = durationNs;
            this.operations = operations;
            this.success = success;
            this.errorMessage = errorMessage;
        }
        
        public String getName() { return name; }
        public long getDurationNs() { return durationNs; }
        public double getDurationMs() { return durationNs / 1_000_000.0; }
        public long getOperations() { return operations; }
        public double getThroughput() { return operations / (durationNs / 1_000_000_000.0); }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        
        @Override
        public String toString() {
            if (!success) {
                return String.format("%s: FAILED - %s", name, errorMessage);
            }
            return String.format("%s: %.2f ms, %.2f Mops/s", 
                name, getDurationMs(), getThroughput() / 1_000_000.0);
        }
    }
    
    private GPUBenchmarkRunner() {
        // Utility class
    }
    
    /**
     * Run a simple vector addition benchmark on the first available GPU
     * 
     * @param size Number of elements to process
     * @return Benchmark result
     */
    public static BenchmarkResult runVectorAddBenchmark(int size) {
        GPUDevice gpu = GPUDeviceDetector.getFirstGPU();
        
        if (gpu == null) {
            return new BenchmarkResult("VectorAdd", 0, 0, false, "No GPU available");
        }
        
        return runVectorAddBenchmark(gpu, size);
    }
    
    /**
     * Run a simple vector addition benchmark on a specific GPU
     * 
     * @param gpu GPU device to use
     * @param size Number of elements to process
     * @return Benchmark result
     */
    public static BenchmarkResult runVectorAddBenchmark(GPUDevice gpu, int size) {
        long contextPtr = NULL;
        long queuePtr = NULL;
        long programPtr = NULL;
        long kernelPtr = NULL;
        long bufferA = NULL;
        long bufferB = NULL;
        long bufferC = NULL;
        
        try {
            // Create OpenCL context
            CL.create();
            
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer errcode = stack.callocInt(1);
                
                // Create context
                PointerBuffer ctxProps = stack.mallocPointer(3);
                ctxProps.put(CL_CONTEXT_PLATFORM).put(gpu.getPlatformId()).put(0).flip();
                
                contextPtr = clCreateContext(ctxProps, gpu.getDeviceId(), null, NULL, errcode);
                if (errcode.get(0) != CL_SUCCESS) {
                    return new BenchmarkResult("VectorAdd", 0, 0, false, 
                        "Failed to create context: " + errcode.get(0));
                }
                
                // Create command queue
                queuePtr = clCreateCommandQueue(contextPtr, gpu.getDeviceId(), 0, errcode);
                if (errcode.get(0) != CL_SUCCESS) {
                    return new BenchmarkResult("VectorAdd", 0, 0, false,
                        "Failed to create command queue: " + errcode.get(0));
                }
                
                // Simple vector addition kernel
                String kernelSource = """
                    __kernel void vector_add(__global const float *a,
                                            __global const float *b,
                                            __global float *c) {
                        int gid = get_global_id(0);
                        c[gid] = a[gid] + b[gid];
                    }
                    """;
                
                // Create program
                PointerBuffer strings = BufferUtils.createPointerBuffer(1);
                PointerBuffer lengths = BufferUtils.createPointerBuffer(1);
                strings.put(0, memAddress(stack.UTF8(kernelSource, false)));
                lengths.put(0, kernelSource.length());
                
                programPtr = clCreateProgramWithSource(contextPtr, strings, lengths, errcode);
                if (errcode.get(0) != CL_SUCCESS) {
                    return new BenchmarkResult("VectorAdd", 0, 0, false,
                        "Failed to create program: " + errcode.get(0));
                }
                
                // Build program
                int buildStatus = clBuildProgram(programPtr, gpu.getDeviceId(), "", null, NULL);
                if (buildStatus != CL_SUCCESS) {
                    return new BenchmarkResult("VectorAdd", 0, 0, false,
                        "Failed to build program: " + buildStatus);
                }
                
                // Create kernel
                kernelPtr = clCreateKernel(programPtr, "vector_add", errcode);
                if (errcode.get(0) != CL_SUCCESS) {
                    return new BenchmarkResult("VectorAdd", 0, 0, false,
                        "Failed to create kernel: " + errcode.get(0));
                }
                
                // Allocate host buffers
                FloatBuffer hostA = BufferUtils.createFloatBuffer(size);
                FloatBuffer hostB = BufferUtils.createFloatBuffer(size);
                
                for (int i = 0; i < size; i++) {
                    hostA.put(i, (float) i);
                    hostB.put(i, (float) (i * 2));
                }
                
                // Create device buffers
                bufferA = clCreateBuffer(contextPtr, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                    hostA, errcode);
                bufferB = clCreateBuffer(contextPtr, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                    hostB, errcode);
                bufferC = clCreateBuffer(contextPtr, CL_MEM_WRITE_ONLY,
                    size * Float.BYTES, errcode);
                
                // Set kernel arguments
                clSetKernelArg1p(kernelPtr, 0, bufferA);
                clSetKernelArg1p(kernelPtr, 1, bufferB);
                clSetKernelArg1p(kernelPtr, 2, bufferC);
                
                // Execute kernel (with timing)
                PointerBuffer globalWorkSize = stack.mallocPointer(1);
                globalWorkSize.put(0, size);
                
                long startTime = System.nanoTime();
                
                int execStatus = clEnqueueNDRangeKernel(queuePtr, kernelPtr, 1, null,
                    globalWorkSize, null, null, null);
                
                if (execStatus != CL_SUCCESS) {
                    return new BenchmarkResult("VectorAdd", 0, 0, false,
                        "Failed to execute kernel: " + execStatus);
                }
                
                // Wait for completion
                clFinish(queuePtr);
                
                long endTime = System.nanoTime();
                long duration = endTime - startTime;
                
                // Verify result (optional - read back one element)
                FloatBuffer hostC = BufferUtils.createFloatBuffer(1);
                clEnqueueReadBuffer(queuePtr, bufferC, true, 0, hostC, null, null);
                
                // Check if result is correct (first element)
                float expected = hostA.get(0) + hostB.get(0);
                float actual = hostC.get(0);
                
                if (Math.abs(expected - actual) > 0.001f) {
                    return new BenchmarkResult("VectorAdd", duration, size, false,
                        String.format("Result verification failed: expected %.2f, got %.2f", expected, actual));
                }
                
                return new BenchmarkResult("VectorAdd", duration, size, true, null);
                
            } finally {
                // Cleanup
                if (bufferC != NULL) clReleaseMemObject(bufferC);
                if (bufferB != NULL) clReleaseMemObject(bufferB);
                if (bufferA != NULL) clReleaseMemObject(bufferA);
                if (kernelPtr != NULL) clReleaseKernel(kernelPtr);
                if (programPtr != NULL) clReleaseProgram(programPtr);
                if (queuePtr != NULL) clReleaseCommandQueue(queuePtr);
                if (contextPtr != NULL) clReleaseContext(contextPtr);
            }
            
        } catch (Exception e) {
            log.error("Benchmark failed with exception", e);
            return new BenchmarkResult("VectorAdd", 0, 0, false, 
                "Exception: " + e.getMessage());
        } finally {
            try {
                CL.destroy();
            } catch (Exception e) {
                log.debug("Error destroying OpenCL context", e);
            }
        }
    }
    
    /**
     * Run a quick sanity check benchmark (small size)
     * 
     * @return Benchmark result
     */
    public static BenchmarkResult runQuickBenchmark() {
        return runVectorAddBenchmark(1024); // 1K elements
    }
    
    /**
     * Run a standard benchmark (medium size)
     * 
     * @return Benchmark result
     */
    public static BenchmarkResult runStandardBenchmark() {
        return runVectorAddBenchmark(1024 * 1024); // 1M elements
    }
    
    /**
     * Run a large benchmark (large size)
     * 
     * @return Benchmark result
     */
    public static BenchmarkResult runLargeBenchmark() {
        return runVectorAddBenchmark(10 * 1024 * 1024); // 10M elements
    }
}
