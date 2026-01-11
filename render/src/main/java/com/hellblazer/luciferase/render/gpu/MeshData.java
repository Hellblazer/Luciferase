/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.render.gpu;

/**
 * Mesh geometry data (vertices and indices).
 */
public record MeshData(float[] vertices, int[] indices) {

    /**
     * Create a cube mesh centered at origin.
     * Vertices include position (3 floats) and normal (3 floats) = 6 floats per vertex.
     * 24 vertices total (4 per face × 6 faces), 36 indices.
     *
     * @param size Half-size of the cube (cube extends from -size to +size)
     * @return Cube mesh data
     */
    public static MeshData createCube(float size) {
        // 24 vertices: 4 per face × 6 faces
        // Format: X, Y, Z, NX, NY, NZ (6 floats per vertex)
        float[] vertices = new float[24 * 6];
        int v = 0;

        // Front face (Z+) - normal 0, 0, 1
        vertices[v++] = -size; vertices[v++] = -size; vertices[v++] = size;  // 0
        vertices[v++] = 0; vertices[v++] = 0; vertices[v++] = 1;
        vertices[v++] = size; vertices[v++] = -size; vertices[v++] = size;   // 1
        vertices[v++] = 0; vertices[v++] = 0; vertices[v++] = 1;
        vertices[v++] = size; vertices[v++] = size; vertices[v++] = size;    // 2
        vertices[v++] = 0; vertices[v++] = 0; vertices[v++] = 1;
        vertices[v++] = -size; vertices[v++] = size; vertices[v++] = size;   // 3
        vertices[v++] = 0; vertices[v++] = 0; vertices[v++] = 1;

        // Back face (Z-) - normal 0, 0, -1
        vertices[v++] = size; vertices[v++] = -size; vertices[v++] = -size;  // 4
        vertices[v++] = 0; vertices[v++] = 0; vertices[v++] = -1;
        vertices[v++] = -size; vertices[v++] = -size; vertices[v++] = -size; // 5
        vertices[v++] = 0; vertices[v++] = 0; vertices[v++] = -1;
        vertices[v++] = -size; vertices[v++] = size; vertices[v++] = -size;  // 6
        vertices[v++] = 0; vertices[v++] = 0; vertices[v++] = -1;
        vertices[v++] = size; vertices[v++] = size; vertices[v++] = -size;   // 7
        vertices[v++] = 0; vertices[v++] = 0; vertices[v++] = -1;

        // Right face (X+) - normal 1, 0, 0
        vertices[v++] = size; vertices[v++] = -size; vertices[v++] = size;   // 8
        vertices[v++] = 1; vertices[v++] = 0; vertices[v++] = 0;
        vertices[v++] = size; vertices[v++] = -size; vertices[v++] = -size;  // 9
        vertices[v++] = 1; vertices[v++] = 0; vertices[v++] = 0;
        vertices[v++] = size; vertices[v++] = size; vertices[v++] = -size;   // 10
        vertices[v++] = 1; vertices[v++] = 0; vertices[v++] = 0;
        vertices[v++] = size; vertices[v++] = size; vertices[v++] = size;    // 11
        vertices[v++] = 1; vertices[v++] = 0; vertices[v++] = 0;

        // Left face (X-) - normal -1, 0, 0
        vertices[v++] = -size; vertices[v++] = -size; vertices[v++] = -size; // 12
        vertices[v++] = -1; vertices[v++] = 0; vertices[v++] = 0;
        vertices[v++] = -size; vertices[v++] = -size; vertices[v++] = size;  // 13
        vertices[v++] = -1; vertices[v++] = 0; vertices[v++] = 0;
        vertices[v++] = -size; vertices[v++] = size; vertices[v++] = size;   // 14
        vertices[v++] = -1; vertices[v++] = 0; vertices[v++] = 0;
        vertices[v++] = -size; vertices[v++] = size; vertices[v++] = -size;  // 15
        vertices[v++] = -1; vertices[v++] = 0; vertices[v++] = 0;

        // Top face (Y+) - normal 0, 1, 0
        vertices[v++] = -size; vertices[v++] = size; vertices[v++] = -size;  // 16
        vertices[v++] = 0; vertices[v++] = 1; vertices[v++] = 0;
        vertices[v++] = -size; vertices[v++] = size; vertices[v++] = size;   // 17
        vertices[v++] = 0; vertices[v++] = 1; vertices[v++] = 0;
        vertices[v++] = size; vertices[v++] = size; vertices[v++] = size;    // 18
        vertices[v++] = 0; vertices[v++] = 1; vertices[v++] = 0;
        vertices[v++] = size; vertices[v++] = size; vertices[v++] = -size;   // 19
        vertices[v++] = 0; vertices[v++] = 1; vertices[v++] = 0;

        // Bottom face (Y-) - normal 0, -1, 0
        vertices[v++] = -size; vertices[v++] = -size; vertices[v++] = size;  // 20
        vertices[v++] = 0; vertices[v++] = -1; vertices[v++] = 0;
        vertices[v++] = -size; vertices[v++] = -size; vertices[v++] = -size; // 21
        vertices[v++] = 0; vertices[v++] = -1; vertices[v++] = 0;
        vertices[v++] = size; vertices[v++] = -size; vertices[v++] = -size;  // 22
        vertices[v++] = 0; vertices[v++] = -1; vertices[v++] = 0;
        vertices[v++] = size; vertices[v++] = -size; vertices[v++] = size;   // 23
        vertices[v++] = 0; vertices[v++] = -1; vertices[v++] = 0;

        // Indices: 2 triangles per face × 6 faces = 12 triangles = 36 indices
        int[] indices = new int[] {
            // Front
            0, 1, 2, 0, 2, 3,
            // Back
            4, 6, 5, 4, 7, 6,
            // Right
            8, 9, 10, 8, 10, 11,
            // Left
            12, 14, 13, 12, 15, 14,
            // Top
            16, 18, 17, 16, 19, 18,
            // Bottom
            20, 21, 22, 20, 22, 23
        };

        return new MeshData(vertices, indices);
    }
}
