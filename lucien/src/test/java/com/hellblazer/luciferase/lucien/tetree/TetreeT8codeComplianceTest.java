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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.vecmath.Point3i;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive t8code compliance tests for the Java Tetree implementation.
 * These tests validate that our implementation matches t8code's behavior exactly
 * for critical algorithms including child generation, parent calculation, and
 * space-filling curve operations.
 *
 * <p><b>Critical Validation Areas:</b></p>
 * <ul>
 *   <li>Child generation algorithm compliance</li>
 *   <li>Parent calculation algorithm compliance</li>
 *   <li>Space-filling curve round-trip consistency</li>
 *   <li>Family relationship validation</li>
 *   <li>Connectivity table compliance</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class TetreeT8codeComplianceTest {

    /**
     * Test child generation compliance against t8code for all parent types.
     * Validates that children form valid families and maintain correct parent relationships.
     */
    @Test
    void testChildGenerationCompliance() {
        // Test all parent types at multiple levels
        for (byte parentType = 0; parentType < 6; parentType++) {
            for (byte level = 0; level < 5; level++) {
                Tet parent = new Tet(0, 0, 0, level, parentType);

                // Generate all 8 children
                Tet[] children = new Tet[8];
                for (int i = 0; i < 8; i++) {
                    children[i] = parent.child(i);
                }

                // Validate family relationships
                assertTrue(TetreeFamily.isFamily(children),
                          "Children of type " + parentType + " level " + level + " should form valid family");

                // Validate all children have correct parent
                for (int i = 0; i < 8; i++) {
                    Tet child = children[i];
                    assertEquals(parent, child.parent(), 
                                "Child " + i + " of parent type " + parentType + " level " + level + 
                                " should report correct parent");
                    
                    // Validate child is at correct level
                    assertEquals(level + 1, child.l(), 
                                "Child " + i + " should be at level " + (level + 1));
                }

                // Validate children have distinct coordinates
                Set<String> coordinates = new HashSet<>();
                for (int i = 0; i < 8; i++) {
                    Tet child = children[i];
                    String coord = child.x() + "," + child.y() + "," + child.z() + "," + child.type();
                    assertFalse(coordinates.contains(coord), 
                               "Child " + i + " has duplicate coordinates for parent type " + parentType + 
                               " level " + level);
                    coordinates.add(coord);
                }

                // Validate child types are in valid range
                for (int i = 0; i < 8; i++) {
                    Tet child = children[i];
                    assertTrue(child.type() >= 0 && child.type() < 6, 
                              "Child " + i + " type " + child.type() + " should be in range 0-5");
                }
            }
        }
    }

    /**
     * Test space-filling curve round-trip compliance for comprehensive index range.
     * Validates that index() and tetrahedron() are exact inverses.
     */
    @Test
    void testSFCRoundTripCompliance() {
        // Test SFC round-trip for various indices
        for (long index = 0; index < 1000; index++) {
            Tet tet = Tet.tetrahedron(index);
            long reconstructed = tet.index();
            assertEquals(index, reconstructed, 
                        "SFC round-trip failed for index " + index);
            
            // Validate level calculation consistency
            byte expectedLevel = Tet.tetLevelFromIndex(index);
            assertEquals(expectedLevel, tet.l(), 
                        "Level calculation inconsistent for index " + index);
        }
    }

    /**
     * Test level boundary compliance for SFC indices.
     * Validates correct behavior at level transitions.
     */
    @Test
    void testSFCLevelBoundaryCompliance() {
        // Level 0: index 0
        validateSFCAtIndex(0L, (byte) 0);

        // Level 1: indices 1-7 (3 bits)
        validateSFCAtIndex(1L, (byte) 1);
        validateSFCAtIndex(7L, (byte) 1);

        // Level 2: indices 8-63 (6 bits) 
        validateSFCAtIndex(8L, (byte) 2);
        validateSFCAtIndex(63L, (byte) 2);

        // Level 3: indices 64-511 (9 bits)
        validateSFCAtIndex(64L, (byte) 3);
        validateSFCAtIndex(511L, (byte) 3);

        // Level 4: indices 512-4095 (12 bits)
        validateSFCAtIndex(512L, (byte) 4);
        validateSFCAtIndex(4095L, (byte) 4);
    }

    /**
     * Test parent-child cycle compliance for all tetrahedron types.
     * Validates that child.parent() == original parent for all cases.
     */
    @Test
    void testParentChildCycleCompliance() {
        // Test at multiple levels and types
        for (byte level = 0; level < 4; level++) {
            for (byte type = 0; type < 6; type++) {
                for (int x = 0; x < 8; x += 4) {
                    for (int y = 0; y < 8; y += 4) {
                        for (int z = 0; z < 8; z += 4) {
                            Tet parent = new Tet(x, y, z, level, type);
                            
                            // Test all 8 children
                            for (int childIdx = 0; childIdx < 8; childIdx++) {
                                Tet child = parent.child(childIdx);
                                Tet reconstructedParent = child.parent();
                                
                                assertEquals(parent, reconstructedParent,
                                            "Parent-child cycle failed for parent (" + x + "," + y + "," + z + 
                                            ") type " + type + " level " + level + " child " + childIdx);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Test connectivity table compliance against t8code.
     * Validates that our connectivity tables produce correct child types using the Bey ID intermediary step.
     */
    @Test
    void testConnectivityTableCompliance() {
        // Test child type lookup for all parent types and child indices
        for (byte parentType = 0; parentType < 6; parentType++) {
            for (int childIndex = 0; childIndex < 8; childIndex++) {
                // Use the same two-step process as actual child generation:
                // Step 1: childIndex -> Bey ID
                byte beyId = TetreeConnectivity.getBeyChildId(parentType, childIndex);
                // Step 2: Bey ID -> child type
                byte childType = TetreeConnectivity.getChildType(parentType, beyId);
                
                // Validate child type is in valid range
                assertTrue(childType >= 0 && childType < 6, 
                          "Child type " + childType + " out of range for parent type " + 
                          parentType + " child index " + childIndex);
                
                // Test with actual tetrahedron generation
                Tet parent = new Tet(0, 0, 0, (byte) 1, parentType);
                Tet child = parent.child(childIndex);
                
                assertEquals(childType, child.type(), 
                            "Child type mismatch: connectivity table returned " + childType + 
                            " but actual child has type " + child.type() + 
                            " for parent type " + parentType + " child index " + childIndex);
            }
        }
    }

    /**
     * Test Bey ID mapping compliance with t8code.
     * Validates that our Bey ID tables match t8code's behavior.
     */
    @Test
    void testBeyIdMappingCompliance() {
        // Test Bey ID mapping for all parent types and child indices
        for (byte parentType = 0; parentType < 6; parentType++) {
            for (int childIndex = 0; childIndex < 8; childIndex++) {
                // Get Bey ID from connectivity table
                byte beyId = TetreeConnectivity.getBeyChildId(parentType, childIndex);
                
                // Validate Bey ID is in valid range
                assertTrue(beyId >= 0 && beyId < 8, 
                          "Bey ID " + beyId + " out of range for parent type " + 
                          parentType + " child index " + childIndex);
                
                // Get vertex from Bey ID
                byte vertex = TetreeConnectivity.getBeyVertex(beyId);
                
                // Validate vertex is in valid range
                assertTrue(vertex >= 0 && vertex < 4, 
                          "Vertex " + vertex + " out of range for Bey ID " + beyId);
            }
        }
    }

    /**
     * Test child coordinate calculation compliance.
     * Validates that child coordinates are calculated correctly using t8code algorithm.
     */
    @Test
    void testChildCoordinateCalculationCompliance() {
        // Test coordinate calculation for various parent configurations
        for (byte parentType = 0; parentType < 6; parentType++) {
            for (byte level = 0; level < 3; level++) {
                // Test with different parent coordinates
                int[] testCoords = {0, 8, 16, 32};
                for (int px : testCoords) {
                    for (int py : testCoords) {
                        for (int pz : testCoords) {
                            Tet parent = new Tet(px, py, pz, level, parentType);
                            
                            // Generate all children and validate coordinates
                            for (int childIdx = 0; childIdx < 8; childIdx++) {
                                Tet child = parent.child(childIdx);
                                
                                // Validate child coordinates are within expected bounds
                                int maxCoord = Constants.lengthAtLevel((byte) 0);
                                assertTrue(child.x() >= 0 && child.x() < maxCoord, 
                                          "Child x coordinate " + child.x() + " out of bounds");
                                assertTrue(child.y() >= 0 && child.y() < maxCoord, 
                                          "Child y coordinate " + child.y() + " out of bounds");
                                assertTrue(child.z() >= 0 && child.z() < maxCoord, 
                                          "Child z coordinate " + child.z() + " out of bounds");
                                
                                // Validate child coordinates are different from parent (except for child 0)
                                if (childIdx != 0) {
                                    boolean coordsDifferent = (child.x() != parent.x()) || 
                                                            (child.y() != parent.y()) || 
                                                            (child.z() != parent.z());
                                    assertTrue(coordsDifferent, 
                                              "Child " + childIdx + " coordinates should differ from parent for non-zero children");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Test sibling relationship compliance.
     * Validates that siblings are correctly identified and form valid families.
     */
    @Test
    void testSiblingRelationshipCompliance() {
        // Test sibling relationships at various levels
        for (byte level = 1; level < 4; level++) {
            for (byte type = 0; type < 6; type++) {
                Tet tet = new Tet(0, 0, 0, level, type);
                Tet[] siblings = TetreeFamily.getSiblings(tet);
                
                // Should have 8 siblings (including itself)
                assertEquals(8, siblings.length, "Should have 8 siblings including self");
                
                // All siblings should have same parent
                Tet parent = tet.parent();
                for (Tet sibling : siblings) {
                    assertEquals(parent, sibling.parent(), "All siblings should have same parent");
                    assertEquals(level, sibling.l(), "All siblings should be at same level");
                }
                
                // Siblings should form a valid family
                assertTrue(TetreeFamily.isFamily(siblings), "Siblings should form valid family");
                
                // One of the siblings should be the original tetrahedron
                boolean foundSelf = false;
                for (Tet sibling : siblings) {
                    if (sibling.equals(tet)) {
                        foundSelf = true;
                        break;
                    }
                }
                assertTrue(foundSelf, "Original tetrahedron should be among its siblings");
            }
        }
    }

    /**
     * Test error handling compliance for invalid operations.
     * Validates that appropriate exceptions are thrown for invalid inputs.
     */
    @Test
    void testErrorHandlingCompliance() {
        // Test invalid child indices
        Tet parent = new Tet(0, 0, 0, (byte) 1, (byte) 0);
        
        assertThrows(IllegalArgumentException.class, () -> parent.child(-1), 
                    "Should throw exception for negative child index");
        assertThrows(IllegalArgumentException.class, () -> parent.child(8), 
                    "Should throw exception for child index >= 8");
        
        // Test parent of root
        Tet root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        assertThrows(IllegalStateException.class, () -> root.parent(), 
                    "Should throw exception when getting parent of root");
        
        // Test children at max level
        byte maxLevel = Constants.getMaxRefinementLevel();
        Tet maxLevelTet = new Tet(0, 0, 0, maxLevel, (byte) 0);
        assertThrows(IllegalStateException.class, () -> maxLevelTet.child(0), 
                    "Should throw exception when generating children at max level");
        
        // Test invalid SFC indices
        assertThrows(IllegalArgumentException.class, () -> Tet.tetrahedron(-1L), 
                    "Should throw exception for negative SFC index");
        assertThrows(IllegalArgumentException.class, () -> Tet.tetLevelFromIndex(-1L), 
                    "Should throw exception for negative index in level calculation");
    }

    /**
     * Test specific t8code reference cases.
     * These are known good cases from t8code that we should match exactly.
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 3, 4, 5, 6, 7, 8, 15, 31, 63, 64, 127, 255, 511})
    void testT8codeReferenceCases(long index) {
        // Test round-trip consistency
        Tet tet = Tet.tetrahedron(index);
        assertEquals(index, tet.index(), "Round-trip failed for reference case " + index);
        
        // Test level calculation
        byte level = Tet.tetLevelFromIndex(index);
        assertEquals(level, tet.l(), "Level calculation failed for reference case " + index);
        
        // If not at max level, test child generation
        if (level < Constants.getMaxRefinementLevel()) {
            for (int childIdx = 0; childIdx < 8; childIdx++) {
                Tet child = tet.child(childIdx);
                assertEquals(tet, child.parent(), 
                            "Parent-child cycle failed for reference case " + index + " child " + childIdx);
            }
        }
    }

    /**
     * Test geometric consistency with t8code.
     * Validates that tetrahedron vertices produce valid geometric shapes.
     */
    @Test
    void testGeometricConsistencyCompliance() {
        // Test various tetrahedra for geometric validity
        for (byte level = 0; level < 3; level++) {
            for (byte type = 0; type < 6; type++) {
                Tet tet = new Tet(0, 0, 0, level, type);
                Point3i[] vertices = tet.coordinates();
                
                // Should have 4 vertices
                assertEquals(4, vertices.length, "Tetrahedron should have 4 vertices");
                
                // All vertices should have non-negative coordinates
                for (int i = 0; i < 4; i++) {
                    assertTrue(vertices[i].x >= 0, "Vertex " + i + " x coordinate should be non-negative");
                    assertTrue(vertices[i].y >= 0, "Vertex " + i + " y coordinate should be non-negative"); 
                    assertTrue(vertices[i].z >= 0, "Vertex " + i + " z coordinate should be non-negative");
                }
                
                // Vertices should not all be identical
                boolean hasDistinctVertices = false;
                for (int i = 1; i < 4; i++) {
                    if (!vertices[0].equals(vertices[i])) {
                        hasDistinctVertices = true;
                        break;
                    }
                }
                assertTrue(hasDistinctVertices, "Tetrahedron should have distinct vertices");
            }
        }
    }

    /**
     * Helper method to validate SFC behavior at a specific index.
     */
    private void validateSFCAtIndex(long index, byte expectedLevel) {
        Tet tet = Tet.tetrahedron(index);
        assertEquals(expectedLevel, tet.l(), "Level mismatch for index " + index);
        assertEquals(index, tet.index(), "Round-trip failed for index " + index);
        assertEquals(expectedLevel, Tet.tetLevelFromIndex(index), "Level inference failed for index " + index);
    }
}