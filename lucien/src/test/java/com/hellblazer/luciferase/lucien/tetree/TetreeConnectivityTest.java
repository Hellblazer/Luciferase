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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TetreeConnectivity tables.
 * Validates the precomputed lookup tables match expected Bey refinement properties.
 * 
 * @author hal.hildebrand
 */
public class TetreeConnectivityTest {
    
    @Test
    public void testTableDimensions() {
        // Verify all tables have correct dimensions
        assertEquals(6, TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE.length);
        assertEquals(8, TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[0].length);
        
        assertEquals(6, TetreeConnectivity.FACE_CORNERS.length);
        assertEquals(4, TetreeConnectivity.FACE_CORNERS[0].length);
        assertEquals(3, TetreeConnectivity.FACE_CORNERS[0][0].length);
        
        assertEquals(6, TetreeConnectivity.CHILDREN_AT_FACE.length);
        assertEquals(4, TetreeConnectivity.CHILDREN_AT_FACE[0].length);
        assertEquals(4, TetreeConnectivity.CHILDREN_AT_FACE[0][0].length);
        
        assertEquals(6, TetreeConnectivity.FACE_CHILD_FACE.length);
        assertEquals(8, TetreeConnectivity.FACE_CHILD_FACE[0].length);
        assertEquals(4, TetreeConnectivity.FACE_CHILD_FACE[0][0].length);
        
        assertEquals(8, TetreeConnectivity.ARE_SIBLINGS.length);
        assertEquals(8, TetreeConnectivity.ARE_SIBLINGS[0].length);
    }
    
    @Test
    public void testChildTypes() {
        // Test that child types are valid (0-5)
        for (byte parentType = 0; parentType < 6; parentType++) {
            for (int childIndex = 0; childIndex < 8; childIndex++) {
                byte childType = TetreeConnectivity.getChildType(parentType, childIndex);
                assertTrue(childType >= 0 && childType < 6, 
                    "Invalid child type " + childType + " for parent " + parentType + " child " + childIndex);
            }
        }
        
        // Test specific known relationships from t8code
        // Type 0 parent: first 4 children are type 0, then 4,5,2,1
        assertEquals(0, TetreeConnectivity.getChildType((byte)0, 0));
        assertEquals(0, TetreeConnectivity.getChildType((byte)0, 1));
        assertEquals(0, TetreeConnectivity.getChildType((byte)0, 2));
        assertEquals(0, TetreeConnectivity.getChildType((byte)0, 3));
        assertEquals(4, TetreeConnectivity.getChildType((byte)0, 4));
        assertEquals(5, TetreeConnectivity.getChildType((byte)0, 5));
        assertEquals(2, TetreeConnectivity.getChildType((byte)0, 6));
        assertEquals(1, TetreeConnectivity.getChildType((byte)0, 7));
    }
    
    @Test
    public void testFaceCorners() {
        // Verify face corners are valid vertex indices (0-3)
        for (byte tetType = 0; tetType < 6; tetType++) {
            for (int face = 0; face < 4; face++) {
                byte[] corners = TetreeConnectivity.getFaceCorners(tetType, face);
                assertEquals(3, corners.length, "Face should have 3 corners");
                
                for (byte corner : corners) {
                    assertTrue(corner >= 0 && corner < 4, 
                        "Invalid corner index " + corner);
                }
                
                // Verify corners are unique
                assertTrue(corners[0] != corners[1] && corners[1] != corners[2] && corners[0] != corners[2],
                    "Face corners must be unique");
            }
        }
        
        // Test that each face excludes exactly one vertex
        for (byte tetType = 0; tetType < 6; tetType++) {
            boolean[] vertexUsed = new boolean[4];
            for (int face = 0; face < 4; face++) {
                byte[] corners = TetreeConnectivity.getFaceCorners(tetType, face);
                // The face opposite vertex i should not contain vertex i
                for (byte corner : corners) {
                    assertNotEquals(face, corner, 
                        "Face " + face + " should not contain vertex " + face);
                }
            }
        }
    }
    
    @Test
    public void testChildrenAtFace() {
        // Verify children at face are valid indices
        for (byte parentType = 0; parentType < 6; parentType++) {
            for (int face = 0; face < 4; face++) {
                byte[] children = TetreeConnectivity.getChildrenAtFace(parentType, face);
                assertEquals(4, children.length, "Should have 4 children per face");
                
                for (byte child : children) {
                    assertTrue(child >= 0 && child < 8, 
                        "Invalid child index " + child);
                }
            }
        }
        
        // Test that each child appears at some face
        for (byte parentType = 0; parentType < 6; parentType++) {
            boolean[] childAtSomeFace = new boolean[8];
            for (int face = 0; face < 4; face++) {
                byte[] children = TetreeConnectivity.getChildrenAtFace(parentType, face);
                for (byte child : children) {
                    childAtSomeFace[child] = true;
                }
            }
            
            // Child 0 is interior, so it shouldn't be at any face
            assertFalse(childAtSomeFace[0], "Child 0 should be interior");
            
            // All other children should be at some face
            for (int i = 1; i < 8; i++) {
                assertTrue(childAtSomeFace[i], "Child " + i + " should be at some face");
            }
        }
    }
    
    @Test
    public void testFaceChildFace() {
        // Verify face mappings are consistent
        for (byte parentType = 0; parentType < 6; parentType++) {
            for (int childIndex = 0; childIndex < 8; childIndex++) {
                for (int parentFace = 0; parentFace < 4; parentFace++) {
                    byte childFace = TetreeConnectivity.getChildFace(parentType, childIndex, parentFace);
                    
                    // Valid values are -1 (not at face) or 0-3 (face index)
                    assertTrue(childFace == -1 || (childFace >= 0 && childFace < 4),
                        "Invalid child face " + childFace);
                    
                    // If child is at parent face, verify consistency
                    if (childFace != -1) {
                        byte[] childrenAtFace = TetreeConnectivity.getChildrenAtFace(parentType, parentFace);
                        boolean foundChild = false;
                        for (byte child : childrenAtFace) {
                            if (child == childIndex) {
                                foundChild = true;
                                break;
                            }
                        }
                        assertTrue(foundChild, 
                            "Child " + childIndex + " has face mapping but not in children-at-face list");
                    }
                }
            }
        }
        
        // Test interior child (index 0) has no face mappings
        for (byte parentType = 0; parentType < 6; parentType++) {
            for (int parentFace = 0; parentFace < 4; parentFace++) {
                assertEquals(-1, TetreeConnectivity.getChildFace(parentType, 0, parentFace),
                    "Interior child should not touch any parent face");
            }
        }
    }
    
    @Test
    public void testSiblingRelationships() {
        // All children should be siblings of each other
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                assertTrue(TetreeConnectivity.areSiblings(i, j),
                    "All children of same parent should be siblings");
            }
        }
    }
    
    @Test
    public void testFaceNeighborTypes() {
        // Verify face neighbor types are valid
        for (byte tetType = 0; tetType < 6; tetType++) {
            for (int face = 0; face < 4; face++) {
                byte neighborType = TetreeConnectivity.getFaceNeighborType(tetType, face);
                assertTrue(neighborType >= 0 && neighborType < 6,
                    "Invalid neighbor type " + neighborType);
            }
        }
        
        // Test symmetry: if A's neighbor across face F is type B,
        // then B's neighbor across the corresponding face should map back
        // (This is a simplified test - full validation would need face correspondence)
        for (byte typeA = 0; typeA < 6; typeA++) {
            for (int faceA = 0; faceA < 4; faceA++) {
                byte typeB = TetreeConnectivity.getFaceNeighborType(typeA, faceA);
                
                // Find if there's a face on B that maps back to A
                boolean foundReverse = false;
                for (int faceB = 0; faceB < 4; faceB++) {
                    if (TetreeConnectivity.getFaceNeighborType(typeB, faceB) == typeA) {
                        foundReverse = true;
                        break;
                    }
                }
                assertTrue(foundReverse,
                    "Should find reverse mapping from type " + typeB + " back to type " + typeA);
            }
        }
    }
    
    @Test
    public void testChildVertexMapping() {
        // Verify child vertex mappings have correct dimensions and valid values
        assertEquals(8, TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX.length);
        
        for (int child = 0; child < 8; child++) {
            assertEquals(4, TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX[child].length,
                "Each child should have 4 vertices");
            
            for (int vertex = 0; vertex < 4; vertex++) {
                byte parentRef = TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX[child][vertex];
                // Valid values: 0-3 (parent vertices), 4-9 (edge midpoints), 10 (center)
                assertTrue(parentRef >= 0 && parentRef <= 10,
                    "Invalid parent reference point " + parentRef);
            }
        }
        
        // Test that corner children (1-4) have one parent vertex
        for (int child = 1; child <= 4; child++) {
            boolean hasParentVertex = false;
            for (int vertex = 0; vertex < 4; vertex++) {
                byte parentRef = TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX[child][vertex];
                if (parentRef >= 0 && parentRef < 4) {
                    hasParentVertex = true;
                    break;
                }
            }
            assertTrue(hasParentVertex,
                "Corner child " + child + " should have at least one parent vertex");
        }
    }
    
    @Test
    public void testBeyRefinementProperties() {
        // Test key properties of Bey refinement scheme
        
        // 1. Each parent produces exactly 8 children
        assertEquals(8, TetreeConnectivity.CHILDREN_PER_TET);
        
        // 2. Each tetrahedron has exactly 4 faces
        assertEquals(4, TetreeConnectivity.FACES_PER_TET);
        
        // 3. Interior child (0) touches no parent faces
        for (byte parentType = 0; parentType < 6; parentType++) {
            for (int face = 0; face < 4; face++) {
                byte[] childrenAtFace = TetreeConnectivity.getChildrenAtFace(parentType, face);
                for (byte child : childrenAtFace) {
                    assertNotEquals(0, child, "Interior child should not be at any face");
                }
            }
        }
        
        // 4. Each face has exactly 4 children touching it
        for (byte parentType = 0; parentType < 6; parentType++) {
            for (int face = 0; face < 4; face++) {
                byte[] childrenAtFace = TetreeConnectivity.getChildrenAtFace(parentType, face);
                int validChildren = 0;
                for (byte child : childrenAtFace) {
                    if (child >= 0 && child < 8) {
                        validChildren++;
                    }
                }
                assertEquals(4, validChildren, "Each face should have exactly 4 children");
            }
        }
    }
}