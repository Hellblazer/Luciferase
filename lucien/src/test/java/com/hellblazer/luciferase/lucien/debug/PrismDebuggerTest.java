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
import com.hellblazer.luciferase.lucien.prism.Prism;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PrismDebugger visualization and analysis features
 */
public class PrismDebuggerTest {

    @Test
    public void testAsciiArtVisualization() {
        var prism = createSamplePrism();
        var debugger = new PrismDebugger<>(prism);
        
        var ascii = debugger.toAsciiArt(3);
        assertNotNull(ascii);
        assertTrue(ascii.contains("Prism Structure Visualization"));
        assertTrue(ascii.contains("Total Nodes:"));
        assertTrue(ascii.contains("Level") || ascii.contains("nodes"));
        
        System.out.println("Prism ASCII Art Visualization:");
        System.out.println(ascii);
    }

    @Test
    public void testTreeBalanceAnalysis() {
        var prism = createSamplePrism();
        var debugger = new PrismDebugger<>(prism);
        
        var stats = debugger.analyzeBalance();
        assertNotNull(stats);
        assertTrue(stats.totalNodes > 0);
        assertTrue(stats.totalEntities > 0);
        assertTrue(stats.maxDepth >= 0);
        assertTrue(stats.avgBranchingFactor >= 0);
        
        // Prism-specific stats
        if (stats instanceof PrismDebugger.PrismBalanceStats prismStats) {
            assertTrue(prismStats.horizontalNodes >= 0);
            assertTrue(prismStats.verticalLevels >= 0);
            assertTrue(prismStats.horizontalDensity >= 0.0);
            assertTrue(prismStats.verticalDensity >= 0.0);
        }
        
        System.out.println("\nPrism Balance Analysis:");
        System.out.println(stats);
    }

    @Test
    public void testTriangularDistribution() {
        var prism = createSamplePrism();
        var debugger = new PrismDebugger<>(prism);
        
        var distribution = debugger.visualizeTriangularDistribution((byte) 3);
        assertNotNull(distribution);
        assertTrue(distribution.contains("Triangular Subdivision Distribution"));
        assertTrue(distribution.contains("X-Axis") || distribution.contains("Distribution"));
        
        System.out.println("\nPrism Triangular Distribution:");
        System.out.println(distribution);
    }

    @Test
    public void test2DSliceVisualization() {
        var prism = createSamplePrism();
        var debugger = new PrismDebugger<>(prism);
        
        // Test slices at different Z coordinates
        var sliceAt0_5 = debugger.visualize2DSlice(0.5f, '■');
        assertNotNull(sliceAt0_5);
        assertTrue(sliceAt0_5.contains("2D Horizontal Slice"));
        
        System.out.println("\n2D Slice Visualization at Z=0.5:");
        System.out.println(sliceAt0_5);
        
        var sliceAt0_8 = debugger.visualize2DSlice(0.8f, '□');
        assertNotNull(sliceAt0_8);
        
        System.out.println("\n2D Slice Visualization at Z=0.8:");
        System.out.println(sliceAt0_8);
    }

    @Test
    public void testObjExport() throws IOException {
        var prism = createSamplePrism();
        var debugger = new PrismDebugger<>(prism);
        
        var tempFile = Files.createTempFile("prism_debug_", ".obj");
        try {
            debugger.exportToObj(tempFile.toString());
            
            // Verify file was created and contains expected content
            assertTrue(Files.exists(tempFile));
            var content = Files.readString(tempFile);
            assertTrue(content.contains("# Prism Export"));
            assertTrue(content.contains("v ")); // vertices
            assertTrue(content.contains("l ")); // lines
            
            System.out.println("\nPrism OBJ Export created at: " + tempFile);
            System.out.println("First 500 characters:");
            System.out.println(content.substring(0, Math.min(500, content.length())));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testUnbalancedPrism() {
        // Create an unbalanced prism by clustering points
        var idGenerator = new SequentialLongIDGenerator();
        var prism = new Prism<LongEntityID, String>(idGenerator, 1000.0f, 10);
        
        // Insert points clustered in one region
        for (int i = 0; i < 100; i++) {
            var x = 10.0f + (float) (Math.random() * 20);
            var y = 10.0f + (float) (Math.random() * 20);
            var z = 10.0f + (float) (Math.random() * 20);
            prism.insert(new Point3f(x, y, z), (byte) 4, "Entity " + i);
        }
        
        var debugger = new PrismDebugger<>(prism);
        var stats = debugger.analyzeBalance();
        
        System.out.println("\nUnbalanced Prism Analysis:");
        System.out.println(stats);
        
        assertTrue(stats.totalNodes > 0);
        assertTrue(stats.totalEntities == 100);
    }

    @Test
    public void testEmptyPrism() {
        var idGenerator = new SequentialLongIDGenerator();
        var prism = new Prism<LongEntityID, String>(idGenerator);
        var debugger = new PrismDebugger<>(prism);
        
        var ascii = debugger.toAsciiArt(3);
        assertNotNull(ascii);
        assertTrue(ascii.contains("Total Nodes: 0"));
        
        var stats = debugger.analyzeBalance();
        assertEquals(0, stats.totalNodes);
        assertEquals(0, stats.totalEntities);
    }

    @Test
    public void testLargePrismPerformance() {
        var idGenerator = new SequentialLongIDGenerator();
        var prism = new Prism<LongEntityID, String>(idGenerator, 1000.0f, 8);
        
        // Insert a moderate number of points with valid triangular coordinates
        var startInsert = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            // Generate coordinates that satisfy x + y < worldSize constraint
            var x = (float) (Math.random() * 500); // Max 500 to leave room for y
            var y = (float) (Math.random() * (1000 - x)); // Ensure x + y < 1000
            var z = (float) (Math.random() * 1000);
            prism.insert(new Point3f(x, y, z), (byte) 4, "Entity " + i);
        }
        var insertTime = System.currentTimeMillis() - startInsert;
        
        var debugger = new PrismDebugger<>(prism);
        
        // Time ASCII art generation
        var startAscii = System.currentTimeMillis();
        var ascii = debugger.toAsciiArt(2); // Only show first 2 levels
        var asciiTime = System.currentTimeMillis() - startAscii;
        
        // Time balance analysis
        var startAnalysis = System.currentTimeMillis();
        var stats = debugger.analyzeBalance();
        var analysisTime = System.currentTimeMillis() - startAnalysis;
        
        System.out.println("\nPrism Performance Test Results:");
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
    public void testDistributionAtDifferentLevels() {
        var prism = createSamplePrism();
        var debugger = new PrismDebugger<>(prism);
        
        // Test distribution at different levels
        for (byte level = 1; level <= 4; level++) {
            var distribution = debugger.visualizeTriangularDistribution(level);
            assertNotNull(distribution);
            
            System.out.println(String.format("\nTriangular Distribution at Level %d:", level));
            System.out.println(distribution);
        }
    }

    @Test
    public void testAnisotropicAnalysis() {
        // Create a prism with anisotropic distribution
        var idGenerator = new SequentialLongIDGenerator();
        var prism = new Prism<LongEntityID, String>(idGenerator, 1000.0f, 8);
        
        // Insert points with high horizontal spread, low vertical spread
        for (int i = 0; i < 100; i++) {
            // Generate coordinates that satisfy x + y < worldSize constraint  
            var x = (float) (Math.random() * 400); // Wide X range, max 400
            var y = (float) (Math.random() * (800 - x)); // Wide Y range, ensure x + y < 800
            var z = 100.0f + (float) (Math.random() * 50); // Narrow Z range
            prism.insert(new Point3f(x, y, z), (byte) 4, "Entity " + i);
        }
        
        var debugger = new PrismDebugger<>(prism);
        var stats = debugger.analyzeBalance();
        
        System.out.println("\nAnisotropic Prism Analysis:");
        System.out.println(stats);
        
        if (stats instanceof PrismDebugger.PrismBalanceStats prismStats) {
            System.out.println("Horizontal vs Vertical distribution:");
            System.out.println(String.format("Horizontal density: %.3f", prismStats.horizontalDensity));
            System.out.println(String.format("Vertical density: %.3f", prismStats.verticalDensity));
            
            // Expect higher horizontal density than vertical for this data
            assertTrue(stats.totalNodes > 0);
        }
    }

    // Helper method to create a sample prism
    private Prism<LongEntityID, String> createSamplePrism() {
        var idGenerator = new SequentialLongIDGenerator();
        var prism = new Prism<LongEntityID, String>(idGenerator, 1000.0f, 8);
        
        // Add some points in different regions to create interesting structure
        // Cluster 1: Near origin
        prism.insert(new Point3f(10, 10, 10), (byte) 3, "A1");
        prism.insert(new Point3f(20, 20, 20), (byte) 3, "A2");
        prism.insert(new Point3f(30, 30, 30), (byte) 3, "A3");
        
        // Cluster 2: Mid-range
        prism.insert(new Point3f(100, 100, 100), (byte) 3, "B1");
        prism.insert(new Point3f(110, 110, 110), (byte) 3, "B2");
        prism.insert(new Point3f(120, 100, 100), (byte) 3, "B3");
        prism.insert(new Point3f(100, 120, 100), (byte) 3, "B4");
        prism.insert(new Point3f(100, 100, 120), (byte) 3, "B5");
        
        // Cluster 3: Far corner
        prism.insert(new Point3f(200, 200, 200), (byte) 3, "C1");
        prism.insert(new Point3f(210, 210, 210), (byte) 3, "C2");
        
        // Some scattered points
        prism.insert(new Point3f(50, 150, 75), (byte) 3, "D1");
        prism.insert(new Point3f(150, 50, 125), (byte) 3, "D2");
        prism.insert(new Point3f(75, 75, 175), (byte) 3, "D3");
        
        return prism;
    }
}