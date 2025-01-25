package com.hellblazer.luciferase.portal.mesh.polyhedra;

import com.hellblazer.luciferase.portal.mesh.Face;

import javax.vecmath.Vector3d;

/**
 * @author hal.hildebrand
 **/
public class QuadrirectangularTetrahedron extends Polyhedron {

    /** The Tetrahedrons in Bey's order */
    public static final Vector3d[][] SIMPLEX = new Vector3d[][] {
    { CORNER.c0.coords(), CORNER.c1.coords(), CORNER.c5.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c1.coords(), CORNER.c3.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c2.coords(), CORNER.c3.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c2.coords(), CORNER.c6.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c4.coords(), CORNER.c6.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c4.coords(), CORNER.c5.coords(), CORNER.c7.coords() } };

    public QuadrirectangularTetrahedron(int simplex, double scale) {
        var vectors = SIMPLEX[simplex];
        var v0 = new Vector3d(vectors[0]);
        var v1 = new Vector3d(vectors[1]);
        var v2 = new Vector3d(vectors[2]);
        var v3 = new Vector3d(vectors[3]);

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

    // The corners of a cube
    public enum CORNER {
        c0 {
            @Override
            public Vector3d coords() {
                return new Vector3d(0, 0, 0);
            }
        }, c1 {
            @Override
            public Vector3d coords() {
                return new Vector3d(1, 0, 0);
            }
        }, c2 {
            @Override
            public Vector3d coords() {
                return new Vector3d(0, 1, 0);
            }
        }, c3 {
            @Override
            public Vector3d coords() {
                return new Vector3d(1, 1, 0);
            }
        }, c4 {
            @Override
            public Vector3d coords() {
                return new Vector3d(0, 0, 1);
            }
        }, c5 {
            @Override
            public Vector3d coords() {
                return new Vector3d(1, 0, 1);
            }
        }, c6 {
            @Override
            public Vector3d coords() {
                return new Vector3d(0, 1, 1);
            }
        }, c7 {
            @Override
            public Vector3d coords() {
                return new Vector3d(1, 1, 1);
            }
        };

        abstract public Vector3d coords();
    }
}
