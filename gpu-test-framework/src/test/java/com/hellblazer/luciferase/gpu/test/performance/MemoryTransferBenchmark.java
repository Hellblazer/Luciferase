package com.hellblazer.luciferase.gpu.test.performance;

import com.hellblazer.luciferase.gpu.test.support.TestSupportMatrix;
import org.junit.jupiter.api.*;
import org.lwjgl.opencl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Benchmarks memory transfer overhead between CPU and GPU.
 * 
 * Critical for understanding when GPU acceleration becomes beneficial
 * versus the cost of data transfer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MemoryTransferBenchmark {
    private static final Logger log = LoggerFactory.getLogger(MemoryTransferBenchmark.class);
    
    // Test various data sizes (in MB)
    private static final int[] DATA_SIZES_MB = {1, 4, 16, 64, 256, 1024};
    private static final int WARMUP_ITERATIONS = 3;
    private static final int BENCHMARK_ITERATIONS = 10;
    
    private TestSupportMatrix supportMatrix;
    private long clContext;
    private long clQueue;
    private boolean gpuAvailable;
    
    @BeforeAll
    void setup() {
        supportMatrix = new TestSupportMatrix();
        gpuAvailable = initializeOpenCL();
        
        if (!gpuAvailable) {
            log.warn("OpenCL not available, skipping memory transfer benchmarks");
        }
    }
    
    @AfterAll
    void cleanup() {
        if (gpuAvailable) {
            CL10.clReleaseCommandQueue(clQueue);
            CL10.clReleaseContext(clContext);
        }
    }
    
    private boolean initializeOpenCL() {
        try {
            if (supportMatrix.getBackendSupport(TestSupportMatrix.Backend.OPENCL) 
                == TestSupportMatrix.SupportLevel.NOT_AVAILABLE) {
                return false;
            }
            
            try (var stack = MemoryStack.stackPush()) {
                IntBuffer errcode = stack.mallocInt(1);
                
                // Get platform and device
                var platforms = stack.mallocPointer(1);
                CL10.clGetPlatformIDs(platforms, (IntBuffer)null);
                long platform = platforms.get(0);
                
                var devices = stack.mallocPointer(1);
                CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, devices, (IntBuffer)null);
                long device = devices.get(0);
                
                // Create context and queue
                var contextProps = stack.mallocPointer(3);
                contextProps.put(CL10.CL_CONTEXT_PLATFORM).put(platform).put(0);
                contextProps.flip();
                
                clContext = CL10.clCreateContext(
                    contextProps, device, null, 0, errcode
                );
                checkError(errcode.get(0), "clCreateContext");
                
                clQueue = CL10.clCreateCommandQueue(
                    clContext, device, CL10.CL_QUEUE_PROFILING_ENABLE, errcode
                );
                checkError(errcode.get(0), "clCreateCommandQueue");
                
                return true;
            }
        } catch (Exception e) {
            log.warn("OpenCL initialization failed: {}", e.getMessage());
            return false;
        }
    }
    
    private void checkError(int error, String operation) {
        if (error != CL10.CL_SUCCESS) {
            throw new RuntimeException(operation + " failed with error: " + error);
        }
    }
    
    @Test
    @DisplayName("Benchmark CPU to GPU memory transfer")
    void benchmarkHostToDevice() {
        Assumptions.assumeTrue(gpuAvailable, "GPU not available");
        
        log.info("Starting Host-to-Device memory transfer benchmarks");
        
        List<TransferResult> results = new ArrayList<>();
        
        for (int sizeMB : DATA_SIZES_MB) {
            long sizeBytes = sizeMB * 1024L * 1024L;
            
            // Allocate host memory
            ByteBuffer hostBuffer = MemoryUtil.memAlloc((int)sizeBytes);
            
            // Fill with test data
            for (int i = 0; i < hostBuffer.capacity(); i++) {
                hostBuffer.put(i, (byte)(i & 0xFF));
            }
            
            try (var stack = MemoryStack.stackPush()) {
                IntBuffer errcode = stack.mallocInt(1);
                
                // Create device buffer
                long deviceBuffer = CL10.clCreateBuffer(
                    clContext,
                    CL10.CL_MEM_READ_WRITE,
                    sizeBytes,
                    errcode
                );
                checkError(errcode.get(0), "clCreateBuffer");
                
                // Warmup
                for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                    CL10.clEnqueueWriteBuffer(
                        clQueue, deviceBuffer, true,
                        0, hostBuffer, null, null
                    );
                    CL10.clFinish(clQueue);
                }
                
                // Benchmark
                long totalTime = 0;
                for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    
                    CL10.clEnqueueWriteBuffer(
                        clQueue, deviceBuffer, true,
                        0, hostBuffer, null, null
                    );
                    CL10.clFinish(clQueue);
                    
                    long end = System.nanoTime();
                    totalTime += (end - start);
                }
                
                long avgTime = totalTime / BENCHMARK_ITERATIONS;
                double bandwidth = (sizeBytes / (1024.0 * 1024.0 * 1024.0)) / 
                                  (avgTime / 1_000_000_000.0); // GB/s
                
                results.add(new TransferResult(
                    sizeMB, avgTime, bandwidth, TransferType.HOST_TO_DEVICE
                ));
                
                log.info("Size: {}MB, Time: {}ms, Bandwidth: {:.2f} GB/s",
                    sizeMB,
                    TimeUnit.NANOSECONDS.toMillis(avgTime),
                    bandwidth
                );
                
                // Cleanup
                CL10.clReleaseMemObject(deviceBuffer);
            }
            
            MemoryUtil.memFree(hostBuffer);
        }
        
        printTransferSummary(results, TransferType.HOST_TO_DEVICE);
    }
    
    @Test
    @DisplayName("Benchmark GPU to CPU memory transfer")
    void benchmarkDeviceToHost() {
        Assumptions.assumeTrue(gpuAvailable, "GPU not available");
        
        log.info("Starting Device-to-Host memory transfer benchmarks");
        
        List<TransferResult> results = new ArrayList<>();
        
        for (int sizeMB : DATA_SIZES_MB) {
            long sizeBytes = sizeMB * 1024L * 1024L;
            
            // Allocate host memory
            ByteBuffer hostBuffer = MemoryUtil.memAlloc((int)sizeBytes);
            
            try (var stack = MemoryStack.stackPush()) {
                IntBuffer errcode = stack.mallocInt(1);
                
                // Create and initialize device buffer
                long deviceBuffer = CL10.clCreateBuffer(
                    clContext,
                    CL10.CL_MEM_READ_WRITE | CL10.CL_MEM_COPY_HOST_PTR,
                    hostBuffer,
                    errcode
                );
                checkError(errcode.get(0), "clCreateBuffer");
                
                // Warmup
                for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                    CL10.clEnqueueReadBuffer(
                        clQueue, deviceBuffer, true,
                        0, hostBuffer, null, null
                    );
                    CL10.clFinish(clQueue);
                }
                
                // Benchmark
                long totalTime = 0;
                for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    
                    CL10.clEnqueueReadBuffer(
                        clQueue, deviceBuffer, true,
                        0, hostBuffer, null, null
                    );
                    CL10.clFinish(clQueue);
                    
                    long end = System.nanoTime();
                    totalTime += (end - start);
                }
                
                long avgTime = totalTime / BENCHMARK_ITERATIONS;
                double bandwidth = (sizeBytes / (1024.0 * 1024.0 * 1024.0)) / 
                                  (avgTime / 1_000_000_000.0); // GB/s
                
                results.add(new TransferResult(
                    sizeMB, avgTime, bandwidth, TransferType.DEVICE_TO_HOST
                ));
                
                log.info("Size: {}MB, Time: {}ms, Bandwidth: {:.2f} GB/s",
                    sizeMB,
                    TimeUnit.NANOSECONDS.toMillis(avgTime),
                    bandwidth
                );
                
                // Cleanup
                CL10.clReleaseMemObject(deviceBuffer);
            }
            
            MemoryUtil.memFree(hostBuffer);
        }
        
        printTransferSummary(results, TransferType.DEVICE_TO_HOST);
    }
    
    @Test
    @DisplayName("Benchmark pinned memory transfers")
    void benchmarkPinnedMemory() {
        Assumptions.assumeTrue(gpuAvailable, "GPU not available");
        
        log.info("Starting pinned memory transfer benchmarks");
        
        List<TransferResult> results = new ArrayList<>();
        
        for (int sizeMB : DATA_SIZES_MB) {
            long sizeBytes = sizeMB * 1024L * 1024L;
            
            try (var stack = MemoryStack.stackPush()) {
                IntBuffer errcode = stack.mallocInt(1);
                
                // Create pinned memory buffer
                long pinnedBuffer = CL10.clCreateBuffer(
                    clContext,
                    CL10.CL_MEM_READ_WRITE | CL10.CL_MEM_ALLOC_HOST_PTR,
                    sizeBytes,
                    errcode
                );
                checkError(errcode.get(0), "clCreateBuffer pinned");
                
                // Map the buffer to host memory
                ByteBuffer hostPtr = CL10.clEnqueueMapBuffer(
                    clQueue, pinnedBuffer, true,
                    CL10.CL_MAP_READ | CL10.CL_MAP_WRITE,
                    0, sizeBytes,
                    null, null, errcode, null
                );
                checkError(errcode.get(0), "clEnqueueMapBuffer");
                
                // Fill with test data
                for (int i = 0; i < Math.min(1024, hostPtr.capacity()); i++) {
                    hostPtr.put(i, (byte)(i & 0xFF));
                }
                
                // Warmup
                for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                    // Unmap (sends to device)
                    CL10.clEnqueueUnmapMemObject(clQueue, pinnedBuffer, hostPtr, null, null);
                    CL10.clFinish(clQueue);
                    
                    // Re-map (gets from device)
                    hostPtr = CL10.clEnqueueMapBuffer(
                        clQueue, pinnedBuffer, true,
                        CL10.CL_MAP_READ | CL10.CL_MAP_WRITE,
                        0, sizeBytes,
                        null, null, errcode, null
                    );
                }
                
                // Benchmark round-trip
                long totalTime = 0;
                for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    
                    // Unmap (sends to device)
                    CL10.clEnqueueUnmapMemObject(clQueue, pinnedBuffer, hostPtr, null, null);
                    CL10.clFinish(clQueue);
                    
                    // Re-map (gets from device)
                    hostPtr = CL10.clEnqueueMapBuffer(
                        clQueue, pinnedBuffer, true,
                        CL10.CL_MAP_READ | CL10.CL_MAP_WRITE,
                        0, sizeBytes,
                        null, null, errcode, null
                    );
                    CL10.clFinish(clQueue);
                    
                    long end = System.nanoTime();
                    totalTime += (end - start);
                }
                
                long avgTime = totalTime / BENCHMARK_ITERATIONS;
                double bandwidth = (sizeBytes * 2 / (1024.0 * 1024.0 * 1024.0)) / 
                                  (avgTime / 1_000_000_000.0); // GB/s (round-trip)
                
                results.add(new TransferResult(
                    sizeMB, avgTime, bandwidth, TransferType.PINNED_MEMORY
                ));
                
                log.info("Size: {}MB, Round-trip Time: {}ms, Bandwidth: {:.2f} GB/s",
                    sizeMB,
                    TimeUnit.NANOSECONDS.toMillis(avgTime),
                    bandwidth
                );
                
                // Final cleanup
                CL10.clEnqueueUnmapMemObject(clQueue, pinnedBuffer, hostPtr, null, null);
                CL10.clReleaseMemObject(pinnedBuffer);
            }
        }
        
        printTransferSummary(results, TransferType.PINNED_MEMORY);
    }
    
    @Test
    @DisplayName("Analyze memory transfer overhead for workloads")
    void analyzeTransferOverhead() {
        Assumptions.assumeTrue(gpuAvailable, "GPU not available");
        
        log.info("\n========== TRANSFER OVERHEAD ANALYSIS ==========");
        
        // Simulate different workload scenarios
        int[] workloadSizes = {100, 1_000, 10_000, 100_000};
        float[] computeIntensities = {0.1f, 1.0f, 10.0f, 100.0f}; // ops per byte
        
        for (int size : workloadSizes) {
            long dataSize = size * 4L * 4; // float4 per item
            
            // Measure transfer time
            long transferTime = measureTransferTime(dataSize);
            
            for (float intensity : computeIntensities) {
                // Estimate compute time (simplified)
                long computeTime = (long)(size * intensity * 1000); // nanoseconds
                
                double overhead = (double)transferTime / (transferTime + computeTime) * 100;
                
                log.info("Workload: {} items, {:.1f} ops/byte -> Transfer overhead: {:.1f}%",
                    size, intensity, overhead
                );
                
                if (overhead < 10) {
                    log.info("  -> GPU recommended (low transfer overhead)");
                } else if (overhead < 50) {
                    log.info("  -> GPU may be beneficial (moderate overhead)");
                } else {
                    log.info("  -> CPU likely better (high transfer overhead)");
                }
            }
        }
        
        log.info("================================================\n");
    }
    
    private long measureTransferTime(long sizeBytes) {
        ByteBuffer hostBuffer = MemoryUtil.memAlloc((int)sizeBytes);
        
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);
            
            long deviceBuffer = CL10.clCreateBuffer(
                clContext,
                CL10.CL_MEM_READ_WRITE,
                sizeBytes,
                errcode
            );
            
            // Measure round-trip transfer
            long start = System.nanoTime();
            
            CL10.clEnqueueWriteBuffer(
                clQueue, deviceBuffer, false,
                0, hostBuffer, null, null
            );
            CL10.clEnqueueReadBuffer(
                clQueue, deviceBuffer, true,
                0, hostBuffer, null, null
            );
            CL10.clFinish(clQueue);
            
            long end = System.nanoTime();
            
            CL10.clReleaseMemObject(deviceBuffer);
            MemoryUtil.memFree(hostBuffer);
            
            return end - start;
            
        } catch (Exception e) {
            MemoryUtil.memFree(hostBuffer);
            return Long.MAX_VALUE;
        }
    }
    
    private void printTransferSummary(List<TransferResult> results, TransferType type) {
        log.info("\n========== {} SUMMARY ==========", type);
        
        double avgBandwidth = results.stream()
            .mapToDouble(r -> r.bandwidth)
            .average()
            .orElse(0);
        
        log.info("Average bandwidth: {:.2f} GB/s", avgBandwidth);
        
        // Find optimal transfer size
        TransferResult optimal = results.stream()
            .max((a, b) -> Double.compare(a.bandwidth, b.bandwidth))
            .orElse(null);
        
        if (optimal != null) {
            log.info("Optimal transfer size: {}MB ({:.2f} GB/s)", 
                optimal.sizeMB, optimal.bandwidth);
        }
        
        log.info("=========================================\n");
    }
    
    private enum TransferType {
        HOST_TO_DEVICE("Host to Device"),
        DEVICE_TO_HOST("Device to Host"),
        PINNED_MEMORY("Pinned Memory Round-trip");
        
        final String description;
        
        TransferType(String description) {
            this.description = description;
        }
        
        @Override
        public String toString() {
            return description;
        }
    }
    
    private static class TransferResult {
        final int sizeMB;
        final long timeNanos;
        final double bandwidth; // GB/s
        final TransferType type;
        
        TransferResult(int sizeMB, long timeNanos, double bandwidth, TransferType type) {
            this.sizeMB = sizeMB;
            this.timeNanos = timeNanos;
            this.bandwidth = bandwidth;
            this.type = type;
        }
    }
}