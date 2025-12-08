/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.benchmark.baseline;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.geometry.Ray3D;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Baseline benchmark for ray traversal operations (Epic 2).
 * 
 * Measures the average number of nodes visited per ray intersection to establish
 * baseline metrics before beam optimization. Target improvement: 30-50% reduction
 * in nodes visited with beam optimization.
 * 
 * Uses datasets generated in Bead 0.4 for realistic performance measurements.
 * 
 * @author hal.hildebrand
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class RayTraversalBaselineBenchmark {

    @Param({"small_sparse_10"})
    private String datasetName;

    private Octree<LongEntityID, String> octree;
    private Tetree<LongEntityID, String> tetree;
    private Ray3D[] testRays;
    
    private static final int RAY_COUNT = 1000;
    private static final byte LEVEL = 10;
    private static final Path DATASET_DIR = Path.of("src/test/resources/datasets/baseline");

    @Setup
    public void setup() throws IOException {
        System.out.println("\n=== Ray Traversal Baseline Benchmark Setup ===");
        System.out.println("Dataset: " + datasetName);
        
        // Load dataset
        var datasetPath = DATASET_DIR.resolve(datasetName + ".dataset");
        var entities = DatasetGenerator.readDataset(datasetPath);
        
        System.out.println("Loaded " + entities.size() + " entities");
        
        // Extract positions and contents
        var positions = entities.stream().map(e -> e.position).toList();
        var contents = entities.stream().map(e -> e.data).toList();
        
        // Create and populate Octree
        System.out.println("Populating Octree...");
        octree = new Octree<>(new SequentialLongIDGenerator(), 100, (byte) 20);
        octree.insertBatch(positions, contents, LEVEL);
        System.out.println("Octree populated: " + octree.size() + " entities");
        
        // Create and populate Tetree
        System.out.println("Populating Tetree...");
        tetree = new Tetree<>(new SequentialLongIDGenerator(), 100, (byte) 20);
        tetree.insertBatch(positions, contents, LEVEL);
        System.out.println("Tetree populated: " + tetree.size() + " entities");
        
        // Generate test rays
        generateTestRays(positions);
        System.out.println("Generated " + RAY_COUNT + " test rays");
        System.out.println("Setup complete\n");
    }

    private void generateTestRays(List<Point3f> positions) {
        testRays = new Ray3D[RAY_COUNT];
        var random = new Random(42); // Fixed seed for reproducibility
        
        // Generate rays from random entity positions toward other random positions
        for (int i = 0; i < RAY_COUNT; i++) {
            var origin = positions.get(random.nextInt(positions.size()));
            var target = positions.get(random.nextInt(positions.size()));
            
            // Calculate direction vector
            var direction = new Vector3f(
                target.x - origin.x,
                target.y - origin.y,
                target.z - origin.z
            );
            direction.normalize();
            
            testRays[i] = new Ray3D(origin, direction);
        }
    }

    /**
     * Benchmark Octree ray intersection (all hits).
     * Measures throughput and nodes visited for complete ray traversal.
     */
    @Benchmark
    public void benchmarkOctreeRayIntersectAll(Blackhole blackhole) {
        for (var ray : testRays) {
            var hits = octree.rayIntersectAll(ray);
            blackhole.consume(hits);
        }
    }

    /**
     * Benchmark Octree ray intersection (first hit only).
     * Measures early-exit performance.
     */
    @Benchmark
    public void benchmarkOctreeRayIntersectFirst(Blackhole blackhole) {
        for (var ray : testRays) {
            var hit = octree.rayIntersectFirst(ray);
            blackhole.consume(hit);
        }
    }

    /**
     * Benchmark Tetree ray intersection (all hits).
     * Tetree uses tetrahedral subdivision which affects traversal patterns.
     */
    @Benchmark
    public void benchmarkTetreeRayIntersectAll(Blackhole blackhole) {
        for (var ray : testRays) {
            var hits = tetree.rayIntersectAll(ray);
            blackhole.consume(hits);
        }
    }

    /**
     * Benchmark Tetree ray intersection (first hit only).
     * Measures early-exit performance for tetrahedral structure.
     */
    @Benchmark
    public void benchmarkTetreeRayIntersectFirst(Blackhole blackhole) {
        for (var ray : testRays) {
            var hit = tetree.rayIntersectFirst(ray);
            blackhole.consume(hit);
        }
    }

    /**
     * Benchmark Octree ray intersection with distance limit.
     * Useful for local queries and collision detection.
     */
    @Benchmark
    public void benchmarkOctreeRayIntersectWithin(Blackhole blackhole) {
        float maxDistance = 100.0f;
        for (var ray : testRays) {
            var hits = octree.rayIntersectWithin(ray, maxDistance);
            blackhole.consume(hits);
        }
    }

    /**
     * Benchmark Tetree ray intersection with distance limit.
     */
    @Benchmark
    public void benchmarkTetreeRayIntersectWithin(Blackhole blackhole) {
        float maxDistance = 100.0f;
        for (var ray : testRays) {
            var hits = tetree.rayIntersectWithin(ray, maxDistance);
            blackhole.consume(hits);
        }
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
