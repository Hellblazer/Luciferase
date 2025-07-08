/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.ccd;

import com.hellblazer.luciferase.lucien.collision.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Random;

/**
 * Performance benchmarks for Continuous Collision Detection.
 * Measures performance of CCD algorithms for various shape combinations.
 *
 * @author hal.hildebrand
 */
public class ContinuousCollisionPerformanceTest {
    
    private static final int WARMUP_ITERATIONS = 10000;
    private static final int BENCHMARK_ITERATIONS = 100000;
    private Random random;
    
    @BeforeEach
    void setUp() {
        random = new Random(42);
    }
    
    @Test
    void benchmarkCCDPerformance() {
        System.out.println("=== Continuous Collision Detection Performance ===\n");
        
        benchmarkMovingSphereCCD();
        benchmarkSweptSphereCCD();
        benchmarkConservativeAdvancement();
        benchmarkHighSpeedScenarios();
        
        System.out.println("\nNote: All times are average per collision check\n");
    }
    
    private void benchmarkMovingSphereCCD() {
        System.out.println("Moving Sphere vs Sphere CCD:");
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            performSphereSphereCheck();
        }
        
        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            performSphereSphereCheck();
        }
        long totalTime = System.nanoTime() - startTime;
        
        double avgTime = (double) totalTime / BENCHMARK_ITERATIONS;
        System.out.printf("  Average time: %.1f ns\n", avgTime);
        System.out.printf("  Throughput: %.1f million checks/sec\n", 1000.0 / avgTime);
    }
    
    private void benchmarkSweptSphereCCD() {
        System.out.println("\nSwept Sphere vs Static Shapes:");
        
        // Test different static shapes
        benchmarkSweptSphereVsBox();
        benchmarkSweptSphereVsCapsule();
        benchmarkSweptSphereVsTriangle();
    }
    
    private void benchmarkSweptSphereVsBox() {
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(2, 2, 2));
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var start = randomPoint(-10, 10);
            var velocity = randomVector(10);
            SweptSphere.sweptSphereVsBox(start, velocity, 1.0f, box);
        }
        
        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            var start = randomPoint(-10, 10);
            var velocity = randomVector(10);
            SweptSphere.sweptSphereVsBox(start, velocity, 1.0f, box);
        }
        long totalTime = System.nanoTime() - startTime;
        
        System.out.printf("  Swept Sphere vs Box: %.1f ns\n", 
                         (double) totalTime / BENCHMARK_ITERATIONS);
    }
    
    private void benchmarkSweptSphereVsCapsule() {
        var capsule = new CapsuleShape(new Point3f(0, 0, 0), 3.0f, 1.0f);
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var start = randomPoint(-10, 10);
            var velocity = randomVector(10);
            SweptSphere.sweptSphereVsCapsule(start, velocity, 1.0f, capsule);
        }
        
        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            var start = randomPoint(-10, 10);
            var velocity = randomVector(10);
            SweptSphere.sweptSphereVsCapsule(start, velocity, 1.0f, capsule);
        }
        long totalTime = System.nanoTime() - startTime;
        
        System.out.printf("  Swept Sphere vs Capsule: %.1f ns\n", 
                         (double) totalTime / BENCHMARK_ITERATIONS);
    }
    
    private void benchmarkSweptSphereVsTriangle() {
        var v0 = new Point3f(-2, 0, -2);
        var v1 = new Point3f(2, 0, -2);
        var v2 = new Point3f(0, 0, 2);
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var start = randomPoint(-10, 10);
            var velocity = randomVector(10);
            SweptSphere.sweptSphereVsTriangle(start, velocity, 1.0f, v0, v1, v2);
        }
        
        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            var start = randomPoint(-10, 10);
            var velocity = randomVector(10);
            SweptSphere.sweptSphereVsTriangle(start, velocity, 1.0f, v0, v1, v2);
        }
        long totalTime = System.nanoTime() - startTime;
        
        System.out.printf("  Swept Sphere vs Triangle: %.1f ns\n", 
                         (double) totalTime / BENCHMARK_ITERATIONS);
    }
    
    private void benchmarkConservativeAdvancement() {
        System.out.println("\nConservative Advancement (General CCD):");
        
        // Create various shape pairs
        var sphere1 = new SphereShape(new Point3f(0, 0, 0), 1.0f);
        var box1 = new BoxShape(new Point3f(10, 0, 0), new Vector3f(2, 2, 2));
        
        var moving1 = new MovingShape(sphere1, new Point3f(0, 0, 0), 
                                     new Point3f(15, 0, 0), 0, 1);
        var moving2 = new MovingShape(box1, new Point3f(10, 0, 0), 
                                     new Point3f(10, 0, 0), 0, 1);
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
            ContinuousCollisionDetector.detectCollision(moving1, moving2);
        }
        
        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS / 10; i++) {
            ContinuousCollisionDetector.detectCollision(moving1, moving2);
        }
        long totalTime = System.nanoTime() - startTime;
        
        System.out.printf("  Average time: %.1f ns\n", 
                         (double) totalTime * 10 / BENCHMARK_ITERATIONS);
    }
    
    private void benchmarkHighSpeedScenarios() {
        System.out.println("\nHigh-Speed Collision Scenarios:");
        
        // Bullet scenario
        var bullet = new SphereShape(new Point3f(-100, 0, 0), 0.1f);
        var wall = new BoxShape(new Point3f(0, 0, 0), new Vector3f(1, 10, 10));
        
        var bulletMotion = new MovingShape(bullet, new Point3f(-100, 0, 0),
                                          new Point3f(100, 0, 0), 0, 0.001f);
        var staticWall = new MovingShape(wall, wall.getPosition(), 
                                        wall.getPosition(), 0, 0.001f);
        
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS / 10; i++) {
            ContinuousCollisionDetector.detectCollision(bulletMotion, staticWall);
        }
        long bulletTime = System.nanoTime() - startTime;
        
        System.out.printf("  Bullet vs Wall (200m in 1ms): %.1f ns\n", 
                         (double) bulletTime * 10 / BENCHMARK_ITERATIONS);
        
        // Crossing objects
        var obj1 = new SphereShape(new Point3f(-50, 0, 0), 1.0f);
        var obj2 = new SphereShape(new Point3f(0, -50, 0), 1.0f);
        
        var crossing1 = new MovingShape(obj1, new Point3f(-50, 0, 0),
                                       new Point3f(50, 0, 0), 0, 0.1f);
        var crossing2 = new MovingShape(obj2, new Point3f(0, -50, 0),
                                       new Point3f(0, 50, 0), 0, 0.1f);
        
        startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS / 10; i++) {
            ContinuousCollisionDetector.detectCollision(crossing1, crossing2);
        }
        long crossingTime = System.nanoTime() - startTime;
        
        System.out.printf("  High-speed crossing: %.1f ns\n", 
                         (double) crossingTime * 10 / BENCHMARK_ITERATIONS);
    }
    
    private void performSphereSphereCheck() {
        var pos1 = randomPoint(-50, 50);
        var pos2 = randomPoint(-50, 50);
        var vel1 = randomVector(20);
        var vel2 = randomVector(20);
        
        var sphere1 = new SphereShape(pos1, random.nextFloat() * 2 + 0.5f);
        var sphere2 = new SphereShape(pos2, random.nextFloat() * 2 + 0.5f);
        
        var end1 = new Point3f(pos1);
        end1.add(vel1);
        var end2 = new Point3f(pos2);
        end2.add(vel2);
        
        var moving1 = new MovingShape(sphere1, pos1, end1, 0, 1);
        var moving2 = new MovingShape(sphere2, pos2, end2, 0, 1);
        
        ContinuousCollisionDetector.detectCollision(moving1, moving2);
    }
    
    private Point3f randomPoint(float min, float max) {
        float range = max - min;
        return new Point3f(
            random.nextFloat() * range + min,
            random.nextFloat() * range + min,
            random.nextFloat() * range + min
        );
    }
    
    private Vector3f randomVector(float magnitude) {
        var v = new Vector3f(
            random.nextFloat() * 2 - 1,
            random.nextFloat() * 2 - 1,
            random.nextFloat() * 2 - 1
        );
        v.normalize();
        v.scale(magnitude * random.nextFloat());
        return v;
    }
}