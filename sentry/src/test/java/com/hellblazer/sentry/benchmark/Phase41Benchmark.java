package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Final benchmark for Phase 4.1 - Hybrid Predicates
 */
public class Phase41Benchmark {
    
    public static void main(String[] args) {
        System.out.println("Phase 4.1 - Hybrid Exact/Approximate Predicates");
        System.out.println("==============================================\n");
        
        // First, run predicate microbenchmarks to show raw speedup
        System.out.println("=== Raw Predicate Performance ===");
        runPredicateMicrobenchmark();
        
        // Then run insertion benchmarks
        System.out.println("\n=== Insertion Performance ===");
        runInsertionBenchmarks();
    }
    
    private static void runPredicateMicrobenchmark() {
        // Create test data
        Random random = new Random(42);
        int iterations = 100000;
        
        // Test standard predicates
        System.setProperty("sentry.useHybridPredicates", "false");
        GeometricPredicatesFactory.reset();
        GeometricPredicates standard = GeometricPredicatesFactory.create();
        
        // Test hybrid predicates
        System.setProperty("sentry.useHybridPredicates", "true");
        GeometricPredicatesFactory.reset();
        GeometricPredicates hybrid = GeometricPredicatesFactory.create();
        
        // Benchmark orientation
        double[] testData = new double[12];
        for (int i = 0; i < 12; i++) {
            testData[i] = random.nextDouble() * 100;
        }
        
        long standardOrientTime = 0;
        for (int i = 0; i < iterations; i++) {
            // Randomize data slightly to prevent caching
            testData[0] += 0.001;
            long start = System.nanoTime();
            standard.orientation(testData[0], testData[1], testData[2],
                                testData[3], testData[4], testData[5],
                                testData[6], testData[7], testData[8],
                                testData[9], testData[10], testData[11]);
            standardOrientTime += System.nanoTime() - start;
        }
        
        long hybridOrientTime = 0;
        for (int i = 0; i < iterations; i++) {
            // Randomize data slightly to prevent caching
            testData[0] += 0.001;
            long start = System.nanoTime();
            hybrid.orientation(testData[0], testData[1], testData[2],
                              testData[3], testData[4], testData[5],
                              testData[6], testData[7], testData[8],
                              testData[9], testData[10], testData[11]);
            hybridOrientTime += System.nanoTime() - start;
        }
        
        System.out.printf("Orientation - Standard: %.2f ns, Hybrid: %.2f ns, Speedup: %.2fx\n",
            (double)standardOrientTime / iterations,
            (double)hybridOrientTime / iterations,
            (double)standardOrientTime / hybridOrientTime);
        
        // Benchmark inSphere
        double[] sphereData = new double[15];
        for (int i = 0; i < 15; i++) {
            sphereData[i] = random.nextDouble() * 100;
        }
        
        long standardSphereTime = 0;
        for (int i = 0; i < iterations; i++) {
            // Randomize data slightly to prevent caching
            sphereData[0] += 0.001;
            long start = System.nanoTime();
            standard.inSphere(sphereData[0], sphereData[1], sphereData[2],
                             sphereData[3], sphereData[4], sphereData[5],
                             sphereData[6], sphereData[7], sphereData[8],
                             sphereData[9], sphereData[10], sphereData[11],
                             sphereData[12], sphereData[13], sphereData[14]);
            standardSphereTime += System.nanoTime() - start;
        }
        
        long hybridSphereTime = 0;
        for (int i = 0; i < iterations; i++) {
            // Randomize data slightly to prevent caching
            sphereData[0] += 0.001;
            long start = System.nanoTime();
            hybrid.inSphere(sphereData[0], sphereData[1], sphereData[2],
                           sphereData[3], sphereData[4], sphereData[5],
                           sphereData[6], sphereData[7], sphereData[8],
                           sphereData[9], sphereData[10], sphereData[11],
                           sphereData[12], sphereData[13], sphereData[14]);
            hybridSphereTime += System.nanoTime() - start;
        }
        
        System.out.printf("InSphere - Standard: %.2f ns, Hybrid: %.2f ns, Speedup: %.2fx\n",
            (double)standardSphereTime / iterations,
            (double)hybridSphereTime / iterations,
            (double)standardSphereTime / hybridSphereTime);
        
        if (hybrid instanceof HybridGeometricPredicates) {
            System.out.println("\n" + ((HybridGeometricPredicates) hybrid).getStatistics());
        }
    }
    
    private static void runInsertionBenchmarks() {
        int[] sizes = {100, 500, 1000, 2000, 5000};
        Random random = new Random(42);
        
        System.out.println("\nGrid Size | Standard Time | Hybrid Time | Improvement");
        System.out.println("----------|---------------|-------------|------------");
        
        for (int size : sizes) {
            // Clear any previous state
            System.gc();
            
            // Generate test points
            Point3f[] points = new Point3f[size];
            for (int i = 0; i < size; i++) {
                points[i] = new Point3f(
                    random.nextFloat() * 100,
                    random.nextFloat() * 100,
                    random.nextFloat() * 100
                );
            }
            
            // Benchmark standard predicates
            long standardTime = benchmarkWithPredicates(points, random, false);
            
            // Benchmark hybrid predicates  
            long hybridTime = benchmarkWithPredicates(points, random, true);
            
            double improvement = ((double)(standardTime - hybridTime) / standardTime) * 100;
            
            System.out.printf("%9d | %11.2f ms | %9.2f ms | %9.1f%%\n",
                size, 
                standardTime / 1_000_000.0,
                hybridTime / 1_000_000.0,
                improvement);
        }
    }
    
    private static long benchmarkWithPredicates(Point3f[] points, Random random, boolean useHybrid) {
        // Run multiple iterations for accuracy
        int iterations = 10;
        long totalTime = 0;
        
        for (int iter = 0; iter < iterations; iter++) {
            // Set up predicates for this run
            System.setProperty("sentry.useHybridPredicates", String.valueOf(useHybrid));
            GeometricPredicatesFactory.reset();
            
            // Create fresh grid
            MutableGrid grid = new MutableGrid();
            
            // Time insertions
            long start = System.nanoTime();
            for (Point3f point : points) {
                grid.track(point, random);
            }
            long end = System.nanoTime();
            
            totalTime += (end - start);
        }
        
        return totalTime / iterations;
    }
}