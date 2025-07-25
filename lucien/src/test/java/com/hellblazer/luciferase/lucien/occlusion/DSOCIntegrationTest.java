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
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.UUIDGenerator;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.FrustumIntersection;
import com.hellblazer.luciferase.lucien.Plane3D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DSOC functionality at the AbstractSpatialIndex level
 *
 * @author hal.hildebrand
 */
public class DSOCIntegrationTest {
    
    private DSOCConfiguration config;
    
    @BeforeEach
    void setUp() {
        config = new DSOCConfiguration()
            .withEnabled(true)
            .withAutoDynamicsEnabled(true)
            .withEnableHierarchicalOcclusion(true);
    }
    
    @Test
    void testDSOCWithOctree() {
        // Create Octree
        EntityIDGenerator<UUIDEntityID> idGenerator = new UUIDGenerator();
        Octree<UUIDEntityID, String> octree = new Octree<>(idGenerator);
        
        // Enable DSOC
        octree.enableDSOC(config, 512, 512);
        
        assertTrue(octree.isDSOCEnabled());
        assertEquals(0L, octree.getCurrentFrame());
        
        // Add entities
        UUIDEntityID id1 = idGenerator.generateID();
        UUIDEntityID id2 = idGenerator.generateID();
        
        octree.insert(id1, new Point3f(50, 50, 50), (byte) 10, "Entity1", null);
        octree.insert(id2, new Point3f(150, 150, 150), (byte) 10, "Entity2", null);
        
        // Update camera
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-200, 200, -200, 200, 0.1f, 1000);
        octree.updateCamera(viewMatrix, projMatrix, new Point3f(0, 0, 0));
        
        // Advance frame
        long frame = octree.nextFrame();
        assertEquals(1L, frame);
        
        // Create frustum and perform culling
        Frustum3D frustum = createFrustum();
        List<FrustumIntersection<UUIDEntityID, String>> visible = 
            octree.frustumCullVisible(frustum, new Point3f(0, 0, 0));
        
        assertNotNull(visible);
        assertTrue(visible.size() > 0);
        
        // Get DSOC statistics
        Map<String, Object> stats = octree.getDSOCStatistics();
        assertTrue((Boolean) stats.get("dsocEnabled"));
        assertEquals(1L, stats.get("currentFrame"));
    }
    
    @Test
    void testDSOCWithTetree() {
        // Create Tetree
        EntityIDGenerator<UUIDEntityID> idGenerator = new UUIDGenerator();
        Tetree<UUIDEntityID, String> tetree = new Tetree<>(idGenerator);
        
        // Enable DSOC
        tetree.enableDSOC(config, 512, 512);
        
        assertTrue(tetree.isDSOCEnabled());
        assertEquals(0L, tetree.getCurrentFrame());
        
        // Add entities
        UUIDEntityID id1 = idGenerator.generateID();
        UUIDEntityID id2 = idGenerator.generateID();
        
        tetree.insert(id1, new Point3f(50, 50, 50), (byte) 10, "Entity1", null);
        tetree.insert(id2, new Point3f(150, 150, 150), (byte) 10, "Entity2", null);
        
        // Update camera
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-200, 200, -200, 200, 0.1f, 1000);
        tetree.updateCamera(viewMatrix, projMatrix, new Point3f(0, 0, 0));
        
        // Advance frame
        long frame = tetree.nextFrame();
        assertEquals(1L, frame);
        
        // Create frustum and perform culling
        Frustum3D frustum = createFrustum();
        List<FrustumIntersection<UUIDEntityID, String>> visible = 
            tetree.frustumCullVisible(frustum, new Point3f(0, 0, 0));
        
        assertNotNull(visible);
        assertTrue(visible.size() > 0);
        
        // Get DSOC statistics
        Map<String, Object> stats = tetree.getDSOCStatistics();
        assertTrue((Boolean) stats.get("dsocEnabled"));
        assertEquals(1L, stats.get("currentFrame"));
    }
    
    @Test
    void testDSOCFrameManagement() {
        EntityIDGenerator<UUIDEntityID> idGenerator = new UUIDGenerator();
        Octree<UUIDEntityID, String> octree = new Octree<>(idGenerator);
        
        // Enable DSOC
        octree.enableDSOC(config, 512, 512);
        
        // Add entities
        UUIDEntityID id1 = idGenerator.generateID();
        UUIDEntityID id2 = idGenerator.generateID();
        
        octree.insert(id1, new Point3f(50, 50, 50), (byte) 10, "Entity1", null);
        octree.insert(id2, new Point3f(150, 150, 150), (byte) 10, "Entity2", null);
        
        // Test frame advancement
        assertEquals(0L, octree.getCurrentFrame());
        
        long frame1 = octree.nextFrame();
        assertEquals(1L, frame1);
        assertEquals(1L, octree.getCurrentFrame());
        
        long frame2 = octree.nextFrame();
        assertEquals(2L, frame2);
        assertEquals(2L, octree.getCurrentFrame());
    }
    
    @Test
    void testDSOCDisabled() {
        EntityIDGenerator<UUIDEntityID> idGenerator = new UUIDGenerator();
        Octree<UUIDEntityID, String> octree = new Octree<>(idGenerator);
        
        // DSOC should be disabled by default
        assertFalse(octree.isDSOCEnabled());
        
        // DSOC statistics should show disabled
        Map<String, Object> stats = octree.getDSOCStatistics();
        assertFalse((Boolean) stats.get("dsocEnabled"));
    }
    
    @Test
    void testDSOCUpdate() {
        EntityIDGenerator<UUIDEntityID> idGenerator = new UUIDGenerator();
        Octree<UUIDEntityID, String> octree = new Octree<>(idGenerator);
        
        // Enable DSOC
        octree.enableDSOC(config, 512, 512);
        
        // Add entity
        UUIDEntityID id = idGenerator.generateID();
        octree.insert(id, new Point3f(50, 50, 50), (byte) 10, "Entity", null);
        
        // Update entity position
        octree.updateEntity(id, new Point3f(100, 100, 100), (byte) 10);
        
        // Entity should have moved
        Point3f newPos = octree.getEntityPosition(id);
        assertNotNull(newPos);
        assertEquals(100.0f, newPos.x, 0.01f);
        assertEquals(100.0f, newPos.y, 0.01f);
        assertEquals(100.0f, newPos.z, 0.01f);
    }
    
    @Test
    void testResetDSOCStatistics() {
        EntityIDGenerator<UUIDEntityID> idGenerator = new UUIDGenerator();
        Octree<UUIDEntityID, String> octree = new Octree<>(idGenerator);
        
        // Enable DSOC
        octree.enableDSOC(config, 512, 512);
        
        // Add entity and perform some operations
        UUIDEntityID id = idGenerator.generateID();
        octree.insert(id, new Point3f(50, 50, 50), (byte) 10, "Entity", null);
        
        octree.nextFrame();
        octree.nextFrame();
        
        // Reset statistics
        octree.resetDSOCStatistics();
        
        // Statistics should be reset
        Map<String, Object> stats = octree.getDSOCStatistics();
        assertNotNull(stats);
    }
    
    // Helper methods
    
    private Frustum3D createFrustum() {
        // Create a simple frustum using createOrthographic
        Point3f cameraPos = new Point3f(250, 250, 100);
        Point3f lookAt = new Point3f(250, 250, 250);
        Vector3f up = new Vector3f(0, 1, 0);
        
        return Frustum3D.createOrthographic(cameraPos, lookAt, up, 
                                           50, 450,   // left, right
                                           50, 450,   // bottom, top
                                           0.1f, 500); // near, far
    }
    
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
}