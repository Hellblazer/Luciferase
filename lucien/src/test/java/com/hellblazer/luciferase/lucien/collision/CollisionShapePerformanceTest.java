/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Performance benchmarks for collision shape detection.
 * Compares performance of different collision shape types.
 *
 * @author hal.hildebrand
 */
public class CollisionShapePerformanceTest {

    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void benchmarkShapeVsShapeCollisions() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        System.out.println("=== Collision Shape Performance Comparison ===\n");

        // Test different shape combinations
        benchmarkSphereVsSphere();
        benchmarkBoxVsBox();
        benchmarkCapsuleVsCapsule();
        benchmarkOrientedBoxVsOrientedBox();
        benchmarkMixedShapes();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void benchmarkSpatialIndexWithCustomShapes() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        System.out.println("\n=== Spatial Index Custom Shape Performance ===\n");

        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        int entityCount = 1000;

        // Insert entities with custom shapes
        long insertStart = System.nanoTime();
        List<LongEntityID> entities = new ArrayList<>();
        
        for (int i = 0; i < entityCount; i++) {
            // Margin of 20 ensures collision shapes don't extend into negative space
            float margin = 20f;
            var pos = new Point3f(
                margin + random.nextFloat() * (1000 - 2 * margin),
                margin + random.nextFloat() * (1000 - 2 * margin),
                margin + random.nextFloat() * (1000 - 2 * margin)
            );
            var id = octree.insert(pos, (byte) 10, "Entity" + i);
            entities.add(id);

            // Assign random shape type
            switch (i % 4) {
                case 0:
                    octree.setCollisionShape(id, new SphereShape(pos, 5 + random.nextFloat() * 10));
                    break;
                case 1:
                    octree.setCollisionShape(id, new BoxShape(pos, 
                        new Vector3f(5 + random.nextFloat() * 10, 
                                    5 + random.nextFloat() * 10, 
                                    5 + random.nextFloat() * 10)));
                    break;
                case 2:
                    octree.setCollisionShape(id, new CapsuleShape(pos, 
                        10 + random.nextFloat() * 10,
                        3 + random.nextFloat() * 5));
                    break;
                case 3:
                    var rotation = new Matrix3f();
                    rotation.rotY(random.nextFloat() * (float) Math.PI * 2);
                    octree.setCollisionShape(id, new OrientedBoxShape(pos,
                        new Vector3f(5 + random.nextFloat() * 10,
                                    5 + random.nextFloat() * 10,
                                    5 + random.nextFloat() * 10), rotation));
                    break;
            }
        }
        long insertTime = System.nanoTime() - insertStart;

        // Benchmark findAllCollisions
        long findAllStart = System.nanoTime();
        var allCollisions = octree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStart;

        // Benchmark individual collision checks
        long checkStart = System.nanoTime();
        int checks = 0;
        for (int i = 0; i < Math.min(100, entities.size()); i++) {
            for (int j = i + 1; j < Math.min(100, entities.size()); j++) {
                octree.checkCollision(entities.get(i), entities.get(j));
                checks++;
            }
        }
        long checkTime = System.nanoTime() - checkStart;

        System.out.println("Spatial Index with Custom Shapes:");
        System.out.println("  Entities: " + entityCount + " (mixed shape types)");
        System.out.println("  Insert + Shape Assignment: " + (insertTime / 1_000_000) + " ms");
        System.out.println("  Find All Collisions: " + (findAllTime / 1_000_000) + " ms");
        System.out.println("  Individual Checks: " + (checkTime / 1_000_000) + " ms (" + checks + " pairs)");
        System.out.println("  Collisions Found: " + allCollisions.size());
        System.out.println("  Avg per collision check: " + (checkTime / checks / 1000) + " μs");
    }

    private void benchmarkSphereVsSphere() {
        int iterations = 100000;
        var sphere1 = new SphereShape(new Point3f(0, 0, 0), 5.0f);
        var sphere2 = new SphereShape(new Point3f(8, 0, 0), 5.0f);

        // Warmup
        for (int i = 0; i < 1000; i++) {
            sphere1.collidesWith(sphere2);
        }

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            sphere1.collidesWith(sphere2);
        }
        long elapsed = System.nanoTime() - start;

        System.out.println("Sphere vs Sphere:");
        System.out.println("  Iterations: " + iterations);
        System.out.println("  Total time: " + (elapsed / 1_000_000) + " ms");
        System.out.println("  Avg per collision: " + (elapsed / iterations) + " ns");
        System.out.println();
    }

    private void benchmarkBoxVsBox() {
        int iterations = 100000;
        var box1 = new BoxShape(new Point3f(0, 0, 0), new Vector3f(5, 5, 5));
        var box2 = new BoxShape(new Point3f(8, 0, 0), new Vector3f(5, 5, 5));

        // Warmup
        for (int i = 0; i < 1000; i++) {
            box1.collidesWith(box2);
        }

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            box1.collidesWith(box2);
        }
        long elapsed = System.nanoTime() - start;

        System.out.println("Box vs Box:");
        System.out.println("  Iterations: " + iterations);
        System.out.println("  Total time: " + (elapsed / 1_000_000) + " ms");
        System.out.println("  Avg per collision: " + (elapsed / iterations) + " ns");
        System.out.println();
    }

    private void benchmarkCapsuleVsCapsule() {
        int iterations = 100000;
        var capsule1 = new CapsuleShape(new Point3f(0, 0, 0), 10.0f, 3.0f);
        var capsule2 = new CapsuleShape(new Point3f(5, 0, 0), 10.0f, 3.0f);

        // Warmup
        for (int i = 0; i < 1000; i++) {
            capsule1.collidesWith(capsule2);
        }

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            capsule1.collidesWith(capsule2);
        }
        long elapsed = System.nanoTime() - start;

        System.out.println("Capsule vs Capsule:");
        System.out.println("  Iterations: " + iterations);
        System.out.println("  Total time: " + (elapsed / 1_000_000) + " ms");
        System.out.println("  Avg per collision: " + (elapsed / iterations) + " ns");
        System.out.println();
    }

    private void benchmarkOrientedBoxVsOrientedBox() {
        int iterations = 100000;
        
        var rotation1 = new Matrix3f();
        rotation1.rotY((float) Math.PI / 4);
        var obb1 = new OrientedBoxShape(new Point3f(0, 0, 0), new Vector3f(5, 5, 5), rotation1);

        var rotation2 = new Matrix3f();
        rotation2.rotY((float) -Math.PI / 4);
        var obb2 = new OrientedBoxShape(new Point3f(7, 0, 0), new Vector3f(5, 5, 5), rotation2);

        // Warmup
        for (int i = 0; i < 1000; i++) {
            obb1.collidesWith(obb2);
        }

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            obb1.collidesWith(obb2);
        }
        long elapsed = System.nanoTime() - start;

        System.out.println("Oriented Box vs Oriented Box:");
        System.out.println("  Iterations: " + iterations);
        System.out.println("  Total time: " + (elapsed / 1_000_000) + " ms");
        System.out.println("  Avg per collision: " + (elapsed / iterations) + " ns");
        System.out.println();
    }

    private void benchmarkMixedShapes() {
        int iterations = 50000;
        
        var sphere = new SphereShape(new Point3f(0, 0, 0), 5.0f);
        var box = new BoxShape(new Point3f(8, 0, 0), new Vector3f(5, 5, 5));
        var capsule = new CapsuleShape(new Point3f(0, 8, 0), 10.0f, 3.0f);
        
        var rotation = new Matrix3f();
        rotation.rotY((float) Math.PI / 3);
        var obb = new OrientedBoxShape(new Point3f(8, 8, 0), new Vector3f(5, 5, 5), rotation);

        // Warmup
        for (int i = 0; i < 1000; i++) {
            sphere.collidesWith(box);
            sphere.collidesWith(capsule);
            box.collidesWith(capsule);
            capsule.collidesWith(obb);
        }

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            sphere.collidesWith(box);
            sphere.collidesWith(capsule);
            box.collidesWith(capsule);
            capsule.collidesWith(obb);
        }
        long elapsed = System.nanoTime() - start;

        System.out.println("Mixed Shape Collisions (4 different combinations):");
        System.out.println("  Iterations: " + iterations + " x 4");
        System.out.println("  Total time: " + (elapsed / 1_000_000) + " ms");
        System.out.println("  Avg per collision: " + (elapsed / (iterations * 4)) + " ns");
        System.out.println();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void benchmarkRayIntersections() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        System.out.println("\n=== Ray Intersection Performance ===\n");

        int iterations = 100000;
        var ray = new com.hellblazer.luciferase.lucien.Ray3D(
            new Point3f(-10, 0, 0), 
            new Vector3f(1, 0, 0)
        );

        // Test sphere ray intersection
        var sphere = new SphereShape(new Point3f(0, 0, 0), 5.0f);
        long sphereStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            sphere.intersectRay(ray);
        }
        long sphereTime = System.nanoTime() - sphereStart;

        // Test box ray intersection
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(5, 5, 5));
        long boxStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            box.intersectRay(ray);
        }
        long boxTime = System.nanoTime() - boxStart;

        // Test capsule ray intersection
        var capsule = new CapsuleShape(new Point3f(0, 0, 0), 10.0f, 3.0f);
        long capsuleStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            capsule.intersectRay(ray);
        }
        long capsuleTime = System.nanoTime() - capsuleStart;

        System.out.println("Ray-Sphere: " + (sphereTime / iterations) + " ns/intersection");
        System.out.println("Ray-Box: " + (boxTime / iterations) + " ns/intersection");
        System.out.println("Ray-Capsule: " + (capsuleTime / iterations) + " ns/intersection");
    }

    private boolean isRunningInCI() {
        return "true".equals(System.getenv("CI")) || 
               "true".equals(System.getProperty("CI")) || 
               "true".equals(System.getenv("GITHUB_ACTIONS"));
    }
}