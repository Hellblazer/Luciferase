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
package com.hellblazer.luciferase.portal.esvo.renderer;

import com.hellblazer.luciferase.geometry.Point3i;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Group;
import javafx.scene.shape.Box;
import javafx.scene.shape.DrawMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VoxelRenderer.
 * 
 * @author hal.hildebrand
 */
class VoxelRendererTest {
    
    @BeforeAll
    static void initJavaFX() throws InterruptedException {
        // Initialize JavaFX toolkit (or use existing initialization)
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(() -> latch.countDown());
            assertTrue(latch.await(5, TimeUnit.SECONDS), "JavaFX platform failed to start");
        } catch (IllegalStateException e) {
            // JavaFX toolkit already initialized by another test
            // This is fine - just continue with tests
        }
    }
    
    @Test
    void testBuilderDefaults() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var holder = new Object[1];
        
        Platform.runLater(() -> {
            try {
                var renderer = VoxelRenderer.builder().build();
                assertNotNull(renderer);
                holder[0] = renderer;
            } finally {
                latch.countDown();
            }
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(holder[0]);
    }
    
    @Test
    void testBuilderWithCustomSettings() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var holder = new Object[1];
        
        Platform.runLater(() -> {
            try {
                var renderer = VoxelRenderer.builder()
                    .voxelSize(2.0)
                    .renderMode(VoxelRenderer.RenderMode.WIREFRAME)
                    .materialScheme(VoxelRenderer.MaterialScheme.RAINBOW)
                    .build();
                assertNotNull(renderer);
                holder[0] = renderer;
            } finally {
                latch.countDown();
            }
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(holder[0]);
    }
    
    @Test
    void testRenderEmptyVoxelList() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var holder = new Object[1];
        
        Platform.runLater(() -> {
            try {
                var renderer = VoxelRenderer.builder().build();
                var voxels = new ArrayList<Point3i>();
                var result = renderer.render(voxels, 64);
                
                assertNotNull(result);
                assertTrue(result instanceof Group);
                assertEquals(0, ((Group) result).getChildren().size());
                holder[0] = result;
            } finally {
                latch.countDown();
            }
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(holder[0]);
    }
    
    @Test
    void testRenderSingleVoxel() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var holder = new Object[1];
        
        Platform.runLater(() -> {
            try {
                var renderer = VoxelRenderer.builder()
                    .voxelSize(1.0)
                    .renderMode(VoxelRenderer.RenderMode.FILLED)
                    .build();
                
                var voxels = new ArrayList<Point3i>();
                voxels.add(new Point3i(32, 32, 32));
                
                var result = renderer.render(voxels, 64);
                
                assertNotNull(result);
                assertTrue(result instanceof Group);
                assertEquals(1, ((Group) result).getChildren().size());
                
                var node = ((Group) result).getChildren().get(0);
                assertTrue(node instanceof Box);
                
                Box box = (Box) node;
                assertEquals(1.0, box.getWidth(), 0.001);
                assertEquals(1.0, box.getHeight(), 0.001);
                assertEquals(1.0, box.getDepth(), 0.001);
                assertEquals(DrawMode.FILL, box.getDrawMode());
                
                holder[0] = result;
            } finally {
                latch.countDown();
            }
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(holder[0]);
    }
    
    @Test
    void testRenderMultipleVoxels() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var holder = new Object[1];
        
        Platform.runLater(() -> {
            try {
                var renderer = VoxelRenderer.builder().build();
                
                var voxels = new ArrayList<Point3i>();
                voxels.add(new Point3i(30, 30, 30));
                voxels.add(new Point3i(32, 32, 32));
                voxels.add(new Point3i(34, 34, 34));
                
                var result = renderer.render(voxels, 64);
                
                assertNotNull(result);
                assertTrue(result instanceof Group);
                assertEquals(3, ((Group) result).getChildren().size());
                
                holder[0] = result;
            } finally {
                latch.countDown();
            }
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(holder[0]);
    }
    
    @Test
    void testWireframeRenderMode() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var holder = new Object[1];
        
        Platform.runLater(() -> {
            try {
                var renderer = VoxelRenderer.builder()
                    .renderMode(VoxelRenderer.RenderMode.WIREFRAME)
                    .build();
                
                var voxels = new ArrayList<Point3i>();
                voxels.add(new Point3i(32, 32, 32));
                
                var result = renderer.render(voxels, 64);
                var box = (Box) ((Group) result).getChildren().get(0);
                
                assertEquals(DrawMode.LINE, box.getDrawMode());
                holder[0] = result;
            } finally {
                latch.countDown();
            }
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(holder[0]);
    }
    
    @Test
    void testAllMaterialSchemes() throws Exception {
        for (var scheme : VoxelRenderer.MaterialScheme.values()) {
            CountDownLatch latch = new CountDownLatch(1);
            var holder = new Object[1];
            
            Platform.runLater(() -> {
                try {
                    var renderer = VoxelRenderer.builder()
                        .materialScheme(scheme)
                        .build();
                    
                    var voxels = new ArrayList<Point3i>();
                    voxels.add(new Point3i(32, 32, 32));
                    
                    var result = renderer.render(voxels, 64);
                    
                    assertNotNull(result, "Failed for scheme: " + scheme);
                    assertEquals(1, ((Group) result).getChildren().size());
                    
                    var box = (Box) ((Group) result).getChildren().get(0);
                    assertNotNull(box.getMaterial(), "Material is null for scheme: " + scheme);
                    
                    holder[0] = result;
                } finally {
                    latch.countDown();
                }
            });
            
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timeout for scheme: " + scheme);
            assertNotNull(holder[0]);
        }
    }
    
    @Test
    void testVoxelPositioning() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var holder = new Object[1];
        
        Platform.runLater(() -> {
            try {
                var renderer = VoxelRenderer.builder()
                    .voxelSize(2.0)
                    .build();
                
                var voxels = new ArrayList<Point3i>();
                voxels.add(new Point3i(10, 20, 30));
                
                var result = renderer.render(voxels, 64);
                var box = (Box) ((Group) result).getChildren().get(0);
                
                // Check positioning (normalized space centered at origin)
                // centerOffset = (64 * 2.0) / 2.0 = 64.0
                // Voxel at (10, 20, 30) with voxelSize=2.0 should be at:
                // x = 10 * 2.0 - 64.0 + 2.0/2.0 = 20 - 64 + 1 = -43
                // y = 20 * 2.0 - 64.0 + 2.0/2.0 = 40 - 64 + 1 = -23
                // z = 30 * 2.0 - 64.0 + 2.0/2.0 = 60 - 64 + 1 = -3
                assertEquals(-43.0, box.getTranslateX(), 0.001);
                assertEquals(-23.0, box.getTranslateY(), 0.001);
                assertEquals(-3.0, box.getTranslateZ(), 0.001);
                
                holder[0] = result;
            } finally {
                latch.countDown();
            }
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(holder[0]);
    }
    
    @Test
    void testLargeVoxelCount() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var holder = new Object[1];
        
        Platform.runLater(() -> {
            try {
                var renderer = VoxelRenderer.builder().build();
                
                // Create 100 voxels
                var voxels = new ArrayList<Point3i>();
                for (int i = 0; i < 10; i++) {
                    for (int j = 0; j < 10; j++) {
                        voxels.add(new Point3i(30 + i, 30 + j, 32));
                    }
                }
                
                var result = renderer.render(voxels, 64);
                
                assertNotNull(result);
                assertEquals(100, ((Group) result).getChildren().size());
                
                holder[0] = result;
            } finally {
                latch.countDown();
            }
        });
        
        assertTrue(latch.await(10, TimeUnit.SECONDS)); // Longer timeout for larger dataset
        assertNotNull(holder[0]);
    }
}
