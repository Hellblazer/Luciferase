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

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TetreeDebugger visualization and analysis features
 */
public class TetreeDebuggerTest {

    @Test
    public void testAsciiArtVisualization() {
        var tetree = createSampleTetree();
        var debugger = new TetreeDebugger<>(tetree);
        
        var ascii = debugger.toAsciiArt(3);
        assertNotNull(ascii);
        assertTrue(ascii.contains("Tetree Structure Visualization"));
        assertTrue(ascii.contains("Total Nodes:"));
        assertTrue(ascii.contains("Level") || ascii.contains("nodes"));
        
        System.out.println("Tetree ASCII Art Visualization:");
        System.out.println(ascii);
    }

    @Test
    public void testTreeBalanceAnalysis() {
        var tetree = createSampleTetree();
        var debugger = new TetreeDebugger<>(tetree);
        
        var stats = debugger.analyzeBalance();
        assertNotNull(stats);
        assertTrue(stats.totalNodes > 0);
        assertTrue(stats.totalEntities > 0);
        assertTrue(stats.maxDepth >= 0);
        assertTrue(stats.avgBranchingFactor >= 0);
        
        // Tetree-specific stats
        if (stats instanceof TetreeDebugger.TreeBalanceStats tetStats) {
            assertTrue(tetStats.tetTypeBalance >= 0.0);
            assertTrue(tetStats.tetTypeBalance <= 1.0);
        }
        
        System.out.println("\nTetree Balance Analysis:");
        System.out.println(stats);
    }

    @Test
    public void testTetTypeDistribution() {
        var tetree = createSampleTetree();
        var debugger = new TetreeDebugger<>(tetree);
        
        var distribution = debugger.visualizeTetTypeDistribution((byte) 3);
        assertNotNull(distribution);
        assertTrue(distribution.contains("Type Distribution"));
        assertTrue(distribution.contains("Type 0") || distribution.contains("nodes"));
        
        System.out.println("\nTetree Type Distribution:");
        System.out.println(distribution);
    }

    @Test
    public void testSubdivisionVisualization() {
        var tetree = createSampleTetree();
        var debugger = new TetreeDebugger<>(tetree);
        
        var subdivision = debugger.visualizeSubdivisionAt(new Point3f(100, 100, 100), (byte) 3);
        assertNotNull(subdivision);
        assertTrue(subdivision.contains("Tetrahedral Subdivision"));
        
        System.out.println("\nTetree Subdivision Visualization:");
        System.out.println(subdivision);
    }

    @Test
    public void testObjExport() throws IOException {
        var tetree = createSampleTetree();
        var debugger = new TetreeDebugger<>(tetree);
        
        var tempFile = Files.createTempFile("tetree_debug_", ".obj");
        try {
            debugger.exportToObj(tempFile.toString());
            
            // Verify file was created and contains expected content
            assertTrue(Files.exists(tempFile));
            var content = Files.readString(tempFile);
            assertTrue(content.contains("# Tetree Export"));
            assertTrue(content.contains("v ")); // vertices
            // Lines might not be present if no valid tetrahedra are generated
            assertTrue(content.length() > 100, "File should have substantial content");
            
            System.out.println("\nTetree OBJ Export created at: " + tempFile);
            System.out.println("First 500 characters:");
            System.out.println(content.substring(0, Math.min(500, content.length())));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testUnbalancedTetree() {
        // Create an unbalanced tetree by clustering points
        var idGenerator = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, String>(idGenerator, 5, (byte) 5);
        
        // Insert points clustered in one region
        for (int i = 0; i < 100; i++) {
            var x = 10.0f + (float) (Math.random() * 20);
            var y = 10.0f + (float) (Math.random() * 20);
            var z = 10.0f + (float) (Math.random() * 20);
            tetree.insert(new Point3f(x, y, z), (byte) 4, "Entity " + i);
        }
        
        var debugger = new TetreeDebugger<>(tetree);
        var stats = debugger.analyzeBalance();
        
        System.out.println("\nUnbalanced Tetree Analysis:");
        System.out.println(stats);
        
        assertTrue(stats.totalNodes > 0);
        assertTrue(stats.totalEntities == 100);
    }

    @Test
    public void testEmptyTetree() {
        var idGenerator = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, String>(idGenerator);
        var debugger = new TetreeDebugger<>(tetree);
        
        var ascii = debugger.toAsciiArt(3);
        assertNotNull(ascii);
        assertTrue(ascii.contains("Total Nodes: 0"));
        
        var stats = debugger.analyzeBalance();
        assertEquals(0, stats.totalNodes);
        assertEquals(0, stats.totalEntities);
    }

    @Test
    public void testLargeTetreePerformance() {
        var idGenerator = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, String>(idGenerator, 10, (byte) 6);
        
        // Insert a moderate number of points
        var startInsert = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            var x = (float) (Math.random() * 1000);
            var y = (float) (Math.random() * 1000);
            var z = (float) (Math.random() * 1000);
            tetree.insert(new Point3f(x, y, z), (byte) 4, "Entity " + i);
        }
        var insertTime = System.currentTimeMillis() - startInsert;
        
        var debugger = new TetreeDebugger<>(tetree);
        
        // Time ASCII art generation
        var startAscii = System.currentTimeMillis();
        var ascii = debugger.toAsciiArt(2); // Only show first 2 levels
        var asciiTime = System.currentTimeMillis() - startAscii;
        
        // Time balance analysis
        var startAnalysis = System.currentTimeMillis();
        var stats = debugger.analyzeBalance();
        var analysisTime = System.currentTimeMillis() - startAnalysis;
        
        System.out.println("\nTetree Performance Test Results:");
        System.out.println("Insert 1000 entities: " + insertTime + "ms");
        System.out.println("Generate ASCII art: " + asciiTime + "ms");
        System.out.println("Analyze balance: " + analysisTime + "ms");
        System.out.println("\nTree stats: " + stats.totalNodes + " nodes, " + 
                         stats.totalEntities + " entities");
        
        // Performance assertions
        assertTrue(asciiTime < 1000, "ASCII generation should be fast");
        assertTrue(analysisTime < 1000, "Balance analysis should be fast");
    }

    @Test
    public void testTypeDistributionAtDifferentLevels() {
        var tetree = createSampleTetree();
        var debugger = new TetreeDebugger<>(tetree);
        
        // Test distribution at different levels
        for (byte level = 1; level <= 4; level++) {
            var distribution = debugger.visualizeTetTypeDistribution(level);
            assertNotNull(distribution);
            
            System.out.println(String.format("\nType Distribution at Level %d:", level));
            System.out.println(distribution);
        }
    }

    // Helper method to create a sample tetree
    private Tetree<LongEntityID, String> createSampleTetree() {
        var idGenerator = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, String>(idGenerator, 5, (byte) 4);
        
        // Add some points in different regions to create interesting structure
        // Cluster 1: Near origin
        tetree.insert(new Point3f(10, 10, 10), (byte) 3, "A1");
        tetree.insert(new Point3f(20, 20, 20), (byte) 3, "A2");
        tetree.insert(new Point3f(30, 30, 30), (byte) 3, "A3");
        
        // Cluster 2: Mid-range
        tetree.insert(new Point3f(100, 100, 100), (byte) 3, "B1");
        tetree.insert(new Point3f(110, 110, 110), (byte) 3, "B2");
        tetree.insert(new Point3f(120, 100, 100), (byte) 3, "B3");
        tetree.insert(new Point3f(100, 120, 100), (byte) 3, "B4");
        tetree.insert(new Point3f(100, 100, 120), (byte) 3, "B5");
        
        // Cluster 3: Far corner
        tetree.insert(new Point3f(200, 200, 200), (byte) 3, "C1");
        tetree.insert(new Point3f(210, 210, 210), (byte) 3, "C2");
        
        // Some scattered points
        tetree.insert(new Point3f(50, 150, 75), (byte) 3, "D1");
        tetree.insert(new Point3f(150, 50, 125), (byte) 3, "D2");
        tetree.insert(new Point3f(75, 75, 175), (byte) 3, "D3");
        
        return tetree;
    }
}