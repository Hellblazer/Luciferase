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
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Baseline benchmark for rendering performance (Epic 4).
 * 
 * Measures FPS (frames per second) with various dataset sizes to establish
 * baseline metrics before GPU optimization. Simulates typical rendering workload
 * by casting rays from a camera viewpoint into the spatial index.
 * 
 * Epic 4 goal: GPU-accelerated rendering with ESVO (Efficient Sparse Voxel Octrees).
 * 
 * NOTE: This benchmark requires dangerouslyDisableSandbox=true for GPU access.
 * Run with: mvn test -Dtest=RenderingPerformanceBaselineBenchmark -DdangerouslyDisableSandbox=true
 * 
 * @author hal.hildebrand
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class RenderingPerformanceBaselineBenchmark {

    @Param({"small_sparse_10"})
    private String datasetName;

    @Param({"800x600", "1920x1080"})
    private String resolution;

    private Octree<LongEntityID, String> octree;
    private Ray3D[][] cameraRays;
    private int imageWidth;
    private int imageHeight;
    
    private static final byte LEVEL = 10;
    private static final Path DATASET_DIR = Path.of("src/test/resources/datasets/baseline");

    @Setup
    public void setup() throws IOException {
        System.out.println("\n=== Rendering Performance Baseline Benchmark Setup ===");
        System.out.println("Dataset: " + datasetName);
        System.out.println("Resolution: " + resolution);
        
        // Parse resolution
        var parts = resolution.split("x");
        imageWidth = Integer.parseInt(parts[0]);
        imageHeight = Integer.parseInt(parts[1]);
        
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
        
        // Generate camera rays for rendering
        generateCameraRays(positions);
        System.out.println("Generated " + (imageWidth * imageHeight) + " camera rays");
        System.out.println("Setup complete\n");
    }

    private void generateCameraRays(List<Point3f> positions) {
        cameraRays = new Ray3D[imageHeight][imageWidth];
        
        // Calculate scene bounds for camera positioning
        var bounds = calculateBounds(positions);
        
        // Position camera looking at scene center
        var sceneCenter = new Point3f(
            (bounds[0].x + bounds[1].x) / 2,
            (bounds[0].y + bounds[1].y) / 2,
            (bounds[0].z + bounds[1].z) / 2
        );
        
        // Camera distance: move back 1.5x the scene size
        float sceneSize = Math.max(
            Math.max(bounds[1].x - bounds[0].x, bounds[1].y - bounds[0].y),
            bounds[1].z - bounds[0].z
        );
        
        var cameraPos = new Point3f(
            sceneCenter.x,
            sceneCenter.y,
            sceneCenter.z - sceneSize * 1.5f
        );
        
        // Generate rays for each pixel
        float aspectRatio = (float) imageWidth / imageHeight;
        float fov = 60.0f; // Field of view in degrees
        float fovRadians = (float) Math.toRadians(fov);
        float halfHeight = (float) Math.tan(fovRadians / 2);
        float halfWidth = aspectRatio * halfHeight;
        
        for (int y = 0; y < imageHeight; y++) {
            for (int x = 0; x < imageWidth; x++) {
                // Normalized device coordinates
                float ndcX = (2.0f * x / imageWidth - 1.0f) * halfWidth;
                float ndcY = (1.0f - 2.0f * y / imageHeight) * halfHeight;
                
                // Ray direction from camera through pixel
                var direction = new Vector3f(ndcX, ndcY, 1.0f);
                direction.normalize();
                
                cameraRays[y][x] = new Ray3D(new Point3f(cameraPos), direction);
            }
        }
    }

    private Point3f[] calculateBounds(List<Point3f> positions) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
        
        for (var pos : positions) {
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }
        
        return new Point3f[] {
            new Point3f(minX, minY, minZ),
            new Point3f(maxX, maxY, maxZ)
        };
    }

    /**
     * Benchmark full frame rendering with ray intersection.
     * Simulates rendering a complete frame by casting all camera rays.
     * Result is in frames per second (FPS).
     */
    @Benchmark
    public void benchmarkFullFrameRender(Blackhole blackhole) {
        int hitCount = 0;
        
        // Cast all rays for this frame
        for (int y = 0; y < imageHeight; y++) {
            for (int x = 0; x < imageWidth; x++) {
                var ray = cameraRays[y][x];
                var hit = octree.rayIntersectFirst(ray);
                
                if (hit.isPresent()) {
                    hitCount++;
                    blackhole.consume(hit.get());
                }
            }
        }
        
        blackhole.consume(hitCount);
    }

    /**
     * Benchmark primary ray casting only (no shading).
     * Measures raw ray-scene intersection performance.
     */
    @Benchmark
    public void benchmarkPrimaryRaysOnly(Blackhole blackhole) {
        int hitCount = 0;
        
        for (int y = 0; y < imageHeight; y++) {
            for (int x = 0; x < imageWidth; x++) {
                var ray = cameraRays[y][x];
                var hit = octree.rayIntersectFirst(ray);
                
                if (hit.isPresent()) {
                    hitCount++;
                }
            }
        }
        
        blackhole.consume(hitCount);
    }

    /**
     * Benchmark tiled rendering (8x8 tiles).
     * Simulates typical GPU workgroup size and memory access patterns.
     */
    @Benchmark
    public void benchmarkTiledRender(Blackhole blackhole) {
        int tileSize = 8;
        List<Integer> hitCounts = new ArrayList<>();
        
        for (int tileY = 0; tileY < imageHeight; tileY += tileSize) {
            for (int tileX = 0; tileX < imageWidth; tileX += tileSize) {
                int tileHits = 0;
                
                // Process tile
                for (int y = tileY; y < Math.min(tileY + tileSize, imageHeight); y++) {
                    for (int x = tileX; x < Math.min(tileX + tileSize, imageWidth); x++) {
                        var ray = cameraRays[y][x];
                        var hit = octree.rayIntersectFirst(ray);
                        
                        if (hit.isPresent()) {
                            tileHits++;
                        }
                    }
                }
                
                hitCounts.add(tileHits);
            }
        }
        
        blackhole.consume(hitCounts);
    }

    /**
     * Report rendering statistics.
     */
    @TearDown(Level.Iteration)
    public void reportStats() {
        int totalRays = imageWidth * imageHeight;
        System.out.printf("Rendering stats:%n");
        System.out.printf("  Resolution: %dx%d%n", imageWidth, imageHeight);
        System.out.printf("  Total rays: %,d%n", totalRays);
        System.out.printf("  Dataset: %s (%,d entities)%n", datasetName, octree.size());
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
