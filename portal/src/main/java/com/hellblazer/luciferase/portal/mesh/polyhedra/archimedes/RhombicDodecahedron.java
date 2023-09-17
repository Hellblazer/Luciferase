/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.mesh.polyhedra.archimedes;

import javax.vecmath.Vector3d;

import com.hellblazer.luciferase.portal.mesh.Face;

import javafx.scene.shape.TriangleMesh;

/**
 * @author hal.hildebrand
 */
public class RhombicDodecahedron extends ArchimedeanSolid {

    public static TriangleMesh createRhombicDodecahedron(double height) {

        TriangleMesh mesh = new TriangleMesh();

        float r = (float) height / 2;
        float s = (float) height / 4;

        mesh.getPoints()
            .addAll(
                    // in ascending z order and counter clockwise from the x axis as 0degrees
                    0, 0, -r, // A0 -z axis
                    s, -s, -s, // B1
                    -s, -s, -s, // C2
                    -s, s, -s, // D3
                    s, s, -s, // E4
                    r, 0, 0, // F5 +x axis
                    0, r, 0, // G6 +y axis
                    -r, 0, 0, // H7 -x axis
                    0, -r, 0, // I8 -y axis
                    s, -s, s, // J9
                    -s, -s, s, // K10
                    -s, s, s, // L11
                    s, s, s, // M12
                    0, 0, r // N13 +z axis

            );

        mesh.getTexCoords().addAll(0, 0);

        // A rhombic dodecahedron can be modeled as six pyramids, one on each face of a
        // cube
        // each face of a pyramid is half of a face of the rhombic dodecahedron
        mesh.getFaces()
            .addAll(0, 0, 1, 0, 2, 0, // ABC
                    0, 0, 2, 0, 3, 0, // ACD
                    0, 0, 3, 0, 4, 0, // ADE
                    0, 0, 4, 0, 1, 0, // AEB

                    5, 0, 1, 0, 4, 0, // FBE
                    5, 0, 4, 0, 12, 0, // FEM
                    5, 0, 12, 0, 9, 0, // FMJ
                    5, 0, 9, 0, 1, 0, // FJB

                    6, 0, 4, 0, 3, 0, // G
                    6, 0, 3, 0, 11, 0, // G
                    6, 0, 11, 0, 12, 0, // G
                    6, 0, 12, 0, 4, 0, // G

                    7, 0, 3, 0, 2, 0, // H
                    7, 0, 2, 0, 10, 0, // H
                    7, 0, 10, 0, 11, 0, // H
                    7, 0, 11, 0, 3, 0, // H

                    8, 0, 1, 0, 9, 0, // I
                    8, 0, 9, 0, 10, 0, // I
                    8, 0, 10, 0, 2, 0, // I
                    8, 0, 2, 0, 1, 0, // I

                    13, 0, 9, 0, 12, 0, // N
                    13, 0, 12, 0, 11, 0, // N
                    13, 0, 11, 0, 10, 0, // N
                    13, 0, 10, 0, 9, 0 // N
            );
        return mesh;
    }

    /**
     * Construct a rhombic dodecahedron mesh centered at the origin with the
     * specified edge length.
     *
     * @param edgeLength The length of each edge of this mesh.
     */
    public RhombicDodecahedron(double edgeLength) {
        float r = (float) edgeLength / 2;
        float s = (float) edgeLength / 4;

        addVertexPositions(new Vector3d(0, 0, -r), // A0 -z axis
                           new Vector3d(s, -s, -s), // B1
                           new Vector3d(-s, -s, -s), // C2
                           new Vector3d(-s, s, -s), // D3
                           new Vector3d(s, s, -s), // E4
                           new Vector3d(r, 0, 0), // F5 +x axis
                           new Vector3d(0, r, 0), // G6 +y axis
                           new Vector3d(-r, 0, 0), // H7 -x axis
                           new Vector3d(0, -r, 0), // I8 -y axis
                           new Vector3d(s, -s, s), // J9
                           new Vector3d(-s, -s, s), // K10
                           new Vector3d(-s, s, s), // L11
                           new Vector3d(s, s, s), // M12
                           new Vector3d(0, 0, r) // N13 +z axis
        );
        addFaces(new Face(4).setAllVertexIndices(0, 0, 1, 0, 2, 0), // ABC
                 new Face(4).setAllVertexIndices(0, 0, 2, 0, 3, 0), // ACD
                 new Face(4).setAllVertexIndices(0, 0, 3, 0, 4, 0), // ADE
                 new Face(4).setAllVertexIndices(0, 0, 4, 0, 1, 0), // AEB

                 new Face(4).setAllVertexIndices(5, 0, 1, 0, 4, 0), // FBE
                 new Face(4).setAllVertexIndices(5, 0, 4, 0, 12, 0), // FEM
                 new Face(4).setAllVertexIndices(5, 0, 12, 0, 9, 0), // FMJ
                 new Face(4).setAllVertexIndices(5, 0, 9, 0, 1, 0), // FJB

                 new Face(4).setAllVertexIndices(6, 0, 4, 0, 3, 0), // G
                 new Face(4).setAllVertexIndices(6, 0, 3, 0, 11, 0), // G
                 new Face(4).setAllVertexIndices(6, 0, 11, 0, 12, 0), // G
                 new Face(4).setAllVertexIndices(6, 0, 12, 0, 4, 0), // G

                 new Face(4).setAllVertexIndices(7, 0, 3, 0, 2, 0), // H
                 new Face(4).setAllVertexIndices(7, 0, 2, 0, 10, 0), // H
                 new Face(4).setAllVertexIndices(7, 0, 10, 0, 11, 0), // H
                 new Face(4).setAllVertexIndices(7, 0, 11, 0, 3, 0), // H

                 new Face(4).setAllVertexIndices(8, 0, 1, 0, 9, 0), // I
                 new Face(4).setAllVertexIndices(8, 0, 9, 0, 10, 0), // I
                 new Face(4).setAllVertexIndices(8, 0, 10, 0, 2, 0), // I
                 new Face(4).setAllVertexIndices(8, 0, 2, 0, 1, 0), // I

                 new Face(4).setAllVertexIndices(13, 0, 9, 0, 12, 0), // N
                 new Face(4).setAllVertexIndices(13, 0, 12, 0, 11, 0), // N
                 new Face(4).setAllVertexIndices(13, 0, 11, 0, 10, 0), // N
                 new Face(4).setAllVertexIndices(13, 0, 10, 0, 9, 0) // N
        );
        toTriangleMesh();
//      setVertexNormalsToFaceNormals();
//        addVertexNormals(cubeAmbo.getVertexNormals());
//        addFaces(cubeAmbo.getFaces());
//        setVertexNormalsToFaceNormals();
    }

}
