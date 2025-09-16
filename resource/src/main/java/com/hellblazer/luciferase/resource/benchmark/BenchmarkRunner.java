package com.hellblazer.luciferase.resource.benchmark;

import com.hellblazer.luciferase.resource.ResourceConfiguration;
import com.hellblazer.luciferase.resource.UnifiedResourceManager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes resource management benchmarks with various workload patterns.
 */
public class BenchmarkRunner {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);
    
    private final ExecutorService executor;
    private final int threadCount;
    
    public BenchmarkRunner() {
        this(Runtime.getRuntime().availableProcessors());
    }
    
    public BenchmarkRunner(int threadCount) {
        this.threadCount = threadCount;
        this.executor = Executors.newFixedThreadPool(threadCount);
    }
    
    /**
     * Runs a sequential allocation benchmark.
     */
    public ResourceBenchmark.BenchmarkResults runSequentialAllocation(
            String name, 
            ResourceConfiguration config,
            int numAllocations,
            int minSize,
            int maxSize) throws Exception {
        
        var benchmark = new ResourceBenchmark(name);
        var manager = new UnifiedResourceManager(config);
        var random = new Random(42);
        var resources = new ArrayList<AutoCloseable>();
        
        try {
            // Allocation phase
            for (int i = 0; i < numAllocations; i++) {
                var size = minSize + random.nextInt(maxSize - minSize);
                var start = System.nanoTime();
                
                try {
                    var buffer = allocateBuffer(i % 2 == 0 ? "GL" : "CL", size);
                    var latency = System.nanoTime() - start;
                    benchmark.recordAllocation(i % 2 == 0 ? "GL_BUFFER" : "CL_BUFFER", size, latency);
                    resources.add(buffer);
                } catch (Exception e) {
                    benchmark.recordAllocationFailure(i % 2 == 0 ? "GL_BUFFER" : "CL_BUFFER", size);
                }
            }
            
            // Deallocation phase
            for (var resource : resources) {
                var start = System.nanoTime();
                resource.close();
                var latency = System.nanoTime() - start;
                benchmark.recordDeallocation("BUFFER", 0, latency);
            }
            
        } finally {
            manager.close();
        }
        
        return benchmark.complete();
    }
    
    /**
     * Runs a concurrent allocation benchmark.
     */
    public ResourceBenchmark.BenchmarkResults runConcurrentAllocation(
            String name,
            ResourceConfiguration config,
            int numOperations,
            int minSize,
            int maxSize) throws Exception {
        
        var benchmark = new ResourceBenchmark(name);
        var manager = new UnifiedResourceManager(config);
        var barrier = new CyclicBarrier(threadCount);
        var latch = new CountDownLatch(threadCount);
        
        var tasks = new ArrayList<Future<?>>();
        var opsPerThread = numOperations / threadCount;
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            tasks.add(executor.submit(() -> {
                try {
                    barrier.await();
                    var random = new Random(threadId);
                    var resources = new ArrayList<AutoCloseable>();
                    
                    for (int i = 0; i < opsPerThread; i++) {
                        var size = minSize + random.nextInt(maxSize - minSize);
                        var api = random.nextBoolean() ? "GL" : "CL";
                        var start = System.nanoTime();
                        
                        try {
                            var buffer = allocateBuffer(api, size);
                            var latency = System.nanoTime() - start;
                            benchmark.recordAllocation(api + "_BUFFER", size, latency);
                            resources.add(buffer);
                            
                            // Random deallocations
                            if (random.nextDouble() < 0.3 && !resources.isEmpty()) {
                                var idx = random.nextInt(resources.size());
                                var resource = resources.remove(idx);
                                start = System.nanoTime();
                                resource.close();
                                latency = System.nanoTime() - start;
                                benchmark.recordDeallocation("BUFFER", 0, latency);
                            }
                        } catch (Exception e) {
                            benchmark.recordAllocationFailure(api + "_BUFFER", size);
                        }
                    }
                    
                    // Clean up remaining resources
                    for (var resource : resources) {
                        resource.close();
                    }
                    
                } catch (Exception e) {
                    log.error("Thread {} failed", threadId, e);
                } finally {
                    latch.countDown();
                }
            }));
        }
        
        latch.await();
        manager.close();
        
        return benchmark.complete();
    }
    
    /**
     * Runs a memory pressure benchmark.
     */
    public ResourceBenchmark.BenchmarkResults runMemoryPressure(
            String name,
            ResourceConfiguration config,
            long targetMemoryMB,
            int avgBufferSizeMB) throws Exception {
        
        var benchmark = new ResourceBenchmark(name);
        var manager = new UnifiedResourceManager(config);
        var resources = new ArrayList<AutoCloseable>();
        var random = new Random(42);
        
        var targetBytes = targetMemoryMB * 1024 * 1024;
        var avgBufferBytes = avgBufferSizeMB * 1024 * 1024;
        var currentUsage = 0L;
        
        try {
            while (currentUsage < targetBytes) {
                var size = (int)(avgBufferBytes * (0.5 + random.nextDouble()));
                var api = random.nextBoolean() ? "GL" : "CL";
                var start = System.nanoTime();
                
                try {
                    var buffer = allocateBuffer(api, size);
                    var latency = System.nanoTime() - start;
                    benchmark.recordAllocation(api + "_BUFFER", size, latency);
                    resources.add(buffer);
                    currentUsage += size;
                } catch (OutOfMemoryError e) {
                    benchmark.recordAllocationFailure(api + "_BUFFER", size);
                    benchmark.recordEviction("OOM", size);
                    
                    // Try to free some resources
                    if (!resources.isEmpty()) {
                        var toFree = Math.min(5, resources.size());
                        for (int i = 0; i < toFree; i++) {
                            resources.remove(0).close();
                        }
                    }
                }
            }
            
            // Clean up
            for (var resource : resources) {
                var start = System.nanoTime();
                resource.close();
                var latency = System.nanoTime() - start;
                benchmark.recordDeallocation("BUFFER", 0, latency);
            }
            
        } finally {
            manager.close();
        }
        
        return benchmark.complete();
    }
    
    /**
     * Runs a churn benchmark (rapid allocation/deallocation).
     */
    public ResourceBenchmark.BenchmarkResults runChurnBenchmark(
            String name,
            ResourceConfiguration config,
            int iterations,
            int poolSize,
            int minSize,
            int maxSize) throws Exception {
        
        var benchmark = new ResourceBenchmark(name);
        var manager = new UnifiedResourceManager(config);
        var random = new Random(42);
        var pool = new ArrayList<AutoCloseable>(poolSize);
        
        try {
            // Pre-fill pool
            for (int i = 0; i < poolSize; i++) {
                var size = minSize + random.nextInt(maxSize - minSize);
                pool.add(allocateBuffer("GL", size));
            }
            
            // Churn phase
            for (int i = 0; i < iterations; i++) {
                var idx = random.nextInt(poolSize);
                var oldResource = pool.get(idx);
                
                // Deallocate
                var start = System.nanoTime();
                oldResource.close();
                var latency = System.nanoTime() - start;
                benchmark.recordDeallocation("BUFFER", 0, latency);
                
                // Reallocate
                var size = minSize + random.nextInt(maxSize - minSize);
                var api = random.nextBoolean() ? "GL" : "CL";
                start = System.nanoTime();
                
                try {
                    var newResource = allocateBuffer(api, size);
                    latency = System.nanoTime() - start;
                    benchmark.recordAllocation(api + "_BUFFER", size, latency);
                    pool.set(idx, newResource);
                    
                    if (random.nextDouble() < 0.1) {
                        benchmark.recordPoolHit();
                    } else {
                        benchmark.recordPoolMiss();
                    }
                } catch (Exception e) {
                    benchmark.recordAllocationFailure(api + "_BUFFER", size);
                    pool.set(idx, allocateBuffer(api, minSize)); // Fallback to min size
                }
            }
            
            // Clean up
            for (var resource : pool) {
                resource.close();
            }
            
        } finally {
            manager.close();
        }
        
        return benchmark.complete();
    }
    
    /**
     * Compares different configurations with the same workload.
     */
    public List<ResourceBenchmark.BenchmarkResults> compareConfigurations(
            List<ConfigurationTest> tests,
            Consumer<ResourceConfiguration> workload) throws Exception {
        
        var results = new ArrayList<ResourceBenchmark.BenchmarkResults>();
        
        for (var test : tests) {
            log.info("Running benchmark: {}", test.name);
            var benchmark = new ResourceBenchmark(test.name);
            
            try {
                workload.accept(test.config);
            } catch (Exception e) {
                log.error("Benchmark {} failed", test.name, e);
            }
            
            results.add(benchmark.complete());
        }
        
        return results;
    }
    
    // Helper method to simulate buffer allocation
    private AutoCloseable allocateBuffer(String api, int size) {
        // Simulate allocation with a ByteBuffer
        var buffer = ByteBuffer.allocateDirect(size);
        
        return new AutoCloseable() {
            @Override
            public void close() {
                // Simulate cleanup
                buffer.clear();
            }
        };
    }
    
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Configuration test case.
     */
    public static class ConfigurationTest {
        public final String name;
        public final ResourceConfiguration config;
        
        public ConfigurationTest(String name, ResourceConfiguration config) {
            this.name = name;
            this.config = config;
        }
    }
    
    /**
     * Main method for running standard benchmarks.
     */
    public static void main(String[] args) throws Exception {
        var runner = new BenchmarkRunner();
        
        try {
            // Run sequential benchmark
            var seqResult = runner.runSequentialAllocation(
                "Sequential Allocation",
                ResourceConfiguration.defaultConfig(),
                10000,
                1024,
                1024 * 1024
            );
            System.out.println(seqResult.generateReport());
            
            // Run concurrent benchmark
            var concResult = runner.runConcurrentAllocation(
                "Concurrent Allocation",
                ResourceConfiguration.defaultConfig(),
                10000,
                1024,
                1024 * 1024
            );
            System.out.println(concResult.generateReport());
            
            // Run memory pressure benchmark
            var memResult = runner.runMemoryPressure(
                "Memory Pressure",
                ResourceConfiguration.defaultConfig(),
                256,  // 256 MB target
                4     // 4 MB average buffer
            );
            System.out.println(memResult.generateReport());
            
            // Run churn benchmark
            var churnResult = runner.runChurnBenchmark(
                "Allocation Churn",
                ResourceConfiguration.defaultConfig(),
                50000,
                100,
                1024,
                64 * 1024
            );
            System.out.println(churnResult.generateReport());
            
        } finally {
            runner.shutdown();
        }
    }
}