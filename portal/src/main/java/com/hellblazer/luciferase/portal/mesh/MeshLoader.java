/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.portal.mesh;

import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;

/**
 *
 */
public class MeshLoader {
    /**
     * error()
     *
     * Display an error message for a loading issue.
     *
     * @param mthd The method the failure happened in.
     * @param msg  The message to be displayed as a result.
     **/
    private static void error(String mthd, String msg) {
        System.out.println(System.currentTimeMillis() + " [!!] Loader->" + mthd + "() " + msg);
    }

    /**
     * loadObj()
     *
     * Loads an OBJ file from disk and convert it to a mesh.
     *
     * @param path The file path to load the OBJ from.
     * @return The mesh of the selected file.
     **/
    public static MeshView loadObj(String path) {
        TriangleMesh mesh = new TriangleMesh(VertexFormat.POINT_NORMAL_TEXCOORD);
        ArrayList<String> lines = readTextFile(path);
        for (int x = 0; x < lines.size(); x++) {
            String line = lines.get(x);
            if (line != null) {
                line = line.trim();
                if (line.length() < 2) {
                    warning("loadObj", "Not enough data to parse line " + x);
                    continue;
                }
                switch (line.charAt(0)) {
                    /* Comment */
                    case '#':
                        /* Ignore comments */
                        break;
                    /* Polygonal face element */
                    case 'f':
                        String[] faces = line.replace("f", "").trim().split(" ");
                        for (int y = 0; y < faces.length; y++) {
                            String[] temp = faces[y].split("/");
                            /* NOTE: Java loads this in the wrong order. */
                            mesh.getFaces().addAll(Integer.parseInt(temp[0]) - 1);
                            mesh.getFaces().addAll(Integer.parseInt(temp[2]) - 1);
                            mesh.getFaces().addAll(Integer.parseInt(temp[1]) - 1);
                        }
                        break;
                    /* Group */
                    case 'g':
                        warning("loadObj", "Cannot handle group on line " + x);
                        break;
                    /* Line element */
                    case 'l':
                        warning("loadObj", "Cannot handle line on line " + x);
                        break;
                    /* Object */
                    case 'o':
                        warning("loadObj", "Cannot handle object on line " + x);
                        break;
                    /* Smoothing */
                    case 's':
                        warning("loadObj", "Cannot handle smoothing on line " + x);
                        break;
                    case 'v':
                        switch (line.charAt(1)) {
                            /* List of geometric vertices, with (x,y,z[,w]) coordinates */
                            case ' ':
                                String[] verts = line.replace("v", "").trim().split(" ");
                                for (int y = 0; y < verts.length; y++) {
                                    mesh.getPoints().addAll(Float.parseFloat(verts[y]));
                                }
                                break;
                            /* List of texture coordinates, in (u, v [,w]) coordinates */
                            case 't':
                                String[] texts = line.replace("vt", "").trim().split(" ");
                                for (int y = 0; y < texts.length; y++) {
                                    mesh.getTexCoords().addAll(Float.parseFloat(texts[y]));
                                }
                                break;
                            /* List of vertex normals in (x,y,z) form */
                            case 'n':
                                String[] norms = line.replace("vn", "").trim().split(" ");
                                for (int y = 0; y < norms.length; y++) {
                                    mesh.getNormals().addAll(Float.parseFloat(norms[y]));
                                }
                                break;
                            /* Parameter space vertices in ( u [,v] [,w] ) form */
                            case 'p':
                                warning("loadObj", "Cannot handle vertices on line " + x);
                                break;
                            default:
                                warning("loadObj", "Bad vertex `" + line.charAt(1) + "`:" + x);
                                break;
                        }
                        break;
                    default:
                        warning("loadObj", "Bad command `" + line.charAt(0) + "`:" + x);
                        break;
                }
            }
        }
        return new MeshView(mesh);
    }

    /**
     * loadStl()
     *
     * Loads an STL file from disk and convert it to a mesh.
     *
     * NOTE: STL files do not contain texture coordinate data, so we generate default UV coordinates.
     * Normals are preserved from the STL file.
     *
     * @param path The file path to load the STL from.
     * @return The mesh of the selected file.
     **/
    public static MeshView loadStl(String path) {
        TriangleMesh mesh = new TriangleMesh(VertexFormat.POINT_NORMAL_TEXCOORD);
        ArrayList<String> lines = readTextFile(path);
        int vertexCount = 0;
        int normalCount = 0;
        
        // First pass: count vertices to allocate texture coordinates
        for (String line : lines) {
            if (line != null && line.contains("vertex")) {
                vertexCount++;
            }
        }
        
        // Generate default texture coordinates (0,0) for each vertex
        // STL files don't contain UV data, so we use a single texture coordinate
        mesh.getTexCoords().addAll(0.0f, 0.0f);
        
        // Parse the file
        for (int x = 0; x < lines.size(); x++) {
            String line = lines.get(x);
            if (line == null) continue;
            
            line = line.trim();
            
            // Skip empty lines and structural keywords
            if (line.isEmpty() || line.startsWith("solid") || line.startsWith("endsolid") ||
                line.startsWith("outer loop") || line.startsWith("endloop") || 
                line.startsWith("endfacet")) {
                continue;
            }
            
            if (line.startsWith("facet normal")) {
                // Parse and store the normal
                String[] normalParts = line.substring("facet normal".length()).trim().split("\\s+");
                if (normalParts.length >= 3) {
                    float nx = Float.parseFloat(normalParts[0]);
                    float ny = Float.parseFloat(normalParts[1]);
                    float nz = Float.parseFloat(normalParts[2]);
                    
                    // Each face has one normal, but we need to duplicate it for each vertex
                    // We'll add the normal three times (once for each vertex of the triangle)
                    for (int i = 0; i < 3; i++) {
                        mesh.getNormals().addAll(nx, ny, nz);
                    }
                }
                
                // Read the three vertices of this face
                int vertexIndex = mesh.getPoints().size() / 3;
                for (int v = 0; v < 3; v++) {
                    // Skip lines until we find a vertex
                    while (x < lines.size() - 1) {
                        x++;
                        line = lines.get(x);
                        if (line != null && line.trim().startsWith("vertex")) {
                            String[] vertexParts = line.trim().substring("vertex".length()).trim().split("\\s+");
                            if (vertexParts.length >= 3) {
                                mesh.getPoints().addAll(
                                    Float.parseFloat(vertexParts[0]),
                                    Float.parseFloat(vertexParts[1]),
                                    Float.parseFloat(vertexParts[2])
                                );
                            }
                            break;
                        }
                    }
                }
                
                // Add face indices
                // Format: vertex/normal/texcoord for each of the 3 vertices
                int normalIndex = normalCount * 3;
                mesh.getFaces().addAll(
                    vertexIndex, normalIndex, 0,         // First vertex
                    vertexIndex + 1, normalIndex + 1, 0, // Second vertex
                    vertexIndex + 2, normalIndex + 2, 0  // Third vertex
                );
                
                normalCount++;
            }
        }
        
        return new MeshView(mesh);
    }

    public static ArrayList<String> readTextFile(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            ArrayList<String> lines = new ArrayList<String>();
            lines.add(br.readLine());
            while (lines.get(lines.size() - 1) != null) {
                lines.add(br.readLine());
            }
            return lines;
        } catch (Exception e) {
            error("readTextFile", "Exception thrown when reading `" + path + "`");
        }
        return null;
    }

    /**
     * warning()
     *
     * Display a warning message for a loading issue.
     *
     * @param mthd The method the failure happened in.
     * @param msg  The message to be displayed as a result.
     **/
    private static void warning(String mthd, String msg) {
        System.out.println(System.currentTimeMillis() + " [??] Loader->" + mthd + "() " + msg);
    }
}
