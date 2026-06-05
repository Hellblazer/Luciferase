package com.hellblazer.luciferase.portal.mesh.polyhedra;

import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Tetrahedron;
import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3d;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the Polyhedron copy constructor (and clone()) correctly copies source data
 * instead of iterating the newly-constructed (empty) instance.
 */
class PolyhedronCopyConstructorTest {

    /** Build a small but non-trivial source polyhedron (tetrahedron: 4 verts, 4 faces, 4 normals). */
    private Tetrahedron buildSource() {
        return new Tetrahedron(1.0);
    }

    @Test
    void copyConstructorCountsMatchSource() {
        Tetrahedron src = buildSource();
        Polyhedron copy = new Polyhedron(src);

        assertEquals(src.getVertexPositions().size(), copy.getVertexPositions().size(),
                     "vertex position count must match source");
        assertEquals(src.getVertexNormals().size(), copy.getVertexNormals().size(),
                     "vertex normal count must match source");
        assertEquals(src.getFaces().size(), copy.getFaces().size(),
                     "face count must match source");

        // Sanity: the source must be non-empty (guards against a hollow source)
        assertTrue(src.getVertexPositions().size() > 0, "source must have vertices");
        assertTrue(src.getFaces().size() > 0, "source must have faces");
    }

    @Test
    void copyConstructorDeepCopiesVertexPositions() {
        Tetrahedron src = buildSource();
        Polyhedron copy = new Polyhedron(src);

        // Mutate the first vertex of the copy
        Vector3d copyVertex = copy.getVertexPositions().get(0);
        double originalX = src.getVertexPositions().get(0).x;
        copyVertex.x += 999.0;

        // Source must be unaffected
        assertEquals(originalX, src.getVertexPositions().get(0).x, 1e-10,
                     "mutating copy vertex must not affect source");
    }

    @Test
    void cloneDelegatesToCopyConstructor() {
        Tetrahedron src = buildSource();
        Polyhedron cloned = src.clone();

        assertEquals(src.getVertexPositions().size(), cloned.getVertexPositions().size(),
                     "cloned vertex position count must match source");
        assertEquals(src.getFaces().size(), cloned.getFaces().size(),
                     "cloned face count must match source");

        // Independence check
        Vector3d cloneVertex = cloned.getVertexPositions().get(0);
        double originalX = src.getVertexPositions().get(0).x;
        cloneVertex.x += 999.0;
        assertEquals(originalX, src.getVertexPositions().get(0).x, 1e-10,
                     "mutating clone vertex must not affect source");
    }
}
