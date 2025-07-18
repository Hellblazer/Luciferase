/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the 3D Incremental Voronoi system
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.sentry.packed;

import com.hellblazer.luciferase.geometry.Geometry;
import javax.vecmath.Tuple3f;

/**
 * A lightweight proxy object that provides access to a tetrahedron in the packed grid.
 * This class holds only an index and delegates all data access to the PackedGrid.
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class TetrahedronProxy {
    
    protected final PackedGrid grid;
    protected final int index;
    
    /**
     * Create a proxy for a tetrahedron at the given index
     */
    public TetrahedronProxy(PackedGrid grid, int index) {
        this.grid = grid;
        this.index = index;
    }
    
    /**
     * Get vertex A index
     */
    public int a() {
        return grid.getVertex(index, 0);
    }
    
    /**
     * Get vertex B index
     */
    public int b() {
        return grid.getVertex(index, 1);
    }
    
    /**
     * Get vertex C index
     */
    public int c() {
        return grid.getVertex(index, 2);
    }
    
    /**
     * Get vertex D index
     */
    public int d() {
        return grid.getVertex(index, 3);
    }
    
    /**
     * Get neighbor opposite vertex A (face 0)
     */
    public TetrahedronProxy neighborA() {
        int n = grid.getNeighbor(index, 0);
        return n == PackedGrid.INVALID_INDEX ? null : new TetrahedronProxy(grid, n);
    }
    
    /**
     * Get neighbor opposite vertex B (face 1)
     */
    public TetrahedronProxy neighborB() {
        int n = grid.getNeighbor(index, 1);
        return n == PackedGrid.INVALID_INDEX ? null : new TetrahedronProxy(grid, n);
    }
    
    /**
     * Get neighbor opposite vertex C (face 2)
     */
    public TetrahedronProxy neighborC() {
        int n = grid.getNeighbor(index, 2);
        return n == PackedGrid.INVALID_INDEX ? null : new TetrahedronProxy(grid, n);
    }
    
    /**
     * Get neighbor opposite vertex D (face 3)
     */
    public TetrahedronProxy neighborD() {
        int n = grid.getNeighbor(index, 3);
        return n == PackedGrid.INVALID_INDEX ? null : new TetrahedronProxy(grid, n);
    }
    
    /**
     * Get neighbor by face index
     */
    public TetrahedronProxy getNeighbor(int face) {
        int n = grid.getNeighbor(index, face);
        return n == PackedGrid.INVALID_INDEX ? null : new TetrahedronProxy(grid, n);
    }
    
    /**
     * Test orientation of a point with respect to face opposite vertex A
     * @return > 0 if positive, < 0 if negative, 0 if coplanar
     */
    public double orientationWrtA(Tuple3f query) {
        // Face opposite A contains vertices CBD (matching Tetrahedron.orientationWrtCBD)
        return orientation(query, c(), b(), d());
    }
    
    /**
     * Test orientation of a point with respect to face opposite vertex B
     * @return > 0 if positive, < 0 if negative, 0 if coplanar
     */
    public double orientationWrtB(Tuple3f query) {
        // Face opposite B contains vertices DAC (matching Tetrahedron.orientationWrtDAC)
        return orientation(query, d(), a(), c());
    }
    
    /**
     * Test orientation of a point with respect to face opposite vertex C
     * @return > 0 if positive, < 0 if negative, 0 if coplanar
     */
    public double orientationWrtC(Tuple3f query) {
        // Face opposite C contains vertices ADB (matching Tetrahedron.orientationWrtADB)
        return orientation(query, a(), d(), b());
    }
    
    /**
     * Test orientation of a point with respect to face opposite vertex D
     * @return > 0 if positive, < 0 if negative, 0 if coplanar
     */
    public double orientationWrtD(Tuple3f query) {
        // Face opposite D contains vertices BCA (matching Tetrahedron.orientationWrtBCA)
        return orientation(query, b(), c(), a());
    }
    
    /**
     * Test orientation of a point with respect to a face
     * @param face the face index (0=opposite A, 1=opposite B, etc.)
     * @return > 0 if positive, < 0 if negative, 0 if coplanar
     */
    public double orientationWrt(int face, Tuple3f query) {
        switch (face) {
            case 0: return orientationWrtA(query);
            case 1: return orientationWrtB(query);
            case 2: return orientationWrtC(query);
            case 3: return orientationWrtD(query);
            default: throw new IllegalArgumentException("Invalid face: " + face);
        }
    }
    
    /**
     * Helper method to compute orientation
     */
    private double orientation(Tuple3f query, int v0, int v1, int v2) {
        float[] p0 = new float[3];
        float[] p1 = new float[3];
        float[] p2 = new float[3];
        
        grid.getVertexCoords(v0, p0);
        grid.getVertexCoords(v1, p1);
        grid.getVertexCoords(v2, p2);
        
        return Geometry.leftOfPlaneFast(
            p0[0], p0[1], p0[2],
            p1[0], p1[1], p1[2],
            p2[0], p2[1], p2[2],
            query.x, query.y, query.z
        );
    }
    
    /**
     * Check if this tetrahedron is valid (not deleted)
     */
    public boolean isValid() {
        return grid.isValidTetrahedron(index);
    }
    
    /**
     * Get the index of this tetrahedron
     */
    public int getIndex() {
        return index;
    }
    
    /**
     * Get vertex by ordinal (0-3)
     */
    public int getVertex(int ordinal) {
        return grid.getVertex(index, ordinal);
    }
    
    /**
     * Get all four vertex indices
     */
    public int[] getVertices() {
        int[] verts = new int[4];
        grid.getTetrahedronVertices(index, verts);
        return verts;
    }
    
    /**
     * Check if this tetrahedron contains a specific vertex
     */
    public boolean hasVertex(int vertexIndex) {
        return a() == vertexIndex || b() == vertexIndex || 
               c() == vertexIndex || d() == vertexIndex;
    }
    
    /**
     * Get the ordinal (0-3) of a vertex in this tetrahedron
     * @return 0-3 if found, -1 if not found
     */
    public int ordinalOf(int vertexIndex) {
        if (a() == vertexIndex) return 0;
        if (b() == vertexIndex) return 1;
        if (c() == vertexIndex) return 2;
        if (d() == vertexIndex) return 3;
        return -1;
    }
    
    /**
     * Get the face index that contains three vertices
     */
    public int findFaceWithVertices(int v0, int v1, int v2) {
        return grid.findFaceWithVertices(index, v0, v1, v2);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof TetrahedronProxy)) return false;
        TetrahedronProxy other = (TetrahedronProxy) obj;
        return index == other.index && grid == other.grid;
    }
    
    @Override
    public int hashCode() {
        return Integer.hashCode(index);
    }
    
    @Override
    public String toString() {
        if (!isValid()) {
            return "TetrahedronProxy{deleted, index=" + index + "}";
        }
        return "TetrahedronProxy{index=" + index + 
               ", vertices=[" + a() + "," + b() + "," + c() + "," + d() + "]}";
    }
}