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
package com.hellblazer.luciferase.portal.esvo.ui;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.portal.esvo.bridge.ESVOBridge;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OctreeStructureDiagram.
 *
 * @author hal.hildebrand
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
public class OctreeStructureDiagramTest {
    
    @BeforeAll
    static void initToolkit() {
        // Skip JavaFX initialization in CI (xvfb may hang on JFXPanel initialization)
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX initialization in CI environment");
            return;
        }
        // Initialize JavaFX toolkit
        new JFXPanel();
    }
    
    @Test
    void testCreation() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            try {
                // Create diagram
                OctreeStructureDiagram diagram = new OctreeStructureDiagram();
                assertNotNull(diagram);
                latch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Diagram creation timed out");
    }
    
    @Test
    void testSetOctreeData() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            try {
                // Create simple octree with a few voxels
                ESVOBridge bridge = new ESVOBridge();
                List<Point3i> voxels = new ArrayList<>();
                voxels.add(new Point3i(32, 32, 32));
                voxels.add(new Point3i(33, 32, 32));
                voxels.add(new Point3i(32, 33, 32));
                
                ESVOOctreeData octree = bridge.buildOctree(voxels, 5);
                
                // Create diagram and set data
                OctreeStructureDiagram diagram = new OctreeStructureDiagram();
                diagram.setOctreeData(octree, 5);
                
                assertNotNull(diagram);
                latch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Set octree data timed out");
    }
    
    @Test
    void testHighlightNodes() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            try {
                // Create simple octree
                ESVOBridge bridge = new ESVOBridge();
                List<Point3i> voxels = new ArrayList<>();
                voxels.add(new Point3i(32, 32, 32));
                
                ESVOOctreeData octree = bridge.buildOctree(voxels, 5);
                
                // Create diagram
                OctreeStructureDiagram diagram = new OctreeStructureDiagram();
                diagram.setOctreeData(octree, 5);
                
                // Highlight some nodes
                Set<Integer> nodesToHighlight = Set.of(0, 1, 2);
                diagram.highlightNodes(nodesToHighlight);
                
                // Clear highlights
                diagram.clearHighlights();
                
                assertNotNull(diagram);
                latch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Highlight nodes test timed out");
    }
}
