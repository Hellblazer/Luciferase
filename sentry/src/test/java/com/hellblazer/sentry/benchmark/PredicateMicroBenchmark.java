package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Microbenchmark to test raw predicate performance
 */
public class PredicateMicroBenchmark {
    
    public static void main(String[] args) {
        System.out.println("Predicate Microbenchmark");
        System.out.println("========================\n");
        
        // Generate test data
        Random random = new Random(42);
        int numPoints = 1000;
        Point3f[] points = generatePoints(numPoints, random);
        
        // Test orientation predicates
        System.out.println("=== Orientation Predicate Performance ===");
        benchmarkOrientation(points);
        
        System.out.println("\n=== InSphere Predicate Performance ===");
        benchmarkInSphere(points);
        
        System.out.println("\n=== Edge Case Analysis ===");
        analyzeEdgeCases();
    }
    
    private static Point3f[] generatePoints(int count, Random random) {
        Point3f[] points = new Point3f[count];
        for (int i = 0; i < count; i++) {
            points[i] = new Point3f(
                random.nextFloat() * 100,
                random.nextFloat() * 100,
                random.nextFloat() * 100
            );
        }
        return points;
    }
    
    private static void benchmarkOrientation(Point3f[] points) {
        // Test with standard predicates
        System.setProperty("sentry.useHybridPredicates", "false");
        GeometricPredicatesFactory.reset();
        GeometricPredicates standard = GeometricPredicatesFactory.getInstance();
        
        // Test with hybrid predicates
        System.setProperty("sentry.useHybridPredicates", "true");
        GeometricPredicatesFactory.reset();
        GeometricPredicates hybrid = GeometricPredicatesFactory.getInstance();
        
        int iterations = 100000;
        Random random = new Random(42);
        
        // Benchmark standard
        long standardTime = 0;
        for (int i = 0; i < iterations; i++) {
            Point3f a = points[random.nextInt(points.length)];
            Point3f b = points[random.nextInt(points.length)];
            Point3f c = points[random.nextInt(points.length)];
            Point3f d = points[random.nextInt(points.length)];
            
            long start = System.nanoTime();
            standard.orientation(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z);
            standardTime += System.nanoTime() - start;
        }
        
        // Benchmark hybrid
        long hybridTime = 0;
        for (int i = 0; i < iterations; i++) {
            Point3f a = points[random.nextInt(points.length)];
            Point3f b = points[random.nextInt(points.length)];
            Point3f c = points[random.nextInt(points.length)];
            Point3f d = points[random.nextInt(points.length)];
            
            long start = System.nanoTime();
            hybrid.orientation(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z);
            hybridTime += System.nanoTime() - start;
        }
        
        System.out.printf("Standard: %.2f ns/call\n", (double)standardTime / iterations);
        System.out.printf("Hybrid: %.2f ns/call\n", (double)hybridTime / iterations);
        System.out.printf("Speedup: %.2fx\n", (double)standardTime / hybridTime);
        
        if (hybrid instanceof HybridGeometricPredicates) {
            System.out.println(((HybridGeometricPredicates) hybrid).getStatistics());
        }
    }
    
    private static void benchmarkInSphere(Point3f[] points) {
        // Test with standard predicates
        System.setProperty("sentry.useHybridPredicates", "false");
        GeometricPredicatesFactory.reset();
        GeometricPredicates standard = GeometricPredicatesFactory.getInstance();
        
        // Test with hybrid predicates
        System.setProperty("sentry.useHybridPredicates", "true");
        GeometricPredicatesFactory.reset();
        GeometricPredicates hybrid = GeometricPredicatesFactory.getInstance();
        
        int iterations = 100000;
        Random random = new Random(42);
        
        // Benchmark standard
        long standardTime = 0;
        for (int i = 0; i < iterations; i++) {
            Point3f a = points[random.nextInt(points.length)];
            Point3f b = points[random.nextInt(points.length)];
            Point3f c = points[random.nextInt(points.length)];
            Point3f d = points[random.nextInt(points.length)];
            Point3f query = points[random.nextInt(points.length)];
            
            long start = System.nanoTime();
            standard.inSphere(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z, query.x, query.y, query.z);
            standardTime += System.nanoTime() - start;
        }
        
        // Benchmark hybrid
        long hybridTime = 0;
        for (int i = 0; i < iterations; i++) {
            Point3f a = points[random.nextInt(points.length)];
            Point3f b = points[random.nextInt(points.length)];
            Point3f c = points[random.nextInt(points.length)];
            Point3f d = points[random.nextInt(points.length)];
            Point3f query = points[random.nextInt(points.length)];
            
            long start = System.nanoTime();
            hybrid.inSphere(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z, query.x, query.y, query.z);
            hybridTime += System.nanoTime() - start;
        }
        
        System.out.printf("Standard: %.2f ns/call\n", (double)standardTime / iterations);
        System.out.printf("Hybrid: %.2f ns/call\n", (double)hybridTime / iterations);
        System.out.printf("Speedup: %.2fx\n", (double)standardTime / hybridTime);
        
        if (hybrid instanceof HybridGeometricPredicates) {
            System.out.println(((HybridGeometricPredicates) hybrid).getStatistics());
        }
    }
    
    private static void analyzeEdgeCases() {
        System.setProperty("sentry.useHybridPredicates", "true");
        GeometricPredicatesFactory.reset();
        GeometricPredicates hybrid = GeometricPredicatesFactory.getInstance();
        
        if (!(hybrid instanceof HybridGeometricPredicates)) {
            return;
        }
        
        HybridGeometricPredicates hybridPred = (HybridGeometricPredicates) hybrid;
        hybridPred.resetStatistics();
        
        // Test nearly coplanar points (should trigger exact predicates)
        Point3f a = new Point3f(0, 0, 0);
        Point3f b = new Point3f(1, 0, 0);
        Point3f c = new Point3f(0, 1, 0);
        Point3f d = new Point3f(0.5f, 0.5f, 0.0001f); // Nearly coplanar
        
        for (int i = 0; i < 1000; i++) {
            hybrid.orientation(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z);
        }
        
        System.out.println("Nearly coplanar test:");
        System.out.println(hybridPred.getStatistics());
        
        // Test well-separated points (should use approximate)
        hybridPred.resetStatistics();
        Point3f e = new Point3f(0, 0, 0);
        Point3f f = new Point3f(10, 0, 0);
        Point3f g = new Point3f(0, 10, 0);
        Point3f h = new Point3f(0, 0, 10);
        
        for (int i = 0; i < 1000; i++) {
            hybrid.orientation(e.x, e.y, e.z, f.x, f.y, f.z, g.x, g.y, g.z, h.x, h.y, h.z);
        }
        
        System.out.println("\nWell-separated test:");
        System.out.println(hybridPred.getStatistics());
    }
}