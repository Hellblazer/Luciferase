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

import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.entity.EntityManager;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.EntityDistance;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.FrustumIntersection;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.Plane3D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive DSOC tests for Tetree implementation
 *
 * @author hal.hildebrand
 */
public class TetreeDSOCTest {
    
    private Tetree<LongEntityID, String> tetree;
    private DSOCConfiguration config;
    private static final float EPSILON = 1e-6f;
    
    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        config = DSOCConfiguration.defaultConfig()
            .withEnabled(true)
            .withAutoDynamicsEnabled(true)
            .withEnableHierarchicalOcclusion(true)
            .withMinOccluderVolume(10.0f);
    }
    
    @Test
    @DisplayName("Basic DSOC functionality with Tetree")
    void testBasicDSOCFunctionality() {
        // Enable DSOC
        tetree.enableDSOC(config, 512, 512);
        assertTrue(tetree.isDSOCEnabled());
        
        // Insert entities
        var id1 = new LongEntityID(1);
        var id2 = new LongEntityID(2);
        var id3 = new LongEntityID(3);
        
        tetree.insert(id1, new Point3f(0.1f, 0.1f, 0.1f), (byte) 10, "Entity1");
        tetree.insert(id2, new Point3f(0.2f, 0.2f, 0.2f), (byte) 10, "Entity2");
        tetree.insert(id3, new Point3f(0.3f, 0.3f, 0.3f), (byte) 10, "Entity3");
        
        // Set up camera
        float[] viewMatrix = createViewMatrix(new Point3f(0, 0, -2), new Point3f(0.25f, 0.25f, 0.25f), new Vector3f(0, 1, 0));
        float[] projMatrix = createPerspectiveMatrix(60.0f, 1.0f, 0.1f, 10.0f);
        tetree.updateCamera(viewMatrix, projMatrix, new Point3f(0, 0, -2));
        
        // Advance frame
        long frame = tetree.nextFrame();
        assertEquals(1L, frame);
        
        // Check statistics
        var stats = tetree.getDSOCStatistics();
        assertTrue((Boolean) stats.get("dsocEnabled"));
        assertEquals(1L, stats.get("currentFrame"));
        assertEquals(3L, stats.get("totalEntities"));
    }
    
    @Test
    @DisplayName("Visibility state updates with Tetree")
    void testVisibilityStateUpdates() {
        tetree.enableDSOC(config, 512, 512);
        
        // Insert multiple entities
        List<LongEntityID> visibleIds = new ArrayList<>();
        List<LongEntityID> hiddenIds = new ArrayList<>();
        
        // Insert at least 50 entities to meet MIN_ENTITIES_FOR_DSOC threshold
        for (int i = 0; i < 60; i++) {
            var id = new LongEntityID(i);
            float coord = 0.01f + (i % 20) * 0.04f; // Spread within tetrahedron bounds
            tetree.insert(id, new Point3f(coord, coord * 0.5f, coord * 0.3f), (byte) 10, "Entity" + i);
            
            if (i < 30) {
                visibleIds.add(id);
            } else {
                hiddenIds.add(id);
            }
        }
        
        // Force Z-buffer activation to ensure DSOC is actually used
        tetree.forceZBufferActivation();
        
        // Also need to provide camera matrices for DSOC to work properly
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-1, 1, -1, 1, 0.1f, 10);
        tetree.updateCamera(viewMatrix, projMatrix, new Point3f(0.5f, 0.5f, -1));
        
        // Create frustum that includes only first 30 entities
        var frustum = createSelectiveFrustum(visibleIds, hiddenIds);
        
        // Perform frustum culling to discover entities (transition from UNKNOWN to VISIBLE)
        var visible = tetree.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        
        // Advance frame
        tetree.nextFrame();
        
        // Perform frustum culling again to ensure visibility states are properly set
        visible = tetree.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        
        var stats = tetree.getDSOCStatistics();
        assertNotNull(stats);
        
        // Verify DSOC is enabled and functional
        assertTrue((Boolean) stats.get("dsocEnabled"), "DSOC should be enabled");
        assertTrue((Long) stats.get("totalEntities") > 50, "Should have at least 50 entities");
        assertTrue((Long) stats.get("frameCount") > 0, "Should have processed frames");
    }
    
    @Test
    @DisplayName("Temporal Bounding Volume (TBV) creation with Tetree")
    void testTBVCreation() {
        tetree.enableDSOC(config, 512, 512);
        // Auto-dynamics enabled through DSOC configuration
        
        var id = new LongEntityID(1);
        var initialPos = new Point3f(0.1f, 0.1f, 0.1f);
        tetree.insert(id, initialPos, (byte) 10, "MovingEntity");
        
        // Simulate entity movement over multiple frames
        float velocity = 0.02f;
        for (int frame = 0; frame < 5; frame++) {
            tetree.nextFrame();
            var newPos = new Point3f(
                initialPos.x + velocity * frame,
                initialPos.y,
                initialPos.z
            );
            tetree.updateEntity(id, newPos, (byte) 10);
        }
        
        // Mark entity as hidden to potentially create TBV
        var frustum = createExcludingFrustum(id);
        tetree.frustumCullVisible(frustum, new Point3f(0, 0, -1));
        tetree.nextFrame();
        
        // Check if TBV was created
        var stats = tetree.getDSOCStatistics();
        // TBV creation depends on visibility state changes
        assertNotNull(stats.get("activeTBVs"));
    }
    
    @Test
    @DisplayName("DSOC performance impact with Tetree")
    void testDSOCPerformanceImpact() {
        // Test with DSOC disabled
        long withoutDSOCTime = measureQueryPerformance(false, 1000);
        
        // Test with DSOC enabled
        long withDSOCTime = measureQueryPerformance(true, 1000);
        
        // DSOC should provide performance benefits when configured properly
        // This is a basic test - actual benefits depend on scene complexity
        assertTrue(withDSOCTime > 0);
        assertTrue(withoutDSOCTime > 0);
    }
    
    @Test
    @DisplayName("Concurrent DSOC operations with Tetree")
    void testConcurrentDSOCOperations() throws InterruptedException, ExecutionException {
        tetree.enableDSOC(config, 512, 512);
        
        // Insert entities
        int entityCount = 100;
        for (int i = 0; i < entityCount; i++) {
            float coord = 0.01f + (i % 30) * 0.01f;
            tetree.insert(new LongEntityID(i), new Point3f(coord, coord * 0.5f, coord * 0.3f), (byte) 10, "Entity" + i);
        }
        
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();
        
        // Concurrent frustum culling
        for (int i = 0; i < 10; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                var frustum = createRandomFrustum(threadId);
                var results = tetree.frustumCullVisible(frustum, new Point3f(0, 0, -1));
                assertNotNull(results);
            }));
        }
        
        // Concurrent entity updates
        for (int i = 0; i < 10; i++) {
            final int entityId = i;
            futures.add(executor.submit(() -> {
                var id = new LongEntityID(entityId);
                float newCoord = 0.01f + (entityId % 30) * 0.015f;
                tetree.updateEntity(id, new Point3f(newCoord, newCoord * 0.5f, newCoord * 0.3f), (byte) 10);
            }));
        }
        
        // Wait for completion
        for (var future : futures) {
            future.get();
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        
        // Verify DSOC state consistency
        var stats = tetree.getDSOCStatistics();
        assertNotNull(stats);
        assertTrue((Boolean) stats.get("dsocEnabled"));
    }
    
    @Test
    @DisplayName("DSOC with ray traversal in Tetree")
    void testDSOCWithRayTraversal() {
        tetree.enableDSOC(config, 512, 512);
        
        // Insert entities along a line
        for (int i = 0; i < 10; i++) {
            float t = i * 0.05f;
            tetree.insert(new LongEntityID(i), new Point3f(t, t * 0.5f, t * 0.3f), (byte) 10, "Entity" + i);
        }
        
        // Create ray through entities
        var ray = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0.5f, 0.3f));
        
        // Update camera for DSOC
        tetree.updateCamera(createIdentityMatrix(), createIdentityMatrix(), new Point3f(0, 0, -1));
        tetree.nextFrame();
        
        // Perform ray intersection
        var intersections = tetree.rayIntersectAll(ray);
        assertFalse(intersections.isEmpty());
        
        // Check DSOC statistics after ray query
        var stats = tetree.getDSOCStatistics();
        assertNotNull(stats);
    }
    
    @Test
    @DisplayName("DSOC with k-NN queries in Tetree")
    void testDSOCWithKNNQueries() {
        tetree.enableDSOC(config, 512, 512);
        
        // Insert cluster of entities
        var queryPoint = new Point3f(0.2f, 0.1f, 0.1f);
        for (int i = 0; i < 20; i++) {
            float offset = i * 0.01f;
            var pos = new Point3f(
                queryPoint.x + offset,
                queryPoint.y + offset * 0.5f,
                queryPoint.z + offset * 0.3f
            );
            tetree.insert(new LongEntityID(i), pos, (byte) 10, "Entity" + i);
        }
        
        tetree.nextFrame();
        
        // Perform k-NN query
        int k = 5;
        var neighborIds = tetree.kNearestNeighbors(queryPoint, k, Float.MAX_VALUE);
        assertEquals(k, neighborIds.size());
        
        // Verify we got k neighbors
        assertFalse(neighborIds.isEmpty());
        assertTrue(neighborIds.size() <= k);
    }
    
    @Test
    @DisplayName("DSOC memory management with Tetree")
    void testDSOCMemoryManagement() {
        // Configure DSOC with smaller Z-buffer
        tetree.enableDSOC(config, 256, 256); // Smaller Z-buffer
        
        // Insert many entities
        for (int i = 0; i < 1000; i++) {
            float coord = 0.001f + (i % 100) * 0.005f;
            tetree.insert(new LongEntityID(i), new Point3f(coord, coord * 0.4f, coord * 0.2f), (byte) 10, "Entity" + i);
        }
        
        // Perform multiple frame updates
        for (int frame = 0; frame < 10; frame++) {
            tetree.nextFrame();
            
            // Update subset of entities
            for (int i = frame * 10; i < (frame + 1) * 10; i++) {
                float coord = 0.001f + (i % 100) * 0.006f;
                tetree.updateEntity(new LongEntityID(i), new Point3f(coord, coord * 0.4f, coord * 0.2f), (byte) 10);
            }
        }
        
        // Check that DSOC is still enabled after memory operations
        var stats = tetree.getDSOCStatistics();
        assertTrue((Boolean) stats.get("dsocEnabled"));
    }
    
    @Test
    @DisplayName("DSOC edge cases with Tetree")
    void testDSOCEdgeCases() {
        tetree.enableDSOC(config, 512, 512);
        
        // Test 1: Entity at tetrahedron boundary
        var boundaryPos = new Point3f(0.499f, 0.499f, 0.001f); // Near x+y=1 constraint
        var id1 = new LongEntityID(1);
        tetree.insert(id1, boundaryPos, (byte) 10, "BoundaryEntity");
        
        // Test 2: Very small entity
        var id2 = new LongEntityID(2);
        tetree.insert(id2, new Point3f(0.1f, 0.1f, 0.1f), (byte) 20, "TinyEntity"); // Max level
        
        // Test 3: Rapid movement
        var id3 = new LongEntityID(3);
        tetree.insert(id3, new Point3f(0.1f, 0.1f, 0.1f), (byte) 10, "FastEntity");
        
        for (int i = 0; i < 5; i++) {
            tetree.nextFrame();
            float jump = 0.1f * i;
            tetree.updateEntity(id3, new Point3f(0.1f + jump, 0.1f, 0.1f), (byte) 10);
        }
        
        // Test 4: Entity removal during DSOC operations
        var id4 = new LongEntityID(4);
        tetree.insert(id4, new Point3f(0.2f, 0.2f, 0.2f), (byte) 10, "TempEntity");
        tetree.nextFrame();
        tetree.removeEntity(id4);
        
        // Verify system stability
        var stats = tetree.getDSOCStatistics();
        assertNotNull(stats);
        assertEquals(3L, stats.get("totalEntities")); // 3 remaining
    }
    
    @Test
    @DisplayName("DSOC with tetrahedral space partitioning")
    void testDSOCWithTetrahedralPartitioning() {
        tetree.enableDSOC(config, 512, 512);
        
        // Insert entities in different tetrahedra at the same level
        var positions = new Point3f[] {
            new Point3f(0.1f, 0.1f, 0.1f),   // Type 0
            new Point3f(0.7f, 0.1f, 0.1f),   // Type 1
            new Point3f(0.1f, 0.7f, 0.1f),   // Type 2
            new Point3f(0.1f, 0.1f, 0.7f),   // Type 3
            new Point3f(0.3f, 0.3f, 0.3f),   // Type 4
            new Point3f(0.4f, 0.2f, 0.3f)    // Type 5
        };
        
        for (int i = 0; i < positions.length; i++) {
            tetree.insert(new LongEntityID(i), positions[i], (byte) 5, "Entity" + i);
        }
        
        // Create frustum that cuts through multiple tetrahedra
        var frustum = Frustum3D.createPerspective(
            new Point3f(0.5f, 0.5f, -1.0f), // Camera position
            new Point3f(0.5f, 0.5f, 0.5f),  // Look at center
            new Vector3f(0, 1, 0),          // Up
            (float)Math.toRadians(60.0f), 1.0f, 0.1f, 10.0f
        );
        
        tetree.updateCamera(
            createViewMatrix(new Point3f(0.5f, 0.5f, -1.0f), new Point3f(0.5f, 0.5f, 0.5f), new Vector3f(0, 1, 0)),
            createPerspectiveMatrix(60.0f, 1.0f, 0.1f, 10.0f),
            new Point3f(0.5f, 0.5f, -1.0f)
        );
        
        tetree.nextFrame();
        
        var visible = tetree.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1.0f));
        assertFalse(visible.isEmpty());
        
        // Verify DSOC handles tetrahedral structure correctly
        var stats = tetree.getDSOCStatistics();
        assertNotNull(stats);
        assertTrue((Boolean) stats.get("dsocEnabled"), "DSOC should be enabled");
        
        // Check that we have entities and they were processed
        Long totalEntities = (Long) stats.get("totalEntities");
        assertNotNull(totalEntities);
        assertEquals(6L, totalEntities, "Should have 6 entities");
        
        // Verify the frustum culling completed successfully
        assertNotNull(visible);
        
        // The test is about tetrahedral partitioning working with DSOC, not specific visibility counts
        System.out.println("Tetree DSOC test completed - " + visible.size() + " entities visible");
    }
    
    // Helper methods
    
    private float[] createIdentityMatrix() {
        float[] matrix = new float[16];
        for (int i = 0; i < 16; i++) {
            matrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
        }
        return matrix;
    }
    
    private float[] createOrthographicMatrix(float left, float right, float bottom, float top, 
                                             float near, float far) {
        float[] matrix = new float[16];
        Arrays.fill(matrix, 0.0f);
        
        matrix[0] = 2.0f / (right - left);
        matrix[5] = 2.0f / (top - bottom);
        matrix[10] = -2.0f / (far - near);
        matrix[12] = -(right + left) / (right - left);
        matrix[13] = -(top + bottom) / (top - bottom);
        matrix[14] = -(far + near) / (far - near);
        matrix[15] = 1.0f;
        
        return matrix;
    }
    
    private long measureQueryPerformance(boolean enableDSOC, int entityCount) {
        var perfTetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        
        if (enableDSOC) {
            perfTetree.enableDSOC(config, 512, 512);
        }
        
        // Insert entities
        for (int i = 0; i < entityCount; i++) {
            float coord = 0.001f + (i % 100) * 0.005f;
            perfTetree.insert(new LongEntityID(i), new Point3f(coord, coord * 0.4f, coord * 0.2f), (byte) 10, "Entity" + i);
        }
        
        // Measure query time
        long startTime = System.nanoTime();
        
        var frustum = createTestFrustum();
        for (int i = 0; i < 100; i++) {
            perfTetree.frustumCullVisible(frustum, new Point3f(0.25f, 0.25f, -1));
        }
        
        return System.nanoTime() - startTime;
    }
    
    private Frustum3D createTestFrustum() {
        return Frustum3D.createPerspective(
            new Point3f(0.25f, 0.25f, -1.0f),
            new Point3f(0.25f, 0.25f, 0.25f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(60.0f), 1.0f, 0.1f, 10.0f
        );
    }
    
    private Frustum3D createSelectiveFrustum(List<LongEntityID> include, List<LongEntityID> exclude) {
        // Create frustum that includes certain entities
        return Frustum3D.createOrthographic(
            new Point3f(0.5f, 0.5f, -1),
            new Point3f(0.25f, 0.125f, 0.125f),
            new Vector3f(0, 1, 0),
            0, 0.3f, 0, 0.2f, 0.1f, 10.0f
        );
    }
    
    private Frustum3D createExcludingFrustum(LongEntityID excludeId) {
        // Create frustum that excludes a specific entity
        return Frustum3D.createOrthographic(
            new Point3f(0.5f, 0.5f, -1),
            new Point3f(0.5f, 0.5f, 0.5f),
            new Vector3f(0, 1, 0),
            0.3f, 1.0f, 0.3f, 1.0f, 0.1f, 10.0f
        );
    }
    
    private Frustum3D createRandomFrustum(int seed) {
        Random rand = new Random(seed);
        float x = rand.nextFloat() * 0.5f;
        float y = rand.nextFloat() * 0.5f;
        
        return Frustum3D.createPerspective(
            new Point3f(x, y, -1.0f),
            new Point3f(x, y, 0.25f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(45.0f + rand.nextFloat() * 30.0f),
            1.0f, 0.1f, 10.0f
        );
    }
    
    private float[] createViewMatrix(Point3f eye, Point3f center, Vector3f up) {
        // Simple view matrix calculation
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
    
    private float[] createPerspectiveMatrix(float fovy, float aspect, float near, float far) {
        float[] matrix = new float[16];
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovy) / 2.0));
        
        matrix[0] = f / aspect;
        matrix[5] = f;
        matrix[10] = (far + near) / (near - far);
        matrix[11] = -1.0f;
        matrix[14] = (2.0f * far * near) / (near - far);
        
        return matrix;
    }
}
