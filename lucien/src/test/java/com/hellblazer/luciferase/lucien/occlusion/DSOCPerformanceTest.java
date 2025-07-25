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
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.FrustumIntersection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;

/**
 * Performance tests for DSOC system
 *
 * @author hal.hildebrand
 */
public class DSOCPerformanceTest {
    
    private EntityIDGenerator<LongEntityID> idGenerator;
    
    @BeforeEach
    public void setup() {
        idGenerator = new SequentialLongIDGenerator();
    }
    
    @Test
    @Disabled("Performance test - run manually with -Dtest=DSOCPerformanceTest")
    @Tag("performance")
    public void testDSOCPerformanceComparison() {
        System.out.println("\n=== DSOC Performance Comparison ===\n");
        
        // Test configurations
        int[] entityCounts = {1000, 10000, 50000};
        double[] occlusionRatios = {0.1, 0.5, 0.9};
        
        for (int entityCount : entityCounts) {
            for (double occlusionRatio : occlusionRatios) {
                System.out.printf("\nEntities: %d, Occlusion Ratio: %.1f\n", entityCount, occlusionRatio);
                System.out.println("----------------------------------------");
                
                // Test Octree
                testSpatialIndex("Octree", new Octree<>(idGenerator), entityCount, occlusionRatio);
                
                // Test Tetree
                testSpatialIndex("Tetree", new Tetree<>(idGenerator), entityCount, occlusionRatio);
            }
        }
    }
    
    private void testSpatialIndex(String name, com.hellblazer.luciferase.lucien.AbstractSpatialIndex<?, LongEntityID, String> index, 
                                  int entityCount, double occlusionRatio) {
        // Setup camera
        Point3f cameraPos = new Point3f(500, 500, 100);
        Point3f lookAt = new Point3f(500, 500, 500);
        Vector3f up = new Vector3f(0, 1, 0);
        
        float[] viewMatrix = createLookAtMatrix(cameraPos, lookAt, up);
        float[] projMatrix = createPerspectiveMatrix(60, 1.0f, 1.0f, 2000.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPos, lookAt, up,
            (float)Math.toRadians(60), 1.0f, 1.0f, 2000.0f
        );
        
        // Populate entities
        populateScene(index, entityCount, occlusionRatio);
        
        // Test without DSOC
        long withoutDSOC = testWithoutDSOC(index, frustum, cameraPos, 100);
        
        // Enable DSOC
        DSOCConfiguration config = DSOCConfiguration.defaultConfig()
            .withEnabled(true)
            .withEnableHierarchicalOcclusion(true)
            .withTBVStrategy(AdaptiveTBVStrategy.defaultStrategy());
            
        index.enableDSOC(config, 1024, 1024);
        index.updateCamera(viewMatrix, projMatrix, cameraPos);
        
        // Test with DSOC
        long withDSOC = testWithDSOC(index, frustum, cameraPos, 100);
        
        // Get statistics
        Map<String, Object> stats = index.getDSOCStatistics();
        
        // Print results
        System.out.printf("%s Results:\n", name);
        System.out.printf("  Without DSOC: %.2f ms/frame\n", withoutDSOC / 100.0);
        System.out.printf("  With DSOC:    %.2f ms/frame\n", withDSOC / 100.0);
        System.out.printf("  Speedup:      %.2fx\n", (double)withoutDSOC / withDSOC);
        System.out.printf("  Occlusion Rate: %.1f%%\n", 100.0 * (double)stats.get("entityOcclusionRate"));
        System.out.printf("  Active TBVs: %d\n", stats.get("activeTBVs"));
        System.out.printf("  TBV Hit Rate: %.1f%%\n", 100.0 * (double)stats.get("tbvHitRate"));
    }
    
    private void populateScene(com.hellblazer.luciferase.lucien.AbstractSpatialIndex<?, LongEntityID, String> index,
                               int entityCount, double occlusionRatio) {
        Random random = new Random(42);
        
        // Add regular entities
        for (int i = 0; i < entityCount; i++) {
            LongEntityID id = idGenerator.generateID();
            Point3f position = new Point3f(
                50 + random.nextFloat() * 900,
                50 + random.nextFloat() * 900,
                50 + random.nextFloat() * 900
            );
            index.insert(id, position, (byte)10, "Entity" + i);
        }
        
        // Add occluders
        int occluderCount = (int)(10 * occlusionRatio);
        for (int i = 0; i < occluderCount; i++) {
            LongEntityID id = idGenerator.generateID();
            float x = 200 + random.nextFloat() * 600;
            float y = 200 + random.nextFloat() * 600;
            float z = 100 + i * 50;
            
            Point3f position = new Point3f(x, y, z);
            EntityBounds bounds = new EntityBounds(
                new Point3f(x - 50, y - 50, z - 10),
                new Point3f(x + 50, y + 50, z + 10)
            );
            
            index.insert(id, position, (byte)10, "Occluder" + i, bounds);
        }
    }
    
    private long testWithoutDSOC(com.hellblazer.luciferase.lucien.AbstractSpatialIndex<?, LongEntityID, String> index,
                                 Frustum3D frustum, Point3f cameraPos, int iterations) {
        // Warm-up
        for (int i = 0; i < 10; i++) {
            index.frustumCullVisible(frustum, cameraPos);
        }
        
        // Measure
        long start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            List<FrustumIntersection<LongEntityID, String>> visible = 
                index.frustumCullVisible(frustum, cameraPos);
        }
        return System.currentTimeMillis() - start;
    }
    
    private long testWithDSOC(com.hellblazer.luciferase.lucien.AbstractSpatialIndex<?, LongEntityID, String> index,
                             Frustum3D frustum, Point3f cameraPos, int iterations) {
        // Warm-up
        for (int i = 0; i < 10; i++) {
            index.nextFrame();
            index.frustumCullVisible(frustum, cameraPos);
        }
        
        // Measure
        long start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            index.nextFrame();
            List<FrustumIntersection<LongEntityID, String>> visible = 
                index.frustumCullVisible(frustum, cameraPos);
        }
        return System.currentTimeMillis() - start;
    }
    
    private float[] createLookAtMatrix(Point3f eye, Point3f center, Vector3f up) {
        Vector3f f = new Vector3f();
        f.sub(center, eye);
        f.normalize();
        
        Vector3f s = new Vector3f();
        s.cross(f, up);
        s.normalize();
        
        Vector3f u = new Vector3f();
        u.cross(s, f);
        
        float[] matrix = new float[16];
        matrix[0] = s.x;
        matrix[4] = s.y;
        matrix[8] = s.z;
        matrix[1] = u.x;
        matrix[5] = u.y;
        matrix[9] = u.z;
        matrix[2] = -f.x;
        matrix[6] = -f.y;
        matrix[10] = -f.z;
        matrix[12] = -s.dot(new Vector3f(eye));
        matrix[13] = -u.dot(new Vector3f(eye));
        matrix[14] = f.dot(new Vector3f(eye));
        matrix[15] = 1.0f;
        
        return matrix;
    }
    
    private float[] createPerspectiveMatrix(float fovDegrees, float aspect, float near, float far) {
        float[] matrix = new float[16];
        float fov = (float)Math.toRadians(fovDegrees);
        float f = 1.0f / (float)Math.tan(fov / 2.0f);
        
        matrix[0] = f / aspect;
        matrix[5] = f;
        matrix[10] = (far + near) / (near - far);
        matrix[11] = -1.0f;
        matrix[14] = (2.0f * far * near) / (near - far);
        
        return matrix;
    }
    
    @Test
    @Disabled("Performance test - run manually with -Dtest=DSOCPerformanceTest")
    @Tag("performance")
    public void testDynamicScenePerformance() {
        System.out.println("\n=== Dynamic Scene Performance Test ===\n");
        
        Octree<LongEntityID, String> octree = new Octree<>(idGenerator);
        
        // Enable DSOC
        DSOCConfiguration config = DSOCConfiguration.defaultConfig()
            .withEnabled(true)
            .withTBVStrategy(AdaptiveTBVStrategy.defaultStrategy())
            .withUpdateCheckInterval(10);
            
        octree.enableDSOC(config, 1024, 1024);
        
        // Camera setup
        Point3f cameraPos = new Point3f(500, 500, 50);
        Point3f lookAt = new Point3f(500, 500, 500);
        Vector3f up = new Vector3f(0, 1, 0);
        
        float[] viewMatrix = createLookAtMatrix(cameraPos, lookAt, up);
        float[] projMatrix = createPerspectiveMatrix(60, 1.0f, 1.0f, 2000.0f);
        
        octree.updateCamera(viewMatrix, projMatrix, cameraPos);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPos, lookAt, up,
            (float)Math.toRadians(60), 1.0f, 1.0f, 2000.0f
        );
        
        // Create dynamic scene
        Random random = new Random(42);
        List<LongEntityID> dynamicEntities = new ArrayList<>();
        Map<LongEntityID, Vector3f> velocities = new HashMap<>();
        
        // Add static entities
        for (int i = 0; i < 5000; i++) {
            LongEntityID id = idGenerator.generateID();
            Point3f position = new Point3f(
                50 + random.nextFloat() * 900,
                50 + random.nextFloat() * 900,
                50 + random.nextFloat() * 900
            );
            octree.insert(id, position, (byte)10, "Static" + i);
        }
        
        // Add dynamic entities
        for (int i = 0; i < 1000; i++) {
            LongEntityID id = idGenerator.generateID();
            Point3f position = new Point3f(
                50 + random.nextFloat() * 900,
                50 + random.nextFloat() * 900,
                50 + random.nextFloat() * 900
            );
            octree.insert(id, position, (byte)10, "Dynamic" + i);
            
            dynamicEntities.add(id);
            velocities.put(id, new Vector3f(
                (random.nextFloat() - 0.5f) * 20,
                (random.nextFloat() - 0.5f) * 20,
                (random.nextFloat() - 0.5f) * 20
            ));
        }
        
        // Simulate frames
        System.out.println("Simulating 100 frames with dynamic entities...");
        
        long totalTime = 0;
        int visibleCount = 0;
        
        for (int frame = 0; frame < 100; frame++) {
            octree.nextFrame();
            
            // Update dynamic entities
            for (LongEntityID id : dynamicEntities) {
                Point3f currentPos = octree.getEntityPosition(id);
                Vector3f velocity = velocities.get(id);
                
                if (currentPos != null) {
                    Point3f newPos = new Point3f(
                        currentPos.x + velocity.x,
                        currentPos.y + velocity.y,
                        currentPos.z + velocity.z
                    );
                    
                    // Bounce off boundaries
                    if (newPos.x < 1 || newPos.x > 999) {
                        velocity.x = -velocity.x;
                        newPos.x = Math.max(1, Math.min(999, newPos.x));
                    }
                    if (newPos.y < 1 || newPos.y > 999) {
                        velocity.y = -velocity.y;
                        newPos.y = Math.max(1, Math.min(999, newPos.y));
                    }
                    if (newPos.z < 1 || newPos.z > 999) {
                        velocity.z = -velocity.z;
                        newPos.z = Math.max(1, Math.min(999, newPos.z));
                    }
                    
                    octree.updateEntity(id, newPos, (byte)10);
                }
            }
            
            // Perform frustum culling
            long start = System.nanoTime();
            List<FrustumIntersection<LongEntityID, String>> visible = 
                octree.frustumCullVisible(frustum, cameraPos);
            totalTime += System.nanoTime() - start;
            visibleCount += visible.size();
        }
        
        // Print results
        Map<String, Object> stats = octree.getDSOCStatistics();
        System.out.println("\nDynamic Scene Results:");
        System.out.printf("  Average frame time: %.2f ms\n", totalTime / 100_000_000.0);
        System.out.printf("  Average visible entities: %d\n", visibleCount / 100);
        System.out.printf("  Active TBVs: %d\n", stats.get("activeTBVs"));
        System.out.printf("  TBV Hit Rate: %.1f%%\n", 100.0 * (double)stats.get("tbvHitRate"));
        System.out.printf("  Entity Occlusion Rate: %.1f%%\n", 100.0 * (double)stats.get("entityOcclusionRate"));
    }
}