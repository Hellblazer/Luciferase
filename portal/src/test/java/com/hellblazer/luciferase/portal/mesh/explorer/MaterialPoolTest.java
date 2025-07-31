/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.mesh.explorer;

import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MaterialPool
 *
 * @author hal.hildebrand
 */
@RequiresJavaFX
public class MaterialPoolTest {
    
    private MaterialPool pool;
    
    @BeforeEach
    void setUp() {
        pool = new MaterialPool(10); // Small capacity for testing
    }
    
    @Test
    void testMaterialCreation() {
        // Create a material
        PhongMaterial material = pool.getMaterial(Color.RED, 0.5, false);
        
        assertNotNull(material);
        // Material pool may adjust color opacity
        assertEquals(Color.RED.getRed(), material.getDiffuseColor().getRed(), 0.01);
        assertEquals(Color.RED.getGreen(), material.getDiffuseColor().getGreen(), 0.01);
        assertEquals(Color.RED.getBlue(), material.getDiffuseColor().getBlue(), 0.01);
        assertEquals(1, pool.getPoolSize());
    }
    
    @Test
    void testMaterialReuse() {
        // Create same material twice
        PhongMaterial material1 = pool.getMaterial(Color.RED, 0.5, false);
        PhongMaterial material2 = pool.getMaterial(Color.RED, 0.5, false);
        
        // Should be the same instance
        assertSame(material1, material2);
        assertEquals(1, pool.getPoolSize());
    }
    
    @Test
    void testDifferentMaterials() {
        // Create different materials
        PhongMaterial red = pool.getMaterial(Color.RED, 1.0, false);
        PhongMaterial blue = pool.getMaterial(Color.BLUE, 1.0, false);
        PhongMaterial redTransparent = pool.getMaterial(Color.RED, 0.5, false);
        PhongMaterial redHighlight = pool.getMaterial(Color.RED, 1.0, true);
        
        // All should be different instances
        assertNotSame(red, blue);
        assertNotSame(red, redTransparent);
        assertNotSame(red, redHighlight);
        
        assertEquals(4, pool.getPoolSize());
    }
    
    @Test
    void testOpacityHandling() {
        // Test different opacity values
        PhongMaterial opaque = pool.getMaterial(Color.GREEN, 1.0, false);
        PhongMaterial semiTransparent = pool.getMaterial(Color.GREEN, 0.5, false);
        PhongMaterial transparent = pool.getMaterial(Color.GREEN, 0.1, false);
        
        assertNotSame(opaque, semiTransparent);
        assertNotSame(semiTransparent, transparent);
        assertEquals(3, pool.getPoolSize());
    }
    
    @Test
    void testStateFlags() {
        Color testColor = Color.YELLOW;
        
        // Test state and opacity combinations
        PhongMaterial normal = pool.getMaterial(testColor, 1.0, false);
        PhongMaterial selected = pool.getMaterial(testColor, 1.0, true);
        PhongMaterial transparent = pool.getMaterial(testColor, 0.5, false);
        PhongMaterial selectedTransparent = pool.getMaterial(testColor, 0.5, true);
        
        // All should be different
        assertNotSame(normal, selected);
        assertNotSame(normal, transparent);
        assertNotSame(normal, selectedTransparent);
        assertNotSame(selected, transparent);
        assertNotSame(selected, selectedTransparent);
        assertNotSame(transparent, selectedTransparent);
        
        assertEquals(4, pool.getPoolSize());
    }
    
    @Test
    void testLRUEviction() {
        // Create materials up to capacity
        List<Color> colors = List.of(
            Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN,
            Color.MAGENTA, Color.ORANGE, Color.PURPLE, Color.PINK, Color.BROWN
        );
        
        for (Color color : colors) {
            pool.getMaterial(color, 1.0, false);
        }
        
        assertEquals(10, pool.getPoolSize()); // At capacity
        
        // Add one more - should evict least recently used (RED)
        pool.getMaterial(Color.GRAY, 1.0, false);
        assertEquals(10, pool.getPoolSize()); // Still at capacity
        
        // Access RED again - should create new instance since it was evicted
        PhongMaterial redAgain = pool.getMaterial(Color.RED, 1.0, false);
        assertNotNull(redAgain);
        assertEquals(10, pool.getPoolSize()); // Still at capacity, something else evicted
    }
    
    @Test
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // Track all materials created
        List<List<PhongMaterial>> threadMaterials = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            threadMaterials.add(new ArrayList<>());
        }
        
        // Launch concurrent threads
        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            executor.submit(() -> {
                try {
                    List<PhongMaterial> materials = threadMaterials.get(threadIndex);
                    for (int i = 0; i < operationsPerThread; i++) {
                        // Each thread uses a different color pattern
                        Color color = Color.hsb(threadIndex * 36, 1.0, 1.0);
                        double opacity = (i % 10) / 10.0;
                        
                        PhongMaterial material = pool.getMaterial(color, opacity, false);
                        materials.add(material);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // Wait for completion
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Verify materials were created
        for (List<PhongMaterial> materials : threadMaterials) {
            assertEquals(operationsPerThread, materials.size());
            for (PhongMaterial material : materials) {
                assertNotNull(material);
            }
        }
        
        // Pool should have entries but limited by capacity
        assertTrue(pool.getPoolSize() > 0);
        assertTrue(pool.getPoolSize() <= 10);
    }
    
    @Test
    void testCacheClear() {
        // Create some materials
        pool.getMaterial(Color.RED, 1.0, false);
        pool.getMaterial(Color.GREEN, 1.0, false);
        pool.getMaterial(Color.BLUE, 1.0, false);
        
        assertEquals(3, pool.getPoolSize());
        
        // Clear the pool
        pool.clear();
        
        assertEquals(0, pool.getPoolSize());
    }
    
    @Test
    void testMaterialProperties() {
        // Test that material properties are set correctly
        Color testColor = Color.rgb(100, 150, 200);
        double opacity = 0.7;
        
        PhongMaterial material = pool.getMaterial(testColor, opacity, false);
        
        // Verify color is set (with opacity applied)
        assertEquals(testColor.getRed(), material.getDiffuseColor().getRed(), 0.01);
        assertEquals(testColor.getGreen(), material.getDiffuseColor().getGreen(), 0.01);
        assertEquals(testColor.getBlue(), material.getDiffuseColor().getBlue(), 0.01);
        
        // For selected material, verify it's different
        PhongMaterial selected = pool.getMaterial(testColor, opacity, true);
        assertNotSame(material, selected);
    }
    
    @Test
    void testKeyGeneration() {
        // Test that similar colors generate different keys
        Color color1 = Color.rgb(255, 0, 0);
        Color color2 = Color.rgb(254, 0, 0); // Slightly different
        
        PhongMaterial mat1 = pool.getMaterial(color1, 1.0, false);
        PhongMaterial mat2 = pool.getMaterial(color2, 1.0, false);
        
        // Should be different materials
        assertNotSame(mat1, mat2);
        assertEquals(2, pool.getPoolSize());
    }
}