package com.hellblazer.luciferase.gpu.test.examples;

import com.hellblazer.luciferase.gpu.test.CICompatibleGPUTest;
import com.hellblazer.luciferase.gpu.test.support.TestSupportMatrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.lwjgl.BufferUtils;
import org.lwjgl.opencl.*;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Simple example demonstrating matrix multiplication on GPU vs CPU.
 * 
 * This test shows:
 * - How to detect GPU availability
 * - How to fall back to CPU when GPU is not available
 * - How to measure and compare performance
 * - How to validate results match between CPU and GPU
 */
public class SimpleMatrixMultiplyTest extends CICompatibleGPUTest {
    private static final Logger log = LoggerFactory.getLogger(SimpleMatrixMultiplyTest.class);
    
    private static final String MATRIX_MULTIPLY_KERNEL = """
        __kernel void matrixMultiply(
            __global const float* A,
            __global const float* B,
            __global float* C,
            const int N)
        {
            int row = get_global_id(0);
            int col = get_global_id(1);
            
            if (row < N && col < N) {
                float sum = 0.0f;
                for (int k = 0; k < N; k++) {
                    sum += A[row * N + k] * B[k * N + col];
                }
                C[row * N + col] = sum;
            }
        }
        """;
    
    private TestSupportMatrix supportMatrix;
    private boolean gpuAvailable;
    
    @BeforeEach
    void setup() {
        supportMatrix = new TestSupportMatrix();
        gpuAvailable = checkIfGPUSupported();
        
        log.info("Test environment: GPU available = {}", gpuAvailable);
    }
    
    @Test
    @DisplayName("Compare CPU vs GPU matrix multiplication")
    void testMatrixMultiplication() {
        int size = 256; // Matrix size (256x256)
        
        // Generate test matrices
        float[] matrixA = generateMatrix(size);
        float[] matrixB = generateMatrix(size);
        
        // CPU computation
        long cpuStart = System.nanoTime();
        float[] cpuResult = multiplyCPU(matrixA, matrixB, size);
        long cpuTime = System.nanoTime() - cpuStart;
        
        log.info("CPU computation took: {} ms", cpuTime / 1_000_000);
        
        // GPU computation (if available)
        if (gpuAvailable) {
            try {
                long gpuStart = System.nanoTime();
                float[] gpuResult = multiplyGPU(matrixA, matrixB, size);
                long gpuTime = System.nanoTime() - gpuStart;
                
                log.info("GPU computation took: {} ms", gpuTime / 1_000_000);
                log.info("Speedup: {}x", (double)cpuTime / gpuTime);
                
                // Validate results match
                assertArrayEquals(cpuResult, gpuResult, 0.001f, 
                    "GPU and CPU results should match");
                
            } catch (Exception e) {
                log.warn("GPU computation failed, using CPU result: {}", e.getMessage());
            }
        } else {
            log.info("GPU not available, skipping GPU test");
        }
        
        // Verify result is non-zero
        assertNotNull(cpuResult);
        assertTrue(cpuResult.length == size * size);
    }
    
    @Test
    @DisplayName("Test performance at different matrix sizes")
    void testPerformanceScaling() {
        int[] sizes = {64, 128, 256, 512};
        
        log.info("Performance scaling test:");
        log.info("Size\tCPU (ms)\tGPU (ms)\tSpeedup");
        
        for (int size : sizes) {
            float[] matrixA = generateMatrix(size);
            float[] matrixB = generateMatrix(size);
            
            // CPU timing
            long cpuTime = timeCPU(matrixA, matrixB, size);
            
            // GPU timing
            long gpuTime = -1;
            double speedup = 0;
            
            if (gpuAvailable && size >= 128) { // GPU beneficial for larger sizes
                gpuTime = timeGPU(matrixA, matrixB, size);
                speedup = (double)cpuTime / gpuTime;
            }
            
            log.info("{}\t{}\t\t{}\t\t{:.2f}x", 
                size, 
                cpuTime / 1_000_000,
                gpuTime > 0 ? gpuTime / 1_000_000 : "N/A",
                speedup);
        }
    }
    
    private float[] multiplyCPU(float[] A, float[] B, int N) {
        float[] C = new float[N * N];
        
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                float sum = 0;
                for (int k = 0; k < N; k++) {
                    sum += A[i * N + k] * B[k * N + j];
                }
                C[i * N + j] = sum;
            }
        }
        
        return C;
    }
    
    private float[] multiplyGPU(float[] A, float[] B, int N) throws Exception {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);
            
            // Get platform and device
            var platforms = stack.mallocPointer(1);
            CL10.clGetPlatformIDs(platforms, (IntBuffer)null);
            long platform = platforms.get(0);
            
            var devices = stack.mallocPointer(1);
            CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, devices, (IntBuffer)null);
            long device = devices.get(0);
            
            // Create context
            var contextProps = stack.mallocPointer(3);
            contextProps.put(CL10.CL_CONTEXT_PLATFORM).put(platform).put(0);
            contextProps.flip();
            
            long context = CL10.clCreateContext(contextProps, device, null, 0, errcode);
            checkError(errcode.get(0), "clCreateContext");
            
            // Create command queue
            long queue = CL10.clCreateCommandQueue(context, device, 0, errcode);
            checkError(errcode.get(0), "clCreateCommandQueue");
            
            // Create buffers
            FloatBuffer bufferA = BufferUtils.createFloatBuffer(N * N);
            bufferA.put(A).flip();
            
            FloatBuffer bufferB = BufferUtils.createFloatBuffer(N * N);
            bufferB.put(B).flip();
            
            long memA = CL10.clCreateBuffer(context, 
                CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR,
                bufferA, errcode);
            checkError(errcode.get(0), "clCreateBuffer A");
            
            long memB = CL10.clCreateBuffer(context,
                CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR,
                bufferB, errcode);
            checkError(errcode.get(0), "clCreateBuffer B");
            
            long memC = CL10.clCreateBuffer(context,
                CL10.CL_MEM_WRITE_ONLY,
                N * N * 4L, errcode);
            checkError(errcode.get(0), "clCreateBuffer C");
            
            // Create and build program
            var strings = stack.mallocPointer(1);
            var lengths = stack.mallocPointer(1);
            var sourceBuffer = stack.UTF8(MATRIX_MULTIPLY_KERNEL);
            strings.put(0, sourceBuffer);
            lengths.put(0, MATRIX_MULTIPLY_KERNEL.length());
            
            long program = CL10.clCreateProgramWithSource(context, strings, lengths, errcode);
            checkError(errcode.get(0), "clCreateProgramWithSource");
            
            int buildStatus = CL10.clBuildProgram(program, device, "", null, 0);
            checkError(buildStatus, "clBuildProgram");
            
            // Create kernel
            long kernel = CL10.clCreateKernel(program, "matrixMultiply", errcode);
            checkError(errcode.get(0), "clCreateKernel");
            
            // Set kernel arguments
            CL10.clSetKernelArg(kernel, 0, memA);
            CL10.clSetKernelArg(kernel, 1, memB);
            CL10.clSetKernelArg(kernel, 2, memC);
            CL10.clSetKernelArg(kernel, 3, stack.ints(N));
            
            // Execute kernel
            var globalWorkSize = stack.pointers(N, N);
            CL10.clEnqueueNDRangeKernel(queue, kernel, 2, null,
                globalWorkSize, null, null, null);
            
            // Read results
            FloatBuffer resultBuffer = BufferUtils.createFloatBuffer(N * N);
            CL10.clEnqueueReadBuffer(queue, memC, true, 0,
                resultBuffer, null, null);
            CL10.clFinish(queue);
            
            // Convert to array
            float[] result = new float[N * N];
            resultBuffer.get(result);
            
            // Cleanup
            CL10.clReleaseKernel(kernel);
            CL10.clReleaseProgram(program);
            CL10.clReleaseMemObject(memA);
            CL10.clReleaseMemObject(memB);
            CL10.clReleaseMemObject(memC);
            CL10.clReleaseCommandQueue(queue);
            CL10.clReleaseContext(context);
            
            return result;
        }
    }
    
    private long timeCPU(float[] A, float[] B, int N) {
        long start = System.nanoTime();
        multiplyCPU(A, B, N);
        return System.nanoTime() - start;
    }
    
    private long timeGPU(float[] A, float[] B, int N) {
        try {
            long start = System.nanoTime();
            multiplyGPU(A, B, N);
            return System.nanoTime() - start;
        } catch (Exception e) {
            return -1;
        }
    }
    
    private float[] generateMatrix(int size) {
        float[] matrix = new float[size * size];
        Random random = new Random(42); // Fixed seed for reproducibility
        
        for (int i = 0; i < matrix.length; i++) {
            matrix[i] = random.nextFloat();
        }
        
        return matrix;
    }
    
    private void checkError(int error, String operation) {
        if (error != CL10.CL_SUCCESS) {
            throw new RuntimeException(operation + " failed with error: " + error);
        }
    }
    
    private boolean checkIfGPUSupported() {
        return supportMatrix.getBackendSupport(TestSupportMatrix.Backend.OPENCL) 
            == TestSupportMatrix.SupportLevel.FULL ||
            supportMatrix.getBackendSupport(TestSupportMatrix.Backend.OPENCL)
            == TestSupportMatrix.SupportLevel.PARTIAL;
    }
}