package com.hellblazer.sentry.benchmark;

import com.hellblazer.luciferase.geometry.Geometry;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark for geometric predicate performance.
 * Measures the performance of critical geometric calculations.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 2, jvmArgs = {"-Xms1G", "-Xmx1G"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
public class GeometricPredicateBenchmark {

    private double[] coords;
    private Random random;

    @Setup(Level.Trial)
    public void setup() {
        random = new Random(42);
        coords = new double[15]; // 5 points x 3 coordinates (x,y,z)
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        // Generate random coordinates for testing
        for (int i = 0; i < 15; i++) {
            coords[i] = random.nextDouble() * 1000;
        }
    }

    @Benchmark
    public void baselineLeftOfPlaneFast(Blackhole bh) {
        double result = Geometry.leftOfPlaneFast(
            coords[0], coords[1], coords[2],
            coords[3], coords[4], coords[5],
            coords[6], coords[7], coords[8],
            coords[9], coords[10], coords[11]
        );
        bh.consume(result);
    }

    @Benchmark
    public void baselineInSphereFast(Blackhole bh) {
        double result = Geometry.inSphereFast(
            coords[0], coords[1], coords[2],
            coords[3], coords[4], coords[5],
            coords[6], coords[7], coords[8],
            coords[9], coords[10], coords[11],
            coords[12], coords[13], coords[14]
        );
        bh.consume(result);
    }

    @Benchmark
    public void baselineLeftOfPlane(Blackhole bh) {
        double result = Geometry.leftOfPlane(
            coords[0], coords[1], coords[2],
            coords[3], coords[4], coords[5],
            coords[6], coords[7], coords[8],
            coords[9], coords[10], coords[11]
        );
        bh.consume(result);
    }

    @Benchmark
    public void baselineInSphere(Blackhole bh) {
        double result = Geometry.inSphere(
            coords[0], coords[1], coords[2],
            coords[3], coords[4], coords[5],
            coords[6], coords[7], coords[8],
            coords[9], coords[10], coords[11],
            coords[12], coords[13], coords[14]
        );
        bh.consume(result);
    }

    @Benchmark
    public void multiplePredicatesSequential(Blackhole bh) {
        // Simulate multiple predicate calculations as in flip operations
        double result1 = Geometry.leftOfPlaneFast(
            coords[0], coords[1], coords[2],
            coords[3], coords[4], coords[5],
            coords[6], coords[7], coords[8],
            coords[9], coords[10], coords[11]
        );
        
        double result2 = Geometry.leftOfPlaneFast(
            coords[3], coords[4], coords[5],
            coords[6], coords[7], coords[8],
            coords[9], coords[10], coords[11],
            coords[12], coords[13], coords[14]
        );
        
        double result3 = Geometry.inSphereFast(
            coords[0], coords[1], coords[2],
            coords[3], coords[4], coords[5],
            coords[6], coords[7], coords[8],
            coords[9], coords[10], coords[11],
            coords[12], coords[13], coords[14]
        );
        
        bh.consume(result1);
        bh.consume(result2);
        bh.consume(result3);
    }
}