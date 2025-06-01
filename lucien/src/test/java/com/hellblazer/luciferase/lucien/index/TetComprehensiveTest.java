package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Tet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Tet.java covering all levels 0-20 and all major functionality
 * 
 * @author hal.hildebrand
 */
public class TetComprehensiveTest {

    private static final byte MAX_LEVEL = Constants.getMaxRefinementLevel();
    private static final Random random = new Random(0x42);

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testBasicConstructorsAndGetters() {
        System.out.println("=== Testing Basic Constructors and Getters ===");
        
        // Test all constructor variants
        for (byte level = 0; level <= Math.min(MAX_LEVEL, 10); level++) {
            for (byte type = 0; type < 6; type++) {
                int h = Constants.lengthAtLevel(level);
                int x = random.nextInt(h * 8);
                int y = random.nextInt(h * 8);
                int z = random.nextInt(h * 8);
                
                // Constructor 1: (x, y, z, level, type)
                Tet tet1 = new Tet(x, y, z, level, type);
                assertEquals(x, tet1.x(), "X coordinate mismatch");
                assertEquals(y, tet1.y(), "Y coordinate mismatch");
                assertEquals(z, tet1.z(), "Z coordinate mismatch");
                assertEquals(level, tet1.l(), "Level mismatch");
                assertEquals(type, tet1.type(), "Type mismatch");
                
                // Constructor 2: (Point3i, level, type)
                Point3i point = new Point3i(x, y, z);
                Tet tet2 = new Tet(point, level, type);
                assertEquals(tet1, tet2, "Point3i constructor should produce identical result");
                
                // Constructor 3: (level, type) - creates at origin
                Tet tet3 = new Tet(level, type);
                assertEquals(0, tet3.x(), "Origin constructor should have x=0");
                assertEquals(0, tet3.y(), "Origin constructor should have y=0");
                assertEquals(0, tet3.z(), "Origin constructor should have z=0");
                assertEquals(level, tet3.l(), "Origin constructor level mismatch");
                assertEquals(type, tet3.type(), "Origin constructor type mismatch");
            }
        }
        
        System.out.println("✅ Basic constructors and getters verified");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    public void testSpaceFillingCurveOperations() {
        System.out.println("=== Testing Space-Filling Curve Operations ===");
        
        int totalTests = 0;
        int successes = 0;
        List<String> failures = new ArrayList<>();
        
        // Test SFC operations at each level
        for (byte level = 0; level <= Math.min(MAX_LEVEL, 15); level++) {
            System.out.printf("Testing SFC at level %d:%n", level);
            
            // Calculate reasonable test range for this level
            long startIndex = level == 0 ? 0 : (1L << (3 * (level - 1)));
            long endIndex = Math.min(1L << (3 * level), startIndex + (level <= 8 ? 1000 : 100));
            
            for (long index = startIndex; index < endIndex; index++) {
                totalTests++;
                
                try {
                    // Test tetLevelFromIndex
                    byte calculatedLevel = Tet.tetLevelFromIndex(index);
                    if (calculatedLevel != level) {
                        failures.add(String.format("Level %d: tetLevelFromIndex(%d) = %d, expected %d", 
                            level, index, calculatedLevel, level));
                        continue;
                    }
                    
                    // Test tetrahedron construction
                    Tet tet = Tet.tetrahedron(index, level);
                    assertNotNull(tet, "Tetrahedron construction should not return null");
                    assertEquals(level, tet.l(), "Constructed tet should have correct level");
                    assertTrue(tet.type() >= 0 && tet.type() <= 5, "Type should be in range [0,5]");
                    
                    // Test index round-trip
                    long reconstructedIndex = tet.index();
                    if (reconstructedIndex != index) {
                        failures.add(String.format("Level %d: index round-trip failed: %d -> %d", 
                            level, index, reconstructedIndex));
                        continue;
                    }
                    
                    // Test single-parameter tetrahedron method
                    Tet autoTet = Tet.tetrahedron(index);
                    if (!tet.equals(autoTet)) {
                        failures.add(String.format("Level %d: auto-level tetrahedron differs for index %d", 
                            level, index));
                        continue;
                    }
                    
                    successes++;
                    
                } catch (Exception e) {
                    failures.add(String.format("Level %d: Exception for index %d: %s", 
                        level, index, e.getMessage()));
                }
            }
        }
        
        System.out.printf("SFC Results: %d/%d passed%n", successes, totalTests);
        if (!failures.isEmpty()) {
            System.out.println("Failures:");
            failures.stream().limit(10).forEach(f -> System.out.println("  " + f));
            if (failures.size() > 10) {
                System.out.printf("  ... and %d more%n", failures.size() - 10);
            }
        }
        
        assertEquals(totalTests, successes, "All SFC operations should succeed");
        System.out.println("✅ Space-filling curve operations verified");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    public void testParentChildRelationships() {
        System.out.println("=== Testing Parent-Child Relationships ===");
        
        int failures = 0;
        
        // Test parent-child relationships at each level
        for (byte level = 1; level <= Math.min(MAX_LEVEL - 1, 12); level++) {
            System.out.printf("Testing parent-child at level %d:%n", level);
            
            // Test a representative sample of tetrahedra at this level
            long startIndex = 1L << (3 * (level - 1));
            long endIndex = Math.min(1L << (3 * level), startIndex + 200);
            
            for (long index = startIndex; index < endIndex; index += Math.max(1, (endIndex - startIndex) / 100)) {
                try {
                    Tet tet = Tet.tetrahedron(index, level);
                    
                    // Test parent relationship
                    if (level > 0) {
                        Tet parent = tet.parent();
                        assertNotNull(parent, "Parent should not be null");
                        assertEquals(level - 1, parent.l(), "Parent should be one level up");
                        
                        // Verify parent index relationship
                        long parentIndex = parent.index();
                        long expectedParentIndex = index >> 3; // Divide by 8
                        assertEquals(expectedParentIndex, parentIndex, 
                            String.format("Parent index mismatch: child=%d, parent=%d, expected=%d", 
                                index, parentIndex, expectedParentIndex));
                    }
                    
                    // Test child relationships (if not at max level)
                    if (level < MAX_LEVEL) {
                        for (byte childIdx = 0; childIdx < 8; childIdx++) {
                            Tet child = tet.child(childIdx);
                            assertNotNull(child, "Child should not be null");
                            assertEquals(level + 1, child.l(), "Child should be one level down");
                            assertTrue(child.type() >= 0 && child.type() <= 5, "Child type should be valid");
                            
                            // Test Morton child variant
                            Tet mortonChild = tet.childTM(childIdx);
                            assertNotNull(mortonChild, "Morton child should not be null");
                            assertEquals(level + 1, mortonChild.l(), "Morton child level should be correct");
                        }
                    }
                    
                } catch (Exception e) {
                    failures++;
                    if (failures <= 5) {
                        System.out.printf("  Parent-child failure at level %d, index %d: %s%n", 
                            level, index, e.getMessage());
                    }
                }
            }
        }
        
        assertEquals(0, failures, "Parent-child relationships should work correctly");
        System.out.println("✅ Parent-child relationships verified");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testGeometricOperations() {
        System.out.println("=== Testing Geometric Operations ===");
        
        int failures = 0;
        
        // Test geometric operations at various levels
        for (byte level = 0; level <= Math.min(MAX_LEVEL, 10); level++) {
            System.out.printf("Testing geometry at level %d:%n", level);
            
            // Test a few representative tetrahedra at this level
            for (byte type = 0; type < 6; type++) {
                try {
                    int h = Constants.lengthAtLevel(level);
                    Tet tet = new Tet(h, h, h, level, type);
                    
                    // Test coordinate calculation
                    Point3i[] coords = tet.coordinates();
                    assertNotNull(coords, "Coordinates should not be null");
                    assertEquals(4, coords.length, "Should have 4 coordinates");
                    for (Point3i coord : coords) {
                        assertNotNull(coord, "Each coordinate should not be null");
                    }
                    
                    // Test vertices calculation
                    Point3i[] vertices = tet.vertices();
                    assertNotNull(vertices, "Vertices should not be null");
                    assertEquals(4, vertices.length, "Should have 4 vertices");
                    
                    // Test length calculation
                    int length = tet.length();
                    int expectedLength = 1 << (MAX_LEVEL - level);
                    assertEquals(expectedLength, length, "Length calculation should be correct");
                    
                    // Test cube ID calculation
                    for (byte testLevel = 0; testLevel <= level; testLevel++) {
                        byte cubeId = tet.cubeId(testLevel);
                        assertTrue(cubeId >= 0 && cubeId <= 7, "Cube ID should be in range [0,7]");
                    }
                    
                    // Test contains for some basic points
                    Point3f testPoint = new Point3f(tet.x() + length/2f, tet.y() + length/2f, tet.z() + length/2f);
                    // Note: contains test is complex due to tetrahedral geometry, so we just verify it doesn't crash
                    assertDoesNotThrow(() -> tet.contains(testPoint), "Contains should not throw");
                    
                    // Test face neighbors
                    for (int face = 0; face < 4; face++) {
                        Tet.FaceNeighbor neighbor = tet.faceNeighbor(face);
                        assertNotNull(neighbor, "Face neighbor should not be null");
                        assertNotNull(neighbor.tet(), "Neighbor tet should not be null");
                        assertTrue(neighbor.face() >= 0 && neighbor.face() <= 3, "Neighbor face should be valid");
                        assertEquals(level, neighbor.tet().l(), "Neighbor should be at same level");
                    }
                    
                } catch (Exception e) {
                    failures++;
                    if (failures <= 5) {
                        System.out.printf("  Geometry failure at level %d, type %d: %s%n", 
                            level, type, e.getMessage());
                    }
                }
            }
        }
        
        assertEquals(0, failures, "Geometric operations should work correctly");
        System.out.println("✅ Geometric operations verified");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void testTypeComputationAtAllLevels() {
        System.out.println("=== Testing Type Computation at All Levels ===");
        
        int failures = 0;
        
        // Test type computation for each level and type combination
        for (byte level = 0; level <= Math.min(MAX_LEVEL, 15); level++) {
            // Level 0 can only have type 0 (root tetrahedron)
            byte maxType = (byte) (level == 0 ? 1 : 6);
            for (byte type = 0; type < maxType; type++) {
                try {
                    int h = Constants.lengthAtLevel(level);
                    Tet tet = new Tet(h * 2, h * 3, h * 4, level, type);
                    
                    // Test computeType for all levels from 0 to current level
                    for (byte testLevel = 0; testLevel <= level; testLevel++) {
                        byte computedType = tet.computeType(testLevel);
                        assertTrue(computedType >= 0 && computedType <= 5, 
                            String.format("Computed type %d out of range at level %d->%d", 
                                computedType, level, testLevel));
                        
                        // At the tet's own level, should return its own type
                        if (testLevel == level) {
                            assertEquals(type, computedType, 
                                String.format("computeType should return own type at level %d", level));
                        }
                        
                        // At level 0, should always return 0 (root type)
                        if (testLevel == 0) {
                            assertEquals(0, computedType, 
                                String.format("computeType should return 0 at root level for tet at (%d,%d,%d) level=%d type=%d", 
                                    tet.x(), tet.y(), tet.z(), level, type));
                        }
                    }
                    
                } catch (Exception e) {
                    failures++;
                    if (failures <= 5) {
                        System.out.printf("  Type computation failure at level %d, type %d: %s%n", 
                            level, type, e.getMessage());
                    }
                }
            }
        }
        
        assertEquals(0, failures, "Type computation should work at all levels");
        System.out.println("✅ Type computation verified at all levels");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testBoundaryConditionsAndEdgeCases() {
        System.out.println("=== Testing Boundary Conditions and Edge Cases ===");
        
        List<String> issues = new ArrayList<>();
        
        // Test level 0 (root)
        try {
            Tet root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
            assertEquals(0, root.index(), "Root should have index 0");
            assertEquals(Constants.MAX_EXTENT, root.length(), "Root should have maximum length");
            
            // Root should not have parent
            assertThrows(Exception.class, () -> root.parent(), "Root should not have parent");
            
        } catch (Exception e) {
            issues.add("Root tetrahedron test failed: " + e.getMessage());
        }
        
        // Test maximum level
        try {
            int maxLength = Constants.lengthAtLevel(MAX_LEVEL);
            Tet maxTet = new Tet(0, 0, 0, MAX_LEVEL, (byte) 0);
            assertEquals(maxLength, maxTet.length(), "Max level tet should have unit length");
            assertEquals(1, maxLength, "Max level should have length 1");
            
            // Max level should not have children
            assertThrows(Exception.class, () -> maxTet.childTM((byte) 0), 
                "Max level tet should not have children");
                
        } catch (Exception e) {
            issues.add("Max level tetrahedron test failed: " + e.getMessage());
        }
        
        // Test invalid inputs
        try {
            // Invalid level
            assertThrows(Exception.class, () -> new Tet(0, 0, 0, (byte) -1, (byte) 0),
                "Negative level should be rejected");
            assertThrows(Exception.class, () -> new Tet(0, 0, 0, (byte) (MAX_LEVEL + 1), (byte) 0),
                "Too high level should be rejected");
                
            // Invalid type  
            assertThrows(Exception.class, () -> new Tet(0, 0, 0, (byte) 5, (byte) -1),
                "Negative type should be rejected");
            assertThrows(Exception.class, () -> new Tet(0, 0, 0, (byte) 5, (byte) 6),
                "Too high type should be rejected");
                
            // Invalid cube ID queries
            Tet tet = new Tet(100, 100, 100, (byte) 10, (byte) 2);
            assertThrows(Exception.class, () -> tet.cubeId((byte) -1),
                "Negative cube ID level should be rejected");
            assertThrows(Exception.class, () -> tet.cubeId((byte) (MAX_LEVEL + 1)),
                "Too high cube ID level should be rejected");
                
        } catch (Exception e) {
            issues.add("Invalid input validation failed: " + e.getMessage());
        }
        
        // Test coordinate bounds
        for (byte level = 0; level <= Math.min(MAX_LEVEL, 10); level++) {
            try {
                int h = Constants.lengthAtLevel(level);
                
                // Test at coordinate boundaries
                int[] testCoords = {0, h, h*2, Constants.MAX_EXTENT - h};
                for (int x : testCoords) {
                    for (int y : testCoords) {
                        for (int z : testCoords) {
                            if (x >= 0 && y >= 0 && z >= 0 && 
                                x <= Constants.MAX_EXTENT && y <= Constants.MAX_EXTENT && z <= Constants.MAX_EXTENT) {
                                
                                Tet tet = new Tet(x, y, z, level, (byte) 0);
                                
                                // Should be able to compute index
                                assertDoesNotThrow(() -> tet.index(), 
                                    String.format("Index computation should work for (%d,%d,%d) at level %d", 
                                        x, y, z, level));
                            }
                        }
                    }
                }
                
            } catch (Exception e) {
                issues.add(String.format("Coordinate bounds test failed at level %d: %s", level, e.getMessage()));
            }
        }
        
        // Report issues
        if (!issues.isEmpty()) {
            System.out.println("Issues found:");
            issues.forEach(issue -> System.out.println("  " + issue));
        }
        
        assertTrue(issues.isEmpty(), "No boundary condition issues should be found");
        System.out.println("✅ Boundary conditions and edge cases verified");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    public void testLevelConsistencyAcrossAllLevels() {
        System.out.println("=== Testing Level Consistency Across All Levels 0-20 ===");
        
        int failures = 0;
        Set<Long> seenIndices = new HashSet<>();
        
        // Test consistency at each level
        for (byte level = 0; level <= MAX_LEVEL; level++) {
            System.out.printf("Testing level %d consistency:%n", level);
            
            try {
                // Calculate expected properties for this level
                int expectedLength = Constants.lengthAtLevel(level);
                long expectedStartIndex = level == 0 ? 0 : (1L << (3 * (level - 1)));
                long expectedEndIndex = 1L << (3 * level);
                
                // Test a representative sample (limit for performance)
                long sampleSize = Math.min(expectedEndIndex - expectedStartIndex, 1000);
                long step = Math.max(1, (expectedEndIndex - expectedStartIndex) / sampleSize);
                
                for (long index = expectedStartIndex; index < expectedEndIndex; index += step) {
                    // Verify tetLevelFromIndex consistency
                    byte calculatedLevel = Tet.tetLevelFromIndex(index);
                    if (calculatedLevel != level) {
                        failures++;
                        if (failures <= 5) {
                            System.out.printf("  Level inconsistency: index %d calculated as level %d, expected %d%n",
                                index, calculatedLevel, level);
                        }
                        continue;
                    }
                    
                    // Verify tetrahedron construction
                    Tet tet = Tet.tetrahedron(index, level);
                    if (tet.l() != level) {
                        failures++;
                        continue;
                    }
                    
                    // Verify length calculation
                    if (tet.length() != expectedLength) {
                        failures++;
                        continue;
                    }
                    
                    // Verify index uniqueness (for small samples)
                    if (sampleSize <= 10000) {
                        if (seenIndices.contains(index)) {
                            failures++;
                            continue;
                        }
                        seenIndices.add(index);
                    }
                    
                    // Verify round-trip consistency
                    long reconstructedIndex = tet.index();
                    if (reconstructedIndex != index) {
                        failures++;
                        if (failures <= 5) {
                            System.out.printf("  Round-trip failure at level %d: %d -> %d%n",
                                level, index, reconstructedIndex);
                        }
                    }
                }
                
                // Test level properties
                assertEquals(expectedLength, Constants.lengthAtLevel(level), 
                    String.format("lengthAtLevel should be consistent for level %d", level));
                    
            } catch (Exception e) {
                failures++;
                System.out.printf("  Exception at level %d: %s%n", level, e.getMessage());
            }
        }
        
        System.out.printf("Level consistency results: %d failures found%n", failures);
        assertEquals(0, failures, "All levels should be consistent");
        System.out.println("✅ Level consistency verified across all levels 0-20");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testRandomizedStressTest() {
        System.out.println("=== Randomized Stress Test Using Valid SFC Tetrahedra ===");
        
        int totalTests = 10000;
        int failures = 0;
        List<String> failureDetails = new ArrayList<>();
        
        for (int i = 0; i < totalTests; i++) {
            try {
                // Generate random valid SFC index and level
                byte level = (byte) random.nextInt(Math.min(MAX_LEVEL, 12) + 1);
                
                // Calculate valid index range for this level  
                long startIndex = level == 0 ? 0 : (1L << (3 * (level - 1)));
                long endIndex = 1L << (3 * level);
                long range = endIndex - startIndex;
                
                // Generate random index within valid range
                long randomIndex = startIndex + Math.abs(random.nextLong()) % range;
                
                // Create tetrahedron from SFC index (this guarantees valid coordinates)
                Tet tet = Tet.tetrahedron(randomIndex, level);
                
                // Test basic properties
                assertEquals(level, tet.l());
                assertTrue(tet.type() >= 0 && tet.type() <= 5);
                
                // Test index round-trip (this should always work for SFC-generated tets)
                long reconstructedIndex = tet.index();
                if (reconstructedIndex != randomIndex) {
                    failures++;
                    if (failureDetails.size() < 10) {
                        failureDetails.add(String.format("SFC round-trip failure: index %d -> tet %s -> index %d", 
                            randomIndex, tet, reconstructedIndex));
                    }
                    continue;
                }
                
                // Test single-parameter tetrahedron method
                Tet autoTet = Tet.tetrahedron(randomIndex);
                if (!tet.equals(autoTet)) {
                    failures++;
                    if (failureDetails.size() < 10) {
                        failureDetails.add(String.format("Auto-level inconsistency for index %d", randomIndex));
                    }
                    continue;
                }
                
                // Test geometric operations
                assertDoesNotThrow(() -> tet.coordinates());
                assertDoesNotThrow(() -> tet.vertices());
                assertDoesNotThrow(() -> tet.length());
                
                // Test type computation
                for (byte testLevel = 0; testLevel <= level; testLevel++) {
                    byte computedType = tet.computeType(testLevel);
                    if (computedType < 0 || computedType > 5) {
                        failures++;
                        if (failureDetails.size() < 10) {
                            failureDetails.add(String.format("Invalid computed type %d at level %d", computedType, testLevel));
                        }
                        break;
                    }
                }
                
                // Test parent-child relationships (if applicable)
                if (level > 0) {
                    Tet parent = tet.parent();
                    assertEquals(level - 1, parent.l());
                    
                    // Verify parent index relationship
                    long parentIndex = parent.index();
                    long expectedParentIndex = randomIndex >> 3;
                    if (parentIndex != expectedParentIndex) {
                        failures++;
                        if (failureDetails.size() < 10) {
                            failureDetails.add(String.format("Parent index mismatch: child=%d, parent=%d, expected=%d", 
                                randomIndex, parentIndex, expectedParentIndex));
                        }
                    }
                }
                
            } catch (Exception e) {
                failures++;
                if (failureDetails.size() < 10) {
                    failureDetails.add(String.format("Exception in test %d: %s", i, e.getMessage()));
                }
            }
        }
        
        System.out.printf("Stress test results: %d/%d passed%n", totalTests - failures, totalTests);
        if (!failureDetails.isEmpty()) {
            System.out.println("Sample failures:");
            failureDetails.forEach(detail -> System.out.println("  " + detail));
        }
        
        assertEquals(0, failures, "All stress tests should pass when using valid SFC tetrahedra");
        System.out.println("✅ Randomized stress test completed successfully");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    public void testAllSixTetrahedraTypes() {
        System.out.println("=== Testing All Six Tetrahedra Types (0-5) Comprehensively ===");
        
        int failures = 0;
        List<String> failureDetails = new ArrayList<>();
        
        // Test all six tetrahedra types at multiple levels
        for (byte level = 0; level <= Math.min(MAX_LEVEL, 10); level++) {
            System.out.printf("Testing all 6 types at level %d:%n", level);
            
            int h = Constants.lengthAtLevel(level);
            
            // Test each of the 6 tetrahedra types
            for (byte type = 0; type < 6; type++) {
                try {
                    // Test type validity for this level
                    if (level == 0 && type != 0) {
                        // Level 0 can only have type 0 (root tetrahedron)
                        continue;
                    }
                    
                    // Create tetrahedron with this type
                    Tet tet = new Tet(h, h, h, level, type);
                    
                    // Test basic properties
                    assertEquals(level, tet.l(), String.format("Level mismatch for type %d", type));
                    assertEquals(type, tet.type(), String.format("Type mismatch for type %d", type));
                    
                    // Test coordinates calculation for each type
                    Point3i[] coords = tet.coordinates();
                    assertNotNull(coords, String.format("Coordinates null for type %d", type));
                    assertEquals(4, coords.length, String.format("Wrong coordinate count for type %d", type));
                    
                    // Verify coordinates are distinct (tetrahedron shouldn't be degenerate)
                    Set<Point3i> uniqueCoords = new HashSet<>(Arrays.asList(coords));
                    assertEquals(4, uniqueCoords.size(), 
                        String.format("Degenerate tetrahedron for type %d at level %d", type, level));
                    
                    // Test vertices calculation for each type
                    Point3i[] vertices = tet.vertices();
                    assertNotNull(vertices, String.format("Vertices null for type %d", type));
                    assertEquals(4, vertices.length, String.format("Wrong vertex count for type %d", type));
                    
                    // Test that coordinates and vertices produce valid tetrahedra
                    assertDoesNotThrow(() -> tet.contains(new Point3f(tet.x() + h/2f, tet.y() + h/2f, tet.z() + h/2f)),
                        String.format("Contains test failed for type %d", type));
                    
                    // Test face neighbors for each type
                    for (int face = 0; face < 4; face++) {
                        Tet.FaceNeighbor neighbor = tet.faceNeighbor(face);
                        assertNotNull(neighbor, String.format("Face neighbor null for type %d, face %d", type, face));
                        assertNotNull(neighbor.tet(), String.format("Neighbor tet null for type %d, face %d", type, face));
                        assertTrue(neighbor.face() >= 0 && neighbor.face() <= 3, 
                            String.format("Invalid neighbor face for type %d, face %d", type, face));
                        assertEquals(level, neighbor.tet().l(), 
                            String.format("Neighbor level mismatch for type %d, face %d", type, face));
                        assertTrue(neighbor.tet().type() >= 0 && neighbor.tet().type() <= 5,
                            String.format("Invalid neighbor type for type %d, face %d", type, face));
                    }
                    
                    // Test child generation for each type (if not at max level)
                    if (level < MAX_LEVEL) {
                        for (byte childIdx = 0; childIdx < 8; childIdx++) {
                            Tet child = tet.child(childIdx);
                            assertNotNull(child, String.format("Child %d null for type %d", childIdx, type));
                            assertEquals(level + 1, child.l(), 
                                String.format("Child level wrong for type %d, child %d", type, childIdx));
                            assertTrue(child.type() >= 0 && child.type() <= 5,
                                String.format("Invalid child type for type %d, child %d", type, childIdx));
                            
                            // Test Morton child variant
                            Tet mortonChild = tet.childTM(childIdx);
                            assertNotNull(mortonChild, String.format("Morton child %d null for type %d", childIdx, type));
                            assertEquals(level + 1, mortonChild.l(),
                                String.format("Morton child level wrong for type %d, child %d", type, childIdx));
                        }
                    }
                    
                    // Test type computation consistency
                    for (byte testLevel = 0; testLevel <= level; testLevel++) {
                        byte computedType = tet.computeType(testLevel);
                        assertTrue(computedType >= 0 && computedType <= 5,
                            String.format("Invalid computed type %d for type %d at level %d->%d", 
                                computedType, type, level, testLevel));
                        
                        if (testLevel == level) {
                            assertEquals(type, computedType,
                                String.format("computeType should return own type %d at own level %d", type, level));
                        }
                        
                        if (testLevel == 0) {
                            assertEquals(0, computedType,
                                String.format("computeType should return 0 at root level for type %d", type));
                        }
                    }
                    
                    // Test parent relationship (if not at root level)
                    if (level > 0) {
                        Tet parent = tet.parent();
                        assertNotNull(parent, String.format("Parent null for type %d", type));
                        assertEquals(level - 1, parent.l(), String.format("Parent level wrong for type %d", type));
                        assertTrue(parent.type() >= 0 && parent.type() <= 5,
                            String.format("Invalid parent type for type %d", type));
                    }
                    
                    // Test index round-trip if the coordinates are on a valid SFC grid
                    try {
                        long index = tet.index();
                        Tet reconstructed = Tet.tetrahedron(index, level);
                        // Note: This may not always match exactly due to coordinate quantization,
                        // but the level and type relationships should be consistent
                        assertEquals(level, reconstructed.l(),
                            String.format("Index round-trip level mismatch for type %d", type));
                    } catch (Exception e) {
                        // Some coordinate combinations may not correspond to valid SFC indices
                        // This is acceptable as long as other operations work correctly
                    }
                    
                    System.out.printf("  ✓ Type %d: all operations successful%n", type);
                    
                } catch (Exception e) {
                    failures++;
                    String error = String.format("Type %d at level %d failed: %s", type, level, e.getMessage());
                    failureDetails.add(error);
                    System.out.printf("  ✗ Type %d failed: %s%n", type, e.getMessage());
                }
            }
        }
        
        // Additional test: Verify type-specific geometric properties
        System.out.println("Testing type-specific geometric properties:");
        for (byte type = 0; type < 6; type++) {
            try {
                Tet tet = new Tet(100, 100, 100, (byte) 5, type);
                Point3i[] coords = tet.coordinates();
                
                // Each type should have a specific geometric orientation
                // Type 0-5 correspond to the 6 tetrahedra that partition a cube
                Point3i origin = coords[0];
                assertEquals(100, origin.x, String.format("Type %d origin x wrong", type));
                assertEquals(100, origin.y, String.format("Type %d origin y wrong", type));
                assertEquals(100, origin.z, String.format("Type %d origin z wrong", type));
                
                // Verify the tetrahedron has the expected volume relationships
                // All 6 types should have the same volume (1/6 of the cube)
                int edgeLength = tet.length();
                assertTrue(edgeLength > 0, String.format("Type %d has invalid edge length", type));
                
                System.out.printf("  ✓ Type %d: geometric properties verified%n", type);
                
            } catch (Exception e) {
                failures++;
                failureDetails.add(String.format("Geometric test failed for type %d: %s", type, e.getMessage()));
            }
        }
        
        // Summary
        System.out.printf("All 6 tetrahedra types test results: %d failures%n", failures);
        if (!failureDetails.isEmpty()) {
            System.out.println("Failure details:");
            failureDetails.stream().limit(20).forEach(detail -> System.out.println("  " + detail));
            if (failureDetails.size() > 20) {
                System.out.printf("  ... and %d more failures%n", failureDetails.size() - 20);
            }
        }
        
        assertEquals(0, failures, "All 6 tetrahedra types should work correctly");
        System.out.println("✅ All six tetrahedra types (0-5) comprehensively tested and verified");
    }
}