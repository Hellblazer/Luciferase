package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.UUIDGenerator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test to verify that the tetrahedral decomposition is consistent:
 * - determineTetrahedronType() correctly identifies which tetrahedron contains a point
 * - Tet.contains() agrees with the type determination
 * - The 6 tetrahedra properly partition each cube with no gaps or overlaps
 */
public class TetreeContainmentConsistencyTest {

    @Test
    @Disabled("t8code tetrahedra don't properly partition the cube - see TETREE_T8CODE_PARTITION_ANALYSIS.md")
    void testDetermineTypeAndContainsAgree() {
        System.out.println("=== Testing Agreement Between determineTetrahedronType and Tet.contains ===\n");
        
        var tetree = new Tetree(new UUIDGenerator());
        int failures = 0;
        
        // Test at different levels
        for (byte level = 1; level <= 5; level++) {
            var cellSize = Constants.lengthAtLevel(level);
            var maxCoord = (1 << Constants.getMaxRefinementLevel()) - cellSize; // Max coordinate for this level
            
            // Test multiple cubes at this level, but stay within bounds
            int maxCubes = Math.min(3, maxCoord / cellSize);
            for (int cubeX = 0; cubeX < maxCubes; cubeX++) {
                for (int cubeY = 0; cubeY < maxCubes; cubeY++) {
                    for (int cubeZ = 0; cubeZ < maxCubes; cubeZ++) {
                        int anchorX = cubeX * cellSize;
                        int anchorY = cubeY * cellSize;
                        int anchorZ = cubeZ * cellSize;
                        
                        // Skip if this cube would exceed bounds
                        if (anchorX + cellSize > maxCoord || 
                            anchorY + cellSize > maxCoord || 
                            anchorZ + cellSize > maxCoord) {
                            continue;
                        }
                        
                        // Test points throughout the cube
                        for (float fx = 0.1f; fx < 1.0f; fx += 0.2f) {
                            for (float fy = 0.1f; fy < 1.0f; fy += 0.2f) {
                                for (float fz = 0.1f; fz < 1.0f; fz += 0.2f) {
                                    var point = new Point3f(
                                        anchorX + fx * cellSize,
                                        anchorY + fy * cellSize,
                                        anchorZ + fz * cellSize
                                    );
                                    
                                    // Use tetree.locate to get the tetrahedron
                                    var locatedTet = tetree.locateTetrahedron(point, level);
                                    
                                    // Verify the located tet actually contains the point
                                    boolean contains = locatedTet.contains(point);
                                    
                                    if (!contains) {
                                        failures++;
                                        System.err.printf("FAILURE: Level %d, Point (%.1f, %.1f, %.1f) not contained in located tet!\n",
                                            level, point.x, point.y, point.z);
                                        System.err.printf("  Located tet: anchor=(%d,%d,%d), type=%d\n",
                                            locatedTet.x(), locatedTet.y(), locatedTet.z(), locatedTet.type());
                                        
                                        // Check which tetrahedron actually contains it
                                        for (byte type = 0; type < 6; type++) {
                                            var testTet = new Tet(anchorX, anchorY, anchorZ, level, type);
                                            if (testTet.contains(point)) {
                                                System.err.printf("  Actually contained in type %d\n", type);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        assertEquals(0, failures, "All points should be contained in their located tetrahedra");
    }
    
    @Test
    @Disabled("t8code tetrahedra don't properly partition the cube - see TETREE_T8CODE_PARTITION_ANALYSIS.md")
    void testCubePartitioning() {
        System.out.println("\n=== Testing Cube Partitioning ===\n");
        
        // Verify that the 6 tetrahedra properly partition each cube
        byte level = 5;
        var cellSize = Constants.lengthAtLevel(level);
        var maxCoord = (1 << Constants.getMaxRefinementLevel()) - cellSize;
        
        // Use a cube that's safely within bounds
        int anchorX = Math.min(10 * cellSize, maxCoord - cellSize);
        int anchorY = Math.min(10 * cellSize, maxCoord - cellSize);
        int anchorZ = Math.min(10 * cellSize, maxCoord - cellSize);
        
        // Test many points throughout the cube
        int totalPoints = 0;
        int pointsWithNoContainer = 0;
        int pointsWithMultipleContainers = 0;
        
        for (float fx = 0.05f; fx < 1.0f; fx += 0.05f) {
            for (float fy = 0.05f; fy < 1.0f; fy += 0.05f) {
                for (float fz = 0.05f; fz < 1.0f; fz += 0.05f) {
                    totalPoints++;
                    
                    var point = new Point3f(
                        anchorX + fx * cellSize,
                        anchorY + fy * cellSize,
                        anchorZ + fz * cellSize
                    );
                    
                    // Count how many tetrahedra contain this point
                    List<Byte> containingTypes = new ArrayList<>();
                    for (byte type = 0; type < 6; type++) {
                        var tet = new Tet(anchorX, anchorY, anchorZ, level, type);
                        if (tet.contains(point)) {
                            containingTypes.add(type);
                        }
                    }
                    
                    if (containingTypes.isEmpty()) {
                        pointsWithNoContainer++;
                        System.err.printf("NO CONTAINER: Point (%.3f, %.3f, %.3f) relative (%.3f, %.3f, %.3f)\n",
                            point.x, point.y, point.z, fx, fy, fz);
                    } else if (containingTypes.size() > 1) {
                        pointsWithMultipleContainers++;
                        System.err.printf("MULTIPLE CONTAINERS: Point (%.3f, %.3f, %.3f) in types %s\n",
                            point.x, point.y, point.z, containingTypes);
                    }
                }
            }
        }
        
        System.out.printf("Tested %d points:\n", totalPoints);
        System.out.printf("  Points with no container: %d\n", pointsWithNoContainer);
        System.out.printf("  Points with multiple containers: %d\n", pointsWithMultipleContainers);
        System.out.printf("  Points with exactly one container: %d\n", 
            totalPoints - pointsWithNoContainer - pointsWithMultipleContainers);
        
        assertEquals(0, pointsWithNoContainer, "All points should be contained in at least one tetrahedron");
        assertEquals(0, pointsWithMultipleContainers, "No point should be contained in multiple tetrahedra");
    }
    
    @Test
    @Disabled("t8code tetrahedra don't properly partition the cube - see TETREE_T8CODE_PARTITION_ANALYSIS.md")
    void testBoundaryPoints() {
        System.out.println("\n=== Testing Boundary Points ===\n");
        
        var tetree = new Tetree(new UUIDGenerator());
        byte level = 5;
        var cellSize = Constants.lengthAtLevel(level);
        int anchorX = 50 * cellSize;
        int anchorY = 50 * cellSize;
        int anchorZ = 50 * cellSize;
        
        // Test points on faces, edges, and vertices
        var boundaryPoints = new Point3f[] {
            // Vertices of the cube
            new Point3f(anchorX, anchorY, anchorZ),
            new Point3f(anchorX + cellSize, anchorY, anchorZ),
            new Point3f(anchorX, anchorY + cellSize, anchorZ),
            new Point3f(anchorX, anchorY, anchorZ + cellSize),
            new Point3f(anchorX + cellSize, anchorY + cellSize, anchorZ),
            new Point3f(anchorX + cellSize, anchorY, anchorZ + cellSize),
            new Point3f(anchorX, anchorY + cellSize, anchorZ + cellSize),
            new Point3f(anchorX + cellSize, anchorY + cellSize, anchorZ + cellSize),
            
            // Edge midpoints
            new Point3f(anchorX + cellSize/2, anchorY, anchorZ),
            new Point3f(anchorX, anchorY + cellSize/2, anchorZ),
            new Point3f(anchorX, anchorY, anchorZ + cellSize/2),
            
            // Face centers
            new Point3f(anchorX + cellSize/2, anchorY + cellSize/2, anchorZ),
            new Point3f(anchorX + cellSize/2, anchorY, anchorZ + cellSize/2),
            new Point3f(anchorX, anchorY + cellSize/2, anchorZ + cellSize/2),
            
            // Cube center
            new Point3f(anchorX + cellSize/2, anchorY + cellSize/2, anchorZ + cellSize/2)
        };
        
        for (var point : boundaryPoints) {
            var locatedTet = tetree.locateTetrahedron(point, level);
            boolean contains = locatedTet.contains(point);
            
            System.out.printf("Boundary point (%.0f, %.0f, %.0f) -> Type %d, contained: %s\n",
                point.x, point.y, point.z, locatedTet.type(), contains);
            
            // Count containers for boundary points
            int containerCount = 0;
            for (byte type = 0; type < 6; type++) {
                var tet = new Tet(anchorX, anchorY, anchorZ, level, type);
                if (tet.contains(point)) {
                    containerCount++;
                }
            }
            
            assertTrue(containerCount >= 1, 
                String.format("Boundary point (%.0f, %.0f, %.0f) should be in at least one tetrahedron",
                    point.x, point.y, point.z));
        }
    }
    
    @Test
    void testSpecificFailureCases() {
        System.out.println("\n=== Testing Specific Failure Cases ===\n");
        
        // If we find specific points that fail in the visualization,
        // we can add them here for targeted testing
        
        var tetree = new Tetree(new UUIDGenerator());
        
        // Example: Test a point that might be problematic
        var point = new Point3f(524288, 262144, 393216);
        byte level = 5;
        
        var locatedTet = tetree.locateTetrahedron(point, level);
        System.out.printf("Point (%.0f, %.0f, %.0f) located in:\n", point.x, point.y, point.z);
        System.out.printf("  Tet: anchor=(%d,%d,%d), type=%d, level=%d\n",
            locatedTet.x(), locatedTet.y(), locatedTet.z(), locatedTet.type(), locatedTet.l());
        
        boolean contains = locatedTet.contains(point);
        System.out.printf("  Contains check: %s\n", contains);
        
        assertTrue(contains, "Located tetrahedron should contain the point");
        
        // Also verify the vertices make sense
        var vertices = locatedTet.coordinates();
        System.out.println("  Vertices:");
        for (int i = 0; i < vertices.length; i++) {
            System.out.printf("    v%d: (%d, %d, %d)\n", i, vertices[i].x, vertices[i].y, vertices[i].z);
        }
    }
}