package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Baseline benchmark for OrientedFace.flip() operations.
 * This benchmark establishes the baseline performance before optimizations.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 2, jvmArgs = {"-Xms2G", "-Xmx2G"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
public class FlipOperationBenchmark {

    @Param({"10", "50", "100", "200"})
    private int earCount;

    private MutableGrid grid;
    private List<OrientedFace> ears;
    private Vertex testVertex;
    private Random random;

    @Setup(Level.Trial)
    public void setupTrial() {
        random = new Random(42); // Fixed seed for reproducibility
        
        // Create a grid with initial tetrahedralization
        grid = new MutableGrid();
        
        // Add initial vertices to create a base structure
        for (int i = 0; i < 100; i++) {
            float x = random.nextFloat() * 100;
            float y = random.nextFloat() * 100;
            float z = random.nextFloat() * 100;
            grid.track(new Point3f(x, y, z), random);
        }
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        // Create a new vertex for testing
        float x = random.nextFloat() * 100;
        float y = random.nextFloat() * 100;
        float z = random.nextFloat() * 100;
        testVertex = new Vertex(new Point3f(x, y, z));
        
        // Create a list of ears (oriented faces)
        ears = new LinkedList<>();
        
        // Get some faces from the grid to use as ears
        List<Tetrahedron> tets = new ArrayList<>(grid.tetrahedrons());
        
        // Create oriented faces from tetrahedra
        for (Tetrahedron tet : tets) {
            if (ears.size() >= earCount) break;
            for (V vertex : V.values()) {
                if (ears.size() >= earCount) break;
                ears.add(tet.getFace(vertex));
            }
        }
    }

    @Benchmark
    public void baselineFlipWithLinkedList(Blackhole bh) {
        if (!ears.isEmpty()) {
            OrientedFace face = ears.get(0);
            Tetrahedron result = face.flip(testVertex, ears);
            bh.consume(result);
        }
    }

    @Benchmark
    public void baselineFlipIteration(Blackhole bh) {
        // Simulate the iteration pattern in flip method
        for (int i = 0; i < ears.size(); i++) {
            OrientedFace ear = ears.get(i);
            bh.consume(ear.getAdjacentVertex());
        }
    }

    @Benchmark
    public void baselineLinkedListAccess(Blackhole bh) {
        // Measure pure LinkedList access overhead
        for (int i = 0; i < ears.size(); i++) {
            OrientedFace face = ears.get(i);
            bh.consume(face);
        }
    }

    @Benchmark
    public void baselineGetAdjacentVertex(Blackhole bh) {
        // Measure getAdjacentVertex performance
        for (OrientedFace ear : ears) {
            Vertex v = ear.getAdjacentVertex();
            bh.consume(v);
        }
    }
}