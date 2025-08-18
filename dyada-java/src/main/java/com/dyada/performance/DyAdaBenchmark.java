package com.dyada.performance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Comprehensive benchmark suite for DyAda performance testing
 * Measures throughput, latency, memory usage, and scalability
 */
public final class DyAdaBenchmark {
    
    private final Random random = new Random(42);
    private final PerformanceProfiler profiler = PerformanceProfiler.getInstance();
    
    public record BenchmarkResult(
        String name,
        long operations,
        Duration totalTime,
        Duration averageTime,
        Duration minTime,
        Duration maxTime,
        double throughput,
        long memoryUsed,
        String details
    ) {
        public String format() {
            return String.format(
                "%s: %.2f ops/sec, avg: %.3f ms, mem: %d KB",
                name, throughput, averageTime.toNanos() / 1_000_000.0, memoryUsed / 1024
            );
        }
    }
    
    public static class BenchmarkSuite {
        private final List<BenchmarkResult> results = new ArrayList<>();
        private final PerformanceProfiler profiler = PerformanceProfiler.getInstance();
        
        public void runBenchmark(String name, int iterations, Runnable operation) {
            profiler.setEnabled(true);
            System.gc(); // Clean up before benchmark
            
            var startTime = Instant.now();
            var startMemory = getUsedMemory();
            
            var times = new ArrayList<Duration>();
            
            for (int i = 0; i < iterations; i++) {
                var opStart = Instant.now();
                operation.run();
                var opEnd = Instant.now();
                times.add(Duration.between(opStart, opEnd));
            }
            
            var endTime = Instant.now();
            var endMemory = getUsedMemory();
            
            var totalTime = Duration.between(startTime, endTime);
            var averageTime = Duration.ofNanos(
                times.stream().mapToLong(Duration::toNanos).sum() / iterations
            );
            var minTime = times.stream().min(Duration::compareTo).orElse(Duration.ZERO);
            var maxTime = times.stream().max(Duration::compareTo).orElse(Duration.ZERO);
            var throughput = iterations / (totalTime.toNanos() / 1_000_000_000.0);
            var memoryUsed = endMemory - startMemory;
            
            var result = new BenchmarkResult(
                name, iterations, totalTime, averageTime, minTime, maxTime,
                throughput, memoryUsed, ""
            );
            
            results.add(result);
            System.out.println(result.format());
        }
        
        public void runScalabilityBenchmark(String name, Supplier<Runnable> operationFactory, 
                                          int[] sizes) {
            System.out.println("\n=== Scalability Benchmark: " + name + " ===");
            
            for (int size : sizes) {
                var operation = operationFactory.get();
                runBenchmark(name + " (n=" + size + ")", 100, operation);
            }
        }
        
        public void printSummary() {
            System.out.println("\n=== Benchmark Summary ===");
            results.forEach(r -> System.out.println(r.format()));
            
            var totalThroughput = results.stream()
                .mapToDouble(BenchmarkResult::throughput)
                .sum();
            var totalMemory = results.stream()
                .mapToLong(BenchmarkResult::memoryUsed)
                .sum();
            
            System.out.printf("\nTotal throughput: %.2f ops/sec\n", totalThroughput);
            System.out.printf("Total memory: %d KB\n", totalMemory / 1024);
        }
        
        private long getUsedMemory() {
            var runtime = Runtime.getRuntime();
            return runtime.totalMemory() - runtime.freeMemory();
        }
    }
    
    public void runAllBenchmarks() {
        var suite = new BenchmarkSuite();
        
        System.out.println("=== DyAda Performance Benchmark Suite ===\n");
        
        // Morton encoding benchmarks
        runMortonBenchmarks(suite);
        
        // BitArray benchmarks
        runBitArrayBenchmarks(suite);
        
        // Cache benchmarks
        runCacheBenchmarks(suite);
        
        // Parallel processing benchmarks
        runParallelBenchmarks(suite);
        
        // Memory optimization benchmarks
        runMemoryBenchmarks(suite);
        
        suite.printSummary();
    }
    
    private void runMortonBenchmarks(BenchmarkSuite suite) {
        System.out.println("\n=== Morton Encoding Benchmarks ===");
        
        // 2D Morton encoding
        int[] xCoords = generateRandomCoords(10000);
        int[] yCoords = generateRandomCoords(10000);
        
        suite.runBenchmark("Morton 2D Encode", 1000, () -> {
            for (int i = 0; i < 1000; i++) {
                MortonOptimizer.encode2D(xCoords[i], yCoords[i]);
            }
        });
        
        // 3D Morton encoding
        int[] zCoords = generateRandomCoords(10000);
        suite.runBenchmark("Morton 3D Encode", 1000, () -> {
            for (int i = 0; i < 1000; i++) {
                MortonOptimizer.encode3D(xCoords[i], yCoords[i], zCoords[i]);
            }
        });
        
        // Morton decoding
        long[] mortonCodes = generateRandomMortonCodes(10000);
        suite.runBenchmark("Morton 2D Decode", 1000, () -> {
            for (int i = 0; i < 1000; i++) {
                var x = MortonOptimizer.decodeX2D(mortonCodes[i]);
                var y = MortonOptimizer.decodeY2D(mortonCodes[i]);
            }
        });
        
        // Parallel Morton encoding
        suite.runBenchmark("Morton 2D Parallel", 100, () -> {
            ParallelDyAdaOperations.encodeMorton2DParallel(xCoords, yCoords);
        });
    }
    
    private void runBitArrayBenchmarks(BenchmarkSuite suite) {
        System.out.println("\n=== BitArray Benchmarks ===");
        
        var bitArray1 = OptimizedBitArray.create(100000);
        var bitArray2 = OptimizedBitArray.create(100000);
        
        // Fill with random data
        for (int i = 0; i < 50000; i++) {
            bitArray1.set(random.nextInt(100000));
            bitArray2.set(random.nextInt(100000));
        }
        
        suite.runBenchmark("BitArray AND", 1000, () -> {
            bitArray1.and(bitArray2);
        });
        
        suite.runBenchmark("BitArray OR", 1000, () -> {
            bitArray1.or(bitArray2);
        });
        
        suite.runBenchmark("BitArray Cardinality", 1000, () -> {
            bitArray1.cardinality();
        });
        
        suite.runBenchmark("BitArray NextSetBit", 1000, () -> {
            for (int i = 0; i < 100; i++) {
                bitArray1.nextSetBit(i * 1000);
            }
        });
    }
    
    private void runCacheBenchmarks(BenchmarkSuite suite) {
        System.out.println("\n=== Cache Benchmarks ===");
        
        var cache = DyAdaCache.<String, Integer>createLRU(1000);
        
        // Cache hit benchmark
        for (int i = 0; i < 500; i++) {
            cache.put("key" + i, i);
        }
        
        suite.runBenchmark("Cache Hit", 10000, () -> {
            cache.get("key" + random.nextInt(500));
        });
        
        // Cache miss benchmark
        suite.runBenchmark("Cache Miss", 1000, () -> {
            cache.get("missing" + random.nextInt(1000));
        });
        
        // Morton cache benchmark
        suite.runBenchmark("Morton Cache 2D", 1000, () -> {
            for (int i = 0; i < 100; i++) {
                MortonCache.encode2DCached(random.nextInt(1000), random.nextInt(1000));
            }
        });
    }
    
    private void runParallelBenchmarks(BenchmarkSuite suite) {
        System.out.println("\n=== Parallel Processing Benchmarks ===");
        
        int[] xCoords = generateRandomCoords(50000);
        int[] yCoords = generateRandomCoords(50000);
        
        suite.runBenchmark("Sequential Morton Encoding", 10, () -> {
            for (int i = 0; i < xCoords.length; i++) {
                MortonOptimizer.encode2D(xCoords[i], yCoords[i]);
            }
        });
        
        suite.runBenchmark("Parallel Morton Encoding", 10, () -> {
            ParallelDyAdaOperations.encodeMorton2DParallel(xCoords, yCoords);
        });
        
        // Parallel BitArray operations
        var bitArrays = new ArrayList<OptimizedBitArray>();
        for (int i = 0; i < 10; i++) {
            var ba = OptimizedBitArray.create(10000);
            for (int j = 0; j < 5000; j++) {
                ba.set(random.nextInt(10000));
            }
            bitArrays.add(ba);
        }
        
        suite.runBenchmark("Parallel BitArray AND", 100, () -> {
            ParallelDyAdaOperations.parallelBitwiseAnd(bitArrays);
        });
    }
    
    private void runMemoryBenchmarks(BenchmarkSuite suite) {
        System.out.println("\n=== Memory Benchmarks ===");
        
        suite.runBenchmark("BitArray Memory Usage", 1, () -> {
            var sizes = new int[]{1000, 10000, 100000, 1000000};
            
            for (int size : sizes) {
                var bitArray = OptimizedBitArray.create(size);
                var memory = bitArray.memoryUsage();
                System.out.printf("  Size %d: %d bytes (%.2f bytes/bit)\n", 
                    size, memory, (double) memory / size);
            }
        });
        
        // Memory allocation benchmark
        suite.runBenchmark("Object Allocation", 10000, () -> {
            var ba = OptimizedBitArray.create(1000);
            for (int i = 0; i < 100; i++) {
                ba.set(i);
            }
        });
    }
    
    private int[] generateRandomCoords(int count) {
        var coords = new int[count];
        for (int i = 0; i < count; i++) {
            coords[i] = random.nextInt(1000000);
        }
        return coords;
    }
    
    private long[] generateRandomMortonCodes(int count) {
        var codes = new long[count];
        for (int i = 0; i < count; i++) {
            codes[i] = MortonOptimizer.encode2D(
                random.nextInt(1000000), 
                random.nextInt(1000000)
            );
        }
        return codes;
    }
    
    public static void main(String[] args) {
        var benchmark = new DyAdaBenchmark();
        benchmark.runAllBenchmarks();
    }
}