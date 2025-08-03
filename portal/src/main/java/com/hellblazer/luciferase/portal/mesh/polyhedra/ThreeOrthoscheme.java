package com.hellblazer.luciferase.portal.mesh.polyhedra;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.portal.mesh.Face;

import javax.vecmath.Vector3d;

import static com.hellblazer.luciferase.lucien.Constants.*;

/**
 * Represents the six Irregular Tetrahedrons that represent the characteristic of the cube
 *
 * @author hal.hildebrand
 **/
public class ThreeOrthoscheme extends Polyhedron {

    public ThreeOrthoscheme(Tet tet) {
        var vectors = tet.vertices();
        var v0 = new Vector3d(vectors[0].x, vectors[0].y, vectors[0].z);
        var v1 = new Vector3d(vectors[1].x, vectors[1].y, vectors[1].z);
        var v2 = new Vector3d(vectors[2].x, vectors[2].y, vectors[2].z);
        var v3 = new Vector3d(vectors[3].x, vectors[3].y, vectors[3].z);
        var scale = tet.length();
        v0.scale(scale);
        v1.scale(scale);
        v2.scale(scale);
        v3.scale(scale);
        addVertexPositions(v0, v1, v2, v3);

        Face f0 = new Face(3);
        Face f1 = new Face(3);
        Face f2 = new Face(3);
        Face f3 = new Face(3);
        f0.setAllVertexIndices(0, 3, 1);
        f1.setAllVertexIndices(0, 1, 2);
        f2.setAllVertexIndices(2, 1, 3);
        f3.setAllVertexIndices(0, 2, 3);

        addFaces(f0, f1, f2, f3);
        setVertexNormalsToFaceNormals();
    }

    public ThreeOrthoscheme(int simplex, double scale) {
        var vectors = SIMPLEX_STANDARD[simplex];
        var v0 = new Vector3d(vectors[0].x, vectors[0].y, vectors[0].z);
        var v1 = new Vector3d(vectors[1].x, vectors[1].y, vectors[1].z);
        var v2 = new Vector3d(vectors[2].x, vectors[2].y, vectors[2].z);
        var v3 = new Vector3d(vectors[3].x, vectors[3].y, vectors[3].z);

        v0.scale(scale);
        v1.scale(scale);
        v2.scale(scale);
        v3.scale(scale);
        addVertexPositions(v0, v1, v2, v3);

        Face f0 = new Face(3);
        Face f1 = new Face(3);
        Face f2 = new Face(3);
        Face f3 = new Face(3);
        f0.setAllVertexIndices(0, 3, 1);
        f1.setAllVertexIndices(0, 1, 2);
        f2.setAllVertexIndices(2, 1, 3);
        f3.setAllVertexIndices(0, 2, 3);

        addFaces(f0, f1, f2, f3);
        setVertexNormalsToFaceNormals();
    }
}
