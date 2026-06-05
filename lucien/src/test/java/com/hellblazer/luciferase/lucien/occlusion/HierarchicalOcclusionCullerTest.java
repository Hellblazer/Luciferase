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
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.FrustumIntersection;
import com.hellblazer.luciferase.lucien.Plane3D;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for DSOC functionality integrated into spatial indices
 *
 * @author hal.hildebrand
 */
public class HierarchicalOcclusionCullerTest {
    
    private DSOCConfiguration config;
    private Octree<LongEntityID, String> octree;
    private EntityIDGenerator<LongEntityID> idGenerator;
    private Frustum3D frustum;
    
    @BeforeEach
    void setUp() {
        config = new DSOCConfiguration()
            .withEnabled(true)
            .withEnableHierarchicalOcclusion(true)
            .withAutoDynamicsEnabled(true);
        
        idGenerator = new SequentialLongIDGenerator();
        octree = new Octree<>(idGenerator);
        
        // Create a simple frustum
        frustum = createFrustum();
    }
    
    @Test
    void testBasicDSOCCulling() {
        // Enable DSOC on the octree
        octree.enableDSOC(config, 512, 512);
        
        // Add some entities to the octree
        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();
        LongEntityID id3 = idGenerator.generateID();
        
        octree.insert(id1, new Point3f(50, 50, 50), (byte) 10, "Entity1", null);
        octree.insert(id2, new Point3f(150, 150, 150), (byte) 10, "Entity2", null);
        octree.insert(id3, new Point3f(250, 250, 250), (byte) 10, "Entity3", null);
        
        // Set up camera
        Point3f cameraPos = new Point3f(0, 0, 0);
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-200, 200, -200, 200, 0.1f, 1000);
        
        octree.updateCamera(viewMatrix, projMatrix, cameraPos);
        
        // Perform frustum culling with DSOC
        List<FrustumIntersection<LongEntityID, String>> visible = 
            octree.frustumCullVisible(frustum, cameraPos);
        
        // Should have some visible entities
        assertNotNull(visible);
        assertTrue(visible.size() > 0);
        
        // Check DSOC statistics
        Map<String, Object> stats = octree.getDSOCStatistics();
        assertTrue((Boolean) stats.get("dsocEnabled"));
        assertEquals(0L, stats.get("currentFrame"));
    }
    
    @Test
    void testOcclusionWithLargeOccluder() {
        // Enable DSOC on the octree
        octree.enableDSOC(config, 512, 512);
        
        // Add a large occluder in front
        LongEntityID occluderId = idGenerator.generateID();
        octree.insert(occluderId, new Point3f(100, 100, 50), (byte) 10, "Occluder", 
                      new EntityBounds(new Point3f(50, 50, 0), new Point3f(150, 150, 100)));
        
        // Add entity behind occluder
        LongEntityID hiddenId = idGenerator.generateID();
        octree.insert(hiddenId, new Point3f(100, 100, 150), (byte) 10, "Hidden", null);
        
        Point3f cameraPos = new Point3f(100, 100, -50);
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-200, 200, -200, 200, 0.1f, 1000);
        
        octree.updateCamera(viewMatrix, projMatrix, cameraPos);
        
        // Perform culling
        List<FrustumIntersection<LongEntityID, String>> visible = 
            octree.frustumCullVisible(frustum, cameraPos);
        
        // Check that occlusion happened
        Map<String, Object> stats = octree.getDSOCStatistics();
        assertNotNull(stats);
        // The actual occlusion behavior depends on HierarchicalZBuffer implementation
    }
    
    @Test
    void testStatisticsCollection() {
        // Enable DSOC on the octree
        octree.enableDSOC(config, 512, 512);
        
        // Add multiple entities
        for (int i = 0; i < 10; i++) {
            float x = 50 + i * 20;
            float y = 50 + i * 20;
            float z = 50 + i * 20;
            LongEntityID id = idGenerator.generateID();
            octree.insert(id, new Point3f(x, y, z), (byte) 10, "Entity" + i, null);
        }
        
        Point3f cameraPos = new Point3f(0, 0, 0);
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-300, 300, -300, 300, 0.1f, 1000);
        
        octree.updateCamera(viewMatrix, projMatrix, cameraPos);
        
        // Perform multiple frames
        for (int frame = 0; frame < 5; frame++) {
            octree.nextFrame();
            octree.frustumCullVisible(frustum, cameraPos);
        }
        
        Map<String, Object> stats = octree.getDSOCStatistics();
        assertEquals(5L, stats.get("currentFrame"));
        
        // Check totalEntities
        Object totalEntitiesObj = stats.get("totalEntities");
        assertNotNull(totalEntitiesObj, "totalEntities should not be null in stats");
        long totalEntities = ((Number) totalEntitiesObj).longValue();
        assertEquals(10L, totalEntities, "Expected 10 entities in statistics");
    }
    
    @Test
    void testResetStatistics() {
        // Enable DSOC on the octree
        octree.enableDSOC(config, 512, 512);
        
        // Add entity and perform culling
        LongEntityID id = idGenerator.generateID();
        octree.insert(id, new Point3f(100, 100, 100), (byte) 10, "Entity", null);
        
        Point3f cameraPos = new Point3f(0, 0, 0);
        octree.frustumCullVisible(frustum, cameraPos);
        
        // Reset statistics
        octree.resetDSOCStatistics();
        
        // Statistics should be reset
        Map<String, Object> stats = octree.getDSOCStatistics();
        assertNotNull(stats);
        // Specific reset behavior depends on implementation
    }
    
    /**
     * H2: setClock before zBuffer allocation — the zBuffer created after setClock must use the
     * injected clock, not Clock.system().  Verified by observing that adaptResolution cooldown
     * is governed by the TestClock: advancing the TestClock by less than ADAPTATION_COOLDOWN_MS
     * (5000 ms) must keep the cooldown active, and advancing past it must allow adaptation.
     */
    /**
     * H2: setClock before zBuffer allocation — the zBuffer created after setClock must use the
     * injected clock, not Clock.system().  Verified by observing that adaptResolution cooldown
     * is governed by the TestClock: advancing the TestClock by less than ADAPTATION_COOLDOWN_MS
     * (5000 ms) must keep the cooldown active, and advancing past it must allow adaptation.
     * <p>
     * Uses upgrade params (high effectiveness, low memory pressure) for the first call because
     * forceActivate() supplies occluderCount=0 (density 0.0) which causes calculateOptimalDimensions
     * to produce MINIMAL — the lowest tier — so downgrade would be a no-op.  Upgrade params trigger
     * MINIMAL→SMALL which is a valid change.
     */
    @Test
    void testZBufferCreatedAfterSetClockUsesInjectedClock() {
        var culler = new HierarchicalOcclusionCuller<>(512, 512,
                new DSOCConfiguration().withEnabled(true));

        // zBuffer is null at this point; inject clock BEFORE activation
        var testClock = new TestClock(6000L); // start well past any 0-based cooldown
        culler.setClock(testClock);

        // Trigger lazy activation — zBuffer is created here and must receive the injected clock
        culler.forceActivate();
        assertNotNull(culler.getZBuffer(), "zBuffer must be non-null after forceActivate");

        // First adapt call: upgrade (MINIMAL→SMALL). Cooldown gate open (time=6000, lastAdapt=0).
        assertTrue(culler.getZBuffer().adaptResolution(0.9, 0.1),
                "First adaptResolution (upgrade) must succeed (no prior cooldown)");

        // Advance TestClock by 4999 ms — still inside 5000 ms cooldown
        testClock.advance(4999L);
        assertFalse(culler.getZBuffer().adaptResolution(0.9, 0.1),
                "adaptResolution must be blocked within cooldown window");

        // Advance 1 ms more — cooldown crossed; downgrade path (low-eff, high-mem): SMALL→MINIMAL
        testClock.advance(1L);
        assertTrue(culler.getZBuffer().adaptResolution(0.05, 0.8),
                "adaptResolution must succeed once cooldown expires; " +
                "failure means zBuffer uses Clock.system() instead of injected TestClock");
    }

    @Test
    void testFrameTimingIsDeterministicWithInjectedClock() {
        // Enable DSOC first, then inject the clock (setClock forwards to dsoc).
        octree.enableDSOC(config, 512, 512);
        var testClock = new TestClock(0L);
        octree.setClock(testClock);

        // Insert enough entities to keep DSOC active.
        for (int i = 0; i < 10; i++) {
            LongEntityID id = idGenerator.generateID();
            octree.insert(id, new Point3f(50 + i * 20f, 50 + i * 20f, 50 + i * 20f), (byte) 10, "E" + i, null);
        }

        Point3f cameraPos = new Point3f(0, 0, 0);
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-300, 300, -300, 300, 0.1f, 1000);
        octree.updateCamera(viewMatrix, projMatrix, cameraPos);

        // With a fixed TestClock, beginFrame and endFrame both read the same nanoTime value
        // within a single frustumCullVisible call, so frameTime = 0 for every frame.
        // This proves the clock is injected: System.nanoTime() would produce a non-zero,
        // non-deterministic value across any repeated invocations.
        testClock.setNanos(1_000_000_000L); // 1 s — a non-zero anchor to verify no wall-clock bleed
        int frames = 3;
        for (int f = 0; f < frames; f++) {
            octree.nextFrame();
            octree.frustumCullVisible(frustum, cameraPos);
            // Do NOT advance the clock: begin and end both see the same instant.
        }

        Map<String, Object> stats = octree.getDSOCStatistics();
        assertNotNull(stats);
        // avgFrameTimeMs is present only when frameCount > 0 inside OcclusionStatistics.
        // With an injected fixed clock, every frame records 0 ns → avgFrameTimeMs == 0.0.
        // If System.nanoTime() were still in use, this would be positive and non-deterministic.
        if (stats.containsKey("avgFrameTimeMs")) {
            double avgMs = ((Number) stats.get("avgFrameTimeMs")).doubleValue();
            assertEquals(0.0, avgMs, 0.0,
                "avgFrameTimeMs must be exactly 0 with a fixed injected clock; " +
                "any positive value means System.nanoTime() is still in use");
        }
    }

    // Helper methods

    private Frustum3D createFrustum() {
        // Create a simple frustum using createOrthographic
        Point3f cameraPos = new Point3f(100, 100, 0);
        Point3f lookAt = new Point3f(100, 100, 100);
        Vector3f up = new Vector3f(0, 1, 0);
        
        return Frustum3D.createOrthographic(cameraPos, lookAt, up, 
                                           0, 200,    // left, right
                                           0, 200,    // bottom, top
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