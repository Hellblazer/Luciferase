package com.hellblazer.luciferase.portal.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Face.setAllNormalIndices — verifies the self-copy no-op bug is fixed
 * (source == dest in arraycopy was a no-op; fix qualifies dest as this.normalIndices).
 */
class FaceTest {

    @Test
    void setAllNormalIndices_writesFieldNotParam() {
        Face face = new Face(3);
        face.setAllNormalIndices(10, 20, 30);
        assertArrayEquals(new int[]{10, 20, 30}, face.getNormalIndices(),
                          "normalIndices field must contain the supplied values");
    }

    @Test
    void setAllNormalIndices_copySemantics_mutatingInputDoesNotAffectFace() {
        Face face = new Face(3);
        int[] input = {5, 6, 7};
        face.setAllNormalIndices(input);
        // Mutate the vararg array after the call
        input[0] = 99;
        assertArrayEquals(new int[]{5, 6, 7}, face.getNormalIndices(),
                          "Face should hold an independent copy; mutating input must not change face");
    }

    @Test
    void divideIntoTriangles_propagatesNormalIndices() {
        // A quad (4 vertices) — divideIntoTriangles uses setAllNormalIndices internally
        Face quad = new Face(4);
        quad.setAllVertexIndices(0, 1, 2, 3);
        quad.setAllNormalIndices(10, 20, 30, 40);

        Face[] triangles = quad.divideIntoTriangles();
        assertEquals(2, triangles.length);

        // Triangle 0: vertices [v0,v1,v3] -> normals [n0,n1,n3]
        assertArrayEquals(new int[]{10, 20, 40}, triangles[0].getNormalIndices(),
                          "First triangle must carry correct normal indices");
        // Triangle 1: vertices [v1,v2,v3] -> normals [n1,n2,n3]
        assertArrayEquals(new int[]{20, 30, 40}, triangles[1].getNormalIndices(),
                          "Second triangle must carry correct normal indices");
    }

    @Test
    void toOBJString_usesSetNormalIndices() {
        Face face = new Face(3);
        face.setAllVertexIndices(0, 1, 2);
        face.setAllNormalIndices(4, 5, 6);

        String obj = face.toOBJString();
        // OBJ format: "f vi//ni ..." with 1-based indices
        assertTrue(obj.contains("1//5"), "Vertex 0 (1-based=1) with normal 4 (1-based=5)");
        assertTrue(obj.contains("2//6"), "Vertex 1 (1-based=2) with normal 5 (1-based=6)");
        assertTrue(obj.contains("3//7"), "Vertex 2 (1-based=3) with normal 6 (1-based=7)");
    }
}
