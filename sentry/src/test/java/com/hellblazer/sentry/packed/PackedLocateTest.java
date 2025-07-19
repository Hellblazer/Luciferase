package com.hellblazer.sentry.packed;

import com.hellblazer.sentry.Grid;
import com.hellblazer.sentry.MutableGrid;
import com.hellblazer.sentry.Tetrahedron;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the locate algorithm in packed implementation
 */
public class PackedLocateTest {
    
    @Test
    public void testLocateInInitialTetrahedron() {
        PackedGrid packed = new PackedGrid();
        Random random = new Random(42);
        
        // Test points that should be inside the big tetrahedron
        Point3f[] testPoints = {
            new Point3f(0, 0, 0),
            new Point3f(100, 100, 100),
            new Point3f(-100, -100, -100),
            new Point3f(1000, 0, 0),
            new Point3f(0, 1000, 0),
            new Point3f(0, 0, 1000),
            new Point3f(-1000, -1000, -1000)
        };
        
        for (Point3f p : testPoints) {
            int result = packed.locate(p, -1, random);
            assertEquals(0, result, 
                "Point " + p + " should be located in initial tetrahedron");
        }
    }
    
    @Test
    public void testLocateOutside() {
        PackedGrid packed = new PackedGrid();
        Random random = new Random(42);
        
        // Test points that should be outside the big tetrahedron
        // The corners are at scale 2^24 ≈ 16.7M
        Point3f[] outsidePoints = {
            new Point3f(20000000, 0, 0),
            new Point3f(0, 20000000, 0),
            new Point3f(0, 0, 20000000),
            new Point3f(-20000000, 0, 0),
            new Point3f(0, -20000000, 0),
            new Point3f(0, 0, -20000000)
        };
        
        for (Point3f p : outsidePoints) {
            int result = packed.locate(p, -1, random);
            assertEquals(-1, result, 
                "Point " + p + " should be outside tetrahedralization");
        }
    }
    
    @Test
    public void testLocateWithMultipleTetrahedra() {
        PackedGrid packed = new PackedGrid();
        Random random = new Random(42);
        
        // Add a vertex to create more tetrahedra
        int v4 = packed.addVertex(0, 0, 0);
        
        // Create additional tetrahedra by subdivision
        // In a real implementation, this would be done by flip operations
        // For now, manually create a tetrahedron sharing face with initial
        int t1 = packed.createTetrahedron(0, 1, 2, v4);
        
        // Set up neighbor relationships
        // The new tetrahedron shares face ABC (opposite D) with initial tet
        packed.setNeighbors(0, 3, t1, 3); // Face 3 of both tets
        
        // Test that origin is still found (might be in either tet)
        int result = packed.locate(new Point3f(0, 0, 0), -1, random);
        assertTrue(result == 0 || result == t1, 
            "Origin should be found in one of the tetrahedra");
        
        // Test walking from one tet to another
        result = packed.locate(new Point3f(0, 0, 0), 0, random);
        assertTrue(result >= 0, "Should find containing tetrahedron");
    }
    
    @Test
    public void testLocateConsistencyWithOO() {
        // Test that locate behaves the same as OO implementation
        MutableGrid ooGrid = new MutableGrid();
        PackedGrid packed = new PackedGrid();
        Random random = new Random(42);
        
        Point3f[] testPoints = {
            new Point3f(0, 0, 0),
            new Point3f(500, 500, 500),
            new Point3f(-500, 500, -500),
            new Point3f(1500, -1500, 1500)
        };
        
        for (Point3f p : testPoints) {
            // OO locate
            Tetrahedron ooResult = ooGrid.locate(p, null);
            
            // Packed locate
            int packedResult = packed.locate(p, -1, random);
            
            if (ooResult != null) {
                assertTrue(packedResult >= 0, 
                    "Point " + p + " found by OO should also be found by packed");
            } else {
                assertEquals(-1, packedResult,
                    "Point " + p + " not found by OO should also not be found by packed");
            }
        }
    }
    
    @Test
    public void testLocatePerformance() {
        PackedGrid packed = new PackedGrid();
        Random random = new Random(42);
        
        // Warm up
        for (int i = 0; i < 1000; i++) {
            packed.locate(new Point3f(0, 0, 0), -1, random);
        }
        
        // Measure performance
        int iterations = 10000;
        long start = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            Point3f p = new Point3f(
                (float)(Math.random() * 2000 - 1000),
                (float)(Math.random() * 2000 - 1000),
                (float)(Math.random() * 2000 - 1000)
            );
            packed.locate(p, -1, random);
        }
        
        long elapsed = System.nanoTime() - start;
        double avgNanos = (double) elapsed / iterations;
        
        System.out.println("Average locate time: " + avgNanos + " ns");
        
        // Should be reasonably fast (under 1 microsecond per locate)
        assertTrue(avgNanos < 1000, 
            "Locate should be fast (under 1μs), but took " + avgNanos + " ns");
    }
    
    @Test
    public void testStochasticWalk() {
        PackedGrid packed = new PackedGrid();
        Random random = new Random();
        
        // Add vertices to create a more complex mesh
        int v4 = packed.addVertex(100, 100, 100);
        int v5 = packed.addVertex(-100, 100, -100);
        
        // Create tetrahedra
        int t1 = packed.createTetrahedron(0, 1, 2, v4);
        int t2 = packed.createTetrahedron(0, 1, 3, v5);
        
        // Set up a simple neighbor structure
        packed.setNeighbors(0, 3, t1, 3);
        packed.setNeighbors(t1, 0, t2, 0);
        
        // Test multiple locate calls with different random seeds
        Point3f testPoint = new Point3f(50, 50, 50);
        
        for (int seed = 0; seed < 10; seed++) {
            random.setSeed(seed);
            int result = packed.locate(testPoint, -1, random);
            assertTrue(result >= 0, 
                "Should consistently find tetrahedron regardless of random seed");
        }
    }
}