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
package com.hellblazer.luciferase.lucien.benchmark;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.occlusion.*;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.FrustumIntersection;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import javax.vecmath.Matrix4f;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive performance benchmarks for DSOC (Dynamic Scene Occlusion Culling)
 *
 * @author hal.hildebrand
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
@Disabled("JMH benchmark - run manually")
@Tag("benchmark")
public class DSOCPerformanceBenchmark {
    
    // Test parameters
    @Param({"1000", "10000", "100000"})
    private int entityCount;
    
    @Param({"0.1", "0.3", "0.5", "0.7", "0.9"})
    private double occlusionRatio;
    
    @Param({"0.0", "0.2", "0.5"})
    private double dynamicEntityRatio;
    
    @Param({"512", "1024", "2048"})
    private int zBufferResolution;
    
    // Spatial indices
    private Octree<LongEntityID, String> octreeWithDSOC;
    private Octree<LongEntityID, String> octreeWithoutDSOC;
    private Tetree<LongEntityID, String> tetreeWithDSOC;
    private Tetree<LongEntityID, String> tetreeWithoutDSOC;
    
    // Entity tracking
    private List<LongEntityID> allEntityIds;
    private List<LongEntityID> dynamicEntityIds;
    private Map<LongEntityID, Point3f> entityVelocities;
    
    // Camera and frustum
    private float[] viewMatrix;
    private float[] projectionMatrix;
    private Point3f cameraPosition;
    private Frustum3D frustum;
    
    // Random for entity movement
    private Random random;
    
    @Setup(Level.Trial)
    public void setup() {
        random = new Random(42); // Fixed seed for reproducibility
        
        // Initialize ID generator
        EntityIDGenerator<LongEntityID> idGenerator = new SequentialLongIDGenerator();
        
        // Create spatial indices
        octreeWithDSOC = new Octree<>(idGenerator);
        octreeWithoutDSOC = new Octree<>(idGenerator);
        tetreeWithDSOC = new Tetree<>(idGenerator);
        tetreeWithoutDSOC = new Tetree<>(idGenerator);
        
        // Configure DSOC
        DSOCConfiguration config = DSOCConfiguration.defaultConfig()
            .withEnabled(true)
            .withEnableHierarchicalOcclusion(true)
            .withTBVStrategy(AdaptiveTBVStrategy.defaultStrategy())
            .withUpdateCheckInterval(10)
            .withMaxTBVsPerEntity(2);
            
        octreeWithDSOC.enableDSOC(config, zBufferResolution, zBufferResolution);
        tetreeWithDSOC.enableDSOC(config, zBufferResolution, zBufferResolution);
        
        // Setup camera
        setupCamera();
        
        // Populate entities
        populateEntities(idGenerator);
        
        // Setup occluders
        setupOccluders();
    }
    
    private void setupCamera() {
        // Camera at origin looking down positive Z
        cameraPosition = new Point3f(500, 500, -100);
        Point3f lookAt = new Point3f(500, 500, 500);
        Vector3f up = new Vector3f(0, 1, 0);
        
        // Create view matrix
        viewMatrix = createLookAtMatrix(cameraPosition, lookAt, up);
        
        // Create projection matrix (perspective)
        float fov = (float) Math.toRadians(60);
        float aspect = 1.0f;
        float near = 1.0f;
        float far = 2000.0f;
        projectionMatrix = createPerspectiveMatrix(fov, aspect, near, far);
        
        // Create frustum
        frustum = Frustum3D.createPerspective(cameraPosition, lookAt, up, fov, aspect, near, far);
        
        // Update camera for DSOC-enabled indices
        octreeWithDSOC.updateCamera(viewMatrix, projectionMatrix, cameraPosition);
        tetreeWithDSOC.updateCamera(viewMatrix, projectionMatrix, cameraPosition);
    }
    
    private void populateEntities(EntityIDGenerator<LongEntityID> idGenerator) {
        allEntityIds = new ArrayList<>();
        dynamicEntityIds = new ArrayList<>();
        entityVelocities = new HashMap<>();
        
        int dynamicCount = (int) (entityCount * dynamicEntityRatio);
        
        for (int i = 0; i < entityCount; i++) {
            LongEntityID id = idGenerator.generateID();
            allEntityIds.add(id);
            
            // Random position in 1000x1000x1000 cube
            Point3f position = new Point3f(
                random.nextFloat() * 1000,
                random.nextFloat() * 1000,
                random.nextFloat() * 1000
            );
            
            // Insert into all indices
            octreeWithDSOC.insert(id, position, (byte) 10, "Entity" + i);
            octreeWithoutDSOC.insert(id, position, (byte) 10, "Entity" + i);
            tetreeWithDSOC.insert(id, position, (byte) 10, "Entity" + i);
            tetreeWithoutDSOC.insert(id, position, (byte) 10, "Entity" + i);
            
            // Mark some as dynamic
            if (i < dynamicCount) {
                dynamicEntityIds.add(id);
                
                // Random velocity
                Vector3f velocity = new Vector3f(
                    (random.nextFloat() - 0.5f) * 10,
                    (random.nextFloat() - 0.5f) * 10,
                    (random.nextFloat() - 0.5f) * 10
                );
                entityVelocities.put(id, new Point3f(velocity));
            }
        }
    }
    
    private void setupOccluders() {
        // Create large occluding walls based on occlusion ratio
        int occluderCount = (int) (10 * occlusionRatio);
        
        EntityIDGenerator<LongEntityID> idGenerator = new SequentialLongIDGenerator();
        
        for (int i = 0; i < occluderCount; i++) {
            LongEntityID id = idGenerator.generateID();
            
            // Position occluders between camera and entities
            float x = 200 + random.nextFloat() * 600;
            float y = 200 + random.nextFloat() * 600;
            float z = 100 + i * 50; // Layer occluders in depth
            
            Point3f position = new Point3f(x, y, z);
            
            // Large bounds for occluders
            EntityBounds bounds = new EntityBounds(
                new Point3f(x - 50, y - 50, z - 10),
                new Point3f(x + 50, y + 50, z + 10)
            );
            
            // Insert occluders
            octreeWithDSOC.insert(id, position, (byte) 10, "Occluder" + i, bounds);
            octreeWithoutDSOC.insert(id, position, (byte) 10, "Occluder" + i, bounds);
            tetreeWithDSOC.insert(id, position, (byte) 10, "Occluder" + i, bounds);
            tetreeWithoutDSOC.insert(id, position, (byte) 10, "Occluder" + i, bounds);
        }
    }
    
    @Setup(Level.Invocation)
    public void updateDynamicEntities() {
        // Move dynamic entities
        for (LongEntityID id : dynamicEntityIds) {
            Point3f currentPos = octreeWithDSOC.getEntityPosition(id);
            Point3f velocity = entityVelocities.get(id);
            
            if (currentPos != null && velocity != null) {
                // Update position
                Point3f newPos = new Point3f(
                    currentPos.x + velocity.x,
                    currentPos.y + velocity.y,
                    currentPos.z + velocity.z
                );
                
                // Bounce off boundaries
                if (newPos.x < 0 || newPos.x > 1000) {
                    velocity.x = -velocity.x;
                    newPos.x = Math.max(0, Math.min(1000, newPos.x));
                }
                if (newPos.y < 0 || newPos.y > 1000) {
                    velocity.y = -velocity.y;
                    newPos.y = Math.max(0, Math.min(1000, newPos.y));
                }
                if (newPos.z < 0 || newPos.z > 1000) {
                    velocity.z = -velocity.z;
                    newPos.z = Math.max(0, Math.min(1000, newPos.z));
                }
                
                // Update all indices
                octreeWithDSOC.updateEntity(id, newPos, (byte) 10);
                octreeWithoutDSOC.updateEntity(id, newPos, (byte) 10);
                tetreeWithDSOC.updateEntity(id, newPos, (byte) 10);
                tetreeWithoutDSOC.updateEntity(id, newPos, (byte) 10);
            }
        }
        
        // Advance frame for DSOC
        octreeWithDSOC.nextFrame();
        tetreeWithDSOC.nextFrame();
    }
    
    // Benchmark methods
    
    @Benchmark
    public List<FrustumIntersection<LongEntityID, String>> benchmarkOctreeWithDSOC() {
        return octreeWithDSOC.frustumCullVisible(frustum, cameraPosition);
    }
    
    @Benchmark
    public List<FrustumIntersection<LongEntityID, String>> benchmarkOctreeWithoutDSOC() {
        return octreeWithoutDSOC.frustumCullVisible(frustum, cameraPosition);
    }
    
    @Benchmark
    public List<FrustumIntersection<LongEntityID, String>> benchmarkTetreeWithDSOC() {
        return tetreeWithDSOC.frustumCullVisible(frustum, cameraPosition);
    }
    
    @Benchmark
    public List<FrustumIntersection<LongEntityID, String>> benchmarkTetreeWithoutDSOC() {
        return tetreeWithoutDSOC.frustumCullVisible(frustum, cameraPosition);
    }
    
    @TearDown(Level.Trial)
    public void tearDown() {
        if (octreeWithDSOC.isDSOCEnabled()) {
            Map<String, Object> stats = octreeWithDSOC.getDSOCStatistics();
            System.out.println("\nOctree DSOC Statistics:");
            System.out.println("  Entities: " + entityCount);
            System.out.println("  Occlusion Ratio: " + occlusionRatio);
            System.out.println("  Dynamic Ratio: " + dynamicEntityRatio);
            System.out.println("  Entity Occlusion Rate: " + stats.get("entityOcclusionRate"));
            System.out.println("  Active TBVs: " + stats.get("activeTBVs"));
            System.out.println("  TBV Hit Rate: " + stats.get("tbvHitRate"));
        }
    }
    
    // Helper methods
    
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
    
    private float[] createPerspectiveMatrix(float fov, float aspect, float near, float far) {
        float[] matrix = new float[16];
        float f = 1.0f / (float) Math.tan(fov / 2.0f);
        
        matrix[0] = f / aspect;
        matrix[5] = f;
        matrix[10] = (far + near) / (near - far);
        matrix[11] = -1.0f;
        matrix[14] = (2.0f * far * near) / (near - far);
        
        return matrix;
    }
    
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(DSOCPerformanceBenchmark.class.getSimpleName())
            .forks(1)
            .build();
            
        new Runner(opt).run();
    }
}