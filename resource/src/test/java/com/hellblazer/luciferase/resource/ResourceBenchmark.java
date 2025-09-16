package com.hellblazer.luciferase.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance benchmarks for Resource Lifecycle Management
 */
public class ResourceBenchmark {
    
    private UnifiedResourceManager manager;
    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 10000;
    
    @BeforeEach
    void setUp() {
        var config = new ResourceConfiguration.Builder()
            .withMaxPoolSize(100L * 1024 * 1024) // 100MB
            .withMaxIdleTime(Duration.ofMinutes(5))
            .withLeakDetection(false) // Disable for performance tests
            .build();
        manager = new UnifiedResourceManager(config);
    }
    
    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }
    
    @Test
    void benchmarkPooledAllocation() {
        System.out.println("\n=== Pooled Allocation Benchmark ===");
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var buffer = manager.allocateMemory(4096);
            manager.releaseMemory(buffer);
        }
        
        // Benchmark allocation
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            var buffer = manager.allocateMemory(4096);
            manager.releaseMemory(buffer);
        }
        long endTime = System.nanoTime();
        
        long totalNanos = endTime - startTime;
        double avgNanos = (double) totalNanos / BENCHMARK_ITERATIONS;
        
        System.out.printf("Iterations: %d%n", BENCHMARK_ITERATIONS);
        System.out.printf("Total time: %.2f ms%n", totalNanos / 1_000_000.0);
        System.out.printf("Average per allocation: %.1f ns%n", avgNanos);
        System.out.printf("Throughput: %.0f allocations/sec%n", 1_000_000_000.0 / avgNanos);
    }
    
    @Test
    void benchmarkDirectAllocation() {
        System.out.println("\n=== Direct Allocation Benchmark (Baseline) ===");
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            ByteBuffer.allocateDirect(4096);
        }
        
        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            ByteBuffer.allocateDirect(4096);
        }
        long endTime = System.nanoTime();
        
        long totalNanos = endTime - startTime;
        double avgNanos = (double) totalNanos / BENCHMARK_ITERATIONS;
        
        System.out.printf("Iterations: %d%n", BENCHMARK_ITERATIONS);
        System.out.printf("Total time: %.2f ms%n", totalNanos / 1_000_000.0);
        System.out.printf("Average per allocation: %.1f ns%n", avgNanos);
        System.out.printf("Throughput: %.0f allocations/sec%n", 1_000_000_000.0 / avgNanos);
    }
    
    @Test
    void benchmarkConcurrentAllocation() throws InterruptedException {
        System.out.println("\n=== Concurrent Allocation Benchmark ===");
        
        int threadCount = 8;
        int allocationsPerThread = BENCHMARK_ITERATIONS / threadCount;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicLong totalNanos = new AtomicLong(0);
        
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    long threadStart = System.nanoTime();
                    for (int i = 0; i < allocationsPerThread; i++) {
                        var buffer = manager.allocateMemory(4096);
                        manager.releaseMemory(buffer);
                    }
                    long threadEnd = System.nanoTime();
                    
                    totalNanos.addAndGet(threadEnd - threadStart);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        long startTime = System.nanoTime();
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        long wallClockNanos = endTime - startTime;
        double avgThreadNanos = (double) totalNanos.get() / (threadCount * allocationsPerThread);
        double throughput = (double) (threadCount * allocationsPerThread) / (wallClockNanos / 1_000_000_000.0);
        
        System.out.printf("Threads: %d%n", threadCount);
        System.out.printf("Total allocations: %d%n", threadCount * allocationsPerThread);
        System.out.printf("Wall clock time: %.2f ms%n", wallClockNanos / 1_000_000.0);
        System.out.printf("Average per allocation: %.1f ns%n", avgThreadNanos);
        System.out.printf("Aggregate throughput: %.0f allocations/sec%n", throughput);
        
        executor.shutdown();
    }
    
    @Test
    void benchmarkVariableSizeAllocation() {
        System.out.println("\n=== Variable Size Allocation Benchmark ===");
        
        int[] sizes = {512, 1024, 2048, 4096, 8192, 16384};
        
        for (int size : sizes) {
            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
                var buffer = manager.allocateMemory(size);
                manager.releaseMemory(buffer);
            }
            
            // Benchmark
            long startTime = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS / 10; i++) {
                var buffer = manager.allocateMemory(size);
                manager.releaseMemory(buffer);
            }
            long endTime = System.nanoTime();
            
            long totalNanos = endTime - startTime;
            double avgNanos = (double) totalNanos / (BENCHMARK_ITERATIONS / 10);
            
            System.out.printf("Size: %d bytes - Avg: %.1f ns, Throughput: %.0f ops/sec%n",
                size, avgNanos, 1_000_000_000.0 / avgNanos);
        }
    }
    
    @Test
    void benchmarkPoolHitRate() {
        System.out.println("\n=== Pool Hit Rate Benchmark ===");
        
        // Allocate and release to populate pool
        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            buffers.add(manager.allocateMemory(4096));
        }
        for (var buffer : buffers) {
            manager.releaseMemory(buffer);
        }
        
        // Clear statistics
        var pool = manager.getMemoryPool();
        
        // Measure hit rate
        int hits = 0;
        int total = 1000;
        
        for (int i = 0; i < total; i++) {
            var buffer = manager.allocateMemory(4096);
            manager.releaseMemory(buffer);
        }
        
        var stats = pool.getPoolStatistics();
        double hitRate = stats.getHitRate();
        
        System.out.printf("Pool hit rate: %.1f%%%n", hitRate * 100);
        System.out.printf("Total allocated: %d%n", stats.getTotalAllocated());
        System.out.printf("Cache hits: %d%n", stats.getHitCount());
        System.out.printf("Cache misses: %d%n", stats.getMissCount());
    }
    
    @Test
    void benchmarkMemoryOverhead() {
        System.out.println("\n=== Memory Overhead Benchmark ===");
        
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        sleep(100);
        
        long baselineMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Allocate many small resources
        List<ByteBuffer> buffers = new ArrayList<>();
        int count = 10000;
        int size = 1024;
        
        for (int i = 0; i < count; i++) {
            buffers.add(manager.allocateMemory(size));
        }
        
        System.gc();
        sleep(100);
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        
        long actualDataSize = (long) count * size;
        long totalMemory = usedMemory - baselineMemory;
        double overhead = ((double) (totalMemory - actualDataSize) / actualDataSize) * 100;
        
        System.out.printf("Resources allocated: %d%n", count);
        System.out.printf("Size per resource: %d bytes%n", size);
        System.out.printf("Expected memory: %.2f MB%n", actualDataSize / (1024.0 * 1024.0));
        System.out.printf("Actual memory: %.2f MB%n", totalMemory / (1024.0 * 1024.0));
        System.out.printf("Overhead: %.1f%%%n", overhead);
        
        // Cleanup
        for (var buffer : buffers) {
            manager.releaseMemory(buffer);
        }
    }
    
    @Test
    void benchmarkMaintenancePerformance() {
        System.out.println("\n=== Maintenance Performance Benchmark ===");
        
        // Create many resources
        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            buffers.add(manager.allocateMemory(4096));
        }
        
        // Release half
        for (int i = 0; i < 500; i++) {
            manager.releaseMemory(buffers.get(i));
        }
        
        // Benchmark maintenance
        long startTime = System.nanoTime();
        manager.performMaintenance();
        long endTime = System.nanoTime();
        
        long maintenanceNanos = endTime - startTime;
        
        System.out.printf("Resources before maintenance: %d%n", 500);
        System.out.printf("Maintenance time: %.2f ms%n", maintenanceNanos / 1_000_000.0);
        System.out.printf("Resources after maintenance: %d%n", manager.getActiveResourceCount());
        
        // Cleanup
        for (int i = 500; i < buffers.size(); i++) {
            manager.releaseMemory(buffers.get(i));
        }
    }
    
    @Test 
    void benchmarkStatisticsCollection() {
        System.out.println("\n=== Statistics Collection Benchmark ===");
        
        // Create resources
        for (int i = 0; i < 1000; i++) {
            manager.allocateMemory(4096);
        }
        
        // Warmup
        for (int i = 0; i < 100; i++) {
            manager.getStatistics();
        }
        
        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            var stats = manager.getStatistics();
        }
        long endTime = System.nanoTime();
        
        long totalNanos = endTime - startTime;
        double avgNanos = (double) totalNanos / 1000;
        
        System.out.printf("Statistics calls: 1000%n");
        System.out.printf("Total time: %.2f ms%n", totalNanos / 1_000_000.0);
        System.out.printf("Average per call: %.1f µs%n", avgNanos / 1000.0);
    }
    
    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}