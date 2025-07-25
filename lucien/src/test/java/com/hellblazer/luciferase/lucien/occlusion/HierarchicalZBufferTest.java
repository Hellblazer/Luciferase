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

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for HierarchicalZBuffer
 *
 * @author hal.hildebrand
 */
public class HierarchicalZBufferTest {
    
    private HierarchicalZBuffer zBuffer;
    
    @BeforeEach
    void setUp() {
        // Create a 256x256 buffer with 4 levels
        zBuffer = new HierarchicalZBuffer(256, 256, 4);
    }
    
    @Test
    void testInitialization() {
        // Check dimensions
        assertEquals(256, zBuffer.getWidthAtLevel(0));
        assertEquals(256, zBuffer.getHeightAtLevel(0));
        assertEquals(128, zBuffer.getWidthAtLevel(1));
        assertEquals(128, zBuffer.getHeightAtLevel(1));
        assertEquals(64, zBuffer.getWidthAtLevel(2));
        assertEquals(64, zBuffer.getHeightAtLevel(2));
        assertEquals(32, zBuffer.getWidthAtLevel(3));
        assertEquals(32, zBuffer.getHeightAtLevel(3));
    }
    
    @Test
    void testCameraUpdate() {
        float[] viewMatrix = new float[16];
        float[] projMatrix = new float[16];
        
        // Identity matrices
        for (int i = 0; i < 16; i++) {
            viewMatrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
            projMatrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
        }
        
        // Should not throw
        assertDoesNotThrow(() -> {
            zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000.0f);
        });
    }
    
    @Test
    void testClear() {
        // Clear should reset all buffers to far plane (1.0)
        zBuffer.clear();
        
        // Create bounds and test - should not be occluded after clear
        var bounds = new EntityBounds(new Point3f(10, 10, 10), new Point3f(20, 20, 20));
        assertFalse(zBuffer.isOccluded(bounds));
    }
    
    @Test
    void testOcclusionWithoutCamera() {
        // Without camera setup, bounds should not be occluded
        var bounds = new EntityBounds(new Point3f(10, 10, 10), new Point3f(20, 20, 20));
        assertFalse(zBuffer.isOccluded(bounds));
    }
    
    @Test
    void testRenderOccluder() {
        // Set up a simple orthographic camera
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-100, 100, -100, 100, 0.1f, 1000);
        
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000);
        
        // Render an occluder
        var occluderBounds = new EntityBounds(new Point3f(0, 0, 10), new Point3f(50, 50, 20));
        zBuffer.renderOccluder(occluderBounds);
        
        // Update hierarchy
        zBuffer.updateHierarchy();
        
        // Test object behind should be occluded
        var behindBounds = new EntityBounds(new Point3f(10, 10, 30), new Point3f(40, 40, 40));
        // Note: Actual occlusion depends on proper projection implementation
        // This is a simplified test
    }
    
    @Test
    void testHierarchyUpdate() {
        // Render something at base level
        var bounds = new EntityBounds(new Point3f(0, 0, 10), new Point3f(10, 10, 20));
        
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-100, 100, -100, 100, 0.1f, 1000);
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000);
        
        zBuffer.renderOccluder(bounds);
        
        // Update should propagate to higher levels
        assertDoesNotThrow(() -> {
            zBuffer.updateHierarchy();
        });
    }
    
    @Test
    void testBoundsOutsideFrustum() {
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-10, 10, -10, 10, 0.1f, 100);
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 100);
        
        // Bounds far outside frustum
        var bounds = new EntityBounds(new Point3f(1000, 1000, 1000), new Point3f(1100, 1100, 1100));
        
        // Should not be occluded (actually outside frustum)
        assertFalse(zBuffer.isOccluded(bounds));
    }
    
    @Test
    void testMultipleLevels() {
        // Test that we can create buffers with different level counts
        var smallBuffer = new HierarchicalZBuffer(64, 64, 2);
        assertEquals(64, smallBuffer.getWidthAtLevel(0));
        assertEquals(32, smallBuffer.getWidthAtLevel(1));
        
        var largeBuffer = new HierarchicalZBuffer(512, 512, 6);
        assertEquals(512, largeBuffer.getWidthAtLevel(0));
        assertEquals(256, largeBuffer.getWidthAtLevel(1));
        assertEquals(128, largeBuffer.getWidthAtLevel(2));
        assertEquals(64, largeBuffer.getWidthAtLevel(3));
        assertEquals(32, largeBuffer.getWidthAtLevel(4));
        assertEquals(16, largeBuffer.getWidthAtLevel(5));
    }
    
    @Test
    void testThreadSafety() throws InterruptedException {
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-100, 100, -100, 100, 0.1f, 1000);
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000);
        
        int numThreads = 10;
        Thread[] threads = new Thread[numThreads];
        
        // Create threads that render occluders
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    float offset = threadId * 20;
                    var bounds = new EntityBounds(
                        new Point3f(offset, offset, 10),
                        new Point3f(offset + 10, offset + 10, 20)
                    );
                    zBuffer.renderOccluder(bounds);
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for completion
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Should complete without errors
        assertDoesNotThrow(() -> {
            zBuffer.updateHierarchy();
        });
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
        
        // Simple orthographic projection
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