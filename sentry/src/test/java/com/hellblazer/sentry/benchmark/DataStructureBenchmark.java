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
 * Benchmark comparing LinkedList vs ArrayList performance for flip operations.
 * This demonstrates the expected performance improvement from Phase 1.1.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 2, jvmArgs = {"-Xms1G", "-Xmx1G"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
public class DataStructureBenchmark {

    @Param({"10", "50", "100", "200", "500"})
    private int listSize;

    private LinkedList<OrientedFace> linkedList;
    private ArrayList<OrientedFace> arrayList;
    private List<OrientedFace> faces;
    private Random random;

    @Setup(Level.Trial)
    public void setup() {
        random = new Random(42);
        
        // Create test data
        MutableGrid grid = new MutableGrid();
        for (int i = 0; i < 50; i++) {
            float x = random.nextFloat() * 100;
            float y = random.nextFloat() * 100;
            float z = random.nextFloat() * 100;
            grid.track(new Point3f(x, y, z), random);
        }
        
        // Collect faces
        faces = new ArrayList<>();
        for (Tetrahedron t : grid.tetrahedrons()) {
            for (V vertex : V.values()) {
                faces.add(t.getFace(vertex));
                if (faces.size() >= listSize) {
                    return;
                }
            }
        }
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        linkedList = new LinkedList<>(faces);
        arrayList = new ArrayList<>(faces);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void linkedListRandomAccess(Blackhole bh) {
        for (int i = 0; i < listSize; i++) {
            OrientedFace face = linkedList.get(i);
            bh.consume(face);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void arrayListRandomAccess(Blackhole bh) {
        for (int i = 0; i < listSize; i++) {
            OrientedFace face = arrayList.get(i);
            bh.consume(face);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void linkedListIteration(Blackhole bh) {
        for (OrientedFace face : linkedList) {
            bh.consume(face.getAdjacentVertex());
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void arrayListIteration(Blackhole bh) {
        for (OrientedFace face : arrayList) {
            bh.consume(face.getAdjacentVertex());
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void linkedListRemoval(Blackhole bh) {
        LinkedList<OrientedFace> copy = new LinkedList<>(linkedList);
        while (!copy.isEmpty()) {
            OrientedFace face = copy.remove(copy.size() / 2);
            bh.consume(face);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void arrayListRemoval(Blackhole bh) {
        ArrayList<OrientedFace> copy = new ArrayList<>(arrayList);
        while (!copy.isEmpty()) {
            OrientedFace face = copy.remove(copy.size() / 2);
            bh.consume(face);
        }
    }
}