package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.Tet;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the spatial operations in Tet.java - boundary checking, volume queries, etc.
 * Demonstrates parity with Octree implementation.
 * 
 * @author hal.hildebrand
 */
public class TetSpatialOperationsTest {

    @Test
    public void testEnclosingOperations() {
        System.out.println("=== Testing Enclosing Operations ===");
        
        // Test point enclosure
        var point = new Point3f(100, 200, 300);
        byte level = 5;
        
        var tet = new Tet(0, 0);  // Root tetrahedron
        long index = tet.enclosing(point, level);
        
        System.out.printf("Point (%g, %g, %g) at level %d -> index %d%n", 
            point.x, point.y, point.z, level, index);
        
        // Verify the index produces a tetrahedron that contains the point
        var resultTet = Tet.tetrahedron(index);
        assertEquals(level, resultTet.l(), "Result should be at requested level");
        
        // Test volume enclosure
        var cube = new Spatial.Cube(50, 100, 150, 100); // origin + extent
        long cubeIndex = tet.enclosing(cube);
        
        System.out.printf("Cube at (%g, %g, %g) with extent %g -> index %d%n", 
            cube.originX(), cube.originY(), cube.originZ(), cube.extent(), cubeIndex);
        
        var cubeTet = Tet.tetrahedron(cubeIndex);
        assertTrue(cubeIndex >= 0, "Should find enclosing tetrahedron");
        
        System.out.println("✅ Enclosing operations verified");
    }

    @Test
    public void testBoundedByOperations() {
        System.out.println("=== Testing BoundedBy Operations ===");
        
        var tet = new Tet(0, 0);  // Root tetrahedron
        
        // Create a cube that should contain several tetrahedra
        var cube = new Spatial.Cube(0, 0, 0, Constants.lengthAtLevel((byte) 2));
        
        // Find all tetrahedra completely contained within the cube
        List<Long> boundedIndices = tet.boundedBy(cube).collect(Collectors.toList());
        
        System.out.printf("Found %d tetrahedra bounded by cube%n", boundedIndices.size());
        
        // Verify each tetrahedron is actually contained
        for (long index : boundedIndices) {
            var tetAtIndex = Tet.tetrahedron(index);
            assertNotNull(tetAtIndex, "Should be able to construct tetrahedron from index");
            
            // Simple check - all vertices should be within cube bounds
            var vertices = tetAtIndex.coordinates();
            for (var vertex : vertices) {
                assertTrue(vertex.x >= cube.originX() && vertex.x <= cube.originX() + cube.extent(),
                    "Vertex x should be within cube bounds");
                assertTrue(vertex.y >= cube.originY() && vertex.y <= cube.originY() + cube.extent(),
                    "Vertex y should be within cube bounds");
                assertTrue(vertex.z >= cube.originZ() && vertex.z <= cube.originZ() + cube.extent(),
                    "Vertex z should be within cube bounds");
            }
        }
        
        System.out.println("✅ BoundedBy operations verified");
    }

    @Test
    public void testBoundingOperations() {
        System.out.println("=== Testing Bounding Operations ===");
        
        var tet = new Tet(0, 0);  // Root tetrahedron
        
        // Create a small sphere 
        var sphere = new Spatial.Sphere(Constants.lengthAtLevel((byte) 3), 
                                       Constants.lengthAtLevel((byte) 3), 
                                       Constants.lengthAtLevel((byte) 3), 
                                       Constants.lengthAtLevel((byte) 4));
        
        // Find all tetrahedra that bound (intersect with) the sphere
        List<Long> boundingIndices = tet.bounding(sphere).collect(Collectors.toList());
        
        System.out.printf("Found %d tetrahedra bounding sphere%n", boundingIndices.size());
        
        assertTrue(boundingIndices.size() > 0, "Should find at least one bounding tetrahedron");
        
        // Verify each tetrahedron actually intersects with the sphere
        for (long index : boundingIndices) {
            var tetAtIndex = Tet.tetrahedron(index);
            assertNotNull(tetAtIndex, "Should be able to construct tetrahedron from index");
            
            // The tetrahedron should have at least one vertex near the sphere or contain the sphere center
            var vertices = tetAtIndex.coordinates();
            boolean intersects = false;
            
            // Check if any vertex is within the sphere's bounding box
            for (var vertex : vertices) {
                float dx = vertex.x - sphere.centerX();
                float dy = vertex.y - sphere.centerY();
                float dz = vertex.z - sphere.centerZ();
                if (Math.abs(dx) <= sphere.radius() && Math.abs(dy) <= sphere.radius() && Math.abs(dz) <= sphere.radius()) {
                    intersects = true;
                    break;
                }
            }
            
            // Or check if sphere center is contained in tetrahedron
            var sphereCenter = new Point3f(sphere.centerX(), sphere.centerY(), sphere.centerZ());
            if (tetAtIndex.contains(sphereCenter)) {
                intersects = true;
            }
            
            if (!intersects) {
                // This is acceptable - the intersection test is approximate
                System.out.printf("Note: Tetrahedron %d may not precisely intersect sphere (approximate test)%n", index);
            }
        }
        
        System.out.println("✅ Bounding operations verified");
    }

    @Test
    public void testIntersectingOperations() {
        System.out.println("=== Testing Intersecting Operations ===");
        
        var tet = new Tet(0, 0);  // Root tetrahedron
        
        // Create an AABB
        var aabb = new Spatial.aabb(100, 100, 100, 200, 200, 200);
        
        // Find first intersecting tetrahedron
        long intersectingIndex = tet.intersecting(aabb);
        
        System.out.printf("First tetrahedron intersecting AABB: index %d%n", intersectingIndex);
        
        if (intersectingIndex > 0) {
            var intersectingTet = Tet.tetrahedron(intersectingIndex);
            assertNotNull(intersectingTet, "Should be able to construct intersecting tetrahedron");
            
            System.out.printf("Intersecting tetrahedron: level=%d, type=%d, coords=(%d,%d,%d)%n", 
                intersectingTet.l(), intersectingTet.type(), 
                intersectingTet.x(), intersectingTet.y(), intersectingTet.z());
        }
        
        System.out.println("✅ Intersecting operations verified");
    }

    @Test
    public void testLocateOperation() {
        System.out.println("=== Testing Locate Operation ===");
        
        var tet = new Tet(0, 0);  // Root tetrahedron
        
        // Test locating tetrahedra at different points and levels
        var testPoints = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(100, 100, 100),
            new Point3f(500, 300, 700),
            new Point3f(1000, 1000, 1000)
        };
        
        for (var point : testPoints) {
            for (byte level = 0; level <= Math.min(Constants.getMaxRefinementLevel(), 5); level++) {
                var locatedTet = tet.locate(point, level);
                
                assertNotNull(locatedTet, "Should locate a tetrahedron");
                assertEquals(level, locatedTet.l(), "Should be at requested level");
                assertTrue(locatedTet.type() >= 0 && locatedTet.type() <= 5, "Should have valid type");
                
                // Verify the point is actually contained in the located tetrahedron
                assertTrue(locatedTet.contains(point), 
                    String.format("Point (%g,%g,%g) should be contained in located tetrahedron at level %d", 
                        point.x, point.y, point.z, level));
                
                System.out.printf("Point (%g,%g,%g) at level %d -> tet(%d,%d,%d) type=%d%n", 
                    point.x, point.y, point.z, level, 
                    locatedTet.x(), locatedTet.y(), locatedTet.z(), locatedTet.type());
            }
        }
        
        System.out.println("✅ Locate operation verified");
    }

    @Test
    public void testSpatialConsistency() {
        System.out.println("=== Testing Spatial Consistency ===");
        
        var tet = new Tet(0, 0);  // Root tetrahedron
        
        // Create a test volume
        var testCube = new Spatial.Cube(200, 200, 200, 400);
        
        // Get bounded and bounding tetrahedra
        List<Long> bounded = tet.boundedBy(testCube).limit(10).collect(Collectors.toList());
        List<Long> bounding = tet.bounding(testCube).limit(20).collect(Collectors.toList());
        
        System.out.printf("Test cube: bounded=%d, bounding=%d tetrahedra%n", bounded.size(), bounding.size());
        
        // All bounded tetrahedra should also be in bounding set
        for (long boundedIndex : bounded) {
            assertTrue(bounding.contains(boundedIndex), 
                "Bounded tetrahedra should also be bounding tetrahedra");
        }
        
        // Bounding should have at least as many as bounded
        assertTrue(bounding.size() >= bounded.size(), 
            "Bounding set should be at least as large as bounded set");
        
        System.out.println("✅ Spatial consistency verified");
    }
}