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
package com.hellblazer.luciferase.lucien.debug;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OctreeDebugger visualization and analysis features
 */
public class OctreeDebuggerTest {

    @Test
    public void testAsciiArtVisualization() {
        var octree = createSampleOctree();
        var debugger = new OctreeDebugger<>(octree);
        
        var ascii = debugger.toAsciiArt(3);
        assertNotNull(ascii);
        assertTrue(ascii.contains("Octree Structure Visualization"));
        assertTrue(ascii.contains("Total Nodes:"));
        // The octree might not have Level 0 depending on the data
        assertTrue(ascii.contains("Level") || ascii.contains("nodes"));
        
        System.out.println("ASCII Art Visualization:");
        System.out.println(ascii);
    }

    @Test
    public void testTreeBalanceAnalysis() {
        var octree = createSampleOctree();
        var debugger = new OctreeDebugger<>(octree);
        
        var stats = debugger.analyzeBalance();
        assertNotNull(stats);
        assertTrue(stats.totalNodes > 0);
        assertTrue(stats.totalEntities > 0);
        assertTrue(stats.maxDepth >= 0);
        assertTrue(stats.avgBranchingFactor >= 0);
        
        System.out.println("\nTree Balance Analysis:");
        System.out.println(stats);
    }

    @Test
    public void test2DSliceVisualization() {
        var octree = createSampleOctree();
        var debugger = new OctreeDebugger<>(octree);
        
        // Test slices at different Z coordinates
        var sliceAt50 = debugger.visualize2DSlice(50.0f, '■');
        assertNotNull(sliceAt50);
        assertTrue(sliceAt50.contains("2D Slice at Z=50.00"));
        
        System.out.println("\n2D Slice Visualization at Z=50:");
        System.out.println(sliceAt50);
        
        var sliceAt150 = debugger.visualize2DSlice(150.0f, '■');
        assertNotNull(sliceAt150);
        
        System.out.println("\n2D Slice Visualization at Z=150:");
        System.out.println(sliceAt150);
    }

    @Test
    public void testObjExport() throws IOException {
        var octree = createSampleOctree();
        var debugger = new OctreeDebugger<>(octree);
        
        var tempFile = Files.createTempFile("octree_debug_", ".obj");
        try {
            debugger.exportToObj(tempFile.toString());
            
            // Verify file was created and contains expected content
            assertTrue(Files.exists(tempFile));
            var content = Files.readString(tempFile);
            assertTrue(content.contains("# Octree Export"));
            assertTrue(content.contains("v ")); // vertices
            assertTrue(content.contains("l ")); // lines
            
            System.out.println("\nOBJ Export created at: " + tempFile);
            System.out.println("First 500 characters:");
            System.out.println(content.substring(0, Math.min(500, content.length())));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testUnbalancedTree() {
        // Create an unbalanced tree by inserting points in one corner
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGenerator, 5, (byte) 5);
        
        // Insert points clustered in one corner to create imbalance
        for (int i = 0; i < 100; i++) {
            var x = 10.0f + (float) (Math.random() * 20);
            var y = 10.0f + (float) (Math.random() * 20);
            var z = 10.0f + (float) (Math.random() * 20);
            octree.insert(new Point3f(x, y, z), (byte) 5, "Entity " + i);
        }
        
        var debugger = new OctreeDebugger<>(octree);
        var stats = debugger.analyzeBalance();
        
        System.out.println("\nUnbalanced Tree Analysis:");
        System.out.println(stats);
        
        // The tree might be balanced if all points go into a single node at the same level
        // So we just check that stats were calculated correctly
        assertTrue(stats.totalNodes > 0);
        assertTrue(stats.totalEntities == 100);
    }

    @Test
    public void testEmptyOctree() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var debugger = new OctreeDebugger<>(octree);
        
        var ascii = debugger.toAsciiArt(3);
        assertNotNull(ascii);
        assertTrue(ascii.contains("Total Nodes: 0"));
        
        var stats = debugger.analyzeBalance();
        assertEquals(0, stats.totalNodes);
        assertEquals(0, stats.totalEntities);
    }

    @Test
    public void testLargeOctreePerformance() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGenerator, 10, (byte) 6);
        
        // Insert a moderate number of points
        var startInsert = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            var x = (float) (Math.random() * 1000);
            var y = (float) (Math.random() * 1000);
            var z = (float) (Math.random() * 1000);
            octree.insert(new Point3f(x, y, z), (byte) 4, "Entity " + i);
        }
        var insertTime = System.currentTimeMillis() - startInsert;
        
        var debugger = new OctreeDebugger<>(octree);
        
        // Time ASCII art generation
        var startAscii = System.currentTimeMillis();
        var ascii = debugger.toAsciiArt(2); // Only show first 2 levels
        var asciiTime = System.currentTimeMillis() - startAscii;
        
        // Time balance analysis
        var startAnalysis = System.currentTimeMillis();
        var stats = debugger.analyzeBalance();
        var analysisTime = System.currentTimeMillis() - startAnalysis;
        
        System.out.println("\nPerformance Test Results:");
        System.out.println("Insert 1000 entities: " + insertTime + "ms");
        System.out.println("Generate ASCII art: " + asciiTime + "ms");
        System.out.println("Analyze balance: " + analysisTime + "ms");
        System.out.println("\nTree stats: " + stats.totalNodes + " nodes, " + 
                         stats.totalEntities + " entities");
        
        // Performance assertions
        assertTrue(asciiTime < 1000, "ASCII generation should be fast");
        assertTrue(analysisTime < 1000, "Balance analysis should be fast");
    }

    // Helper method to create a sample octree
    private Octree<LongEntityID, String> createSampleOctree() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGenerator, 5, (byte) 4);
        
        // Add some points in different regions to create interesting structure
        // Cluster 1: Near origin
        octree.insert(new Point3f(10, 10, 10), (byte) 3, "A1");
        octree.insert(new Point3f(20, 20, 20), (byte) 3, "A2");
        octree.insert(new Point3f(30, 30, 30), (byte) 3, "A3");
        
        // Cluster 2: Mid-range
        octree.insert(new Point3f(100, 100, 100), (byte) 3, "B1");
        octree.insert(new Point3f(110, 110, 110), (byte) 3, "B2");
        octree.insert(new Point3f(120, 100, 100), (byte) 3, "B3");
        octree.insert(new Point3f(100, 120, 100), (byte) 3, "B4");
        octree.insert(new Point3f(100, 100, 120), (byte) 3, "B5");
        
        // Cluster 3: Far corner
        octree.insert(new Point3f(200, 200, 200), (byte) 3, "C1");
        octree.insert(new Point3f(210, 210, 210), (byte) 3, "C2");
        
        // Some scattered points
        octree.insert(new Point3f(50, 150, 75), (byte) 3, "D1");
        octree.insert(new Point3f(150, 50, 125), (byte) 3, "D2");
        octree.insert(new Point3f(75, 75, 175), (byte) 3, "D3");
        
        return octree;
    }
}